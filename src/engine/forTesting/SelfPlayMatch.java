package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.Move;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The SelfPlayMatch class plays a match between two chess engines running as separate processes
 * and reports the result. It is the arbiter as well as the runner: it holds the only board that
 * counts, decides the outcome, and accepts a move only after resolving it against its own legal
 * moves, so an engine cannot end a game by claiming one.
 * <p>
 * Every opening is played twice, once with each engine as White, which is what a pair is. Both
 * engines are started once for the whole match and reused, and a new game is started on each of
 * them before every game, so the transposition table and the heuristic tables an engine holds
 * never carry from one game into the next.
 * <p>
 * Openings are drawn from a shuffle of the book seeded from the command line, since the book is
 * grouped by opening classification and taking its lines in order would play a match out of one
 * corner of it. The same seed draws the same openings, so a match can be repeated.
 * <p>
 * Both engines are told the position as the standard starting position followed by every move
 * played so far, including the moves of the opening, rather than as a position in
 * Forsyth-Edwards Notation. Replaying the whole game is what gives each engine the repetition
 * history a game actually has, which its own draw detection depends on.
 * <p>
 * Each engine searches under its own limit, a node count or a move time, which the command line
 * sets for both engines at once or for one engine at a time, or both engines play on a game clock
 * under one time control. A match is played under one kind of limit throughout, since a node
 * count, a move time and a clock do not measure the same thing.
 * <p>
 * Under a time control the arbiter keeps both clocks. The time a search takes, from sending the
 * search command to reading the move, is taken off the clock of the side that moved, and the
 * increment is added once the move is made. A side whose search takes longer than its clock held
 * loses the game on time.
 * <p>
 * Search threads are fixed at one for both engines. A node limit is only a meaningful measure of
 * equal effort while one thread is searching, since helper threads contribute nodes that the limit
 * does not account for.
 * <p>
 * A game that the engines agree is decided, or agree is level, is adjudicated rather than played
 * out, on the rules {@link Adjudicator} holds. A position that has already ended is judged on the
 * board before it is judged on the scores, so a checkmate is a checkmate rather than an
 * adjudicated win.
 * <p>
 * A match may also be stopped by the sequential test {@link MatchStatistics} holds, which ends it
 * as soon as the games have settled on one of two hypotheses rather than playing every pair asked
 * for. The test is weighed at the end of a pair rather than after a game, since the pair is what
 * balances the colours and a test read between the two games of one pair is read on a match that
 * gave one engine White more often than the other.
 * <p>
 * The arbiter uses the move generation of the build it was compiled from, not that of either
 * engine, so a move generation fault shared by both engines would be invisible here. That is what
 * {@link PerftSuite} covers.
 * <p>
 * Every pair that reaches a result is appended to a results file as soon as it ends, after a line
 * recording the settings that decide the games. A match stopped part way, by a crash or by hand,
 * can be resumed from that file: the pairs it records are counted again rather than played, and
 * only the rest are played. A resumed match must be played under the same settings, apart from the
 * number of pairs played at once, the timeout, the log directory and verbose output.
 *
 * @author Aaron Ho
 */
public class SelfPlayMatch {

  /** The node limit used for each move when no other limit is given. */
  private static final long DEFAULT_NODE_LIMIT = 100_000;

  /** The transposition table size in megabytes given to each engine when no other size is given. */
  private static final int DEFAULT_TABLE_SIZE_MB = 64;

  /** The largest number of plies a game may reach before it is stopped, when no other cap is given. */
  private static final int DEFAULT_PLY_CAP = 300;

  /** The score a side must be ahead by for a ply to count towards a win, by default. */
  private static final int DEFAULT_RESIGN_SCORE = 800;

  /** The number of consecutive plies at the resign score that win a game, by default. */
  private static final int DEFAULT_RESIGN_PLIES = 8;

  /** The score a position must be within for a ply to count towards a draw, by default. */
  private static final int DEFAULT_DRAW_SCORE = 10;

  /** The number of consecutive plies within the draw score that draw a game, by default. */
  private static final int DEFAULT_DRAW_PLIES = 8;

  /** The ply a game must have reached before it may be drawn on score, by default. */
  private static final int DEFAULT_DRAW_AFTER = 80;

  /** The Elo difference the null hypothesis holds when no other difference is given. */
  private static final double DEFAULT_NULL_ELO = 0.0;

  /** The Elo difference the alternative hypothesis holds when no other difference is given. */
  private static final double DEFAULT_GAIN_ELO = 5.0;

  /** The chance of accepting the alternative when the null holds, when no other rate is given. */
  private static final double DEFAULT_FALSE_POSITIVE_RATE = 0.05;

  /** The chance of accepting the null when the alternative holds, when no other rate is given. */
  private static final double DEFAULT_FALSE_NEGATIVE_RATE = 0.05;

  /** The time an engine is allowed to take to answer, in seconds, when no other time is given. */
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;

  /** The directory the engine logs are written to when no other directory is given. */
  private static final String DEFAULT_LOG_DIRECTORY = "out/match-logs";

  /** The name of the results file, in the log directory, when no other file is given. */
  private static final String DEFAULT_RESULTS_FILE_NAME = "results.txt";

  /** The first line of a results file. */
  private static final String RESULTS_TITLE =
          "# SelfPlayMatch results: the settings, then one line per finished pair";

  /** The word starting the line of a results file that records the settings. */
  private static final String RESULTS_SETTINGS_WORD = "settings";

  /** The word starting each line of a results file that records a finished pair. */
  private static final String RESULTS_PAIR_WORD = "pair";

  /** The number of fields on a pair line of a results file, the leading word included. */
  private static final int RESULTS_PAIR_FIELDS = 16;

  /**
   * The number of fields on a pair line written before time losses were recorded, which is read
   * as a pair with no time losses.
   */
  private static final int RESULTS_PAIR_FIELDS_WITHOUT_TIME_LOSSES = 14;

  /** The number of openings played when no other number is given. */
  private static final int DEFAULT_PAIRS = 1;

  /** The seed the book shuffle uses when no other seed is given. */
  private static final long DEFAULT_SEED = 1;

  /** The number of pairs played at once when no other number is given. */
  private static final int DEFAULT_CONCURRENCY = 1;

  /** The number of games played from each opening, one with each engine as White. */
  private static final int GAMES_PER_PAIR = 2;

  /** The search thread count both engines are held to. */
  private static final int SEARCH_THREADS = 1;

  /** The long algebraic notation an engine reports when it has no move to make. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The name the first engine is reported and logged under. */
  private static final String ENGINE_A_NAME = "A";

  /** The name the second engine is reported and logged under. */
  private static final String ENGINE_B_NAME = "B";

  /** The exit status used when the match stopped in a failure rather than reaching its end. */
  private static final int FAILURE_STATUS = 1;

  /** The exit status used when the command line could not be read. */
  private static final int USAGE_STATUS = 2;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private SelfPlayMatch() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Plays a match from the command line and reports the result. The exit status is zero if every
   * game reached a result, one if the match stopped on a failure, and two if the command line or
   * the opening book could not be read.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    final Settings settings;
    try {
      settings = Settings.read(args);
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      printUsage();
      System.exit(USAGE_STATUS);
      return;
    }
    if (settings == null) {
      printUsage();
      return;
    }

    final List<BookLine> lines;
    try {
      lines = selectLines(settings);
    } catch (final IOException exception) {
      System.out.println("The opening book at " + settings.bookPath() + " could not be read: " +
              exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    }

    printHeader(settings, lines);
    final ResultsFile results;
    try {
      results = ResultsFile.open(settings, lines.size());
    } catch (final IOException exception) {
      System.out.println("The results file " + settings.resultsPath() + " could not be opened: " +
              exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      System.exit(USAGE_STATUS);
      return;
    }

    final long start = System.nanoTime();
    final Tally tally = runMatch(settings, lines, results);
    results.close();
    final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
    printTally(tally, lines.size(), workerCount(settings, lines), elapsedMillis,
            settings.sequentialTest());
    if (tally.failure() != null) {
      System.out.println();
      System.out.println("The match stopped: " + tally.failure());
      System.exit(FAILURE_STATUS);
    }
  }

  /**
   * Plays every pair of the match on a pool of workers, reporting each pair as it ends. Each
   * worker holds its own pair of engine processes and takes pairs until there are none left, the
   * sequential test has settled, or a worker has failed. Pairs already being played when the match
   * stops are played to the end and counted, so a match may hold more pairs than a match of one
   * worker would.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from, each of them played twice.
   * @param results The results file the finished pairs are recorded in, holding the pairs of a
   *                resumed match.
   * @return What the games came to and what stopped the match, if anything did.
   */
  private static Tally runMatch(final Settings settings, final List<BookLine> lines,
                                final ResultsFile results) {
    final List<String> commandA;
    final List<String> commandB;
    try {
      commandA = EngineProcess.tokenize(settings.engineACommand());
      commandB = EngineProcess.tokenize(settings.engineBCommand());
    } catch (final IllegalArgumentException exception) {
      return new Tally(0, 0, 0, 0, 0, 0, 0, new SearchTotals(), new SearchTotals(), 0, null,
              exception.getMessage());
    }

    final int workers = workerCount(settings, lines);
    final Coordinator coordinator = new Coordinator(lines, settings.sequentialTest(), results);
    if (!coordinator.hasPairsLeft()) {
      return coordinator.tally();
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
      for (int worker = 1; worker <= workers; worker++) {
        final int number = worker;
        pool.execute(() -> runWorker(settings, lines, number, workers, commandA, commandB,
                coordinator));
      }
    }
    return coordinator.tally();
  }

  /**
   * Names how many workers a match is played on. A match is never given more workers than it has
   * pairs, since a worker with no pair to play would still start two engine processes.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from.
   * @return The number of workers the match is played on.
   */
  private static int workerCount(final Settings settings, final List<BookLine> lines) {
    return Math.min(settings.concurrency(), lines.size());
  }

  /**
   * Starts one pair of engine processes and plays pairs on them until the coordinator has none
   * left to give. The processes are started once and reused, so a worker carries out the handshake
   * and sets the options once however many pairs it plays.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from.
   * @param worker The number of this worker, counting from one.
   * @param workers The number of workers the match is played on.
   * @param commandA The command line starting the first engine, already split.
   * @param commandB The command line starting the second engine, already split.
   * @param coordinator The coordinator handing out pairs and holding what they came to.
   */
  private static void runWorker(final Settings settings, final List<BookLine> lines,
                                final int worker, final int workers, final List<String> commandA,
                                final List<String> commandB, final Coordinator coordinator) {
    final Path logDirectory = Path.of(settings.logDirectory());
    final long timeoutMillis = settings.timeoutSeconds() * 1_000L;
    try (EngineProcess engineA = new EngineProcess(ENGINE_A_NAME, commandA,
            logDirectory.resolve("engine-a-" + worker + ".log"), timeoutMillis);
         EngineProcess engineB = new EngineProcess(ENGINE_B_NAME, commandB,
                 logDirectory.resolve("engine-b-" + worker + ".log"), timeoutMillis)) {
      prepare(engineA, settings);
      prepare(engineB, settings);
      while (true) {
        final int pair = coordinator.nextPair();
        if (pair < 0) {
          return;
        }
        coordinator.finish(pair,
                playPair(settings, pair + 1, lines.get(pair), engineA, engineB));
      }
    } catch (final EngineProcess.Fault fault) {
      coordinator.fail(workers == 1 ? fault.getMessage() :
              "on worker " + worker + ", " + fault.getMessage());
    }
  }

  /**
   * Plays both games of one pair, the second of them with the colours reversed, collecting the
   * lines that report them rather than writing them out. The pair stops at the first failure, and
   * the game played before it is still counted.
   *
   * @param settings The settings the pair is played under.
   * @param pair The number of the pair, counting from one.
   * @param line The opening both games are played from.
   * @param engineA The first engine, which plays White in the first game.
   * @param engineB The second engine, which plays White in the second game.
   * @return What the games of the pair came to and the lines reporting them.
   */
  private static PairResult playPair(final Settings settings, final int pair, final BookLine line,
                                     final EngineProcess engineA, final EngineProcess engineB) {
    final List<String> report = new ArrayList<>();
    reportPair(report, pair, line);
    final SearchTotals totalsA = new SearchTotals();
    final SearchTotals totalsB = new SearchTotals();
    int engineAWins = 0;
    int engineBWins = 0;
    int draws = 0;
    int played = 0;
    int adjudicated = 0;
    int engineATimeLosses = 0;
    int engineBTimeLosses = 0;
    long gameMillis = 0;

    for (int game = 0; game < GAMES_PER_PAIR; game++) {
      final boolean engineAIsWhite = game == 0;
      final EngineProcess white = engineAIsWhite ? engineA : engineB;
      final EngineProcess black = engineAIsWhite ? engineB : engineA;
      final EngineProcess.Limit whiteLimit = engineAIsWhite ? settings.limitA() : settings.limitB();
      final EngineProcess.Limit blackLimit = engineAIsWhite ? settings.limitB() : settings.limitA();
      final SearchTotals whiteTotals = engineAIsWhite ? totalsA : totalsB;
      final SearchTotals blackTotals = engineAIsWhite ? totalsB : totalsA;
      final long start = System.nanoTime();
      final Result result = play(settings, line.opening(), white, black, whiteLimit,
              blackLimit, whiteTotals, blackTotals, report);
      final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
      reportGame(report, game + 1, engineAIsWhite, result, elapsedMillis, settings.verbose());
      if (result.outcome() == Outcome.FAILURE) {
        return new PairResult(engineAWins, engineBWins, draws, played, adjudicated,
                engineATimeLosses, engineBTimeLosses, totalsA, totalsB, gameMillis,
                result.reason(), report);
      }
      played++;
      gameMillis += elapsedMillis;
      if (result.adjudicated()) {
        adjudicated++;
      }
      if (result.outcome() == Outcome.DRAW) {
        draws++;
      } else if ((result.outcome() == Outcome.WHITE_WINS) == engineAIsWhite) {
        engineAWins++;
        if (result.lostOnTime()) {
          engineBTimeLosses++;
        }
      } else {
        engineBWins++;
        if (result.lostOnTime()) {
          engineATimeLosses++;
        }
      }
    }
    return new PairResult(engineAWins, engineBWins, draws, played, adjudicated,
            engineATimeLosses, engineBTimeLosses, totalsA, totalsB, gameMillis, null, report);
  }

  /**
   * Reports what the sequential test comes to after a pair and decides whether the games have
   * settled it, now or after an earlier pair. A match holding no test is never settled this way
   * and reports nothing.
   *
   * @param test The hypotheses the games are weighed between, or null if the match holds no test.
   * @param accumulator What the pairs played so far have come to.
   * @return True if the match should stop before its remaining pairs are played.
   */
  private static boolean settled(final MatchStatistics.SequentialTest test,
                                 final Accumulator accumulator) {
    if (test == null) {
      return false;
    }
    final MatchStatistics statistics = new MatchStatistics(accumulator.engineAWins,
            accumulator.engineBWins, accumulator.draws);
    System.out.printf("  test    LLR %+.2f of %+.2f to %+.2f after %d games%n",
            statistics.logLikelihoodRatio(test), test.lowerBound(), test.upperBound(),
            statistics.games());
    return accumulator.settle(test);
  }

  /**
   * Plays one game between two engine processes, starting a new game on each of them first.
   *
   * @param settings The settings the game is played under.
   * @param opening The opening the game starts from, or null to start from the standard starting
   *                position.
   * @param white The engine playing White.
   * @param black The engine playing Black.
   * @param whiteLimit What stops a search by White, or null if the settings hold a time control.
   * @param blackLimit What stops a search by Black, or null if the settings hold a time control.
   * @param whiteTotals The totals the searches White carries out are folded into.
   * @param blackTotals The totals the searches Black carries out are folded into.
   * @param report The lines reporting the pair, which this method appends to when the match is
   *               verbose.
   * @return The outcome of the game, why it ended, and the moves it held.
   */
  public static Result play(final Settings settings, final OpeningBook.Opening opening,
                            final EngineProcess white, final EngineProcess black,
                            final EngineProcess.Limit whiteLimit,
                            final EngineProcess.Limit blackLimit,
                            final SearchTotals whiteTotals, final SearchTotals blackTotals,
                            final List<String> report) {
    final List<String> moves = new ArrayList<>();
    final Board board = opening == null ? Board.createStandardBoard() : OpeningBook.play(opening);
    if (opening != null) {
      moves.addAll(opening.moves());
    }
    try {
      white.newGame();
      black.newGame();
      return playMoves(settings, board, moves, white, black, whiteLimit, blackLimit,
              whiteTotals, blackTotals, report);
    } catch (final EngineProcess.Fault fault) {
      return new Result(Outcome.FAILURE, fault.getMessage(), moves);
    }
  }

  /**
   * Carries out the handshake with one engine and sets its options. The engine reads the options
   * when a new game is started, so this is done once for the match.
   *
   * @param engine The engine to prepare.
   * @param settings The settings the match is played under.
   * @throws EngineProcess.Fault If the engine does not answer.
   */
  private static void prepare(final EngineProcess engine, final Settings settings) {
    engine.handshake();
    engine.setOption("Hash", settings.tableSizeMB());
    engine.setOption("Threads", SEARCH_THREADS);
  }

  /**
   * Plays moves until the game reaches a result or fails. Under a time control both clocks start
   * at the base time when this method is called.
   *
   * @param settings The settings the game is played under.
   * @param board The position the game is played on, which this method advances.
   * @param moves The moves played so far, which this method appends to.
   * @param white The engine playing White.
   * @param black The engine playing Black.
   * @param whiteLimit What stops a search by White, or null if the settings hold a time control.
   * @param blackLimit What stops a search by Black, or null if the settings hold a time control.
   * @param whiteTotals The totals the searches White carries out are folded into.
   * @param blackTotals The totals the searches Black carries out are folded into.
   * @param report The lines reporting the pair, which this method appends to when the match is
   *               verbose.
   * @return The outcome of the game, why it ended, and the moves it held.
   * @throws EngineProcess.Fault If an engine does not answer.
   */
  private static Result playMoves(final Settings settings, final Board board,
                                  final List<String> moves, final EngineProcess white,
                                  final EngineProcess black,
                                  final EngineProcess.Limit whiteLimit,
                                  final EngineProcess.Limit blackLimit,
                                  final SearchTotals whiteTotals,
                                  final SearchTotals blackTotals, final List<String> report) {
    final Adjudicator adjudicator = new Adjudicator(settings.adjudicate(),
            settings.resignScore(), settings.resignPlies(), settings.drawScore(),
            settings.drawPlies(), settings.drawAfter());
    Adjudicator.Verdict verdict = Adjudicator.Verdict.NONE;
    final TimeControl timeControl = settings.timeControl();
    long whiteClock = timeControl == null ? 0 : timeControl.baseMillis();
    long blackClock = whiteClock;

    while (true) {
      final Result terminal = terminalResult(board, moves);
      if (terminal != null) {
        return terminal;
      }
      if (verdict != Adjudicator.Verdict.NONE) {
        return new Result(outcomeOf(verdict), verdict.getReason(), moves, true);
      }
      if (moves.size() >= settings.plyCap()) {
        return new Result(Outcome.DRAW, "the ply cap of " + settings.plyCap() + " was reached",
                moves);
      }

      final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
      final EngineProcess mover = whiteToMove ? white : black;
      final String moverName = "the " + mover.getName() + " engine, playing " +
              (whiteToMove ? "White" : "Black") + ",";
      mover.setPosition(moves);
      final EngineProcess.Reply reply = timeControl == null ?
              mover.go(whiteToMove ? whiteLimit : blackLimit) :
              mover.go(whiteClock, blackClock, timeControl.incrementMillis());
      if (timeControl != null) {
        final long left = (whiteToMove ? whiteClock : blackClock) - reply.elapsedMillis();
        if (left < 0) {
          return new Result(whiteToMove ? Outcome.BLACK_WINS : Outcome.WHITE_WINS,
                  moverName + " lost on time, " + -left + " ms past its clock", moves, false,
                  true);
        }
        if (whiteToMove) {
          whiteClock = left + timeControl.incrementMillis();
        } else {
          blackClock = left + timeControl.incrementMillis();
        }
      }
      if (NULL_MOVE_NOTATION.equals(reply.move())) {
        return new Result(Outcome.FAILURE, moverName + " reported no move in a position the " +
                "arbiter did not find terminal", moves);
      }
      final Move move = OpeningBook.resolve(board, reply.move());
      if (move == null) {
        return new Result(Outcome.FAILURE, moverName + " reported the illegal move " +
                reply.move(), moves);
      }
      board.makeMove(move);
      moves.add(reply.move());
      (whiteToMove ? whiteTotals : blackTotals).add(reply);
      if (settings.verbose()) {
        reportMove(report, moves.size(), whiteToMove, reply);
      }
      verdict = adjudicator.judge(reply, whiteToMove, moves.size());
    }
  }

  /**
   * Names the outcome a verdict awards.
   *
   * @param verdict What the reported scores settled.
   * @return The outcome of the game.
   * @throws IllegalArgumentException If the verdict settled nothing.
   */
  private static Outcome outcomeOf(final Adjudicator.Verdict verdict) {
    return switch (verdict) {
      case WHITE_WINS -> Outcome.WHITE_WINS;
      case BLACK_WINS -> Outcome.BLACK_WINS;
      case DRAW -> Outcome.DRAW;
      case NONE -> throw new IllegalArgumentException("A game that was not adjudicated has no " +
              "adjudicated outcome");
    };
  }

  /**
   * Decides whether the game has ended in the given position. Conditions are checked in the order
   * checkmate, stalemate, insufficient material, the fifty move rule, then threefold repetition,
   * so that a mate delivered on the fiftieth move is a mate.
   *
   * @param board The position to judge.
   * @param moves The moves played so far.
   * @return The result of the game, or null if the game has not ended.
   */
  private static Result terminalResult(final Board board, final List<String> moves) {
    final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
    if (board.currentPlayer().isInCheckMate()) {
      return new Result(whiteToMove ? Outcome.BLACK_WINS : Outcome.WHITE_WINS, "checkmate", moves);
    }
    if (board.currentPlayer().isInStaleMate()) {
      return new Result(Outcome.DRAW, "stalemate", moves);
    }
    if (board.isInsufficientMaterial()) {
      return new Result(Outcome.DRAW, "insufficient material", moves);
    }
    if (board.isFiftyMoveRule()) {
      return new Result(Outcome.DRAW, "the fifty move rule", moves);
    }
    if (board.isThreefoldRepetition()) {
      return new Result(Outcome.DRAW, "threefold repetition", moves);
    }
    return null;
  }

  /**
   * Chooses the openings the match is played from. Without a book every pair starts from the
   * standard starting position, a named line is the only line played, and otherwise the lines are
   * drawn from a shuffle of the book seeded from the settings.
   *
   * @param settings The settings the match is played under.
   * @return The openings the match is played from, one for each pair.
   * @throws IOException If the book file cannot be read.
   * @throws IllegalArgumentException If the book holds no openings, holds no line at the named
   *                                  index, or holds fewer lines than the match asks for.
   */
  private static List<BookLine> selectLines(final Settings settings) throws IOException {
    final List<BookLine> lines = new ArrayList<>();
    if (!settings.useBook()) {
      for (int pair = 0; pair < settings.pairs(); pair++) {
        lines.add(new BookLine(-1, null));
      }
      return lines;
    }

    final List<OpeningBook.Opening> book = OpeningBook.load(Path.of(settings.bookPath()));
    if (book.isEmpty()) {
      throw new IllegalArgumentException("The book at " + settings.bookPath() +
              " holds no openings");
    }
    if (settings.openingIndex() >= 0) {
      if (settings.openingIndex() >= book.size()) {
        throw new IllegalArgumentException("The book holds " + book.size() +
                " openings, so there is none at index " + settings.openingIndex());
      }
      lines.add(new BookLine(settings.openingIndex(), book.get(settings.openingIndex())));
      return lines;
    }
    if (settings.pairs() > book.size()) {
      throw new IllegalArgumentException("The book holds " + book.size() + " openings, so " +
              settings.pairs() + " pairs cannot be played without repeating one");
    }

    final List<Integer> order = new ArrayList<>(book.size());
    for (int index = 0; index < book.size(); index++) {
      order.add(index);
    }
    Collections.shuffle(order, new Random(settings.seed()));
    for (int pair = 0; pair < settings.pairs(); pair++) {
      final int index = order.get(pair);
      lines.add(new BookLine(index, book.get(index)));
    }
    return lines;
  }

  /**
   * Reports the settings the match is played under.
   *
   * @param settings The settings the match is played under.
   * @param lines The openings the match is played from.
   */
  private static void printHeader(final Settings settings, final List<BookLine> lines) {
    System.out.println("Engine A: " + settings.engineACommand());
    System.out.println("Engine B: " + settings.engineBCommand());
    final String limit;
    if (settings.timeControl() != null) {
      limit = settings.timeControl().description() + " per game";
    } else if (settings.limitA().equals(settings.limitB())) {
      limit = settings.limitA().description() + " per move";
    } else {
      limit = settings.limitA().description() + " for " + ENGINE_A_NAME + " and " +
              settings.limitB().description() + " for " + ENGINE_B_NAME + " per move";
    }
    System.out.println("Limit: " + limit + ", hash " + settings.tableSizeMB() + " MB, " +
            SEARCH_THREADS + " search thread");
    if (!settings.useBook()) {
      System.out.println("Openings: the standard starting position");
    } else if (settings.openingIndex() >= 0) {
      System.out.println("Openings: line " + settings.openingIndex() + " of " +
              settings.bookPath());
    } else {
      System.out.println("Openings: drawn from " + settings.bookPath() + " with seed " +
              settings.seed());
    }
    System.out.println("Games: " + lines.size() * GAMES_PER_PAIR + " over " + lines.size() +
            " pairs, ply cap " + settings.plyCap() + ", timeout " + settings.timeoutSeconds() +
            "s");
    final int workers = workerCount(settings, lines);
    System.out.println("Workers: " + workers + " pairs at once, " + workers * 2 +
            " engine processes, " + workers * 2 * settings.tableSizeMB() + " MB of hash in total");
    if (settings.adjudicate()) {
      System.out.println("Adjudication: a win at " + settings.resignScore() + " over " +
              settings.resignPlies() + " plies, a draw within " + settings.drawScore() + " over " +
              settings.drawPlies() + " plies from ply " + settings.drawAfter());
    } else {
      System.out.println("Adjudication: off");
    }
    final MatchStatistics.SequentialTest test = settings.sequentialTest();
    if (test == null) {
      System.out.println("Sequential test: off");
    } else {
      System.out.printf("Sequential test: %.1f Elo against %.1f Elo, error rates %.3f and %.3f, " +
                      "bounds %+.2f to %+.2f%n", test.nullElo(), test.gainElo(),
              test.falsePositiveRate(), test.falseNegativeRate(), test.lowerBound(),
              test.upperBound());
    }
    System.out.println();
  }

  /**
   * Writes the opening a pair is played from into the lines reporting the pair.
   *
   * @param report The lines reporting the pair, which this method appends to.
   * @param pair The number of the pair, counting from one.
   * @param line The opening the pair is played from.
   */
  private static void reportPair(final List<String> report, final int pair, final BookLine line) {
    if (line.opening() == null) {
      report.add("Pair " + pair + ": the standard starting position");
      return;
    }
    report.add("Pair " + pair + ": line " + line.index() + ", " + line.opening().eco() +
            " " + line.opening().name() + ", " + String.join(" ", line.opening().moves()));
  }

  /**
   * Writes how one game ended into the lines reporting its pair.
   *
   * @param report The lines reporting the pair, which this method appends to.
   * @param game The number of the game within its pair, counting from one.
   * @param engineAIsWhite True if engine A played White.
   * @param result How the game ended.
   * @param elapsedMillis The time the game took, in milliseconds.
   * @param verbose True to report the moves the game held.
   */
  private static void reportGame(final List<String> report, final int game,
                                 final boolean engineAIsWhite, final Result result,
                                 final long elapsedMillis, final boolean verbose) {
    report.add(String.format("  game %d  White %s  %-7s  %3d plies  %7.1fs  %s", game,
            engineAIsWhite ? ENGINE_A_NAME : ENGINE_B_NAME, scoreOf(result.outcome()),
            result.moves().size(), elapsedMillis / 1000.0, result.reason()));
    if (verbose) {
      report.add("  moves: " + String.join(" ", result.moves()));
    }
  }

  /**
   * Writes one move into the lines reporting its pair. The score column holds the score from
   * White's point of view, not from the point of view of the engine that reported it.
   *
   * @param report The lines reporting the pair, which this method appends to.
   * @param ply The number of the ply just played, counting the moves of the opening.
   * @param whiteMoved True if White played the move.
   * @param reply What the engine reported.
   */
  private static void reportMove(final List<String> report, final int ply,
                                 final boolean whiteMoved, final EngineProcess.Reply reply) {
    report.add(String.format("%6d  %s  %-6s  %4s  %9s  %7.2fs", ply, whiteMoved ? "W" : "B",
            reply.move(), reply.depth() == null ? "-" : String.valueOf(reply.depth()),
            scoreText(reply, whiteMoved), reply.elapsedMillis() / 1000.0));
  }

  /**
   * Names what an engine reported about the position it moved from, written from White's point
   * of view. Engines report scores from the point of view of the side to move, so a score
   * reported with a Black move is negated here.
   *
   * @param reply What the engine reported.
   * @param whiteMoved True if White played the move the score was reported with.
   * @return The distance to mate, the score in centipawns, or a dash if the engine reported
   *         neither, each from White's point of view.
   */
  private static String scoreText(final EngineProcess.Reply reply, final boolean whiteMoved) {
    if (reply.mateIn() != null) {
      return "mate " + (whiteMoved ? reply.mateIn() : -reply.mateIn());
    }
    if (reply.score() != null) {
      return "cp " + (whiteMoved ? reply.score() : -reply.score());
    }
    return "-";
  }

  /**
   * Reports what the games of the match came to and the Elo difference they measure. A match the
   * sequential test stopped reports the test as it stood when it settled, then the ratio over every
   * game counted. The mean time a game took is reported alongside the number of pairs played at
   * once, since a game played beside others shares the machine with them and is only comparable
   * with a game timed the same way.
   *
   * @param tally What the games came to.
   * @param pairs The number of pairs the match was to hold.
   * @param workers The number of pairs the match played at once.
   * @param elapsedMillis The time the whole match took, in milliseconds.
   * @param test The hypotheses the games are weighed between, or null if the match held no test.
   */
  private static void printTally(final Tally tally, final int pairs, final int workers,
                                 final long elapsedMillis,
                                 final MatchStatistics.SequentialTest test) {
    final double points = tally.engineAWins() + tally.draws() / 2.0;
    System.out.println();
    System.out.println("Played: " + tally.played() + " of " + pairs * GAMES_PER_PAIR + " games");
    System.out.println("A wins " + tally.engineAWins() + ", B wins " + tally.engineBWins() +
            ", drawn " + tally.draws());
    if (tally.adjudicated() > 0) {
      System.out.println("Adjudicated: " + tally.adjudicated() + " of " + tally.played() +
              " games");
    }
    if (tally.engineATimeLosses() + tally.engineBTimeLosses() > 0) {
      System.out.println("Lost on time: " + ENGINE_A_NAME + " " + tally.engineATimeLosses() +
              ", " + ENGINE_B_NAME + " " + tally.engineBTimeLosses());
    }
    if (tally.played() > 0) {
      System.out.printf("Score for A: %.1f of %d, %.2f percent%n", points, tally.played(),
              100.0 * points / tally.played());
      printSearchTotals(tally, elapsedMillis, workers);
      final MatchStatistics statistics = new MatchStatistics(tally.engineAWins(),
              tally.engineBWins(), tally.draws());
      final MatchStatistics settlement = tally.settlement();
      final List<String> report;
      if (test == null) {
        report = statistics.report(ENGINE_A_NAME);
      } else if (settlement == null) {
        report = statistics.report(ENGINE_A_NAME, test);
      } else {
        report = statistics.report(ENGINE_A_NAME);
        report.addAll(settlement.testReport(test));
        if (settlement.games() < statistics.games()) {
          report.add(String.format("Over all %d games, including the pairs already being played " +
                  "when the test settled, the LLR is %+.2f", statistics.games(),
                  statistics.logLikelihoodRatio(test)));
        }
      }
      for (final String line : report) {
        System.out.println(line);
      }
    }
  }

  /**
   * Reports the mean depth of the searches each engine carried out, the mean time a game took, and
   * the time the whole match took. The mean time counts only the games that reached a result,
   * while the time the match took holds everything the match did.
   *
   * @param tally What the games came to.
   * @param elapsedMillis The time the whole match took, in milliseconds.
   * @param workers The number of pairs the match played at once.
   */
  private static void printSearchTotals(final Tally tally, final long elapsedMillis,
                                        final int workers) {
    System.out.println("Mean depth: " + ENGINE_A_NAME + " " + depthText(tally.engineATotals()) +
            ", " + ENGINE_B_NAME + " " + depthText(tally.engineBTotals()) + ", over " +
            tally.engineATotals().searches() + " and " + tally.engineBTotals().searches() +
            " searches");
    System.out.println("Mean time: " + timeText(tally.gameMillis() / tally.played()) +
            " per game, " + timeText(elapsedMillis) + " in total, at " + workers +
            (workers == 1 ? " pair" : " pairs") + " at once");
  }

  /**
   * Names the mean depth of a set of searches.
   *
   * @param totals What the searches came to.
   * @return The mean depth, or a dash if no search reported a depth.
   */
  private static String depthText(final SearchTotals totals) {
    return totals.depthSearches() == 0 ? "-" : String.format("%.2f", totals.meanDepth());
  }

  /**
   * Names a length of time, in hours, minutes and seconds as far as it reaches.
   *
   * @param millis The length of time, in milliseconds.
   * @return The length of time written out.
   */
  private static String timeText(final long millis) {
    final long seconds = Math.round(millis / 1000.0);
    if (seconds >= 3600) {
      return String.format("%dh %02dm %02ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
    }
    if (seconds >= 60) {
      return String.format("%dm %02ds", seconds / 60, seconds % 60);
    }
    return String.format("%.1fs", millis / 1000.0);
  }

  /**
   * Names an outcome as a score from White's point of view.
   *
   * @param outcome How a game ended.
   * @return The score the outcome is written as.
   */
  private static String scoreOf(final Outcome outcome) {
    return switch (outcome) {
      case WHITE_WINS -> "1-0";
      case BLACK_WINS -> "0-1";
      case DRAW -> "1/2-1/2";
      case FAILURE -> "failed";
    };
  }

  /**
   * Reports how this class is run.
   */
  private static void printUsage() {
    System.out.println("Usage: SelfPlayMatch --engine-a <command> --engine-b <command> [options]");
    System.out.println();
    System.out.println("  --engine-a <command> the command line starting the first engine");
    System.out.println("  --engine-b <command> the command line starting the second engine");
    System.out.println("  --pairs <count>      openings to play, each of them played twice with " +
            "the colours reversed, " + DEFAULT_PAIRS + " by default");
    System.out.println("  --seed <number>      the seed the book shuffle uses, " + DEFAULT_SEED +
            " by default");
    System.out.println("  --concurrency <n>    pairs played at once, " + DEFAULT_CONCURRENCY +
            " by default");
    System.out.println("  --opening <index>    play only this book line, as one pair");
    System.out.println("  --nobook             play from the standard starting position");
    System.out.println("  --nodes <count>      nodes per move for both engines, " +
            DEFAULT_NODE_LIMIT + " by default");
    System.out.println("  --nodes-a <count>    nodes per move for the first engine, overriding " +
            "--nodes");
    System.out.println("  --nodes-b <count>    nodes per move for the second engine, overriding " +
            "--nodes");
    System.out.println("  --movetime <ms>      milliseconds per move for both engines, in place " +
            "of --nodes");
    System.out.println("  --movetime-a <ms>    milliseconds per move for the first engine, " +
            "overriding --movetime");
    System.out.println("  --movetime-b <ms>    milliseconds per move for the second engine, " +
            "overriding --movetime");
    System.out.println("  --tc <base>[+<inc>]  a game clock for both engines, the base and " +
            "increment in seconds, such as 10+0.1, in place of --nodes");
    System.out.println("  --hash <megabytes>   transposition table size, " + DEFAULT_TABLE_SIZE_MB +
            " by default");
    System.out.println("  --book <path>        the opening book, " + OpeningBook.DEFAULT_BOOK_PATH +
            " by default");
    System.out.println("  --plycap <count>     the longest game allowed, " + DEFAULT_PLY_CAP +
            " by default");
    System.out.println("  --no-adjudication    play every game out on the board");
    System.out.println("  --resign-score <cp>  the lead a ply counts as decisive at, " +
            DEFAULT_RESIGN_SCORE + " by default");
    System.out.println("  --resign-plies <n>   decisive plies that win a game, " +
            DEFAULT_RESIGN_PLIES + " by default");
    System.out.println("  --draw-score <cp>    the score a ply counts as level within, " +
            DEFAULT_DRAW_SCORE + " by default");
    System.out.println("  --draw-plies <n>     level plies that draw a game, " +
            DEFAULT_DRAW_PLIES + " by default");
    System.out.println("  --draw-after <ply>   the ply a draw may first be given at, " +
            DEFAULT_DRAW_AFTER + " by default");
    System.out.println("  --sprt               stop the match once the sequential test settles " +
            "it, off by default");
    System.out.println("  --elo0 <elo>         the Elo difference the null hypothesis holds, " +
            DEFAULT_NULL_ELO + " by default");
    System.out.println("  --elo1 <elo>         the Elo difference the alternative holds, " +
            DEFAULT_GAIN_ELO + " by default");
    System.out.println("  --alpha <rate>       the chance of accepting the alternative when the " +
            "null holds, " + DEFAULT_FALSE_POSITIVE_RATE + " by default");
    System.out.println("  --beta <rate>        the chance of accepting the null when the " +
            "alternative holds, " + DEFAULT_FALSE_NEGATIVE_RATE + " by default");
    System.out.println("  --timeout <seconds>  the longest wait for an answer, " +
            DEFAULT_TIMEOUT_SECONDS + " by default");
    System.out.println("  --logs <directory>   where the engine logs are written, " +
            DEFAULT_LOG_DIRECTORY + " by default");
    System.out.println("  --results <file>     where every finished pair is recorded, " +
            DEFAULT_RESULTS_FILE_NAME + " in the log directory by default");
    System.out.println("  --resume             continue the match the results file records");
    System.out.println("  --verbose            report every move and the moves of every game");
    System.out.println("  --help               print this message");
    System.out.println();
    System.out.println("The sequential test is weighed at the end of a pair, so a match it stops");
    System.out.println("holds fewer pairs than --pairs asked for. The value of --pairs is the");
    System.out.println("budget the test is given rather than the number of pairs it will use.");
    System.out.println();
    System.out.println("A match is played under node limits, move times or a time control, and");
    System.out.println("only one of them. A move time or a clock is what an engine spends rather");
    System.out.println("than what it searches, so a match under one is only as repeatable as the");
    System.out.println("load on the machine is, and playing more pairs at once than the machine");
    System.out.println("has cores leaves every engine searching less than its time suggests.");
    System.out.println();
    System.out.println("Under a time control the arbiter keeps the clocks, and a side whose search");
    System.out.println("takes longer than its clock held loses on time. The value of --timeout");
    System.out.println("must be longer than the base time.");
    System.out.println();
    System.out.println("A pair already being played when the match stops is played to the end and");
    System.out.println("counted, so a match on several workers may hold more pairs than the same");
    System.out.println("match on one. Each worker holds two engine processes and each process");
    System.out.println("holds its own hash, so --concurrency multiplies the memory a match needs.");
    System.out.println();
    System.out.println("A fresh match renames an existing results file to <name>.previous.<ext>");
    System.out.println("before writing its own. A resumed match must be given the same settings,");
    System.out.println("apart from --concurrency, --timeout, --logs and --verbose, and the same");
    System.out.println("engines: a working tree jar keeps its path when the tree changes.");
    System.out.println();
    System.out.println("Engine A plays White in the first game of every pair. An engine command");
    System.out.println("line is one argument. Whitespace inside it separates its arguments except");
    System.out.println("inside double quotes, so a path holding a space can be written as");
    System.out.println("\"--engine-a\" \"java -jar \"\"C:\\My Builds\\engine.jar\"\"\".");
  }

  /**
   * The Outcome enum names how a game ended from White's point of view.
   */
  public enum Outcome {

    /** White won. */
    WHITE_WINS,

    /** Black won. */
    BLACK_WINS,

    /** Neither side won. */
    DRAW,

    /** An engine failed, so the game has no result. */
    FAILURE
  }

  /**
   * The Result record holds how one game ended.
   *
   * @param outcome How the game ended from White's point of view.
   * @param reason Why the game ended.
   * @param moves The moves the game held, including the moves of the opening.
   * @param adjudicated True if the game ended on the reported scores rather than on the board.
   * @param lostOnTime True if the side that lost ran out of time on its clock.
   */
  public record Result(Outcome outcome, String reason, List<String> moves, boolean adjudicated,
                       boolean lostOnTime) {

    /**
     * Constructs a result holding a copy of the moves given.
     */
    public Result {
      moves = List.copyOf(moves);
    }

    /**
     * Constructs a result for a game that was not lost on time.
     *
     * @param outcome How the game ended from White's point of view.
     * @param reason Why the game ended.
     * @param moves The moves the game held, including the moves of the opening.
     * @param adjudicated True if the game ended on the reported scores rather than on the board.
     */
    public Result(final Outcome outcome, final String reason, final List<String> moves,
                  final boolean adjudicated) {
      this(outcome, reason, moves, adjudicated, false);
    }

    /**
     * Constructs a result for a game that ended on the board.
     *
     * @param outcome How the game ended from White's point of view.
     * @param reason Why the game ended.
     * @param moves The moves the game held, including the moves of the opening.
     */
    public Result(final Outcome outcome, final String reason, final List<String> moves) {
      this(outcome, reason, moves, false);
    }
  }

  /**
   * The Tally record holds what the games of a match came to.
   *
   * @param engineAWins The games engine A won.
   * @param engineBWins The games engine B won.
   * @param draws The games neither engine won.
   * @param played The games that reached a result.
   * @param adjudicated The games that ended on the reported scores rather than on the board.
   * @param engineATimeLosses The games engine A lost on time.
   * @param engineBTimeLosses The games engine B lost on time.
   * @param engineATotals What the searches of the first engine came to.
   * @param engineBTotals What the searches of the second engine came to.
   * @param gameMillis The time the games that reached a result took, in milliseconds, summed.
   * @param settlement What the games had come to when they first settled the sequential test, or
   *                   null if they never settled it.
   * @param failure What stopped the match, or null if every game reached a result.
   */
  public record Tally(int engineAWins, int engineBWins, int draws, int played, int adjudicated,
                      int engineATimeLosses, int engineBTimeLosses, SearchTotals engineATotals,
                      SearchTotals engineBTotals, long gameMillis, MatchStatistics settlement,
                      String failure) {
  }

  /**
   * The PairResult record holds what the games of one pair came to and the lines reporting them.
   *
   * @param engineAWins The games engine A won.
   * @param engineBWins The games engine B won.
   * @param draws The games neither engine won.
   * @param played The games that reached a result.
   * @param adjudicated The games that ended on the reported scores rather than on the board.
   * @param engineATimeLosses The games engine A lost on time.
   * @param engineBTimeLosses The games engine B lost on time.
   * @param engineATotals What the searches of the first engine came to.
   * @param engineBTotals What the searches of the second engine came to.
   * @param gameMillis The time the games that reached a result took, in milliseconds, summed.
   * @param failure What stopped the pair, or null if both games reached a result.
   * @param report The lines reporting the pair, in the order they are written out.
   */
  private record PairResult(int engineAWins, int engineBWins, int draws, int played,
                            int adjudicated, int engineATimeLosses, int engineBTimeLosses,
                            SearchTotals engineATotals, SearchTotals engineBTotals,
                            long gameMillis, String failure, List<String> report) {
  }

  /**
   * The SearchTotals class holds the depths a set of searches by one engine reached. A search
   * reporting no depth is counted and left out of the depth, so a mean depth is a mean over the
   * searches that reported one.
   */
  public static final class SearchTotals {

    /** The searches folded in. */
    private int searches;

    /** The searches folded in that reported a depth. */
    private int depthSearches;

    /** The depths those searches reported, summed. */
    private long depth;

    /**
     * Folds one search into the totals.
     *
     * @param reply What the engine reported for the search.
     */
    private void add(final EngineProcess.Reply reply) {
      this.searches++;
      if (reply.depth() != null) {
        this.depthSearches++;
        this.depth += reply.depth();
      }
    }

    /**
     * Folds another set of totals into these.
     *
     * @param other What the other searches came to.
     */
    private void add(final SearchTotals other) {
      this.searches += other.searches;
      this.depthSearches += other.depthSearches;
      this.depth += other.depth;
    }

    /**
     * Returns the number of searches folded in.
     *
     * @return The searches folded in.
     */
    public int searches() {
      return this.searches;
    }

    /**
     * Returns the number of searches folded in that reported a depth.
     *
     * @return The searches folded in that reported a depth.
     */
    public int depthSearches() {
      return this.depthSearches;
    }

    /**
     * Returns the mean depth of the searches that reported one.
     *
     * @return The mean depth, or zero if no search reported a depth.
     */
    public double meanDepth() {
      return this.depthSearches == 0 ? 0 : (double) this.depth / this.depthSearches;
    }
  }

  /**
   * The Accumulator class holds what the pairs played so far have come to.
   */
  private static final class Accumulator {

    /** The games engine A has won. */
    private int engineAWins;

    /** The games engine B has won. */
    private int engineBWins;

    /** The games neither engine has won. */
    private int draws;

    /** The games that have reached a result. */
    private int played;

    /** The games that ended on the reported scores rather than on the board. */
    private int adjudicated;

    /** The games engine A has lost on time. */
    private int engineATimeLosses;

    /** The games engine B has lost on time. */
    private int engineBTimeLosses;

    /** What the searches of the first engine have come to. */
    private final SearchTotals engineATotals = new SearchTotals();

    /** What the searches of the second engine have come to. */
    private final SearchTotals engineBTotals = new SearchTotals();

    /** The time the games that reached a result took, in milliseconds, summed. */
    private long gameMillis;

    /** What the games had come to when they first settled the sequential test, or null. */
    private MatchStatistics settlement;

    /**
     * Folds the games of one pair into the running count.
     *
     * @param result What the games of the pair came to.
     */
    private void add(final PairResult result) {
      this.engineAWins += result.engineAWins();
      this.engineBWins += result.engineBWins();
      this.draws += result.draws();
      this.played += result.played();
      this.adjudicated += result.adjudicated();
      this.engineATimeLosses += result.engineATimeLosses();
      this.engineBTimeLosses += result.engineBTimeLosses();
      this.engineATotals.add(result.engineATotals());
      this.engineBTotals.add(result.engineBTotals());
      this.gameMillis += result.gameMillis();
    }

    /**
     * Weighs the sequential test on the games counted so far, keeping what they came to the first
     * time they settle it. Games counted after that do not change what is kept.
     *
     * @param test The hypotheses the games are weighed between.
     * @return True if the games have settled the test, on this call or an earlier one.
     */
    private boolean settle(final MatchStatistics.SequentialTest test) {
      if (this.settlement == null) {
        final MatchStatistics statistics = new MatchStatistics(this.engineAWins,
                this.engineBWins, this.draws);
        if (statistics.conclude(test) != MatchStatistics.Conclusion.UNDECIDED) {
          this.settlement = statistics;
        }
      }
      return this.settlement != null;
    }

    /**
     * Names what the pairs played so far have come to.
     *
     * @param failure What stopped the match, or null if every game reached a result.
     * @return The tally the match is reported from.
     */
    private Tally tally(final String failure) {
      return new Tally(this.engineAWins, this.engineBWins, this.draws, this.played,
              this.adjudicated, this.engineATimeLosses, this.engineBTimeLosses,
              this.engineATotals, this.engineBTotals, this.gameMillis, this.settlement, failure);
    }
  }

  /**
   * The Coordinator class hands pairs out to the workers of a match and holds what they came to.
   * Every method is synchronised, so a pair is reported and counted as one step and the lines of
   * two pairs never interleave.
   */
  private static final class Coordinator {

    /** The openings the match is played from, one for each pair. */
    private final List<BookLine> lines;

    /** The hypotheses the games are weighed between, or null if the match holds no test. */
    private final MatchStatistics.SequentialTest test;

    /** What the pairs finished so far have come to. */
    private final Accumulator accumulator;

    /** Whether each pair has reached a result, indexed by its position in the list of lines. */
    private final boolean[] finished;

    /** The results file each finished pair is recorded in. */
    private final ResultsFile results;

    /** The next pair to hand out, unless it has already reached a result. */
    private int cursor;

    /** What stopped the match, or null if nothing has. */
    private String failure;

    /** True once no further pair is to be handed out. */
    private boolean stopped;

    /**
     * Constructs a coordinator over the pairs of a match, starting from the pairs the results file
     * already records. A match whose recorded games have already settled the sequential test hands
     * out no pair.
     *
     * @param lines The openings the match is played from, one for each pair.
     * @param test The hypotheses the games are weighed between, or null if the match holds no
     *             test.
     * @param results The results file the finished pairs are recorded in.
     */
    private Coordinator(final List<BookLine> lines, final MatchStatistics.SequentialTest test,
                        final ResultsFile results) {
      this.lines = lines;
      this.test = test;
      this.results = results;
      this.accumulator = results.accumulator();
      this.finished = results.finished();
      this.stopped = this.accumulator.settlement != null;
    }

    /**
     * Reports whether a pair remains to be handed out.
     *
     * @return True if the match is not stopped and holds a pair that has not reached a result.
     */
    private synchronized boolean hasPairsLeft() {
      skipFinished();
      return !this.stopped && this.cursor < this.lines.size();
    }

    /**
     * Hands out the next pair to play, passing over pairs that have already reached a result.
     *
     * @return The position of the next opening in the list of lines, or minus one if the match
     *         holds no further pair to play.
     */
    private synchronized int nextPair() {
      skipFinished();
      if (this.stopped || this.cursor >= this.lines.size()) {
        return -1;
      }
      return this.cursor++;
    }

    /** Moves the cursor past the pairs that have already reached a result. */
    private void skipFinished() {
      while (this.cursor < this.lines.size() && this.finished[this.cursor]) {
        this.cursor++;
      }
    }

    /**
     * Reports one finished pair, counts its games, records it in the results file, and weighs the
     * sequential test on the games counted so far. A pair stopped by a failure is counted but not
     * recorded, so a resumed match plays it again.
     *
     * @param pair The position of the pair's opening in the list of lines.
     * @param result What the games of the pair came to and the lines reporting them.
     */
    private synchronized void finish(final int pair, final PairResult result) {
      for (final String line : result.report()) {
        System.out.println(line);
      }
      this.accumulator.add(result);
      if (result.failure() != null) {
        fail(result.failure());
        return;
      }
      try {
        this.results.record(pair, result);
      } catch (final IOException exception) {
        fail("the results file could not be written: " + exception.getMessage());
        return;
      }
      if (settled(this.test, this.accumulator)) {
        this.stopped = true;
      }
    }

    /**
     * Stops the match, keeping the first failure reported if more than one worker fails.
     *
     * @param message What stopped the match.
     */
    private synchronized void fail(final String message) {
      if (this.failure == null) {
        this.failure = message;
      }
      this.stopped = true;
    }

    /**
     * Names what the pairs of the match came to.
     *
     * @return The tally the match is reported from.
     */
    private synchronized Tally tally() {
      return this.accumulator.tally(this.failure);
    }
  }

  /**
   * The ResultsFile class records the pairs of a match in a file as they finish, and reads a file
   * back to resume the match it records. The file holds a title line, a line naming the settings
   * that decide the games, and one line for each finished pair. A line left incomplete by a crash
   * is passed over when the file is read.
   */
  private static final class ResultsFile {

    /** The file. */
    private final Path path;

    /** The writer each finished pair is appended through. */
    private final BufferedWriter writer;

    /** What the pairs the file already recorded when it was opened came to. */
    private final Accumulator accumulator;

    /** Whether the file recorded each pair when it was opened, indexed by pair. */
    private final boolean[] finished;

    /**
     * Constructs a results file that has been opened for writing.
     *
     * @param path The file.
     * @param writer The writer each finished pair is appended through.
     * @param accumulator What the pairs the file already recorded came to.
     * @param finished Whether the file recorded each pair, indexed by pair.
     */
    private ResultsFile(final Path path, final BufferedWriter writer,
                        final Accumulator accumulator, final boolean[] finished) {
      this.path = path;
      this.writer = writer;
      this.accumulator = accumulator;
      this.finished = finished;
    }

    /**
     * Opens the results file a match records its pairs in. A fresh match renames any file already
     * at the path to its previous name and writes a new one. A resumed match reads the pairs the
     * file records, weighing the sequential test after each in the order the file records them,
     * reports what they came to, and appends to the file.
     *
     * @param settings The settings the match is played under.
     * @param pairs The number of pairs the match holds.
     * @return The opened results file.
     * @throws IOException If the file cannot be read, renamed or written.
     * @throws IllegalArgumentException If a resumed match has no results file, or the file was
     *                                  written under other settings.
     */
    private static ResultsFile open(final Settings settings, final int pairs) throws IOException {
      final Path path = Path.of(settings.resultsPath());
      final String settingsLine = RESULTS_SETTINGS_WORD + " " + fingerprint(settings);
      final Accumulator accumulator = new Accumulator();
      final boolean[] finished = new boolean[pairs];

      if (!settings.resume()) {
        if (path.getParent() != null) {
          Files.createDirectories(path.getParent());
        }
        if (Files.exists(path)) {
          Files.move(path, previousPath(path), StandardCopyOption.REPLACE_EXISTING);
        }
        final BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writer.write(RESULTS_TITLE);
        writer.newLine();
        writer.write(settingsLine);
        writer.newLine();
        writer.flush();
        return new ResultsFile(path, writer, accumulator, finished);
      }

      if (!Files.exists(path)) {
        throw new IllegalArgumentException("The results file " + path + " does not exist, so " +
                "there is no match to resume");
      }
      final String content = Files.readString(path, StandardCharsets.UTF_8);
      final List<String> fileLines = content.lines().toList();
      final String recorded = fileLines.stream()
              .filter(line -> line.startsWith(RESULTS_SETTINGS_WORD + " "))
              .findFirst()
              .orElse("none");
      if (!settingsLine.equals(recorded)) {
        throw new IllegalArgumentException("The results file " + path + " records a match " +
                "played under other settings, so it cannot be resumed." +
                System.lineSeparator() + "  recorded: " + recorded +
                System.lineSeparator() + "  given:    " + settingsLine);
      }

      int recordedPairs = 0;
      int skippedLines = 0;
      for (final String line : fileLines) {
        if (!line.startsWith(RESULTS_PAIR_WORD + " ")) {
          continue;
        }
        final PairResult result = parsePair(line, pairs, finished);
        if (result == null) {
          skippedLines++;
          continue;
        }
        accumulator.add(result);
        if (settings.sequentialTest() != null) {
          accumulator.settle(settings.sequentialTest());
        }
        recordedPairs++;
      }

      final BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
              StandardOpenOption.APPEND);
      if (!content.isEmpty() && !content.endsWith("\n")) {
        writer.newLine();
        writer.flush();
      }
      reportResumption(path, recordedPairs, skippedLines, accumulator,
              settings.sequentialTest());
      return new ResultsFile(path, writer, accumulator, finished);
    }

    /**
     * Reads one pair line, marking its pair finished.
     *
     * @param line The line to read.
     * @param pairs The number of pairs the match holds.
     * @param finished Whether each pair has already been read, indexed by pair.
     * @return What the pair came to, or null if the line is incomplete, names no pair of the match,
     *         or repeats a pair already read.
     */
    private static PairResult parsePair(final String line, final int pairs,
                                        final boolean[] finished) {
      final String[] fields = line.trim().split(" ");
      if (fields.length != RESULTS_PAIR_FIELDS &&
              fields.length != RESULTS_PAIR_FIELDS_WITHOUT_TIME_LOSSES) {
        return null;
      }
      try {
        final int pair = Integer.parseInt(fields[1]);
        if (pair < 0 || pair >= pairs || finished[pair]) {
          return null;
        }
        final boolean timeLossesRecorded = fields.length == RESULTS_PAIR_FIELDS;
        final PairResult result = new PairResult(Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                Integer.parseInt(fields[5]), Integer.parseInt(fields[6]),
                timeLossesRecorded ? Integer.parseInt(fields[14]) : 0,
                timeLossesRecorded ? Integer.parseInt(fields[15]) : 0,
                parseTotals(fields, 8), parseTotals(fields, 11), Long.parseLong(fields[7]), null,
                List.of());
        finished[pair] = true;
        return result;
      } catch (final NumberFormatException exception) {
        return null;
      }
    }

    /**
     * Reads the search totals of one engine from the fields of a pair line.
     *
     * @param fields The fields of the line.
     * @param first The position of the first of the three fields holding the totals.
     * @return The search totals.
     * @throws NumberFormatException If a field is not a number.
     */
    private static SearchTotals parseTotals(final String[] fields, final int first) {
      final SearchTotals totals = new SearchTotals();
      totals.searches = Integer.parseInt(fields[first]);
      totals.depthSearches = Integer.parseInt(fields[first + 1]);
      totals.depth = Long.parseLong(fields[first + 2]);
      return totals;
    }

    /**
     * Reports what the pairs read back from a results file came to.
     *
     * @param path The file.
     * @param recordedPairs The number of pairs read back.
     * @param skippedLines The number of pair lines passed over.
     * @param accumulator What the pairs read back came to.
     * @param test The hypotheses the games are weighed between, or null if the match holds no test.
     */
    private static void reportResumption(final Path path, final int recordedPairs,
                                         final int skippedLines, final Accumulator accumulator,
                                         final MatchStatistics.SequentialTest test) {
      System.out.println("Resumed from " + path + ": " + recordedPairs + " pairs, " +
              accumulator.played + " games, A wins " + accumulator.engineAWins + ", B wins " +
              accumulator.engineBWins + ", drawn " + accumulator.draws +
              (skippedLines == 0 ? "" : ", " + skippedLines + " unreadable pair " +
                      (skippedLines == 1 ? "line" : "lines") + " passed over"));
      if (test != null && accumulator.played > 0) {
        final MatchStatistics statistics = new MatchStatistics(accumulator.engineAWins,
                accumulator.engineBWins, accumulator.draws);
        System.out.printf("  test    LLR %+.2f of %+.2f to %+.2f after %d games%n",
                statistics.logLikelihoodRatio(test), test.lowerBound(), test.upperBound(),
                statistics.games());
      }
      if (accumulator.settlement != null) {
        System.out.println("The recorded games settled the sequential test after " +
                accumulator.settlement.games() + " games, so no pair is played");
      }
      System.out.println();
    }

    /**
     * Appends one finished pair to the file and flushes it.
     *
     * @param pair The position of the pair's opening in the list of lines.
     * @param result What the games of the pair came to.
     * @throws IOException If the file cannot be written.
     */
    private void record(final int pair, final PairResult result) throws IOException {
      final SearchTotals a = result.engineATotals();
      final SearchTotals b = result.engineBTotals();
      this.writer.write(String.format("%s %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d",
              RESULTS_PAIR_WORD, pair, result.engineAWins(), result.engineBWins(), result.draws(),
              result.played(), result.adjudicated(), result.gameMillis(), a.searches,
              a.depthSearches, a.depth, b.searches, b.depthSearches, b.depth,
              result.engineATimeLosses(), result.engineBTimeLosses()));
      this.writer.newLine();
      this.writer.flush();
    }

    /**
     * Returns what the pairs the file recorded when it was opened came to. The accumulator is the
     * one the match goes on counting into.
     *
     * @return The accumulator.
     */
    private Accumulator accumulator() {
      return this.accumulator;
    }

    /**
     * Returns whether the file recorded each pair when it was opened. The array is the one the
     * match reads.
     *
     * @return Whether each pair was recorded, indexed by pair.
     */
    private boolean[] finished() {
      return this.finished;
    }

    /** Closes the file, reporting rather than throwing if it cannot be closed. */
    private void close() {
      try {
        this.writer.close();
      } catch (final IOException exception) {
        System.out.println("The results file " + this.path + " could not be closed: " +
                exception.getMessage());
      }
    }

    /**
     * Names the settings that decide the games of a match, which a resumed match must share.
     *
     * @param settings The settings the match is played under.
     * @return The settings written out on one line.
     */
    private static String fingerprint(final Settings settings) {
      final MatchStatistics.SequentialTest test = settings.sequentialTest();
      final String limits = settings.timeControl() != null ?
              "time-control " + settings.timeControl().description() :
              "limit-a " + settings.limitA().description() + " | " +
                      "limit-b " + settings.limitB().description();
      return String.join(" | ",
              "engine-a " + settings.engineACommand(),
              "engine-b " + settings.engineBCommand(),
              limits,
              "hash " + settings.tableSizeMB(),
              "book " + (settings.useBook() ? settings.bookPath() : "off"),
              "pairs " + settings.pairs(),
              "seed " + settings.seed(),
              "opening " + settings.openingIndex(),
              "plycap " + settings.plyCap(),
              "adjudication " + (settings.adjudicate() ?
                      settings.resignScore() + " " + settings.resignPlies() + " " +
                              settings.drawScore() + " " + settings.drawPlies() + " " +
                              settings.drawAfter() :
                      "off"),
              "test " + (test == null ? "off" :
                      test.nullElo() + " " + test.gainElo() + " " + test.falsePositiveRate() +
                              " " + test.falseNegativeRate()));
    }

    /**
     * Names the file an existing results file is renamed to when a fresh match starts: the same
     * name with ".previous" before its extension.
     *
     * @param path The results file.
     * @return The path it is renamed to.
     */
    private static Path previousPath(final Path path) {
      final String name = path.getFileName().toString();
      final int dot = name.lastIndexOf('.');
      return path.resolveSibling(dot > 0 ?
              name.substring(0, dot) + ".previous" + name.substring(dot) :
              name + ".previous");
    }
  }

  /**
   * The BookLine record pairs an opening with the place it was read from in the book.
   *
   * @param index The position of the opening in the book, or minus one for the standard starting
   *              position.
   * @param opening The opening, or null for the standard starting position.
   */
  private record BookLine(int index, OpeningBook.Opening opening) {
  }

  /**
   * The TimeControl record holds the game clock both engines play on: a base time each side
   * starts with and an increment added to a side's clock after each of its moves.
   *
   * @param baseMillis The time each side starts the game with, in milliseconds.
   * @param incrementMillis The time added to a side's clock after each of its moves, in
   *                        milliseconds.
   */
  public record TimeControl(long baseMillis, long incrementMillis) {

    /**
     * Reads a time control written as a base time in seconds, optionally followed by a plus sign
     * and an increment in seconds, such as 10+0.1. Times are rounded to the millisecond.
     *
     * @param value The time control as written on the command line.
     * @param argument The argument the value follows, used in the fault message.
     * @return The time control the value states.
     * @throws IllegalArgumentException If the value is not written that way, the base time is not
     *                                  at least one millisecond, or the increment is negative.
     */
    private static TimeControl parse(final String value, final String argument) {
      final int plus = value.indexOf('+');
      final String base = plus < 0 ? value : value.substring(0, plus);
      final String increment = plus < 0 ? "0" : value.substring(plus + 1);
      final long baseMillis;
      final long incrementMillis;
      try {
        baseMillis = Math.round(Double.parseDouble(base) * 1000);
        incrementMillis = Math.round(Double.parseDouble(increment) * 1000);
      } catch (final NumberFormatException exception) {
        throw new IllegalArgumentException("The value of " + argument + " is not a time " +
                "control such as 10+0.1: " + value);
      }
      if (baseMillis < 1 || incrementMillis < 0) {
        throw new IllegalArgumentException("The value of " + argument + " must hold a base " +
                "time of at least one millisecond and an increment that is not negative: " +
                value);
      }
      return new TimeControl(baseMillis, incrementMillis);
    }

    /**
     * Describes this time control as it is reported to a reader, in seconds.
     *
     * @return The base time and the increment, joined by a plus sign and followed by s.
     */
    public String description() {
      return seconds(this.baseMillis) + "+" + seconds(this.incrementMillis) + " s";
    }

    /**
     * Writes a number of milliseconds as seconds with no trailing zeros.
     *
     * @param millis The time in milliseconds.
     * @return The time in seconds.
     */
    private static String seconds(final long millis) {
      return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
    }
  }

  /**
   * The Settings record holds what a match is played under.
   *
   * @param engineACommand The command line starting the first engine.
   * @param engineBCommand The command line starting the second engine.
   * @param limitA What stops a search by the first engine, or null under a time control.
   * @param limitB What stops a search by the second engine, or null under a time control.
   * @param timeControl The game clock both engines play on, or null if each search is held to a
   *                    limit instead.
   * @param tableSizeMB The transposition table size in megabytes given to each engine.
   * @param bookPath The path of the opening book.
   * @param pairs The number of openings played, each of them played twice.
   * @param seed The seed the book shuffle uses.
   * @param openingIndex The only book line to play, or minus one to draw lines from the shuffle.
   * @param useBook False to play every game from the standard starting position.
   * @param plyCap The largest number of plies a game may reach.
   * @param adjudicate True to end a game the engines agree is decided or is level.
   * @param resignScore The score a side must be ahead by for a ply to count towards a win.
   * @param resignPlies The number of consecutive plies at the resign score that win a game.
   * @param drawScore The score a position must be within for a ply to count towards a draw.
   * @param drawPlies The number of consecutive plies within the draw score that draw a game.
   * @param drawAfter The ply a game must have reached before it may be drawn on score.
   * @param sequentialTest The hypotheses the games are weighed between, or null to play every
   *                       pair asked for.
   * @param concurrency The number of pairs played at once.
   * @param timeoutSeconds The longest an engine may take to answer.
   * @param logDirectory The directory the engine logs are written to.
   * @param resultsPath The file every finished pair is appended to.
   * @param resume True to continue the match the results file records rather than start anew.
   * @param verbose True to report every move and the moves of every game.
   */
  public record Settings(String engineACommand, String engineBCommand, EngineProcess.Limit limitA,
                         EngineProcess.Limit limitB, TimeControl timeControl, int tableSizeMB,
                         String bookPath, int pairs,
                         long seed, int openingIndex, boolean useBook, int plyCap,
                         boolean adjudicate, int resignScore, int resignPlies, int drawScore,
                         int drawPlies, int drawAfter,
                         MatchStatistics.SequentialTest sequentialTest, int concurrency,
                         long timeoutSeconds, String logDirectory, String resultsPath,
                         boolean resume, boolean verbose) {

    /**
     * Reads settings from command line arguments. The argument --nodes sets the node limit of both
     * engines, and --nodes-a and --nodes-b each set the limit of one engine and override --nodes
     * whatever order they are given in. The argument --movetime and its two counterparts state a
     * move time the same way, in place of a node limit rather than alongside one, and --tc states
     * a time control for both engines in place of either. Adjudication is on unless
     * --no-adjudication is given, and the sequential test is off unless --sprt is given.
     *
     * @param args The command line arguments, as described by the usage text.
     * @return The settings the arguments describe, or null if they asked for the usage text.
     * @throws IllegalArgumentException If an argument is unrecognised, names no value, holds a
     *                                  value that is not a number, if either engine command line
     *                                  is missing, if fewer than one pair is asked for, if
     *                                  arguments naming different openings are given together, if
     *                                  an adjudication value is out of range, if an adjudication
     *                                  value is given with --no-adjudication, if fewer than one
     *                                  pair is played at once, if a sequential
     *                                  test value is given without --sprt, if the sequential
     *                                  test values do not state a test, if more than one of node
     *                                  limits, move times and a time control are given, if a move
     *                                  time is given for one engine alone, if a time control is
     *                                  not written as one, or if a move time or the base time of
     *                                  a time control is not shorter than the timeout.
     */
    public static Settings read(final String[] args) {
      String engineACommand = null;
      String engineBCommand = null;
      Long nodeLimitBoth = null;
      Long nodeLimitA = null;
      Long nodeLimitB = null;
      String nodeArgument = null;
      Long moveTimeBoth = null;
      Long moveTimeA = null;
      Long moveTimeB = null;
      String moveTimeArgument = null;
      TimeControl timeControl = null;
      int tableSizeMB = DEFAULT_TABLE_SIZE_MB;
      String bookPath = OpeningBook.DEFAULT_BOOK_PATH;
      int pairs = DEFAULT_PAIRS;
      boolean pairsGiven = false;
      long seed = DEFAULT_SEED;
      int openingIndex = -1;
      boolean useBook = true;
      int plyCap = DEFAULT_PLY_CAP;
      boolean adjudicate = true;
      int resignScore = DEFAULT_RESIGN_SCORE;
      int resignPlies = DEFAULT_RESIGN_PLIES;
      int drawScore = DEFAULT_DRAW_SCORE;
      int drawPlies = DEFAULT_DRAW_PLIES;
      int drawAfter = DEFAULT_DRAW_AFTER;
      String tuningArgument = null;
      boolean sprt = false;
      double nullElo = DEFAULT_NULL_ELO;
      double gainElo = DEFAULT_GAIN_ELO;
      double falsePositiveRate = DEFAULT_FALSE_POSITIVE_RATE;
      double falseNegativeRate = DEFAULT_FALSE_NEGATIVE_RATE;
      String testArgument = null;
      int concurrency = DEFAULT_CONCURRENCY;
      long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
      String logDirectory = DEFAULT_LOG_DIRECTORY;
      String resultsPath = null;
      boolean resume = false;
      boolean verbose = false;

      for (int index = 0; index < args.length; index++) {
        final String argument = args[index];
        if ("--help".equals(argument)) {
          return null;
        }
        if ("--nobook".equals(argument)) {
          useBook = false;
          continue;
        }
        if ("--no-adjudication".equals(argument)) {
          adjudicate = false;
          continue;
        }
        if ("--sprt".equals(argument)) {
          sprt = true;
          continue;
        }
        if ("--verbose".equals(argument)) {
          verbose = true;
          continue;
        }
        if ("--resume".equals(argument)) {
          resume = true;
          continue;
        }
        final String value = valueOf(args, index, argument);
        switch (argument) {
          case "--engine-a" -> engineACommand = value;
          case "--engine-b" -> engineBCommand = value;
          case "--nodes" -> {
            nodeLimitBoth = number(value, argument);
            nodeArgument = argument;
          }
          case "--nodes-a" -> {
            nodeLimitA = number(value, argument);
            nodeArgument = argument;
          }
          case "--nodes-b" -> {
            nodeLimitB = number(value, argument);
            nodeArgument = argument;
          }
          case "--movetime" -> {
            moveTimeBoth = number(value, argument);
            moveTimeArgument = argument;
          }
          case "--movetime-a" -> {
            moveTimeA = number(value, argument);
            moveTimeArgument = argument;
          }
          case "--movetime-b" -> {
            moveTimeB = number(value, argument);
            moveTimeArgument = argument;
          }
          case "--tc" -> timeControl = TimeControl.parse(value, argument);
          case "--hash" -> tableSizeMB = (int) number(value, argument);
          case "--book" -> bookPath = value;
          case "--pairs" -> {
            pairs = (int) number(value, argument);
            pairsGiven = true;
          }
          case "--seed" -> seed = number(value, argument);
          case "--opening" -> openingIndex = (int) number(value, argument);
          case "--plycap" -> plyCap = (int) number(value, argument);
          case "--resign-score" -> {
            resignScore = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--resign-plies" -> {
            resignPlies = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-score" -> {
            drawScore = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-plies" -> {
            drawPlies = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--draw-after" -> {
            drawAfter = (int) number(value, argument);
            tuningArgument = argument;
          }
          case "--elo0" -> {
            nullElo = decimal(value, argument);
            testArgument = argument;
          }
          case "--elo1" -> {
            gainElo = decimal(value, argument);
            testArgument = argument;
          }
          case "--alpha" -> {
            falsePositiveRate = decimal(value, argument);
            testArgument = argument;
          }
          case "--beta" -> {
            falseNegativeRate = decimal(value, argument);
            testArgument = argument;
          }
          case "--concurrency" -> concurrency = (int) number(value, argument);
          case "--timeout" -> timeoutSeconds = number(value, argument);
          case "--logs" -> logDirectory = value;
          case "--results" -> resultsPath = value;
          default -> throw new IllegalArgumentException("Unrecognised argument: " + argument);
        }
        index++;
      }

      if (engineACommand == null || engineBCommand == null) {
        throw new IllegalArgumentException("Both --engine-a and --engine-b must name a command " +
                "line");
      }
      if (pairs < 1) {
        throw new IllegalArgumentException("The value of --pairs must be at least one");
      }
      if (concurrency < 1) {
        throw new IllegalArgumentException("The value of --concurrency must be at least one");
      }
      if (openingIndex >= 0) {
        if (!useBook) {
          throw new IllegalArgumentException("The argument --opening names a book line, so it " +
                  "cannot be given with --nobook");
        }
        if (pairsGiven && pairs != 1) {
          throw new IllegalArgumentException("The argument --opening names the only line to " +
                  "play, so it cannot be given with --pairs " + pairs);
        }
        pairs = 1;
      }
      if (!adjudicate && tuningArgument != null) {
        throw new IllegalArgumentException("The argument " + tuningArgument + " tunes " +
                "adjudication, so it cannot be given with --no-adjudication");
      }
      if (resignScore < 0 || drawScore < 0 || drawAfter < 0) {
        throw new IllegalArgumentException("The values of --resign-score, --draw-score and " +
                "--draw-after cannot be negative");
      }
      if (resignPlies < 1 || drawPlies < 1) {
        throw new IllegalArgumentException("The values of --resign-plies and --draw-plies must " +
                "be at least one");
      }
      if (!sprt && testArgument != null) {
        throw new IllegalArgumentException("The argument " + testArgument + " states the " +
                "sequential test, so it cannot be given without --sprt");
      }
      if (nodeArgument != null && moveTimeArgument != null) {
        throw new IllegalArgumentException("The arguments " + nodeArgument + " and " +
                moveTimeArgument + " measure effort differently, so they cannot be given " +
                "together");
      }
      if (timeControl != null && (nodeArgument != null || moveTimeArgument != null)) {
        throw new IllegalArgumentException("The arguments --tc and " +
                (nodeArgument != null ? nodeArgument : moveTimeArgument) + " measure effort " +
                "differently, so they cannot be given together");
      }
      if (moveTimeArgument != null && moveTimeBoth == null &&
              (moveTimeA == null || moveTimeB == null)) {
        throw new IllegalArgumentException("The arguments --movetime-a and --movetime-b each set " +
                "the move time of one engine, so either both of them or --movetime must be given");
      }
      final MatchStatistics.SequentialTest sequentialTest = sprt ?
              new MatchStatistics.SequentialTest(nullElo, gainElo, falsePositiveRate,
                      falseNegativeRate) : null;
      final EngineProcess.Limit limitA;
      final EngineProcess.Limit limitB;
      if (timeControl != null) {
        limitA = null;
        limitB = null;
        if (timeControl.baseMillis() >= timeoutSeconds * 1000) {
          throw new IllegalArgumentException("The base time of --tc must be shorter than the " +
                  "value of --timeout, which is " + timeoutSeconds + " seconds");
        }
      } else if (moveTimeArgument == null) {
        final long shared = nodeLimitBoth == null ? DEFAULT_NODE_LIMIT : nodeLimitBoth;
        limitA = EngineProcess.Limit.ofNodes(nodeLimitA == null ? shared : nodeLimitA);
        limitB = EngineProcess.Limit.ofNodes(nodeLimitB == null ? shared : nodeLimitB);
      } else {
        limitA = EngineProcess.Limit.ofMoveTime(moveTimeA == null ? moveTimeBoth : moveTimeA);
        limitB = EngineProcess.Limit.ofMoveTime(moveTimeB == null ? moveTimeBoth : moveTimeB);
        if (Math.min(limitA.moveTimeMillis(), limitB.moveTimeMillis()) < 1) {
          throw new IllegalArgumentException("A move time must be at least one millisecond");
        }
        if (Math.max(limitA.moveTimeMillis(), limitB.moveTimeMillis()) >= timeoutSeconds * 1000) {
          throw new IllegalArgumentException("A move time must be shorter than the value of " +
                  "--timeout, which is " + timeoutSeconds + " seconds");
        }
      }
      if (resultsPath == null) {
        resultsPath = Path.of(logDirectory, DEFAULT_RESULTS_FILE_NAME).toString();
      }
      return new Settings(engineACommand, engineBCommand, limitA, limitB, timeControl,
              tableSizeMB, bookPath, pairs, seed, openingIndex, useBook, plyCap, adjudicate, resignScore, resignPlies,
              drawScore, drawPlies, drawAfter, sequentialTest, concurrency, timeoutSeconds,
              logDirectory, resultsPath, resume, verbose);
    }

    /**
     * Reads the value following an argument.
     *
     * @param args The command line arguments.
     * @param index The position of the argument the value follows.
     * @param argument The argument the value follows, used in the fault message.
     * @return The value following the argument.
     * @throws IllegalArgumentException If the argument is the last one.
     */
    private static String valueOf(final String[] args, final int index, final String argument) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("The argument " + argument + " names no value");
      }
      return args[index + 1];
    }

    /**
     * Reads a whole number from a command line value.
     *
     * @param value The value to read.
     * @param argument The argument the value follows, used in the fault message.
     * @return The number the value holds.
     * @throws IllegalArgumentException If the value is not a whole number.
     */
    private static long number(final String value, final String argument) {
      try {
        return Long.parseLong(value);
      } catch (final NumberFormatException exception) {
        throw new IllegalArgumentException("The value of " + argument + " is not a number: " +
                value);
      }
    }

    /**
     * Reads a decimal number from a command line value.
     *
     * @param value The value to read.
     * @param argument The argument the value follows, used in the fault message.
     * @return The number the value holds.
     * @throws IllegalArgumentException If the value is not a decimal number.
     */
    private static double decimal(final String value, final String argument) {
      try {
        return Double.parseDouble(value);
      } catch (final NumberFormatException exception) {
        throw new IllegalArgumentException("The value of " + argument + " is not a number: " +
                value);
      }
    }
  }
}