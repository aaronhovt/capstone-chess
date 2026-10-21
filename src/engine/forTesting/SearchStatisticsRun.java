package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPlayer.forAI.AlphaBeta;
import engine.forPlayer.forAI.SearchStatistics;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SearchStatisticsRun class plays self-play games under the conditions of a node-limited
 * match and reports the {@link SearchStatistics} of every search played in them.
 * <p>
 * Each game starts from a book opening, drawn from a shuffle of the book seeded from the
 * arguments, and is played by two engines of one search thread each, created for that game, as a
 * match's engines are on ucinewgame. Games end as a match ends them: on checkmate, stalemate,
 * insufficient material, the fifty move rule, threefold repetition, the ply cap, or adjudication
 * on score with a match's default thresholds.
 * <p>
 * The statistics are counted only because this class sets the system property
 * {@value SearchStatistics#PROPERTY} before the search is first used. A run that finds counting
 * off refuses to play. The searches print their iteration reports to standard output, so standard
 * output is silenced while games are played and the report is written once they are finished.
 * <p>
 * Usage: {@code SearchStatisticsRun [--games n] [--workers n] [--nodes n] [--seed n]
 * [--book path] [--hash megabytes] [--report path]}
 */
public final class SearchStatisticsRun {

  /** The depth limit each search is given, the same as the UCI engine's. */
  private static final int LIMIT_DEPTH = 64;

  /** The number of games played when no count is given. */
  private static final int DEFAULT_GAMES = 100;

  /** The number of games played at once when no count is given. */
  private static final int DEFAULT_WORKERS = 7;

  /** The node limit of each search when none is given. */
  private static final long DEFAULT_NODES = 100_000;

  /** The seed of the opening shuffle when none is given. */
  private static final long DEFAULT_SEED = 1;

  /** The transposition table size of each engine in megabytes when none is given. */
  private static final int DEFAULT_TABLE_SIZE_MB = 64;

  /** The book read when none is given. */
  private static final String DEFAULT_BOOK = "book/openings.txt";

  /** The largest number of plies a game may reach. */
  private static final int PLY_CAP = 300;

  /** The score in centipawns a side must be ahead by for a ply to count towards a win. */
  private static final int RESIGN_SCORE = 800;

  /** The number of consecutive plies at the resign score that end a game as a win. */
  private static final int RESIGN_PLIES = 8;

  /** The score in centipawns a position must be within for a ply to count towards a draw. */
  private static final int DRAW_SCORE = 10;

  /** The number of consecutive plies within the draw score that end a game as a draw. */
  private static final int DRAW_PLIES = 8;

  /** The ply a game must have reached before it may be adjudicated as a draw. */
  private static final int DRAW_AFTER = 80;

  /** The number of finished games between progress lines. */
  private static final int PROGRESS_INTERVAL = 10;

  /** Prevents instantiation of this entry point. */
  private SearchStatisticsRun() {
  }

  /**
   * Plays the games the arguments ask for and reports the statistics of their searches to
   * standard output, and to the report file if one is named.
   *
   * @param args The options listed in the class description.
   * @throws IOException If the book cannot be read or the report cannot be written.
   * @throws InterruptedException If the calling thread is interrupted while games are played.
   * @throws ExecutionException If a game fails.
   */
  public static void main(final String[] args)
          throws IOException, InterruptedException, ExecutionException {
    System.setProperty(SearchStatistics.PROPERTY, "true");
    if (!SearchStatistics.isCollecting()) {
      System.err.println("The search was loaded before counting could be turned on.");
      System.exit(1);
    }

    int games = DEFAULT_GAMES;
    int workers = DEFAULT_WORKERS;
    long nodes = DEFAULT_NODES;
    long seed = DEFAULT_SEED;
    int tableSizeMB = DEFAULT_TABLE_SIZE_MB;
    Path book = Path.of(DEFAULT_BOOK);
    Path reportPath = null;
    for (int i = 0; i < args.length; i++) {
      if (i + 1 >= args.length) {
        usage("Option " + args[i] + " has no value");
      }
      final String value = args[++i];
      switch (args[i - 1]) {
        case "--games" -> games = Integer.parseInt(value);
        case "--workers" -> workers = Integer.parseInt(value);
        case "--nodes" -> nodes = Long.parseLong(value);
        case "--seed" -> seed = Long.parseLong(value);
        case "--hash" -> tableSizeMB = Integer.parseInt(value);
        case "--book" -> book = Path.of(value);
        case "--report" -> reportPath = Path.of(value);
        default -> usage("Unknown option " + args[i - 1]);
      }
    }

    final List<OpeningBook.Opening> openings = new ArrayList<>(OpeningBook.load(book));
    Collections.shuffle(openings, new Random(seed));

    final PrintStream console = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));

    final SearchStatistics total = new SearchStatistics();
    final AtomicInteger finished = new AtomicInteger();
    final int gameCount = games;
    final long nodeLimit = nodes;
    final int tableSize = tableSizeMB;
    final long start = System.currentTimeMillis();
    final ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      final List<Future<?>> results = new ArrayList<>();
      for (int game = 0; game < gameCount; game++) {
        final OpeningBook.Opening opening = openings.get(game % openings.size());
        results.add(pool.submit(() -> {
          SearchStatistics.takeCurrent();
          playGame(opening, nodeLimit, tableSize);
          final SearchStatistics counted = SearchStatistics.takeCurrent();
          synchronized (total) {
            counted.addTo(total);
          }
          final int done = finished.incrementAndGet();
          if (done % PROGRESS_INTERVAL == 0 || done == gameCount) {
            System.err.printf("%d of %d games, %.0f s%n", done, gameCount,
                    (System.currentTimeMillis() - start) / 1000.0);
          }
          return null;
        }));
      }
      for (final Future<?> result : results) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
      System.setOut(console);
    }

    final String header = String.format("Search statistics: %d games from %s (seed %d), %d nodes " +
                    "per move, %d MB hash, %d games at once, %.0f s%n%n", gameCount,
            book.getFileName(), seed, nodeLimit, tableSize, workers,
            (System.currentTimeMillis() - start) / 1000.0);
    final PrintWriter out = new PrintWriter(console, true);
    out.print(header);
    total.report(out);
    out.flush();
    if (reportPath != null) {
      try (PrintWriter file = new PrintWriter(Files.newBufferedWriter(reportPath))) {
        file.print(header);
        total.report(file);
      }
    }
  }

  /**
   * Plays one game from the given opening until it ends.
   *
   * @param opening The opening the game starts from.
   * @param nodeLimit The node limit of each search.
   * @param tableSizeMB The transposition table size of each engine in megabytes.
   */
  private static void playGame(final OpeningBook.Opening opening, final long nodeLimit,
                               final int tableSizeMB) {
    final Board board = OpeningBook.play(opening);
    int plies = opening.moves().size();
    final AlphaBeta white = new AlphaBeta(LIMIT_DEPTH, tableSizeMB, 1);
    final AlphaBeta black = new AlphaBeta(LIMIT_DEPTH, tableSizeMB, 1);
    final Adjudicator adjudicator = new Adjudicator(true, RESIGN_SCORE, RESIGN_PLIES, DRAW_SCORE,
            DRAW_PLIES, DRAW_AFTER);
    try {
      while (!isOver(board) && plies < PLY_CAP) {
        final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
        final AlphaBeta engine = whiteToMove ? white : black;
        final Move move = engine.execute(board, LIMIT_DEPTH, nodeLimit, AlphaBeta.UNLIMITED_TIME);
        final EngineProcess.Reply reply = replyOf(engine, whiteToMove);
        board.makeMove(move);
        plies++;
        if (adjudicator.judge(reply, whiteToMove, plies) != Adjudicator.Verdict.NONE) {
          break;
        }
      }
    } finally {
      white.shutdown();
      black.shutdown();
    }
  }

  /**
   * Returns whether a game has ended in the given position by checkmate, stalemate, insufficient
   * material, the fifty move rule or threefold repetition.
   *
   * @param board The position to judge.
   * @return True if the game has ended.
   */
  private static boolean isOver(final Board board) {
    return board.currentPlayer().isInCheckMate() || board.currentPlayer().isInStaleMate()
            || board.isInsufficientMaterial() || board.isFiftyMoveRule()
            || board.isThreefoldRepetition();
  }

  /**
   * Returns the score of an engine's last search as the UCI engine would report it, from the
   * point of view of the side that moved: in centipawns, or as the number of moves to a mate.
   *
   * @param engine The engine that has just searched.
   * @param whiteMoved True if White searched.
   * @return The reply the adjudicator judges, with no move and no depth.
   */
  private static EngineProcess.Reply replyOf(final AlphaBeta engine, final boolean whiteMoved) {
    final double relativeScore = whiteMoved ? engine.getLastScore() : -engine.getLastScore();
    final double magnitude = Math.abs(relativeScore);
    if (magnitude < AlphaBeta.MATE_THRESHOLD) {
      return new EngineProcess.Reply(null, null, (int) Math.round(relativeScore), null, 0);
    }
    final int moves = (int) ((Math.round(AlphaBeta.MATE_VALUE - magnitude) + 1) / 2);
    return new EngineProcess.Reply(null, null, null, relativeScore < 0 ? -moves : moves, 0);
  }

  /**
   * Reports a problem with the arguments and the usage, then exits.
   *
   * @param problem What was wrong with the arguments.
   */
  private static void usage(final String problem) {
    System.err.println(problem);
    System.err.println("Usage: SearchStatisticsRun [--games n] [--workers n] [--nodes n] " +
            "[--seed n] [--book path] [--hash megabytes] [--report path]");
    System.exit(1);
  }
}
