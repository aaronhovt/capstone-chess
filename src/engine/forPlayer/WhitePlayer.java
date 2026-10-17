package engine.forPlayer;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forPiece.Piece;

import java.util.Collection;

/**
 * The WhitePlayer class represents a player controlling the white pieces in a chess game.
 * It extends the abstract Player class and provides functionality specific to white pieces,
 * namely its alliance, its opponent and its pieces.
 * <p>
 * The white player follows standard chess rules where pieces move from the bottom of the board
 * toward the top, with pawns advancing toward the eighth rank for promotion.
 *
 * @author Aaron
 * @author dareTo81
 */
public final class WhitePlayer extends Player {

  /**
   * Constructs a WhitePlayer object with the given chessboard. The player is initialized with
   * access to the current board state. This player's legal moves are computed lazily by
   * {@link Player#getLegalMoves()} rather than here.
   *
   * @param board The current chess board state.
   */
  public WhitePlayer(final Board board) {
    super(board);
  }

  /**
   * Gets the opponent of the white player, which is the black player in the chess game.
   * This method provides access to the opposing player for game state evaluation and
   * move calculation purposes.
   *
   * @return The BlackPlayer object representing the opponent.
   */
  @Override
  public BlackPlayer getOpponent() {
    return this.board.blackPlayer();
  }

  /**
   * Gets a collection of active white pieces on the current chess board.
   * Active pieces are those that remain on the board and have not been captured.
   * This collection is used for move generation and board evaluation.
   *
   * @return A collection of Piece objects representing the active white pieces.
   */
  @Override
  public Collection<Piece> getActivePieces() {
    return this.board.getWhitePieces();
  }

  /**
   * Gets the alliance of the white player, which is always Alliance.WHITE.
   * This method identifies the player's color for game logic and rule enforcement.
   *
   * @return The Alliance enum value representing the white player's alliance.
   */
  @Override
  public Alliance getAlliance() {
    return Alliance.WHITE;
  }

  /**
   * Returns a string representation of the white player's alliance.
   * The representation matches the alliance's string format for display purposes.
   *
   * @return The string "WHITE" to represent the white player.
   */
  @Override
  public String toString() {
    return Alliance.WHITE.toString();
  }
}