package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;

import java.util.*;

import static engine.forBoard.BoardUtils.isValidTileCoordinate;
import static engine.forBoard.MoveUtils.Line;

/**
 * The Bishop class represents a bishop chess piece that moves diagonally across the board.
 * It extends the Piece class and implements the specific movement patterns and behaviors
 * unique to bishops in chess. Bishops are constrained to diagonal movement and remain on
 * the same color squares throughout the game.
 * <p>
 * This class precomputes all possible diagonal move lines for each board position to optimize
 * move generation during gameplay. The implementation handles edge cases where diagonal moves
 * would wrap around the board edges.
 *
 * @author Aaron Ho
 */
public final class Bishop extends Piece {

  /** The diagonal direction offsets representing all possible bishop moves from any position. */
  private final static int[] CANDIDATE_MOVE_COORDINATES = { -9, -7, 7, 9 };

  /** A cache of precomputed legal move lines, indexed by tile position on the board. */
  private final static Line[][] PRECOMPUTED_CANDIDATES = computeCandidates();

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
   * Precomputes all possible diagonal move lines for bishops on each tile of the board.
   * This optimization allows for efficient move generation by calculating move patterns
   * once at initialization rather than during gameplay.
   *
   * @return An array, indexed by board position, of the corresponding diagonal move lines.
   */
  private static Line[][] computeCandidates() {
    Line[][] candidates = new Line[BoardUtils.NUM_TILES][];
    for (int position = 0; position < BoardUtils.NUM_TILES; position++) {
      List<Line> lines = new ArrayList<>();
      for (int offset : CANDIDATE_MOVE_COORDINATES) {
        int destination = position;
        Line line = new Line();
        while (isValidTileCoordinate(destination)) {
          if (isFirstColumnExclusion(destination, offset) ||
                  isEighthColumnExclusion(destination, offset)) {
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
      candidates[position] = lines.toArray(new Line[0]);
    }
    return candidates;
  }

  /**
   * Appends all legal moves for this bishop given the current board state to the given list.
   * The method generates moves along each diagonal line until blocked by a piece
   * or the board edge. Captures are allowed if the blocking piece belongs to the opponent.
   *
   * @param board The current chess board state.
   * @param legalMoves The list to which this bishop's legal moves are appended.
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
            legalMoves.add(new Move.MajorAttackMove(board, this, candidateDestinationCoordinate,
                    pieceAtDestination));
          }
          break;
        }
      }
    }
  }

  /**
   * Determines whether this bishop bears on the given square, walking the same precomputed
   * diagonal lines as {@link #calculateLegalMoves(Board)} but without allocating any moves.
   * Occupancy of the target square is disregarded, while any piece standing strictly between
   * this bishop and the target square blocks the line.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this bishop defends targetSquare, false otherwise.
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
   * Increments the entry of the given array for every square this bishop defends on the given
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
   * Creates a new Bishop instance representing this piece after making the specified move.
   * This method uses the piece pool to efficiently reuse Bishop objects.
   *
   * @param move The move to be executed.
   * @return A new Bishop instance at the destination position.
   */
  @Override
  public Bishop movePiece(final Move move) {
    return PieceUtils.Instance.getMovedBishop(move.getMovedPiece().getPieceAllegiance(), move.getDestinationCoordinate());
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

  /**
   * Determines if a diagonal move from the first column would illegally wrap to the opposite edge.
   * This exclusion prevents bishops on the leftmost column from making certain diagonal moves
   * that would incorrectly place them on the rightmost column.
   *
   * @param position The current position being evaluated.
   * @param offset The diagonal move offset being considered.
   * @return True if this move should be excluded, false otherwise.
   */
  private static boolean isFirstColumnExclusion(final int position,
                                                final int offset) {
    return (BoardUtils.Instance.FirstColumn.get(position) &&
            ((offset == -9) || (offset == 7)));
  }

  /**
   * Determines if a diagonal move from the eighth column would illegally wrap to the opposite edge.
   * This exclusion prevents bishops on the rightmost column from making certain diagonal moves
   * that would incorrectly place them on the leftmost column.
   *
   * @param position The current position being evaluated.
   * @param offset The diagonal move offset being considered.
   * @return True if this move should be excluded, false otherwise.
   */
  private static boolean isEighthColumnExclusion(final int position,
                                                 final int offset) {
    return BoardUtils.Instance.EighthColumn.get(position) &&
            ((offset == -7) || (offset == 9));
  }
}