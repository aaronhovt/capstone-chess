package engine.forPlayer.forAI;

import engine.forBoard.Board;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The EvaluationCache class provides a thread-safe cache for chess board evaluations to avoid redundant calculations
 * during search operations. It uses a board's Zobrist hash as the cache key, so one entry serves every lookup of a
 * position however deep in the search the position is reached. The cache automatically manages its size by evicting
 * entries when it reaches capacity limits.
 * <p>
 * Entries hold the scores of whichever evaluator produced them, so a cache must be cleared whenever the evaluator in
 * use changes. Each cache belongs to the engine that constructed it, so two engines running at the same time neither
 * share entries nor clear one another's. Cache statistics are maintained to monitor hit rates and performance
 * characteristics.
 *
 * @author Aaron Ho
 */
public class EvaluationCache {

  /** The concurrent hash map storing cached evaluation scores indexed by Zobrist hash. */
  private final ConcurrentHashMap<Long, Double> cache;

  /** The maximum number of entries allowed in the cache before eviction occurs. */
  private static final int MAX_SIZE = 1_000_000;

  /** The number of successful cache lookups (hits). */
  private long hits = 0;

  /** The number of unsuccessful cache lookups (misses). */
  private long misses = 0;

  /**
   * Constructs a new empty EvaluationCache. The table grows on demand up to the maximum size.
   */
  public EvaluationCache() {
    this.cache = new ConcurrentHashMap<>();
  }

  /**
   * Stores an evaluation score in the cache for the specified board position.
   * If the cache is at capacity, entries are automatically evicted before storing the new value.
   *
   * @param board The chess board position being evaluated.
   * @param score The evaluation score to cache.
   */
  public void store(Board board, double score) {
    if (cache.size() >= MAX_SIZE) {
      clearSomeEntries();
    }

    cache.put(board.getZobristHash(), score);
  }

  /**
   * Retrieves a cached evaluation score for the specified board position.
   * Updates cache statistics based on whether the lookup was successful.
   *
   * @param board The chess board position to look up.
   * @return The cached evaluation score, or null if not found in cache.
   */
  public Double probe(Board board) {
    Double result = cache.get(board.getZobristHash());

    if (result != null) {
      hits++;
    } else {
      misses++;
    }

    return result;
  }

  /**
   * Returns a formatted string containing cache performance statistics including entry count,
   * hit rate percentage, and total hits and misses.
   *
   * @return A string representation of cache statistics.
   */
  public String getStats() {
    long total = hits + misses;
    if (total == 0) return "No cache lookups yet";

    double hitRate = (double) hits / total * 100.0;
    return String.format("Cache: %d entries, %.2f%% hit rate (%d hits, %d misses)",
            cache.size(), hitRate, hits, misses);
  }

  /**
   * Clears all entries from the cache and resets hit and miss statistics to zero.
   */
  public void clear() {
    cache.clear();
    hits = 0;
    misses = 0;
  }

  /**
   * Removes approximately half of the cache entries to prevent excessive memory usage.
   * This method is called automatically when the cache reaches its maximum size.
   */
  private void clearSomeEntries() {
    int toRemove = MAX_SIZE / 2;

    int removed = 0;
    for (Long key : cache.keySet()) {
      cache.remove(key);
      removed++;
      if (removed >= toRemove) break;
    }
  }
}
