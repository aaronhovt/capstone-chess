package engine.forPlayer;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forPiece.Piece;

import java.util.Collection;

/**
 * The BlackPlayer class represents a chess player controlling the black pieces.
 * It extends the abstract Player class and provides black-specific functionality, namely its
 * alliance, its opponent and its pieces.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class BlackPlayer extends Player {

  /**
   * Constructs a new BlackPlayer with the specified board. Initializes the player with access to
   * the current board state. This player's legal moves are computed lazily by
   * {@link Player#getLegalMoves()} rather than here.
   *
   * @param board The current chess board state.
   */
  public BlackPlayer(final Board board) {
    super(board);
  }

  /**
   * Returns the opponent player for the black player.
   * The opponent of the black player is always the white player.
   *
   * @return The WhitePlayer instance representing the opponent.
   */
  @Override
  public WhitePlayer getOpponent() {
    return this.board.whitePlayer();
  }

  /**
   * Returns all active black pieces currently on the chess board.
   * Active pieces are those that remain in play and have not been captured.
   *
   * @return Collection of black pieces on the board.
   */
  @Override
  public Collection<Piece> getActivePieces() {
    return this.board.getBlackPieces();
  }

  /**
   * Returns the alliance designation for the black player.
   * The alliance identifies which color pieces this player controls.
   *
   * @return Alliance.BLACK indicating this player controls black pieces.
   */
  @Override
  public Alliance getAlliance() {
    return Alliance.BLACK;
  }

  /**
   * Returns the string representation of the black player's alliance.
   * Provides a human-readable identifier for this player type.
   *
   * @return String "BLACK" representing this player's alliance.
   */
  @Override
  public String toString() {
    return Alliance.BLACK.toString();
  }
}