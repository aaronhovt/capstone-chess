package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.BoardUtils;

/**
 * The PawnSpans class holds the front span of a pawn on each tile: the tiles ahead of it, on its
 * own file and the files beside it, up to and including the rank furthest from its own side. A
 * pawn is passed when no opposing pawn stands in its front span.
 *
 * @author Aaron Ho
 */
final class PawnSpans {

  /** The front span of a white pawn standing on each tile, indexed by tile. */
  private static final long[] WHITE_FRONT_SPANS = computeFrontSpans(Alliance.WHITE);

  /** The front span of a black pawn standing on each tile, indexed by tile. */
  private static final long[] BLACK_FRONT_SPANS = computeFrontSpans(Alliance.BLACK);

  /** Private constructor to prevent instantiation. */
  private PawnSpans() {}

  /**
   * Returns the front span of a pawn of the given alliance standing on the given tile.
   *
   * @param alliance The alliance of the pawn.
   * @param pawnPosition The tile the pawn stands on.
   * @return The front span, as one bit per tile.
   */
  static long frontSpan(final Alliance alliance, final int pawnPosition) {
    return alliance.isWhite() ?
            WHITE_FRONT_SPANS[pawnPosition] :
            BLACK_FRONT_SPANS[pawnPosition];
  }

  /**
   * Builds the table of the front span of a pawn of the given alliance standing on each tile.
   *
   * @param alliance The alliance of the pawn.
   * @return The front span of each tile, indexed by tile, as one bit per tile.
   */
  private static long[] computeFrontSpans(final Alliance alliance) {
    final long[] frontSpans = new long[BoardUtils.NUM_TILES];

    for (int pawnPosition = 0; pawnPosition < BoardUtils.NUM_TILES; pawnPosition++) {
      final int pawnFile = pawnPosition % 8;
      final int pawnRank = pawnPosition / 8;

      for (int rank = 0; rank < 8; rank++) {
        if (alliance.isWhite() ? rank >= pawnRank : rank <= pawnRank) {
          continue;
        }

        for (int file = Math.max(0, pawnFile - 1); file <= Math.min(7, pawnFile + 1); file++) {
          frontSpans[pawnPosition] |= 1L << (rank * 8 + file);
        }
      }
    }

    return frontSpans;
  }
}
