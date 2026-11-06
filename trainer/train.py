"""Trains the evaluation network on the positions prepare.py or pack.py writes and exports it quantised.

The target of each position blends the search score and the game result, both from the side to
move's view: (1 - wdl) * sigmoid(score / SCALE) + wdl * result, with the result as 1, 0.5 or 0. The
loss is the mean squared difference between sigmoid(output) and the target, where output * SCALE is
the network's evaluation in centipawns. The loss over the held out positions is reported after
every epoch: those of the directory of shards --validation-data names, or otherwise a random share
of the training arrays, fixed by the seed.

Training uses Adam with a learning rate that falls along a cosine from --lr to --lr * --final-lr
over the whole run, and clips the weights to their quantised range after every step. The rate is
computed from the step number and --epochs, so a run resumed with more epochs follows the longer
schedule from where it stands.

The data directory holds either the arrays prepare.py writes or the shards pack.py writes. Arrays
are loaded whole, onto the GPU when they and the training index arrays fit in the GPU's free memory
with GPU_HEADROOM to spare, and into main memory otherwise. Shards are streamed: each epoch visits
the shards in a random order, loading one at a time onto the training device, and visits each
shard's positions in a random order, so the data may be larger than either memory. The last batch of
each shard may be smaller than --batch. Shards need --validation-data.

The output directory receives log.txt (one line per epoch), checkpoint.pt (the latest epoch, from
which --resume continues), and epoch-<n>.nnue (the quantised network, see nnue.py) every
--save-every epochs and after the last.

Usage: python trainer/train.py data/training data/nets/first [options]
       python trainer/train.py data/packed data/nets/fourth --validation-data data/packed-validation
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

# The size of one pack.py record in bytes.
RECORD_BYTES = 32

# The number of feature slots of a position, as in prepare.py.
MAX_PIECES = 32

# The names of prepare.py's arrays, in the order a batch holds them.
ARRAY_NAMES = ("features", "stm", "score", "result")


def load_data(directory, device):
    """Loads the arrays prepare.py wrote as tensors on the given device."""
    arrays = {name: np.load(os.path.join(directory, name + ".npy")) for name in ARRAY_NAMES}
    return {name: torch.from_numpy(array).to(device) for name, array in arrays.items()}


def choose_device(directory):
    """Returns the device the data is kept on: the GPU if its free memory holds the data and two
    int32 index arrays of one entry per position with GPU_HEADROOM to spare."""
    if not torch.cuda.is_available():
        return torch.device("cpu")
    size = sum(os.path.getsize(os.path.join(directory, name + ".npy")) for name in ARRAY_NAMES)
    positions = os.path.getsize(os.path.join(directory, "stm.npy"))
    free, _ = torch.cuda.mem_get_info()
    needed = size + 2 * 4 * positions + GPU_HEADROOM
    return torch.device("cuda") if needed < free else torch.device("cpu")


def shard_paths(directory):
    """Returns the paths of the shards pack.py wrote to a directory, in name order."""
    return [os.path.join(directory, name) for name in sorted(os.listdir(directory))
            if name.startswith("shard-") and name.endswith(".bin")]


def decode_records(records):
    """Returns a batch of the features, side to move, score and result of pack.py records.

    records is a uint8 tensor of one record per row. The tensors returned are on its device and hold
    what prepare.py's arrays hold for the same positions, each position's features in ascending
    square order rather than prepare.py's order.
    """
    squares = torch.arange(64, device=records.device)
    occupancy = records[:, :8].long()
    occupied = ((occupancy[:, squares // 8] >> (squares % 8)) & 1).bool()
    codes = records[:, 8:24].long()
    nibbles = torch.stack([codes & 15, codes >> 4], dim=2).reshape(len(records), MAX_PIECES)
    rank = torch.cumsum(occupied, dim=1) - 1
    code = torch.gather(nibbles, 1, rank.clamp(min=0))
    feature = (code >> 3) * 384 + (code & 7) * 64 + squares
    features = torch.full((len(records), MAX_PIECES + 1), nnue.PAD, dtype=torch.long,
                          device=records.device)
    features.scatter_(1, torch.where(occupied, rank, MAX_PIECES), feature)
    score = records[:, 24:26].contiguous().view(torch.int16).squeeze(1)
    return features[:, :MAX_PIECES], records[:, 27].bool(), score, records[:, 26]


def array_batch(data, indices, device):
    """Returns a batch of the positions at the given indices of prepare.py's arrays on a device."""
    return tuple(data[name][indices].to(device, non_blocking=True) for name in ARRAY_NAMES)


class ArrayData:
    """The arrays prepare.py writes, loaded whole, with a random share fixed by the seed held out
    unless holds_out is false."""

    def __init__(self, directory, validation, seed, holds_out):
        self.storage = choose_device(directory)
        self.data = load_data(directory, self.storage)
        total = self.data["features"].shape[0]
        split = torch.randperm(total, generator=torch.Generator().manual_seed(seed),
                               dtype=torch.int32)
        self.held_out = max(1, int(total * validation)) if holds_out else 0
        self.validation_indices = split[:self.held_out].to(self.storage)
        self.training_indices = split[self.held_out:].to(self.storage)
        self.count = len(self.training_indices)

    def steps(self, batch_size):
        """Returns the number of batches in one epoch."""
        return math.ceil(self.count / batch_size)

    def training_batches(self, batch_size, device):
        """Yields one epoch of training batches on a device, in a random order."""
        order = torch.randperm(self.count, device=self.storage, dtype=torch.int32)
        for step in range(self.steps(batch_size)):
            indices = self.training_indices[order[step * batch_size:(step + 1) * batch_size]]
            yield array_batch(self.data, indices, device)

    def validation_batches(self, batch_size, device):
        """Yields the held out positions as batches on a device."""
        for start in range(0, self.held_out, batch_size):
            indices = self.validation_indices[start:start + batch_size]
            yield array_batch(self.data, indices, device)


class ShardData:
    """The shards pack.py writes, streamed one shard at a time. read_seconds accumulates the time
    spent reading shards from disk."""

    def __init__(self, directory):
        self.paths = shard_paths(directory)
        self.sizes = [os.path.getsize(path) // RECORD_BYTES for path in self.paths]
        self.count = sum(self.sizes)
        self.read_seconds = 0.0

    def steps(self, batch_size):
        """Returns the number of batches in one epoch."""
        return sum(math.ceil(size / batch_size) for size in self.sizes)

    def load(self, path, device):
        """Returns the records of one shard as a uint8 tensor on a device, one record per row."""
        start = time.time()
        records = np.fromfile(path, dtype=np.uint8).reshape(-1, RECORD_BYTES)
        self.read_seconds += time.time() - start
        return torch.from_numpy(records).to(device)

    def training_batches(self, batch_size, device):
        """Yields one epoch of training batches on a device: the shards in a random order and the
        positions of each in a random order."""
        for shard in torch.randperm(len(self.paths)).tolist():
            records = self.load(self.paths[shard], device)
            order = torch.randperm(len(records), device=device)
            for start in range(0, len(records), batch_size):
                yield decode_records(records[order[start:start + batch_size]])

    def validation_batches(self, batch_size, device):
        """Yields every position as batches on a device, in shard and record order."""
        for path in self.paths:
            records = self.load(path, device)
            for start in range(0, len(records), batch_size):
                yield decode_records(records[start:start + batch_size])


def batch_loss(network, batch, wdl):
    """Returns the mean loss of the network over a batch on its device."""
    features, white_to_move, score, result = batch
    target = (1 - wdl) * torch.sigmoid(score.float() / nnue.SCALE) + wdl * (result.float() / 2)
    prediction = torch.sigmoid(network(features.long(), white_to_move))
    return torch.mean((prediction - target) ** 2)


def validation_loss(network, batches, wdl):
    """Returns the mean loss of the network over batches of held out positions."""
    network.eval()
    total = 0.0
    count = 0
    with torch.no_grad():
        for batch in batches:
            total += float(batch_loss(network, batch, wdl)) * len(batch[0])
            count += len(batch[0])
    network.train()
    return total / count


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
    parser.add_argument("data", help="the directory prepare.py or pack.py wrote")
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
    parser.add_argument("--buckets", type=int, default=nnue.BUCKETS,
                        help=f"output buckets chosen by piece count, a divisor of "
                             f"{nnue.MAX_PIECES}, default {nnue.BUCKETS}")
    parser.add_argument("--validation", type=float, default=0.01,
                        help="share of the arrays held out without --validation-data, default 0.01")
    parser.add_argument("--validation-data",
                        help="a directory pack.py wrote whose positions are held out instead")
    parser.add_argument("--save-every", type=int, default=10,
                        help="epochs between saved networks, default 10")
    parser.add_argument("--seed", type=int, default=1, help="seed of the split and shuffles, default 1")
    parser.add_argument("--resume", action="store_true", help="continue from out/checkpoint.pt")
    arguments = parser.parse_args()

    try:
        nnue.check_buckets(arguments.buckets)
    except ValueError as error:
        parser.error(str(error))
    torch.manual_seed(arguments.seed)
    os.makedirs(arguments.out, exist_ok=True)
    compute = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if shard_paths(arguments.data):
        if arguments.validation_data is None:
            parser.error("training from shards needs --validation-data")
        training = ShardData(arguments.data)
        source = f"streamed from {len(training.paths)} shards"
    else:
        training = ArrayData(arguments.data, arguments.validation, arguments.seed,
                             arguments.validation_data is None)
        source = f"on {training.storage}"
    if arguments.validation_data is None:
        held_out = training
        held_out_count = training.held_out
    else:
        held_out = ShardData(arguments.validation_data)
        held_out_count = held_out.count
        if held_out_count == 0:
            parser.error(f"{arguments.validation_data} holds no shards")
    steps_per_epoch = training.steps(arguments.batch)
    total_steps = steps_per_epoch * arguments.epochs

    network = nnue.Network(arguments.hidden, arguments.buckets).to(compute)
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

    print(f"{training.count:,} training positions {source}, {held_out_count:,} held out, "
          f"training on {compute}, hidden {arguments.hidden}, buckets {arguments.buckets}, "
          f"batch {arguments.batch}, "
          f"{steps_per_epoch} steps per epoch, wdl {arguments.wdl}, "
          f"lr {arguments.lr} to {arguments.lr * arguments.final_lr}", flush=True)

    with open(os.path.join(arguments.out, "log.txt"), "a") as log:
        for epoch in range(first_epoch, arguments.epochs + 1):
            start = time.time()
            if isinstance(training, ShardData):
                training.read_seconds = 0.0
            total = 0.0
            for batch in training.training_batches(arguments.batch, compute):
                rate = learning_rate(step_number, total_steps, arguments.lr, arguments.final_lr)
                for group in optimizer.param_groups:
                    group["lr"] = rate
                loss = batch_loss(network, batch, arguments.wdl)
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                optimizer.step()
                network.clip_weights()
                step_number += 1
                total += loss.detach()
            training_loss = float(total) / steps_per_epoch
            validation = validation_loss(network,
                                         held_out.validation_batches(arguments.batch, compute),
                                         arguments.wdl)
            line = (f"epoch {epoch:3d}  training {training_loss:.6f}  validation {validation:.6f}  "
                    f"lr {rate:.2e}  {time.time() - start:.1f} s")
            if isinstance(training, ShardData):
                line += f"  (reading {training.read_seconds:.1f} s)"
            print(line, flush=True)
            log.write(line + "\n")
            log.flush()
            torch.save({"network": network.state_dict(), "optimizer": optimizer.state_dict(),
                        "epoch": epoch, "step": step_number, "hidden": arguments.hidden,
                        "buckets": arguments.buckets},
                       checkpoint_path)
            if epoch % arguments.save_every == 0 or epoch == arguments.epochs:
                path = os.path.join(arguments.out, f"epoch-{epoch}.nnue")
                print(f"saved {path}")
                save_network(network, path)


if __name__ == "__main__":
    main()
