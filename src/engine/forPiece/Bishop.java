package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;

/**
 * The Bishop class represents a bishop chess piece that moves diagonally across the board.
 * It extends the Piece class and implements the specific movement patterns and behaviors
 * unique to bishops in chess. Bishops are constrained to diagonal movement and remain on
 * the same color squares throughout the game.
 *
 * @author Aaron Ho
 */
public final class Bishop extends Piece {

  /**
   * Constructs a new Bishop with the specified alliance, position, and move count.
   * The bishop is initialized with first move status set to true.
   *
   * @param alliance The alliance (color) of the bishop.
   * @param piecePosition The initial position of the bishop on the board (0-63).
   * @param numMoves The number of moves made by this bishop.
   */
  public Bishop(final Alliance alliance,
                final int piecePosition,
                final int numMoves) {
    super(PieceType.BISHOP, alliance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a new Bishop with full specification of all attributes.
   *
   * @param alliance The alliance (color) of the bishop.
   * @param piecePosition The position of the bishop on the board (0-63).
   * @param isFirstMove Whether this bishop has made its first move.
   * @param numMoves The number of moves made by this bishop.
   */
  public Bishop(final Alliance alliance,
                final int piecePosition,
                final boolean isFirstMove,
                final int numMoves) {
    super(PieceType.BISHOP, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Returns the squares this bishop attacks on the given board, being every square along each
   * diagonal up to and including the first occupied one.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.bishopAttacks(this.piecePosition, board.getOccupancy());
  }

  /**
   * Calculates the positional bonus for this bishop based on its current location.
   * The bonus value is determined by alliance-specific position tables that favor
   * certain squares for optimal bishop placement.
   *
   * @param board The current chess board state.
   * @return The location bonus value for this bishop's position.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.bishopBonus(this.piecePosition, board);
  }

  /**
   * Returns the string representation of this bishop piece.
   *
   * @return The character "B" representing a bishop.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }
}