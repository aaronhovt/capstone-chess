package engine.forBoard;

import java.util.Objects;

/**
 * Board geometry and game state helpers. Squares are numbered 0 to 63 from a8 to h1, a row at a
 * time, so row 0 is the eighth rank and row 7 the first.
 *
 * @author Aaron Ho
 */
public final class BoardUtils {

  /** The number of tiles per row on the chessboard. */
  public static final int NUM_TILES_PER_ROW = 8;

  /** The total number of tiles on the chessboard. */
  public static final int NUM_TILES = 64;

  /** Prevents instantiation, since this class holds only constants and static helpers. */
  private BoardUtils() {
  }

  /**
   * Returns whether the position is drawn by threefold repetition, by the fifty-move rule, or by
   * insufficient material.
   * <p>
   * Repetition and the fifty-move rule are claims rather than automatic draws under the FIDE
   * rules, but this engine gives a player no way to claim one, and a game that cannot end is worse
   * than a game that ends a little early, so both are treated as automatic here.
   * <p>
   * Deliberately kept out of {@link #isEndOfGame(Board)}. Repetition is a property of the path
   * taken to a position rather than of the position itself, so a search that treated it as
   * terminal would be storing path dependent scores in a table keyed only by position. This
   * method is for the game being played, not for the search.
   *
   * @param board The current state of the board.
   * @return True if the position is drawn by repetition, the fifty-move rule, or insufficient
   *         material.
   */
  public static boolean isDrawnPosition(final Board board) {
    return board.isThreefoldRepetition() || board.isFiftyMoveRule() ||
            board.isInsufficientMaterial();
  }

  /**
   * Checks whether a given tile coordinate is within the valid range of tile coordinates on the chessboard.
   * Valid coordinates range from 0 to 63 inclusive.
   *
   * @param coordinate The tile coordinate to check.
   * @return True if the coordinate is valid, false otherwise.
   */
  public static boolean isValidTileCoordinate(final int coordinate) {
    return coordinate >= 0 && coordinate < NUM_TILES;
  }

  /**
   * Returns the row of the given square, 0 for the eighth rank through 7 for the first.
   *
   * @param square The square, 0 to 63.
   * @return The square's row.
   */
  public static int rowOf(final int square) {
    return square / NUM_TILES_PER_ROW;
  }

  /**
   * Returns the algebraic name of the given square, such as "a8" for 0 and "h1" for 63.
   *
   * @param square The square, 0 to 63.
   * @return The square's name.
   * @throws IndexOutOfBoundsException If the square is not on the board.
   */
  public static String squareName(final int square) {
    Objects.checkIndex(square, NUM_TILES);
    return String.valueOf((char) ('a' + square % NUM_TILES_PER_ROW)) +
            (char) ('8' - rowOf(square));
  }

  /**
   * Returns the square with the given algebraic name, which must be a lowercase file letter
   * followed by a rank digit.
   *
   * @param name The square's name, such as "e4".
   * @return The square, or -1 if the name is not a square's name.
   */
  public static int squareOf(final String name) {
    if (name.length() != 2) {
      return -1;
    }
    final int file = name.charAt(0) - 'a';
    final int rank = name.charAt(1) - '1';
    if (file < 0 || file >= NUM_TILES_PER_ROW || rank < 0 || rank >= NUM_TILES_PER_ROW) {
      return -1;
    }
    return (NUM_TILES_PER_ROW - 1 - rank) * NUM_TILES_PER_ROW + file;
  }

  /**
   * Checks if the current board is in an endgame state, either checkmate or stalemate.
   * An endgame state means the game is over.
   *
   * @param board The current state of the board.
   * @return True if the game is in an endgame state, false otherwise.
   */
  public static boolean isEndOfGame(final Board board) {
    return board.currentPlayer().isInCheckMate() ||
            board.currentPlayer().isInStaleMate();
  }

  /**
   * Checks if the current game is in the opening stage.
   * This is determined by the number of plies played so far, with fewer than 12 plies
   * indicating the opening phase.
   *
   * @param board The current state of the board.
   * @return True if the game is in the opening phase, false otherwise.
   */
  public static boolean isOpening(final Board board) {
    return board.getPlyCount() < 12;
  }
}