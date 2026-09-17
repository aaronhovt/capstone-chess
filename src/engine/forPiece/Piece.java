package engine.forPiece;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.Move;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * The `Piece` class represents a piece on the board in a game of chess. 
 * Each chess piece is characterized by its type (e.g., Pawn, Knight), alliance (color, e.g., WHITE, BLACK), current position
 * on the chessboard, first move status, and the number of moves made. This class encapsulates the common attributes and behaviors
 * of all chess pieces, providing a foundation for specific piece implementations. The class includes methods to retrieve the
 * piece type, alliance, position, first move status, number of moves, and the piece's value based on its type. Additionally,
 * abstract methods are defined for calculating location-based bonuses, moving the piece according to a specified move, and determining
 * legal moves for the piece on a given chessboard. The `equals` and `hashCode` methods are overridden to facilitate
 * comparisons between piece instances based on their properties, ensuring accurate equality checks. The class also
 * incorporates the `PieceType` enum, which defines the various types of chess pieces (Pawn, Knight, Bishop, Rook,
 * Queen, King) along with their associated values. The enum includes methods to retrieve the piece value and determine
 * whether the piece is a minor piece. This class serves as the primary building block for each piece on the board.
 * 
 * @author Aaron Ho
 */
public abstract class Piece {

  /*** The type of the chess piece (e.g., Pawn, Knight). */
  final PieceType pieceType;

  /*** The alliance (color) of the chess piece (e.g., WHITE, BLACK). */
  final Alliance pieceAlliance;

  /*** The current position of the chess piece on the board. */
  final int piecePosition;

  /*** Flag indicating whether it's the first move of the chess piece. */
  private final boolean isFirstMove;

  /*** Number of moves made by the chess piece. */
  private final int numMoves;

  /*** Cached hash code for efficient equality checks. */
  private final int cachedHashCode;

  /**
   * Constructs a new Piece instance with the given type, alliance, position, first move status, and number of moves.
   *
   * @param type           The type of the chess piece.
   * @param alliance       The alliance (color) of the chess piece.
   * @param piecePosition  The current position of the chess piece on the board.
   * @param isFirstMove    Whether it's the first move of the chess piece.
   * @param numMoves       Number of moves made by the chess piece.
   */
  Piece(final PieceType type, final Alliance alliance, final int piecePosition,
        final boolean isFirstMove, final int numMoves) {
    this.pieceType = type;
    this.piecePosition = piecePosition;
    this.pieceAlliance = alliance;
    this.isFirstMove = isFirstMove;
    this.numMoves = numMoves;
    this.cachedHashCode = computeHashCode();
  }

  /**
   * Gets the type of the chess piece.
   *
   * @return The type of the chess piece.
   */
  public PieceType getPieceType() {
    return this.pieceType;
  }

  /**
   * Gets the alliance (color) of the chess piece.
   *
   * @return The alliance of the chess piece.
   */
  public Alliance getPieceAllegiance() {
    return this.pieceAlliance;
  }

  /**
   * Gets the current position of the chess piece on the board.
   *
   * @return The current position of the chess piece.
   */
  public int getPiecePosition() {
    return this.piecePosition;
  }

  /**
   * Checks if it's the first move of the chess piece.
   *
   * @return true if it's the first move, false otherwise.
   */
  public boolean isFirstMove() {
    return this.isFirstMove;
  }

  /**
   * Gets the value of the chess piece in terms of its type.
   *
   * @return The value of the chess piece.
   */
  public int getPieceValue() {
    return this.pieceType.getPieceValue();
  }

  /**
   * Abstract method for calculating a location bonus for the piece. The bonus affects the piece's tactical strength based on its position.
   *
   * @param board The current board.
   * @return The location bonus value for the piece.
   */
  public abstract int locationBonus(final Board board);

  /**
   * Abstract method for moving the piece according to the given move.
   *
   * @param move The move to be made.
   * @return A new Piece instance after the move is made.
   */
  public abstract Piece movePiece(final Move move);

  /**
   * Calculates the legal moves for the piece on the given board.
   *
   * @param board The current board.
   * @return An unmodifiable collection of legal moves for the piece.
   */
  public Collection<Move> calculateLegalMoves(final Board board) {
    final List<Move> legalMoves = new ArrayList<>();
    addLegalMoves(board, legalMoves);
    return Collections.unmodifiableList(legalMoves);
  }

  /**
   * Appends the legal moves for the piece on the given board to the given list, in the same order
   * {@link #calculateLegalMoves(Board)} returns them.
   *
   * @param board The current board.
   * @param legalMoves The list to which the piece's legal moves are appended.
   */
  public abstract void addLegalMoves(final Board board, final List<Move> legalMoves);

  /**
   * Determines whether this piece bears on the given square on the given board, without
   * allocating any {@link Move} objects. Whatever occupies the target square is disregarded, so
   * a square held by a piece of this piece's own alliance is still defended. A sliding piece is
   * blocked by any piece standing strictly between it and the target square.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this piece defends targetSquare, false otherwise.
   */
  public abstract boolean defendsSquare(final int targetSquare, final Board board);

  /**
   * Increments the entry of the given array for every square this piece defends on the given
   * board, which is every square for which {@link #defendsSquare(int, Board)} returns true. The
   * array holds one entry per tile and is indexed by square. A piece never defends its own
   * square, so its own entry is left alone.
   *
   * @param board The current board.
   * @param defenderCounts The per square counts to increment, of length {@link
   *                       BoardUtils#NUM_TILES}.
   */
  public abstract void addDefendedSquares(final Board board, final int[] defenderCounts);

  /**
   * Determines whether this piece attacks the given square on the given board, without
   * allocating any {@link Move} objects. A defended square is attacked unless a piece of this
   * piece's own alliance stands on it, so this mirrors exactly the destinations {@link
   * #calculateLegalMoves(Board)} would produce for this piece.
   *
   * @param targetSquare The square to test.
   * @param board The current board.
   * @return True if this piece attacks targetSquare, false otherwise.
   */
  public boolean attacksSquare(final int targetSquare, final Board board) {
    if (!defendsSquare(targetSquare, board)) {
      return false;
    }
    final Piece pieceAtTarget = board.getPiece(targetSquare);
    return pieceAtTarget == null || pieceAtTarget.getPieceAllegiance() != this.pieceAlliance;
  }

  /**
   * Overrides the `equals` method to compare two pieces for equality.
   *
   * @param other The object to compare with.
   * @return true if the pieces are equal, false otherwise.
   */
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Piece otherPiece)) {
      return false;
    }

    return Objects.equals(this.piecePosition, otherPiece.piecePosition) &&
            Objects.equals(this.pieceType, otherPiece.pieceType) &&
            Objects.equals(this.pieceAlliance, otherPiece.pieceAlliance) &&
            this.isFirstMove == otherPiece.isFirstMove &&
            Objects.equals(this.numMoves, otherPiece.numMoves);
  }

  /**
   * Overrides the `hashCode` method to compute the hash code of the piece based on its properties.
   *
   * @return The hash code of the piece.
   */
  @Override
  public int hashCode() {
    return this.cachedHashCode;
  }

  /**
   * Computes the hash code of the piece based on its properties.
   *
   * @return The computed hash code of the piece.
   */
  private int computeHashCode() {
    return Objects.hash(pieceType, pieceAlliance, piecePosition, isFirstMove, numMoves);
  }

  /*** The PieceType enum defines the types of chess pieces and their associated values. */
  public enum PieceType {

    /*** Represents a pawn with a value of 1. */
    PAWN(100, "P"),
    
    /*** Represents a knight with a value of 3.1 pawns */
    KNIGHT(310, "N"),
    
    /*** Represents a bishop with a value of 3.3 pawns. */
    BISHOP(330, "B"),
    
    /*** Represents a rook with a value of five pawns. */
    ROOK(500, "R"),
    
    /*** Represents a queen with a value of nine pawns. */
    QUEEN(900, "Q"),
    
    /*** Represents a king with a value of 100 pawns. In reality, it is of infinite worth, but 10,000 is high enough. */
    KING(10000, "K");

    /*** The value of the piece. */
    private final int value;

    /*** The name of the piece (e.g., R for rook). */
    private final String pieceName;

    /**
     * Gets the value associated with the piece type.
     *
     * @return The value of the piece type.
     */
    public int getPieceValue() {
      return this.value;
    }

    /**
     * Overrides the `toString` method to provide a human-readable string representation of the piece type.
     *
     * @return The string representation of the piece type.
     */
    @Override
    public String toString() {
      return this.pieceName;
    }

    /**
     * Constructs a new `PieceType` enum value with the specified value and name.
     *
     * @param val       The value associated with the piece type.
     * @param pieceName The short name of the piece type.
     */
    PieceType(final int val,
              final String pieceName) {
      this.value = val;
      this.pieceName = pieceName;
    }

    /**
     * Checks if the piece in question is a minor piece (e.g., bishop or knight).
     *
     * @return True if the piece is a minor piece, false otherwise. 
     */
    public boolean isMinorPiece() {
      return this.getPieceValue() < 400;
    }
  }
}