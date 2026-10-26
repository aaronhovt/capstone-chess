"""Trains the evaluation network on the arrays prepare.py writes and exports it quantised.

The target of each position blends the search score and the game result, both from the side to
move's view: (1 - wdl) * sigmoid(score / SCALE) + wdl * result, with the result as 1, 0.5 or 0. The
loss is the mean squared difference between sigmoid(output) and the target, where output * SCALE is
the network's evaluation in centipawns. A random share of the positions, fixed by the seed, is held
out and its loss reported after every epoch.

Training uses Adam with a learning rate that falls along a cosine from --lr to --lr * --final-lr
over the whole run, and clips the weights to their quantised range after every step. The rate is
computed from the step number and --epochs, so a run resumed with more epochs follows the longer
schedule from where it stands. The data is kept on the GPU when it and the training index arrays
fit in the GPU's free memory with GPU_HEADROOM to spare, and in main memory otherwise.

The output directory receives log.txt (one line per epoch), checkpoint.pt (the latest epoch, from
which --resume continues), and epoch-<n>.nnue (the quantised network, see nnue.py) every
--save-every epochs and after the last.

Usage: python trainer/train.py data/training data/nets/first [options]
"""

import argparse
import math
import os
import time

import numpy as np
import torch

import nnue

# Positions whose float and quantised evaluations are printed with each saved network.
CHECK_POSITIONS = [
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
    "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    "4k3/8/8/8/8/8/8/R3K3 w - - 0 1",
]

# GPU memory left free for the network, its optimiser and the tensors of one batch.
GPU_HEADROOM = 1 << 30


def load_data(directory, device):
    """Loads the arrays prepare.py wrote as tensors on the given device."""
    arrays = {name: np.load(os.path.join(directory, name + ".npy"))
              for name in ("features", "stm", "score", "result")}
    return {name: torch.from_numpy(array).to(device) for name, array in arrays.items()}


def choose_device(directory):
    """Returns the device the data is kept on: the GPU if its free memory holds the data and two
    int32 index arrays of one entry per position with GPU_HEADROOM to spare."""
    if not torch.cuda.is_available():
        return torch.device("cpu")
    size = sum(os.path.getsize(os.path.join(directory, name + ".npy"))
               for name in ("features", "stm", "score", "result"))
    positions = os.path.getsize(os.path.join(directory, "stm.npy"))
    free, _ = torch.cuda.mem_get_info()
    needed = size + 2 * 4 * positions + GPU_HEADROOM
    return torch.device("cuda") if needed < free else torch.device("cpu")


def batch_loss(network, data, indices, wdl, device):
    """Returns the mean loss of the network over the positions at the given indices."""
    white = data["features"][indices].to(device, non_blocking=True).long()
    white_to_move = data["stm"][indices].to(device, non_blocking=True)
    score = data["score"][indices].to(device, non_blocking=True).float()
    result = data["result"][indices].to(device, non_blocking=True).float() / 2
    target = (1 - wdl) * torch.sigmoid(score / nnue.SCALE) + wdl * result
    prediction = torch.sigmoid(network(white, white_to_move))
    return torch.mean((prediction - target) ** 2)


def validation_loss(network, data, indices, wdl, batch_size, device):
    """Returns the mean loss of the network over the held out positions."""
    network.eval()
    total = 0.0
    with torch.no_grad():
        for start in range(0, len(indices), batch_size):
            batch = indices[start:start + batch_size]
            total += float(batch_loss(network, data, batch, wdl, device)) * len(batch)
    network.train()
    return total / len(indices)


def learning_rate(step, total_steps, initial, final_fraction):
    """Returns the learning rate for a step: a cosine from initial down to initial * final_fraction
    over total_steps, held at the final rate after that."""
    progress = min(1.0, step / total_steps)
    final = initial * final_fraction
    return final + (initial - final) * 0.5 * (1 + math.cos(math.pi * progress))


def save_network(network, path):
    """Writes the quantised network and prints its float and quantised check evaluations."""
    nnue.write_network(path, network)
    parameters = nnue.read_network(path)
    for fen in CHECK_POSITIONS:
        print(f"  float {nnue.evaluate_float(network, fen):8.1f}  "
              f"quantised {nnue.evaluate_quantised(parameters, fen):6d}  {fen}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("data", help="the directory prepare.py wrote")
    parser.add_argument("out", help="the directory to write the log, checkpoint and networks to")
    parser.add_argument("--epochs", type=int, default=40, help="passes over the data, default 40")
    parser.add_argument("--batch", type=int, default=16384, help="positions per step, default 16384")
    parser.add_argument("--lr", type=float, default=1e-3, help="starting learning rate, default 0.001")
    parser.add_argument("--final-lr", type=float, default=0.01,
                        help="final learning rate as a fraction of --lr, default 0.01")
    parser.add_argument("--wdl", type=float, default=0.25,
                        help="weight of the game result in the target, default 0.25")
    parser.add_argument("--hidden", type=int, default=nnue.HIDDEN,
                        help=f"accumulator size, default {nnue.HIDDEN}")
    parser.add_argument("--validation", type=float, default=0.01,
                        help="share of positions held out, default 0.01")
    parser.add_argument("--save-every", type=int, default=10,
                        help="epochs between saved networks, default 10")
    parser.add_argument("--seed", type=int, default=1, help="seed of the split and shuffles, default 1")
    parser.add_argument("--resume", action="store_true", help="continue from out/checkpoint.pt")
    arguments = parser.parse_args()

    torch.manual_seed(arguments.seed)
    os.makedirs(arguments.out, exist_ok=True)
    compute = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    storage = choose_device(arguments.data)
    data = load_data(arguments.data, storage)
    count = data["features"].shape[0]

    split = torch.randperm(count, generator=torch.Generator().manual_seed(arguments.seed),
                           dtype=torch.int32)
    held_out = max(1, int(count * arguments.validation))
    validation_indices = split[:held_out].to(storage)
    training_indices = split[held_out:].to(storage)
    steps_per_epoch = math.ceil(len(training_indices) / arguments.batch)
    total_steps = steps_per_epoch * arguments.epochs

    network = nnue.Network(arguments.hidden).to(compute)
    optimizer = torch.optim.Adam(network.parameters(), lr=arguments.lr)
    first_epoch = 1
    step_number = 0
    checkpoint_path = os.path.join(arguments.out, "checkpoint.pt")
    if arguments.resume:
        checkpoint = torch.load(checkpoint_path, map_location=compute)
        network.load_state_dict(checkpoint["network"])
        optimizer.load_state_dict(checkpoint["optimizer"])
        first_epoch = checkpoint["epoch"] + 1
        step_number = checkpoint["step"]

    print(f"{count:,} positions ({held_out:,} held out) on {storage}, training on {compute}, "
          f"hidden {arguments.hidden}, batch {arguments.batch}, {steps_per_epoch} steps per epoch, "
          f"wdl {arguments.wdl}, lr {arguments.lr} to {arguments.lr * arguments.final_lr}", flush=True)

    with open(os.path.join(arguments.out, "log.txt"), "a") as log:
        for epoch in range(first_epoch, arguments.epochs + 1):
            start = time.time()
            order = torch.randperm(len(training_indices), device=storage, dtype=torch.int32)
            total = 0.0
            for step in range(steps_per_epoch):
                rate = learning_rate(step_number, total_steps, arguments.lr, arguments.final_lr)
                for group in optimizer.param_groups:
                    group["lr"] = rate
                batch = training_indices[order[step * arguments.batch:(step + 1) * arguments.batch]]
                loss = batch_loss(network, data, batch, arguments.wdl, compute)
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                optimizer.step()
                network.clip_weights()
                step_number += 1
                total += loss.detach()
            training = float(total) / steps_per_epoch
            validation = validation_loss(network, data, validation_indices, arguments.wdl,
                                         arguments.batch, compute)
            line = (f"epoch {epoch:3d}  training {training:.6f}  validation {validation:.6f}  "
                    f"lr {rate:.2e}  {time.time() - start:.1f} s")
            print(line, flush=True)
            log.write(line + "\n")
            log.flush()
            torch.save({"network": network.state_dict(), "optimizer": optimizer.state_dict(),
                        "epoch": epoch, "step": step_number, "hidden": arguments.hidden},
                       checkpoint_path)
            if epoch % arguments.save_every == 0 or epoch == arguments.epochs:
                path = os.path.join(arguments.out, f"epoch-{epoch}.nnue")
                print(f"saved {path}")
                save_network(network, path)


if __name__ == "__main__":
    main()
