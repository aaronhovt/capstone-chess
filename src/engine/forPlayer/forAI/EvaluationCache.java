package engine.forPlayer.forAI;

import java.util.Arrays;

/**
 * The EvaluationCache class provides a thread-safe cache for chess board evaluations to avoid redundant calculations
 * during search operations. It is a fixed table of slots indexed by a board's Zobrist hash, so one entry serves every
 * lookup of a position however deep in the search the position is reached. A store overwrites whatever slot the hash
 * indexes, and a lookup that finds another position in that slot reports a miss.
 * <p>
 * Entries hold the scores of whichever evaluator produced them, so a cache must be cleared whenever the evaluator in
 * use changes. Each cache belongs to the engine that constructed it, so two engines running at the same time neither
 * share entries nor clear one another's. Cache statistics are maintained to monitor hit rates and performance
 * characteristics.
 * <p>
 * Reads and writes take no locks. Each slot records its score twice, once as raw bits and once combined with the
 * position's hash by exclusive or, and a lookup accepts a slot only when the two agree. A lookup that runs while
 * another thread writes the same slot therefore reports a miss rather than a wrong score. A slot that has never been
 * written agrees for the hash zero and reports a score of zero for it.
 *
 * @author Aaron Ho
 */
public class EvaluationCache {

  /** The number of slots in the table, which is a power of two. */
  private static final int CAPACITY = 1 << 20;

  /** The mask reducing a Zobrist hash to a slot index. */
  private static final int INDEX_MASK = CAPACITY - 1;

  /** The value a lookup returns when the position is not in the table. */
  private static final double MISS = Double.NaN;

  /** The Zobrist hash held in each slot, combined with that slot's score bits by exclusive or. */
  private final long[] keys;

  /** The raw bits of the evaluation score held in each slot. */
  private final long[] scores;

  /** The number of successful cache lookups (hits). */
  private long hits = 0;

  /** The number of unsuccessful cache lookups (misses). */
  private long misses = 0;

  /**
   * Constructs a new empty EvaluationCache. The table is allocated at its full size and never grows.
   */
  public EvaluationCache() {
    this.keys = new long[CAPACITY];
    this.scores = new long[CAPACITY];
  }

  /**
   * Stores an evaluation score in the cache for the specified position, replacing whatever the
   * position's slot already held.
   *
   * @param zobristHash The Zobrist hash of the position being evaluated.
   * @param score The evaluation score to cache.
   */
  public void store(final long zobristHash, final double score) {
    final int index = (int) zobristHash & INDEX_MASK;
    final long scoreBits = Double.doubleToRawLongBits(score);

    this.scores[index] = scoreBits;
    this.keys[index] = zobristHash ^ scoreBits;
  }

  /**
   * Retrieves a cached evaluation score for the specified position.
   * Updates cache statistics based on whether the lookup was successful.
   *
   * @param zobristHash The Zobrist hash of the position to look up.
   * @return The cached evaluation score, or NaN if the position is not in the table.
   */
  public double probe(final long zobristHash) {
    final int index = (int) zobristHash & INDEX_MASK;
    final long key = this.keys[index];
    final long score = this.scores[index];

    if ((key ^ score) == zobristHash) {
      this.hits++;
      return Double.longBitsToDouble(score);
    }

    this.misses++;
    return MISS;
  }

  /**
   * Returns a formatted string containing cache performance statistics including slot count,
   * hit rate percentage, and total hits and misses.
   *
   * @return A string representation of cache statistics.
   */
  public String getStats() {
    long total = hits + misses;
    if (total == 0) return "No cache lookups yet";

    double hitRate = (double) hits / total * 100.0;
    return String.format("Cache: %d slots, %.2f%% hit rate (%d hits, %d misses)",
            CAPACITY, hitRate, hits, misses);
  }

  /**
   * Discards every entry in the cache and resets hit and miss statistics to zero.
   */
  public void clear() {
    Arrays.fill(this.keys, 0L);
    Arrays.fill(this.scores, 0L);
    resetStatistics();
  }

  /**
   * Resets hit and miss statistics to zero, leaving cached entries in place.
   */
  public void resetStatistics() {
    this.hits = 0;
    this.misses = 0;
  }
}
