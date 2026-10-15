package engine.forBoard;

import engine.forPiece.Piece;

/**
 * MoveCode packs a move into an int. Bits 0 to 2 hold the promotion piece type's ordinal plus
 * one, or zero for no promotion; bits 3 to 8 the destination square; bits 9 to 14 the origin
 * square; and bits 16 to 19 the flags {@link #CAPTURE}, {@link #EN_PASSANT}, {@link #CASTLE} and
 * {@link #PAWN_JUMP}. The low sixteen bits are the move's transposition table code, so two moves
 * of a position have the same low sixteen bits exactly when they are equal as {@link Move}s.
 *
 * @author Aaron Ho
 */
public final class MoveCode {

  /** The flag of a move that captures, including an en passant capture. */
  public static final int CAPTURE = 1 << 16;

  /** The flag of an en passant capture. */
  public static final int EN_PASSANT = 1 << 17;

  /** The flag of a castling move, whose origin and destination are the king's. */
  public static final int CASTLE = 1 << 18;

  /** The flag of a pawn's two-square advance. */
  public static final int PAWN_JUMP = 1 << 19;

  /** The value standing for no move, or for the null move, which no packed move equals. */
  public static final int NONE = -1;

  /** The bit position of the origin square. */
  private static final int ORIGIN_SHIFT = 9;

  /** The bit position of the destination square. */
  private static final int DESTINATION_SHIFT = 3;

  /** The mask of a square once shifted down. */
  private static final int SQUARE_MASK = 63;

  /** The mask of the promotion field. */
  private static final int PROMOTION_MASK = 7;

  /** The mask of the transposition table code. */
  private static final int TABLE_CODE_MASK = 0xFFFF;

  /** The piece types, indexed by ordinal. */
  private static final Piece.PieceType[] PIECE_TYPES = Piece.PieceType.values();

  /** Prevents instantiation, since this class holds only static helpers. */
  private MoveCode() {
  }

  /**
   * Packs a move.
   *
   * @param origin The square the move leaves.
   * @param destination The square the move arrives on.
   * @param promotionType The type a pawn promotes to, or null if the move is not a promotion.
   * @param flags Any of {@link #CAPTURE}, {@link #EN_PASSANT}, {@link #CASTLE} and
   *              {@link #PAWN_JUMP}, combined.
   * @return The packed move.
   */
  public static int of(final int origin, final int destination,
                       final Piece.PieceType promotionType, final int flags) {
    return origin << ORIGIN_SHIFT | destination << DESTINATION_SHIFT |
            (promotionType == null ? 0 : promotionType.ordinal() + 1) | flags;
  }

  /**
   * Packs the given move, with the flags its kind carries.
   *
   * @param move The move, or the null move.
   * @return The packed move, or {@link #NONE} for the null move.
   */
  public static int of(final Move move) {
    if (move == Move.MoveFactory.getNullMove()) {
      return NONE;
    }
    final Piece promotion = move.getPromotionPiece();
    int flags = 0;
    if (move.isAttack()) {
      flags |= CAPTURE;
    }
    if (move instanceof Move.PawnEnPassantAttack) {
      flags |= EN_PASSANT;
    }
    if (move.isCastlingMove()) {
      flags |= CASTLE;
    }
    if (move instanceof Move.PawnJump) {
      flags |= PAWN_JUMP;
    }
    return of(move.getCurrentCoordinate(), move.getDestinationCoordinate(),
            promotion == null ? null : promotion.getPieceType(), flags);
  }

  /**
   * Returns the square the move leaves.
   *
   * @param move A packed move.
   * @return The origin square.
   */
  public static int origin(final int move) {
    return (move >>> ORIGIN_SHIFT) & SQUARE_MASK;
  }

  /**
   * Returns the square the move arrives on.
   *
   * @param move A packed move.
   * @return The destination square.
   */
  public static int destination(final int move) {
    return (move >>> DESTINATION_SHIFT) & SQUARE_MASK;
  }

  /**
   * Returns the type a pawn promotes to.
   *
   * @param move A packed move.
   * @return The promotion piece type, or null if the move is not a promotion.
   */
  public static Piece.PieceType promotionType(final int move) {
    final int promotion = move & PROMOTION_MASK;
    return promotion == 0 ? null : PIECE_TYPES[promotion - 1];
  }

  /**
   * Returns the move's transposition table code.
   *
   * @param move A packed move.
   * @return The low sixteen bits of the move.
   */
  public static short tableCode(final int move) {
    return (short) (move & TABLE_CODE_MASK);
  }

  /**
   * Determines whether the move captures, including by en passant.
   *
   * @param move A packed move.
   * @return True if the move captures, false otherwise.
   */
  public static boolean isCapture(final int move) {
    return (move & CAPTURE) != 0;
  }

  /**
   * Determines whether the move is an en passant capture.
   *
   * @param move A packed move.
   * @return True if the move captures en passant, false otherwise.
   */
  public static boolean isEnPassant(final int move) {
    return (move & EN_PASSANT) != 0;
  }

  /**
   * Determines whether the move castles.
   *
   * @param move A packed move.
   * @return True if the move castles, false otherwise.
   */
  public static boolean isCastle(final int move) {
    return (move & CASTLE) != 0;
  }

  /**
   * Determines whether the move is a pawn's two-square advance.
   *
   * @param move A packed move.
   * @return True if the move is a pawn jump, false otherwise.
   */
  public static boolean isPawnJump(final int move) {
    return (move & PAWN_JUMP) != 0;
  }
}
