package engine.forPlayer.forAI;

import engine.Alliance;

/**
 * The PawnStructureCache class holds pawn structure scores keyed by the tiles each player's pawns
 * occupy. It is a fixed table of slots indexed by a hash of both occupancies, and each slot holds
 * the occupancies it was stored under, so a lookup never returns the scores of a different pawn
 * placement. A store overwrites whatever slot the occupancies index.
 * <p>
 * Callers may store only scores that depend on nothing but the placement of both players' pawns.
 * Such a score never goes stale, so a cache is never cleared and may be shared by every thread and
 * every engine that uses the evaluator owning it.
 * <p>
 * Reads and writes take no locks. Each slot holds a reference to an immutable entry, so a lookup
 * that runs while another thread writes the same slot returns either a whole entry or a miss.
 *
 * @author Aaron Ho
 */
public class PawnStructureCache {

  /** The number of slots in the table, which is a power of two. */
  private static final int CAPACITY = 1 << 16;

  /** The number of bits in a slot index. */
  private static final int INDEX_BITS = Integer.numberOfTrailingZeros(CAPACITY);

  /** The multiplier spreading the white occupancy across the hash. */
  private static final long WHITE_MULTIPLIER = 0x9E3779B97F4A7C15L;

  /** The multiplier spreading the black occupancy across the hash. */
  private static final long BLACK_MULTIPLIER = 0xC2B2AE3D27D4EB4FL;

  /** The entry held in each slot, or null for a slot that has never been written. */
  private final Entry[] entries;

  /**
   * The pawn structure scores of both players for one placement of pawns.
   *
   * @param whiteOccupancy The tiles holding white's pawns, as one bit per tile.
   * @param blackOccupancy The tiles holding black's pawns, as one bit per tile.
   * @param whiteScore The pawn structure score of white.
   * @param blackScore The pawn structure score of black.
   */
  public record Entry(long whiteOccupancy, long blackOccupancy, double whiteScore,
                      double blackScore) {

    /**
     * Returns the pawn structure score of the given alliance.
     *
     * @param alliance The alliance whose score is requested.
     * @return That alliance's pawn structure score.
     */
    public double scoreOf(final Alliance alliance) {
      return alliance.isWhite() ? whiteScore : blackScore;
    }
  }

  /**
   * Constructs a new empty PawnStructureCache. The table is allocated at its full size and never
   * grows.
   */
  public PawnStructureCache() {
    this.entries = new Entry[CAPACITY];
  }

  /**
   * Stores an entry, replacing whatever the slot for its occupancies already held.
   *
   * @param entry The entry to store.
   */
  public void store(final Entry entry) {
    this.entries[index(entry.whiteOccupancy(), entry.blackOccupancy())] = entry;
  }

  /**
   * Retrieves the entry stored for the given pawn occupancies.
   *
   * @param whiteOccupancy The tiles holding white's pawns, as one bit per tile.
   * @param blackOccupancy The tiles holding black's pawns, as one bit per tile.
   * @return The entry stored for those occupancies, or null if the table does not hold one.
   */
  public Entry probe(final long whiteOccupancy, final long blackOccupancy) {
    final Entry entry = this.entries[index(whiteOccupancy, blackOccupancy)];

    if (entry != null && entry.whiteOccupancy() == whiteOccupancy &&
            entry.blackOccupancy() == blackOccupancy) {
      return entry;
    }

    return null;
  }

  /**
   * Returns the slot index for the given pawn occupancies.
   *
   * @param whiteOccupancy The tiles holding white's pawns, as one bit per tile.
   * @param blackOccupancy The tiles holding black's pawns, as one bit per tile.
   * @return The index of the slot those occupancies map to.
   */
  private static int index(final long whiteOccupancy, final long blackOccupancy) {
    final long hash = whiteOccupancy * WHITE_MULTIPLIER ^ blackOccupancy * BLACK_MULTIPLIER;
    return (int) (hash >>> (Long.SIZE - INDEX_BITS));
  }
}
