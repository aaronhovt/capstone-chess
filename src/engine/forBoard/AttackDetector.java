package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Piece;

/**
 * AttackDetector answers whether a given square is attacked by a given side. It reads the board's
 * bitboards: the squares a knight, a king or a pawn would attack the square from are each held as
 * a precomputed mask, and the squares a slider would attack it from are found by walking each ray
 * out of the square to its first occupied square.
 * <p>
 * A sliding piece attacks a square when it stands on one of the rays out of that square with
 * nothing in between. A knight or a king attacks a square when it stands on a square the same
 * step pattern reaches from there, the pattern being its own mirror image. A pawn is the one piece
 * whose pattern is not its own mirror image, so the squares a pawn of each alliance attacks from
 * are tabulated separately per alliance.
 * <p>
 * Whatever occupies the square being tested is disregarded, except that a square held by a piece of
 * the attacking side is reported as unattacked.
 *
 * @author Aaron Ho
 */
public final class AttackDetector {

  /** The number of files on the board, which is also the number of ranks. */
  private static final int BOARD_SIZE = BoardUtils.NUM_TILES_PER_ROW;

  /**
   * The number of leading directions in each step table that step to lower tiles. The rays of
   * those directions find their nearest square as their highest set bit, and the rest as their
   * lowest.
   */
  private static final int DECREASING_DIRECTIONS = 2;

  /** The per step file displacements of the rays a rook or a queen travels along. */
  private static final int[] ORTHOGONAL_FILE_STEPS = { 0, -1, 1, 0 };

  /** The per step rank displacements of the rays a rook or a queen travels along. */
  private static final int[] ORTHOGONAL_RANK_STEPS = { -1, 0, 0, 1 };

  /** The per step file displacements of the rays a bishop or a queen travels along. */
  private static final int[] DIAGONAL_FILE_STEPS = { -1, 1, -1, 1 };

  /** The per step rank displacements of the rays a bishop or a queen travels along. */
  private static final int[] DIAGONAL_RANK_STEPS = { -1, -1, 1, 1 };

  /** The file displacements of the squares a knight reaches, paired with {@link #KNIGHT_RANK_STEPS}. */
  private static final int[] KNIGHT_FILE_STEPS = { -1, 1, -1, 1, -2, 2, -2, 2 };

  /** The rank displacements of the squares a knight reaches, paired with {@link #KNIGHT_FILE_STEPS}. */
  private static final int[] KNIGHT_RANK_STEPS = { -2, -2, 2, 2, -1, -1, 1, 1 };

  /** The file displacements of the squares a king reaches, paired with {@link #KING_RANK_STEPS}. */
  private static final int[] KING_FILE_STEPS = { -1, 0, 1, -1, 1, -1, 0, 1 };

  /** The rank displacements of the squares a king reaches, paired with {@link #KING_FILE_STEPS}. */
  private static final int[] KING_RANK_STEPS = { -1, -1, -1, 0, 0, 1, 1, 1 };

  /**
   * The rays out of each square along which a rook or a queen would stand to attack it, indexed by
   * square and then by direction, each as one bit per tile.
   */
  private static final long[][] ORTHOGONAL_RAYS =
          computeRays(ORTHOGONAL_FILE_STEPS, ORTHOGONAL_RANK_STEPS);

  /**
   * The rays out of each square along which a bishop or a queen would stand to attack it, indexed
   * by square and then by direction, each as one bit per tile.
   */
  private static final long[][] DIAGONAL_RAYS =
          computeRays(DIAGONAL_FILE_STEPS, DIAGONAL_RANK_STEPS);

  /** Every square on the orthogonal rays out of each square, indexed by square. */
  private static final long[] ORTHOGONAL_LINES = computeLines(ORTHOGONAL_RAYS);

  /** Every square on the diagonal rays out of each square, indexed by square. */
  private static final long[] DIAGONAL_LINES = computeLines(DIAGONAL_RAYS);

  /**
   * The squares beyond a second square on the queen line leading out of a first square through
   * it, indexed by first square and then second square, as one bit per tile. Empty when the two
   * squares share no queen line.
   */
  private static final long[][] BEYOND = computeBeyond();

  /** The squares from which a knight attacks each square, indexed by square. */
  private static final long[] KNIGHT_ATTACKERS =
          computeStepAttackers(KNIGHT_FILE_STEPS, KNIGHT_RANK_STEPS);

  /** The squares from which a king attacks each square, indexed by square. */
  private static final long[] KING_ATTACKERS =
          computeStepAttackers(KING_FILE_STEPS, KING_RANK_STEPS);

  /** The squares from which a white pawn attacks each square, indexed by square. */
  private static final long[] WHITE_PAWN_ATTACKERS = computePawnAttackers(Alliance.WHITE);

  /** The squares from which a black pawn attacks each square, indexed by square. */
  private static final long[] BLACK_PAWN_ATTACKERS = computePawnAttackers(Alliance.BLACK);

  /** Prevents instantiation, since this class holds only tables and static queries against them. */
  private AttackDetector() {
  }

  /**
   * Determines whether the given side attacks the given square on the given board. Whatever
   * occupies the square is disregarded, except that a square held by a piece of the attacking side
   * is reported as unattacked.
   *
   * @param square The square to test.
   * @param attackerAlliance The side whose pieces are the candidate attackers.
   * @param board The current board.
   * @return True if the given side attacks the square, false otherwise.
   */
  public static boolean isSquareAttacked(final int square, final Alliance attackerAlliance,
                                         final Board board) {
    if ((board.getOccupancy(attackerAlliance) & (1L << square)) != 0L) {
      return false;
    }
    final long pawnAttackers = attackerAlliance.isWhite() ? WHITE_PAWN_ATTACKERS[square]
            : BLACK_PAWN_ATTACKERS[square];
    if ((pawnAttackers & board.getPieces(attackerAlliance, Piece.PieceType.PAWN)) != 0L ||
            (KNIGHT_ATTACKERS[square] & board.getPieces(attackerAlliance, Piece.PieceType.KNIGHT)) != 0L ||
            (KING_ATTACKERS[square] & board.getPieces(attackerAlliance, Piece.PieceType.KING)) != 0L) {
      return true;
    }
    final long queens = board.getPieces(attackerAlliance, Piece.PieceType.QUEEN);
    final long diagonalSliders = board.getPieces(attackerAlliance, Piece.PieceType.BISHOP) | queens;
    final long orthogonalSliders = board.getPieces(attackerAlliance, Piece.PieceType.ROOK) | queens;
    final long occupancy = board.getOccupancy();
    return sliderBears(DIAGONAL_RAYS, DIAGONAL_LINES, square, occupancy, diagonalSliders) ||
            sliderBears(ORTHOGONAL_RAYS, ORTHOGONAL_LINES, square, occupancy, orthogonalSliders);
  }

  /**
   * Determines whether the given move would leave the opponent of the side making it in check,
   * without making the move. The answer is the one making the move and testing the side then to
   * move for check would give, provided that side is not in check before the move, which holds in
   * any position reached by legal play. Whether the move is legal is not considered.
   *
   * @param board The board the move was generated from, in the position before the move.
   * @param move The move to test.
   * @return True if the move would give check, false otherwise.
   */
  public static boolean givesCheck(final Board board, final Move move) {
    final Alliance mover = move.getMovedPiece().getPieceAllegiance();
    final int kingSquare =
            board.getKing(mover.isWhite() ? Alliance.BLACK : Alliance.WHITE).getPiecePosition();
    final long originBit = 1L << move.getCurrentCoordinate();
    final long destinationBit = 1L << move.getDestinationCoordinate();
    final Piece.PieceType arrivingType = move instanceof Move.PawnPromotion promotion ?
            promotion.promotionPiece.getPieceType() : move.getMovedPiece().getPieceType();

    final long stepOrigins = switch (arrivingType) {
      case PAWN -> mover.isWhite() ? WHITE_PAWN_ATTACKERS[kingSquare]
              : BLACK_PAWN_ATTACKERS[kingSquare];
      case KNIGHT -> KNIGHT_ATTACKERS[kingSquare];
      case KING -> KING_ATTACKERS[kingSquare];
      default -> 0L;
    };
    if ((stepOrigins & destinationBit) != 0L) {
      return true;
    }

    long occupancy = (board.getOccupancy() & ~originBit) | destinationBit;
    final long queens = board.getPieces(mover, Piece.PieceType.QUEEN) & ~originBit;
    long diagonalSliders = (board.getPieces(mover, Piece.PieceType.BISHOP) & ~originBit) | queens;
    long orthogonalSliders = (board.getPieces(mover, Piece.PieceType.ROOK) & ~originBit) | queens;
    if (arrivingType == Piece.PieceType.BISHOP || arrivingType == Piece.PieceType.QUEEN) {
      diagonalSliders |= destinationBit;
    }
    if (arrivingType == Piece.PieceType.ROOK || arrivingType == Piece.PieceType.QUEEN) {
      orthogonalSliders |= destinationBit;
    }
    if (move instanceof Move.CastleMove castle) {
      final long rookOriginBit = 1L << castle.castleRookStart;
      final long rookDestinationBit = 1L << castle.castleRookDestination;
      occupancy = (occupancy & ~rookOriginBit) | rookDestinationBit;
      orthogonalSliders = (orthogonalSliders & ~rookOriginBit) | rookDestinationBit;
    } else if (move instanceof Move.PawnEnPassantAttack) {
      occupancy &= ~(1L << move.getAttackedPiece().getPiecePosition());
    }

    return sliderBears(DIAGONAL_RAYS, DIAGONAL_LINES, kingSquare, occupancy, diagonalSliders) ||
            sliderBears(ORTHOGONAL_RAYS, ORTHOGONAL_LINES, kingSquare, occupancy, orthogonalSliders);
  }

  /**
   * Returns every piece of either alliance that bears on the given square on the given board.
   * Whatever occupies the square is disregarded. A slider is included only when nothing stands
   * between it and the square.
   *
   * @param square The square to test.
   * @param board The current board.
   * @return The squares of the pieces bearing on the square, as one bit per tile.
   */
  public static long attackersOf(final int square, final Board board) {
    final long occupancy = board.getOccupancy();
    final long queens = board.getPieces(Alliance.WHITE, Piece.PieceType.QUEEN) |
            board.getPieces(Alliance.BLACK, Piece.PieceType.QUEEN);
    final long diagonalSliders = board.getPieces(Alliance.WHITE, Piece.PieceType.BISHOP) |
            board.getPieces(Alliance.BLACK, Piece.PieceType.BISHOP) | queens;
    final long orthogonalSliders = board.getPieces(Alliance.WHITE, Piece.PieceType.ROOK) |
            board.getPieces(Alliance.BLACK, Piece.PieceType.ROOK) | queens;
    return (WHITE_PAWN_ATTACKERS[square] & board.getPieces(Alliance.WHITE, Piece.PieceType.PAWN)) |
            (BLACK_PAWN_ATTACKERS[square] & board.getPieces(Alliance.BLACK, Piece.PieceType.PAWN)) |
            (KNIGHT_ATTACKERS[square] & (board.getPieces(Alliance.WHITE, Piece.PieceType.KNIGHT) |
                    board.getPieces(Alliance.BLACK, Piece.PieceType.KNIGHT))) |
            (KING_ATTACKERS[square] & (board.getPieces(Alliance.WHITE, Piece.PieceType.KING) |
                    board.getPieces(Alliance.BLACK, Piece.PieceType.KING))) |
            (nearestOnRays(DIAGONAL_RAYS, square, occupancy) & diagonalSliders) |
            (nearestOnRays(ORTHOGONAL_RAYS, square, occupancy) & orthogonalSliders);
  }

  /**
   * Returns the nearest occupied square beyond the given square on the queen line leading out of
   * the origin square through it.
   *
   * @param origin The square the line leads out of.
   * @param through The square on the line beyond which to look.
   * @param occupancy The occupied squares, as one bit per tile.
   * @return The nearest occupied square beyond {@code through}, or -1 if there is none or the two
   *         squares share no queen line.
   */
  public static int nearestBeyond(final int origin, final int through, final long occupancy) {
    final long beyond = BEYOND[origin][through] & occupancy;
    if (beyond == 0L) {
      return -1;
    }
    return through < origin ?
            63 - Long.numberOfLeadingZeros(beyond) : Long.numberOfTrailingZeros(beyond);
  }

  /**
   * Determines whether the two squares share a rank or a file.
   *
   * @param origin One square.
   * @param other The other square.
   * @return True if the squares are distinct and share a rank or a file, false otherwise.
   */
  public static boolean onOrthogonalLine(final int origin, final int other) {
    return (ORTHOGONAL_LINES[origin] & (1L << other)) != 0L;
  }

  /**
   * Returns the nearest occupied square on each of the given rays out of the given square.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @param square The square the rays lead out of.
   * @param occupancy The occupied squares, as one bit per tile.
   * @return The nearest occupied square on each ray, as one bit per tile.
   */
  private static long nearestOnRays(final long[][] rays, final int square, final long occupancy) {
    long nearest = 0L;
    final long[] squareRays = rays[square];
    for (int direction = 0; direction < squareRays.length; direction++) {
      final long blockers = squareRays[direction] & occupancy;
      if (blockers != 0L) {
        nearest |= 1L << (direction < DECREASING_DIRECTIONS ?
                63 - Long.numberOfLeadingZeros(blockers) : Long.numberOfTrailingZeros(blockers));
      }
    }
    return nearest;
  }

  /**
   * Determines whether any of the given sliders stands at the near end of one of the given rays
   * out of the given square, with nothing in between.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @param lines Every square on those rays out of each square, indexed by square.
   * @param square The square the rays lead out of.
   * @param occupancy The occupied squares, as one bit per tile.
   * @param sliders The squares of the sliders looked for, as one bit per tile.
   * @return True if such a slider bears along one of the rays, false otherwise.
   */
  private static boolean sliderBears(final long[][] rays, final long[] lines, final int square,
                                     final long occupancy, final long sliders) {
    if ((lines[square] & sliders) == 0L) {
      return false;
    }
    final long[] squareRays = rays[square];
    for (int direction = 0; direction < squareRays.length; direction++) {
      final long blockers = squareRays[direction] & occupancy;
      if (blockers == 0L) {
        continue;
      }
      final int nearest = direction < DECREASING_DIRECTIONS ?
              63 - Long.numberOfLeadingZeros(blockers) : Long.numberOfTrailingZeros(blockers);
      if ((sliders & (1L << nearest)) != 0L) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes, for every square, the rays leading out of it in each of the given directions. The
   * first {@link #DECREASING_DIRECTIONS} directions must step to lower tiles and the rest to higher
   * tiles.
   *
   * @param fileSteps The per step file displacement of each direction.
   * @param rankSteps The per step rank displacement of each direction, paired with fileSteps.
   * @return The rays, indexed by square and then by direction, each as one bit per tile.
   * @throws IllegalArgumentException If a direction steps the other way from its position.
   */
  private static long[][] computeRays(final int[] fileSteps, final int[] rankSteps) {
    final long[][] rays = new long[BoardUtils.NUM_TILES][fileSteps.length];
    for (int direction = 0; direction < fileSteps.length; direction++) {
      final boolean decreasing = rankSteps[direction] * BOARD_SIZE + fileSteps[direction] < 0;
      if (decreasing != (direction < DECREASING_DIRECTIONS)) {
        throw new IllegalArgumentException("Direction " + direction + " is out of order.");
      }
    }
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      for (int direction = 0; direction < fileSteps.length; direction++) {
        rays[square][direction] = computeRay(square, fileSteps[direction], rankSteps[direction]);
      }
    }
    return rays;
  }

  /**
   * Computes the ray leading out of the given square in the given direction, stopping at the edge
   * of the board.
   *
   * @param square The square the ray leads out of, which is not itself part of the ray.
   * @param fileStep The per step file displacement of the direction.
   * @param rankStep The per step rank displacement of the direction.
   * @return The squares of the ray, as one bit per tile.
   */
  private static long computeRay(final int square, final int fileStep, final int rankStep) {
    long ray = 0L;
    int file = square % BOARD_SIZE;
    int rank = square / BOARD_SIZE;
    while (true) {
      file += fileStep;
      rank += rankStep;
      if (file < 0 || file >= BOARD_SIZE || rank < 0 || rank >= BOARD_SIZE) {
        break;
      }
      ray |= 1L << (rank * BOARD_SIZE + file);
    }
    return ray;
  }

  /**
   * Computes, for every square, the union of the given rays out of it.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @return Every square on those rays, indexed by square, as one bit per tile.
   */
  private static long[] computeLines(final long[][] rays) {
    final long[] lines = new long[BoardUtils.NUM_TILES];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      for (final long ray : rays[square]) {
        lines[square] |= ray;
      }
    }
    return lines;
  }

  /**
   * Computes, for every pair of squares on a common queen line, the squares on that line beyond
   * the second square as seen from the first.
   *
   * @return The squares beyond, indexed by first square and then second square, as one bit per
   *         tile.
   */
  private static long[][] computeBeyond() {
    final long[][] beyond = new long[BoardUtils.NUM_TILES][BoardUtils.NUM_TILES];
    for (final long[][] rays : new long[][][] { ORTHOGONAL_RAYS, DIAGONAL_RAYS }) {
      for (int origin = 0; origin < BoardUtils.NUM_TILES; origin++) {
        for (int direction = 0; direction < rays[origin].length; direction++) {
          for (long remaining = rays[origin][direction]; remaining != 0L; remaining &= remaining - 1) {
            final int through = Long.numberOfTrailingZeros(remaining);
            beyond[origin][through] = rays[through][direction];
          }
        }
      }
    }
    return beyond;
  }

  /**
   * Computes, for every square, the squares a stepping piece with the given pattern attacks it
   * from. The knight pattern and the king pattern are each their own mirror image, so the squares
   * such a piece attacks a square from are the same squares that pattern reaches from there.
   *
   * @param fileSteps The file displacement of each step in the pattern.
   * @param rankSteps The rank displacement of each step in the pattern, paired with fileSteps.
   * @return The attacking squares, indexed by square, as one bit per tile.
   */
  private static long[] computeStepAttackers(final int[] fileSteps, final int[] rankSteps) {
    final long[] attackers = new long[BoardUtils.NUM_TILES];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final int file = square % BOARD_SIZE;
      final int rank = square / BOARD_SIZE;
      for (int step = 0; step < fileSteps.length; step++) {
        final int originFile = file + fileSteps[step];
        final int originRank = rank + rankSteps[step];
        if (originFile >= 0 && originFile < BOARD_SIZE &&
                originRank >= 0 && originRank < BOARD_SIZE) {
          attackers[square] |= 1L << (originRank * BOARD_SIZE + originFile);
        }
      }
    }
    return attackers;
  }

  /**
   * Computes, for every square, the squares a pawn of the given alliance attacks it from, being the
   * two squares one rank back along that alliance's direction of travel and one file to either
   * side.
   *
   * @param alliance The alliance of the attacking pawns.
   * @return The attacking squares, indexed by square, as one bit per tile.
   */
  private static long[] computePawnAttackers(final Alliance alliance) {
    final long[] attackers = new long[BoardUtils.NUM_TILES];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final int file = square % BOARD_SIZE;
      final int originRank = (square / BOARD_SIZE) - alliance.getDirection();
      if (originRank >= 0 && originRank < BOARD_SIZE) {
        if (file > 0) {
          attackers[square] |= 1L << (originRank * BOARD_SIZE + file - 1);
        }
        if (file < BOARD_SIZE - 1) {
          attackers[square] |= 1L << (originRank * BOARD_SIZE + file + 1);
        }
      }
    }
    return attackers;
  }
}
