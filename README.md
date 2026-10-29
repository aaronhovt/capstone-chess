# Capstone Chess

A chess engine written in Java. It began as a STEM Academy capstone project at Essex High School
between January 2023 and May 2025 and has been developed continuously since. The repository holds
24,212 lines of Java and Javadoc across 57 source files and carries three front ends: a Swing
interface for playing the engine by hand, a UCI adapter that lets another program drive it, and a
testing package that validates move generation, search, and evaluation. It also holds the PyTorch
trainer for the engine's evaluation network.

## Requirements

JDK 25 or later. The build targets Java 25 and will not compile on an older release.

The engine has no dependencies beyond the JDK. The graphical interface uses the Batik set in `lib/`
to render the piece artwork in `art/`. Those jars are vendored, so there is nothing to download and
no dependency manager to run.

Training an evaluation network needs Python with the PyTorch and NumPy versions pinned in
`trainer/requirements.txt`. Nothing else in the repository uses Python.

The project is developed on Windows and built primarily for it. `build.bat` is a Windows batch file
and its `compare` target depends on `git worktree` and on the script being able to invoke itself.
Other platforms can compile and run everything with the `javac` invocations shown below, but the
build script will not run there.

## Running a Release

Both jars are attached to each entry on the
[Releases](https://github.com/aaronhovt/capstone-chess/releases) page.

`CapstoneChess.jar` is the graphical interface. It needs the `lib/` and `art/` directories beside it,
since its manifest resolves Batik out of `lib/`.

```
java -jar CapstoneChess.jar
```

`capstone-chess-uci.jar` is the engine on its own, speaking UCI on standard input and output. It is
self contained, evaluation network included, and can be pointed at by a chess GUI or a match runner.

```
java -jar capstone-chess-uci.jar
```

It answers `uci`, `isready`, `ucinewgame`, `setoption`, `position`, `go`, and `quit`. The `position`
command accepts `startpos` or `fen` with an optional `moves` list, and `go` accepts `depth`, `nodes`,
and `movetime` limits as well as the `wtime`, `btime`, `winc`, `binc`, and `movestogo` clock.
`Hash` and `Threads` are exposed as options.

A release documents one build. The repository moves ahead of it between releases, so build from
source to run current code.

## Building from Source

Run `build.bat` from the repository root. With no argument it builds the `all` target.

The script locates a JDK by checking `JAVA_HOME`, then the directory holding the `javac` found on
`PATH`, then `%ProgramFiles%\Java\jdk-*`. It needs a directory holding `jar.exe` as well as
`javac.exe`, which the Oracle installer's shim directory on `PATH` does not provide.

| Target | Effect |
| --- | --- |
| `uci` | Compiles the engine and packages `capstone-chess-uci.jar` with the evaluation network inside it. |
| `gui` | Compiles the interface and packages `CapstoneChess.jar` with the evaluation network inside it and a manifest pointing at `lib/`. |
| `perft` | Compiles `PerftSuite`. |
| `tactical` | Compiles `TacticalSuite`. |
| `eval` | Compiles `EvaluationSuite`. |
| `bench` | Compiles `EvaluationBench`. |
| `book` | Compiles `OpeningBook`. |
| `match` | Compiles `SelfPlayMatch`. |
| `stats` | Compiles `SearchStatisticsRun`. |
| `datagen` | Compiles `TrainingDataRun`. |
| `compare` | Plays one revision against another. Described under Validation Tooling. |
| `all` | Every target above except `compare`. This is the default. |
| `clean` | Removes `out/` and both jars, leaving the `jars/` cache alone. |

The suite targets compile only. Each one prints the command that runs what it just built, for
example:

```
java -cp "out\tactical" engine.forTesting.TacticalSuite
```

On a platform without the batch script, compile an entry point directly. `javac` follows the source
path from there, so no file list is needed.

```
javac --release 25 -nowarn -cp "lib/*" -sourcepath src -d out/gui src/engine/engineDriver.java
java -cp "out/gui:lib/*" engine.engineDriver
```

Substitute another entry point for a different target: `src/engine/forUCI/UciEngine.java` for the UCI
engine, or any of the suite classes in `src/engine/forTesting`. Only the interface needs `lib/*` on
the classpath; the engine and the suites need nothing beyond their own output directory. Run from
the repository root, they read the evaluation network from `nets/default.nnue`. The build script
copies the network into each output directory instead.

## Validation Tooling

Every tool that makes a check exits with a non-zero status when the check fails, which is what
allows each one to gate a commit.

**PerftSuite** counts the leaf nodes of the move tree for seven reference positions and compares them
against published counts, to depth four by default. `--int-moves` takes the counts with the packed
int move generator instead of move objects. `--verify-hash` adds a walk checking that the
incrementally updated Zobrist hash of every position matches one computed from scratch.
`--verify-mutation` adds a walk checking that copying the board, making and unmaking a move, and
making and unmaking a null move each restore the position exactly, that the bitboards and piece
lists agree with the pieces on the board, that the int move generator produces exactly the legal
move list, and that making a move as a packed int matches making it as a move object.
`--verify-fen` adds a walk checking that every position written as FEN and read back gives the same
position. `--verify-nnue` adds a walk checking that the network accumulators the board keeps from
move to move match those computed from its pieces, on arriving at each position, after a null move
from it, and after its moves are unmade. The `divide`
subcommand breaks a single position down by root move, which is how a count mismatch gets
localized. Depths past five are expensive. This is the gate for any move generation change.

**TacticalSuite** searches nineteen positions grouped into mate, material, and draw categories. Each
position carries its own recorded depth, and it counts as solved only when the engine both plays an
accepted move and returns a score inside the band recorded against the position, so a mate found at
the wrong distance is a failure. `--workers` sets how many positions are searched at once and
defaults to one, which is the configuration to use when comparing runs, since each worker holds its
own transposition table. `--category` restricts the run and `--verbose` prints the detail of every
position. A depth argument overrides every recorded depth, and overriding downward is expected to
fail positions. This is the gate for any search or evaluation change.

**EvaluationSuite** reports four quantities over eight positions for the hand-tuned middlegame,
endgame, and tapered evaluators, which the search no longer uses. Balance prints the score each
evaluator gives. Agreement prints the spread between the highest and lowest score the evaluators give
a position. Mirror symmetry prints the residual left when a position is added to its colour-mirrored
twin, which is zero for an evaluation that treats both colours alike. Side to move prints the
difference between evaluating one placement with each side to move. Only the mirror check carries a
verdict, and it is the one that can fail the run.

**EvaluationBench** runs the hand-tuned middlegame, endgame, and tapered evaluators over 150
positions, fifty per phase, with no search. It reports the bytes one evaluation allocates as measured
after a warmup, the fastest and median batch times, the resulting rate, and a checksum folding the
raw bits of every score. Nothing here can fail, so it always exits zero. The checksum is what makes a
behaviour-preserving evaluation refactor checkable. Each position's legal moves are generated once
during the warmup and reused, so the bench measures the evaluators' own work but not the move
generation they trigger inside a search, which is a large part of what evaluation costs there.

**OpeningBook** verifies the 33,398-line book at `book/openings.txt`, or a book at a given path. A line
holds the moves of one opening in long algebraic notation, its ECO code, and its name, separated by
tabs.

**SelfPlayMatch** plays two engine processes against each other over paired openings, each opening
played twice with the colours reversed. Both sides run under the same node limit, 100,000 per move by
default, or under the same move time. It reports the result, an Elo estimate with a confidence
interval, and the time and mean depth of each engine. Adjudication from the reported scores is on by
default; `--no-adjudication` plays every game out, which matters when the two sides evaluate on
different scales, because asymmetric resign thresholds confound the result. `--sprt` stops the match
once a sequential test settles it. The suite exits with status 2 on a usage error and 1 when the
match itself fails. Run the class with `--help` for the full option list.

Matches are normally started through the build script:

```
build compare <first-ref> [second-ref] [match options]
```

A ref is anything git resolves to a commit. The first is engine A and the second is engine B, and the
word `working` stands for the working tree in either position, so an uncommitted change can be played
against a commit. Leaving the second ref out plays the working tree as engine B. Everything after the
refs is passed to `SelfPlayMatch` unchanged. The jar built from a revision is cached in `jars/` under
its commit hash and reused, so a baseline is built once. Only revisions carrying the `uci` target can
be built this way, which sets the oldest ref a comparison can reach back to.

**SearchStatisticsRun** plays self-play games under the conditions of a node-limited match, 100,000
nodes a move by default, and reports what the search did in them: where its nodes went, how often
each pruning and reduction fired, how well its moves were ordered, and how its iterations and
aspiration windows behaved.

## Network Training

The engine evaluates positions with a network trained on its own games.

**TrainingDataRun**, built by the `datagen` target, plays self-play games at a node limit, 5,000
nodes a move by default, each from a book opening followed by a few random moves. It writes every
position searched in them that is not in check, not answered by a capture or a promotion, and not
scored as mate, one per line as `<fen> | <score> | <result>`: the search score in centipawns and the
game result, both from White's point of view. The output file is appended to, so runs with different
seeds can add to one file.

`trainer/prepare.py` converts that text into the arrays `trainer/train.py` reads. The trainer fits
the network to a blend of the search score and the game result and writes the quantised network
every few epochs. `trainer/nnue.py` defines the network, its file format, and an integer reference
evaluation that the engine's implementation reproduces exactly. `trainer/pack.py` packs the text
into shuffled shards of fixed-size records for training sets too large to hold in memory, a format
`train.py` does not read yet.

```
python trainer/prepare.py data/training.txt data/training
python trainer/train.py data/training data/nets/first
```

A trained network becomes the engine's by replacing `nets/default.nnue`, which the build packages
into both jars. `trainer/requirements.txt` gives the command that installs the pinned PyTorch build
into a virtual environment at the repository root.

## Architecture

| Package | Lines | Responsibility |
| --- | --- | --- |
| `engine` | 345 | The alliance type and the entry point that opens the interface. |
| `engine.forBoard` | 3,287 | Board state with piece bitboards, the move class and packed int moves, the int move generator, attack detection, make and unmake, Zobrist hashing, and the evaluation network's accumulators, kept as moves are made and unmade. |
| `engine.forPiece` | 1,154 | The piece types and the squares each one attacks and moves to. |
| `engine.forPlayer` | 372 | Per-colour move lists and check, mate, and castling state. |
| `engine.forPlayer.forAI` | 8,538 | Search, transposition table, evaluation cache, the mop-up scoring of bare king endings, static exchange evaluation, and search statistics, along with the hand-tuned middlegame, endgame, and tapered evaluators, their weights, and their pawn structure caches, which only the testing tools still use. |
| `engine.forNetwork` | 300 | The evaluation network: reading its weights from the trainer's file format and computing its output from a pair of accumulators. |
| `engine.forGUI` | 1,080 | The Swing interface: the board view, move history, search report strip, and game setup dialog. |
| `engine.forTesting` | 8,634 | Perft, tactical, evaluation, and bench suites, FEN parsing and writing, the opening book, the self-play harness with its match statistics and adjudication, the search statistics and training data runs, and the Texel tuner. |
| `engine.forUCI` | 502 | The UCI adapter. |

Outside the source tree, `art/` holds the piece artwork, `book/` holds the opening book, `lib/` holds
the Batik jars the interface renders the artwork with, `nets/` holds the evaluation network the jars
are built with, and `trainer/` holds the Python scripts that train it. `match-nps.sh` reports the
engine's speed from the logs of a move time match.

## Current Capability

Move generation runs on bitboards, matches the published perft counts at the tested depths, and
passes the hash consistency, make and unmake, FEN round trip, and network accumulator walks.

Search is a negamax alpha-beta with principal variation search, iterative deepening, aspiration
windows, Lazy SMP across threads, a transposition table, quiescence search with delta pruning, null
move pruning with verification at depth, logarithmic late move reductions, late move pruning,
reverse futility and futility pruning, razoring, pruning of losing captures by static exchange
evaluation, reduced depth at nodes the transposition table holds no move for, and move ordering
drawn from killer moves, history, countermoves, and static exchange evaluation. Under a clock it
plans an even share of the remaining time plus three quarters of the increment for each move. It
starts no iteration once that share has passed, scaled up while the best move keeps changing and
down once it has held for several iterations, and stops outright at a fixed multiple of it.

Evaluation is a network of 768 inputs, one for each piece type of each colour on each square,
feeding an accumulator of 256 values for each side, a squared clipped ReLU, and one output. The
accumulators are updated as moves are made and unmade, and the network is computed in integer
arithmetic. The network in `nets/` was trained on 71 million positions from the self-play games of
an earlier network, itself trained on the games of the hand-tuned evaluators. Endings in which one
side has only its king against enough material to mate are scored by a mop-up rule that drives the
bare king to the edge, and positions drawn by material are scored zero.

In two gauntlets totalling 3,408 games at 60 seconds plus 0.6 seconds a move, played single-threaded
against Leorik 2.4, 2.5, and 3.0 and Weiss 1.1, 1.2, and 1.4, revision ebc5c2b rated 3126 ± 10 on the
CCRL Blitz scale, fitted with Ordo using those six engines' CCRL Blitz ratings as anchors. Two of the
anchors are rated above it and four below. The self-play harness measures the difference between two
revisions of this engine and nothing else, so it shows whether a change helped, while a gauntlet
against rated engines is what says where the engine stands.

## Acknowledgments

The engine began from Amir Afghani's [BlackWidow-Chess](https://github.com/amir650/BlackWidow-Chess)
and his video walkthrough of it, which supplied the original board, move, piece, player, and Swing
interface classes. Those classes have since been reworked, and the search, evaluation, bitboard
move generation, and testing code were written for this project.

## License

MIT. See [LICENSE](LICENSE). The code derived from BlackWidow-Chess is distributed under the MIT
License with its author's written permission, as [NOTICE](NOTICE) records.

## Contact

Questions and suggestions are welcome at aaronho.vt@gmail.com, or as an issue on this repository.
