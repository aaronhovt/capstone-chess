package engine.forPiece;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;

/**
 * The Pawn class represents a pawn chess piece with its unique movement and capture rules.
 * Pawns can move forward one square, move forward two squares on their first move, capture
 * diagonally, perform en passant captures, and promote to other pieces upon reaching the
 * opponent's back rank. This class extends the Piece class and implements all pawn-specific
 * behaviors including promotion to queen, rook, bishop, or knight pieces.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Pawn extends Piece {

  /**
   * Constructs a new Pawn instance with the specified alliance and position.
   *
   * @param allegiance The alliance (color) of the pawn.
   * @param piecePosition The current position of the pawn on the board.
   * @param numMoves The number of moves made by the pawn.
   */
  public Pawn(final Alliance allegiance,
              final int piecePosition, final int numMoves) {
    super(PieceType.PAWN, allegiance, piecePosition, true, numMoves);
  }

  /**
   * Constructs a new Pawn instance with the specified alliance, position, and move status.
   *
   * @param alliance The alliance (color) of the pawn.
   * @param piecePosition The current position of the pawn on the board.
   * @param isFirstMove Whether this is the pawn's first move.
   * @param numMoves The number of moves made by the pawn.
   */
  public Pawn(final Alliance alliance,
              final int piecePosition,
              final boolean isFirstMove,
              final int numMoves) {
    super(PieceType.PAWN, alliance, piecePosition, isFirstMove, numMoves);
  }

  /**
   * Calculates the positional bonus for this pawn based on its current board position.
   * The bonus value is determined by alliance-specific pawn position tables that favor
   * advanced and central pawn positions.
   *
   * @param board The current chess board.
   * @return The positional bonus value for this pawn's position.
   */
  @Override
  public int locationBonus(final Board board) {
    return this.pieceAlliance.pawnBonus(this.piecePosition, board);
  }

  /**
   * Returns the destination squares of this pawn's legal moves on the given board, being the
   * single and two-square advances, the diagonal captures, and the en passant capture. A
   * destination on the promotion rank appears once, although one move per promotion piece is
   * generated for it.
   *
   * @param board The current chess board.
   * @return The destination squares, as one bit per square.
   */
  @Override
  public long legalDestinations(final Board board) {
    final long occupancy = board.getOccupancy();
    final int direction = this.pieceAlliance.getDirection();
    final int rank = this.piecePosition / BoardUtils.NUM_TILES_PER_ROW;
    long destinations = 0L;

    final int advance = this.piecePosition + direction * 8;
    if (BoardUtils.isValidTileCoordinate(advance) && (occupancy & (1L << advance)) == 0L) {
      destinations |= 1L << advance;
      final int jump = advance + direction * 8;
      if (this.isFirstMove() && rank == (this.pieceAlliance.isWhite() ? 6 : 1) &&
              (occupancy & (1L << jump)) == 0L) {
        destinations |= 1L << jump;
      }
    }

    final long attacks = attackedSquares(board);
    destinations |= attacks & occupancy & ~board.getOccupancy(this.pieceAlliance);

    final Pawn enPassantPawn = board.getEnPassantPawn();
    if (enPassantPawn != null && enPassantPawn.getPieceAllegiance() != this.pieceAlliance) {
      final int enPassantTarget = enPassantPawn.getPiecePosition() + direction * 8;
      if (BoardUtils.isValidTileCoordinate(enPassantTarget)) {
        destinations |= attacks & ~occupancy & (1L << enPassantTarget);
      }
    }
    return destinations;
  }

  /**
   * Returns the squares this pawn attacks, being the two squares diagonally ahead of it.
   *
   * @param board The current chess board.
   * @return The attacked squares, as one bit per square.
   */
  @Override
  long attackedSquares(final Board board) {
    return AttackDetector.pawnAttacks(this.pieceAlliance, this.piecePosition);
  }

  /**
   * Returns the string representation of this pawn piece.
   *
   * @return The string representation of the pawn piece type.
   */
  @Override
  public String toString() {
    return this.pieceType.toString();
  }
}