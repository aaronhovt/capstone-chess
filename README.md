# Capstone Chess

A chess engine written in Java. It began as a STEM Academy capstone project at Essex High School
between January 2023 and May 2025 and has been developed continuously since. The repository holds
20,818 lines across 47 source files and carries three front ends: a Swing interface for playing the
engine by hand, a UCI adapter that lets another program drive it, and a testing package that
validates move generation, search, and evaluation.

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
[Releases](https://github.com/aaronhovt/capstone-chess/releases) page.

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
and `movetime` limits. `Hash` and `Threads` are exposed as options.

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
against published counts, to depth four by default. `--verify-hash` adds a walk checking that the
incrementally updated Zobrist hash of every position matches one computed from scratch.
`--verify-mutation` adds a walk checking that making and unmaking a move, including a null move,
restores the board exactly. The `divide` subcommand breaks a single position down by root move, which
is how a count mismatch gets localized. Depths past five are expensive, since the engine regenerates
the legal moves of both players at every node. This is the gate for any move generation change.

**TacticalSuite** searches nineteen positions grouped into mate, material, and draw categories. Each
position carries its own recorded depth, and it counts as solved only when the engine both plays an
accepted move and returns a score inside the band recorded against the position, so a mate found at
the wrong distance is a failure. `--workers` sets how many positions are searched at once and
defaults to one, which is the configuration to use when comparing runs, since each worker holds its
own transposition table. `--category` restricts the run and `--verbose` prints the detail of every
position. A depth argument overrides every recorded depth, and overriding downward is expected to
fail positions. This is the gate for any search or evaluation change.

**EvaluationSuite** reports three checks over eight positions. Balance prints the score each
evaluator gives. Mirror symmetry prints the residual left when a position is added to its
colour-mirrored twin, which is zero for an evaluation that treats both colours alike. Side to move
prints the difference between evaluating one placement with each side to move. Only the mirror check
carries a verdict, and it is the one that can fail the run.

**EvaluationBench** runs all three evaluators over 150 positions, fifty per phase, with no search. It
reports the bytes one evaluation allocates as measured after a warmup, the fastest and median batch
times, the resulting rate, and a checksum folding the raw bits of every score. Nothing here can fail,
so it always exits zero. The checksum is what makes a behaviour-preserving evaluation refactor
checkable, and the allocation figure is where evaluator cost actually shows, since the evaluator is a
small fraction of tactical suite runtime.

**OpeningBook** verifies the 971-line book at `book/openings.txt`, or a book at a given path. A line
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
| `engine` | 358 | The alliance type and the entry point that opens the interface. |
| `engine.forBoard` | 3,087 | Board and tile representation, moves, make and unmake, undo state, Zobrist hashing. |
| `engine.forPiece` | 1,946 | The piece hierarchy and the candidate move generation for each piece type. |
| `engine.forPlayer` | 513 | Per-colour legal move generation and check, mate, and castling state. |
| `engine.forPlayer.forAI` | 7,027 | Search, transposition table, evaluation cache, the three phase evaluators, static exchange evaluation, and phase detection. |
| `engine.forGUI` | 1,659 | The Swing interface, move history, debug panel, and game setup dialog. |
| `engine.forTesting` | 5,795 | Perft, tactical, evaluation, and bench suites, FEN parsing, the opening book, and the self-play harness. |
| `engine.forUCI` | 433 | The UCI adapter. |

Outside the source tree, `art/` holds the piece and board artwork, `book/` holds the opening book,
`lib/` holds the vendored dependencies, and `src/javadocs/` holds the generated API documentation.

## Current Capability

Move generation matches the published perft counts at the tested depths and passes both the hash
consistency walk and the make and unmake walk.

Search is alpha-beta with iterative deepening, aspiration windows, Lazy SMP across threads, a
transposition table, quiescence search with delta pruning, null move pruning, late move reductions,
futility and razoring margins, and move ordering drawn from killer moves, history, countermoves, and
static exchange evaluation.

Evaluation uses three separate evaluators, one for each phase of the game, chosen per search.

The honest limits are worth stating plainly. The engine has never played a rated opponent or any
external engine, so its absolute strength is unknown and no rating is claimed for it. The self-play
harness measures the difference between two revisions of this engine and nothing else, so it can show
that a change helped without saying where the engine stands. The evaluation still carries large
residuals on material-equal positions, which is the current limit on its positional play and the
focus of ongoing work.

## License

MIT. See [LICENSE](LICENSE).

## Contact

Questions and suggestions are welcome at aaronho.vt@gmail.com, or as an issue on this repository.
