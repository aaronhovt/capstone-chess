package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPlayer.forAI.AlphaBeta;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The TrainingDataRun class plays node-limited self-play games and writes the positions searched
 * in them, with the search score and the game result, as training data for an evaluation network.
 * <p>
 * Each game starts from a book opening, drawn from a shuffle of the book seeded from the
 * arguments, followed by a number of uniformly random legal moves drawn from a generator seeded
 * from the arguments and the game's index, so a run is reproducible and two games from the same
 * book line differ. The game is then played by two engines of one search thread each, created for
 * that game. A game whose first search scores either side further ahead than
 * {@link #OPENING_SCORE_LIMIT} is discarded, as is one the random moves end. Games end on
 * checkmate, stalemate, insufficient material, the fifty move rule, threefold repetition, the ply
 * cap, or adjudication on score. A game is adjudicated as won once the given number of consecutive
 * plies have scored one side ahead by at least the resign score, by default a match's thresholds,
 * and as drawn with a match's draw thresholds.
 * <p>
 * A searched position is written unless the side to move is in check, the move chosen is a
 * capture or a promotion, or the score is a mate score. Each position is one line,
 * {@code <fen> | <score> | <result>}: the score in whole centipawns and the result as 1.0, 0.5 or
 * 0.0, both from White's point of view. The lines of a game are written once the game has ended,
 * and the output file is appended to, so runs with different seeds can add to one file.
 * <p>
 * The searches print their iteration reports to standard output, so standard output is silenced
 * while games are played. Progress is reported to standard error.
 * <p>
 * Usage: {@code TrainingDataRun [--games n] [--workers n] [--nodes n] [--seed n]
 * [--random-plies n] [--resign-score centipawns] [--resign-plies n] [--book path]
 * [--hash megabytes] [--out path]}
 */
public final class TrainingDataRun {

  /** The depth limit each search is given, the same as the UCI engine's. */
  private static final int LIMIT_DEPTH = 64;

  /** The number of games played when no count is given. */
  private static final int DEFAULT_GAMES = 1000;

  /** The number of games played at once when no count is given: all but two hardware threads. */
  private static final int DEFAULT_WORKERS =
          Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

  /** The node limit of each search when none is given. */
  private static final long DEFAULT_NODES = 5_000;

  /** The seed of the opening shuffle and the random moves when none is given. */
  private static final long DEFAULT_SEED = 1;

  /** The number of random moves played after the book line when no count is given. */
  private static final int DEFAULT_RANDOM_PLIES = 4;

  /** The transposition table size of each engine in megabytes when none is given. */
  private static final int DEFAULT_TABLE_SIZE_MB = 16;

  /** The file written when none is given. */
  private static final String DEFAULT_OUT = "data/training.txt";

  /** The score in centipawns past which the first search of a game discards it. */
  private static final int OPENING_SCORE_LIMIT = 1000;

  /** The largest number of plies a game may reach. */
  private static final int PLY_CAP = 300;

  /**
   * The score in centipawns a side must be ahead by for a ply to count towards a win when none is
   * given.
   */
  private static final int DEFAULT_RESIGN_SCORE = 800;

  /** The number of consecutive plies at the resign score that end a game as a win when none is given. */
  private static final int DEFAULT_RESIGN_PLIES = 8;

  /** The score in centipawns a position must be within for a ply to count towards a draw. */
  private static final int DRAW_SCORE = 10;

  /** The number of consecutive plies within the draw score that end a game as a draw. */
  private static final int DRAW_PLIES = 8;

  /** The ply a game must have reached before it may be adjudicated as a draw. */
  private static final int DRAW_AFTER = 80;

  /** The number of finished games between progress lines. */
  private static final int PROGRESS_INTERVAL = 100;

  /** The multiplier that spreads the run seed across games when seeding each game's random moves. */
  private static final long GAME_SEED_STRIDE = 0x9E3779B97F4A7C15L;

  /** Prevents instantiation of this entry point. */
  private TrainingDataRun() {
  }

  /**
   * Plays the games the arguments ask for and appends their positions to the output file.
   *
   * @param args The options listed in the class description.
   * @throws IOException If the book cannot be read or the output cannot be written.
   * @throws InterruptedException If the calling thread is interrupted while games are played.
   * @throws ExecutionException If a game fails.
   */
  public static void main(final String[] args)
          throws IOException, InterruptedException, ExecutionException {
    int games = DEFAULT_GAMES;
    int workers = DEFAULT_WORKERS;
    long nodes = DEFAULT_NODES;
    long seed = DEFAULT_SEED;
    int randomPlies = DEFAULT_RANDOM_PLIES;
    int resignScore = DEFAULT_RESIGN_SCORE;
    int resignPlies = DEFAULT_RESIGN_PLIES;
    int tableSizeMB = DEFAULT_TABLE_SIZE_MB;
    Path book = Path.of(OpeningBook.DEFAULT_BOOK_PATH);
    Path out = Path.of(DEFAULT_OUT);
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
        case "--random-plies" -> randomPlies = Integer.parseInt(value);
        case "--resign-score" -> resignScore = Integer.parseInt(value);
        case "--resign-plies" -> resignPlies = Integer.parseInt(value);
        case "--hash" -> tableSizeMB = Integer.parseInt(value);
        case "--book" -> book = Path.of(value);
        case "--out" -> out = Path.of(value);
        default -> usage("Unknown option " + args[i - 1]);
      }
    }

    final List<OpeningBook.Opening> openings = new ArrayList<>(OpeningBook.load(book));
    Collections.shuffle(openings, new Random(seed));
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }

    System.err.printf("Training data: %d games from %s (seed %d), %d random plies, %d nodes per " +
                    "move, resign at %d for %d plies, %d MB hash, %d games at once, appending to %s%n",
            games, book.getFileName(), seed, randomPlies, nodes, resignScore, resignPlies,
            tableSizeMB, workers, out);

    final PrintStream console = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));

    final AtomicInteger finished = new AtomicInteger();
    final AtomicInteger discarded = new AtomicInteger();
    final AtomicLong positions = new AtomicLong();
    final int gameCount = games;
    final long nodeLimit = nodes;
    final long runSeed = seed;
    final int randomMoves = randomPlies;
    final int resignAt = resignScore;
    final int resignAfter = resignPlies;
    final int tableSize = tableSizeMB;
    final long start = System.currentTimeMillis();
    final ExecutorService pool = Executors.newFixedThreadPool(workers);
    try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE,
            StandardOpenOption.APPEND)) {
      final List<Future<?>> results = new ArrayList<>();
      for (int game = 0; game < gameCount; game++) {
        final OpeningBook.Opening opening = openings.get(game % openings.size());
        final Random random = new Random(runSeed + game * GAME_SEED_STRIDE);
        results.add(pool.submit(() -> {
          final List<String> lines = playGame(opening, random, randomMoves, nodeLimit, resignAt,
                  resignAfter, tableSize);
          if (lines == null) {
            discarded.incrementAndGet();
          } else {
            synchronized (writer) {
              for (final String line : lines) {
                writer.write(line);
                writer.newLine();
              }
              writer.flush();
            }
            positions.addAndGet(lines.size());
          }
          final int done = finished.incrementAndGet();
          if (done % PROGRESS_INTERVAL == 0 || done == gameCount) {
            final double seconds = (System.currentTimeMillis() - start) / 1000.0;
            System.err.printf("%d of %d games, %d discarded, %d positions, %.0f s, " +
                            "%.0f positions per second%n", done, gameCount, discarded.get(),
                    positions.get(), seconds, positions.get() / seconds);
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
  }

  /**
   * Plays one game and returns the lines of its recorded positions.
   *
   * @param opening The opening the game starts from.
   * @param random The generator the random moves after the opening are drawn from.
   * @param randomPlies The number of random moves played after the opening.
   * @param nodeLimit The node limit of each search.
   * @param resignScore The score in centipawns a side must be ahead by for a ply to count
   *                    towards a win.
   * @param resignPlies The number of consecutive plies at the resign score that end the game as a
   *                    win.
   * @param tableSizeMB The transposition table size of each engine in megabytes.
   * @return The lines of the game's recorded positions, or null if the game was discarded.
   */
  private static List<String> playGame(final OpeningBook.Opening opening, final Random random,
                                       final int randomPlies, final long nodeLimit,
                                       final int resignScore, final int resignPlies,
                                       final int tableSizeMB) {
    final Board board = OpeningBook.play(opening);
    int plies = opening.moves().size();
    for (int i = 0; i < randomPlies; i++) {
      if (resultOf(board) != null) {
        return null;
      }
      final List<Move> legalMoves = new ArrayList<>();
      for (final Move move : board.currentPlayer().getLegalMoves()) {
        if (board.isLegal(move)) {
          legalMoves.add(move);
        }
      }
      board.makeMove(legalMoves.get(random.nextInt(legalMoves.size())));
      plies++;
    }

    final List<String> fens = new ArrayList<>();
    final List<Integer> scores = new ArrayList<>();
    final AlphaBeta white = new AlphaBeta(LIMIT_DEPTH, tableSizeMB, 1);
    final AlphaBeta black = new AlphaBeta(LIMIT_DEPTH, tableSizeMB, 1);
    final Adjudicator adjudicator = new Adjudicator(true, resignScore, resignPlies, DRAW_SCORE,
            DRAW_PLIES, DRAW_AFTER);
    String result;
    try {
      boolean firstSearch = true;
      while (true) {
        result = resultOf(board);
        if (result != null) {
          break;
        }
        if (plies >= PLY_CAP) {
          result = "0.5";
          break;
        }
        final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
        final AlphaBeta engine = whiteToMove ? white : black;
        final Move move = engine.execute(board, LIMIT_DEPTH, nodeLimit, AlphaBeta.UNLIMITED_TIME);
        final double score = engine.getLastScore();
        if (firstSearch && Math.abs(score) > OPENING_SCORE_LIMIT) {
          return null;
        }
        firstSearch = false;
        if (!board.currentPlayer().isInCheck() && !move.isAttack()
                && move.getPromotionPiece() == null && Math.abs(score) < AlphaBeta.MATE_THRESHOLD) {
          fens.add(FenWriter.write(board));
          scores.add((int) Math.round(score));
        }
        final EngineProcess.Reply reply = replyOf(score, whiteToMove);
        board.makeMove(move);
        plies++;
        final Adjudicator.Verdict verdict = adjudicator.judge(reply, whiteToMove, plies);
        if (verdict != Adjudicator.Verdict.NONE) {
          result = switch (verdict) {
            case WHITE_WINS -> "1.0";
            case BLACK_WINS -> "0.0";
            default -> "0.5";
          };
          break;
        }
      }
    } finally {
      white.shutdown();
      black.shutdown();
    }

    final List<String> lines = new ArrayList<>(fens.size());
    for (int i = 0; i < fens.size(); i++) {
      lines.add(fens.get(i) + " | " + scores.get(i) + " | " + result);
    }
    return lines;
  }

  /**
   * Returns the result of a game that has ended in the given position by checkmate, stalemate,
   * insufficient material, the fifty move rule or threefold repetition.
   *
   * @param board The position to judge.
   * @return The result from White's point of view, "1.0", "0.5" or "0.0", or null if the game has
   *         not ended.
   */
  private static String resultOf(final Board board) {
    if (board.currentPlayer().isInCheckMate()) {
      return board.currentPlayer().getAlliance().isWhite() ? "0.0" : "1.0";
    }
    if (board.currentPlayer().isInStaleMate() || board.isInsufficientMaterial()
            || board.isFiftyMoveRule() || board.isThreefoldRepetition()) {
      return "0.5";
    }
    return null;
  }

  /**
   * Returns a search score as the UCI engine would report it, from the point of view of the side
   * that moved: in centipawns, or as the number of moves to a mate.
   *
   * @param score The score of the search, from White's point of view.
   * @param whiteMoved True if White searched.
   * @return The reply the adjudicator judges, with no move and no depth.
   */
  private static EngineProcess.Reply replyOf(final double score, final boolean whiteMoved) {
    final double relativeScore = whiteMoved ? score : -score;
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
    System.err.println("Usage: TrainingDataRun [--games n] [--workers n] [--nodes n] [--seed n] " +
            "[--random-plies n] [--resign-score centipawns] [--resign-plies n] [--book path] " +
            "[--hash megabytes] [--out path]");
    System.exit(1);
  }
}
