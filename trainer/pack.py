"""Packs the text training data TrainingDataRun writes into shuffled shards of fixed-size records.

Each input line is "<fen> | <score> | <result>", the score in centipawns and the result 1.0, 0.5 or
0.0, both from White's point of view. Every position becomes one 32-byte record (all values
little-endian):

    bytes 0-7    uint64  occupancy, bit s set for each occupied square s (a1 = 0, ... h8 = 63)
    bytes 8-23   uint8   one 4-bit piece code per occupied square in ascending square order, the
                         first in the low half of byte 8; code = colour * 8 + type, colour 0 for
                         White, type numbered pawn 0 ... king 5 as in nnue.py; unused codes are 0
    bytes 24-25  int16   the score from the side to move's view, clamped to +-32000
    byte 26      uint8   the result from the side to move's view in half points: 0, 1 or 2
    byte 27      uint8   1 if White is to move, otherwise 0
    bytes 28-31          zero

The output directory receives shard-<k>.bin files of records and nothing else. Each position goes
to a shard chosen at random and each shard is then put in a random order, so the shards together
are one random permutation of every position read, and any shard on its own is a random sample.

Lines that cannot be read, positions with more than 32 pieces, and a last line without its line
break are counted and skipped. Only as many bytes as each file held when packing started are read,
so a file still being appended to can be packed. Parsing runs in --workers processes, each writing
its share of every shard to a part file under <out>/parts, which is removed once the shards are
written.

Usage: python trainer/pack.py data/packed data/gen-4/chunk-4.txt data/gen-4/chunk-5.txt [options]
"""

import argparse
import math
import multiprocessing
import os
import shutil
import struct
import time

import numpy as np

RECORD_BYTES = 32
MAX_PIECES = 32
SCORE_LIMIT = 32000
PIECE_TYPES = "pnbrqk"
RESULT_HALF_POINTS = {"1.0": 2, "0.5": 1, "0.0": 0}
RANGE_BYTES = 1 << 26
TEXT_BYTES_PER_SHARD = 1 << 30
PROGRESS_RANGES = 16
TAIL = struct.Struct("<hBB4x")


def encode(line):
    """Returns the record of one text line, or None if the line cannot be read."""
    parts = line.split(" | ")
    try:
        fen, centipawns, outcome = parts[0], int(parts[1]), RESULT_HALF_POINTS[parts[2].strip()]
        placement, side = fen.split(" ", 2)[:2]
    except (IndexError, KeyError, ValueError):
        return None
    if side not in ("w", "b"):
        return None
    pieces = []
    rank = 7
    file = 0
    for symbol in placement:
        if symbol == "/":
            rank -= 1
            file = 0
        elif symbol.isdigit():
            file += int(symbol)
        else:
            piece_type = PIECE_TYPES.find(symbol.lower())
            if piece_type < 0 or not 0 <= rank < 8 or not 0 <= file < 8:
                return None
            pieces.append((rank * 8 + file, (0 if symbol.isupper() else 8) + piece_type))
            file += 1
    if len(pieces) > MAX_PIECES:
        return None
    pieces.sort()
    occupancy = 0
    codes = bytearray(16)
    for i, (square, code) in enumerate(pieces):
        if occupancy >> square & 1:
            return None
        occupancy |= 1 << square
        codes[i >> 1] |= code << (4 * (i & 1))
    white_to_move = side == "w"
    centipawns = max(-SCORE_LIMIT, min(SCORE_LIMIT, centipawns))
    return (occupancy.to_bytes(8, "little") + bytes(codes) +
            TAIL.pack(centipawns if white_to_move else -centipawns,
                      outcome if white_to_move else 2 - outcome, 1 if white_to_move else 0))


def split_ranges(paths):
    """Returns (path, start, end, size) byte ranges of about RANGE_BYTES covering every file, where
    size is the file's length when this was called."""
    ranges = []
    for path in paths:
        size = os.path.getsize(path)
        for start in range(0, size, RANGE_BYTES):
            ranges.append((path, start, min(start + RANGE_BYTES, size), size))
    return ranges


def read_range(path, start, end, size):
    """Yields the complete lines that begin in [start, end) of a file and end within its first size
    bytes, decoded as ASCII, and None for each line that is cut off."""
    with open(path, "rb") as file:
        if start > 0:
            file.seek(start - 1)
            file.readline()
        position = file.tell()
        while position < end:
            line = file.readline()
            if not line:
                return
            position += len(line)
            if position > size or not line.endswith(b"\n"):
                yield None
                return
            yield line.decode("ascii", "replace")


def pack_ranges(worker, ranges, parts, shards, seed):
    """Encodes the lines of the given ranges, sending each record to a random shard's part file of
    this worker. Returns the number of records written and of lines skipped."""
    rng = np.random.default_rng([seed, worker])
    handles = [open(os.path.join(parts, f"w{worker:03d}-s{shard:04d}.bin"), "wb")
               for shard in range(shards)]
    written = 0
    skipped = 0
    try:
        for done, (path, start, end, size) in enumerate(ranges, 1):
            records = []
            for line in read_range(path, start, end, size):
                record = None if line is None else encode(line)
                if record is None:
                    skipped += 1
                else:
                    records.append(record)
            if records:
                block = np.frombuffer(b"".join(records), dtype=np.uint8).reshape(-1, RECORD_BYTES)
                targets = rng.integers(shards, size=len(block))
                order = np.argsort(targets, kind="stable")
                bounds = np.searchsorted(targets[order], np.arange(shards + 1))
                for shard in range(shards):
                    if bounds[shard] < bounds[shard + 1]:
                        block[order[bounds[shard]:bounds[shard + 1]]].tofile(handles[shard])
                written += len(block)
            if done % PROGRESS_RANGES == 0 or done == len(ranges):
                print(f"worker {worker}: {done} of {len(ranges)} ranges, {written:,} positions",
                      flush=True)
    finally:
        for handle in handles:
            handle.close()
    return written, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("out", help="the directory to write the shards to, which must hold no shards")
    parser.add_argument("text", nargs="+", help="the text files TrainingDataRun wrote")
    parser.add_argument("--shards", type=int, default=0,
                        help="the number of shards, default one per GiB of text")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2),
                        help="parsing processes, default all but two hardware threads")
    parser.add_argument("--seed", type=int, default=1, help="seed of the shuffle, default 1")
    arguments = parser.parse_args()

    os.makedirs(arguments.out, exist_ok=True)
    if any(name.startswith("shard-") for name in os.listdir(arguments.out)):
        parser.error(f"{arguments.out} already holds shards")
    ranges = split_ranges(arguments.text)
    total_bytes = sum(os.path.getsize(path) for path in arguments.text)
    shards = arguments.shards or max(1, math.ceil(total_bytes / TEXT_BYTES_PER_SHARD))
    workers = max(1, min(arguments.workers, len(ranges)))
    parts = os.path.join(arguments.out, "parts")
    os.makedirs(parts, exist_ok=True)
    print(f"{len(arguments.text)} files, {total_bytes / 2**30:.1f} GiB in {len(ranges)} ranges, "
          f"{workers} workers, {shards} shards, seed {arguments.seed}", flush=True)

    start = time.time()
    tasks = [(worker, ranges[worker::workers], parts, shards, arguments.seed)
             for worker in range(workers)]
    with multiprocessing.Pool(workers) as pool:
        counts = pool.starmap(pack_ranges, tasks)
    written = sum(count[0] for count in counts)
    skipped = sum(count[1] for count in counts)
    print(f"parsed {written:,} positions, {skipped:,} lines skipped, {time.time() - start:.0f} s",
          flush=True)

    rng = np.random.default_rng([arguments.seed, workers])
    for shard in range(shards):
        names = [os.path.join(parts, f"w{worker:03d}-s{shard:04d}.bin") for worker in range(workers)]
        records = np.concatenate([np.fromfile(name, dtype=np.uint8).reshape(-1, RECORD_BYTES)
                                  for name in names])
        records[rng.permutation(len(records))].tofile(
            os.path.join(arguments.out, f"shard-{shard:04d}.bin"))
        for name in names:
            os.remove(name)
    shutil.rmtree(parts)
    print(f"{written:,} positions in {shards} shards written to {arguments.out}, "
          f"{time.time() - start:.0f} s")


if __name__ == "__main__":
    main()
