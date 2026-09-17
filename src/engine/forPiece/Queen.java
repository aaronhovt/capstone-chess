package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forBoard.MoveUtils;

import java.util.*;

/**
 * The Queen class represents a queen chess piece, the most powerful piece on the board.
 * Queens can move diagonally, horizontally, and vertically any number of squares, combining
 * the movement patterns of rooks and bishops. This class extends the Piece class and provides
 * methods for calculating legal moves, evaluating positional bonuses, and creating new instances
 * after moves are executed. The implementation uses precomputed movement lines for efficient
 * move generation and integrates with the move pooling system for memory optimization.
 * <p>
 * The queen's movement is restricted by board boundaries and other pieces, following standard
 * chess rules where it cannot jump over pieces and captures by moving to a square occupied
 * by an opponent's piece.
 *
 * @author Aaron Ho
 */
public final class Queen extends Piece {

  /** Array of coordinate offsets representing all possible queen movement directions. */
  private static final int[] CANDIDATE_MOVE_COORDINATES = { -9, -8, -7, -1, 1, 7, 8, 9 };

  /** Empty array constant used when no movement lines are available for a position. */
  private static final MoveUtils.Line[] EMPTY_LINES_ARRAY = new MoveUtils.Line[0];

  /**
   * An array, indexed by board position, of the precomputed movement lines available from that
   * position. This field must be declared after EMPTY_LINES_ARRAY, which its initializer reads.
   */
  private static final MoveUtils.Line[][] PRECOMPUTED_CANDIDATES = computeCandidates();

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
   * Precomputes and returns all possible movement lines for every position on the board.
   * This method calculates valid queen movements considering board boundaries and edge case
   * exclusions to optimize runtime move generation performance.
   *
   * @return An array, indexed by position, of the arrays of movement lines available from that
   *         position.
   */
  private static MoveUtils.Line[][] computeCandidates() {
    MoveUtils.Line[][] candidates = new MoveUtils.Line[BoardUtils.NUM_TILES][];
    for (int position = 0; position < BoardUtils.NUM_TILES; position++) {
      List<MoveUtils.Line> lines = new ArrayList<>();
      for (int offset : CANDIDATE_MOVE_COORDINATES) {
        int destination = position;
        MoveUtils.Line line = new MoveUtils.Line();
        while (BoardUtils.isValidTileCoordinate(destination)) {
          if (isFirstColumnExclusion(destination, offset) || isEightColumnExclusion(destination, offset)) {
            break;
          }
          destination += offset;
          if (BoardUtils.isValidTileCoordinate(destination)) {
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
   * Appends all legal moves the queen can make from its current position to the given list.
   * The method iterates through precomputed movement lines, checking for blocking pieces
   * and generating appropriate move or attack move instances using the move pool.
   *
   * @param board The current chess board state.
   * @param legalMoves The list to which the queen's legal moves are appended.
   */
  @Override
  public void addLegalMoves(final Board board, final List<Move> legalMoves) {
    for (final MoveUtils.Line line : PRECOMPUTED_CANDIDATES[this.piecePosition]) {
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
   * Determines whether this queen bears on the given square, walking the same precomputed
   * lines as {@link #calculateLegalMoves(Board)} but without allocating any moves. Occupancy of
   * the target square is disregarded, while any piece standing strictly between this queen and
   * the target square blocks the line.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this queen defends targetSquare, false otherwise.
   */
  @Override
  public boolean defendsSquare(final int targetSquare, final Board board) {
    final MoveUtils.Line[] lines = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final MoveUtils.Line line : lines) {
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
   * Increments the entry of the given array for every square this queen defends on the given
   * board.
   *
   * @param board The current board.
   * @param defenderCounts The per square counts to increment, of length {@link
   *                       BoardUtils#NUM_TILES}.
   */
  @Override
  public void addDefendedSquares(final Board board, final int[] defenderCounts) {
    final MoveUtils.Line[] lines = PRECOMPUTED_CANDIDATES[this.piecePosition];
    for (final MoveUtils.Line line : lines) {
      for (final int candidateDestinationCoordinate : line.getLineCoordinates()) {
        defenderCounts[candidateDestinationCoordinate]++;
        if (board.getPiece(candidateDestinationCoordinate) != null) {
          break;
        }
      }
    }
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
   * Creates and returns a new queen instance representing the piece after a move is executed.
   * The new instance is retrieved from the piece utility pool for memory efficiency.
   *
   * @param move The move being executed.
   * @return A new queen instance at the move's destination coordinate.
   */
  @Override
  public Queen movePiece(final Move move) {
    return PieceUtils.Instance.getMovedQueen(move.getMovedPiece().getPieceAllegiance(), move.getDestinationCoordinate());
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

  /**
   * Determines if a queen movement from a position with a given offset should be excluded
   * due to first column boundary constraints. Prevents illegal wraparound moves.
   *
   * @param position The current position on the board.
   * @param offset The movement offset being considered.
   * @return True if the move should be excluded, false otherwise.
   */
  private static boolean isFirstColumnExclusion(final int position, final int offset) {
    return BoardUtils.Instance.FirstColumn.get(position) && ((offset == -9) || (offset == -1) || (offset == 7));
  }

  /**
   * Determines if a queen movement from a position with a given offset should be excluded
   * due to eighth column boundary constraints. Prevents illegal wraparound moves.
   *
   * @param position The current position on the board.
   * @param offset The movement offset being considered.
   * @return True if the move should be excluded, false otherwise.
   */
  private static boolean isEightColumnExclusion(final int position, final int offset) {
    return BoardUtils.Instance.EighthColumn.get(position) && ((offset == -7) || (offset == 1) || (offset == 9));
  }
}