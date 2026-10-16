# Capstone Chess

A chess engine written in Java. It began as a STEM Academy capstone project at Essex High School
between January 2023 and May 2025 and has been developed continuously since. The repository holds
21,028 lines of Java and Javadoc across 52 source files and carries three front ends: a Swing
interface for playing the engine by hand, a UCI adapter that lets another program drive it, and a
testing package that validates move generation, search, and evaluation.

## Requirements

JDK 25 or later. The build targets Java 25 and will not compile on an older release.

The engine itself depends only on `lib/guava-33.4.0-jre.jar` and `lib/failureaccess-1.0.2.jar`. The
graphical interface additionally uses the Batik set in `lib/` to render the piece artwork in `art/`.
Every dependency is vendored, so there is nothing to download and no dependency manager to run.

The project is developed on Windows and built primarily for it. `build.bat` is a Windows batch file
and its `compare` target depends on `git worktree` and on the script being able to invoke itself.
Other platforms can compile and run everything with the `javac` invocations shown below, but the
build script will not run there.

## Running a Release

Both jars are attached to each entry on the
[Releases](https://github.com/aaronho01/capstoneChess/releases) page.

`CapstoneChess.jar` is the graphical interface. It needs the `lib/` and `art/` directories beside it,
since its manifest resolves Batik out of `lib/`.

```
java -jar CapstoneChess.jar
```

`capstone-chess-uci.jar` is the engine on its own, speaking UCI on standard input and output. It is
self contained and can be pointed at by a chess GUI or a match runner.

```
java -jar capstone-chess-uci.jar
```

It answers `uci`, `isready`, `ucinewgame`, `setoption`, `position`, `go`, and `quit`. The `position`
command accepts `startpos` or `fen` with an optional `moves` list, and `go` accepts `depth`, `nodes`,
and `movetime` limits as well as the `wtime`, `btime`, `winc`, and `binc` clock. `Hash` and
`Threads` are exposed as options.

A release documents one build. The repository moves ahead of it between releases, so build from
source to run current code.

## Building from Source

Run `build.bat` from the repository root. With no argument it builds the `all` target.

The script locates a JDK by checking `JAVA_HOME`, then the directory holding the `javac` found on
`PATH`, then `%ProgramFiles%\Java\jdk-*`. It needs a directory holding `jar.exe` as well as
`javac.exe`, which the Oracle installer's shim directory on `PATH` does not provide.

| Target | Effect |
| --- | --- |
| `uci` | Compiles the engine and packages `capstone-chess-uci.jar` with both engine dependencies unpacked into it. |
| `gui` | Compiles the interface and packages `CapstoneChess.jar` with a manifest pointing at `lib/`. |
| `perft` | Compiles `PerftSuite`. |
| `tactical` | Compiles `TacticalSuite`. |
| `eval` | Compiles `EvaluationSuite`. |
| `bench` | Compiles `EvaluationBench`. |
| `book` | Compiles `OpeningBook`. |
| `match` | Compiles `SelfPlayMatch`. |
| `compare` | Plays one revision against another. Described under Validation Tooling. |
| `all` | Every target above except `compare`. This is the default. |
| `clean` | Removes `out/` and both jars, leaving the `jars/` cache alone. |

The suite targets compile only. Each one prints the command that runs what it just built, for
example:

```
java -cp "out\tactical;lib\guava-33.4.0-jre.jar;lib\failureaccess-1.0.2.jar" engine.forTesting.TacticalSuite
```

On a platform without the batch script, compile an entry point directly. `javac` follows the source
path from there, so no file list is needed.

```
javac --release 25 -nowarn -cp "lib/*" -sourcepath src -d out/gui src/engine/engineDriver.java
java -cp "out/gui:lib/*" engine.engineDriver
```

Substitute another entry point for a different target: `src/engine/forUCI/UciEngine.java` for the UCI
engine, or any of the suite classes in `src/engine/forTesting`. Only the interface needs the full
`lib/*` on the classpath; the engine and the suites need the Guava and failureaccess jars alone.

## Validation Tooling

Every tool but the bench exits with a non-zero status when a check fails, which is what allows each
one to gate a commit.

**PerftSuite** counts the leaf nodes of the move tree for seven reference positions and compares them
against published counts, to depth four by default. `--int-moves` takes the counts with the packed
int move generator instead of move objects. `--verify-hash` adds a walk checking that the
incrementally updated Zobrist hash of every position matches one computed from scratch.
`--verify-mutation` adds a walk checking that copying the board, making and unmaking a move, and
making and unmaking a null move each restore the position exactly, that the bitboards and piece
lists agree with the pieces on the board, that the int move generator produces exactly the legal
move list, and that making a move as a packed int matches making it as a move object. The `divide`
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

**EvaluationSuite** reports four quantities over eight positions for the middlegame, endgame, and
tapered evaluators. Balance prints the score each evaluator gives. Agreement prints the spread
between the highest and lowest score the evaluators give a position. Mirror symmetry prints the
residual left when a position is added to its colour-mirrored twin, which is zero for an evaluation
that treats both colours alike. Side to move prints the difference between evaluating one placement
with each side to move. Only the mirror check carries a verdict, and it is the one that can fail the
run.

**EvaluationBench** runs the middlegame, endgame, and tapered evaluators over 150 positions, fifty
per phase, with no search. It reports the bytes one evaluation allocates as measured after a warmup,
the fastest and median batch times, the resulting rate, and a checksum folding the raw bits of every
score. Nothing here can fail, so it always exits zero. The checksum is what makes a
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

## Architecture

| Package | Lines | Responsibility |
| --- | --- | --- |
| `engine` | 305 | The alliance type and the entry point that opens the interface. |
| `engine.forBoard` | 3,403 | Board state with piece bitboards, move objects and packed int moves, the int move generator, attack detection, make and unmake, undo state, Zobrist hashing. |
| `engine.forPiece` | 1,948 | The piece hierarchy and the candidate move generation for each piece type. |
| `engine.forPlayer` | 463 | Per-colour legal move generation and check, mate, and castling state. |
| `engine.forPlayer.forAI` | 6,301 | Search, transposition table, evaluation and pawn structure caches, the middlegame and endgame evaluators and the tapered evaluator that blends them, evaluation weights, and static exchange evaluation. |
| `engine.forGUI` | 1,343 | The Swing interface, move history, debug panel, and game setup dialog. |
| `engine.forTesting` | 6,828 | Perft, tactical, evaluation, and bench suites, FEN parsing, the opening book, the self-play harness with its match statistics and adjudication, and the Texel tuner. |
| `engine.forUCI` | 437 | The UCI adapter. |

Outside the source tree, `art/` holds the piece artwork, `book/` holds the opening book, and `lib/`
holds the vendored dependencies.

## Current Capability

Move generation runs on bitboards, matches the published perft counts at the tested depths, and
passes both the hash consistency walk and the make and unmake walk.

Search is a negamax alpha-beta with principal variation search, iterative deepening, aspiration
windows, Lazy SMP across threads, a transposition table, quiescence search with delta pruning, null
move pruning with verification at depth, logarithmic late move reductions, late move pruning,
reverse futility and futility pruning, razoring, pruning of losing captures by static exchange
evaluation, and move ordering drawn from killer moves, history, countermoves, and static exchange
evaluation.

Evaluation blends a middlegame and an endgame evaluator by the material left on the board. Their
weights were tuned with the Texel tuner in the testing package.

In a gauntlet of 1,720 games at 60 seconds plus 0.6 seconds a move, played single-threaded against
Blunder 7.1.0 and 8.5.5 and Leorik 2.1 and 2.4, revision 2841e15 rated 2528 ± 13 on the CCRL Blitz
scale, fitted with Ordo using those four engines' CCRL Blitz ratings as anchors. The self-play
harness measures the difference between two revisions of this engine and nothing else, so it shows
whether a change helped, while a gauntlet against rated engines is what says where the engine
stands.

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
