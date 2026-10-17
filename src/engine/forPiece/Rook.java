package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;

/**
 * The Rook class represents a rook chess piece. It extends the Piece class and defines the specific properties and
 * behaviors of a rook on the chess board. Rooks move horizontally and vertically across the board, able to travel
 * any number of squares in these directions until blocked by another piece or the edge of the board.
 * <p>
 * This class provides methods for calculating legal moves for a rook, including horizontal and vertical moves,
 * as well as capturing opponent pieces.
 *
 * @author Aaron Ho
 */
public final class Rook extends Piece {

  /**
   * Constructs a rook chess piece with the given alliance, starting position, and number of moves.
   * The rook is marked as having made its first move.
   *
   * @param alliance The alliance (color) of the rook (WHITE or BLACK).
   * @param piecePosition The position of the rook on the chessboard.
   * @param numMoves The number of moves made by the rook.
   */
  public Rook(final Alliance alliance, final int piecePosition, final int numMoves) {
    super(PieceType.ROOK, alliance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a rook chess piece with the given alliance, starting position, first move status, and number of moves.
   *
   * @param alliance The alliance (color) of the rook (WHITE or BLACK).
   * @param piecePosition The position of the rook on the chessboard.
   * @param isFirstMove Indicates whether this is the rook's first move.
   * @param numMoves The number of moves made by the rook.
   */
  public Rook(final Alliance alliance,
              final int piecePosition,
              final boolean isFirstMove,
              final int numMoves) {
    super(PieceType.ROOK, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Returns the squares this rook attacks on the given board, being every square along each
   * rank and file direction up to and including the first occupied one.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.rookAttacks(this.piecePosition, board.getOccupancy());
  }

  /**
   * Provides a location bonus value for the rook piece on the board. The bonus is determined based on the rook's
   * current position and alliance, contributing to the evaluation of the piece's strategic importance.
   *
   * @param board The current state of the chess board.
   * @return The location bonus value for the rook.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.rookBonus(this.piecePosition, board);
  }

  /**
   * Returns a string representation of the rook piece.
   *
   * @return A string representation of the rook piece.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }
}