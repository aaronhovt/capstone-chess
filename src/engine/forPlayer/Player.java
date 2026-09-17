package engine.forPlayer;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPiece.King;
import engine.forPiece.Piece;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static engine.forPiece.Piece.PieceType.KING;
import static java.util.stream.Collectors.collectingAndThen;

/**
 * The Player class represents an abstract chess player, providing the foundation for player-specific
 * operations and game state management. Each player is associated with a board position and maintains
 * their king piece, legal moves, and check status. The class answers state queries including check,
 * checkmate, stalemate, and castling status. Applying a move is not a player's responsibility;
 * moves are applied to a board in place through {@link Board#makeMove(Move)}. Concrete subclasses
 * must implement alliance-specific behavior for piece management and castling calculations.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public abstract class Player {

  /** The chessboard associated with this player. */
  protected final Board board;

  /** The king piece belonging to this player. */
  protected final King playerKing;

  /**
   * The collection of legal moves available to this player on the current board state. Null
   * until {@link #getLegalMoves()} is first called, at which point it is computed once and
   * cached here.
   */
  protected Collection<Move> legalMoves;

  /**
   * Whether this player is currently in check. Null until {@link #isInCheck()} is first called,
   * at which point it is computed once and cached here.
   */
  private Boolean inCheck;

  /**
   * Constructs a Player with the specified board, establishing the player's king. Neither this
   * player's check status nor its legal moves, including castling, are computed here; they are
   * computed lazily by {@link #isInCheck()} and {@link #getLegalMoves()} the first time something
   * actually asks for them.
   *
   * @param board The chessboard associated with this player.
   */
  Player(final Board board) {
    this.board = board;
    this.playerKing = establishKing();
  }

  /**
   * Determines whether this player is currently in check, computing and caching it on the first
   * call by scanning outward from the king's square. Callers that only ever ask one side of a
   * position this question, such as a search testing whether the move just played was legal, pay
   * for that side alone.
   *
   * @return True if the player is in check, false otherwise.
   */
  public boolean isInCheck() {
    if (this.inCheck == null) {
      final Alliance opponentAlliance = getAlliance().isWhite() ? Alliance.BLACK : Alliance.WHITE;
      this.inCheck = AttackDetector.isSquareAttacked(
              this.playerKing.getPiecePosition(), opponentAlliance, this.board);
    }
    return this.inCheck;
  }

  /**
   * Determines whether this player is in checkmate.
   * A player is in checkmate if they are in check and have no legal escape moves.
   *
   * @return True if the player is in checkmate, false otherwise.
   */
  public boolean isInCheckMate() {
    return isInCheck() && !hasEscapeMoves();
  }

  /**
   * Determines whether this player is in stalemate.
   * A player is in stalemate if they are not in check but have no legal moves.
   *
   * @return True if the player is in stalemate, false otherwise.
   */
  public boolean isInStaleMate() {
    return !isInCheck() && !hasEscapeMoves();
  }

  /**
   * Determines whether this player has castled.
   *
   * @return True if the player has castled, false otherwise.
   */
  public boolean isCastled() {
    return this.playerKing.isCastled();
  }

  /**
   * Retrieves this player's king piece.
   *
   * @return The king piece belonging to this player.
   */
  public King getPlayerKing() {
    return this.playerKing;
  }

  /**
   * Establishes the king piece for this player from the board's tracked kings.
   *
   * @return The king piece belonging to this player.
   * @throws RuntimeException If this player has no king on the board.
   */
  private King establishKing() {
    final King king = this.board.getKing(getAlliance());
    if (king == null) {
      throw new RuntimeException("No " + getAlliance() + " king on the board.");
    }
    return king;
  }

  /**
   * Determines whether this player has any legal escape moves available, by applying each of this
   * player's pseudo-legal moves to the board in place and keeping the first one that does not
   * leave this player's own king attacked.
   * <p>
   * This is only a meaningful question to ask of the side to move, since a move can only be played
   * from a position in which it is that side's turn. The previous implementation carried the same
   * assumption implicitly, through a board transition that read the board's current player rather
   * than this one, and simply returned a wrong answer if the assumption did not hold. Now that the
   * board is mutated in place instead, the same mistake would corrupt the board rather than merely
   * misreport, so it is checked rather than assumed.
   *
   * @return True if escape moves exist, false otherwise.
   * @throws IllegalStateException If this player is not the side to move on its own board.
   */
  private boolean hasEscapeMoves() {
    if (this.board.currentPlayer() != this) {
      throw new IllegalStateException(
              "Escape moves can only be tested for the side to move, not for " + getAlliance() + ".");
    }
    for (final Move move : getLegalMoves()) {
      if (this.board.isLegal(move)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Retrieves the collection of legal moves for this player, computing and caching it on the
   * first call. This is the expensive part of standing up a player, full pseudo-legal move
   * generation for every active piece plus castling, and most positions visited during search
   * only ever need one side's move list at a given node, so deferring it here rather than
   * computing it for both sides on every board mutation is the whole point.
   *
   * @return An unmodifiable collection of legal moves.
   */
  public Collection<Move> getLegalMoves() {
    if (this.legalMoves == null) {
      this.legalMoves = calculateLegalMoves();
    }
    return this.legalMoves;
  }

  /**
   * Computes this player's full legal move list against the current board: pseudo-legal moves
   * for every active piece, plus castling.
   *
   * @return An unmodifiable collection of this player's legal moves.
   */
  private Collection<Move> calculateLegalMoves() {
    final List<Move> playerLegals = new ArrayList<>();
    for (final Piece piece : getActivePieces()) {
      piece.addLegalMoves(this.board, playerLegals);
    }
    playerLegals.addAll(calculateKingCastles(playerLegals));
    return Collections.unmodifiableList(playerLegals);
  }

  /**
   * Calculates all moves that attack a specific tile on the board.
   *
   * @param tile The tile coordinate to check for attacks.
   * @param moves The collection of moves to examine.
   * @return An unmodifiable collection of moves that attack the specified tile.
   */
  public static Collection<Move> calculateAttacksOnTile(final int tile, final Collection<Move> moves) {
    return moves.stream()
            .filter(move -> move.getDestinationCoordinate() == tile)
            .collect(collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
  }

  /**
   * Retrieves the collection of active pieces belonging to this player.
   * Must be implemented by concrete subclasses to return alliance-specific pieces.
   *
   * @return The collection of active pieces for this player.
   */
  public abstract Collection<Piece> getActivePieces();

  /**
   * Retrieves the alliance (color) of this player.
   * Must be implemented by concrete subclasses to return the appropriate alliance.
   *
   * @return The alliance of this player.
   */
  public abstract Alliance getAlliance();

  /**
   * Retrieves the opponent player.
   * Must be implemented by concrete subclasses to return the opposing player.
   *
   * @return The opponent player.
   */
  public abstract Player getOpponent();

  /**
   * Calculates and returns possible castling moves for this player's king.
   * Must be implemented by concrete subclasses to handle alliance-specific castling rules.
   *
   * @param playerLegals The legal moves available to this player.
   * @return A collection of possible castling moves.
   */
  protected abstract Collection<Move> calculateKingCastles(Collection<Move> playerLegals);

  /**
   * Determines whether this player has any castling opportunities available.
   * A player has castling opportunities if they are not in check, have not already castled,
   * and retain either kingside or queenside castling capabilities.
   *
   * @return True if castling opportunities exist, false otherwise.
   */
  protected boolean hasCastleOpportunities() {
    return !isInCheck() || !this.playerKing.isCastled() ||
            (this.playerKing.isKingSideCastleCapable() && this.playerKing.isQueenSideCastleCapable());
  }
}