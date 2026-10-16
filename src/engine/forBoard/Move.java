package engine.forBoard;

import engine.forPiece.Pawn;
import engine.forPiece.Piece;
import engine.forPiece.Rook;

/**
 * The Move class represents a move in a game of chess.
 * This abstract class provides the base functionality for all types of chess moves,
 * including standard moves, attack moves, castling, en passant, and pawn promotion.
 * <p>
 * Different types of moves are represented by specialized subclasses, each implementing
 * the specific behavior and rules for that move type.
 * <p>
 * A move is applied to a board through {@link Board#makeMove(Move)}, which applies its packed
 * form, {@link MoveCode#of(Move)}.
 *
 * @author Aaron Ho
 */
public abstract class Move {

  /**
   * The board this move was generated against. Under the mutable board model this is a live
   * board that will have moved on by the time anything reads it back, not a frozen snapshot of
   * the position the move belongs to. It is meaningful only before that board is mutated. In
   * particular, walking backwards through game history via getBoard().getTransitionMove() no
   * longer terminates, since a move's board reports that same move as its transition move.
   */
  protected Board board;

  /** The destination coordinate of the move. */
  protected int destinationCoordinate;

  /** The piece that is being moved. */
  protected Piece movedPiece;

  /** Indicates whether it's the first move for the piece being moved. */
  protected boolean isFirstMove;

  /**
   * Creates a new Move object with the given board, moved piece, and destination coordinate.
   *
   * @param board The current state of the chess board.
   * @param pieceMoved The piece that is being moved.
   * @param destinationCoordinate The destination coordinate of the move.
   */
  protected Move(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
    this.board = board;
    this.destinationCoordinate = destinationCoordinate;
    this.movedPiece = pieceMoved;
    this.isFirstMove = pieceMoved != null && pieceMoved.isFirstMove();
  }

  /**
   * Creates a new Move object with the given board and destination coordinate.
   * This constructor is used for pawn jump moves and for initializing move objects
   * that do not have a moved piece.
   *
   * @param board The current state of the chess board.
   * @param destinationCoordinate The destination coordinate of the move.
   */
  protected Move(final Board board, final int destinationCoordinate) {
    this.board = board;
    this.destinationCoordinate = destinationCoordinate;
    this.movedPiece = null;
    this.isFirstMove = false;
  }

  /**
   * Returns the piece a pawn is promoted to by this move, or null if this move is not a promotion.
   * Promotion is part of move identity, since four distinct legal moves can otherwise share the
   * same pair of squares.
   *
   * @return The promotion piece, or null if this move is not a promotion.
   */
  public Piece getPromotionPiece() {
    return null;
  }

  /**
   * Compares this move to another for equality. Two moves are equal when they leave the same
   * square, arrive at the same square, and promote to the same piece type, which is exactly what
   * identifies a move within a position. The moved piece, the captured piece, the castling rook,
   * and the originating board are deliberately excluded. They are all recoverable from the
   * position, and including the moved piece made the same logical move compare unequal across two
   * positions, because Piece equality covers the piece's move count and first move flag. That is
   * precisely the comparison killer move and countermove matching depends on.
   * <p>
   * This method is final. Per subclass overrides are what made equality asymmetric, with a plain
   * pawn move reporting equal to the promotion decorating it but not the reverse.
   *
   * @param other The other object to compare to.
   * @return True if the moves are equal, false otherwise.
   */
  @Override
  public final boolean equals(final Object other) {
    if (this == other) return true;
    if (!(other instanceof Move otherMove)) return false;
    return getCurrentCoordinate() == otherMove.getCurrentCoordinate() &&
            getDestinationCoordinate() == otherMove.getDestinationCoordinate() &&
            promotionType() == otherMove.promotionType();
  }

  /**
   * Calculates the hash code of the move from the same three values that define equality.
   *
   * @return The calculated hash code.
   */
  @Override
  public final int hashCode() {
    final Piece.PieceType promotion = promotionType();
    return ((getCurrentCoordinate() + 1) << 9) | (getDestinationCoordinate() << 3) |
            (promotion == null ? 0 : promotion.ordinal() + 1);
  }

  /**
   * Returns the type of the promotion piece, or null when this move is not a promotion.
   *
   * @return The promotion piece type, or null.
   */
  private Piece.PieceType promotionType() {
    final Piece promotion = getPromotionPiece();
    return promotion != null ? promotion.getPieceType() : null;
  }

  /**
   * Gets the board this move was generated against. See {@link #board} for the constraints on
   * using it: it is a live mutable board, not a snapshot of the position this move belongs to.
   *
   * @return The board this move was generated against.
   */
  public Board getBoard() {
    return this.board;
  }

  /**
   * Gets the current coordinate of the moved piece.
   *
   * @return The current coordinate, or -1 if no piece is being moved.
   */
  public int getCurrentCoordinate() {
    return this.movedPiece != null ? this.movedPiece.getPiecePosition() : -1;
  }

  /**
   * Gets the destination coordinate of the move.
   *
   * @return The destination coordinate.
   */
  public int getDestinationCoordinate() {
    return this.destinationCoordinate;
  }

  /**
   * Gets the piece that is being moved.
   *
   * @return The moved piece.
   */
  public Piece getMovedPiece() {
    return this.movedPiece;
  }

  /**
   * Checks if the move is an attack.
   * This base implementation returns false; subclasses that represent attack moves
   * should override this method to return true.
   *
   * @return True if the move is an attack, false otherwise.
   */
  public boolean isAttack() {
    return false;
  }

  /**
   * Checks if the move is a castling move.
   * This base implementation returns false; subclasses that represent castling moves
   * should override this method to return true.
   *
   * @return True if the move is a castling move, false otherwise.
   */
  public boolean isCastlingMove() {
    return false;
  }

  /**
   * Gets the attacked piece in case of an attack move.
   * This base implementation returns null; subclasses that represent attack moves
   * should override this method to return the attacked piece.
   *
   * @return The attacked piece, or null if this is not an attack move.
   */
  public Piece getAttackedPiece() {
    return null;
  }

  /**
   * Generates disambiguation information for moves involving pieces of the same type, resolved
   * against the position the move is being played from.
   *
   * @param position The board as it stood immediately before this move was made.
   * @return The disambiguation file character, or an empty string if disambiguation is not needed.
   */
  String disambiguationFile(final Board position) {
    for (final Move move: position.currentPlayer().getLegalMoves()) {
      if (move.getDestinationCoordinate() == this.destinationCoordinate && !this.equals(move) &&
              this.movedPiece.getPieceType().equals(move.getMovedPiece().getPieceType())) {
        return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()).substring(0, 1);
      }
    } return "";
  }

  /**
   * Generates disambiguation information against this move's own board reference. Under the
   * mutable board model that reference is the single live board rather than a frozen snapshot
   * of the position this move belongs to, so this is only meaningful before that board has been
   * mutated. Anything that needs stable notation should use {@link #toNotation(Board)}.
   *
   * @return The disambiguation file character, or an empty string if disambiguation is not needed.
   */
  String disambiguationFile() {
    return disambiguationFile(this.board);
  }

  /**
   * Renders this move in algebraic notation as it reads from the given position. Callers that
   * need notation to stay correct after the board moves on must call this before mutating the
   * board and keep the returned string, since re-rendering later will resolve disambiguation
   * against whatever position the board has since reached.
   *
   * @param position The board as it stood immediately before this move was made.
   * @return The algebraic notation for this move.
   */
  public String toNotation(final Board position) {
    return toString();
  }

  /**
   * The AttackMove class represents a move that results in attacking an opponent's piece in chess.
   * This abstract class provides the base functionality for all types of attack moves.
   */
  static abstract class AttackMove extends Move {

    /** The piece that is attacked as a result of this move. */
    protected Piece attackedPiece;

    /**
     * Constructs an AttackMove instance with the provided board, moving piece, destination coordinate,
     * and the piece being attacked.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The piece that is attacked in this move.
     */
    AttackMove(final Board board, final Piece pieceMoved,
               final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate);
      this.attackedPiece = pieceAttacked;
    }

    /**
     * Gets the piece attacked as a result of this move.
     *
     * @return The attacked piece.
     */
    @Override
    public Piece getAttackedPiece() {
      return this.attackedPiece;
    }

    /**
     * Indicates whether this move is an attack on an opponent's piece.
     *
     * @return True for an attack move, false otherwise.
     */
    @Override
    public boolean isAttack() {
      return true;
    }
  }

  /**
   * The NullMove class represents a placeholder for no move in a chess game.
   * It is used as a sentinel value to indicate the absence of a valid move.
   */
  public static class NullMove extends Move {

    /**
     * Constructs a NullMove instance. A null move has no board or destination coordinate.
     */
    public NullMove() {
      super(null, -1);
    }

    /**
     * Gets the current coordinate, which is set to -1 for a null move.
     *
     * @return The current coordinate, which is -1.
     */
    @Override
    public int getCurrentCoordinate() {
      return -1;
    }

    /**
     * Gets the destination coordinate, which is also set to -1 for a null move.
     *
     * @return The destination coordinate, which is -1.
     */
    @Override
    public int getDestinationCoordinate() {
      return -1;
    }

    /**
     * Gets a string representation of the null move.
     *
     * @return The string "Null Move."
     */
    @Override
    public String toString() {
      return "Null Move";
    }
  }

  /**
   * The MajorMove class represents a standard major move in chess. This move is typically
   * associated with major pieces (queen, rook, knight, or bishop) and involves the piece
   * moving to a new destination coordinate on the board without capturing any opponent's piece.
   */
  public static class MajorMove extends Move {

    /**
     * Constructs a MajorMove instance with the provided board, piece, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The major piece (queen, rook, bishop, or knight) that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public MajorMove(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Generates a string representation of the major move.
     *
     * @return The string representing the move, e.g., "Qd4" for a queen move to d4.
     */
    @Override
    public String toString() {
      return movedPiece.getPieceType().toString() + disambiguationFile() +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }

    /**
     * Generates a string representation of the major move, disambiguated against the given position.
     *
     * @param position The board as it stood immediately before this move was made.
     * @return The string representing the move, e.g., "Qd4" for a queen move to d4.
     */
    @Override
    public String toNotation(final Board position) {
      return movedPiece.getPieceType().toString() + disambiguationFile(position) +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The MajorAttackMove class represents a major attack move in chess. This type of move
   * involves a major piece (queen, rook, knight, or bishop) moving to a new destination
   * coordinate and capturing an opponent's piece.
   */
  public static class MajorAttackMove extends AttackMove {

    /**
     * Constructs a MajorAttackMove instance with the provided board, moving piece,
     * destination coordinate, and the attacked piece.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The major piece (queen, rook, bishop, or knight) that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's piece that is being attacked and captured.
     */
    public MajorAttackMove(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }

    /**
     * Generates a string representation of the major attack move.
     *
     * @return The string representing the move, e.g., "Qxd4" for a queen capturing a piece on d4.
     */
    @Override
    public String toString() {
      return movedPiece.getPieceType() + disambiguationFile() + "x" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }

    /**
     * Generates a string representation of the major attack move, disambiguated against the
     * given position.
     *
     * @param position The board as it stood immediately before this move was made.
     * @return The string representing the move, e.g., "Qxd4" for a queen capturing a piece on d4.
     */
    @Override
    public String toNotation(final Board position) {
      return movedPiece.getPieceType() + disambiguationFile(position) + "x" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnMove class represents a basic pawn move in chess. This move involves a pawn
   * advancing to a new destination coordinate without capturing any opponent's piece.
   */
  public static class PawnMove extends Move {

    /**
     * Constructs a PawnMove instance with the provided board, moving piece, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public PawnMove(final Board board, final Piece pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Generates a string representation of the pawn move.
     *
     * @return The string representing the move, e.g., "e4" for a pawn moving to e4.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnAttackMove class represents a pawn attack move in chess. This type of move
   * involves a pawn moving to a new destination coordinate and capturing an opponent's piece.
   */
  public static class PawnAttackMove extends AttackMove {

    /**
     * Constructs a PawnAttackMove instance with the provided board, moving piece,
     * destination coordinate, and the attacked piece.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's piece that is being attacked and captured.
     */
    public PawnAttackMove(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }

    /**
     * Generates a string representation of the pawn attack move.
     *
     * @return The string representing the move, e.g., "exd4" for a pawn capturing a piece on d4.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()).charAt(0) + "x" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnJump class represents a pawn move where a pawn advances two squares from its starting position.
   * This special move is only available on a pawn's first move and creates an en passant opportunity
   * for opponent pawns on adjacent files.
   */
  public static class PawnJump extends Move {

    /**
     * Constructs a PawnJump instance with the provided board, moving pawn, and destination coordinate.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     */
    public PawnJump(final Board board, final Pawn pieceMoved, final int destinationCoordinate) {
      super(board, pieceMoved, destinationCoordinate);
    }

    /**
     * Returns a string representation of the PawnJump move, showing only the destination coordinate.
     *
     * @return A string representing the PawnJump move.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.destinationCoordinate);
    }
  }

  /**
   * The PawnEnPassantAttack class represents a special pawn attack move called "en passant" in chess.
   * En passant is a situation where a pawn captures an opponent's pawn that has moved two squares
   * forward from its starting position, as if the opponent's pawn had moved only one square.
   */
  public static class PawnEnPassantAttack extends PawnAttackMove {

    /**
     * Constructs a PawnEnPassantAttack instance with the provided board,
     * moving a piece, destination coordinate, and piece attacked.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The pawn piece that is moved.
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param pieceAttacked         The opponent's pawn piece that is attacked en passant.
     */
    public PawnEnPassantAttack(final Board board, final Piece pieceMoved, final int destinationCoordinate, final Piece pieceAttacked) {
      super(board, pieceMoved, destinationCoordinate, pieceAttacked);
    }
  }

  /**
   * The PawnPromotion class represents a special move in chess. When a pawn reaches
   * the opponent's back rank, it can be promoted to any other chess piece (queen, rook,
   * bishop, or knight). This class extends a standard PawnMove by adding promotion information.
   */
  public static class PawnPromotion extends PawnMove {

    /** The decorated move that represents the original pawn move. */
    protected Move decoratedMove;

    /** The piece to which the pawn is promoted (queen, rook, bishop, or knight). */
    protected Piece promotionPiece;

    /**
     * Constructs a PawnPromotion instance.
     *
     * @param decoratedMove    The decorated move representing the original pawn move.
     * @param promotionPiece   The piece to which the pawn is promoted (queen, rook, bishop, or knight).
     */
    public PawnPromotion(final Move decoratedMove, final Piece promotionPiece) {
      super(decoratedMove != null ? decoratedMove.getBoard() : null,
              decoratedMove != null ? decoratedMove.getMovedPiece() : null,
              decoratedMove != null ? decoratedMove.getDestinationCoordinate() : -1);
      this.decoratedMove = decoratedMove;
      this.promotionPiece = promotionPiece;
    }

    /**
     * Checks if the pawn promotion move is an attack on an opponent's piece.
     *
     * @return True if it's an attack, false otherwise.
     */
    @Override
    public boolean isAttack() {
      return this.decoratedMove.isAttack();
    }

    /**
     * Gets the piece attacked by this pawn promotion move.
     *
     * @return The attacked piece, or null if it's not an attack move.
     */
    @Override
    public Piece getAttackedPiece() {
      return this.decoratedMove.getAttackedPiece();
    }

    /**
     * Generates a string representation of the pawn promotion move.
     *
     * @return The string representing the move, e.g., "e8=Q" for a pawn promoting to a queen.
     */
    @Override
    public String toString() {
      return BoardUtils.getPositionAtCoordinate(this.movedPiece.getPiecePosition()) + "-" +
              BoardUtils.getPositionAtCoordinate(this.destinationCoordinate) + "=" + this.promotionPiece.getPieceType();
    }

    /**
     * Returns the piece this pawn promotes to.
     *
     * @return The promotion piece.
     */
    @Override
    public Piece getPromotionPiece() {
      return this.promotionPiece;
    }
  }

  /**
   * The CastleMove class represents a move that involves castling, which is a special king and rook move.
   * This abstract class provides the base functionality for king-side and queen-side castling moves.
   */
  static abstract class CastleMove extends Move {

    /** The rook involved in the castling move. */
    protected Rook castleRook;

    /** The starting position of the castle rook. */
    protected int castleRookStart;

    /** The destination position of the castle rook. */
    protected int castleRookDestination;

    /**
     * Constructs a CastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param castleRookDestination The destination position of the castle rook.
     */
    CastleMove(final Board board,
               final Piece pieceMoved,
               final int destinationCoordinate,
               final Rook castleRook,
               final int castleRookStart,
               final int castleRookDestination) {
      super(board, pieceMoved, destinationCoordinate);
      this.castleRook = castleRook;
      this.castleRookStart = castleRookStart;
      this.castleRookDestination = castleRookDestination;
    }

    /**
     * Checks if this move is a castling move.
     *
     * @return True since it is a castling move.
     */
    @Override
    public boolean isCastlingMove() {
      return true;
    }
  }

  /**
   * The KingSideCastleMove class represents a move for king-side castling in chess.
   * King-side castling involves the king moving two squares towards the h-file (kingside)
   * and the rook moving to the square the king crossed.
   */
  public static class KingSideCastleMove extends CastleMove {

    /**
     * Constructs a KingSideCastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param castleRookDestination The destination position of the castle rook.
     */
    public KingSideCastleMove(final Board board,
                              final Piece pieceMoved,
                              final int destinationCoordinate,
                              final Rook castleRook,
                              final int castleRookStart,
                              final int castleRookDestination) {
      super(board, pieceMoved, destinationCoordinate, castleRook, castleRookStart, castleRookDestination);
    }

    /**
     * Returns a string representation of the KingSideCastleMove, indicating king-side castling.
     *
     * @return The string "O-O" representing king-side castling.
     */
    @Override
    public String toString() {
      return "O-O";
    }
  }

  /**
   * The QueenSideCastleMove class represents a move for queen-side castling in chess.
   * Queen-side castling involves the king moving two squares towards the a-file (queenside)
   * and the rook moving to the square the king crossed.
   */
  public static class QueenSideCastleMove extends CastleMove {

    /**
     * Constructs a QueenSideCastleMove instance with the provided board, moving piece, destination coordinate,
     * castle rook, and rook start and destination positions.
     *
     * @param board                 The chess board on which the move is made.
     * @param pieceMoved            The piece being moved (usually the king).
     * @param destinationCoordinate The coordinate to which the piece is moved.
     * @param castleRook            The rook involved in the castling move.
     * @param castleRookStart       The starting position of the castle rook.
     * @param rookCastleDestination The destination position of the castle rook.
     */
    public QueenSideCastleMove(final Board board,
                               final Piece pieceMoved,
                               final int destinationCoordinate,
                               final Rook castleRook,
                               final int castleRookStart,
                               final int rookCastleDestination) {
      super(board, pieceMoved, destinationCoordinate, castleRook, castleRookStart, rookCastleDestination);
    }

    /**
     * Returns a string representation of the QueenSideCastleMove, indicating queen-side castling.
     *
     * @return The string "O-O-O" representing queen-side castling.
     */
    @Override
    public String toString() {
      return "O-O-O";
    }
  }

  /**
   * The MoveFactory class provides factory methods for creating different types of moves.
   * It ensures that moves are created consistently and efficiently.
   */
  public static class MoveFactory {

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws RuntimeException Always thrown when attempting to instantiate MoveFactory.
     */
    private MoveFactory() {
      throw new RuntimeException("Not instantiatable!");
    }

    /**
     * Creates and returns a null move.
     * A null move is used as a sentinel value to indicate the absence of a valid move.
     *
     * @return The created null move.
     */
    public static Move getNullMove() {
      return MoveUtils.NULL_MOVE;
    }

    /**
     * Creates a move with the specified current and destination coordinates on the given board,
     * promoting to a queen if the move turns out to be a promotion. This is the form to use where
     * the promotion piece is not a choice, such as replaying a recorded move.
     *
     * @param board The current state of the chess board.
     * @param currentCoordinate The current coordinate of the moved piece.
     * @param destinationCoordinate The destination coordinate of the move.
     * @return The matching legal move, or a null move if there is no such move.
     */
    public static Move createMove(final Board board,
                                  final int currentCoordinate,
                                  final int destinationCoordinate) {
      return createMove(board, currentCoordinate, destinationCoordinate, Piece.PieceType.QUEEN);
    }

    /**
     * Creates a move with the specified current and destination coordinates on the given board,
     * promoting to the requested piece type if the move is a promotion. Four legal moves can share
     * a pair of squares when a pawn reaches the back rank, so the promotion type is what tells them
     * apart. It is ignored for every other kind of move.
     *
     * @param board The current state of the chess board.
     * @param currentCoordinate The current coordinate of the moved piece.
     * @param destinationCoordinate The destination coordinate of the move.
     * @param promotionType The piece type to promote to if this move is a promotion.
     * @return The matching legal move, or a null move if there is no such move.
     */
    public static Move createMove(final Board board,
                                  final int currentCoordinate,
                                  final int destinationCoordinate,
                                  final Piece.PieceType promotionType) {
      for (final Move move : board.getAllLegalMoves()) {
        if (move.getCurrentCoordinate() != currentCoordinate ||
                move.getDestinationCoordinate() != destinationCoordinate) {
          continue;
        }
        final Piece promotion = move.getPromotionPiece();
        if (promotion == null || promotion.getPieceType() == promotionType) {
          return move;
        }
      }
      return MoveUtils.NULL_MOVE;
    }
  }
}