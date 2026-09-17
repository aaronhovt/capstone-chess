package engine.forPlayer.forAI;

import engine.forBoard.Move;

/**
 * The TranspositionTable class provides a hash table implementation for storing previously evaluated
 * chess positions during search operations. This optimization technique allows the chess engine to
 * avoid redundant calculations by caching position evaluations along with their search depth and
 * node type information.
 * <p>
 * The transposition table uses Zobrist hashing to uniquely identify board positions and stores
 * evaluation scores with associated metadata including search depth, node type classification,
 * and age information for replacement strategies. This significantly improves search performance
 * by enabling alpha-beta pruning cutoffs and avoiding duplicate work in the search tree.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public class TranspositionTable {

  /** Private constructor to prevent instantiation. */
  private TranspositionTable() {}

  /**
   * The Entry class holds the values of one transposition table slot, as returned by a probe.
   * Each entry carries the position hash, evaluation score, search depth, node classification,
   * best move, and age of the slot it was read from. Changing an entry does not change the table.
   */
  static class Entry {

    /** The Zobrist hash key uniquely identifying the chess position. */
    long key;

    /** The evaluation score for the position at the specified search depth. */
    double score;

    /** The search depth at which this position was evaluated. */
    short depth;

    /** The node type classification indicating the nature of the stored bound. */
    byte nodeType;

    /** The age of this entry used for replacement policy decisions. */
    byte age;

    /** The best move found for this position, or null if none was recorded. */
    Move move;

    /**
     * Constructs a new Entry with default values for all fields.
     * Initializes the entry with zero values for the hash key, score, depth,
     * node type, and age, and a null move.
     */
    Entry() {
      this.key = 0L;
      this.score = 0.0;
      this.depth = 0;
      this.nodeType = 0;
      this.age = 0;
      this.move = null;
    }
  }

  /** Node type constant indicating an exact evaluation score within the alpha-beta window. */
  public static final byte EXACT = 0;

  /** Node type constant indicating a lower bound on the position evaluation. */
  public static final byte LOWERBOUND = 1;

  /** Node type constant indicating an upper bound on the position evaluation. */
  public static final byte UPPERBOUND = 2;
}