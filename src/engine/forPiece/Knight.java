package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;

import java.util.*;

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

  /** An array of possible candidate move coordinates for a knight in its L-shaped move pattern. */
  private final static int[] CANDIDATE_MOVE_COORDINATES = { -17, -15, -10, -6, 6, 10, 15, 17 };

  /**
   * An array, indexed by tile coordinate, of the precomputed legal destination squares for
   * each tile on the board, taking edge cases into consideration.
   */
  private final static int[][] PRECOMPUTED_CANDIDATES = computeCandidates();

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
   * Returns the squares a knight on the given square reaches. The returned array is shared with
   * move generation and must not be modified.
   *
   * @param square The square the knight stands on.
   * @return The squares a knight reaches from that square.
   */
  public static int[] squaresFrom(final int square) {
    return PRECOMPUTED_CANDIDATES[square];
  }

  /**
   * Computes and precomputes the legal destination squares for each tile on the board for the
   * knight. Takes edge cases to exclude illegal move offsets into consideration.
   *
   * @return An array, indexed by tile coordinate, containing the precomputed legal destination
   *         squares for each tile on the board.
   */
  private static int[][] computeCandidates() {
    final int[][] candidates = new int[BoardUtils.NUM_TILES][];
    for (int position = 0; position < BoardUtils.NUM_TILES; position++) {
      final int[] legalOffsets = new int[CANDIDATE_MOVE_COORDINATES.length];
      int numLegalMoves = 0;
      for (final int offset: CANDIDATE_MOVE_COORDINATES) {
        if (isFirstColumnExclusion(position, offset) ||
                isSecondColumnExclusion(position, offset) ||
                isSeventhColumnExclusion(position, offset) ||
                isEighthColumnExclusion(position, offset)) {
          continue;
        }
        final int destination = position + offset;
        if (BoardUtils.isValidTileCoordinate(destination)) {
          legalOffsets[numLegalMoves++] = destination;
        }
      }
      candidates[position] = Arrays.copyOf(legalOffsets, numLegalMoves);
    }
    return candidates;
  }

  /**
   * Appends the legal moves for the knight on the given board to the given list.
   *
   * @param board The current board.
   * @param legalMoves The list to which the knight's legal moves are appended.
   */
  @Override
  public void addLegalMoves(final Board board, final List<Move> legalMoves) {
    for (final int candidateDestinationCoordinate: PRECOMPUTED_CANDIDATES[this.piecePosition]) {
      final Piece pieceAtDestination = board.getPiece(candidateDestinationCoordinate);
      if (pieceAtDestination == null) {
        legalMoves.add(new Move.MajorMove(board, this, candidateDestinationCoordinate));
      } else {
        final Alliance pieceAtDestinationAllegiance = pieceAtDestination.getPieceAllegiance();
        if (this.pieceAlliance != pieceAtDestinationAllegiance) {
          legalMoves.add(new Move.MajorAttackMove(board, this, candidateDestinationCoordinate,
                  pieceAtDestination));
        }
      }
    }
  }

  /**
   * Determines whether this knight bears on the given square, using the same precomputed
   * offsets as {@link #calculateLegalMoves(Board)} but without allocating any moves. Occupancy
   * of the target square is disregarded.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this knight defends targetSquare, false otherwise.
   */
  @Override
  public boolean defendsSquare(final int targetSquare, final Board board) {
    final int[] candidates = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final int candidateDestinationCoordinate : candidates) {
      if (candidateDestinationCoordinate == targetSquare) {
        return true;
      }
    }
    return false;
  }

  /**
   * Increments the entry of the given array for every square this knight defends on the given
   * board.
   *
   * @param board The current board.
   * @param defenderCounts The per square counts to increment, of length {@link
   *                       BoardUtils#NUM_TILES}.
   */
  @Override
  public void addDefendedSquares(final Board board, final int[] defenderCounts) {
    final int[] candidates = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final int candidateDestinationCoordinate : candidates) {
      defenderCounts[candidateDestinationCoordinate]++;
    }
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
   * Creates a new Knight instance after making the given move.
   *
   * @param move The move to be made.
   * @return A new Knight instance after the move is made.
   */
  @Override
  public Knight movePiece(final Move move) {
    return PieceUtils.Instance.getMovedKnight(move.getMovedPiece().getPieceAllegiance(), move.getDestinationCoordinate());
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

  /**
   * Checks if the current position is on the first column and the candidate offset is excluded.
   *
   * @param currentPosition  The current position on the board.
   * @param candidateOffset  The candidate offset to check.
   * @return true if the exclusion criteria are met, otherwise false.
   */
  private static boolean isFirstColumnExclusion(final int currentPosition,
                                                final int candidateOffset) {
    return BoardUtils.Instance.FirstColumn.get(currentPosition) && ((candidateOffset == -17) ||
            (candidateOffset == -10) || (candidateOffset == 6) || (candidateOffset == 15));
  }

  /**
   * Checks if the current position is on the second column and the candidate offset is excluded.
   *
   * @param currentPosition  The current position on the board.
   * @param candidateOffset  The candidate offset to check.
   * @return true if the exclusion criteria are met, otherwise false.
   */
  private static boolean isSecondColumnExclusion(final int currentPosition,
                                                 final int candidateOffset) {
    return BoardUtils.Instance.SecondColumn.get(currentPosition) && ((candidateOffset == -10) || (candidateOffset == 6));
  }

  /**
   * Checks if the current position is on the seventh column and the candidate offset is excluded.
   *
   * @param currentPosition  The current position on the board.
   * @param candidateOffset  The candidate offset to check.
   * @return true if the exclusion criteria are met, otherwise false.
   */
  private static boolean isSeventhColumnExclusion(final int currentPosition,
                                                  final int candidateOffset) {
    return BoardUtils.Instance.SeventhColumn.get(currentPosition) && ((candidateOffset == -6) || (candidateOffset == 10));
  }

  /**
   * Checks if the current position is on the eighth column and the candidate offset is excluded.
   *
   * @param currentPosition  The current position on the board.
   * @param candidateOffset  The candidate offset to check.
   * @return true if the exclusion criteria are met, otherwise false.
   */
  private static boolean isEighthColumnExclusion(final int currentPosition,
                                                 final int candidateOffset) {
    return BoardUtils.Instance.EighthColumn.get(currentPosition) && ((candidateOffset == -15) || (candidateOffset == -6) ||
            (candidateOffset == 10) || (candidateOffset == 17));
  }

}