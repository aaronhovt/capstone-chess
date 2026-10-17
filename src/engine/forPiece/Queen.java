package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;

/**
 * The Queen class represents a queen chess piece, the most powerful piece on the board.
 * Queens can move diagonally, horizontally, and vertically any number of squares, combining
 * the movement patterns of rooks and bishops. This class extends the Piece class and provides
 * methods for calculating legal moves, evaluating positional bonuses, and creating new instances
 * after moves are executed.
 * <p>
 * The queen's movement is restricted by board boundaries and other pieces, following standard
 * chess rules where it cannot jump over pieces and captures by moving to a square occupied
 * by an opponent's piece.
 *
 * @author Aaron Ho
 */
public final class Queen extends Piece {

  /**
   * Constructs a queen chess piece with the specified alliance, position, and move count.
   * This constructor assumes this is the queen's first move.
   *
   * @param alliance The alliance (color) of the queen piece.
   * @param piecePosition The initial position of the queen on the board.
   * @param numMoves The number of moves made by the queen.
   */
  public Queen(final Alliance alliance, final int piecePosition, final int numMoves) {
    super(PieceType.QUEEN, alliance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a queen chess piece with the specified alliance, position, first move status, and move count.
   *
   * @param alliance The alliance (color) of the queen piece.
   * @param piecePosition The initial position of the queen on the board.
   * @param isFirstMove True if this is the queen's first move, false otherwise.
   * @param numMoves The number of moves made by the queen.
   */
  public Queen(final Alliance alliance,
               final int piecePosition,
               final boolean isFirstMove,
               final int numMoves) {
    super(PieceType.QUEEN, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Returns the squares this queen attacks on the given board, being every square along each
   * line up to and including the first occupied one.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.bishopAttacks(this.piecePosition, board.getOccupancy()) |
            AttackDetector.rookAttacks(this.piecePosition, board.getOccupancy());
  }

  /**
   * Calculates and returns the positional bonus value for the queen at its current location.
   * The bonus is determined by the alliance-specific evaluation function which considers
   * strategic factors such as centralization and development timing.
   *
   * @param board The current chess board state.
   * @return The positional bonus value for the queen's current position.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.queenBonus(this.piecePosition, board);
  }

  /**
   * Returns the string representation of the queen piece type.
   *
   * @return The string representation of the queen ("Q").
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }
}