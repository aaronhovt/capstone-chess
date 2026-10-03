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
  private final LocklessTranspositionTable transpositionTable;

  /** Cache of board evaluations belonging to this engine, cleared at the start of each search. */
  private final EvaluationCache evaluationCache = new EvaluationCache();

  /**
   * History heuristic table for move ordering, indexed by the side that plays the move, its
   * origin square, and its destination square. The two sides are kept apart because a pair of
   * squares that both sides can traverse would otherwise mix their cutoff evidence into one score.
   */
  private final int[][][] historyHeuristic = new int[2][64][64];

  /**
   * The bound on the magnitude of a history heuristic score. An entry approaches this value, and
   * its negation, without passing either, because each update moves the entry by an amount scaled
   * down by how little headroom it has left in the direction it is moving. The bound sits within
   * reach of what a single search accumulates, which is what makes that scaling take effect. It
   * also keeps the score a packed ordering key subtracts from Integer.MAX_VALUE inside the 32 bits
   * that key reserves for it.
   */
  private static final int HISTORY_MAX = 1 << 10;

  /**
   * The number of quiet moves searched at one node that are held for penalizing. A node that cuts
   * off after searching more quiet moves than this leaves the ones past the limit unpenalized.
   */
  private static final int MAX_TRACKED_QUIETS = 64;

  /**
   * The quiet moves searched so far at each ply, held so that a cutoff can penalize the quiet
   * moves that were searched before it. A ply's entries are meaningful only up to the count kept
   * by the node searching at that ply.
   */
  private final ThreadLocal<Move[][]> searchedQuiets = ThreadLocal.withInitial(() ->
          new Move[MAX_SEARCH_DEPTH][MAX_TRACKED_QUIETS]);

  /**
   * The static exchange scores of the moves in the list the standard sorter last returned at each
   * ply, at the same index as the move, with zero for a move that is not a capture. A ply's
   * entries are meaningful only while the node that sorted them is searching its moves.
   */
  private final ThreadLocal<int[][]> sortedExchangeScores = ThreadLocal.withInitial(() ->
          new int[MAX_SEARCH_DEPTH][(int) ORDER_INDEX_MASK + 1]);

  /** Killer moves table storing good non-capture moves for each search ply. */
  private final ThreadLocal<Move[][]> killerMoves = ThreadLocal.withInitial(() ->
          new Move[2][MAX_SEARCH_DEPTH]);

  /** Countermove table for storing responses to opponent moves for improved move ordering. */
  @SuppressWarnings("unchecked")
  private final AtomicReference<Move>[][] counterMoves = new AtomicReference[64][64];

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

  /** The futility pruning margin per ply of remaining depth. */
  private static final int FUTILITY_MARGIN = 65;

  /** The greatest remaining depth at which quiet moves late in the move order are pruned. */
  private static final int LATE_MOVE_PRUNING_DEPTH = 3;

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

  /**
   * The margin added to a capture's static exchange score when testing whether the capture can
   * raise the score of a quiescence node past its bound.
   */
  private static final double DELTA_PRUNING_VALUE = 5;

  /** The evaluation margin for razoring pruning technique. */
  private static final double RAZOR_MARGIN = 150;

  /** The starting half-width of the aspiration window at the root. */
  private static final double ASPIRATION_WINDOW = 40;

  /** The static exchange evaluation threshold for pruning bad captures. */
  private static final int SEE_PRUNING_THRESHOLD = -20;

  /** The low bits of a packed quiescence ordering key that hold the source index of a capture. */
  private static final long INDEX_MASK = (1L << 30) - 1;

  /** The number of low bits of a packed standard ordering key that hold a move's source index. */
  private static final int ORDER_INDEX_BITS = 10;

  /** The mask that recovers a move's source index from a packed standard ordering key. */
  private static final long ORDER_INDEX_MASK = (1L << ORDER_INDEX_BITS) - 1;

  /**
   * The mask that recovers the negated score from a packed standard ordering key shifted right by
   * ORDER_INDEX_BITS.
   */
  private static final long ORDER_SCORE_MASK = (1L << 32) - 1;

  /** The bit position at which a packed standard ordering key holds its tier. */
  private static final int ORDER_TIER_SHIFT = ORDER_INDEX_BITS + 32;

  /** The bit position within a packed standard ordering key's tier that holds the capture rank. */
  private static final int ORDER_RANK_SHIFT = 2;

  /** The tier bit a packed standard ordering key sets when its quiet move is not a killer move. */
  private static final long KILLER_TIER_BIT = 2;

  /** The tier bit a packed standard ordering key sets when its quiet move is not a countermove. */
  private static final long COUNTER_TIER_BIT = 1;

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
     * countermoves, and static exchange evaluation for move ordering. Sorting also writes the
     * static exchange score of each capture in the returned list to the same index of the ply's
     * row of sortedExchangeScores, and zero for each other move.
     */
    STANDARD {
      @Override
      List<Move> sort(final Collection<Move> moves, final Board board,
                      final AlphaBeta engine, final int ply) {
        final Move[] ordered = moves.toArray(new Move[0]);
        final int count = ordered.length;
        final Move[][] killers = engine.killerMoves.get();
        final Move counter = counterMoveOf(board, engine);
        final int historySide = historySideOf(board);

        // Bits 42 through 45 hold the tier. Its upper bits hold the capture rank, so that a good
        // capture outranks a quiet move and a quiet move outranks a bad capture. Its two lowest
        // bits are set on a quiet move that is not a killer move and on one that is not the
        // countermove, so that among quiet moves a killer move sorts first and a countermove
        // next. Bits 10 through 41 hold the static exchange score of a capture or the history score of a
        // quiet move, negated against Integer.MAX_VALUE so that higher scores sort first. A
        // history score never leaves the range negative HISTORY_MAX through HISTORY_MAX, so that
        // difference stays within those bits instead of carrying into the tier above them. Bits 0
        // through 9 hold the source index, so equal keys keep move generation order and a list of
        // more than ORDER_INDEX_MASK moves cannot be packed. Every key is non-negative, so sorting
        // the packed values ascending yields the intended move order.
        final long[] orderKeys = new long[count];
        for (int i = 0; i < count; i++) {
          final Move move = ordered[i];
          final boolean capture = move.isAttack();

          final int exchangeScore = capture ? engine.seeEvaluator.evaluate(board, move) : 0;

          final long rank = capture ? (exchangeScore >= 0 ? GOOD_CAPTURE_RANK : BAD_CAPTURE_RANK) :
                  QUIET_RANK;
          long tier = rank << ORDER_RANK_SHIFT;
          if (!capture) {
            tier += (move.equals(killers[0][ply]) || move.equals(killers[1][ply]) ? 0 : KILLER_TIER_BIT) +
                    (counter != null && move.equals(counter) ? 0 : COUNTER_TIER_BIT);
          }
          final long secondary = (long) Integer.MAX_VALUE -
                  (capture ? (long) exchangeScore : (long) historyOf(move, engine, historySide));

          orderKeys[i] = (tier << ORDER_TIER_SHIFT) | (secondary << ORDER_INDEX_BITS) | i;
        }
        Arrays.sort(orderKeys);

        final int[] exchangeScores = engine.sortedExchangeScores.get()[ply];
        final List<Move> sortedMoves = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          final long orderKey = orderKeys[i];
          final Move move = ordered[(int) (orderKey & ORDER_INDEX_MASK)];
          sortedMoves.add(move);
          exchangeScores[i] = move.isAttack() ? (int) ((long) Integer.MAX_VALUE -
                  ((orderKey >>> ORDER_INDEX_BITS) & ORDER_SCORE_MASK)) : 0;
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
      List<Move> sort(final Collection<Move> moves, final Board board,
                      final AlphaBeta engine, final int ply) {
        List<Move> sortedMoves = new ArrayList<>(moves);

        final int historySide = historySideOf(board);
        Map<Move, Integer> seeScores = new HashMap<>();
        Map<Move, Integer> historyScores = new HashMap<>();
        for (Move move : sortedMoves) {
          if (move.isAttack()) {
            seeScores.put(move, engine.seeEvaluator.evaluate(board, move));
          }
          historyScores.put(move, isValidPosition(move) ?
                  engine.historyHeuristic[historySide][move.getCurrentCoordinate()]
                          [move.getDestinationCoordinate()] : 0);
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
     * @return A sorted list of moves.
     */
    abstract List<Move> sort(Collection<Move> moves, final Board board,
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
     * @param historySide The index of the side that plays the move within the history table.
     * @return The recorded history score, or zero if the move has no pair of squares in range.
     */
    private static int historyOf(final Move move, final AlphaBeta engine, final int historySide) {
      if (move == null) {
        return 0;
      }
      final int current = move.getCurrentCoordinate();
      final int destination = move.getDestinationCoordinate();
      if (current < 0 || current >= 64 || destination < 0 || destination >= 64) {
        return 0;
      }
      return engine.historyHeuristic[historySide][current][destination];
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
    this.transpositionTable = new LocklessTranspositionTable(tableSizeMB);

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
   * A search that reaches the node limit or the time limit is stopped where it stands. If the
   * iteration it was in the middle of finished searching a root move whose score landed inside
   * that iteration's window, the move and score returned are that iteration's best so far;
   * otherwise they are those of the deepest iteration that finished. The first iteration is held
   * to neither limit, so a finished iteration always exists.
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

    final BoardEvaluator previousEvaluator = this.evaluator;
    this.evaluator = determineGameState(board);
    if (this.evaluator != previousEvaluator) {
      this.evaluationCache.clear();
    }
    this.evaluationCache.resetStatistics();
    halveHistoryHeuristic();

    final Board mainBoard = board.copy();
    final long rootHash = mainBoard.getZobristHash();
    final List<Future<?>> helpers = startHelperSearches(board, searchDepth);

    final SearchStats stats = new SearchStats();
    this.threadStats.set(stats);

    try {
      for (int currentDepth = 1; currentDepth <= searchDepth && !searchStopped; currentDepth++) {
        stats.nodeLimit = currentDepth == 1 ? UNLIMITED_NODES : nodeLimit;
        stats.deadline = currentDepth == 1 ? NO_DEADLINE : deadline;

        final RootResult result = currentDepth >= 4 ?
                searchRootAspirationWindow(mainBoard, currentDepth, bestMove, bestScore) :
                searchRoot(mainBoard, currentDepth, -Double.MAX_VALUE, Double.MAX_VALUE, bestMove);

        if (result.move() == MoveFactory.getNullMove()) {
          break;
        }

        bestMove = result.move();
        bestScore = result.score();

        if (searchStopped) {
          break;
        }

        bestDepth = currentDepth;

        recordHistory(mainBoard, bestMove, currentDepth);

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
   * @return The evaluation score for the board position.
   */
  private double getCachedEvaluation(Board board) {
    final long zobristHash = board.getZobristHash();
    final double cachedScore = this.evaluationCache.probe(zobristHash);
    if (!Double.isNaN(cachedScore)) {
      return cachedScore;
    }

    double score = this.evaluator.evaluate(board);
    this.evaluationCache.store(zobristHash, score);
    return score;
  }

  /**
   * Searches the root with a narrow window centered on the score of the previous iteration. A
   * search whose score reaches or passes a bound is discarded and repeated with that bound moved
   * to infinity. If the repeated search fails on the other bound, the root is searched again with
   * a full window. A checkmate score from the previous iteration is never narrowed.
   *
   * @param board The board this search thread owns, in the root position.
   * @param depth The current search depth.
   * @param previousBestMove The best move from the previous iteration.
   * @param previousScore The root score from the previous iteration.
   * @return The best move found and its score. If the search was stopped, this is the best move of
   *         the attempt that was stopped, which is the null move if no root move searched in that
   *         attempt scored inside its window.
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
        return result;
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
   * The move that was best in the previous iteration is searched first. The first legal move is
   * searched against the full window, and each later move against a zero window at the best score
   * found so far, then again against the window from that score to the far bound if it scores
   * strictly inside it. A root move that leaves the mover in check is skipped. A search that is stopped part way returns the best of the moves whose search
   * finished, and the null move if none of them scored inside the window. The move being searched
   * when the stop came is not considered.
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
    boolean firstMove = true;

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
        if (firstMove) {
          score = rootIsWhite ?
                  min(board, depth - 1, bestScore, beta, 1, true) :
                  max(board, depth - 1, alpha, bestScore, 1, true);
        } else if (rootIsWhite) {
          score = min(board, depth - 1, bestScore, bestScore + ZERO_WINDOW, 1, true);
          if (score > bestScore && score < beta) {
            score = min(board, depth - 1, bestScore, beta, 1, true);
          }
        } else {
          score = max(board, depth - 1, bestScore - ZERO_WINDOW, bestScore, 1, true);
          if (score < bestScore && score > alpha) {
            score = max(board, depth - 1, alpha, bestScore, 1, true);
          }
        }
      } finally {
        board.unmakeMove();
      }
      firstMove = false;

      if (searchStopped) {
        break;
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
   * Records a quiet move as a countermove response to the last opponent move for move ordering.
   * A capture is not recorded.
   *
   * @param board The current board position.
   * @param move The move to record as a countermove.
   */
  private void recordCounterMove(Board board, Move move) {
    if (move.isAttack()) {
      return;
    }
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
   * Moves the move at the given index of a sorted move list to the front, shifting the moves
   * ahead of it back one place, and moves the entries of the matching exchange scores the same
   * way. Nothing changes when the index is not positive.
   *
   * @param moves The sorted moves.
   * @param exchangeScores The exchange scores at the same indices as the moves.
   * @param index The index of the move to bring to the front.
   */
  private static void moveToFront(final List<Move> moves, final int[] exchangeScores, final int index) {
    if (index <= 0) {
      return;
    }
    Collections.rotate(moves.subList(0, index + 1), 1);
    final int exchangeScore = exchangeScores[index];
    System.arraycopy(exchangeScores, 0, exchangeScores, 1, index);
    exchangeScores[0] = exchangeScore;
  }

  /**
   * Returns the index within the given list of the move whose code, as given by
   * {@link TranspositionTable#moveCode}, is the given code.
   *
   * @param moves The moves to search, none of them the null move.
   * @param moveCode The code of the move to find.
   * @return The index of that move, or -1 if the list holds no move with that code.
   */
  private static int indexOfMoveCode(final List<Move> moves, final short moveCode) {
    for (int i = 0; i < moves.size(); i++) {
      if (TranspositionTable.moveCode(moves.get(i)) == moveCode) {
        return i;
      }
    }
    return -1;
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
   * Returns whether a legal move, already made on the board, may be skipped without a search
   * because it is a quiet move late in the move order at a shallow node. A capture, a promotion,
   * a checking move, and any move at a node in check are never prunable. The caller applies its
   * own mate guard on the window.
   *
   * @param move The move under consideration.
   * @param depth The remaining search depth at the node.
   * @param movesSearched The number of legal moves already searched at the node.
   * @param inCheckAtNode Whether the side to move at the node is in check.
   * @param givesCheck Whether the move gives check.
   * @return true if the move may be skipped.
   */
  private static boolean isLateMovePrunable(final Move move, final int depth,
                                            final int movesSearched, final boolean inCheckAtNode,
                                            final boolean givesCheck) {
    return depth <= LATE_MOVE_PRUNING_DEPTH
            && movesSearched >= 3 + depth * depth
            && !inCheckAtNode
            && !givesCheck
            && !move.isAttack()
            && !(move instanceof Move.PawnPromotion);
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
              getCachedEvaluation(board);
    } if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, true);
    } if (ply >= MAX_PLY) {
      return getCachedEvaluation(board);
    }

    // Read before the transposition probe narrows the window. A zero window built by adding
    // ZERO_WINDOW to a score can come out slightly wider than ZERO_WINDOW.
    final boolean openWindow = beta - alpha > 2 * ZERO_WINDOW;

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

    // No static evaluation reaches a mate score, so razoring against a mate bound would send every
    // such node to quiescence, which cannot find a shorter mate that ends in a quiet move.
    if (depth == 1 && !inCheckAtNode && alpha < MATE_THRESHOLD) {
      double eval = getCachedEvaluation(board);
      if (eval + RAZOR_MARGIN < alpha) {
        return quiescenceSearch(board, alpha, beta, ply, true);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board);
      if (eval >= beta + depth * FUTILITY_MARGIN) {
        return eval;
      }
    }

    if (depth >= NULL_MOVE_DEPTH && nullMoveAllowed && !openWindow && !inCheckAtNode
            && beta < MATE_THRESHOLD && getCachedEvaluation(board) >= beta
            && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 3 + depth / 4;
      double nullMoveScore;
      board.makeNullMove();
      try {
        nullMoveScore = min(board, depth - 1 - R, beta - ZERO_WINDOW, beta, ply + 1, false);
      } finally {
        board.unmakeNullMove();
      }
      if (nullMoveScore >= beta
              && (depth < NULL_MOVE_VERIFICATION_DEPTH
                      || max(board, depth - R, beta - ZERO_WINDOW, beta, ply, false) >= beta)) {
        return beta;
      }
    }

    short ttMoveCode = entry != null ? entry.moveCode : TranspositionTable.NO_MOVE;
    if (ttMoveCode == TranspositionTable.NO_MOVE && depth >= 4 && openWindow) {
      max(board, depth - 2, alpha, beta, ply, nullMoveAllowed);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMoveCode = entry.moveCode;
      }
    }

    double currentAlpha = alpha;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;
    final Move[] quietsSearched = searchedQuiets.get()[ply];
    int quietCount = 0;

    final List<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);
    final int[] exchangeScores = sortedExchangeScores.get()[ply];

    if (ttMoveCode != TranspositionTable.NO_MOVE) {
      moveToFront(sortedMoves, exchangeScores, indexOfMoveCode(sortedMoves, ttMoveCode));
    }

    for (int moveIndex = 0; moveIndex < sortedMoves.size(); moveIndex++) {
      final Move move = sortedMoves.get(moveIndex);
      if (move.isAttack() && depth < 3 && movesSearched > 2 &&
              exchangeScores[moveIndex] < SEE_PRUNING_THRESHOLD) {
        continue;
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      final boolean givesCheck = board.currentPlayer().isInCheck();
      if (isLateMovePrunable(move, depth, movesSearched, inCheckAtNode, givesCheck)
              && alpha > -MATE_THRESHOLD) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        // A check that evades a check is not extended, so no two consecutive plies are both
        // extended and a chain of checks by both sides loses depth.
        if (givesCheck && !inCheckAtNode) {
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

          // A reduced search that raises alpha is not evidence at this node's depth, so the move
          // is searched again against the same window at its unreduced depth before its score is
          // allowed to raise alpha or cut this node off. Without this the node would store a
          // bound at its own depth on the strength of a search shallower than that depth.
          if (reduction > 0 && currentValue > currentAlpha) {
            currentValue = min(board, newDepth, currentAlpha, currentAlpha + 0.1, ply + 1, true);
          }

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
            recordHistory(board, move, depth);
            penalizeSearchedQuiets(board, quietsSearched, quietCount, depth);
          }

          recordCounterMove(board, move);

          storeIfSearching(zobristHash, scoreToTable(beta, ply), depth,
                  TranspositionTable.LOWERBOUND, bestFoundMove);
          return beta;
        }
      }

      if (!move.isAttack() && quietCount < MAX_TRACKED_QUIETS) {
        quietsSearched[quietCount++] = move;
      }

      firstMove = false;
      movesSearched++;
    }

    // No legal move was searched. Neither the static exchange skip nor late move pruning in the
    // loop can fire until three legal moves have been counted, so this can only mean the
    // position has no legal move.
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
              getCachedEvaluation(board);
    } if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply, false);
    } if (ply >= MAX_PLY) {
      return getCachedEvaluation(board);
    }

    // Read before the transposition probe narrows the window. A zero window built by adding
    // ZERO_WINDOW to a score can come out slightly wider than ZERO_WINDOW.
    final boolean openWindow = beta - alpha > 2 * ZERO_WINDOW;

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

    // No static evaluation reaches a mate score, so razoring against a mate bound would send every
    // such node to quiescence, which cannot find a shorter mate that ends in a quiet move.
    if (depth == 1 && !inCheckAtNode && beta > -MATE_THRESHOLD) {
      double eval = getCachedEvaluation(board);
      if (eval - RAZOR_MARGIN > beta) {
        return quiescenceSearch(board, alpha, beta, ply, false);
      }
    }

    if (depth < FUTILITY_PRUNING_DEPTH && !inCheckAtNode) {
      double eval = getCachedEvaluation(board);
      if (eval <= alpha - depth * FUTILITY_MARGIN) {
        return eval;
      }
    }

    if (depth >= NULL_MOVE_DEPTH && nullMoveAllowed && !openWindow && !inCheckAtNode
            && alpha > -MATE_THRESHOLD && getCachedEvaluation(board) <= alpha
            && hasNonPawnMaterial(board.currentPlayer())) {
      final int R = 3 + depth / 4;
      double nullMoveScore;
      board.makeNullMove();
      try {
        nullMoveScore = max(board, depth - 1 - R, alpha, alpha + ZERO_WINDOW, ply + 1, false);
      } finally {
        board.unmakeNullMove();
      }
      if (nullMoveScore <= alpha
              && (depth < NULL_MOVE_VERIFICATION_DEPTH
                      || min(board, depth - R, alpha, alpha + ZERO_WINDOW, ply, false) <= alpha)) {
        return alpha;
      }
    }

    short ttMoveCode = entry != null ? entry.moveCode : TranspositionTable.NO_MOVE;
    if (ttMoveCode == TranspositionTable.NO_MOVE && depth >= 4 && openWindow) {
      min(board, depth - 2, alpha, beta, ply, nullMoveAllowed);
      entry = transpositionTable.get(zobristHash);
      if (entry != null) {
        ttMoveCode = entry.moveCode;
      }
    }

    double currentBeta = beta;
    boolean firstMove = true;
    Move bestFoundMove = null;
    Move[][] killers = killerMoves.get();
    int movesSearched = 0;
    final Move[] quietsSearched = searchedQuiets.get()[ply];
    int quietCount = 0;

    final List<Move> sortedMoves = MoveSorter.STANDARD.sort(board.currentPlayer().getLegalMoves(), board, this, ply);
    final int[] exchangeScores = sortedExchangeScores.get()[ply];

    if (ttMoveCode != TranspositionTable.NO_MOVE) {
      moveToFront(sortedMoves, exchangeScores, indexOfMoveCode(sortedMoves, ttMoveCode));
    }

    for (int moveIndex = 0; moveIndex < sortedMoves.size(); moveIndex++) {
      final Move move = sortedMoves.get(moveIndex);
      if (move.isAttack() && depth < 3 && movesSearched > 2 &&
              exchangeScores[moveIndex] < SEE_PRUNING_THRESHOLD) {
        continue;
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove();
        continue;
      }

      final boolean givesCheck = board.currentPlayer().isInCheck();
      if (isLateMovePrunable(move, depth, movesSearched, inCheckAtNode, givesCheck)
              && beta < MATE_THRESHOLD) {
        board.unmakeMove();
        continue;
      }

      double currentValue;
      try {
        int newDepth = depth - 1;
        // A check that evades a check is not extended, so no two consecutive plies are both
        // extended and a chain of checks by both sides loses depth.
        if (givesCheck && !inCheckAtNode) {
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

          // A reduced search that lowers beta is not evidence at this node's depth, so the move
          // is searched again against the same window at its unreduced depth before its score is
          // allowed to lower beta or cut this node off. Without this the node would store a
          // bound at its own depth on the strength of a search shallower than that depth.
          if (reduction > 0 && currentValue < currentBeta) {
            currentValue = max(board, newDepth, currentBeta - 0.1, currentBeta, ply + 1, true);
          }

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
            recordHistory(board, move, depth);
            penalizeSearchedQuiets(board, quietsSearched, quietCount, depth);
          }

          recordCounterMove(board, move);

          storeIfSearching(zobristHash, scoreToTable(alpha, ply), depth,
                  TranspositionTable.UPPERBOUND, bestFoundMove);
          return alpha;
        }
      }

      if (!move.isAttack() && quietCount < MAX_TRACKED_QUIETS) {
        quietsSearched[quietCount++] = move;
      }

      firstMove = false;
      movesSearched++;
    }

    // No legal move was searched. Neither the static exchange skip nor late move pruning in the
    // loop can fire until three legal moves have been counted, so this can only mean the
    // position has no legal move.
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
   * The moves searched are captures and quiet promotions to a queen.
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
      return getCachedEvaluation(board);
    }

    if (ply >= MAX_PLY) {
      return getCachedEvaluation(board);
    }

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

    double standPat = getCachedEvaluation(board);

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

    final Collection<Move> legalMoves = board.currentPlayer().getLegalMoves();

    int captureCount = 0;
    for (Move move : legalMoves) {
      if (isQuiescenceMove(move)) {
        captureCount++;
      }
    }

    // The static exchange score is resolved once per capture here. Each evaluator call walks every
    // piece on the board, so reading it from inside a comparator costs O(n log n) board scans per
    // node, and the loop below would then scan again for every capture it searches.
    final Move[] captures = new Move[captureCount];
    final int[] captureSeeScores = new int[captureCount];

    int captureIndex = 0;
    for (Move move : legalMoves) {
      if (!isQuiescenceMove(move)) {
        continue;
      }
      captures[captureIndex] = move;
      captureSeeScores[captureIndex] = seeEvaluator.evaluate(board, move);
      captureIndex++;
    }

    // Bits 30 through 61 hold the static exchange score negated against Integer.MAX_VALUE so higher
    // scores sort first, and bits 0 through 29 hold the source index so equal keys keep move
    // generation order. Every key is non-negative, so sorting the packed values ascending yields
    // the intended move order.
    final long[] orderKeys = new long[captureCount];
    for (int i = 0; i < captureCount; i++) {
      final long descendingSee = (long) Integer.MAX_VALUE - (long) captureSeeScores[i];
      orderKeys[i] = (descendingSee << 30) | i;
    }
    Arrays.sort(orderKeys);

    for (final long orderKey : orderKeys) {
      final int index = (int) (orderKey & INDEX_MASK);
      final Move move = captures[index];
      final int seeScore = captureSeeScores[index];

      board.makeMove(move);

      final boolean legal = !board.currentPlayer().getOpponent().isInCheck();
      final boolean givesCheck = legal && board.currentPlayer().isInCheck();
      final boolean prunedByExchange = seeScore < SEE_PRUNING_THRESHOLD && !givesCheck;
      // The exchange score does not count a promotion, so a capture that promotes is never
      // judged by it here.
      final boolean prunedByDelta = !givesCheck && !(move instanceof Move.PawnPromotion) &&
              (maximizing ?
                      standPat + seeScore + DELTA_PRUNING_VALUE <= alpha :
                      standPat - seeScore - DELTA_PRUNING_VALUE >= beta);

      if (!legal || prunedByExchange || prunedByDelta) {
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
   * Returns whether quiescence search searches the given move: any capture, or a promotion to a
   * queen that captures nothing.
   *
   * @param move The move to test.
   * @return True if the move is searched in quiescence search.
   */
  private static boolean isQuiescenceMove(final Move move) {
    return move.isAttack() ||
            (move instanceof Move.PawnPromotion &&
                    move.getPromotionPiece().getPieceType() == Piece.PieceType.QUEEN);
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
   * Returns the index within the history heuristic table of the side to move in the given
   * position. A caller recording a move must hand this the position the move is played from, not
   * the position it leads to.
   *
   * @param board The position whose side to move is being indexed.
   * @return Zero if White is to move, one if Black is to move.
   */
  private static int historySideOf(final Board board) {
    return board.currentPlayer().getAlliance().isWhite() ? 0 : 1;
  }

  /**
   * Credits a move that was found to be good in the history heuristic table, so that later
   * searches order it earlier.
   *
   * @param board The position the move is played from, whose side to move plays it.
   * @param move The move to record in the history heuristic.
   * @param depth The depth at which this move was found to be good.
   */
  private void recordHistory(final Board board, final Move move, final int depth) {
    addHistory(board, move, depth * depth);
  }

  /**
   * Penalizes in the history heuristic table each of the given quiet moves, which were searched at
   * a node that a later quiet move cut off, so that later searches order them after the quiet
   * moves that have yet to be refuted.
   *
   * @param board The position the moves are played from, whose side to move plays them.
   * @param quiets The quiet moves searched at the node before the move that cut it off.
   * @param count The number of leading entries of quiets that hold such a move.
   * @param depth The depth at which those moves were searched.
   */
  private void penalizeSearchedQuiets(final Board board, final Move[] quiets, final int count,
                                      final int depth) {
    final int malus = -depth * depth;
    for (int i = 0; i < count; i++) {
      addHistory(board, quiets[i], malus);
    }
  }

  /**
   * Adds the given amount to a move's score in the history heuristic table, less that amount's
   * share of the distance the entry has already travelled toward the bound it is moving toward.
   * An entry near the bound therefore barely moves further, while one at the opposite bound takes
   * the amount close to whole, so a move that has been passed over many times can still be
   * rehabilitated by the cutoffs it does produce. The amount is clamped to HISTORY_MAX first,
   * which is what holds an entry within HISTORY_MAX of zero. Does nothing if the move is null or
   * the null move.
   *
   * @param board The position the move is played from, whose side to move plays it.
   * @param move The move whose history score is changing.
   * @param bonus The amount to add, negative to penalize the move.
   */
  private void addHistory(final Board board, final Move move, final int bonus) {
    if (move == null || move == MoveFactory.getNullMove()) {
      return;
    }
    final int[] destinations = historyHeuristic[historySideOf(board)][move.getCurrentCoordinate()];
    final int destination = move.getDestinationCoordinate();
    final int change = Math.max(-HISTORY_MAX, Math.min(bonus, HISTORY_MAX));
    final int score = destinations[destination];
    destinations[destination] = score + change - score * Math.abs(change) / HISTORY_MAX;
  }

  /**
   * Halves every score in the history heuristic table toward zero, which is how evidence gathered
   * earlier in the game gives way to evidence from the position being searched now. Called at the
   * start of a search, before any helper thread of that search is started.
   */
  private void halveHistoryHeuristic() {
    for (final int[][] origins : historyHeuristic) {
      for (final int[] destinations : origins) {
        for (int destination = 0; destination < destinations.length; destination++) {
          destinations[destination] /= 2;
        }
      }
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
    /** The node count at which this thread stops the search. */
    long nodeLimit = UNLIMITED_NODES;
    /** The reading of {@link System#nanoTime()} at which this thread stops the search. */
    long deadline = NO_DEADLINE;
  }

  /**
   * The LocklessTranspositionTable class implements a transposition table that every search
   * thread probes and stores into without locking. Each slot is three consecutive words of one
   * array: a check word, the bits of the score, and a data word packing the depth, node type, age
   * and move code. The check word is the key combined by exclusive or with the other two words, so
   * a probe accepts a slot only when all three words were written by the same store. A slot left
   * mixed by two threads storing into it at once reads as a miss. Two threads storing the same key
   * at once can leave that key in both slots of its bucket, and a probe then finds the first.
   */
  private static class LocklessTranspositionTable {
    /** The number of words each slot occupies. */
    private static final int WORDS_PER_SLOT = 3;
    /** The offset within a slot of the key combined with the slot's other two words. */
    private static final int CHECK = 0;
    /** The offset within a slot of the raw bits of the score. */
    private static final int SCORE = 1;
    /** The offset within a slot of the packed depth, node type, age and move code. */
    private static final int DATA = 2;

    /** The words of every slot, all zero for an empty slot. */
    private final long[] words;
    /** The bit mask for indexing into the hash table. */
    private final int mask;
    /** The current age counter for entry replacement decisions. */
    private volatile byte currentAge;

    /**
     * Constructs a new transposition table with the specified size.
     *
     * @param sizeMB The size of the transposition table in megabytes.
     */
    public LocklessTranspositionTable(int sizeMB) {
      long bytes = (long) sizeMB * 1024 * 1024;
      int entryCount = (int) (bytes / 24);
      int size = Integer.highestOneBit(entryCount);

      words = new long[size * WORDS_PER_SLOT];
      mask = size - 1;
      currentAge = 0;

      System.out.println("Transposition Table created with " + size +
              " entries (" + (size * 24 / (1024 * 1024)) + " MB)");
    }

    /**
     * Returns the data word holding the given values.
     *
     * @param depth The search depth, of which only the low 16 bits are kept.
     * @param nodeType The node type.
     * @param age The age.
     * @param moveCode The move code.
     * @return The packed data word.
     */
    private static long pack(final int depth, final byte nodeType, final byte age,
                             final short moveCode) {
      return (depth & 0xFFFFL) | (nodeType & 0xFFL) << 16 | (age & 0xFFL) << 24
              | (moveCode & 0xFFFFL) << 32;
    }

    /**
     * Returns the depth held in a data word.
     *
     * @param data The data word.
     * @return The depth.
     */
    private static short depthOf(final long data) {
      return (short) data;
    }

    /**
     * Returns the node type held in a data word.
     *
     * @param data The data word.
     * @return The node type.
     */
    private static byte nodeTypeOf(final long data) {
      return (byte) (data >>> 16);
    }

    /**
     * Returns the age held in a data word.
     *
     * @param data The data word.
     * @return The age.
     */
    private static byte ageOf(final long data) {
      return (byte) (data >>> 24);
    }

    /**
     * Returns the move code held in a data word.
     *
     * @param data The data word.
     * @return The move code.
     */
    private static short moveCodeOf(final long data) {
      return (short) (data >>> 32);
    }

    /**
     * Returns the key held in the given slot, zero for an empty slot. The key of a slot another
     * thread is writing can be any value.
     *
     * @param slot The index of the slot.
     * @return The slot's key.
     */
    private long keyAt(final int slot) {
      final int base = slot * WORDS_PER_SLOT;
      return words[base + CHECK] ^ words[base + SCORE] ^ words[base + DATA];
    }

    /**
     * Returns the data word of the given slot.
     *
     * @param slot The index of the slot.
     * @return The slot's data word.
     */
    private long dataAt(final int slot) {
      return words[slot * WORDS_PER_SLOT + DATA];
    }

    /**
     * Writes the given key, score bits and data word into the given slot.
     *
     * @param slot The index of the slot.
     * @param key The Zobrist hash of the position.
     * @param scoreBits The raw bits of the score.
     * @param data The packed data word.
     */
    private void write(final int slot, final long key, final long scoreBits, final long data) {
      final int base = slot * WORDS_PER_SLOT;
      words[base + CHECK] = key ^ scoreBits ^ data;
      words[base + SCORE] = scoreBits;
      words[base + DATA] = data;
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
     * @return A new entry holding the values of the matching slot, or null if no slot matches.
     */
    public TranspositionTable.Entry get(long zobristHash) {
      int index = (int) (zobristHash & mask) & ~1;

      final TranspositionTable.Entry entry = entryAt(index, zobristHash);
      return entry != null ? entry : entryAt(index + 1, zobristHash);
    }

    /**
     * Returns a new entry holding the values of the given slot if the slot holds the given key.
     *
     * @param slot The index of the slot to read.
     * @param zobristHash The Zobrist hash of the board position.
     * @return A new entry holding the slot's values, or null if the slot does not hold the key
     *         or the key is zero.
     */
    private TranspositionTable.Entry entryAt(final int slot, final long zobristHash) {
      final int base = slot * WORDS_PER_SLOT;
      final long scoreBits = words[base + SCORE];
      final long data = words[base + DATA];
      if ((words[base + CHECK] ^ scoreBits ^ data) != zobristHash || zobristHash == 0) {
        return null;
      }
      TranspositionTable.Entry entry = new TranspositionTable.Entry();
      entry.key = zobristHash;
      entry.score = Double.longBitsToDouble(scoreBits);
      entry.depth = depthOf(data);
      entry.nodeType = nodeTypeOf(data);
      entry.age = ageOf(data);
      entry.moveCode = moveCodeOf(data);
      return entry;
    }

    /**
     * Stores a transposition table entry for the given board position. A store for a position a
     * slot of the bucket already holds updates that slot; a bound searched shallower than the
     * entry already in that slot leaves its score, depth and node type in place.
     *
     * @param zobristHash The Zobrist hash of the board position.
     * @param score The evaluation score for the position.
     * @param depth The search depth for the evaluation.
     * @param nodeType The type of node (exact, lower bound, upper bound).
     * @param bestMove The best move found at the position, or null if none was found.
     */
    public void store(long zobristHash, double score, int depth, byte nodeType, Move bestMove) {
      int index = (int) (zobristHash & mask) & ~1;
      final short moveCode = bestMove == null
              ? TranspositionTable.NO_MOVE : TranspositionTable.moveCode(bestMove);

      final byte age = currentAge;
      final int target = replacementSlot(index, zobristHash);

      final int base = target * WORDS_PER_SLOT;
      final long heldScoreBits = words[base + SCORE];
      final long heldData = words[base + DATA];
      if ((words[base + CHECK] ^ heldScoreBits ^ heldData) == zobristHash
              && depthOf(heldData) > depth && nodeType != TranspositionTable.EXACT) {
        write(target, zobristHash, heldScoreBits,
                pack(depthOf(heldData), nodeTypeOf(heldData), age, moveCodeOf(heldData)));
        return;
      }

      write(target, zobristHash, Double.doubleToRawLongBits(score),
              pack(depth, nodeType, age, moveCode));
    }

    /**
     * Determines which slot of a bucket a store should be written to. A slot already holding the
     * key is taken over any other, so a key occupies at most one slot of its bucket. Otherwise an
     * empty slot is taken, then a slot left by an earlier search, then the shallower slot, an
     * exact entry being kept over a bound of the same depth.
     *
     * @param first The index of the bucket's first slot; the second slot follows it.
     * @param key The Zobrist hash of the new entry.
     * @return The index of the slot the store should be written to.
     */
    private int replacementSlot(final int first, final long key) {
      final int second = first + 1;
      final long firstKey = keyAt(first);
      final long secondKey = keyAt(second);
      if (firstKey == key) return first;
      if (secondKey == key) return second;
      if (firstKey == 0) return first;
      if (secondKey == 0) return second;

      final long firstData = dataAt(first);
      final long secondData = dataAt(second);
      final byte age = currentAge;
      final boolean firstIsStale = ageOf(firstData) != age;
      final boolean secondIsStale = ageOf(secondData) != age;
      if (firstIsStale != secondIsStale) {
        return firstIsStale ? first : second;
      }

      final short firstDepth = depthOf(firstData);
      final short secondDepth = depthOf(secondData);
      if (firstDepth != secondDepth) {
        return firstDepth < secondDepth ? first : second;
      }

      final boolean firstIsExact = nodeTypeOf(firstData) == TranspositionTable.EXACT;
      final boolean secondIsExact = nodeTypeOf(secondData) == TranspositionTable.EXACT;
      if (firstIsExact != secondIsExact) {
        return firstIsExact ? second : first;
      }

      return first;
    }
  }
}