package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;

import java.util.*;

import static engine.forBoard.BoardUtils.isValidTileCoordinate;
import static engine.forBoard.MoveUtils.Line;

/**
 * The Rook class represents a rook chess piece. It extends the Piece class and defines the specific properties and
 * behaviors of a rook on the chess board. Rooks move horizontally and vertically across the board, able to travel
 * any number of squares in these directions until blocked by another piece or the edge of the board.
 * <p>
 * This class provides methods for calculating legal moves for a rook, including horizontal and vertical moves,
 * as well as capturing opponent pieces. It precomputes valid move lines for each tile on the board to optimize
 * move generation performance during gameplay.
 *
 * @author Aaron Ho
 */
public final class Rook extends Piece {

  /** An array of possible movement offsets for a rook, representing vertical and horizontal directions. */
  private static final int[] CANDIDATE_MOVE_COORDINATES = { -8, -1, 1, 8 };

  /** An empty array used as a default value when creating arrays of Line objects. */
  private static final Line[] EMPTY_LINES_ARRAY = new Line[0];

  /**
   * An array, indexed by tile coordinate, of the precomputed legal move lines for rook movement
   * patterns. This field must be declared after EMPTY_LINES_ARRAY, which its initializer reads.
   */
  private static final Line[][] PRECOMPUTED_CANDIDATES = computeCandidates();

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
   * Computes and precomputes the legal move lines for each tile on the board for rook movement.
   * This method generates all possible horizontal and vertical movement patterns for rooks,
   * taking into account board boundaries and edge exclusions.
   *
   * @return An array, indexed by tile coordinate, containing the precomputed legal move lines
   *         for each tile on the board.
   */
  private static Line[][] computeCandidates() {
    Line[][] candidates = new Line[BoardUtils.NUM_TILES][];
    for (int position = 0; position < BoardUtils.NUM_TILES; position++) {
      List<Line> lines = new ArrayList<>();
      for (int offset : CANDIDATE_MOVE_COORDINATES) {
        int destination = position;
        Line line = new Line();
        while (isValidTileCoordinate(destination)) {
          if (isColumnExclusion(destination, offset)) {
            break;
          }
          destination += offset;
          if (isValidTileCoordinate(destination)) {
            line.addCoordinate(destination);
          }
        }
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
      candidates[position] = lines.toArray(EMPTY_LINES_ARRAY);
    }
    return candidates;
  }

  /**
   * Appends the legal moves that the rook can make on the given chess board to the given list. A rook can move
   * horizontally or vertically across the board, capturing opponent pieces along the way until blocked by a piece or
   * board edge.
   *
   * @param board The current state of the chess board.
   * @param legalMoves The list to which the rook's legal moves are appended.
   */
  @Override
  public void addLegalMoves(final Board board, final List<Move> legalMoves) {
    for (final Line line : PRECOMPUTED_CANDIDATES[this.piecePosition]) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        final Piece pieceAtDestination = board.getPiece(candidateDestinationCoordinate);
        if (pieceAtDestination == null) {
          legalMoves.add(new Move.MajorMove(board, this, candidateDestinationCoordinate));
        } else {
          final Alliance pieceAlliance = pieceAtDestination.getPieceAllegiance();
          if (this.pieceAlliance != pieceAlliance) {
            legalMoves.add(new Move.MajorAttackMove(board, this, candidateDestinationCoordinate, pieceAtDestination));
          }
          break;
        }
      }
    }
  }

  /**
   * Returns the destination squares of this rook's legal moves on the given board, being every
   * square along each line up to the first piece, and that piece's square when it is opposing.
   *
   * @param board The current chess board.
   * @return The destination squares, as one bit per square.
   */
  @Override
  public long legalDestinations(final Board board) {
    long destinations = 0L;
    for (final Line line : PRECOMPUTED_CANDIDATES[this.piecePosition]) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        final Piece pieceAtDestination = board.getPiece(candidateDestinationCoordinate);
        if (pieceAtDestination == null) {
          destinations |= 1L << candidateDestinationCoordinate;
        } else {
          if (this.pieceAlliance != pieceAtDestination.getPieceAllegiance()) {
            destinations |= 1L << candidateDestinationCoordinate;
          }
          break;
        }
      }
    }
    return destinations;
  }
  /**
   * Determines whether this rook bears on the given square, walking the same precomputed
   * lines as {@link #calculateLegalMoves(Board)} but without allocating any moves. Occupancy of
   * the target square is disregarded, while any piece standing strictly between this rook and
   * the target square blocks the line.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this rook defends targetSquare, false otherwise.
   */
  @Override
  public boolean defendsSquare(final int targetSquare, final Board board) {
    final Line[] lines = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final Line line : lines) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        if (candidateDestinationCoordinate == targetSquare) {
          return true;
        }
        if (board.getPiece(candidateDestinationCoordinate) != null) {
          break;
        }
      }
    }
    return false;
  }

  /**
   * Increments the entry of the given array for every square this rook defends on the given
   * board.
   *
   * @param board The current board.
   * @param defenderCounts The per square counts to increment, of length {@link
   *                       BoardUtils#NUM_TILES}.
   */
  @Override
  public void addDefendedSquares(final Board board, final int[] defenderCounts) {
    final Line[] lines = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final Line line : lines) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        defenderCounts[candidateDestinationCoordinate]++;
        if (board.getPiece(candidateDestinationCoordinate) != null) {
          break;
        }
      }
    }
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
   * Creates a new rook piece after a given move is made. This method is used to update the piece's position after a
   * legal move is executed on the board.
   *
   * @param move The move that is being made.
   * @return A new rook piece after the specified move is made on the chessboard.
   */
  @Override
  public Rook movePiece(final Move move) {
    return PieceUtils.Instance.getMovedRook(move.getMovedPiece().getPieceAllegiance(), move.getDestinationCoordinate());
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

  /**
   * Checks if a given position is at the edge of a column on the chessboard and if the given offset would
   * result in an invalid horizontal movement for the rook.
   *
   * @param position The position to check.
   * @param offset The offset value for the movement.
   * @return True if the position is at the column edge and the offset is invalid for horizontal movement, false otherwise.
   */
  private static boolean isColumnExclusion(final int position, final int offset) {
    return (BoardUtils.Instance.FirstColumn.get(position) && (offset == -1)) ||
            (BoardUtils.Instance.EighthColumn.get(position) && (offset == 1));
  }
}