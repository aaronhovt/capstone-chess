package engine.forBoard;

import engine.forPiece.Piece;

/**
 * A move of a chess game as an object, for the code that works with moves outside the search:
 * the GUI, the UCI adapter, the opening book and the testing tools. Each one wraps a packed move
 * from {@link MoveGenerator} together with the pieces it involves, read from the board it was
 * created on. The search works on the packed moves directly.
 * <p>
 * A move is applied to a board through {@link Board#makeMove(Move)}, which applies its packed
 * form, {@link MoveCode#of(Move)}.
 *
 * @author Aaron Ho
 */
public final class Move {

  /** The move that stands for the absence of a move. */
  private static final Move NULL_MOVE = new Move(null, MoveCode.NONE, null, null, null);

  /**
   * The board this move was created on. It is a live board that will have moved on by the time
   * anything reads it back, so it is read only for {@link #toString()}, which is meaningful only
   * before that board is mutated.
   */
  private final Board board;

  /** The packed form of this move, or {@link MoveCode#NONE} for the null move. */
  private final int code;

  /** The piece that moves, or null for the null move. */
  private final Piece movedPiece;

  /** The piece this move captures, or null if it captures nothing. */
  private final Piece attackedPiece;

  /** The piece a pawn becomes by this move, or null if this move is not a promotion. */
  private final Piece promotionPiece;

  /**
   * Creates a move from its packed form and the pieces it involves.
   *
   * @param board The board the move was created on.
   * @param code The packed move.
   * @param movedPiece The piece that moves.
   * @param attackedPiece The piece captured, or null.
   * @param promotionPiece The piece promoted to, or null.
   */
  private Move(final Board board, final int code, final Piece movedPiece,
               final Piece attackedPiece, final Piece promotionPiece) {
    this.board = board;
    this.code = code;
    this.movedPiece = movedPiece;
    this.attackedPiece = attackedPiece;
    this.promotionPiece = promotionPiece;
  }

  /**
   * Returns the move that stands for the absence of a move. There is one such move, so it may be
   * compared by identity.
   *
   * @return The null move.
   */
  public static Move nullMove() {
    return NULL_MOVE;
  }

  /**
   * Creates the move the given packed move describes on the given board.
   *
   * @param board The board the move belongs to, standing in the position it was generated in.
   * @param move A move {@link MoveGenerator} generates on that board, for either side.
   * @return The move.
   */
  public static Move fromCode(final Board board, final int move) {
    final int destination = MoveCode.destination(move);
    final Piece moved = board.getPiece(MoveCode.origin(move));
    final Piece attacked;
    if (MoveCode.isCastle(move)) {
      attacked = null;
    } else if (MoveCode.isEnPassant(move)) {
      attacked = board.getEnPassantPawn();
    } else {
      attacked = board.getPiece(destination);
    }
    final Piece.PieceType promotionType = MoveCode.promotionType(move);
    final Piece promotion = promotionType == null ? null :
            Piece.moved(promotionType, moved.getPieceAllegiance(), destination, false);
    return new Move(board, move, moved, attacked, promotion);
  }

  /**
   * Returns the legal move between the given squares on the given board, promoting to a queen if
   * the move turns out to be a promotion. This is the form to use where the promotion piece is
   * not a choice, such as replaying a recorded move.
   *
   * @param board The current state of the chess board.
   * @param origin The square the moved piece stands on.
   * @param destination The square it moves to.
   * @return The matching legal move, or the null move if there is no such move.
   */
  public static Move find(final Board board, final int origin, final int destination) {
    return find(board, origin, destination, Piece.PieceType.QUEEN);
  }

  /**
   * Returns the legal move between the given squares on the given board, promoting to the
   * requested piece type if the move is a promotion. Four legal moves can share a pair of squares
   * when a pawn reaches the back rank, so the promotion type is what tells them apart. It is
   * ignored for every other kind of move.
   *
   * @param board The current state of the chess board.
   * @param origin The square the moved piece stands on.
   * @param destination The square it moves to.
   * @param promotionType The piece type to promote to if this move is a promotion.
   * @return The matching legal move, or the null move if there is no such move.
   */
  public static Move find(final Board board, final int origin, final int destination,
                          final Piece.PieceType promotionType) {
    for (final Move move : board.getAllLegalMoves()) {
      if (move.getCurrentCoordinate() != origin ||
              move.getDestinationCoordinate() != destination) {
        continue;
      }
      if (move.promotionPiece == null || move.promotionPiece.getPieceType() == promotionType) {
        return move;
      }
    }
    return NULL_MOVE;
  }

  /**
   * Returns the packed form of this move.
   *
   * @return The packed move, or {@link MoveCode#NONE} for the null move.
   */
  int code() {
    return this.code;
  }

  /**
   * Returns the piece a pawn is promoted to by this move, or null if this move is not a promotion.
   * Promotion is part of move identity, since four distinct legal moves can otherwise share the
   * same pair of squares.
   *
   * @return The promotion piece, or null if this move is not a promotion.
   */
  public Piece getPromotionPiece() {
    return this.promotionPiece;
  }

  /**
   * Compares this move to another for equality. Two moves are equal when they leave the same
   * square, arrive at the same square, and promote to the same piece type, which is exactly what
   * identifies a move within a position. The moved piece, the captured piece and the board are
   * excluded, so the same move compares equal across two positions.
   *
   * @param other The other object to compare to.
   * @return True if the moves are equal, false otherwise.
   */
  @Override
  public boolean equals(final Object other) {
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
  public int hashCode() {
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
    return this.promotionPiece != null ? this.promotionPiece.getPieceType() : null;
  }

  /**
   * Gets the square the moved piece stands on.
   *
   * @return The origin square, or -1 for the null move.
   */
  public int getCurrentCoordinate() {
    return this.movedPiece != null ? this.movedPiece.getPiecePosition() : -1;
  }

  /**
   * Gets the square the moved piece arrives on.
   *
   * @return The destination square, or -1 for the null move.
   */
  public int getDestinationCoordinate() {
    return this.code == MoveCode.NONE ? -1 : MoveCode.destination(this.code);
  }

  /**
   * Gets the piece that is being moved.
   *
   * @return The moved piece, or null for the null move.
   */
  public Piece getMovedPiece() {
    return this.movedPiece;
  }

  /**
   * Checks whether this move captures a piece.
   *
   * @return True if the move captures, false otherwise.
   */
  public boolean isAttack() {
    return this.attackedPiece != null;
  }

  /**
   * Checks whether this move castles.
   *
   * @return True if the move castles, false otherwise.
   */
  public boolean isCastlingMove() {
    return this.code != MoveCode.NONE && MoveCode.isCastle(this.code);
  }

  /**
   * Gets the piece this move captures.
   *
   * @return The captured piece, or null if the move captures nothing.
   */
  public Piece getAttackedPiece() {
    return this.attackedPiece;
  }

  /**
   * Renders this move in algebraic notation, with the file of origin added to a piece move when
   * another piece of the same type can reach the same square. That test is made against this
   * move's own board, so the result is correct only before that board has been mutated. Anything
   * that needs stable notation should use {@link #toNotation(Board)}.
   *
   * @return The algebraic notation for this move.
   */
  @Override
  public String toString() {
    return render(this.board);
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
    return render(position);
  }

  /**
   * Renders this move in algebraic notation, disambiguating a piece move against the given
   * position: "Nbd7" or "Rxe1" for a piece, "e4" or "dxe5" for a pawn, "e7-e8=Q" for a promotion,
   * and "O-O" or "O-O-O" for castling.
   *
   * @param position The board the piece move is disambiguated against.
   * @return The algebraic notation for this move.
   */
  private String render(final Board position) {
    if (this == NULL_MOVE) {
      return "Null Move";
    }
    final int origin = getCurrentCoordinate();
    final int destination = getDestinationCoordinate();
    final String target = BoardUtils.getPositionAtCoordinate(destination);
    if (isCastlingMove()) {
      return destination > origin ? "O-O" : "O-O-O";
    }
    if (this.promotionPiece != null) {
      return BoardUtils.getPositionAtCoordinate(origin) + "-" + target + "=" +
              this.promotionPiece.getPieceType();
    }
    final String capture = isAttack() ? "x" : "";
    if (this.movedPiece.getPieceType() == Piece.PieceType.PAWN) {
      return isAttack() ? BoardUtils.getPositionAtCoordinate(origin).charAt(0) + capture + target :
              target;
    }
    return this.movedPiece.getPieceType() + disambiguationFile(position) + capture + target;
  }

  /**
   * Returns the file this move's piece leaves when another legal move of a piece of the same type
   * arrives on the same square in the given position.
   *
   * @param position The board as it stood immediately before this move was made.
   * @return The file of the origin square, or an empty string if no other piece needs telling
   *         apart.
   */
  private String disambiguationFile(final Board position) {
    for (final Move move : position.currentPlayer().getLegalMoves()) {
      if (move.getDestinationCoordinate() == getDestinationCoordinate() && !this.equals(move) &&
              this.movedPiece.getPieceType() == move.getMovedPiece().getPieceType()) {
        return BoardUtils.getPositionAtCoordinate(getCurrentCoordinate()).substring(0, 1);
      }
    }
    return "";
  }
}