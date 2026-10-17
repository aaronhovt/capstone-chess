package engine.forPiece;

import engine.Alliance;
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

  /** Array of possible move coordinate offsets for pawn movement and capture patterns. */
  private static final int[] CANDIDATE_MOVE_COORDINATES = { 8, 16, 7, 9 };

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
    long destinations = 0L;

    for (final int currentCandidateOffset: CANDIDATE_MOVE_COORDINATES) {
      final int candidateDestinationCoordinate =
              this.piecePosition + (this.pieceAlliance.getDirection() * currentCandidateOffset);
      if (!BoardUtils.isValidTileCoordinate(candidateDestinationCoordinate)) {
        continue;
      }

      if (currentCandidateOffset == 8 && board.getPiece(candidateDestinationCoordinate) == null) {
        destinations |= 1L << candidateDestinationCoordinate;
      }
      else if (currentCandidateOffset == 16 && this.isFirstMove() &&
              ((BoardUtils.Instance.SecondRow.get(this.piecePosition) && this.pieceAlliance.isBlack()) ||
                      (BoardUtils.SeventhRow.get(this.piecePosition) && this.pieceAlliance.isWhite()))) {
        final int behindCandidateDestinationCoordinate =
                this.piecePosition + (this.pieceAlliance.getDirection() * 8);
        if (board.getPiece(candidateDestinationCoordinate) == null &&
                board.getPiece(behindCandidateDestinationCoordinate) == null) {
          destinations |= 1L << candidateDestinationCoordinate;
        }
      }
      else if (currentCandidateOffset == 7 &&
              !((BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                      (BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()))) {
        final Piece pieceOnCandidate = board.getPiece(candidateDestinationCoordinate);
        if (pieceOnCandidate != null) {
          if (this.pieceAlliance != pieceOnCandidate.getPieceAllegiance()) {
            destinations |= 1L << candidateDestinationCoordinate;
          }
        } else if (board.getEnPassantPawn() != null && board.getEnPassantPawn().getPiecePosition() ==
                (this.piecePosition + (this.pieceAlliance.getOppositeDirection()))) {
          if (this.pieceAlliance != board.getEnPassantPawn().getPieceAllegiance()) {
            destinations |= 1L << candidateDestinationCoordinate;
          }
        }
      }
      else if (currentCandidateOffset == 9 &&
              !((BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                      (BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()))) {
        final Piece pieceOnCandidate = board.getPiece(candidateDestinationCoordinate);
        if (pieceOnCandidate != null) {
          if (this.pieceAlliance != pieceOnCandidate.getPieceAllegiance()) {
            destinations |= 1L << candidateDestinationCoordinate;
          }
        } else if (board.getEnPassantPawn() != null && board.getEnPassantPawn().getPiecePosition() ==
                (this.piecePosition - (this.pieceAlliance.getOppositeDirection()))) {
          if (this.pieceAlliance != board.getEnPassantPawn().getPieceAllegiance()) {
            destinations |= 1L << candidateDestinationCoordinate;
          }
        }
      }
    }

    return destinations;
  }

  /**
   * Determines whether this pawn bears on the given square, which is either of the two squares
   * diagonally ahead of it. Occupancy of the target square is disregarded.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this pawn defends targetSquare, false otherwise.
   */
  @Override
  public boolean defendsSquare(final int targetSquare, final Board board) {
    final int candidateSeven = this.piecePosition + (this.pieceAlliance.getDirection() * 7);
    if (candidateSeven == targetSquare && BoardUtils.isValidTileCoordinate(candidateSeven) &&
            !((BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                    (BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()))) {
      return true;
    }

    final int candidateNine = this.piecePosition + (this.pieceAlliance.getDirection() * 9);
    return candidateNine == targetSquare && BoardUtils.isValidTileCoordinate(candidateNine) &&
            !((BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                    (BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()));
  }

  /**
   * Increments the entry of the given array for every square this pawn defends on the given
   * board, being its two capture squares.
   *
   * @param board The current board.
   * @param defenderCounts The per square counts to increment, of length {@link
   *                       BoardUtils#NUM_TILES}.
   */
  @Override
  public void addDefendedSquares(final Board board, final int[] defenderCounts) {
    final int candidateSeven = this.piecePosition + (this.pieceAlliance.getDirection() * 7);
    if (BoardUtils.isValidTileCoordinate(candidateSeven) &&
            !((BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                    (BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()))) {
      defenderCounts[candidateSeven]++;
    }

    final int candidateNine = this.piecePosition + (this.pieceAlliance.getDirection() * 9);
    if (BoardUtils.isValidTileCoordinate(candidateNine) &&
            !((BoardUtils.Instance.FirstColumn.get(this.piecePosition) && this.pieceAlliance.isWhite()) ||
                    (BoardUtils.Instance.EighthColumn.get(this.piecePosition) && this.pieceAlliance.isBlack()))) {
      defenderCounts[candidateNine]++;
    }
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