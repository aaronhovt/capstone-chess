package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forPiece.Piece;

/**
 * The PawnSpans class holds the front span of a pawn on each tile: the tiles ahead of it, on its
 * own file and the files beside it, up to and including the rank furthest from its own side. A
 * pawn is passed when no opposing pawn stands in its front span. It also reads the placement of
 * one player's pawns, which both evaluators score from.
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
   * The placement of one player's pawns. The arrays belong to this record and must not be modified
   * by a caller. Rank indices run from zero on black's back rank to seven on white's.
   *
   * @param positions The tiles holding the pawns, in ascending tile order.
   * @param occupancy The tiles holding the pawns, as one bit per tile.
   * @param countByFile The number of pawns on each file, indexed by file.
   * @param rearmostRankByFile The rank of the rearmost pawn on each file, indexed by file. A file
   *                           without a pawn holds zero.
   * @param filesByRank The files holding a pawn on each rank, as one bit per file, indexed by rank.
   */
  record Pawns(int[] positions, long occupancy, int[] countByFile, int[] rearmostRankByFile,
               int[] filesByRank) {

    /**
     * Reads the placement of the given alliance's pawns.
     *
     * @param board The board whose pawns are read.
     * @param alliance The alliance whose pawns are read.
     * @return The placement of that alliance's pawns.
     */
    static Pawns of(final Board board, final Alliance alliance) {
      final long occupancy = board.getPieces(alliance, Piece.PieceType.PAWN);
      final boolean isWhite = alliance.isWhite();

      final int[] positions = new int[Long.bitCount(occupancy)];
      final int[] countByFile = new int[8];
      final int[] rearmostRankByFile = new int[8];
      final int[] filesByRank = new int[8];
      int index = 0;

      for (long remaining = occupancy; remaining != 0L; remaining &= remaining - 1) {
        final int position = Long.numberOfTrailingZeros(remaining);
        final int file = position % 8;
        final int rank = position / 8;

        positions[index++] = position;
        filesByRank[rank] |= 1 << file;

        if (countByFile[file] == 0) {
          rearmostRankByFile[file] = rank;
        } else if (isWhite) {
          rearmostRankByFile[file] = Math.max(rearmostRankByFile[file], rank);
        } else {
          rearmostRankByFile[file] = Math.min(rearmostRankByFile[file], rank);
        }

        countByFile[file]++;
      }

      return new Pawns(positions, occupancy, countByFile, rearmostRankByFile, filesByRank);
    }
  }

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
