package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forNetwork.VectorModule;
import engine.forPlayer.forAI.AlphaBeta;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The TacticalSuite class runs the engine's search against positions whose winning move and
 * expected score are known and reports whether the engine produced both. This tests the
 * evaluation, move ordering, and pruning layers that a perft cannot reach, since a perft counts
 * nodes and is blind to which move is picked. A position is solved only when the engine both
 * chooses an accepted move and returns a score the position accepts, so a search that finds the
 * mate but reports the wrong distance to it fails.
 * <p>
 * A position may record moves to replay onto the board before the search begins, which is how a
 * position whose result depends on the moves that preceded it, such as a repetition, is expressed.
 * <p>
 * Each position is searched to a fixed depth rather than for a fixed time, so a run does not depend
 * on how fast the host is. Every position is searched by a freshly constructed engine that is shut
 * down once the position is finished, because the transposition table, evaluation cache, history
 * heuristic, and countermove table all survive a search and would otherwise make a result depend on
 * the order the positions are listed in.
 * <p>
 * Positions are searched on several worker threads at once, each running a single-threaded engine.
 * Nothing is shared between those engines, so a run reports the same result for every position
 * whatever order the workers happen to finish in. The engines themselves are single-threaded
 * because a parallel search reaches different results on different runs of the same position.
 * <p>
 * This class is designed to be run from the command line and its entry point returns a non-zero
 * exit status when any position is failed.
 *
 * @author Aaron Ho
 */
@SuppressWarnings("JavaPrintToLogpoint")
public class TacticalSuite {

  /** The size of the transposition table given to each engine, in megabytes. */
  private static final int TABLE_SIZE_MB = 64;

  /**
   * The number of search threads given to each engine. This is one so that a run is reproducible:
   * a parallel search reaches different results on different runs of the same position.
   */
  private static final int SEARCH_THREADS = 1;

  /**
   * The number of positions searched at once when no worker count is given. Each worker holds a
   * transposition table for as long as its position is running, so raising this raises peak memory
   * in proportion.
   */
  private static final int DEFAULT_WORKER_THREADS = 1;

  /** The command line flag prefix requesting a number of positions to search at once. */
  private static final String WORKERS_FLAG_PREFIX = "--workers=";

  /** The command line flag prefix requesting that only some categories of position be searched. */
  private static final String CATEGORY_FLAG_PREFIX = "--category=";

  /** The command line flag requesting that the engine's own search output be left on screen. */
  private static final String VERBOSE_FLAG = "--verbose";

  /** The command line flag requesting usage information. */
  private static final String HELP_FLAG = "--help";

  /** The depth override meaning that every position is searched to its own recorded depth. */
  private static final int NO_DEPTH_OVERRIDE = 0;

  /** The long algebraic notation of a move with no origin square, matching the null move convention. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The number of nanoseconds in one second, used to convert elapsed times for reporting. */
  private static final double NANOSECONDS_PER_SECOND = 1_000_000_000.0;

  /**
   * The kinds of position the suite tests. Positions are reported grouped by category, in the
   * order the constants are declared in.
   */
  public enum Category {

    /** A position in which the side to move has a forced checkmate. */
    MATE,

    /** A position in which the side to move wins material without forcing checkmate. */
    MATERIAL,

    /** A position in which the side to move is held to a draw, or holds one, by rule. */
    DRAW
  }

  /**
   * The positions the suite tests, each paired with the moves that win and the depth to search to.
   * The depth recorded against a position keeps its tactic inside the search horizon with a margin
   * and is deep enough for the engine to return the position's expected score, not merely its
   * expected move. The shallowest depth that solves a position is not used, because the move a
   * search returns is not monotonic in depth.
   */
  private static final List<TacticalPosition> STANDARD_POSITIONS = List.of(
          new TacticalPosition("Back rank mate", Category.MATE,
                  "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1",
                  2, ScoreBand.mateIn(1), "a1a8"),
          new TacticalPosition("Back rank mate by capture", Category.MATE,
                  "2r3k1/5ppp/8/8/8/8/5PPP/2R3K1 w - - 0 1",
                  2, ScoreBand.mateIn(1), "c1c8"),
          new TacticalPosition("Scholar's mate", Category.MATE,
                  "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                  2, ScoreBand.mateIn(1), "h5f7"),
          new TacticalPosition("Queen sacrifice, mate in two", Category.MATE,
                  "2rr3k/pp3pp1/1nnqbN1p/3pN3/2pP4/2P3Q1/PPB4P/R4RK1 w - - 0 1",
                  4, ScoreBand.mateIn(2), "g3g6"),
          new TacticalPosition("Bishop sacrifice, mate in two", Category.MATE,
                  "r1bq2rk/pp3pbp/2p1p1pQ/7P/3P4/2PB1N2/PP3PPR/2KR4 w - - 0 1",
                  4, ScoreBand.mateIn(2), "h6h7"),
          new TacticalPosition("Queen check, mate in two", Category.MATE,
                  "5k2/6pp/p1qN4/1p1p4/3P4/2PKP2Q/PP3r2/3R4 b - - 0 1",
                  4, ScoreBand.mateIn(2), "c6c4"),
          new TacticalPosition("Rook deflection, mate in two", Category.MATE,
                  "6k1/pp4p1/2p5/2bp4/8/P5Pb/1P3rrP/2BRRN1K b - - 0 1",
                  4, ScoreBand.mateIn(2), "g2g1"),
          new TacticalPosition("Rook lift wins the queen", Category.MATERIAL,
                  "5rk1/1ppb3p/p1pb4/6q1/3P1p1r/2P1R2P/PP1BQ1P1/5RKN w - - 0 1",
                  10, ScoreBand.atLeast(300), "e3g3"),
          new TacticalPosition("Rook sacrifice wins the knight", Category.MATERIAL,
                  "2br2k1/2q3rn/p2NppQ1/2p1P3/Pp5R/4P3/1P3PPP/3R2K1 w - - 0 1",
                  6, ScoreBand.atLeast(150), "h4h7"),
          new TacticalPosition("Bishop capture wins a piece", Category.MATERIAL,
                  "r1b1kb1r/3q1ppp/pBp1pn2/8/Np3P2/5B2/PPP3PP/R2Q1RK1 w kq - 0 1",
                  6, ScoreBand.atLeast(150), "f3c6"),
          new TacticalPosition("Back rank threat wins the queen", Category.MATERIAL,
                  "3r1r1k/1p4pp/p4p2/8/1PQR4/6Pq/P3PP2/2R3K1 b - - 0 1",
                  6, ScoreBand.atLeast(300), "d8c8"),
          new TacticalPosition("Knight fork wins the exchange", Category.MATERIAL,
                  "8/p7/1ppk1n2/5ppp/P1PP4/2P1K1P1/5N1P/8 b - - 0 1",
                  6, ScoreBand.atLeast(100), "f6g4"),
          new TacticalPosition("Queen sacrifice, mate in four", Category.MATE,
                  "r2rb1k1/pp1q1p1p/2n1p1p1/2bp4/5P2/PP1BPR1Q/1BPN2PP/R5K1 w - - 0 1",
                  10, ScoreBand.mateIn(4), "h3h7"),
          new TacticalPosition("Smothered mate in four", Category.MATE,
                  "5r1k/6pp/8/6N1/8/1Q6/6PP/6K1 w - - 0 1",
                  10, ScoreBand.mateIn(4), "g5f7"),
          new TacticalPosition("Knight check, mate in four", Category.MATE,
                  "rnbqkb1r/pppp1ppp/8/4P3/6n1/7P/PPPNPnP1/R1BQKBNR b KQkq - 0 1",
                  8, ScoreBand.mateIn(4), "f2d3"),
          new TacticalPosition("Fifty-move rule saves the defender", Category.DRAW,
                  "k7/8/8/8/8/8/2Q5/2K5 b - - 99 60",
                  6, ScoreBand.drawn(), "a8a7", "a8b7", "a8b8"),
          new TacticalPosition("Capture resets the fifty-move clock", Category.DRAW,
                  "7k/8/8/8/1p6/1Q6/8/K7 w - - 99 60",
                  6, ScoreBand.atLeast(300), "b3b4"),
          new TacticalPosition("Repetition saves a lost position", Category.DRAW,
                  "6k1/7r/8/8/8/8/1Q6/K1R5 b - - 0 1",
                  List.of("h7h6", "a1a2", "h6h7", "a2a1"),
                  6, ScoreBand.drawn(), "h7h6"),
          new TacticalPosition("Repetition saves a lost position, colours reversed", Category.DRAW,
                  "k1r5/1q6/8/8/8/8/7R/6K1 w - - 0 1",
                  List.of("h2h3", "a8a7", "h3h2", "a7a8"),
                  6, ScoreBand.drawn(), "h2h3"));

  /**
   * Runs the suite from the command line. With no arguments every position is searched to its own
   * recorded depth, a single numeric argument overrides that depth for every position, the category
   * flag restricts the run to the named categories, the workers flag sets how many positions are
   * searched at once, and the verbose flag leaves the engine's own search output on screen.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    VectorModule.relaunchIfMissing(TacticalSuite.class, args);
    int depthOverride = NO_DEPTH_OVERRIDE;
    int workerThreads = DEFAULT_WORKER_THREADS;
    Set<Category> categories = EnumSet.allOf(Category.class);
    boolean verbose = false;
    for (final String argument : args) {
      if (VERBOSE_FLAG.equals(argument)) {
        verbose = true;
      } else if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else if (argument.startsWith(CATEGORY_FLAG_PREFIX)) {
        try {
          categories = parseCategories(argument.substring(CATEGORY_FLAG_PREFIX.length()));
        } catch (final IllegalArgumentException exception) {
          System.out.println("Unrecognised category: " + argument);
          printUsage();
          return;
        }
      } else if (argument.startsWith(WORKERS_FLAG_PREFIX)) {
        try {
          workerThreads = Integer.parseInt(argument.substring(WORKERS_FLAG_PREFIX.length()));
        } catch (final NumberFormatException exception) {
          System.out.println("The worker count must be a number: " + argument);
          printUsage();
          return;
        }
      } else {
        try {
          depthOverride = Integer.parseInt(argument);
        } catch (final NumberFormatException exception) {
          System.out.println("Unrecognised argument: " + argument);
          printUsage();
          return;
        }
      }
    }
    if (depthOverride != NO_DEPTH_OVERRIDE && depthOverride < 1) {
      System.out.println("The depth override must be at least one.");
      printUsage();
      return;
    }
    if (workerThreads < 1) {
      System.out.println("The worker count must be at least one.");
      printUsage();
      return;
    }
    System.exit(run(depthOverride, verbose, workerThreads, categories) ? 0 : 1);
  }

  /**
   * Parses a comma separated list of category names, in any case.
   *
   * @param value The list of category names.
   * @return The categories the list names.
   * @throws IllegalArgumentException If the list names no category or names one that does not
   *         exist.
   */
  private static Set<Category> parseCategories(final String value) {
    final Set<Category> categories = EnumSet.noneOf(Category.class);
    for (final String name : value.split(",")) {
      final String trimmed = name.trim();
      if (!trimmed.isEmpty()) {
        categories.add(Category.valueOf(trimmed.toUpperCase(Locale.ROOT)));
      }
    }
    if (categories.isEmpty()) {
      throw new IllegalArgumentException("No category was named.");
    }
    return categories;
  }

  /**
   * Returns the positions belonging to the given categories, grouped by category in the order the
   * categories are declared in and in list order within a category.
   *
   * @param categories The categories to select.
   * @return The selected positions.
   */
  private static List<TacticalPosition> select(final Set<Category> categories) {
    final List<TacticalPosition> selected = new ArrayList<>(STANDARD_POSITIONS.size());
    for (final Category category : Category.values()) {
      if (categories.contains(category)) {
        for (final TacticalPosition position : STANDARD_POSITIONS) {
          if (position.category() == category) {
            selected.add(position);
          }
        }
      }
    }
    return selected;
  }

  /**
   * Searches every position in the suite on the default number of workers and prints a report of
   * the results.
   *
   * @param depthOverride The depth to search every position to, or zero to use each recorded depth.
   * @param verbose Whether to leave the engine's own search output on screen.
   * @return True if every position was solved, false otherwise.
   */
  public static boolean run(final int depthOverride, final boolean verbose) {
    return run(depthOverride, verbose, DEFAULT_WORKER_THREADS);
  }

  /**
   * Searches every position in the suite and prints a report of the results.
   *
   * @param depthOverride The depth to search every position to, or zero to use each recorded depth.
   * @param verbose Whether to leave the engine's own search output on screen.
   * @param requestedWorkers The number of positions to search at once, at least one.
   * @return True if every position was solved, false otherwise.
   */
  public static boolean run(final int depthOverride, final boolean verbose,
                            final int requestedWorkers) {
    return run(depthOverride, verbose, requestedWorkers, EnumSet.allOf(Category.class));
  }

  /**
   * Searches every selected position and prints a report of the results. Overriding the depth
   * downwards is expected to fail positions whose tactic no longer fits inside the horizon.
   * Results are reported grouped by category, and in list order within a category, however the
   * workers are scheduled. A verbose run uses a single worker so the engine's output stays
   * readable.
   *
   * @param depthOverride The depth to search every position to, or zero to use each recorded depth.
   * @param verbose Whether to leave the engine's own search output on screen.
   * @param requestedWorkers The number of positions to search at once, at least one.
   * @param categories The categories of position to search, of which at least one must be
   *        represented in the suite or the run reports a failure.
   * @return True if every selected position was solved, false otherwise.
   */
  public static boolean run(final int depthOverride, final boolean verbose,
                            final int requestedWorkers, final Set<Category> categories) {
    final List<TacticalPosition> selected = select(categories);
    if (selected.isEmpty()) {
      System.out.println("No position in the suite belongs to any of the requested categories.");
      return false;
    }
    final int workers = verbose ? 1 : Math.min(requestedWorkers, selected.size());
    System.out.printf("Tactical suite: %d positions on %d worker%s%s%n%n",
            selected.size(), workers, workers == 1 ? "" : "s",
            depthOverride == NO_DEPTH_OVERRIDE ? "" : ", every one searched to depth " + depthOverride);

    final PrintStream originalOut = System.out;
    final ExecutorService workerPool = Executors.newFixedThreadPool(workers);
    final List<Future<PositionOutcome>> outcomes = new ArrayList<>(selected.size());
    for (int index = 0; index < selected.size(); index++) {
      outcomes.add(null);
    }

    int solved = 0;
    long totalNodes = 0;
    final Map<Category, Integer> solvedByCategory = new EnumMap<>(Category.class);
    final Map<Category, Integer> totalByCategory = new EnumMap<>(Category.class);
    final long startTime = System.nanoTime();
    if (!verbose) {
      System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    }
    try {
      for (final int index : submissionOrder(selected, depthOverride)) {
        final TacticalPosition position = selected.get(index);
        outcomes.set(index, workerPool.submit(() -> runPosition(position, depthOverride)));
      }
      Category reported = null;
      for (int index = 0; index < selected.size(); index++) {
        final TacticalPosition position = selected.get(index);
        if (position.category() != reported) {
          reported = position.category();
          originalOut.printf("== %s ==%n%n", reported);
        }
        final PositionOutcome outcome = await(outcomes.get(index), position);
        originalOut.print(outcome.report());
        totalNodes += outcome.nodes();
        totalByCategory.merge(position.category(), 1, Integer::sum);
        if (outcome.solved()) {
          solved++;
          solvedByCategory.merge(position.category(), 1, Integer::sum);
        }
      }
    } finally {
      System.setOut(originalOut);
      workerPool.shutdown();
    }

    final double elapsedSeconds = (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
    for (final Map.Entry<Category, Integer> entry : totalByCategory.entrySet()) {
      System.out.printf("%-12s %d of %d solved%n", entry.getKey(),
              solvedByCategory.getOrDefault(entry.getKey(), 0), entry.getValue());
    }
    System.out.printf("%d of %d positions solved, %d nodes in %.2fs%n",
            solved, selected.size(), totalNodes, elapsedSeconds);
    return solved == selected.size();
  }

  /**
   * Returns the indices of the positions in the order they are handed to the workers, deepest
   * first. Starting the longest searches first keeps a deep position from being picked up last and
   * running on alone after every other worker has finished.
   *
   * @param selected The positions being searched, in the order they are reported in.
   * @param depthOverride The depth every position is searched to, or zero to use recorded depths.
   * @return The position indices, ordered by descending search depth.
   */
  private static List<Integer> submissionOrder(final List<TacticalPosition> selected,
                                               final int depthOverride) {
    final List<Integer> order = new ArrayList<>(selected.size());
    for (int index = 0; index < selected.size(); index++) {
      order.add(index);
    }
    if (depthOverride == NO_DEPTH_OVERRIDE) {
      order.sort(Comparator.comparingInt(
              (Integer index) -> selected.get(index).searchDepth()).reversed());
    }
    return order;
  }

  /**
   * Waits for a position's search to finish and returns its outcome, reporting a failure to
   * complete as a failed position rather than ending the run.
   *
   * @param outcome The pending outcome of the position's search.
   * @param position The position the search was run against.
   * @return The outcome of the search.
   */
  private static PositionOutcome await(final Future<PositionOutcome> outcome,
                                       final TacticalPosition position) {
    try {
      return outcome.get();
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      return abandoned(position, "the search was interrupted");
    } catch (final ExecutionException exception) {
      return abandoned(position, "the search threw " + exception.getCause());
    }
  }

  /**
   * Builds the outcome of a position whose search did not produce a move.
   *
   * @param position The position the search was run against.
   * @param reason The reason no move was produced.
   * @return A failed outcome carrying a report of the reason.
   */
  private static PositionOutcome abandoned(final TacticalPosition position, final String reason) {
    return new PositionOutcome(false, 0, String.format("%s%n  %s%n  FAIL  %s%n%n",
            position.name(), position.fen(), reason));
  }

  /**
   * Searches a single position and builds a report of whether the engine chose a move the position
   * accepts and returned a score the position accepts. Any moves the position records are replayed
   * onto the board before the search, so that the search sees the history they leave behind. The score is reported from the point of
   * view of the side to move rather than from White's. Nothing is printed from here, so that
   * reports can be shown in the order the positions are listed in rather than the order the
   * workers finish in.
   *
   * @param position The position to search.
   * @param depthOverride The depth to search to, or zero to use the position's recorded depth.
   * @return The outcome of the search.
   */
  private static PositionOutcome runPosition(final TacticalPosition position,
                                             final int depthOverride) {
    final int depth = depthOverride == NO_DEPTH_OVERRIDE ? position.searchDepth() : depthOverride;
    final Board board;
    try {
      board = FenParser.parse(position.fen());
    } catch (final IllegalArgumentException exception) {
      return abandoned(position, "the position could not be parsed: " + exception.getMessage());
    }
    for (final String notation : position.setupMoves()) {
      final Move setupMove = OpeningBook.resolve(board, notation);
      if (setupMove == null) {
        return abandoned(position, "the setup move " + notation + " is not legal");
      }
      board.makeMove(setupMove);
    }
    final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
    final long startTime = System.nanoTime();
    final SearchOutcome outcome = search(board, depth);
    final double elapsedSeconds = (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
    final String chosenNotation = describe(board, outcome.move());
    final double sideToMoveScore = whiteToMove ? outcome.score() : -outcome.score();
    final boolean moveAccepted = position.accepts(chosenNotation);
    final boolean scoreAccepted = position.expectedScore().contains(sideToMoveScore);
    final boolean solved = moveAccepted && scoreAccepted;
    final double nodesPerSecond = elapsedSeconds > 0 ? outcome.nodes() / elapsedSeconds : 0;
    final String setup = position.setupMoves().isEmpty() ? "" :
            String.format("  setup %s%n", String.join(" ", position.setupMoves()));
    final String report = String.format(
            "%s%n  %s%n%s  depth %d  expected %-14s chose %-14s  MOVE %s%n"
                    + "  score %12.0f  expected %-16s  SCORE %s%n"
                    + "  %14d nodes  %12.0f nodes/s  %8.2fs%n%n",
            position.name(), position.fen(), setup, depth,
            String.join(" or ", position.acceptedMoves()), chosenNotation,
            moveAccepted ? "PASS" : "FAIL",
            sideToMoveScore, position.expectedScore().description(),
            scoreAccepted ? "PASS" : "FAIL",
            outcome.nodes(), nodesPerSecond, elapsedSeconds);
    return new PositionOutcome(solved, outcome.nodes(), report);
  }

  /**
   * Searches the given position to the given depth with a single-threaded engine built for this
   * position alone. The engine is shut down before this returns, so nothing it learned reaches any
   * other position.
   *
   * @param board The position to search.
   * @param depth The depth to search to.
   * @return The outcome of the search.
   */
  private static SearchOutcome search(final Board board, final int depth) {
    final AlphaBeta engine = new AlphaBeta(depth, TABLE_SIZE_MB, SEARCH_THREADS);
    try {
      final Move move = engine.execute(board, depth);
      return new SearchOutcome(move, engine.getLastScore(), engine.getBoardsEvaluated());
    } finally {
      engine.shutdown();
    }
  }

  /**
   * The move a search chose, the score it was given, and the size of the tree it took to find it.
   *
   * @param move The move the engine chose.
   * @param score The root score of that move.
   * @param nodes The number of positions the search evaluated.
   */
  private record SearchOutcome(Move move, double score, long nodes) {
  }

  /**
   * Renders the move the engine chose in long algebraic notation. The move is applied to the board
   * to name a promotion after the piece left on the destination square, and the board is restored
   * before this method returns.
   *
   * @param board The position the move was chosen in.
   * @param move The move the engine chose, which may be a null move.
   * @return The long algebraic notation of the move.
   */
  private static String describe(final Board board, final Move move) {
    if (move == null || !BoardUtils.isValidTileCoordinate(move.getCurrentCoordinate())) {
      return NULL_MOVE_NOTATION;
    }
    board.makeMove(move);
    try {
      return Perft.longAlgebraicNotation(move, board);
    } finally {
      board.unmakeMove();
    }
  }

  /**
   * Prints the usage text describing how the suite is run from the command line.
   */
  private static void printUsage() {
    System.out.println("""
            Usage:
              TacticalSuite [depth] [--category=NAME,...] [--workers=N] [--verbose]
              TacticalSuite --help                     print this message

            A depth argument overrides the depth recorded against every position. Positions are
            recorded at a depth that keeps the tactic inside the search horizon with a margin, so
            overriding downwards is expected to fail some of them.

            A position is solved only when the engine both chooses an accepted move and returns a
            score inside the range recorded against the position, so a mate found at the wrong
            distance is a failure.

            The category flag restricts the run to the named categories, given in any case and
            separated by commas. The categories are mate, material, and draw. Positions are reported
            grouped by category whether or not the flag is given.

            The workers flag sets how many positions are searched at once, one by default. Every
            worker holds a transposition table for as long as its position is running, so raising
            it raises peak memory in proportion. A verbose run uses one worker whatever is asked
            for, so that the engine's own output stays readable.

            The suite exits with a non-zero status when any position is failed.""");
  }

  /**
   * The PositionOutcome record pairs the result of a position's search with the report to print
   * for it.
   *
   * @param solved Whether the engine chose a move and returned a score the position accepts.
   * @param nodes The number of positions the search evaluated.
   * @param report The text describing the position and the move that was chosen.
   */
  private record PositionOutcome(boolean solved, long nodes, String report) { }

  /**
   * The ScoreBand record holds the range of root scores a position accepts, from the point of view
   * of the side to move rather than from White's.
   *
   * @param minimum The lowest score the position accepts.
   * @param maximum The highest score the position accepts.
   * @param description The text naming this range in the report.
   */
  public record ScoreBand(double minimum, double maximum, String description) {

    /**
     * Returns the band accepting only a checkmate delivered by the side to move in exactly the
     * given number of moves. Such a mate leaves the mated side to move at ply two times the move
     * count less one, and the search scores it {@link AlphaBeta#MATE_VALUE} reduced by that ply.
     *
     * @param moves The number of moves to the mate.
     * @return The band accepting that mate and nothing else.
     */
    public static ScoreBand mateIn(final int moves) {
      final double score = AlphaBeta.MATE_VALUE - (2 * moves - 1);
      return new ScoreBand(score, score, "mate in " + moves);
    }

    /**
     * Returns the band accepting any score at or above the given advantage, up to and including a
     * checkmate score.
     *
     * @param advantage The lowest score the position accepts.
     * @return The band accepting that advantage or better.
     */
    public static ScoreBand atLeast(final double advantage) {
      return new ScoreBand(advantage, AlphaBeta.MATE_VALUE, "at least " + (long) advantage);
    }

    /**
     * Returns the band accepting only a drawn score.
     *
     * @return The band accepting a score of zero and nothing else.
     */
    public static ScoreBand drawn() {
      return new ScoreBand(0, 0, "draw");
    }

    /**
     * Reports whether the given score falls inside this band.
     *
     * @param score The root score, from the point of view of the side to move.
     * @return True if the score is inside this band, false otherwise.
     */
    public boolean contains(final double score) {
      return score >= this.minimum && score <= this.maximum;
    }
  }

  /**
   * The TacticalPosition record pairs a position with the moves that solve it and the depth to
   * search it to. More than one move is accepted only where a position genuinely has more than one
   * winning move.
   *
   * @param name The tactic the position illustrates.
   * @param category The kind of position this is, which the report is grouped by.
   * @param fen The Forsyth-Edwards Notation string describing the position.
   * @param setupMoves The moves replayed onto the position before the search, in long algebraic
   *        notation, which may be empty.
   * @param searchDepth The depth this position is searched to when no override is given.
   * @param expectedScore The root scores this position accepts, from the point of view of the
   *        side to move.
   * @param acceptedMoves The winning moves, in long algebraic notation.
   */
  public record TacticalPosition(String name, Category category, String fen,
                                 List<String> setupMoves, int searchDepth,
                                 ScoreBand expectedScore, List<String> acceptedMoves) {

    /**
     * Constructs a position with no moves to replay, whose accepted moves are given one after
     * another.
     *
     * @param name The tactic the position illustrates.
     * @param category The kind of position this is, which the report is grouped by.
     * @param fen The Forsyth-Edwards Notation string describing the position.
     * @param searchDepth The depth this position is searched to when no override is given.
     * @param expectedScore The root scores this position accepts, from the point of view of the
     *        side to move.
     * @param acceptedMoves The winning moves, in long algebraic notation.
     */
    public TacticalPosition(final String name, final Category category, final String fen,
                            final int searchDepth, final ScoreBand expectedScore,
                            final String... acceptedMoves) {
      this(name, category, fen, List.of(), searchDepth, expectedScore, List.of(acceptedMoves));
    }

    /**
     * Constructs a position with moves to replay before the search, whose accepted moves are given
     * one after another.
     *
     * @param name The tactic the position illustrates.
     * @param category The kind of position this is, which the report is grouped by.
     * @param fen The Forsyth-Edwards Notation string describing the position.
     * @param setupMoves The moves replayed onto the position before the search, in long algebraic
     *        notation.
     * @param searchDepth The depth this position is searched to when no override is given.
     * @param expectedScore The root scores this position accepts, from the point of view of the
     *        side to move.
     * @param acceptedMoves The winning moves, in long algebraic notation.
     */
    public TacticalPosition(final String name, final Category category, final String fen,
                            final List<String> setupMoves, final int searchDepth,
                            final ScoreBand expectedScore, final String... acceptedMoves) {
      this(name, category, fen, setupMoves, searchDepth, expectedScore, List.of(acceptedMoves));
    }

    /**
     * Reports whether the given move solves this position.
     *
     * @param moveNotation The long algebraic notation of the move the engine chose.
     * @return True if the move is one this position accepts, false otherwise.
     */
    public boolean accepts(final String moveNotation) {
      return this.acceptedMoves.contains(moveNotation);
    }
  }
}