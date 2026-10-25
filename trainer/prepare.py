"""Converts the text training data TrainingDataRun writes into the binary arrays train.py reads.

Each input line is "<fen> | <score> | <result>", the score in centipawns and the result 1.0, 0.5 or
0.0, both from White's point of view. The output directory receives four .npy files, one row per
position:

    features.npy  int16 (N, 32)  White perspective feature indices (see nnue.py), padded with PAD
    stm.npy       bool  (N,)     True if White is to move
    score.npy     int16 (N,)     the score from the side to move's view, clamped to +-32000
    result.npy    int8  (N,)     the result from the side to move's view in half points: 0, 1 or 2

Lines that cannot be read are counted and skipped. Only as many lines as the file held when it was
first counted are read, so a file still being appended to can be converted.

Usage: python trainer/prepare.py data/training.txt data/training
"""

import argparse
import os
import time

import numpy as np

from nnue import PAD, white_features

MAX_PIECES = 32
SCORE_LIMIT = 32000
RESULT_HALF_POINTS = {"1.0": 2, "0.5": 1, "0.0": 0}
PROGRESS_INTERVAL = 1_000_000


def count_lines(path):
    """Returns the number of lines in a file."""
    with open(path, "rb") as file:
        return sum(chunk.count(b"\n") for chunk in iter(lambda: file.read(1 << 24), b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("text", help="the text file TrainingDataRun wrote")
    parser.add_argument("out", help="the directory to write the arrays to")
    arguments = parser.parse_args()

    capacity = count_lines(arguments.text)
    os.makedirs(arguments.out, exist_ok=True)
    features = np.full((capacity, MAX_PIECES), PAD, dtype=np.int16)
    stm = np.zeros(capacity, dtype=bool)
    score = np.zeros(capacity, dtype=np.int16)
    result = np.zeros(capacity, dtype=np.int8)

    rows = 0
    skipped = 0
    start = time.time()
    with open(arguments.text, encoding="ascii") as file:
        for line_number, line in enumerate(file):
            if line_number == capacity:
                break
            parts = line.split(" | ")
            try:
                fen, centipawns, outcome = parts[0], int(parts[1]), RESULT_HALF_POINTS[parts[2].strip()]
                placement, side = fen.split(" ", 2)[:2]
                pieces = white_features(placement)
            except (IndexError, KeyError, ValueError):
                skipped += 1
                continue
            if len(pieces) > MAX_PIECES or side not in ("w", "b"):
                skipped += 1
                continue
            white_to_move = side == "w"
            features[rows, :len(pieces)] = pieces
            stm[rows] = white_to_move
            centipawns = max(-SCORE_LIMIT, min(SCORE_LIMIT, centipawns))
            score[rows] = centipawns if white_to_move else -centipawns
            result[rows] = outcome if white_to_move else 2 - outcome
            rows += 1
            if rows % PROGRESS_INTERVAL == 0:
                print(f"{rows:,} positions, {time.time() - start:.0f} s", flush=True)

    np.save(os.path.join(arguments.out, "features.npy"), features[:rows])
    np.save(os.path.join(arguments.out, "stm.npy"), stm[:rows])
    np.save(os.path.join(arguments.out, "score.npy"), score[:rows])
    np.save(os.path.join(arguments.out, "result.npy"), result[:rows])
    print(f"{rows:,} positions written to {arguments.out}, {skipped:,} lines skipped, "
          f"{time.time() - start:.0f} s")


if __name__ == "__main__":
    main()
