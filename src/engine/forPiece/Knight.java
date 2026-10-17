package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;

/**
 * The Knight class represents a knight chess piece. It extends the Piece class and defines the specific properties and behaviors
 * of a knight on the chess board. Knights have a unique move pattern, making an L-shaped move that consists of two squares in
 * one direction and one square perpendicular to the first direction. Knights can jump over other pieces, allowing them to
 * control squares that other pieces cannot reach directly.
 * <p>
 * This class provides methods for calculating legal moves for a knight, including standard moves and capturing moves.
 * It also implements the locationBonus method to evaluate a knight's position on the board.
 *
 * @author Aaron Ho
 */
public final class Knight extends Piece {

  /**
   * Constructs a new Knight instance with the given alliance, piece position, and number of moves.
   *
   * @param alliance       The alliance (color) of the knight.
   * @param piecePosition  The current position of the knight on the board.
   * @param numMoves       The number of moves made by the knight.
   */
  public Knight(final Alliance alliance,
                final int piecePosition,
                final int numMoves) {
    super(PieceType.KNIGHT, alliance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a new Knight instance with the given alliance, piece position, first move status, and number of moves.
   *
   * @param alliance       The alliance (color) of the knight.
   * @param piecePosition  The current position of the knight on the board.
   * @param isFirstMove    Whether it's the first move of the knight.
   * @param numMoves       The number of moves made by the knight.
   */
  public Knight(final Alliance alliance,
                final int piecePosition,
                final boolean isFirstMove,
                final int numMoves) {
    super(PieceType.KNIGHT, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Returns the squares this knight attacks, being every square a knight's jump away.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.knightAttacks(this.piecePosition);
  }

  /**
   * Calculates the location bonus for the knight based on its position on the board.
   *
   * @param board The current board state.
   * @return The location bonus for the knight.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.knightBonus(this.piecePosition, board);
  }

  /**
   * Returns a string representation of the knight.
   *
   * @return The string representation of the knight.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }
}