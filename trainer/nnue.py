"""The evaluation network: its input features, its PyTorch model, its quantised file format, and
an integer reference evaluation that the engine's implementation must reproduce exactly.

Architecture: 768 inputs -> HIDDEN accumulator per perspective (one weight matrix shared by both
perspectives) -> SCReLU -> the side to move's accumulator followed by the other side's -> 1 output.

Input features. Each piece sets one feature in each perspective's accumulator. For a perspective p
and a piece of colour c, type t and square s:

    index = (0 if c == p else 1) * 384 + t * 64 + (s if p is White else s ^ 56)

Types are numbered pawn 0, knight 1, bishop 2, rook 3, queen 4, king 5. Squares are numbered from
a1 = 0, b1 = 1, ... h1 = 7, a2 = 8, ... h8 = 63, so s ^ 56 mirrors a square vertically. The engine
numbers squares from a8 = 0, so an engine square e is square e ^ 56 here.

Output. The network's output, multiplied by SCALE, is the evaluation in centipawns from the point of
view of the side to move.

Quantised file (all values little-endian):

    bytes 0-3   magic "CCNN"
    int32       format version, 1
    int32       input size, 768
    int32       hidden size, HIDDEN
    int32       QA
    int32       QB
    int32       SCALE
    int16       feature weights, 768 rows of HIDDEN, row = feature index, each round(w * QA)
    int16       feature biases, HIDDEN, each round(b * QA)
    int16       output weights, 2 * HIDDEN, the side to move's HIDDEN first, each round(w * QB)
    int32       output bias, round(b * QA * QB)

Integer evaluation (see evaluate_quantised): each accumulator is the feature bias plus the sum of
the rows of its active features. With v = clamp(accumulator, 0, QA) for each of the 2 * HIDDEN
values, sum = sum of v * v * output weight, each product fitting in 32 bits and the sum needing
64; then
eval = (sum / QA + output bias) * SCALE / (QA * QB), where both divisions truncate toward zero.
"""

import struct

import numpy as np
import torch

INPUTS = 768
HIDDEN = 256
QA = 255
QB = 1024
SCALE = 400

# The feature index that stands for no piece, used to pad a position's feature list.
PAD = INPUTS

# The largest magnitude a weight may take, so that an accumulator of 32 pieces stays inside int16
# and a quantised output weight fits in int16.
WEIGHT_CLIP = 1.98

MAGIC = b"CCNN"
VERSION = 1

PIECE_TYPES = "pnbrqk"


def white_features(placement):
    """Returns the White perspective feature index of every piece in a FEN piece placement field."""
    features = []
    rank = 7
    file = 0
    for symbol in placement:
        if symbol == "/":
            rank -= 1
            file = 0
        elif symbol.isdigit():
            file += int(symbol)
        else:
            colour = 0 if symbol.isupper() else 1
            features.append(colour * 384 + PIECE_TYPES.index(symbol.lower()) * 64 + rank * 8 + file)
            file += 1
    return features


def black_features(white):
    """Returns the Black perspective feature indices for White perspective indices, keeping PAD.

    Works on an int tensor or a numpy array of any shape.
    """
    colour = white // 384
    piece_type = (white % 384) // 64
    square = white % 64
    black = (1 - colour) * 384 + piece_type * 64 + (square ^ 56)
    if isinstance(white, torch.Tensor):
        return torch.where(white == PAD, white, black)
    return np.where(white == PAD, white, black)


class Network(torch.nn.Module):
    """The evaluation network in floating point, for training."""

    def __init__(self, hidden=HIDDEN):
        super().__init__()
        self.hidden = hidden
        self.feature_weights = torch.nn.EmbeddingBag(INPUTS + 1, hidden, mode="sum",
                                                     padding_idx=PAD)
        self.feature_bias = torch.nn.Parameter(torch.zeros(hidden))
        self.output = torch.nn.Linear(2 * hidden, 1)
        with torch.no_grad():
            self.feature_weights.weight.uniform_(-0.05, 0.05)
            self.feature_weights.weight[PAD].zero_()

    def forward(self, white, white_to_move):
        """Returns the output for a batch, one value per position, from the side to move's view.

        white is a long tensor of White perspective feature indices, one row per position padded
        with PAD; white_to_move is a bool tensor, one value per position.
        """
        white_accumulator = self.feature_weights(white) + self.feature_bias
        black_accumulator = self.feature_weights(black_features(white)) + self.feature_bias
        mover = torch.where(white_to_move[:, None], white_accumulator, black_accumulator)
        other = torch.where(white_to_move[:, None], black_accumulator, white_accumulator)
        hidden = torch.clamp(torch.cat([mover, other], dim=1), 0.0, 1.0) ** 2
        return self.output(hidden).squeeze(1)

    def clip_weights(self):
        """Clips every weight to the range its quantised form can hold."""
        with torch.no_grad():
            self.feature_weights.weight.clamp_(-WEIGHT_CLIP, WEIGHT_CLIP)
            self.output.weight.clamp_(-WEIGHT_CLIP, WEIGHT_CLIP)


def quantise(network):
    """Returns the network's quantised parameters as numpy arrays.

    The arrays are the feature weights (768 x hidden, int16), feature biases (int16), output
    weights (2 * hidden, int16) and output bias (int64 scalar).
    """
    feature_weights = network.feature_weights.weight.detach().cpu().double().numpy()[:INPUTS]
    feature_bias = network.feature_bias.detach().cpu().double().numpy()
    output_weights = network.output.weight.detach().cpu().double().numpy()[0]
    output_bias = float(network.output.bias.detach().cpu().double().numpy()[0])
    return (np.round(feature_weights * QA).astype(np.int16),
            np.round(feature_bias * QA).astype(np.int16),
            np.round(output_weights * QB).astype(np.int16),
            np.int64(round(output_bias * QA * QB)))


def write_network(path, network):
    """Writes the network to a quantised file in the format the module description gives."""
    feature_weights, feature_bias, output_weights, output_bias = quantise(network)
    with open(path, "wb") as file:
        file.write(MAGIC)
        file.write(struct.pack("<6i", VERSION, INPUTS, network.hidden, QA, QB, SCALE))
        file.write(feature_weights.astype("<i2").tobytes())
        file.write(feature_bias.astype("<i2").tobytes())
        file.write(output_weights.astype("<i2").tobytes())
        file.write(struct.pack("<i", int(output_bias)))


def read_network(path):
    """Reads a quantised file and returns its parameters as quantise returns them.

    Raises ValueError if the file is not a network file of this format.
    """
    with open(path, "rb") as file:
        data = file.read()
    if data[:4] != MAGIC:
        raise ValueError(f"{path} is not a network file")
    version, inputs, hidden, qa, qb, scale = struct.unpack_from("<6i", data, 4)
    if (version, inputs, qa, qb, scale) != (VERSION, INPUTS, QA, QB, SCALE):
        raise ValueError(f"{path} has version {version}, inputs {inputs}, QA {qa}, QB {qb}, "
                         f"scale {scale}, which this trainer does not read")
    offset = 28
    feature_weights = np.frombuffer(data, "<i2", INPUTS * hidden, offset).reshape(INPUTS, hidden)
    offset += INPUTS * hidden * 2
    feature_bias = np.frombuffer(data, "<i2", hidden, offset)
    offset += hidden * 2
    output_weights = np.frombuffer(data, "<i2", 2 * hidden, offset)
    offset += 2 * hidden * 2
    (output_bias,) = struct.unpack_from("<i", data, offset)
    if offset + 4 != len(data):
        raise ValueError(f"{path} is {len(data)} bytes, expected {offset + 4}")
    return feature_weights, feature_bias, output_weights, np.int64(output_bias)


def truncating_divide(numerator, denominator):
    """Divides two integers, truncating toward zero as Java's integer division does."""
    quotient = abs(numerator) // abs(denominator)
    return quotient if (numerator < 0) == (denominator < 0) else -quotient


def evaluate_quantised(parameters, fen):
    """Returns the integer evaluation of a FEN position in centipawns from the side to move's view.

    parameters are as read_network returns them. This is the reference the engine must match.
    """
    feature_weights, feature_bias, output_weights, output_bias = parameters
    hidden = feature_bias.shape[0]
    fields = fen.split()
    white = np.array(white_features(fields[0]), dtype=np.int64)
    black = black_features(white)
    white_accumulator = feature_bias.astype(np.int64) + feature_weights[white].astype(np.int64).sum(0)
    black_accumulator = feature_bias.astype(np.int64) + feature_weights[black].astype(np.int64).sum(0)
    if fields[1] == "w":
        mover, other = white_accumulator, black_accumulator
    else:
        mover, other = black_accumulator, white_accumulator
    total = 0
    for accumulator, weights in ((mover, output_weights[:hidden]), (other, output_weights[hidden:])):
        clipped = np.clip(accumulator, 0, QA)
        total += int((clipped * clipped * weights.astype(np.int64)).sum())
    return truncating_divide((truncating_divide(total, QA) + int(output_bias)) * SCALE, QA * QB)


def evaluate_float(network, fen):
    """Returns the floating point network's evaluation of a FEN position in centipawns from the
    side to move's view."""
    fields = fen.split()
    device = next(network.parameters()).device
    white = torch.tensor([white_features(fields[0])], dtype=torch.long, device=device)
    white_to_move = torch.tensor([fields[1] == "w"], device=device)
    with torch.no_grad():
        return float(network(white, white_to_move)[0]) * SCALE


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Evaluate FEN positions with a quantised network.")
    parser.add_argument("network", help="the quantised network file")
    parser.add_argument("fen", nargs="+", help="a FEN position, quoted")
    arguments = parser.parse_args()
    loaded = read_network(arguments.network)
    for position in arguments.fen:
        print(f"{evaluate_quantised(loaded, position):6d}  {position}")
