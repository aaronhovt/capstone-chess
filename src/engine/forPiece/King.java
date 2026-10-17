package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;

/**
 * The King class represents a king chess piece with specialized movement and castling capabilities.
 * Kings move one square in any direction and are the most important piece on the board, as their
 * capture results in checkmate. This class handles standard king movement, castling rights,
 * and maintains state information about whether the king has moved or castled.
 * <p>
 * Kings can participate in castling moves under specific conditions, including unmoved status,
 * clear paths to rooks, and absence of check.
 *
 * @author Aaron Ho
 */
public final class King extends Piece {

  /** Indicates whether this king has completed a castling move. */
  private final boolean isCastled;

  /** Indicates whether this king retains the right to castle kingside. */
  private final boolean kingSideCastleCapable;

  /** Indicates whether this king retains the right to castle queenside. */
  private final boolean queenSideCastleCapable;

  /**
   * Constructs a King with specified alliance, position, and castling capabilities.
   * This constructor assumes the king has not moved and sets the move count to zero.
   *
   * @param alliance The alliance (color) of the king.
   * @param piecePosition The initial position of the king on the board.
   * @param kingSideCastleCapable Whether the king can castle kingside.
   * @param queenSideCastleCapable Whether the king can castle queenside.
   */
  public King(final Alliance alliance,
              final int piecePosition,
              final boolean kingSideCastleCapable,
              final boolean queenSideCastleCapable) {
    super(PieceType.KING, alliance, piecePosition, true, 0);
    this.isCastled = false;
    this.kingSideCastleCapable = kingSideCastleCapable;
    this.queenSideCastleCapable = queenSideCastleCapable;
  }

  /**
   * Constructs a King with complete state information including move and castling status.
   *
   * @param alliance The alliance (color) of the king.
   * @param piecePosition The current position of the king on the board.
   * @param isFirstMove Whether this is the king's first move.
   * @param isCastled Whether the king has completed a castling move.
   * @param kingSideCastleCapable Whether the king can castle kingside.
   * @param queenSideCastleCapable Whether the king can castle queenside.
   */
  public King(final Alliance alliance,
              final int piecePosition,
              final boolean isFirstMove,
              final boolean isCastled,
              final boolean kingSideCastleCapable,
              final boolean queenSideCastleCapable) {
    super(PieceType.KING, alliance, piecePosition, isFirstMove, 0);
    this.isCastled = isCastled;
    this.kingSideCastleCapable = kingSideCastleCapable;
    this.queenSideCastleCapable = queenSideCastleCapable;
  }

  /**
   * Returns whether this king has completed a castling move.
   *
   * @return True if the king has castled, false otherwise.
   */
  public boolean isCastled() {
    return this.isCastled;
  }

  /**
   * Returns whether this king stands on a square that castling can produce for its alliance,
   * meaning the b, c, g, or h file of its own back rank. This is derived from the king's square
   * alone and does not depend on how the king reached it, so unlike {@link #isCastled()} it is
   * covered by the board's Zobrist hash.
   *
   * @return True if the king stands on a castled square, false otherwise.
   */
  public boolean isOnCastledSquare() {
    final int homeRank = this.pieceAlliance.isWhite() ? 7 : 0;
    if (this.piecePosition / 8 != homeRank) {
      return false;
    }
    final int file = this.piecePosition % 8;
    return file == 1 || file == 2 || file == 6 || file == 7;
  }

  /**
   * Returns whether this king retains the right to castle kingside.
   *
   * @return True if kingside castling is possible, false otherwise.
   */
  public boolean isKingSideCastleCapable() {
    return this.kingSideCastleCapable;
  }

  /**
   * Returns whether this king retains the right to castle queenside.
   *
   * @return True if queenside castling is possible, false otherwise.
   */
  public boolean isQueenSideCastleCapable() {
    return this.queenSideCastleCapable;
  }

  /**
   * Returns the squares this king attacks, being every adjacent square. Castling is not
   * included.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.kingAttacks(this.piecePosition);
  }

  /**
   * Returns the string representation of this king piece.
   *
   * @return The piece type string representation.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }

  /**
   * Calculates the positional bonus for this king based on its current board position.
   * The bonus value is determined by alliance-specific evaluation tables.
   *
   * @param board The current board state.
   * @return The location bonus value for this king's position.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.kingBonus(this.piecePosition, board);
  }

  /**
   * Compares this King with another object for equality.
   * Kings are equal if they have the same basic piece properties and castling status.
   *
   * @param other The object to compare with this King.
   * @return True if the objects are equal, false otherwise.
   */
  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof King king)) {
      return false;
    }
    if (!super.equals(other)) {
      return false;
    }
    return isCastled == king.isCastled;
  }

  /**
   * Calculates the hash code for this King instance.
   * The hash code incorporates the base piece hash code and castling status.
   *
   * @return The hash code value for this King.
   */
  @Override
  public int hashCode() {
    return (31 * super.hashCode()) + (isCastled ? 1 : 0);
  }
}