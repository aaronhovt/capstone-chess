package engine.forPlayer.forAI;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ComparisonChain;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static engine.forBoard.Move.MoveFactory;

/**
 * The AlphaBeta class implements a chess engine using the alpha-beta search algorithm
 * with Lazy SMP parallel search optimization. This implementation employs advanced chess
 * engine techniques including iterative deepening, aspiration windows, transposition tables,
 * quiescence search, null move pruning, late move reductions, and various move ordering
 * heuristics to achieve high-performance chess move selection.
 * <p>
 * The engine searches on several threads at once. Every thread runs its own iterative deepening
 * over a private copy of the position and shares nothing but the transposition table, so a helper
 * thread contributes by filling that table rather than by returning a move. It incorporates modern
 * pruning techniques and evaluation caching to reduce the search space and improve performance.
 *
 * @author Aaron Ho
 */
public class AlphaBeta extends Observable implements MoveStrategy {

  /** The evaluator used to assess board positions, selected at the start of each search. */
  private volatile BoardEvaluator evaluator;

  /** The depth used by {@link #execute(Board)} when a caller supplies no depth of its own. */
  private final int maxDepth;

  /** The count of boards evaluated during the search process. */
  private final AtomicLong boardsEvaluated = new AtomicLong(0);

  /** The root score of the most recent search, from White's point of view. */
  private volatile double lastScore;

  /** The depth of the deepest iteration the most recent search finished. */
  private volatile int lastDepth;

  /** Thread-local search statistics for tracking per-thread performance metrics. */
  private final ThreadLocal<SearchStats> threadStats = new ThreadLocal<>();

  /** The number of search threads, including the thread that calls the search. */
  private final int threadCount;

  /** Flag indicating whether the search should be stopped. */
  private volatile boolean searchStopped;

  /** Thread pool holding this engine's helper search threads. */
  private final ExecutorService searchThreadPool;

  /** Thread-safe transposition table for storing previously evaluated positions. */
  private final StripedTranspositionTable transpositionTable;

  /** Cache of board evaluations belonging to this engine, cleared at the start of each search. */
  private final EvaluationCache evaluationCache = new EvaluationCache();

  /** History heuristic table for move ordering, indexed by origin and destination square. */
  private final int[][] historyHeuristic = new int[64][64];

  /** Killer moves table storing good non-capture moves for each search ply. */
  private final ThreadLocal<Move[][]> killerMoves = ThreadLocal.withInitial(() ->
          new Move[2][MAX_SEARCH_DEPTH]);

  /** Countermove table for storing responses to opponent moves for improved move ordering. */
  @SuppressWarnings("unchecked")
  private final AtomicReference<Move>[][] counterMoves = new AtomicReference[64][64];

  /** The maximum number of quiescence search nodes allowed per search. */
  private static final int MAX_QUIESCENCE = 300000;

  /** The node limit that lets a search run to the depth it was asked for. */
  public static final long UNLIMITED_NODES = Long.MAX_VALUE;

  /** The time limit in milliseconds that lets a search run to the depth it was asked for. */
  public static final long UNLIMITED_TIME = Long.MAX_VALUE;

  /** The deadline held by a search that is not under a time limit. */
  private static final long NO_DEADLINE = Long.MAX_VALUE;

  /** The mask a node count is tested against to decide whether the clock is read. */
  private static final long TIME_CHECK_MASK = 1023;

  /** Maximum search depth supported by data structures. */
  private static final int MAX_SEARCH_DEPTH = 100;

  /** The ply at which a node is evaluated rather than searched, bounding search recursion. */
  private static final int MAX_PLY = MAX_SEARCH_DEPTH - 2;

  /** The transposition table size in megabytes used when a caller does not specify one. */
  private static final int DEFAULT_TABLE_SIZE_MB = 256;

  /** The depth threshold for applying futility pruning. */
  private static final int FUTILITY_PRUNING_DEPTH = 3;

  /** The least remaining depth at which a null move is searched. */
  private static final int NULL_MOVE_DEPTH = 3;

  /** The least remaining depth at which a null move cutoff is verified before it is taken. */
  private static final int NULL_MOVE_VERIFICATION_DEPTH = 6;

  /** The width of a zero window, being the smallest score separation the search distinguishes. */
  private static final double ZERO_WINDOW = 0.1;

  /** The move count threshold for applying late move reductions. */
  private static final int LMR_THRESHOLD = 9;

  /** The reduction scale factor used in late move reductions. */
  private static final double LMR_SCALE = 0.9;

  /** The evaluation margin for delta pruning in quiescence search. */
  private static final double DELTA_PRUNING_VALUE = 5;

  /** The evaluation margin for razoring pruning technique. */
  private static final double RAZOR_MARGIN = 150;

  /** The starting half-width of the aspiration window at the root. */
  private static final double ASPIRATION_WINDOW = 40;

  /** The material threshold for delta pruning in quiescence search. */
  private static final double DELTA_MATERIAL = 100;

  /** The static exchange evaluation threshold for pruning bad captures. */
  private static final int SEE_PRUNING_THRESHOLD = -20;

  /** The low bits of a packed quiescence ordering key that hold the source index of a capture. */
  private static final long INDEX_MASK = (1L << 30) - 1;

  /** The number of low bits of a packed standard ordering key that hold a move's source index. */
  private static final int ORDER_INDEX_BITS = 10;

  /** The mask that recovers a move's source index from a packed standard ordering key. */
  private static final long ORDER_INDEX_MASK = (1L << ORDER_INDEX_BITS) - 1;

  /** The bit position at which a packed standard ordering key holds its tier. */
  private static final int ORDER_TIER_SHIFT = ORDER_INDEX_BITS + 32;

  /** The tier bit a packed standard ordering key sets when its move is not an undefended capture. */
  private static final long UNDEFENDED_TIER_BIT = 16;

  /** The tier bit a packed standard ordering key sets when its move is not a killer move. */
  private static final long KILLER_TIER_BIT = 8;

  /** The tier bit a packed standard ordering key sets when its move is not a countermove. */
  private static final long COUNTER_TIER_BIT = 4;

  /** The tier rank of a capture whose static exchange score is not negative. */
  private static final long GOOD_CAPTURE_RANK = 0;

  /** The tier rank of a move that is not a capture. */
  private static final long QUIET_RANK = 1;

  /** The tier rank of a capture whose static exchange score is negative. */
  private static final long BAD_CAPTURE_RANK = 2;

  /** The score of a checkmate delivered at the root, reduced by one for each ply to the mate. */
  public static final double MATE_VALUE = 1000000;

  /** The lowest magnitude at which a score is a checkmate score rather than an evaluation. */
  public static final double MATE_THRESHOLD = MATE_VALUE - MAX_SEARCH_DEPTH;

  /** Reference to the static exchange evaluator for move evaluation. */
  private final StaticExchangeEvaluator seeEvaluator = StaticExchangeEvaluator.get();

  /**
   * The MoveSorter enumeration defines different strategies for ordering moves
   * to improve alpha-beta search efficiency. Different sorting strategies are
   * used based on the search context and depth.
   */
  private enum MoveSorter {

    /**
     * Standard move sorting strategy using history heuristic, killer moves,
     * countermoves, and static exchange evaluation for move ordering.
     */
    STANDARD {
      @Override
      Collection<Move> sort(final Collection<Move> moves, final Board board,
                            final AlphaBeta engine, final int ply) {
        final Move[] ordered = moves.toArray(new Move[0]);
        final int count = ordered.length;
        final Move[][] killers = engine.killerMoves.get();
        final Move counter = counterMoveOf(board, engine);

        // Bits 42 through 46 hold the tier, which is set for each ordering property the move does
        // not have so that a move having it sorts first, and is completed by the capture rank so
        // that a good capture outranks a quiet move and a quiet move outranks a bad capture. Bits
        // 10 through 41 hold the static exchange score of a capture or the history score of a
        // quiet move, negated against Integer.MAX_VALUE so that higher scores sort first. Bits 0
        // through 9 hold the source index, so equal keys keep move generation order and a list of
        // more than ORDER_INDEX_MASK moves cannot be packed. Every key is non-negative, so sorting
        // the packed values ascending yields the intended move order.
        final long[] orderKeys = new long[count];
        for (int i = 0; i < count; i++) {
          final Move move = ordered[i];
          final boolean capture = move.isAttack();

          int exchangeScore = 0;
          boolean undefended = false;
          if (capture) {
            exchangeScore = engine.seeEvaluator.evaluate(board, move);
            final Piece attackedPiece = move.getAttackedPiece();
            undefended = attackedPiece != null &&
                    !engine.seeEvaluator.isPieceDefended(attackedPiece, board);
          }

          final long rank = capture ? (exchangeScore >= 0 ? GOOD_CAPTURE_RANK : BAD_CAPTURE_RANK) :
                  QUIET_RANK;
          final long tier = (undefended ? 0 : UNDEFENDED_TIER_BIT) +
                  (move.equals(killers[0][ply]) || move.equals(killers[1][ply]) ? 0 : KILLER_TIER_BIT) +
                  (counter != null && move.equals(counter) ? 0 : COUNTER_TIER_BIT) + rank;
          final long secondary = (long) Integer.MAX_VALUE -
                  (capture ? (long) exchangeScore : (long) historyOf(move, engine));

          orderKeys[i] = (tier << ORDER_TIER_SHIFT) | (secondary << ORDER_INDEX_BITS) | i;
        }
        Arrays.sort(orderKeys);

        final List<Move> sortedMoves = new ArrayList<>(count);
        for (final long orderKey : orderKeys) {
          sortedMoves.add(ordered[(int) (orderKey & ORDER_INDEX_MASK)]);
        }
        return sortedMoves;
      }
    },

    /**
     * Expensive move sorting strategy used for root moves that performs
     * comprehensive evaluation including threat analysis and detailed move scoring.
     */
    EXPENSIVE {
      @Override
      Collection<Move> sort(final Collection<Move> moves, final Board board,
                            final AlphaBeta engine, final int ply) {
        List<Move> sortedMoves = new ArrayList<>(moves);

        Map<Move, Integer> seeScores = new HashMap<>();
        Map<Move, Integer> historyScores = new HashMap<>();
        for (Move move : sortedMoves) {
          if (move.isAttack()) {
            seeScores.put(move, engine.seeEvaluator.evaluate(board, move));
          }
          historyScores.put(move, isValidPosition(move) ?
                  engine.historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] : 0);
        }

        // Whether a move gives check is resolved once per move here rather than inside the
        // comparator, which would ask the same question O(n log n) times. It is resolved against a
        // private copy of the position because kingThreat mutates the board it is handed, and the
        // board this sorter is given at the root is the game board the rest of the application is
        // reading.
        final Board probeBoard = board.copy();
        Map<Move, Boolean> givesCheck = new HashMap<>();
        for (Move move : sortedMoves) {
          givesCheck.put(move, BoardUtils.kingThreat(move, probeBoard));
        }

        // The two score comparisons take their operands reversed, so that a higher static exchange
        // score and a higher history score each sort first.
        sortedMoves.sort((move1, move2) -> ComparisonChain.start()
                .compareTrueFirst(givesCheck.getOrDefault(move1, false),
                        givesCheck.getOrDefault(move2, false))
                .compareTrueFirst(move1.isCastlingMove(), move2.isCastlingMove())
                .compare(captureRankOf(move1, seeScores), captureRankOf(move2, seeScores))
                .compare(seeScores.getOrDefault(move2, 0), seeScores.getOrDefault(move1, 0))
                .compare(historyScores.getOrDefault(move2, 0), historyScores.getOrDefault(move1, 0))
                .result());
        return sortedMoves;
      }

      /**
       * Returns the ordering rank of the given move, which places a good capture before a quiet
       * move and a quiet move before a bad capture.
       *
       * @param move The move to rank.
       * @param seeScores The static exchange scores of the capturing moves being sorted.
       * @return GOOD_CAPTURE_RANK, QUIET_RANK, or BAD_CAPTURE_RANK.
       */
      private long captureRankOf(final Move move, final Map<Move, Integer> seeScores) {
        if (!move.isAttack()) {
          return QUIET_RANK;
        }
        return seeScores.getOrDefault(move, 0) >= 0 ? GOOD_CAPTURE_RANK : BAD_CAPTURE_RANK;
      }

      /**
       * Validates that a move has coordinates within the valid range for history heuristic access.
       *
       * @param move The move to validate.
       * @return True if the move has valid coordinates, false otherwise.
       */
      private boolean isValidPosition(Move move) {
        int current = move.getCurrentCoordinate();
        int dest = move.getDestinationCoordinate();
        return current >= 0 && current < 64 && dest >= 0 && dest < 64;
      }
    };

    /**
     * Sorts the given collection of moves according to the strategy's ordering criteria.
     *
     * @param moves The collection of moves to sort.
     * @param board The current board position.
     * @param engine The engine instance for accessing move ordering data.
     * @param ply The current search ply for accessing ply-specific data.
     * @return A sorted collection of moves.
     */
    abstract Collection<Move> sort(Collection<Move> moves, final Board board,
                                   final AlphaBeta engine, final int ply);

    /**
     * Returns the countermove recorded against the move that produced the given position.
     *
     * @param board The position being sorted.
     * @param engine The engine holding the countermove table.
     * @return The recorded countermove, or null if none is recorded.
     */
    private static Move counterMoveOf(final Board board, final AlphaBeta engine) {
      final Move lastMove = board.getTransitionMove();
      if (lastMove == null || lastMove == MoveFactory.getNullMove() ||
              lastMove.getCurrentCoordinate() < 0 || lastMove.getDestinationCoordinate() < 0 ||
              lastMove.getCurrentCoordinate() >= 64 || lastMove.getDestinationCoordinate() >= 64) {
        return null;
      }
      return engine.counterMoves[lastMove.getCurrentCoordinate()]
              [lastMove.getDestinationCoordinate()].get();
    }

    /**
     * Returns the history heuristic score recorded for the given move.
     *
     * @param move The move to score.
     * @param engine The engine holding the history table.
     * @return The recorded history score, or zero if the move has no pair of squares in range.
     */
    private static int historyOf(final Move move, final AlphaBeta engine) {
      if (move == null) {
        return 0;
      }
      final int current = move.getCurrentCoordinate();
      final int destination = move.getDestinationCoordinate();
      if (current < 0 || current >= 64 || destination < 0 || destination >= 64) {
        return 0;
      }
      return engine.historyHeuristic[current][destination];
    }
  }

  /**
   * Constructs an AlphaBeta chess engine with the given default search depth and a transposition
   * table of the default size.
   *
   * @param maxDepth The depth used by {@link #execute(Board)} when no depth is supplied per search.
   */
  public AlphaBeta(final int maxDepth) {
    this(maxDepth, DEFAULT_TABLE_SIZE_MB);
  }

  /**
   * Constructs an AlphaBeta chess engine with the given default search depth and transposition
   * table size, searching on one thread per available processor.
   *
   * @param maxDepth The depth used by {@link #execute(Board)} when no depth is supplied per search.
   * @param tableSizeMB The size of the transposition table in megabytes, at least one.
   * @throws IllegalArgumentException If the requested table size is less than one megabyte.
   */
  public AlphaBeta(final int maxDepth, final int tableSizeMB) {
    this(maxDepth, tableSizeMB, Runtime.getRuntime().availableProcessors());
  }

  /**
   * Constructs an AlphaBeta chess engine with the given default search depth, transposition table
   * size, and search thread count. The count includes the thread that calls
   * {@link #execute(Board, int)}, so a count of one searches on the calling thread alone and makes
   * the search reproducible, since a search with helper threads reaches different results on
   * different runs of the same position. The table is allocated once here and serves every search
   * this engine runs.
   *
   * @param maxDepth The depth used by {@link #execute(Board)} when no depth is supplied per search.
   * @param tableSizeMB The size of the transposition table in megabytes, at least one.
   * @param threadCount The number of search threads, at least one.
   * @throws IllegalArgumentException If the requested table size or thread count is less than one.
   */
  public AlphaBeta(final int maxDepth, final int tableSizeMB, final int threadCount) {
    if (tableSizeMB < 1) {
      throw new IllegalArgumentException(
              "The transposition table needs at least one megabyte, requested " + tableSizeMB);
    }
    if (threadCount < 1) {
      throw new IllegalArgumentException(
              "The search needs at least one thread, requested " + threadCount);
    }
    this.maxDepth = maxDepth;
    this.threadCount = threadCount;
    this.searchThreadPool = Executors.newFixedThreadPool(Math.max(1, threadCount - 1));
    this.transpositionTable = new StripedTranspositionTable(tableSizeMB);

    for (int i = 0; i < 64; i++) {
      for (int j = 0; j < 64; j++) {
        counterMoves[i][j] = new AtomicReference<>();
      }
    }
  }

  /**
   * Returns a string representation of this chess engine.
   *
   * @return A string identifying this engine implementation.
   */
  @Override
  public String toString() {
    return "StockAB with Lazy SMP";
  }

  /**
   * Executes the alpha-beta search to this engine's default depth.
   *
   * @param board The current chess board position.
   * @return The best move determined by the search algorithm.
   */
  @Override
  public Move execute(final Board board) {
    return execute(board, this.maxDepth);
  }

  public Move execute(final Board board, final int searchDepth) {
    return execute(board, searchDepth, UNLIMITED_NODES);
  }

  /**
   * Executes the alpha-beta search algorithm with iterative deepening to find the best move for
   * the current player, to the given depth and under no time limit.
   *
   * @param board The current chess board position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @param nodeLimit The number of positions to evaluate before the search is stopped, or
   *                  UNLIMITED_NODES to run every iteration to its end.
   * @return The best move determined by the search algorithm.
   */
  public Move execute(final Board board, final int searchDepth, final long nodeLimit) {
    return execute(board, searchDepth, nodeLimit, UNLIMITED_TIME);
  }

  /**
   * Executes the alpha-beta search algorithm with iterative deepening to find the best move for
   * the current player, to the given depth.
   * <p>
   * The calling thread is the main search thread and its result is the one returned. Every other
   * search thread runs its own independent iterative deepening over a private copy of the position
   * and its results are discarded, so the only thing those threads contribute is the entries they
   * leave in the shared transposition table. They are stopped as soon as the main search finishes,
   * and this method does not return until they have.
   * <p>
   * A search that reaches the node limit or the time limit is stopped where it stands and the
   * iteration it was in the middle of is discarded, so the move and score returned are those of
   * the deepest iteration that finished. The first iteration is held to neither limit, so a
   * finished iteration always exists.
   * <p>
   * The clock is not read at every node, so a search under a time limit runs somewhat past its
   * deadline rather than stopping on it.
   *
   * @param board The current chess board position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @param nodeLimit The number of positions to evaluate before the search is stopped, or
   *                  UNLIMITED_NODES to run every iteration to its end.
   * @param timeLimitMillis The milliseconds to search for before the search is stopped, or
   *                        UNLIMITED_TIME to run every iteration to its end.
   * @return The best move determined by the search algorithm.
   */
  public Move execute(final Board board, final int searchDepth, final long nodeLimit,
                      final long timeLimitMillis) {
    final long startTime = System.currentTimeMillis();
    final long deadline = deadlineOf(timeLimitMillis);
    Move bestMove = MoveFactory.getNullMove();
    double bestScore = 0;
    int bestDepth = 0;

    this.searchStopped = false;
    this.boardsEvaluated.set(0);
    this.transpositionTable.incrementAge();
    this.evaluator = determineGameState(board);

    this.evaluationCache.clear();

    final Board mainBoard = board.copy();
    final long rootHash = mainBoard.getZobristHash();
    final List<Future<?>> helpers = startHelperSearches(board, searchDepth);

    final SearchStats stats = new SearchStats();
    this.threadStats.set(stats);

    try {
      for (int currentDepth = 1; currentDepth <= searchDepth && !searchStopped; currentDepth++) {
        stats.quiescenceCount = 0;
        stats.nodeLimit = currentDepth == 1 ? UNLIMITED_NODES : nodeLimit;
        stats.deadline = currentDepth == 1 ? NO_DEADLINE : deadline;

        final RootResult result = currentDepth >= 4 ?
                searchRootAspirationWindow(mainBoard, currentDepth, bestMove, bestScore) :
                searchRoot(mainBoard, currentDepth, -Double.MAX_VALUE, Double.MAX_VALUE, bestMove);

        if (searchStopped || result.move() == MoveFactory.getNullMove()) {
          break;
        }

        bestMove = result.move();
        bestScore = result.score();
        bestDepth = currentDepth;

        updateHistoryHeuristic(bestMove, currentDepth);

        final long evaluatedPositions = this.boardsEvaluated.get() + stats.boardsEvaluated;
        final long executionTime = System.currentTimeMillis() - startTime;
        final String report = String.format(
                "%s | depth = %d | boards evaluated = %d | time = %.2f sec | nps = %.2f | %s",
                bestMove, currentDepth, evaluatedPositions,
                executionTime / 1000.0,
                evaluatedPositions / (executionTime / 1000.0),
                this.evaluationCache.getStats());

        System.out.println(report);
        setChanged();
        notifyObservers(report);
      }
    } finally {
      stopHelperSearches(helpers);
      this.boardsEvaluated.addAndGet(stats.boardsEvaluated);
    }

    assert mainBoard.getZobristHash() == rootHash :
            "The main search left its board somewhere other than the root position.";

    this.lastScore = bestScore;
    this.lastDepth = bestDepth;

    return bestMove;
  }

  /**
   * Returns the point in time a search under the given time limit is stopped at.
   *
   * @param timeLimitMillis The milliseconds the search may run for, or UNLIMITED_TIME to run
   *                        without a deadline.
   * @return The deadline as a reading of {@link System#nanoTime()}, or NO_DEADLINE if the search
   *         is not under a time limit.
   */
  private static long deadlineOf(final long timeLimitMillis) {
    if (timeLimitMillis >= UNLIMITED_TIME / 1_000_000L) {
      return NO_DEADLINE;
    }
    return System.nanoTime() + timeLimitMillis * 1_000_000L;
  }

  /**
   * Reports whether a search thread has reached the node limit or the deadline it is searching
   * under. The clock is read only on the node counts the check mask selects, so a deadline is
   * noticed a little after it passes.
   *
   * @param stats The statistics of the calling thread, holding its limits and its node count.
   * @return True if the search should be stopped.
   */
  private static boolean limitReached(final SearchStats stats) {
    if (stats.boardsEvaluated >= stats.nodeLimit) {
      return true;
    }
    return (stats.boardsEvaluated & TIME_CHECK_MASK) == 0 && System.nanoTime() >= stats.deadline;
  }

  /**
   * Starts a search thread for every search thread this engine owns beyond the calling one. Each
   * is given its own copy of the position, taken here on the calling thread.
   *
   * @param board The root board position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @return The futures of the started searches, empty if this engine searches on one thread.
   */
  private List<Future<?>> startHelperSearches(final Board board, final int searchDepth) {
    final List<Future<?>> helpers = new ArrayList<>();
    for (int helperId = 1; helperId < this.threadCount; helperId++) {
      final int id = helperId;
      final Board helperBoard = board.copy();
      helpers.add(this.searchThreadPool.submit(() -> runHelperSearch(helperBoard, searchDepth, id)));
    }
    return helpers;
  }

  /**
   * Raises the stop flag and waits for every helper search to unwind.
   *
   * @param helpers The futures returned by {@link #startHelperSearches}.
   */
  private void stopHelperSearches(final List<Future<?>> helpers) {
    this.searchStopped = true;
    for (final Future<?> helper : helpers) {
      try {
        helper.get();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final ExecutionException e) {
        System.err.println("Helper search thread failed: " + e.getCause());
      }
    }
  }

  /**
   * Runs one helper's iterative deepening over its own copy of the position, discarding the moves
   * it finds. The ladder starts one ply higher for odd numbered helpers so that helpers do not all
   * repeat the main search, and never runs past the depth the main search was asked for, so no
   * entry deeper than that depth reaches the transposition table.
   *
   * @param board The helper's private copy of the root position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @param helperId The helper's number, counted from one.
   */
  private void runHelperSearch(final Board board, final int searchDepth, final int helperId) {
    final long rootHash = board.getZobristHash();
    Move bestMove = MoveFactory.getNullMove();

    for (int depth = 1 + (helperId % 2); depth <= searchDepth && !searchStopped; depth++) {
      final SearchStats stats = new SearchStats();
      this.threadStats.set(stats);
      try {
        bestMove = searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, bestMove).move();
      } finally {
        this.boardsEvaluated.addAndGet(stats.boardsEvaluated);
      }
    }

    assert board.getZobristHash() == rootHash :
            "Helper search left its board somewhere other than the root position.";
  }

  /**
   * Raises the stop flag and shuts this engine's search thread pool down. The engine cannot
   * search after this returns.
   */
  public void shutdown() {
    this.searchStopped = true;
    this.searchThreadPool.shutdown();
  }

  /**
   * Returns the root score of the most recent search, from White's point of view.
   *
   * @return The score of the move the last search returned.
   */
  public double getLastScore() {
    return this.lastScore;
  }

  /**
   * Returns the depth of the deepest iteration the most recent search finished. An iteration the
   * node limit aborted is not counted, and the depth is the one the calling thread reached rather
   * than the one any helper thread reached.
   *
   * @return The depth the last search finished, or zero if it finished no iteration.
   */
  public int getLastDepth() {
    return this.lastDepth;
  }

  /**
   * Returns the number of positions the most recent search evaluated, counting every thread that
   * took part in it. The count is reset at the start of each search.
   *
   * @return The number of positions the last search evaluated.
   */
  public long getBoardsEvaluated() {
    return this.boardsEvaluated.get();
  }

  /**
   * Retrieves a cached board evaluation or computes a new evaluation if not found in cache.
   *
   * @param board The board position to evaluate.
   * @param depth The search depth the cache entry is keyed under.
   * @return The evaluation score for the board position.
   */
  private double getCachedEvaluation(Board board, int depth) {
    Double cachedScore = this.evaluationCache.probe(board, depth);
    if (cachedScore != null) {
      return cachedScore;
    }

    double score = this.evaluator.evaluate(board);
    this.evaluationCache.store(board, depth, score);
    return score;
  }

  /**
   * Searches the root with a narrow window centered on the score of the previous iteration. A
   * search whose score reaches or passes a bound is discarded and repeated with that bound moved
   * out and the window doubled, until the score lands strictly inside the window or the window
   * and full window is used. A checkmate score from the previous iteration is never narrowed.
   *
   * @param board The board this search thread owns, in the root position.
   * @param depth The current search depth.
   * @param previousBestMove The best move from the previous iteration.
   * @param previousScore The root score from the previous iteration.
   * @return The best move found and its score, or the null move and a score of zero if the search
   *         was stopped before an attempt finished.
   */
  private RootResult searchRootAspirationWindow(final Board board, final int depth,
                                                final Move previousBestMove, final double previousScore) {
    if (Math.abs(previousScore) >= MATE_THRESHOLD) {
      return searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
    }

    double alpha = previousScore - ASPIRATION_WINDOW;
    double beta = previousScore + ASPIRATION_WINDOW;

    for (int attempt = 0; attempt < 2; attempt++) {
      final RootResult result = searchRoot(board, depth, alpha, beta, previousBestMove);

      if (this.searchStopped) {
        return new RootResult(MoveFactory.getNullMove(), 0);
      } if (result.score() > alpha && result.score() < beta) {
        return result;
      }

      if (result.score() <= alpha) {
        alpha = -Double.MAX_VALUE;
      } else {
        beta = Double.MAX_VALUE;
      }
    }

    return searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
  }

  /**
   * Searches every legal root move on the calling thread and returns the best one with its score.
   * The move that was best in the previous iteration is searched first, and each move is searched
   * against a window narrowed to the best score found so far. A root move that leaves the mover in
   * check is skipped. A search that is stopped part way returns the best move found up to that point,
   * and the null move if it finished no move at all
   *
   * @param board The board this search thread owns, in the root position. It is left in that
   *              position when this method returns.
   * @param depth The current search depth.
   * @param alpha The alpha bound for alpha-beta search.
   * @param beta The beta bound for alpha-beta search.
   * @param previousBestMove The best move from the previous iteration, which may be null.
   * @return The best move found and its score.
   */
  private RootResult searchRoot(final Board board, final int depth, final double alpha,
                                final double beta, final Move previousBestMove) {
    final List<Move> rootMoves = new ArrayList<>(
            MoveSorter.EXPENSIVE.sort(board.currentPlayer().getLegalMoves(), board, this, 0));

    if (rootMoves.isEmpty()) {
      return new RootResult(MoveFactory.getNullMove(), 0);
    }

    if (previousBestMove != null && previousBestMove != MoveFactory.getNullMove() &&
            rootMoves.contains(previousBestMove)) {
      rootMoves.remove(previousBestMove);
      rootMoves.add(0, previousBestMove);
    }

    final boolean rootIsWhite = board.currentPlayer().getAlliance().isWhite();
    Move bestMove = MoveFactory.getNullMove();
    double bestScore = rootIsWhite ? alpha : beta;

    for (final Move move : rootMoves) {
      if (searchStopped) {
        break;
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      double score;
      try {
        score = rootIsWhite ?
                min(board, depth - 1, bestScore, beta, 1, true) :
                max(board, depth - 1, alpha, bestScore, 1, true);
      } finally {
        board.unmakeMove();
      }

      if (rootIsWhite ? score > bestScore : score < bestScore) {
        bestScore = score;
        bestMove = move;

        if (rootIsWhite ? bestScore >= beta : bestScore <= alpha) {
          recordCounterMove(board, move);
          break;
        }
      }
    }

    return new RootResult(bestMove, bestScore);
  }

  /**
   * The move a root search chose and the score it was given.
   *
   * @param move The best root move found.
   * @param score The score of that move.
   */
  private record RootResult(Move move, double score) {
  }

  /**
   * Records a move as a countermove response to the last opponent move for move ordering.
   *
   * @param board The current board position.
   * @param move The move to record as a countermove.
   */
  private void recordCounterMove(Board board, Move move) {
    Move lastMove = board.getTransitionMove();
    if (lastMove != null && lastMove != MoveFactory.getNullMove()) {
      counterMoves[lastMove.getCurrentCoordinate()][lastMove.getDestinationCoordinate()].set(move);
    }
  }

  /**
   * Returns whether the position at a node below the root is to be scored as a draw without
   * being searched. A position that has already been reached once on the path to this node, or
   * that has reached the fifty-move limit without the side to move being checkmated, is drawn.
   * A position reached through a null move is never treated as drawn, since it was never reached
   * in a real game.
   *
   * @param board The board at the current node.
   * @return True if the node is to be scored as a draw.
   */
  private static boolean isDrawnByRule(final Board board) {
    if (board.getTransitionMove() == MoveFactory.getNullMove()) {
      return false;
    }
    if (board.isFiftyMoveRule()) {
      return !board.currentPlayer().isInCheckMate();
    }
    return board.repetitionCount() >= 2;
  }

  /**
   * Scores a position in which the side to move has no legal moves. A checkmate scores
   * {@link #MATE_VALUE} against the mated side, reduced by the ply at which it occurs so that
   * shorter mates outrank longer ones, and a stalemate scores as a draw.
   *
   * @param board The terminal board position.
   * @param ply The current search ply, counted from the root.
   * @return The terminal score, positive when Black is mated and negative when White is mated.
   */
  private double terminalScore(final Board board, final int ply) {
    if (!board.currentPlayer().isInCheckMate()) {
      return 0;
    }
    final double mateScore = MATE_VALUE - ply;
    return board.currentPlayer().getAlliance().isWhite() ? -mateScore : mateScore;
  }

  /**
   * Converts a score into the form the transposition table stores. A checkmate score counts plies
   * from the root, which makes it a property of the path to the position rather than of the
   * position, so it is rewritten to count plies from this node before it is stored.
   *
   * @param score The score as the search produced it.
   * @param ply The current search ply, counted from the root.
   * @return The score to store against the position's key.
   */
  private static double scoreToTable(final double score, final int ply) {
    if (score >= MATE_THRESHOLD) {
      return score + ply;
    }
    if (score <= -MATE_THRESHOLD) {
      return score - ply;
    }
    return score;
  }

  /**
   * Converts a score read from the transposition table back into a score counted from the root,
   * reversing {@link #scoreToTable}.
   *
   * @param score The score as it was stored.
   * @param ply The current search ply, counted from the root.
   * @return The score as the search at this ply should read it.
   */
  private static double scoreFromTable(final double score, final int ply) {
    if (score >= MATE_THRESHOLD) {
      return score - ply;
    }
    if (score <= -MATE_THRESHOLD) {
      return score + ply;
    }
    return score;
  }

  /**
   * Stores an entry in the transposition table unless the search has been stopped. A score
   * produced after the stop flag is raised is not the result of a completed search.
   *
   * @param zobristHash The Zobrist hash of the board position.
   * @param score The score to store, already converted by {@link #scoreToTable}.
   * @param depth The search depth the score was produced at.
   * @param nodeType The type of node (exact, lower bound, upper bound).
   * @param bestMove The best move found at this node, or null.
   */
  private void storeIfSearching(final long zobristHash, final double score, final int depth,
                                final byte nodeType, final Move bestMove) {
    if (!searchStopped) {
      transpositionTable.store(zobristHash, score, depth, nodeType, bestMove);
    }
  }

  /**
   * Implements the maximizing player portion of the alpha-beta search algorithm
   * with various pruning techniques and search extensions.
   *
   * @param board The current board position.
   * @param depth The remaining search depth.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @param nullMoveAllowed Whether a null move may be played at this node.
   * @return The best evaluation score for the maximizing player.
   */
  private double max(final Board board, int depth, double alpha, double beta, int ply,
                     boolean nullMoveAllowed) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;
    if (limitReached(stats)) {
      this.searchStopped = true;
    }

    if (ply > 0 && isDrawnByRule(board)) {
      return 0;
    } if (searchStopped) {
      return depth <= 0 ? quiescenceSearch(board, alpha, beta, ply, true) :
              getCachedEvaluation(board, depth);
    } if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, true);
    } if (ply >= MAX_PLY) {
      return getCachedEvaluation(board, depth);
    }

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null && entry.depth >= depth) {
      final double entryScore = scoreFromTable(entry.score, ply);
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entryScore;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entryScore);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entryScore);
      }
      if (alpha >= beta) {
        return entryScore;
      }
    }

    final boolean inCheckAtNode = board.currentPlayer().isInCheck();

    if (depth == 1 && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval + RAZOR_MARGIN < alpha) {
        return quiescenceSearch(board, alpha, beta, ply, true);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval >= beta + (depth * 100)) {
        return eval;
      }
    }

    Move ttMove = entry != null ? entry.move : null;
    if (ttMove == null && depth >= 4) {
      max(board, depth - 2, alpha, beta, ply, nullMoveAllowed);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMove = entry.move;
      }
    }

    if (depth >= NULL_MOVE_DEPTH && nullMoveAllowed && !inCheckAtNode
            && beta < Double.MAX_VALUE && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 2 + depth / 6;
      double nullMoveScore;
      board.makeNullMove();
      try {
        nullMoveScore = min(board, depth - 1 - R, beta - ZERO_WINDOW, beta, ply + 1, false);
      } finally {
        board.unmakeNullMove();
      }
      if (nullMoveScore >= beta
              && (depth < NULL_MOVE_VERIFICATION_DEPTH
                      || max(board, depth - R, alpha, beta, ply, false) >= beta)) {
        return beta;
      }
    }

    double currentAlpha = alpha;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;

    Collection<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);

    if (ttMove != null) {
      List<Move> reorderedMoves = new ArrayList<>();
      for (Move move : sortedMoves) {
        if (move.equals(ttMove)) {
          reorderedMoves.add(0, move);
        } else {
          reorderedMoves.add(move);
        }
      }
      sortedMoves = reorderedMoves;
    }

    for (final Move move : sortedMoves) {
      if (move.isAttack() && depth < 3 && movesSearched > 2) {
        int seeScore = seeEvaluator.evaluate(board, move);
        if (seeScore < SEE_PRUNING_THRESHOLD) {
          continue;
        }
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        if (board.currentPlayer().isInCheck()) {
          newDepth++;
        }

        if (firstMove) {
          currentValue = min(board, newDepth, currentAlpha, beta, ply + 1, true);
        } else {
          int reduction = 0;
          if (depth >= 3 && movesSearched >= 4 && !move.isAttack() && !inCheckAtNode) {
            reduction = 1 + (movesSearched / 6);
            if (reduction > 3) reduction = 3;
          }

          currentValue = min(board, newDepth - reduction, currentAlpha, currentAlpha + 0.1,
                  ply + 1, true);

          if (currentValue > currentAlpha && currentValue < beta) {
            currentValue = min(board, newDepth, currentAlpha, beta, ply + 1, true);
          }
        }
      } finally {
        board.unmakeMove();
      }

      if (currentValue > currentAlpha) {
        currentAlpha = currentValue;
        bestFoundMove = move;

        if (currentAlpha >= beta) {
          if (!move.isAttack()) {
            if (!move.equals(killers[0][ply])) {
              killers[1][ply] = killers[0][ply];
              killers[0][ply] = move;
            }
            historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
          }

          recordCounterMove(board, move);

          storeIfSearching(zobristHash, scoreToTable(beta, ply), depth,
                  TranspositionTable.LOWERBOUND, bestFoundMove);
          return beta;
        }
      }

      firstMove = false;
      movesSearched++;
    }

    // No legal move was searched. The static exchange skip in the loop cannot fire until three
    // legal moves have been counted, so this can only mean the position has no legal move.
    if (movesSearched == 0) {
      return terminalScore(board, ply);
    }

    byte nodeType = TranspositionTable.EXACT;
    if (currentAlpha <= alpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (currentAlpha >= beta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    storeIfSearching(zobristHash, scoreToTable(currentAlpha, ply), depth, nodeType, bestFoundMove);

    return currentAlpha;
  }

  /**
   * Implements the minimizing player portion of the alpha-beta search algorithm
   * with various pruning techniques and search extensions.
   *
   * @param board The current board position.
   * @param depth The remaining search depth.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @param nullMoveAllowed Whether a null move may be played at this node.
   * @return The best evaluation score for the minimizing player.
   */
  private double min(final Board board, int depth, double alpha, double beta, int ply,
                     boolean nullMoveAllowed) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;
    if (limitReached(stats)) {
      this.searchStopped = true;
    }

    if (ply > 0 && isDrawnByRule(board)) {
      return 0;
    } if (searchStopped) {
      return depth <= 0 ? quiescenceSearch(board, alpha, beta, ply, false) :
              getCachedEvaluation(board, depth);
    } if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, false);
    } if (ply >= MAX_PLY) {
      return getCachedEvaluation(board, depth);
    }

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null && entry.depth >= depth) {
      final double entryScore = scoreFromTable(entry.score, ply);
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entryScore;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entryScore);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entryScore);
      }
      if (alpha >= beta) {
        return entryScore;
      }
    }

    final boolean inCheckAtNode = board.currentPlayer().isInCheck();

    if (depth == 1 && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval - RAZOR_MARGIN > beta) {
        return quiescenceSearch(board, alpha, beta, ply, false);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board, depth);
      if (eval <= alpha - (depth * 100)) {
        return eval;
      }
    }

    Move ttMove = entry != null ? entry.move : null;
    if (ttMove == null && depth >= 4) {
      min(board, depth - 2, alpha, beta, ply, nullMoveAllowed);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMove = entry.move;
      }
    }

    if (depth >= NULL_MOVE_DEPTH && nullMoveAllowed && !inCheckAtNode
            && alpha > -Double.MAX_VALUE && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 2 + depth / 6;
      double nullMoveScore;
      board.makeNullMove();
      try {
        nullMoveScore = max(board, depth - 1 - R, alpha, alpha + ZERO_WINDOW, ply + 1, false);
      } finally {
        board.unmakeNullMove();
      }
      if (nullMoveScore <= alpha
              && (depth < NULL_MOVE_VERIFICATION_DEPTH
                      || min(board, depth - R, alpha, beta, ply, false) <= alpha)) {
        return alpha;
      }
    }

    double currentBeta = beta;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;

    Collection<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);

    if (ttMove != null) {
      List<Move> reorderedMoves = new ArrayList<>();
      for (Move move : sortedMoves) {
        if (move.equals(ttMove)) {
          reorderedMoves.add(0, move);
        } else {
          reorderedMoves.add(move);
        }
      }
      sortedMoves = reorderedMoves;
    }

    for (final Move move : sortedMoves) {
      if (move.isAttack() && depth < 3 && movesSearched > 2) {
        int seeScore = seeEvaluator.evaluate(board, move);
        if (seeScore < SEE_PRUNING_THRESHOLD) {
          continue;
        }
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        if (board.currentPlayer().isInCheck()) {
          newDepth++;
        }

        if (firstMove) {
          currentValue = max(board, newDepth, alpha, currentBeta, ply + 1, true);
        } else {
          int reduction = 0;
          if (depth >= 3 && movesSearched >= 4 && !move.isAttack() && !inCheckAtNode) {
            reduction = 1 + (movesSearched / 6);
            if (reduction > 3) reduction = 3;
          }

          currentValue = max(board, newDepth - reduction, currentBeta - 0.1, currentBeta,
                  ply + 1, true);

          if (currentValue < currentBeta && currentValue > alpha) {
            currentValue = max(board, newDepth, alpha, currentBeta, ply + 1, true);
          }
        }
      } finally {
        board.unmakeMove();
      }
      if (currentValue < currentBeta) {
        currentBeta = currentValue;
        bestFoundMove = move;

        if (currentBeta <= alpha) {
          if (!move.isAttack()) {
            if (!move.equals(killers[0][ply])) {
              killers[1][ply] = killers[0][ply];
              killers[0][ply] = move;
            }
            historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
          }

          recordCounterMove(board, move);

          storeIfSearching(zobristHash, scoreToTable(alpha, ply), depth,
                  TranspositionTable.UPPERBOUND, bestFoundMove);
          return alpha;
        }
      }

      firstMove = false;
      movesSearched++;
    }

    // No legal move was searched. The static exchange skip in the loop cannot fire until three
    // legal moves have been counted, so this can only mean the position has no legal move.
    if (movesSearched == 0) {
      return terminalScore(board, ply);
    }

    byte nodeType = TranspositionTable.EXACT;
    if (currentBeta <= alpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (currentBeta >= beta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    storeIfSearching(zobristHash, scoreToTable(currentBeta, ply), depth, nodeType, bestFoundMove);

    return currentBeta;
  }

  /**
   * Implements quiescence search to handle tactical sequences involving captures
   * and checks to avoid the horizon effect in evaluation.
   * <p>
   * A checkmate is scored as a mate by the evasion search this node delegates to when the side to
   * move is in check. A stalemate is not detected here and scores as the static evaluation.
   *
   * @param board The current board position.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @param maximizing True if this is a maximizing node, false for minimizing.
   * @return The quiescence search evaluation score.
   */
  private double quiescenceSearch(Board board, double alpha, double beta, int ply, boolean maximizing) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;
    if (limitReached(stats)) {
      this.searchStopped = true;
    }

    if (searchStopped) {
      return getCachedEvaluation(board, 0);
    }

    if (stats.quiescenceCount >= MAX_QUIESCENCE || ply >= MAX_PLY) {
      return getCachedEvaluation(board, 0);
    }
    stats.quiescenceCount++;

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null) {
      final double entryScore = scoreFromTable(entry.score, ply);
      if (entry.nodeType == TranspositionTable.EXACT) {
        return entryScore;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entryScore);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entryScore);
      }
      if (alpha >= beta) {
        return entryScore;
      }
    }

    // A side that is in check cannot decline to move, so the static score is not a bound on what
    // this node can reach and must not narrow the window or cut the node off. Evasions are searched
    // at the ply of this node rather than the next one, because no move was made on the way in.
    if (board.currentPlayer().isInCheck()) {
      return maximizing ?
              max(board, 1, alpha, beta, ply, true) :
              min(board, 1, alpha, beta, ply, true);
    }

    final double originalAlpha = alpha;
    final double originalBeta = beta;

    double standPat = getCachedEvaluation(board, 0);

    if (maximizing) {
      if (standPat >= beta) {
        transpositionTable.store(zobristHash, scoreToTable(beta, ply), 0,
                TranspositionTable.LOWERBOUND, null);
        return beta;
      }
      if (standPat > alpha) alpha = standPat;
    } else {
      if (standPat <= alpha) {
        transpositionTable.store(zobristHash, scoreToTable(alpha, ply), 0,
                TranspositionTable.UPPERBOUND, null);
        return alpha;
      }
      if (standPat < beta) beta = standPat;
    }

    if (maximizing && standPat < alpha - DELTA_MATERIAL) return alpha;
    if (!maximizing && standPat > beta + DELTA_MATERIAL) return beta;

    final Collection<Move> legalMoves = board.currentPlayer().getLegalMoves();

    int captureCount = 0;
    for (Move move : legalMoves) {
      if (move.isAttack()) {
        captureCount++;
      }
    }

    // Both ordering keys are resolved once per capture here. Each of these evaluator calls walks
    // every piece on the board, so reading them from inside a comparator costs O(n log n) board
    // scans per node, and the loop below would then scan a third time for every key it uses.
    final Move[] captures = new Move[captureCount];
    final int[] captureSeeScores = new int[captureCount];
    final boolean[] captureUndefended = new boolean[captureCount];

    int captureIndex = 0;
    for (Move move : legalMoves) {
      if (!move.isAttack()) {
        continue;
      }
      final Piece attackedPiece = move.getAttackedPiece();
      captures[captureIndex] = move;
      captureSeeScores[captureIndex] = seeEvaluator.evaluate(board, move);
      captureUndefended[captureIndex] = attackedPiece != null &&
              !seeEvaluator.isPieceDefended(attackedPiece, board);
      captureIndex++;
    }

    // Bit 62 holds the defended flag so undefended captures sort first, bits 30 through 61 hold
    // the static exchange score negated against Integer.MAX_VALUE so higher scores sort first, and
    // bits 0 through 29 hold the source index so equal keys keep move generation order. Every key
    // is non-negative, so sorting the packed values ascending yields the intended move order.
    final long[] orderKeys = new long[captureCount];
    for (int i = 0; i < captureCount; i++) {
      final long defendedBit = captureUndefended[i] ? 0L : 1L;
      final long descendingSee = (long) Integer.MAX_VALUE - (long) captureSeeScores[i];
      orderKeys[i] = (defendedBit << 62) | (descendingSee << 30) | i;
    }
    Arrays.sort(orderKeys);

    for (final long orderKey : orderKeys) {
      final int index = (int) (orderKey & INDEX_MASK);
      final Move move = captures[index];
      final int seeScore = captureSeeScores[index];
      final boolean isUndefendedCapture = captureUndefended[index];

      board.makeMove(move);

      final boolean legal = !board.currentPlayer().getOpponent().isInCheck();
      final boolean givesCheck = legal && board.currentPlayer().isInCheck();
      final boolean prunedByExchange = seeScore < SEE_PRUNING_THRESHOLD &&
              !isUndefendedCapture && !givesCheck;

      if (!legal || prunedByExchange) {
        board.unmakeMove();
        continue;
      }

      double score;
      try {
        score = quiescenceSearch(board, alpha, beta, ply + 1, !maximizing);
      } finally {
        board.unmakeMove();
      }

      if (maximizing) {
        if (score > alpha) alpha = score;
      } else {
        if (score < beta) beta = score;
      }

      if (alpha >= beta) {
        storeIfSearching(zobristHash, scoreToTable(maximizing ? beta : alpha, ply), 0,
                maximizing ? TranspositionTable.LOWERBOUND : TranspositionTable.UPPERBOUND, null);
        return maximizing ? beta : alpha;
      }
    }

    double finalScore = maximizing ? alpha : beta;
    byte nodeType = TranspositionTable.EXACT;
    if (finalScore <= originalAlpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (finalScore >= originalBeta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    storeIfSearching(zobristHash, scoreToTable(finalScore, ply), 0, nodeType, null);
    return finalScore;
  }

  /**
   * Checks whether a player has non-pawn material remaining on the board.
   * Used to determine if null move pruning is safe to apply.
   *
   * @param player The player to check for non-pawn material.
   * @return True if the player has pieces other than pawns and king, false otherwise.
   */
  private boolean hasNonPawnMaterial(Player player) {
    for (Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() != Piece.PieceType.PAWN &&
              piece.getPieceType() != Piece.PieceType.KING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Updates the history heuristic table with information about a good move
   * to improve move ordering in future searches.
   *
   * @param move The move to record in the history heuristic.
   * @param depth The depth at which this move was found to be good.
   */
  private void updateHistoryHeuristic(Move move, int depth) {
    if (move != null && move != MoveFactory.getNullMove()) {
      historyHeuristic[move.getCurrentCoordinate()][move.getDestinationCoordinate()] += depth * depth;
    }
  }

  /**
   * Determines the appropriate board evaluator based on the current game state.
   * Uses game phase detection to select between opening, middlegame, and endgame evaluators.
   *
   * @param board The current board position.
   * @return The appropriate board evaluator for the game state.
   */
  @VisibleForTesting
  private BoardEvaluator determineGameState(final Board board) {
    return GameStateDetector.get().determineEvaluator(board);
  }

  /**
   * The SearchStats class tracks performance statistics for individual search threads
   * during parallel search operations.
   */
  private static class SearchStats {
    /** The number of board positions evaluated by this thread. */
    long boardsEvaluated;
    /** The number of quiescence search nodes explored by this thread. */
    int quiescenceCount;
    /** The node count at which this thread stops the search. */
    long nodeLimit = UNLIMITED_NODES;
    /** The reading of {@link System#nanoTime()} at which this thread stops the search. */
    long deadline = NO_DEADLINE;
  }

  /**
   * The StripedTranspositionTable class implements a thread-safe transposition table
   * using striped locking to reduce contention in parallel search operations.
   */
  private static class StripedTranspositionTable {
    /** The hash table storing transposition table entries. */
    private final TranspositionTable.Entry[] table;
    /** The bit mask for indexing into the hash table. */
    private final int mask;
    /** The current age counter for entry replacement decisions. */
    private volatile byte currentAge;
    /** Array of read-write locks for striped locking. */
    private final ReadWriteLock[] locks;
    /** The number of locks used for striped locking. */
    private static final int LOCK_COUNT = 1024;

    /**
     * Constructs a new striped transposition table with the specified size.
     *
     * @param sizeMB The size of the transposition table in megabytes.
     */
    public StripedTranspositionTable(int sizeMB) {
      long bytes = (long) sizeMB * 1024 * 1024;
      int entryCount = (int) (bytes / 24);
      int size = Integer.highestOneBit(entryCount);

      table = new TranspositionTable.Entry[size];
      mask = size - 1;
      currentAge = 0;

      locks = new ReentrantReadWriteLock[LOCK_COUNT];
      for (int i = 0; i < LOCK_COUNT; i++) {
        locks[i] = new ReentrantReadWriteLock();
      }

      for (int i = 0; i < size; i++) {
        table[i] = new TranspositionTable.Entry();
      }

      System.out.println("Transposition Table created with " + size +
              " entries (" + (size * 24 / (1024 * 1024)) + " MB)");
    }

    /**
     * Gets the appropriate lock for the given hash value using striped locking.
     *
     * @param hash The hash value to determine the lock.
     * @return The read-write lock for the hash value.
     */
    private ReadWriteLock getLock(long hash) {
      return locks[(int) ((hash & mask) >>> 1) & (LOCK_COUNT - 1)];
    }

    /**
     * Increments the age counter for entry replacement decisions.
     */
    public synchronized void incrementAge() {
      currentAge++;
      if (currentAge == 0) {
        currentAge = 1;
      }
    }

    /**
     * Retrieves a transposition table entry for the given board hash.
     *
     * @param zobristHash The Zobrist hash of the board position.
     * @return The transposition table entry if found, null otherwise.
     */
    public TranspositionTable.Entry get(long zobristHash) {
      int index = (int) (zobristHash & mask) & ~1;

      ReadWriteLock lock = getLock(zobristHash);
      lock.readLock().lock();
      try {
        TranspositionTable.Entry entry = table[index];
        if (entry.key == zobristHash && entry.key != 0) {
          return copyOf(entry);
        }

        TranspositionTable.Entry entry2 = table[index + 1];
        if (entry2.key == zobristHash && entry2.key != 0) {
          return copyOf(entry2);
        }

        return null;
      } finally {
        lock.readLock().unlock();
      }
    }

    /**
     * Returns a copy of the given entry. Probing threads receive copies, because storing threads
     * reuse the table's entries in place.
     *
     * @param entry The entry to copy.
     * @return A new entry holding the same values.
     */
    private static TranspositionTable.Entry copyOf(final TranspositionTable.Entry entry) {
      TranspositionTable.Entry copy = new TranspositionTable.Entry();
      copy.key = entry.key;
      copy.score = entry.score;
      copy.depth = entry.depth;
      copy.nodeType = entry.nodeType;
      copy.age = entry.age;
      copy.move = entry.move;
      return copy;
    }

    /**
     * Stores a transposition table entry for the given board position.
     *
     * @param zobristHash The Zobrist hash of the board position.
     * @param score The evaluation score for the position.
     * @param depth The search depth for the evaluation.
     * @param nodeType The type of node (exact, lower bound, upper bound).
     */
    public void store(long zobristHash, double score, int depth, byte nodeType, Move bestMove) {
      int index = (int) (zobristHash & mask) & ~1;

      ReadWriteLock lock = getLock(zobristHash);
      lock.writeLock().lock();
      try {
        TranspositionTable.Entry entry = table[index];
        TranspositionTable.Entry entry2 = table[index + 1];

        boolean useFirst = shouldReplace(entry, entry2, depth, nodeType);
        TranspositionTable.Entry target = useFirst ? entry : entry2;

        target.key = zobristHash;
        target.score = score;
        target.depth = (short) depth;
        target.nodeType = nodeType;
        target.age = currentAge;
        target.move = bestMove;
      } finally {
        lock.writeLock().unlock();
      }
    }

    /**
     * Determines which transposition table entry should be replaced based on
     * depth, node type, and age criteria.
     *
     * @param entry1 The first entry candidate for replacement.
     * @param entry2 The second entry candidate for replacement.
     * @param depth The depth of the new entry.
     * @param nodeType The node type of the new entry.
     * @return True if the first entry should be replaced, false for the second.
     */
    private boolean shouldReplace(TranspositionTable.Entry entry1,
                                  TranspositionTable.Entry entry2,
                                  int depth, byte nodeType) {
      if (entry1.key == 0) return true;
      if (entry2.key == 0) return false;

      if (entry1.depth < entry2.depth) return true;
      if (entry1.depth > entry2.depth) return false;

      boolean isExact1 = entry1.nodeType == TranspositionTable.EXACT;
      boolean isExact2 = entry2.nodeType == TranspositionTable.EXACT;
      boolean newIsExact = nodeType == TranspositionTable.EXACT;

      if (!isExact1 && isExact2) return false;
      if (isExact1 && !isExact2) return true;
      if (!isExact1 && newIsExact) return true;

      return entry1.age <= entry2.age;
    }
  }
}