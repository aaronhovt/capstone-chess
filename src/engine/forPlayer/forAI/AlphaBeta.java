package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forBoard.MoveCode;
import engine.forBoard.MoveGenerator;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;


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
public class AlphaBeta implements MoveStrategy {

  /**
   * The squares of one colour, a8 among them, as one bit per tile. Two bishops stand on squares of
   * the same colour when both or neither are in it.
   */
  private static final long A8_COLOURED_SQUARES = 0xAA55AA55AA55AA55L;

  /** The score of a position won against a bare king, before material and king placement. */
  private static final double BARE_KING_WIN = 3000;

  /** The score per step the bare king stands from the centre, or towards the mating corner. */
  private static final int MOP_UP_EDGE_WEIGHT = 20;

  /** The score per step the two kings stand closer than the greatest distance of seven. */
  private static final int MOP_UP_KING_WEIGHT = 10;

  /** The material the stronger side's pieces count for against a bare king, by piece type. */
  private static final int[] MOP_UP_PIECE_VALUES = {100, 320, 330, 500, 900, 0};

  /** The squares of a corner of each colour: a8 and h1, then h8 and a1. */
  private static final int[][] CORNERS_BY_COLOUR = {{0, 63}, {7, 56}};

  /** The depth used by {@link #execute(Board)} when a caller supplies no depth of its own. */
  private final int maxDepth;

  /** The count of boards evaluated during the search process. */
  private final AtomicLong boardsEvaluated = new AtomicLong(0);

  /**
   * The receiver of the report line printed after each completed iteration of the main search.
   * It is called on the thread running {@link #execute}.
   */
  private volatile Consumer<String> reportListener = report -> { };

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
   * The {@link TranspositionTable#moveCode codes} of the quiet moves searched so far at each ply,
   * held so that a cutoff can penalize the quiet moves that were searched before it. A ply's
   * entries are meaningful only up to the count kept by the node searching at that ply.
   */
  private final ThreadLocal<short[][]> searchedQuiets = ThreadLocal.withInitial(() ->
          new short[MAX_SEARCH_DEPTH][MAX_TRACKED_QUIETS]);

  /**
   * The static evaluation of the node at each ply from the point of view of its side to move, or
   * NaN for a node in check. A ply's entry is meaningful only for the node on the current search
   * path at that ply, which writes it before it searches any move.
   */
  private final ThreadLocal<double[]> staticEvals = ThreadLocal.withInitial(() ->
          new double[MAX_SEARCH_DEPTH]);

  /**
   * The arrays a quiescence node at each ply writes its moves, their static exchange scores and
   * their ordering keys into. A ply's entries are meaningful only while the node at that ply is
   * searching its moves.
   */
  private final ThreadLocal<QuiescenceBuffers> quiescenceBuffers =
          ThreadLocal.withInitial(QuiescenceBuffers::new);

  /**
   * The arrays a node of the main search at each ply generates and orders its moves in. A ply's
   * entries are meaningful only while the node at that ply is searching its moves.
   */
  private final ThreadLocal<NegamaxBuffers> negamaxBuffers =
          ThreadLocal.withInitial(NegamaxBuffers::new);

  /**
   * The per ply arrays of the main search, indexed by ply and then by move.
   *
   * @param generated The packed moves at each ply in generation order.
   * @param orderKeys The packed ordering keys of the generated moves, which the node at that ply
   *                  rearranges into search order as it searches.
   */
  private record NegamaxBuffers(int[][] generated, long[][] orderKeys) {

    /** Creates the arrays for every ply a search can reach. */
    NegamaxBuffers() {
      this(new int[MAX_SEARCH_DEPTH][MoveGenerator.MAX_MOVES],
              new long[MAX_SEARCH_DEPTH][MoveGenerator.MAX_MOVES]);
    }
  }

  /**
   * The per ply arrays of quiescence search, indexed by ply and then by move.
   *
   * @param moves The packed moves generated at each ply.
   * @param exchangeScores The static exchange score of each of those moves.
   * @param orderKeys The packed ordering keys of those moves.
   */
  private record QuiescenceBuffers(int[][] moves, int[][] exchangeScores, long[][] orderKeys) {

    /** Creates the arrays for every ply a search can reach. */
    QuiescenceBuffers() {
      this(new int[MAX_SEARCH_DEPTH][MoveGenerator.MAX_MOVES],
              new int[MAX_SEARCH_DEPTH][MoveGenerator.MAX_MOVES],
              new long[MAX_SEARCH_DEPTH][MoveGenerator.MAX_MOVES]);
    }
  }

  /**
   * Killer moves table storing the {@link TranspositionTable#moveCode codes} of good non-capture
   * moves for each search ply, with {@link TranspositionTable#NO_MOVE} where none is held.
   */
  private final ThreadLocal<short[][]> killerMoves = ThreadLocal.withInitial(() ->
          new short[2][MAX_SEARCH_DEPTH]);

  /**
   * Countermove table holding, for each origin and destination square of an opponent move at
   * index origin * 64 + destination, the {@link TranspositionTable#moveCode code} of the quiet
   * move recorded in response, or {@link TranspositionTable#NO_MOVE} if none is recorded.
   */
  private final AtomicIntegerArray counterMoves = new AtomicIntegerArray(64 * 64);

  /** The node limit that lets a search run to the depth it was asked for. */
  public static final long UNLIMITED_NODES = Long.MAX_VALUE;

  /** The time limit in milliseconds that lets a search run to the depth it was asked for. */
  public static final long UNLIMITED_TIME = Long.MAX_VALUE;

  /** The deadline held by a search that is not under a time limit. */
  private static final long NO_DEADLINE = Long.MAX_VALUE;

  /** The mask a node count is tested against to decide whether the clock is read. */
  private static final long TIME_CHECK_MASK = 1023;

  /**
   * The factors a soft time limit is multiplied by, indexed by the number of finished iterations
   * in a row that returned the same best move as the iteration before them. The last factor holds
   * for any longer run.
   */
  private static final double[] SOFT_TIME_SCALES = {1.5, 1.2, 1.0, 0.85, 0.75};

  /** Maximum search depth supported by data structures. */
  private static final int MAX_SEARCH_DEPTH = 100;

  /** The ply at which a node is evaluated rather than searched, bounding search recursion. */
  private static final int MAX_PLY = MAX_SEARCH_DEPTH - 2;

  /** The transposition table size in megabytes used when a caller does not specify one. */
  private static final int DEFAULT_TABLE_SIZE_MB = 256;

  /** The greatest remaining depth at which a node is pruned by reverse futility. */
  private static final int FUTILITY_PRUNING_DEPTH = 5;

  /** The futility pruning margin per ply of remaining depth. */
  private static final int FUTILITY_MARGIN = 65;

  /** The greatest remaining depth at which quiet moves late in the move order are pruned. */
  private static final int LATE_MOVE_PRUNING_DEPTH = 3;

  /** The greatest remaining depth at which quiet moves are pruned by futility. */
  private static final int QUIET_FUTILITY_DEPTH = 3;

  /** The quiet move futility pruning margin per ply of remaining depth. */
  private static final int QUIET_FUTILITY_MARGIN = 75;

  /** The least remaining depth at which a null move is searched. */
  private static final int NULL_MOVE_DEPTH = 3;

  /** The least remaining depth at which a null move cutoff is verified before it is taken. */
  private static final int NULL_MOVE_VERIFICATION_DEPTH = 6;

  /**
   * The least remaining depth at which an open-window node with no transposition table move is
   * searched one ply shallower.
   */
  private static final int INTERNAL_REDUCTION_DEPTH = 5;

  /**
   * The least remaining depth at which a zero-window node with no transposition table move is
   * searched one ply shallower.
   */
  private static final int ZERO_WINDOW_INTERNAL_REDUCTION_DEPTH = 6;

  /**
   * Whether the search counts what it does into the {@link SearchStatistics} of its thread, read
   * once from the system property {@value SearchStatistics#PROPERTY} when this class is loaded.
   */
  static final boolean COLLECT_STATISTICS = Boolean.getBoolean(SearchStatistics.PROPERTY);

  /** The width of a zero window, being the smallest score separation the search distinguishes. */
  private static final double ZERO_WINDOW = 0.1;

  /** The constant term of the late move reduction formula, in plies. */
  private static final double LMR_BASE = 0.75;

  /**
   * The divisor of the product of the logarithms of remaining depth and move number in the late
   * move reduction formula.
   */
  private static final double LMR_DIVISOR = 2.25;

  /**
   * The late move reduction in plies, indexed by remaining depth and then by move number, each
   * counted from one and capped at the last index.
   */
  private static final int[][] LATE_MOVE_REDUCTIONS = new int[64][64];

  static {
    for (int depth = 1; depth < LATE_MOVE_REDUCTIONS.length; depth++) {
      for (int moveNumber = 1; moveNumber < LATE_MOVE_REDUCTIONS[depth].length; moveNumber++) {
        LATE_MOVE_REDUCTIONS[depth][moveNumber] =
                (int) (LMR_BASE + Math.log(depth) * Math.log(moveNumber) / LMR_DIVISOR);
      }
    }
  }

  /**
   * The margin added to the static exchange score of a pawn capture when testing whether the
   * capture can raise the score of a quiescence node past its bound.
   */
  private static final double PAWN_CAPTURE_DELTA_MARGIN = 105;

  /**
   * The margin added to the static exchange score of a knight or bishop capture when testing
   * whether the capture can raise the score of a quiescence node past its bound.
   */
  private static final double MINOR_CAPTURE_DELTA_MARGIN = 165;

  /**
   * The margin added to the static exchange score of a rook capture when testing whether the
   * capture can raise the score of a quiescence node past its bound.
   */
  private static final double ROOK_CAPTURE_DELTA_MARGIN = 285;

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

  /**
   * The number of leading moves of a main search node that are each selected from the remaining
   * ordering keys before the keys left after them are sorted.
   */
  private static final int SELECTED_MOVES = 3;

  /** The score of a checkmate delivered at the root, reduced by one for each ply to the mate. */
  public static final double MATE_VALUE = 1000000;

  /** The lowest magnitude at which a score is a checkmate score rather than an evaluation. */
  public static final double MATE_THRESHOLD = MATE_VALUE - MAX_SEARCH_DEPTH;

  /** Reference to the static exchange evaluator for move evaluation. */
  private final StaticExchangeEvaluator seeEvaluator = StaticExchangeEvaluator.get();

  /**
   * Returns the root moves of the given position in search order: moves that give check first,
   * then castling moves, then good captures, quiet moves and bad captures in that order, then by
   * static exchange score and by history score, each highest first. Moves that compare equal keep
   * generation order.
   *
   * @param board The root position.
   * @return The packed root moves in search order.
   */
  private int[] orderRootMoves(final Board board) {
    final int[] generated = new int[MoveGenerator.MAX_MOVES];
    final int count = MoveGenerator.generate(board, board.currentPlayer().getAlliance(), generated);
    final int historySide = historySideOf(board);
    final boolean[] givesCheck = new boolean[count];
    final int[] exchangeScores = new int[count];
    final int[] historyScores = new int[count];
    final Integer[] order = new Integer[count];
    for (int i = 0; i < count; i++) {
      final int move = generated[i];
      givesCheck[i] = AttackDetector.givesCheck(board, move);
      exchangeScores[i] = MoveCode.isCapture(move) ? seeEvaluator.evaluate(board, move) : 0;
      historyScores[i] = historyOf(move, historySide);
      order[i] = i;
    }

    // The sort is stable, so moves that compare equal keep generation order. The two score
    // comparisons take their operands reversed, so that a higher static exchange score and a
    // higher history score each sort first.
    Arrays.sort(order, (first, second) -> {
      int comparison = Boolean.compare(givesCheck[second], givesCheck[first]);
      if (comparison == 0) {
        comparison = Boolean.compare(MoveCode.isCastle(generated[second]),
                MoveCode.isCastle(generated[first]));
      }
      if (comparison == 0) {
        comparison = Long.compare(captureRankOf(generated[first], exchangeScores[first]),
                captureRankOf(generated[second], exchangeScores[second]));
      }
      if (comparison == 0) {
        comparison = Integer.compare(exchangeScores[second], exchangeScores[first]);
      }
      if (comparison == 0) {
        comparison = Integer.compare(historyScores[second], historyScores[first]);
      }
      return comparison;
    });

    final int[] sorted = new int[count];
    for (int i = 0; i < count; i++) {
      sorted[i] = generated[order[i]];
    }
    return sorted;
  }

  /**
   * Returns the ordering rank of the given packed move, which places a good capture before a
   * quiet move and a quiet move before a bad capture.
   *
   * @param move The packed move to rank.
   * @param exchangeScore The static exchange score of the move, if it captures.
   * @return GOOD_CAPTURE_RANK, QUIET_RANK, or BAD_CAPTURE_RANK.
   */
  private static long captureRankOf(final int move, final int exchangeScore) {
    if (!MoveCode.isCapture(move)) {
      return QUIET_RANK;
    }
    return exchangeScore >= 0 ? GOOD_CAPTURE_RANK : BAD_CAPTURE_RANK;
  }

  /**
   * Writes the moves of the side to move at the given ply into that ply's row of the generated
   * move buffer, in generation order, and the packed ordering key of each to the same index of the
   * ply's row of the ordering key buffer. Ascending key order is search order: good captures
   * first, then killer moves, the countermove and the other quiet moves by history score, then
   * bad captures; captures are ordered by static exchange score. The keys are distinct, and
   * {@link #moveIndexOf} and {@link #exchangeScoreOf} read a key's move and exchange score.
   *
   * @param board The current board position.
   * @param ply The current search ply.
   * @return The number of moves written.
   */
  private int writeOrderKeys(final Board board, final int ply) {
    final NegamaxBuffers buffers = negamaxBuffers.get();
    final int[] generated = buffers.generated()[ply];
    final long[] orderKeys = buffers.orderKeys()[ply];
    final int count = MoveGenerator.generate(board, board.currentPlayer().getAlliance(), generated);
    final short[][] killers = killerMoves.get();
    final int counter = counterMoveOf(board);
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
    for (int i = 0; i < count; i++) {
      final int move = generated[i];
      final boolean capture = MoveCode.isCapture(move);

      final int exchangeScore = capture ? seeEvaluator.evaluate(board, move) : 0;

      final long rank = capture ? (exchangeScore >= 0 ? GOOD_CAPTURE_RANK : BAD_CAPTURE_RANK) :
              QUIET_RANK;
      long tier = rank << ORDER_RANK_SHIFT;
      if (!capture) {
        final short code = MoveCode.tableCode(move);
        tier += (code == killers[0][ply] || code == killers[1][ply] ? 0 : KILLER_TIER_BIT) +
                (code == counter ? 0 : COUNTER_TIER_BIT);
      }
      final long secondary = (long) Integer.MAX_VALUE -
              (capture ? (long) exchangeScore : (long) historyOf(move, historySide));

      orderKeys[i] = (tier << ORDER_TIER_SHIFT) | (secondary << ORDER_INDEX_BITS) | i;
    }
    return count;
  }

  /**
   * Returns the generation index of the move a packed standard ordering key belongs to.
   *
   * @param orderKey The packed ordering key.
   * @return The index of its move in the generated move buffer.
   */
  private static int moveIndexOf(final long orderKey) {
    return (int) (orderKey & ORDER_INDEX_MASK);
  }

  /**
   * Returns the static exchange score held in a packed standard ordering key. The result is
   * meaningful only for the key of a capture.
   *
   * @param orderKey The packed ordering key of a capture.
   * @return The static exchange score of the capture.
   */
  private static int exchangeScoreOf(final long orderKey) {
    return (int) ((long) Integer.MAX_VALUE - ((orderKey >>> ORDER_INDEX_BITS) & ORDER_SCORE_MASK));
  }

  /**
   * Returns the {@link SearchStatistics} cutoff class of the move a packed standard ordering key
   * belongs to.
   *
   * @param orderKey The packed ordering key of the move.
   * @param tableMove Whether the move is the transposition table move searched first.
   * @return The cutoff class.
   */
  private static int cutClassOf(final long orderKey, final boolean tableMove) {
    if (tableMove) {
      return SearchStatistics.CUT_BY_TABLE_MOVE;
    }
    final long tier = orderKey >>> ORDER_TIER_SHIFT;
    final long rank = tier >>> ORDER_RANK_SHIFT;
    if (rank == GOOD_CAPTURE_RANK) {
      return SearchStatistics.CUT_BY_GOOD_CAPTURE;
    } else if (rank == BAD_CAPTURE_RANK) {
      return SearchStatistics.CUT_BY_BAD_CAPTURE;
    } else if ((tier & KILLER_TIER_BIT) == 0) {
      return SearchStatistics.CUT_BY_KILLER;
    } else if ((tier & COUNTER_TIER_BIT) == 0) {
      return SearchStatistics.CUT_BY_COUNTERMOVE;
    }
    return SearchStatistics.CUT_BY_OTHER_QUIET;
  }

  /**
   * Returns the search statistics of the calling thread. Called only when
   * {@link #COLLECT_STATISTICS} is set.
   *
   * @return The calling thread's statistics.
   */
  private static SearchStatistics statistics() {
    return SearchStatistics.current();
  }

  /**
   * Swaps the smallest of the keys from the given index up to the given count into the given
   * index.
   *
   * @param keys The packed ordering keys.
   * @param from The index to fill, which must be less than count.
   * @param count The number of leading entries of keys that hold a key.
   */
  private static void selectSmallestKey(final long[] keys, final int from, final int count) {
    int smallest = from;
    for (int i = from + 1; i < count; i++) {
      if (keys[i] < keys[smallest]) {
        smallest = i;
      }
    }
    final long key = keys[smallest];
    keys[smallest] = keys[from];
    keys[from] = key;
  }

  /**
   * Returns the code of the countermove recorded against the move that produced the given
   * position.
   *
   * @param board The position being searched.
   * @return The {@link TranspositionTable#moveCode code} of the recorded countermove, or
   *         {@link TranspositionTable#NO_MOVE}, which is the code of no move, if none is
   *         recorded.
   */
  private int counterMoveOf(final Board board) {
    final int lastMove = board.getTransitionCode();
    if (lastMove == MoveCode.NONE) {
      return TranspositionTable.NO_MOVE;
    }
    return counterMoves.get(MoveCode.origin(lastMove) * 64 + MoveCode.destination(lastMove));
  }

  /**
   * Returns the history heuristic score recorded for the given packed move.
   *
   * @param move The move to score.
   * @param historySide The index of the side that plays the move within the history table.
   * @return The recorded history score.
   */
  private int historyOf(final int move, final int historySide) {
    return historyHeuristic[historySide][MoveCode.origin(move)][MoveCode.destination(move)];
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
   * the current player, to the given depth, under one time limit that both stops the search and
   * ends the iterations, as {@link #execute(Board, int, long, long, long)} does with both of its
   * time limits equal.
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
    return execute(board, searchDepth, nodeLimit, timeLimitMillis, timeLimitMillis);
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
   * to neither limit, so a finished iteration always exists. When the soft time limit is shorter
   * than the time limit, no further iteration is started once the soft time limit, multiplied by a
   * factor from {@link #SOFT_TIME_SCALES}, has passed. The factor shrinks as more finished
   * iterations in a row return the same best move, so the search starts fewer iterations when the
   * move is settled and more when it has just changed.
   * <p>
   * The clock is not read at every node, so a search under a time limit runs somewhat past its
   * deadline rather than stopping on it.
   *
   * @param board The current chess board position.
   * @param searchDepth The maximum depth for iterative deepening on this search.
   * @param nodeLimit The number of positions to evaluate before the search is stopped, or
   *                  UNLIMITED_NODES to run every iteration to its end.
   * @param softTimeLimitMillis The milliseconds after which no further iteration is started,
   *                            before it is scaled by the stability of the best move, or
   *                            UNLIMITED_TIME to start iterations until another limit ends the
   *                            search.
   * @param timeLimitMillis The milliseconds to search for before the search is stopped, or
   *                        UNLIMITED_TIME to run every iteration to its end.
   * @return The best move determined by the search algorithm.
   */
  public Move execute(final Board board, final int searchDepth, final long nodeLimit,
                      final long softTimeLimitMillis, final long timeLimitMillis) {
    final long startTime = System.currentTimeMillis();
    final long searchStart = System.nanoTime();
    final long deadline = deadlineOf(timeLimitMillis);
    final boolean softLimited = softTimeLimitMillis < timeLimitMillis;
    int bestMove = MoveCode.NONE;
    double bestScore = 0;
    int bestDepth = 0;
    int stableIterations = 0;

    this.searchStopped = false;
    this.boardsEvaluated.set(0);
    this.transpositionTable.incrementAge();

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

        final long iterationStart = stats.boardsEvaluated;
        final RootResult result = currentDepth >= 4 ?
                searchRootAspirationWindow(mainBoard, currentDepth, bestMove, bestScore) :
                searchRoot(mainBoard, currentDepth, -Double.MAX_VALUE, Double.MAX_VALUE, bestMove);
        if (COLLECT_STATISTICS) {
          final long iterationNodes = stats.boardsEvaluated - iterationStart;
          if (searchStopped) {
            statistics().iterationStopped(iterationNodes);
          } else {
            statistics().iterationFinished(currentDepth, iterationNodes);
          }
        }

        if (result.move() == MoveCode.NONE) {
          break;
        }

        final boolean sameMove = result.move() == bestMove;
        bestMove = result.move();
        bestScore = result.score();

        if (searchStopped) {
          break;
        }

        bestDepth = currentDepth;
        stableIterations = sameMove ? stableIterations + 1 : 0;

        recordHistory(mainBoard, bestMove, currentDepth);

        final long evaluatedPositions = this.boardsEvaluated.get() + stats.boardsEvaluated;
        final long executionTime = System.currentTimeMillis() - startTime;
        final String report = String.format(
                "%s | depth = %d | boards evaluated = %d | time = %.2f sec | nps = %.2f | %s",
                toMove(mainBoard, bestMove), currentDepth, evaluatedPositions,
                executionTime / 1000.0,
                evaluatedPositions / (executionTime / 1000.0),
                this.evaluationCache.getStats());

        System.out.println(report);
        this.reportListener.accept(report);

        if (softLimited) {
          final double scale =
                  SOFT_TIME_SCALES[Math.min(stableIterations, SOFT_TIME_SCALES.length - 1)];
          final long softLimitNanos = (long) (softTimeLimitMillis * 1_000_000L * scale);
          if (System.nanoTime() - searchStart >= softLimitNanos) {
            break;
          }
        }
      }
    } finally {
      stopHelperSearches(helpers);
      this.boardsEvaluated.addAndGet(stats.boardsEvaluated);
    }

    assert mainBoard.getZobristHash() == rootHash :
            "The main search left its board somewhere other than the root position.";

    // Adding zero turns the negative zero that negating a drawn score can produce into zero.
    this.lastScore = (mainBoard.currentPlayer().getAlliance().isWhite() ? bestScore : -bestScore) + 0.0;
    this.lastDepth = bestDepth;

    if (COLLECT_STATISTICS) {
      statistics().searchFinished(bestDepth, System.currentTimeMillis() - startTime,
              stats.boardsEvaluated);
    }

    return toMove(board, bestMove);
  }

  /**
   * Returns the move of the given position equal to the given packed move.
   *
   * @param board The position the move belongs to.
   * @param move A packed move of the side to move, or {@link MoveCode#NONE}.
   * @return The equal move from the side to move's legal moves, or the null move for
   *         {@link MoveCode#NONE}.
   * @throws IllegalStateException If the side to move has no such move.
   */
  private static Move toMove(final Board board, final int move) {
    if (move == MoveCode.NONE) {
      return Move.nullMove();
    }
    for (final Move candidate : board.currentPlayer().getLegalMoves()) {
      if (MoveCode.of(candidate) == move) {
        return candidate;
      }
    }
    throw new IllegalStateException("The position has no move " + move + ".");
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
    int bestMove = MoveCode.NONE;

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
   * Sets the receiver of the report line printed after each completed iteration of the main
   * search, replacing any receiver set before. It is called on the thread running
   * {@link #execute}, so a receiver that updates a user interface must hand the line over to that
   * interface's thread.
   *
   * @param listener The receiver of the report lines.
   */
  public void setReportListener(final Consumer<String> listener) {
    this.reportListener = Objects.requireNonNull(listener);
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
   * Retrieves a cached board evaluation or computes a new evaluation if not found in cache. A
   * position {@link #isDrawnByMaterial} is scored zero; any other is scored by the evaluation
   * network.
   *
   * @param board The board position to evaluate.
   * @return The evaluation score for the board position, from White's point of view.
   */
  private double getCachedEvaluation(Board board) {
    final long zobristHash = board.getZobristHash();
    final double cachedScore = this.evaluationCache.probe(zobristHash);
    if (!Double.isNaN(cachedScore)) {
      return cachedScore;
    }

    double score = 0;
    if (!isDrawnByMaterial(board)) {
      score = mopUpScore(board);
      if (Double.isNaN(score)) {
        final int networkScore = board.evaluateNetwork();
        score = board.currentPlayer().getAlliance().isWhite() ? networkScore : -networkScore;
      }
    }
    this.evaluationCache.store(zobristHash, score);
    return score;
  }

  /**
   * Returns the score of a position in which one side has only its king and the other side has
   * the material to force mate: a queen, a rook, a bishop and a knight, or bishops on squares of
   * both colours. The score is {@link #BARE_KING_WIN} plus the stronger side's material, plus
   * {@link #MOP_UP_EDGE_WEIGHT} for each step the bare king stands from the centre, plus
   * {@link #MOP_UP_KING_WEIGHT} for each step the kings stand closer than seven apart. When the
   * mating material is a bishop and a knight alone, the bare king is scored by how near it stands
   * to a corner of the bishop's colour instead of by its distance from the centre.
   *
   * @param board The board position to score.
   * @return The score from White's point of view, or NaN if the position is not such an ending.
   */
  private static double mopUpScore(final Board board) {
    final boolean whiteBare = board.getOccupancy(Alliance.WHITE) ==
            board.getPieces(Alliance.WHITE, Piece.PieceType.KING);
    final boolean blackBare = board.getOccupancy(Alliance.BLACK) ==
            board.getPieces(Alliance.BLACK, Piece.PieceType.KING);
    if (whiteBare == blackBare) {
      return Double.NaN;
    }
    final Alliance strong = whiteBare ? Alliance.BLACK : Alliance.WHITE;
    final Alliance weak = whiteBare ? Alliance.WHITE : Alliance.BLACK;
    final long heavy = board.getPieces(strong, Piece.PieceType.QUEEN)
            | board.getPieces(strong, Piece.PieceType.ROOK);
    final long bishops = board.getPieces(strong, Piece.PieceType.BISHOP);
    final long knights = board.getPieces(strong, Piece.PieceType.KNIGHT);
    final boolean bothBishopColours = (bishops & A8_COLOURED_SQUARES) != 0L
            && (bishops & ~A8_COLOURED_SQUARES) != 0L;
    if (heavy == 0L && !bothBishopColours && (bishops == 0L || knights == 0L)) {
      return Double.NaN;
    }

    double score = BARE_KING_WIN;
    for (final Piece.PieceType type : Piece.PieceType.values()) {
      score += MOP_UP_PIECE_VALUES[type.ordinal()] * Long.bitCount(board.getPieces(strong, type));
    }
    final int weakKing = Long.numberOfTrailingZeros(board.getPieces(weak, Piece.PieceType.KING));
    final int strongKing = Long.numberOfTrailingZeros(board.getPieces(strong, Piece.PieceType.KING));
    if (heavy == 0L && !bothBishopColours) {
      final int[] corners = CORNERS_BY_COLOUR[(bishops & A8_COLOURED_SQUARES) != 0L ? 0 : 1];
      final int nearest = Math.min(manhattanDistance(weakKing, corners[0]),
              manhattanDistance(weakKing, corners[1]));
      score += MOP_UP_EDGE_WEIGHT * (14 - nearest) / 2.0;
    } else {
      score += MOP_UP_EDGE_WEIGHT * centreDistance(weakKing);
    }
    score += MOP_UP_KING_WEIGHT * (7 - Math.max(Math.abs(weakKing / 8 - strongKing / 8),
            Math.abs(weakKing % 8 - strongKing % 8)));
    return strong.isWhite() ? score : -score;
  }

  /**
   * Returns how many steps a square stands from the four centre squares, counting files and ranks.
   *
   * @param square The square.
   * @return The distance, from 0 on a centre square to 6 in a corner.
   */
  private static int centreDistance(final int square) {
    final int file = square % 8;
    final int rank = square / 8;
    return Math.max(3 - file, file - 4) + Math.max(3 - rank, rank - 4);
  }

  /**
   * Returns the number of file and rank steps between two squares.
   *
   * @param from The first square.
   * @param to The second square.
   * @return The sum of the file distance and the rank distance.
   */
  private static int manhattanDistance(final int from, final int to) {
    return Math.abs(from % 8 - to % 8) + Math.abs(from / 8 - to / 8);
  }

  /**
   * Returns whether neither side has the material to win: no pawns, rooks or queens remain, and
   * either at most one minor piece is left, each side has one bishop and the two stand on squares
   * of the same colour, or the only minor pieces are two knights of one side.
   *
   * @param board The board position to judge.
   * @return True if the position is scored as a draw on material.
   */
  private static boolean isDrawnByMaterial(final Board board) {
    if ((board.getPieces(Alliance.WHITE, Piece.PieceType.PAWN)
            | board.getPieces(Alliance.BLACK, Piece.PieceType.PAWN)
            | board.getPieces(Alliance.WHITE, Piece.PieceType.ROOK)
            | board.getPieces(Alliance.BLACK, Piece.PieceType.ROOK)
            | board.getPieces(Alliance.WHITE, Piece.PieceType.QUEEN)
            | board.getPieces(Alliance.BLACK, Piece.PieceType.QUEEN)) != 0L) {
      return false;
    }
    final long whiteKnights = board.getPieces(Alliance.WHITE, Piece.PieceType.KNIGHT);
    final long blackKnights = board.getPieces(Alliance.BLACK, Piece.PieceType.KNIGHT);
    final long whiteBishops = board.getPieces(Alliance.WHITE, Piece.PieceType.BISHOP);
    final long blackBishops = board.getPieces(Alliance.BLACK, Piece.PieceType.BISHOP);
    final int knights = Long.bitCount(whiteKnights) + Long.bitCount(blackKnights);
    final int bishops = Long.bitCount(whiteBishops) + Long.bitCount(blackBishops);
    if (knights + bishops <= 1) {
      return true;
    }
    if (knights == 0 && Long.bitCount(whiteBishops) == 1 && Long.bitCount(blackBishops) == 1) {
      return ((whiteBishops & A8_COLOURED_SQUARES) == 0L) ==
              ((blackBishops & A8_COLOURED_SQUARES) == 0L);
    }
    return bishops == 0 && knights == 2 &&
            (Long.bitCount(whiteKnights) == 2 || Long.bitCount(blackKnights) == 2);
  }

  /**
   * Searches the root with a narrow window centered on the score of the previous iteration. A
   * search whose score reaches or passes a bound is discarded and repeated with that bound moved
   * to infinity. If the repeated search fails on the other bound, the root is searched again with
   * a full window. A checkmate score from the previous iteration is never narrowed. Scores are from
   * the point of view of the side to move at the root.
   *
   * @param board The board this search thread owns, in the root position.
   * @param depth The current search depth.
   * @param previousBestMove The packed best move from the previous iteration.
   * @param previousScore The root score from the previous iteration.
   * @return The best move found and its score. If the search was stopped, this is the best move of
   *         the attempt that was stopped, which is {@link MoveCode#NONE} if no root move searched
   *         in that attempt scored inside its window.
   */
  private RootResult searchRootAspirationWindow(final Board board, final int depth,
                                                final int previousBestMove, final double previousScore) {
    if (Math.abs(previousScore) >= MATE_THRESHOLD) {
      return searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
    }

    double alpha = previousScore - ASPIRATION_WINDOW;
    double beta = previousScore + ASPIRATION_WINDOW;

    if (COLLECT_STATISTICS) {
      statistics().aspirationStarted();
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      final long attemptStart = COLLECT_STATISTICS ? threadStats.get().boardsEvaluated : 0;
      final RootResult result = searchRoot(board, depth, alpha, beta, previousBestMove);
      if (COLLECT_STATISTICS) {
        final boolean settled = this.searchStopped;
        statistics().aspirationAttempt(threadStats.get().boardsEvaluated - attemptStart,
                !settled && result.score() <= alpha, !settled && result.score() >= beta);
      }

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

    if (COLLECT_STATISTICS) {
      statistics().aspirationFullWindow();
      final long attemptStart = threadStats.get().boardsEvaluated;
      final RootResult result =
              searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
      statistics().aspirationAttempt(threadStats.get().boardsEvaluated - attemptStart, false, false);
      return result;
    }
    return searchRoot(board, depth, -Double.MAX_VALUE, Double.MAX_VALUE, previousBestMove);
  }

  /**
   * Searches every legal root move on the calling thread and returns the best one with its score.
   * The move that was best in the previous iteration is searched first. The first legal move is
   * searched against the full window, and each later move against a zero window at the best score
   * found so far, then again against the window from that score to the far bound if it scores
   * strictly inside it. A root move that leaves the mover in check is skipped. A search that is
   * stopped part way returns the best of the moves whose search finished, and
   * {@link MoveCode#NONE} if none of them scored inside the window. The move being searched
   * when the stop came is not considered. Bounds and scores are from the point of view of the side
   * to move at the root.
   *
   * @param board The board this search thread owns, in the root position. It is left in that
   *              position when this method returns.
   * @param depth The current search depth.
   * @param alpha The alpha bound for alpha-beta search.
   * @param beta The beta bound for alpha-beta search.
   * @param previousBestMove The packed best move from the previous iteration, or
   *                         {@link MoveCode#NONE}.
   * @return The best move found and its score.
   */
  private RootResult searchRoot(final Board board, final int depth, final double alpha,
                                final double beta, final int previousBestMove) {
    final int[] rootMoves = orderRootMoves(board);

    if (rootMoves.length == 0) {
      return new RootResult(MoveCode.NONE, 0);
    }

    if (previousBestMove != MoveCode.NONE) {
      for (int i = 0; i < rootMoves.length; i++) {
        if (rootMoves[i] == previousBestMove) {
          System.arraycopy(rootMoves, 0, rootMoves, 1, i);
          rootMoves[0] = previousBestMove;
          break;
        }
      }
    }

    staticEvals.get()[0] = board.currentPlayer().isInCheck() ? Double.NaN :
            evaluateForSideToMove(board);

    int bestMove = MoveCode.NONE;
    double bestScore = alpha;
    boolean firstMove = true;

    for (final int move : rootMoves) {
      if (searchStopped) {
        break;
      }

      board.makeMove(move);
      if (board.currentPlayer().getOpponent().isInCheck()) {
        board.unmakeMove(move);
        continue;
      }

      double score;
      try {
        if (firstMove) {
          score = -negamax(board, depth - 1, -beta, -bestScore, 1, true);
        } else {
          score = -negamax(board, depth - 1, -(bestScore + ZERO_WINDOW), -bestScore, 1, true);
          if (score > bestScore && score < beta) {
            score = -negamax(board, depth - 1, -beta, -bestScore, 1, true);
          }
        }
      } finally {
        board.unmakeMove(move);
      }
      firstMove = false;

      if (searchStopped) {
        break;
      }

      if (score > bestScore) {
        bestScore = score;
        bestMove = move;

        if (bestScore >= beta) {
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
   * @param move The packed best root move found, or {@link MoveCode#NONE}.
   * @param score The score of that move, from the point of view of the side to move at the root.
   */
  private record RootResult(int move, double score) {
  }

  /**
   * Records a packed quiet move as a countermove response to the last opponent move for move
   * ordering. A capture is not recorded.
   *
   * @param board The current board position.
   * @param move The packed move to record as a countermove.
   */
  private void recordCounterMove(final Board board, final int move) {
    if (MoveCode.isCapture(move)) {
      return;
    }
    final int lastMove = board.getTransitionCode();
    if (lastMove != MoveCode.NONE) {
      counterMoves.set(MoveCode.origin(lastMove) * 64 + MoveCode.destination(lastMove),
              MoveCode.tableCode(move));
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
    if (board.getTransitionMove() == Move.nullMove()) {
      return false;
    }
    if (board.isFiftyMoveRule()) {
      return !board.currentPlayer().isInCheckMate();
    }
    return board.repetitionCount() >= 2;
  }

  /**
   * Returns the index among the first entries of the given array of the packed move whose
   * transposition table code is the given code.
   *
   * @param moves The packed moves to search.
   * @param count The number of leading entries of moves that hold a move.
   * @param moveCode The code of the move to find.
   * @return The index of that move, or -1 if none of those moves has that code.
   */
  private static int indexOfMoveCode(final int[] moves, final int count, final short moveCode) {
    for (int i = 0; i < count; i++) {
      if (MoveCode.tableCode(moves[i]) == moveCode) {
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
   * @return The terminal score from the point of view of the side to move, negative when it is
   *         mated.
   */
  private double terminalScore(final Board board, final int ply) {
    if (!board.currentPlayer().isInCheckMate()) {
      return 0;
    }
    return -(MATE_VALUE - ply);
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
   * Returns the number of plies by which the search of a move is reduced, from the remaining depth
   * at the node and the move's place in the order of moves searched there. Depths and move numbers
   * past the end of the table read its last entry.
   *
   * @param depth The remaining search depth at the node, at least one.
   * @param moveNumber The move's number among the legal moves searched at the node, counted from
   *                   one.
   * @return The reduction in plies.
   */
  private static int lateMoveReduction(final int depth, final int moveNumber) {
    final int lastIndex = LATE_MOVE_REDUCTIONS.length - 1;
    return LATE_MOVE_REDUCTIONS[Math.min(depth, lastIndex)][Math.min(moveNumber, lastIndex)];
  }

  /**
   * Returns whether a move, not yet made on the board, may be skipped without a search because it
   * is a quiet move late in the move order at a shallow node. A capture, a promotion, and any move
   * at a node in check are never prunable. A checking move is not prunable either, which the
   * caller tests, and the caller applies its own mate guard on the window.
   *
   * @param move The move under consideration.
   * @param depth The remaining search depth at the node.
   * @param movesSearched The number of legal moves already searched at the node.
   * @param inCheckAtNode Whether the side to move at the node is in check.
   * @return true if the move may be skipped unless it gives check.
   */
  private static boolean isLateMovePrunable(final int move, final int depth,
                                            final int movesSearched, final boolean inCheckAtNode) {
    return depth <= LATE_MOVE_PRUNING_DEPTH
            && movesSearched >= 3 + depth * depth
            && !inCheckAtNode
            && !MoveCode.isCapture(move)
            && MoveCode.promotionType(move) == null;
  }

  /**
   * Returns whether a move, not yet made on the board, may be skipped without a search because it
   * is a quiet move at a shallow node whose static score falls short of the bound by at least the
   * quiet move futility margin for the remaining depth. The first move searched at a node, a
   * capture, a promotion, and any move at a node in check are never prunable. A checking move is
   * not prunable either, which the caller tests, and the caller applies its own mate guard on the
   * bound.
   *
   * @param move The move under consideration.
   * @param depth The remaining search depth at the node.
   * @param movesSearched The number of legal moves already searched at the node.
   * @param inCheckAtNode Whether the side to move at the node is in check.
   * @param shortfall How far the static score of the node falls short of the bound the side to
   *                  move must pass, positive when it falls short.
   * @return true if the move may be skipped unless it gives check.
   */
  private static boolean isFutileQuietMove(final int move, final int depth,
                                           final int movesSearched, final boolean inCheckAtNode,
                                           final double shortfall) {
    return depth <= QUIET_FUTILITY_DEPTH
            && movesSearched > 0
            && !inCheckAtNode
            && !MoveCode.isCapture(move)
            && MoveCode.promotionType(move) == null
            && shortfall >= depth * QUIET_FUTILITY_MARGIN;
  }

  /**
   * Returns whether the static evaluation of a node is higher than that of the last node before it
   * on the search path with the same side to move and not in check, looking back two plies and
   * then four. A node with no such earlier node counts as improving.
   *
   * @param evals The static evaluations of the nodes on the search path, indexed by ply, NaN for a
   *              node in check.
   * @param ply The ply of the node.
   * @param staticEval The static evaluation of the node, not NaN.
   * @return true if the node is improving.
   */
  private static boolean isImproving(final double[] evals, final int ply,
                                     final double staticEval) {
    double earlier = ply >= 2 ? evals[ply - 2] : Double.NaN;
    if (Double.isNaN(earlier) && ply >= 4) {
      earlier = evals[ply - 4];
    }
    return Double.isNaN(earlier) || staticEval > earlier;
  }

  /**
   * Stores an entry in the transposition table unless the search has been stopped. A score
   * produced after the stop flag is raised is not the result of a completed search.
   *
   * @param zobristHash The Zobrist hash of the board position.
   * @param score The score to store, already converted by {@link #scoreToTable}.
   * @param depth The search depth the score was produced at.
   * @param nodeType The type of node (exact, lower bound, upper bound).
   * @param moveCode The {@link TranspositionTable#moveCode code} of the best move found at this
   *                 node, or {@link TranspositionTable#NO_MOVE}.
   */
  private void storeIfSearching(final long zobristHash, final double score, final int depth,
                                final byte nodeType, final short moveCode) {
    if (!searchStopped) {
      transpositionTable.store(zobristHash, score, depth, nodeType, moveCode);
    }
  }

  /**
   * Returns the origins from which a move of the side to move can leave that side's own king in
   * check: every square when that side is in check, and otherwise the king's square and the
   * squares of the side's pinned pieces. An en passant capture can as well, from any origin.
   *
   * @param board The board in the position before the move.
   * @param inCheck Whether the side to move is in check.
   * @return The origins, as one bit per tile.
   */
  private static long testedOrigins(final Board board, final boolean inCheck) {
    if (inCheck) {
      return -1L;
    }
    final Alliance side = board.currentPlayer().getAlliance();
    return AttackDetector.pinnedPieces(board, side) |
            board.getPieces(side, Piece.PieceType.KING);
  }

  /**
   * Determines whether the move just made left the side that made it in check. A move that is not
   * an en passant capture and whose origin is not among the given origins cannot, so it is recorded
   * as legal without testing.
   *
   * @param board The board in the position after the move.
   * @param move The packed move just made.
   * @param testedOrigins The origins {@link #testedOrigins} returned before the move.
   * @return True if the side that made the move is in check, false otherwise.
   */
  private static boolean moverInCheck(final Board board, final int move,
                                      final long testedOrigins) {
    final Player mover = board.currentPlayer().getOpponent();
    if (((testedOrigins >>> MoveCode.origin(move)) & 1L) == 0L && !MoveCode.isEnPassant(move)) {
      mover.recordNotInCheck();
      return false;
    }
    return mover.isInCheck();
  }

  /**
   * Returns the static evaluation of a position from the point of view of the side to move.
   *
   * @param board The board position to evaluate.
   * @return The evaluation score, positive when the side to move stands better.
   */
  private double evaluateForSideToMove(final Board board) {
    final double score = getCachedEvaluation(board);
    return board.currentPlayer().getAlliance().isWhite() ? score : -score;
  }

  /**
   * Searches a position with alpha-beta search and the pruning techniques and search extensions
   * of this engine. Scores, bounds and the returned value are all from the point of view of the
   * side to move.
   *
   * @param board The current board position.
   * @param depth The remaining search depth.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @param nullMoveAllowed Whether a null move may be played at this node.
   * @return The score of the position for the side to move.
   */
  private double negamax(final Board board, int depth, double alpha, double beta, int ply,
                         boolean nullMoveAllowed) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;
    if (COLLECT_STATISTICS) {
      statistics().negamaxEntered(depth);
    }
    if (limitReached(stats)) {
      this.searchStopped = true;
    }

    if (ply > 0 && isDrawnByRule(board)) {
      return 0;
    } if (searchStopped) {
      return depth <= 0 ? quiescenceSearch(board, alpha, beta, ply) :
              evaluateForSideToMove(board);
    } if (depth <= 0) {
      return quiescenceSearch(board, alpha, beta, ply);
    } if (ply >= MAX_PLY) {
      return evaluateForSideToMove(board);
    }

    // Read before the transposition probe narrows the window. A zero window built by adding
    // ZERO_WINDOW to a score can come out slightly wider than ZERO_WINDOW.
    final boolean openWindow = beta - alpha > 2 * ZERO_WINDOW;

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null && entry.depth >= depth) {
      final double entryScore = scoreFromTable(entry.score, ply);
      if (entry.nodeType == TranspositionTable.EXACT) {
        if (COLLECT_STATISTICS) {
          statistics().tableCutoff();
        }
        return entryScore;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entryScore);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entryScore);
      }
      if (alpha >= beta) {
        if (COLLECT_STATISTICS) {
          statistics().tableCutoff();
        }
        return entryScore;
      }
    }

    final boolean inCheckAtNode = board.currentPlayer().isInCheck();
    final double staticEval = inCheckAtNode ? Double.NaN : evaluateForSideToMove(board);
    final double[] evals = staticEvals.get();
    evals[ply] = staticEval;
    final boolean improving = !inCheckAtNode && isImproving(evals, ply, staticEval);

    // No static evaluation reaches a mate score, so razoring against a mate bound would send every
    // such node to quiescence, which cannot find a shorter mate that ends in a quiet move.
    if (depth == 1 && !inCheckAtNode && alpha < MATE_THRESHOLD) {
      if (staticEval + RAZOR_MARGIN < alpha) {
        if (COLLECT_STATISTICS) {
          statistics().razored();
        }
        return quiescenceSearch(board, alpha, beta, ply);
      }
    }

    // An improving node is pruned with the margin of one ply less.
    if (depth <= FUTILITY_PRUNING_DEPTH && !openWindow && !inCheckAtNode) {
      if (staticEval >= beta + (improving ? depth - 1 : depth) * FUTILITY_MARGIN) {
        if (COLLECT_STATISTICS) {
          statistics().reverseFutility();
        }
        return staticEval;
      }
    }

    if (depth >= NULL_MOVE_DEPTH && nullMoveAllowed && !openWindow && !inCheckAtNode
            && beta < MATE_THRESHOLD && staticEval >= beta
            && hasNonPawnMaterial(board, board.currentPlayer().getAlliance())) {
      final int R = 3 + depth / 4;
      double nullMoveScore;
      if (COLLECT_STATISTICS) {
        statistics().nullMoveTried();
      }
      board.makeNullMove();
      try {
        nullMoveScore = -negamax(board, depth - 1 - R, -beta, -(beta - ZERO_WINDOW), ply + 1, false);
      } finally {
        board.unmakeNullMove();
      }
      if (nullMoveScore >= beta) {
        final boolean verified = depth < NULL_MOVE_VERIFICATION_DEPTH
                || negamax(board, depth - R, beta - ZERO_WINDOW, beta, ply, false) >= beta;
        if (COLLECT_STATISTICS && depth >= NULL_MOVE_VERIFICATION_DEPTH) {
          statistics().verification(!verified);
        }
        if (verified) {
          if (COLLECT_STATISTICS) {
            statistics().nullMoveCutoff();
          }
          return beta;
        }
      }
    }

    // A node with no move to search first is searched one ply shallower.
    final short ttMoveCode = entry != null ? entry.moveCode : TranspositionTable.NO_MOVE;
    if (COLLECT_STATISTICS) {
      statistics().movesReached(depth, openWindow, ttMoveCode != TranspositionTable.NO_MOVE);
    }
    if (ttMoveCode == TranspositionTable.NO_MOVE && depth >= (openWindow
            ? INTERNAL_REDUCTION_DEPTH : ZERO_WINDOW_INTERNAL_REDUCTION_DEPTH)) {
      depth--;
      if (COLLECT_STATISTICS) {
        statistics().internalReduction();
      }
    }

    double currentAlpha = alpha;
    boolean firstMove = true;
    int bestFoundMove = MoveCode.NONE;
    final short[][] killers = killerMoves.get();
    int movesSearched = 0;
    final short[] quietsSearched = searchedQuiets.get()[ply];
    int quietCount = 0;

    final int moveCount = writeOrderKeys(board, ply);
    final NegamaxBuffers buffers = negamaxBuffers.get();
    final int[] generatedMoves = buffers.generated()[ply];
    final long[] orderKeys = buffers.orderKeys()[ply];
    final int historySide = historySideOf(board);
    final long testedOrigins = testedOrigins(board, inCheckAtNode);

    // The transposition table move is searched first and the other moves follow in ascending key
    // order. The keys are still in generation order here, so the move's index is its key's index.
    boolean ttMoveFirst = false;
    if (ttMoveCode != TranspositionTable.NO_MOVE) {
      final int ttIndex = indexOfMoveCode(generatedMoves, moveCount, ttMoveCode);
      if (ttIndex >= 0) {
        final long ttKey = orderKeys[ttIndex];
        orderKeys[ttIndex] = orderKeys[0];
        orderKeys[0] = ttKey;
        ttMoveFirst = true;
      }
    }

    for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
      // Most nodes cut off within their first few moves, so those are selected one at a time and
      // the keys left after them are sorted only when the search reaches them.
      if (moveIndex < SELECTED_MOVES) {
        if (moveIndex > 0 || !ttMoveFirst) {
          selectSmallestKey(orderKeys, moveIndex, moveCount);
        }
      } else if (moveIndex == SELECTED_MOVES) {
        Arrays.sort(orderKeys, moveIndex, moveCount);
      }
      final long orderKey = orderKeys[moveIndex];
      final int move = generatedMoves[moveIndexOf(orderKey)];
      final boolean capture = MoveCode.isCapture(move);
      if (capture && depth < 3 && movesSearched > 2 &&
              exchangeScoreOf(orderKey) < SEE_PRUNING_THRESHOLD) {
        if (COLLECT_STATISTICS) {
          statistics().exchangeSkip();
        }
        continue;
      }

      // A quiet move cannot find a shorter mate once a mate is the bound, so futility is not
      // applied against a winning mate bound. A pruned move is skipped before it is made, legal
      // or not, so whether it gives check is answered without making it.
      if (((isLateMovePrunable(move, depth, movesSearched, inCheckAtNode)
              && alpha > -MATE_THRESHOLD)
              || (isFutileQuietMove(move, depth, movesSearched, inCheckAtNode,
                      currentAlpha - staticEval) && currentAlpha < MATE_THRESHOLD))
              && !AttackDetector.givesCheck(board, move)) {
        if (COLLECT_STATISTICS) {
          if (isLateMovePrunable(move, depth, movesSearched, inCheckAtNode)
                  && alpha > -MATE_THRESHOLD) {
            statistics().lateMoveSkip();
          } else {
            statistics().futileMoveSkip();
          }
        }
        continue;
      }

      board.makeMove(move);
      if (moverInCheck(board, move, testedOrigins)) {
        if (COLLECT_STATISTICS) {
          statistics().illegalMove();
        }
        board.unmakeMove(move);
        continue;
      }

      final boolean givesCheck = board.currentPlayer().isInCheck();

      double currentValue;
      try {
        int newDepth = depth - 1;
        // A check that evades a check is not extended, so no two consecutive plies are both
        // extended and a chain of checks by both sides loses depth.
        if (givesCheck && !inCheckAtNode) {
          newDepth++;
        }

        if (firstMove) {
          currentValue = -negamax(board, newDepth, -beta, -currentAlpha, ply + 1, true);
        } else {
          int reduction = 0;
          if (depth >= 3 && movesSearched >= 4 && !capture && !inCheckAtNode) {
            reduction = lateMoveReduction(depth, movesSearched + 1);
            // A quiet move with a positive history score is reduced one ply less. The score is read
            // for the side that played the move, captured before the move was made.
            if (reduction > 0 && historyOf(move, historySide) > 0) {
              reduction--;
            }
            // A move at an open-window node is reduced one ply less.
            if (reduction > 0 && openWindow) {
              reduction--;
            }
          }

          if (COLLECT_STATISTICS) {
            statistics().zeroWindowSearch(reduction > 0);
          }
          currentValue = -negamax(board, newDepth - reduction, -(currentAlpha + 0.1), -currentAlpha,
                  ply + 1, true);

          // A reduced search that raises alpha is not evidence at this node's depth, so the move
          // is searched again against the same window at its unreduced depth before its score is
          // allowed to raise alpha or cut this node off. Without this the node would store a
          // bound at its own depth on the strength of a search shallower than that depth.
          if (reduction > 0 && currentValue > currentAlpha) {
            if (COLLECT_STATISTICS) {
              statistics().reductionReSearch();
            }
            currentValue = -negamax(board, newDepth, -(currentAlpha + 0.1), -currentAlpha,
                    ply + 1, true);
          }

          if (currentValue > currentAlpha && currentValue < beta) {
            if (COLLECT_STATISTICS) {
              statistics().windowReSearch();
            }
            currentValue = -negamax(board, newDepth, -beta, -currentAlpha, ply + 1, true);
          }
        }
      } finally {
        board.unmakeMove(move);
      }

      if (currentValue > currentAlpha) {
        currentAlpha = currentValue;
        bestFoundMove = move;

        if (currentAlpha >= beta) {
          if (COLLECT_STATISTICS) {
            statistics().failHigh(depth, movesSearched,
                    cutClassOf(orderKey, moveIndex == 0 && ttMoveFirst));
          }
          if (!capture) {
            final short code = MoveCode.tableCode(move);
            if (code != killers[0][ply]) {
              killers[1][ply] = killers[0][ply];
              killers[0][ply] = code;
            }
            recordHistory(board, move, depth);
            penalizeSearchedQuiets(board, quietsSearched, quietCount, depth);
          }

          recordCounterMove(board, move);

          storeIfSearching(zobristHash, scoreToTable(beta, ply), depth,
                  TranspositionTable.LOWERBOUND, MoveCode.tableCode(bestFoundMove));
          return beta;
        }
      }

      if (!capture && quietCount < MAX_TRACKED_QUIETS) {
        quietsSearched[quietCount++] = MoveCode.tableCode(move);
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
    if (COLLECT_STATISTICS) {
      if (nodeType == TranspositionTable.UPPERBOUND) {
        statistics().failLow(movesSearched);
      } else {
        statistics().exact();
      }
    }
    storeIfSearching(zobristHash, scoreToTable(currentAlpha, ply), depth, nodeType,
            bestFoundMove == MoveCode.NONE ? TranspositionTable.NO_MOVE :
                    MoveCode.tableCode(bestFoundMove));

    return currentAlpha;
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
   * Scores, bounds and the returned value are all from the point of view of the side to move.
   *
   * @param board The current board position.
   * @param alpha The alpha bound.
   * @param beta The beta bound.
   * @param ply The current search ply.
   * @return The quiescence search score of the position for the side to move.
   */
  private double quiescenceSearch(Board board, double alpha, double beta, int ply) {
    SearchStats stats = threadStats.get();
    stats.boardsEvaluated++;
    if (COLLECT_STATISTICS) {
      statistics().quiescenceEntered();
    }
    if (limitReached(stats)) {
      this.searchStopped = true;
    }

    if (searchStopped) {
      return evaluateForSideToMove(board);
    }

    if (ply >= MAX_PLY) {
      return evaluateForSideToMove(board);
    }

    long zobristHash = board.getZobristHash();
    TranspositionTable.Entry entry = transpositionTable.get(zobristHash);
    if (entry != null) {
      final double entryScore = scoreFromTable(entry.score, ply);
      if (entry.nodeType == TranspositionTable.EXACT) {
        if (COLLECT_STATISTICS) {
          statistics().quiescenceTableCutoff();
        }
        return entryScore;
      } else if (entry.nodeType == TranspositionTable.LOWERBOUND) {
        alpha = Math.max(alpha, entryScore);
      } else if (entry.nodeType == TranspositionTable.UPPERBOUND) {
        beta = Math.min(beta, entryScore);
      }
      if (alpha >= beta) {
        if (COLLECT_STATISTICS) {
          statistics().quiescenceTableCutoff();
        }
        return entryScore;
      }
    }

    // A side that is in check cannot decline to move, so the static score is not a bound on what
    // this node can reach and must not narrow the window or cut the node off. Evasions are searched
    // at the ply of this node rather than the next one, because no move was made on the way in.
    if (board.currentPlayer().isInCheck()) {
      if (COLLECT_STATISTICS) {
        statistics().quiescenceInCheck();
      }
      return negamax(board, 1, alpha, beta, ply, true);
    }

    final double originalAlpha = alpha;
    final double originalBeta = beta;

    double standPat = evaluateForSideToMove(board);

    if (standPat >= beta) {
      if (COLLECT_STATISTICS) {
        statistics().standPatCutoff();
      }
      transpositionTable.store(zobristHash, scoreToTable(beta, ply), 0,
              TranspositionTable.LOWERBOUND, TranspositionTable.NO_MOVE);
      return beta;
    }
    if (standPat > alpha) alpha = standPat;

    final QuiescenceBuffers buffers = quiescenceBuffers.get();
    final int[] captures = buffers.moves()[ply];
    final int[] captureSeeScores = buffers.exchangeScores()[ply];
    final long[] orderKeys = buffers.orderKeys()[ply];
    final int captureCount = MoveGenerator.generateQuiescence(board,
            board.currentPlayer().getAlliance(), captures);
    final long testedOrigins = captureCount == 0 ? 0L : testedOrigins(board, false);

    // The static exchange score is resolved once per capture here. Each evaluator call walks every
    // piece on the board, so reading it from inside a comparator costs O(n log n) board scans per
    // node, and the loop below would then scan again for every capture it searches.
    //
    // Bits 30 through 61 of an ordering key hold the static exchange score negated against
    // Integer.MAX_VALUE so higher scores sort first, and bits 0 through 29 hold the source index so
    // equal keys keep move generation order. Every key is non-negative, so sorting the packed
    // values ascending yields the intended move order.
    for (int i = 0; i < captureCount; i++) {
      captureSeeScores[i] = seeEvaluator.evaluate(board, captures[i]);
      final long descendingSee = (long) Integer.MAX_VALUE - (long) captureSeeScores[i];
      orderKeys[i] = (descendingSee << 30) | i;
    }
    Arrays.sort(orderKeys, 0, captureCount);

    for (int order = 0; order < captureCount; order++) {
      final int index = (int) (orderKeys[order] & INDEX_MASK);
      final int move = captures[index];
      final int seeScore = captureSeeScores[index];
      // The exchange score does not count a promotion, so a capture that promotes is never
      // judged by it here, and every other move searched here is a capture.
      final boolean promotion = MoveCode.promotionType(move) != null;
      final double deltaMargin = promotion ? 0 : deltaMarginOf(MoveCode.isEnPassant(move) ?
              Piece.PieceType.PAWN :
              board.getPiece(MoveCode.destination(move)).getPieceType());

      board.makeMove(move);

      final boolean legal = !moverInCheck(board, move, testedOrigins);
      final boolean givesCheck = legal && board.currentPlayer().isInCheck();
      final boolean prunedByExchange = seeScore < SEE_PRUNING_THRESHOLD && !givesCheck;
      final boolean prunedByDelta = !givesCheck && !promotion &&
              standPat + seeScore + deltaMargin <= alpha;

      if (!legal || prunedByExchange || prunedByDelta) {
        if (COLLECT_STATISTICS) {
          if (!legal) {
            statistics().captureIllegal();
          } else if (prunedByExchange) {
            statistics().captureExchangePruned();
          } else {
            statistics().captureDeltaPruned();
          }
        }
        board.unmakeMove(move);
        continue;
      }
      if (COLLECT_STATISTICS) {
        statistics().captureSearched();
      }

      double score;
      try {
        score = -quiescenceSearch(board, -beta, -alpha, ply + 1);
      } finally {
        board.unmakeMove(move);
      }

      if (score > alpha) alpha = score;

      if (alpha >= beta) {
        if (COLLECT_STATISTICS) {
          statistics().captureCutoff();
        }
        storeIfSearching(zobristHash, scoreToTable(beta, ply), 0,
                TranspositionTable.LOWERBOUND, TranspositionTable.NO_MOVE);
        return beta;
      }
    }

    double finalScore = alpha;
    byte nodeType = TranspositionTable.EXACT;
    if (finalScore <= originalAlpha) {
      nodeType = TranspositionTable.UPPERBOUND;
    } else if (finalScore >= originalBeta) {
      nodeType = TranspositionTable.LOWERBOUND;
    }
    storeIfSearching(zobristHash, scoreToTable(finalScore, ply), 0, nodeType,
            TranspositionTable.NO_MOVE);
    return finalScore;
  }

  /**
   * Returns the delta pruning margin for a capture, chosen by the type of the piece it captures.
   * A queen capture has an infinite margin, so it is never delta pruned.
   *
   * @param capturedType The type of the piece the capture takes.
   * @return The margin added to the capture's static exchange score.
   */
  private static double deltaMarginOf(final Piece.PieceType capturedType) {
    return switch (capturedType) {
      case PAWN -> PAWN_CAPTURE_DELTA_MARGIN;
      case KNIGHT, BISHOP -> MINOR_CAPTURE_DELTA_MARGIN;
      case ROOK -> ROOK_CAPTURE_DELTA_MARGIN;
      default -> Double.POSITIVE_INFINITY;
    };
  }

  /**
   * Checks whether a player has non-pawn material remaining on the board.
   * Used to determine if null move pruning is safe to apply.
   *
   * @param board The current board.
   * @param alliance The alliance of the player to check for non-pawn material.
   * @return True if the player has pieces other than pawns and king, false otherwise.
   */
  private boolean hasNonPawnMaterial(final Board board, final Alliance alliance) {
    return (board.getOccupancy(alliance) & ~board.getPieces(alliance, Piece.PieceType.PAWN)
            & ~board.getPieces(alliance, Piece.PieceType.KING)) != 0L;
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
   * Credits a packed move that was found to be good in the history heuristic table, so that later
   * searches order it earlier.
   *
   * @param board The position the move is played from, whose side to move plays it.
   * @param move The packed move to record in the history heuristic.
   * @param depth The depth at which this move was found to be good.
   */
  private void recordHistory(final Board board, final int move, final int depth) {
    addHistory(historySideOf(board), MoveCode.origin(move), MoveCode.destination(move),
            depth * depth);
  }

  /**
   * Penalizes in the history heuristic table each of the given quiet moves, which were searched at
   * a node that a later quiet move cut off, so that later searches order them after the quiet
   * moves that have yet to be refuted.
   *
   * @param board The position the moves are played from, whose side to move plays them.
   * @param quiets The {@link TranspositionTable#moveCode codes} of the quiet moves searched at the
   *               node before the move that cut it off.
   * @param count The number of leading entries of quiets that hold such a move.
   * @param depth The depth at which those moves were searched.
   */
  private void penalizeSearchedQuiets(final Board board, final short[] quiets, final int count,
                                      final int depth) {
    final int malus = -depth * depth;
    final int historySide = historySideOf(board);
    for (int i = 0; i < count; i++) {
      addHistory(historySide, TranspositionTable.originOf(quiets[i]),
              TranspositionTable.destinationOf(quiets[i]), malus);
    }
  }

  /**
   * Adds the given amount to the history heuristic score of a move between the given squares,
   * less that amount's share of the distance the entry has already travelled toward the bound it
   * is moving toward. An entry near the bound therefore barely moves further, while one at the
   * opposite bound takes the amount close to whole, so a move that has been passed over many
   * times can still be rehabilitated by the cutoffs it does produce. The amount is clamped to
   * HISTORY_MAX first, which is what holds an entry within HISTORY_MAX of zero.
   *
   * @param historySide The index of the side that plays the move within the history table.
   * @param origin The square the move leaves.
   * @param destination The square the move arrives on.
   * @param bonus The amount to add, negative to penalize the move.
   */
  private void addHistory(final int historySide, final int origin, final int destination,
                          final int bonus) {
    final int[] destinations = historyHeuristic[historySide][origin];
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
     * @param moveCode The {@link TranspositionTable#moveCode code} of the best move found at the
     *                 position, or {@link TranspositionTable#NO_MOVE} if none was found.
     */
    public void store(long zobristHash, double score, int depth, byte nodeType, short moveCode) {
      int index = (int) (zobristHash & mask) & ~1;

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