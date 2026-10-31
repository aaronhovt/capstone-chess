package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Piece;

/**
 * AttackDetector answers whether a given square is attacked by a given side. It reads the board's
 * bitboards: the squares a knight, a king or a pawn would attack the square from are each held as
 * a precomputed mask, and the squares a slider would attack it from are looked up in a table per
 * square, indexed by multiplying the occupied squares that could block it by a fixed magic number
 * and keeping the top bits of the product.
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

  /**
   * The squares whose occupancy decides which squares a rook on each square attacks, being its
   * rays without the square at the far end of each, indexed by square.
   */
  private static final long[] ROOK_MASKS = computeMasks(ORTHOGONAL_RAYS);

  /**
   * The squares whose occupancy decides which squares a bishop on each square attacks, being its
   * rays without the square at the far end of each, indexed by square.
   */
  private static final long[] BISHOP_MASKS = computeMasks(DIAGONAL_RAYS);

  /**
   * The multipliers that map every occupancy of each square's {@link #ROOK_MASKS} entry to an
   * index into its part of {@link #ROOK_TABLE}, no two occupancies with different attacks sharing
   * an index, indexed by square.
   */
  private static final long[] ROOK_MAGICS = {
      0x0080018840015420L, 0x0540100420014002L, 0x0100110008402004L, 0x0900100100200408L,
      0x2A00200200080410L, 0x6080040002008001L, 0x4280020000800100L, 0x0180004100002480L,
      0x0020800232400280L, 0x0189402010004001L, 0x0008802000801008L, 0x8082001008204204L,
      0x0022000A00201004L, 0x0804802400020080L, 0x2114001001080204L, 0x0001800500004080L,
      0x8040208000400080L, 0x4110820022420300L, 0x0000808010002002L, 0x0000090010002100L,
      0x0000808004000802L, 0x0002008002040080L, 0x08E0040001100208L, 0x8288060000A24C03L,
      0x8800802080004000L, 0x8090500040002000L, 0x9020010100104020L, 0x200A001200200840L,
      0x020C000808004080L, 0x0002000200100804L, 0x0001002100141200L, 0x0080014200209904L,
      0x0080814001800024L, 0x8410002000404002L, 0x0220A00082803000L, 0x0000080080801000L,
      0x8404008008080040L, 0x4006000402000810L, 0x0801020804005001L, 0x4400800040800100L,
      0x044018C221808000L, 0x1021500320044000L, 0x3006048020120041L, 0x1270008008008010L,
      0x2054000800808004L, 0x40C1000804010002L, 0x05800208410400B0L, 0x0640508061160004L,
      0x202040118000A280L, 0x0020084008802080L, 0x0008204080120200L, 0x4101A30210000900L,
      0x090500C800045100L, 0x000200E4000E8080L, 0x0030500102884400L, 0x1900404401008200L,
      0x8010800010204109L, 0x2020108900244001L, 0x9000084011002001L, 0x1042442100C81001L,
      0x1409000210040801L, 0x0112000811041016L, 0x197A100802008104L, 0x0928840102815422L
  };

  /**
   * The multipliers that map every occupancy of each square's {@link #BISHOP_MASKS} entry to an
   * index into its part of {@link #BISHOP_TABLE}, no two occupancies with different attacks
   * sharing an index, indexed by square.
   */
  private static final long[] BISHOP_MAGICS = {
      0x21120C2C08020020L, 0x0002102101010003L, 0x20040104210CA000L, 0xBC04040088400808L,
      0x0241104100000001L, 0x2131140240401400L, 0x000844306C1000A0L, 0x6014808080904004L,
      0x00412120022A0040L, 0x0800600811404281L, 0x0000100404803800L, 0xC020180600420000L,
      0x0106011040842800L, 0x1000811008040014L, 0x1100020104024020L, 0x90080088A4100200L,
      0x0040809202080128L, 0x004800206800C080L, 0x0008004108010112L, 0x400800040125A040L,
      0x1214006C80A00604L, 0x0202004040502400L, 0x005080A402011048L, 0x00020A0D80A40100L,
      0x08A010282064110CL, 0x1101206008280100L, 0x8080208010008080L, 0x0940802018020120L,
      0x0021004004004040L, 0x0202830012010480L, 0x000C240088860106L, 0x080212000821010AL,
      0x0050042000120200L, 0x4101042080100100L, 0x1008140482100300L, 0x4000020081080080L,
      0x00A4010012440040L, 0x0401C0A5000A0062L, 0x0010010044020240L, 0x2091234200010510L,
      0x0004044440820401L, 0x00004C0220002880L, 0x8000108401001000L, 0x0000314208000380L,
      0x0820180104001441L, 0x111020020C100820L, 0x1410238304108502L, 0xC008090402804C30L,
      0x0C0041041040001BL, 0x2003A88230108021L, 0x0040050041100980L, 0x0010000308480208L,
      0x2020009002022800L, 0x0608081090208004L, 0x0684080898088000L, 0x0204100202002818L,
      0x00C0410808420200L, 0x2014010048020802L, 0x100C0C0042080420L, 0x010808812C840408L,
      0x0009000110221202L, 0x0C00004248010104L, 0x0000125001110C10L, 0x22082004C20D4100L
  };

  /**
   * The right shift that leaves a rook index from a masked occupancy times its magic,
   * indexed by square.
   */
  private static final int[] ROOK_SHIFTS = computeShifts(ROOK_MASKS);

  /**
   * The right shift that leaves a bishop index from a masked occupancy times its magic,
   * indexed by square.
   */
  private static final int[] BISHOP_SHIFTS = computeShifts(BISHOP_MASKS);

  /**
   * The start of each square's part of {@link #ROOK_TABLE}, indexed by square, followed by the
   * table's length.
   */
  private static final int[] ROOK_OFFSETS = computeOffsets(ROOK_MASKS);

  /**
   * The start of each square's part of {@link #BISHOP_TABLE}, indexed by square, followed by the
   * table's length.
   */
  private static final int[] BISHOP_OFFSETS = computeOffsets(BISHOP_MASKS);

  /** The squares a rook attacks, for every square and every occupancy of its mask. */
  private static final long[] ROOK_TABLE = computeTable(ORTHOGONAL_RAYS, ROOK_MASKS, ROOK_MAGICS,
          ROOK_SHIFTS, ROOK_OFFSETS);

  /** The squares a bishop attacks, for every square and every occupancy of its mask. */
  private static final long[] BISHOP_TABLE = computeTable(DIAGONAL_RAYS, BISHOP_MASKS,
          BISHOP_MAGICS, BISHOP_SHIFTS, BISHOP_OFFSETS);

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
    return sliderBears(square, board.getOccupancy(), diagonalSliders, orthogonalSliders);
  }

  /**
   * Determines whether the given move would leave the opponent of the side making it in check,
   * without making the move. The answer is the one making the move and testing the side then to
   * move for check would give, provided that side is not in check before the move, which holds in
   * any position reached by legal play. Whether the move is legal is not considered.
   *
   * @param board The board in the position before the move.
   * @param move The packed move to test, one {@link MoveGenerator} generates in that position.
   * @return True if the move would give check, false otherwise.
   */
  public static boolean givesCheck(final Board board, final int move) {
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    final Piece moved = board.getPiece(origin);
    final Alliance mover = moved.getPieceAllegiance();
    final int kingSquare =
            board.getKing(mover.isWhite() ? Alliance.BLACK : Alliance.WHITE).getPiecePosition();
    final long originBit = 1L << origin;
    final long destinationBit = 1L << destination;
    final Piece.PieceType promotionType = MoveCode.promotionType(move);
    final Piece.PieceType arrivingType =
            promotionType != null ? promotionType : moved.getPieceType();

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
    if (MoveCode.isCastle(move)) {
      final boolean kingSide = destination > origin;
      final long rookOriginBit = 1L << (kingSide ? origin + 3 : origin - 4);
      final long rookDestinationBit = 1L << (kingSide ? origin + 1 : origin - 1);
      occupancy = (occupancy & ~rookOriginBit) | rookDestinationBit;
      orthogonalSliders = (orthogonalSliders & ~rookOriginBit) | rookDestinationBit;
    } else if (MoveCode.isEnPassant(move)) {
      occupancy &= ~(1L << board.getEnPassantPawn().getPiecePosition());
    }

    return sliderBears(kingSquare, occupancy, diagonalSliders, orthogonalSliders);
  }

  /**
   * Returns the pieces of the given side that each stand alone between that side's king and an
   * opposing bishop, rook or queen bearing along the line through them, so that moving the piece
   * off that line would leave the king attacked.
   *
   * @param board The current board.
   * @param side The side whose pinned pieces are returned.
   * @return The squares of the pinned pieces, as one bit per tile.
   */
  public static long pinnedPieces(final Board board, final Alliance side) {
    final Alliance opponent = side.isWhite() ? Alliance.BLACK : Alliance.WHITE;
    final int kingSquare =
            Long.numberOfTrailingZeros(board.getPieces(side, Piece.PieceType.KING));
    final long queens = board.getPieces(opponent, Piece.PieceType.QUEEN);
    final long diagonalSliders = board.getPieces(opponent, Piece.PieceType.BISHOP) | queens;
    final long orthogonalSliders = board.getPieces(opponent, Piece.PieceType.ROOK) | queens;
    final long occupancy = board.getOccupancy();
    final long own = board.getOccupancy(side);
    return pinnedAlong(DIAGONAL_RAYS, DIAGONAL_LINES, kingSquare, occupancy, own,
            diagonalSliders) |
            pinnedAlong(ORTHOGONAL_RAYS, ORTHOGONAL_LINES, kingSquare, occupancy, own,
                    orthogonalSliders);
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
            (bishopAttacks(square, occupancy) & diagonalSliders) |
            (rookAttacks(square, occupancy) & orthogonalSliders);
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
   * Returns the squares a knight on the given square attacks.
   *
   * @param square The knight's square.
   * @return The attacked squares, as one bit per tile.
   */
  public static long knightAttacks(final int square) {
    return KNIGHT_ATTACKERS[square];
  }

  /**
   * Returns the squares a king on the given square attacks, castling aside.
   *
   * @param square The king's square.
   * @return The attacked squares, as one bit per tile.
   */
  public static long kingAttacks(final int square) {
    return KING_ATTACKERS[square];
  }

  /**
   * Returns the squares a pawn of the given alliance on the given square attacks, being the two
   * squares one rank ahead of it and one file to either side.
   *
   * @param alliance The pawn's alliance.
   * @param square The pawn's square.
   * @return The attacked squares, as one bit per tile.
   */
  public static long pawnAttacks(final Alliance alliance, final int square) {
    return alliance.isWhite() ? BLACK_PAWN_ATTACKERS[square] : WHITE_PAWN_ATTACKERS[square];
  }

  /**
   * Returns the squares a bishop on the given square attacks, being every square along each
   * diagonal up to and including the first occupied one.
   *
   * @param square The bishop's square.
   * @param occupancy The occupied squares, as one bit per tile.
   * @return The attacked squares, as one bit per tile.
   */
  public static long bishopAttacks(final int square, final long occupancy) {
    return BISHOP_TABLE[BISHOP_OFFSETS[square] + (int) (((occupancy & BISHOP_MASKS[square]) *
            BISHOP_MAGICS[square]) >>> BISHOP_SHIFTS[square])];
  }

  /**
   * Returns the squares a rook on the given square attacks, being every square along each rank
   * and file direction up to and including the first occupied one.
   *
   * @param square The rook's square.
   * @param occupancy The occupied squares, as one bit per tile.
   * @return The attacked squares, as one bit per tile.
   */
  public static long rookAttacks(final int square, final long occupancy) {
    return ROOK_TABLE[ROOK_OFFSETS[square] + (int) (((occupancy & ROOK_MASKS[square]) *
            ROOK_MAGICS[square]) >>> ROOK_SHIFTS[square])];
  }

  /**
   * Returns the squares along the given rays out of the given square up to and including the
   * first occupied square of each.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @param square The square the rays lead out of.
   * @param occupancy The occupied squares, as one bit per tile.
   * @return The squares reached, as one bit per tile.
   */
  private static long slideAttacks(final long[][] rays, final int square, final long occupancy) {
    long attacks = 0L;
    final long[] squareRays = rays[square];
    for (int direction = 0; direction < squareRays.length; direction++) {
      final long ray = squareRays[direction];
      final long blockers = ray & occupancy;
      if (blockers == 0L) {
        attacks |= ray;
        continue;
      }
      final int nearest = direction < DECREASING_DIRECTIONS ?
              63 - Long.numberOfLeadingZeros(blockers) : Long.numberOfTrailingZeros(blockers);
      attacks |= ray & ~rays[nearest][direction];
    }
    return attacks;
  }

  /**
   * Determines whether any of the given diagonal sliders stands on a diagonal out of the given
   * square, or any of the given orthogonal sliders on a rank or file out of it, with nothing in
   * between.
   *
   * @param square The square the lines lead out of.
   * @param occupancy The occupied squares, as one bit per tile.
   * @param diagonalSliders The squares of the bishops and queens looked for, as one bit per tile.
   * @param orthogonalSliders The squares of the rooks and queens looked for, as one bit per tile.
   * @return True if such a slider bears on the square, false otherwise.
   */
  private static boolean sliderBears(final int square, final long occupancy,
                                     final long diagonalSliders, final long orthogonalSliders) {
    return ((DIAGONAL_LINES[square] & diagonalSliders) != 0L &&
            (bishopAttacks(square, occupancy) & diagonalSliders) != 0L) ||
            ((ORTHOGONAL_LINES[square] & orthogonalSliders) != 0L &&
                    (rookAttacks(square, occupancy) & orthogonalSliders) != 0L);
  }

  /**
   * Returns the given own pieces that stand nearest the given square on one of the given rays out
   * of it, with one of the given sliders the next occupied square beyond them on that ray.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @param lines Every square on those rays out of each square, indexed by square.
   * @param square The square the rays lead out of.
   * @param occupancy The occupied squares, as one bit per tile.
   * @param own The squares of the pieces that can be pinned, as one bit per tile.
   * @param sliders The squares of the sliders that pin, as one bit per tile.
   * @return The squares of the pinned pieces, as one bit per tile.
   */
  private static long pinnedAlong(final long[][] rays, final long[] lines, final int square,
                                  final long occupancy, final long own, final long sliders) {
    if ((lines[square] & sliders) == 0L) {
      return 0L;
    }
    long pinned = 0L;
    final long[] squareRays = rays[square];
    for (int direction = 0; direction < squareRays.length; direction++) {
      final long blockers = squareRays[direction] & occupancy;
      if (blockers == 0L) {
        continue;
      }
      final int nearest = direction < DECREASING_DIRECTIONS ?
              63 - Long.numberOfLeadingZeros(blockers) : Long.numberOfTrailingZeros(blockers);
      if ((own & (1L << nearest)) == 0L) {
        continue;
      }
      final long beyond = rays[nearest][direction] & occupancy;
      if (beyond == 0L) {
        continue;
      }
      final int pinner = direction < DECREASING_DIRECTIONS ?
              63 - Long.numberOfLeadingZeros(beyond) : Long.numberOfTrailingZeros(beyond);
      if ((sliders & (1L << pinner)) != 0L) {
        pinned |= 1L << nearest;
      }
    }
    return pinned;
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
   * Computes, for every square, the given rays out of it without the square at the far end of
   * each, which is attacked whether or not it is occupied.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @return The masks, indexed by square, as one bit per tile.
   */
  private static long[] computeMasks(final long[][] rays) {
    final long[] masks = new long[BoardUtils.NUM_TILES];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      for (int direction = 0; direction < rays[square].length; direction++) {
        final long ray = rays[square][direction];
        final long farthest = direction < DECREASING_DIRECTIONS ? Long.lowestOneBit(ray)
                : Long.highestOneBit(ray);
        masks[square] |= ray & ~farthest;
      }
    }
    return masks;
  }

  /**
   * Computes, for every square, the right shift that keeps as many top bits of a product as the
   * square's mask has squares.
   *
   * @param masks The masks, indexed by square.
   * @return The shifts, indexed by square.
   */
  private static int[] computeShifts(final long[] masks) {
    final int[] shifts = new int[BoardUtils.NUM_TILES];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      shifts[square] = Long.SIZE - Long.bitCount(masks[square]);
    }
    return shifts;
  }

  /**
   * Computes where each square's part of an attack table starts, each part holding one entry per
   * occupancy of the square's mask.
   *
   * @param masks The masks, indexed by square.
   * @return The start of each square's part, indexed by square, followed by the table's length.
   */
  private static int[] computeOffsets(final long[] masks) {
    final int[] offsets = new int[BoardUtils.NUM_TILES + 1];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      offsets[square + 1] = offsets[square] + (1 << Long.bitCount(masks[square]));
    }
    return offsets;
  }

  /**
   * Computes the attack table for a slider moving along the given rays, filling the entry each
   * occupancy of each square's mask indexes with the squares the slider attacks from that square.
   *
   * @param rays The rays out of each square, indexed by square and then by direction.
   * @param masks The masks, indexed by square.
   * @param magics The magic multipliers, indexed by square.
   * @param shifts The right shifts, indexed by square.
   * @param offsets The start of each square's part of the table, followed by the table's length.
   * @return The attack table.
   * @throws IllegalStateException If a magic multiplier indexes the same entry from two
   *         occupancies with different attacks.
   */
  private static long[] computeTable(final long[][] rays, final long[] masks, final long[] magics,
                                     final int[] shifts, final int[] offsets) {
    final long[] table = new long[offsets[BoardUtils.NUM_TILES]];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final long mask = masks[square];
      long subset = 0L;
      do {
        final long attacks = slideAttacks(rays, square, subset);
        final int index = offsets[square] + (int) ((subset * magics[square]) >>> shifts[square]);
        if (table[index] != 0L && table[index] != attacks) {
          throw new IllegalStateException("The magic for square " + square + " collides.");
        }
        table[index] = attacks;
        subset = (subset - mask) & mask;
      } while (subset != 0L);
    }
    return table;
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
