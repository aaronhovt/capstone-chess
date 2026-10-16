package engine;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forPlayer.BlackPlayer;
import engine.forPlayer.Player;
import engine.forPlayer.WhitePlayer;

/**
 * The Alliance enum represents the two player alliances in a game of chess: White and Black.
 * Each alliance has its own set of characteristics, such as movement direction, pawn promotion criteria,
 * and positional bonuses for different pieces on the chessboard. This enum encapsulates the behavior and
 * attributes associated with each alliance, providing a structured way to handle alliance-specific logic
 * within a chess engine.
 *
 * @author Aaron Ho
 */
public enum Alliance {

  /** An enumeration representing white in a game of chess. */
  WHITE(0) {
    @Override
    public int getDirection() {
      return UP_DIRECTION;
    }

    @Override
    public int getOppositeDirection() {
      return DOWN_DIRECTION;
    }

    @Override
    public boolean isPawnPromotionSquare(final int position) {
      return BoardUtils.Instance.FirstRow.get(position);
    }

    @Override
    public Player choosePlayerByAlliance(final WhitePlayer whitePlayer, final BlackPlayer blackPlayer) {
      return whitePlayer;
    }

    @Override
    public String toString() {
      return "White";
    }

    @Override
    public boolean isWhite() {
      return true;
    }

    @Override
    public boolean isBlack() {
      return false;
    }
  },

  /** An enumeration representing black in a game of chess. */
  BLACK(64) {
    @Override
    public int getDirection() {
      return DOWN_DIRECTION;
    }

    @Override
    public int getOppositeDirection() {
      return UP_DIRECTION;
    }

    @Override
    public boolean isPawnPromotionSquare(final int position) {
      return BoardUtils.Instance.EighthRow.get(position);
    }

    @Override
    public Player choosePlayerByAlliance(final WhitePlayer whitePlayer, final BlackPlayer blackPlayer) {
      return blackPlayer;
    }

    @Override
    public String toString() {
      return "Black";
    }

    @Override
    public boolean isWhite() {
      return false;
    }

    @Override
    public boolean isBlack() {
      return true;
    }
  };

  /** A constant that represents the upward direction. */
  private static final int UP_DIRECTION = -1;

  /** A constant that represents the downward direction. */
  private static final int DOWN_DIRECTION = 1;

  /** The offset for accessing piece-square tables for this alliance. */
  private final int offset;

  /**
   * Constructs an Alliance with the specified offset for piece-square table access.
   *
   * @param offset The offset for accessing alliance-specific piece values.
   */
  Alliance(final int offset) {
    this.offset = offset;
  }

  /**
   * Optimized piece-square table using a single array with offsets.
   * Layout: [WHITE_PAWN, BLACK_PAWN, WHITE_KNIGHT, BLACK_KNIGHT, WHITE_BISHOP, BLACK_BISHOP, WHITE_ROOK, BLACK_ROOK]
   * Each section is 64 bytes representing the 64 board squares.
   */
  private static final byte[] PIECE_SQUARE_TABLE = {
          // WHITE PAWNS (0-63)
          0, 0, 0, 0, 0, 0, 0, 0,
          50, 50, 50, 50, 50, 50, 50, 50,
          10, 10, 30, 40, 40, 30, 10, 10,
          5, 5, 15, 35, 35, 15, 5, 5,
          0, 0, 5, 30, 30, 5, 0, 0,
          4, 0, -10, -5, -5, -10, 0, 4,
          10, 10, 10, -20, -20, 10, 10, 10,
          0, 0, 0, 0, 0, 0, 0, 0,

          // BLACK PAWNS (64-127)
          0, 0, 0, 0, 0, 0, 0, 0,
          5, 10, 10, -20, -20, 10, 10, 5,
          5, 0, -10, -5, -5, -10, 0, 5,
          0, 0, 5, 30, 30, 5, 0, 0,
          5, 5, 15, 35, 35, 15, 5, 5,
          10, 10, 30, 40, 40, 30, 10, 10,
          50, 50, 50, 50, 50, 50, 50, 50,
          0, 0, 0, 0, 0, 0, 0, 0,

          // WHITE KNIGHTS (128-191)
          -20, -20, -20, -20, -20, -20, -20, -20,
          -20, -20, 0, 5, 5, 0, -20, -20,
          -20, 5, 10, 15, 15, 10, 5, -20,
          -15, 0, 15, 20, 20, 15, 0, -15,
          -15, 5, 15, 20, 20, 15, 5, -15,
          -20, 0, 5, 15, 15, 10, 0, -20,
          -20, -20, 0, 10, 7, 0, -20, -20,
          -20, -15, -20, -20, -20, -20, -15, -20,

          // BLACK KNIGHTS (192-255)
          -20, -20, -20, -20, -20, -20, -20, -20,
          -20, -20, 0, 10, 7, 0, -20, -20,
          -20, 5, 10, 15, 15, 5, 5, -20,
          -15, 0, 15, 20, 20, 15, 0, -15,
          -15, 5, 15, 20, 20, 15, 5, -15,
          -20, 0, 10, 15, 15, 10, 0, -20,
          -20, -20, 0, 0, 0, 0, -20, -20,
          -20, -20, -20, -20, -20, -20, -20, -20,

          // WHITE BISHOPS (256-319)
          -20, -10, -10, -10, -10, -10, -10, -20,
          -10, 0, 0, 0, 0, 0, 0, -10,
          -10, 0, 10, 10, 10, 10, 0, -10,
          -10, 5, 10, 15, 15, 10, 0, -10,
          -10, 5, 10, 15, 15, 10, 5, -10,
          -10, 9, 8, 10, 10, 8, 9, -10,
          -10, 10, 0, 0, 10, 0, 10, -10,
          -20, -10, -10, -10, -10, -10, -10, -20,

          // BLACK BISHOPS (320-383)
          -20, -10, -10, -10, -10, -10, -10, -20,
          -10, 10, 0, 0, 15, 0, 10, -10,
          -10, 9, 8, 10, 10, 8, 9, -10,
          -10, 0, 10, 15, 15, 10, 0, -10,
          -10, 2, 10, 15, 15, 10, 2, -10,
          -10, 0, 10, 10, 10, 10, 0, -10,
          -10, 0, 0, 0, 0, 0, 0, -10,
          -20, -10, -10, -10, -10, -10, -10, -20,

          // WHITE ROOKS (384-447)
          0, 0, 0, 0, 0, 0, 0, 0,
          5, 20, 20, 20, 20, 20, 20, 5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          0, 0, 5, 5, 5, 0, 0, 0,

          // BLACK ROOKS (448-511)
          0, 0, 5, 5, 5, 0, 0, 0,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          -5, 0, 0, 0, 0, 0, 0, -5,
          5, 20, 20, 20, 20, 20, 20, 5,
          0, 0, 0, 0, 0, 0, 0, 0
  };

  /** Pawn offset for accessing the piece-square table. */
  private static final int PAWN_OFFSET = 0;

  /** Knight offset for accessing the piece-square table. */
  private static final int KNIGHT_OFFSET = 128;

  /** Bishop offset for accessing the piece-square table. */
  private static final int BISHOP_OFFSET = 256;

  /** Rook offset for accessing the piece-square table. */
  private static final int ROOK_OFFSET = 384;

  /**
   * Gets the direction in which the pieces of this alliance move on the board.
   *
   * @return The direction.
   */
  public abstract int getDirection();

  /**
   * Gets the opposite direction to the one in which the pieces of this alliance move on the board.
   *
   * @return The opposite direction.
   */
  public abstract int getOppositeDirection();

  /**
   * Checks if the alliance is white.
   *
   * @return True if white, false otherwise.
   */
  public abstract boolean isWhite();

  /**
   * Checks if the alliance is black.
   *
   * @return True if black, false otherwise.
   */
  public abstract boolean isBlack();

  /**
   * Checks if a given position on the board is a square where a pawn of this alliance can be promoted.
   *
   * @param position The position to check.
   * @return True if it is a pawn promotion square, false otherwise.
   */
  public abstract boolean isPawnPromotionSquare(final int position);

  /**
   * Chooses the player object that represents this alliance.
   *
   * @param whitePlayer The WhitePlayer object.
   * @param blackPlayer The BlackPlayer object.
   * @return The chosen player object.
   */
  public abstract Player choosePlayerByAlliance(final WhitePlayer whitePlayer, final BlackPlayer blackPlayer);

  /**
   * Calculates the bonus for a pawn at a given position on the board.
   *
   * @param position The position of the pawn.
   * @param board The current chess board.
   * @return The pawn bonus.
   */
  public final int pawnBonus(final int position, final Board board) {
    return PIECE_SQUARE_TABLE[PAWN_OFFSET + offset + position];
  }

  /**
   * Calculates the bonus for a knight at a given position on the board.
   *
   * @param position The position of the knight.
   * @param board The current chess board.
   * @return The knight bonus.
   */
  public final int knightBonus(final int position, final Board board) {
    return PIECE_SQUARE_TABLE[KNIGHT_OFFSET + offset + position];
  }

  /**
   * Calculates the bonus for a bishop at a given position on the board.
   *
   * @param position The position of the bishop.
   * @param board The current chess board.
   * @return The bishop bonus.
   */
  public final int bishopBonus(final int position, final Board board) {
    return PIECE_SQUARE_TABLE[BISHOP_OFFSET + offset + position];
  }

  /**
   * Calculates the bonus for a rook at a given position on the board.
   *
   * @param position The position of the rook.
   * @param board The current chess board.
   * @return The rook bonus.
   */
  public final int rookBonus(final int position, final Board board) {
    return PIECE_SQUARE_TABLE[ROOK_OFFSET + offset + position];
  }

  /**
   * Calculates the bonus for a queen at a given position on the board.
   *
   * @param position The position of the queen.
   * @param board The current chess board.
   * @return The queen bonus.
   */
  public final int queenBonus(final int position, final Board board) {
    if (BoardUtils.isOpening(board) && !(board.getPiece(position).isFirstMove())) {
      return -250;
    }
    return 0;
  }

  /**
   * Calculates the bonus for a king at a given position on the board.
   *
   * @param position The position of the king.
   * @param board The current chess board.
   * @return The king bonus.
   */
  public final int kingBonus(final int position, final Board board) {
    return 0;
  }
}