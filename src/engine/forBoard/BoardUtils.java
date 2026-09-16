package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

import java.util.Arrays;
import java.util.List;

/**
 * The BoardUtils class provides utility methods and constants for working with a chess board.
 * It includes functions for validating coordinates, checking threats, evaluating moves,
 * and tracking game progression. This class is implemented as an enum singleton to ensure
 * a single global instance.
 * <p>
 * The class maintains information about board geometry (rows and columns), handles algebraic
 * notation, and provides helper methods for various chess-related calculations.
 *
 * @author Aaron Ho
 */
public enum BoardUtils {

  /** The singleton instance of the BoardUtils class. */
  Instance;

  /** A list indicating whether each tile belongs to the first column (a-file) on the chessboard. */
  public final List<Boolean> FirstColumn = initColumn(0);

  /** A list indicating whether each tile belongs to the second column (b-file) on the chessboard. */
  public final List<Boolean> SecondColumn = initColumn(1);

  /** A list indicating whether each tile belongs to the third column (c-file) on the chessboard. */
  public final List<Boolean> ThirdColumn = initColumn(2);

  /** A list indicating whether each tile belongs to the fourth column (d-file) on the chessboard. */
  public final List<Boolean> FourthColumn = initColumn(3);

  /** A list indicating whether each tile belongs to the fifth column (e-file) on the chessboard. */
  public final List<Boolean> FifthColumn = initColumn(4);

  /** A list indicating whether each tile belongs to the sixth column (f-file) on the chessboard. */
  public final List<Boolean> SixthColumn = initColumn(5);

  /** A list indicating whether each tile belongs to the seventh column (g-file) on the chessboard. */
  public final List<Boolean> SeventhColumn = initColumn(6);

  /** A list indicating whether each tile belongs to the eighth column (h-file) on the chessboard. */
  public final List<Boolean> EighthColumn = initColumn(7);

  /** A list indicating whether each tile belongs to the first row (8th rank) on the chessboard. */
  public final List<Boolean> FirstRow = initRow(0);

  /** A list indicating whether each tile belongs to the second row (7th rank) on the chessboard. */
  public final List<Boolean> SecondRow = initRow(8);

  /** A list indicating whether each tile belongs to the third row (6th rank) on the chessboard. */
  public final List<Boolean> ThirdRow = initRow(16);

  /** A list indicating whether each tile belongs to the fourth row (5th rank) on the chessboard. */
  public final List<Boolean> FourthRow = initRow(24);

  /** A list indicating whether each tile belongs to the fifth row (4th rank) on the chessboard. */
  public final List<Boolean> FifthRow = initRow(32);

  /** A list indicating whether each tile belongs to the sixth row (3rd rank) on the chessboard. */
  public final List<Boolean> SixthRow = initRow(40);

  /** A list indicating whether each tile belongs to the seventh row (2nd rank) on the chessboard. */
  public static final List<Boolean> SeventhRow = initRow(48);

  /** A list indicating whether each tile belongs to the eighth row (1st rank) on the chessboard. */
  public final List<Boolean> EighthRow = initRow(56);

  /** A list of algebraic notation strings representing each tile on the chessboard. */
  public static final List<String> ALGEBRAIC_NOTATION = initializeAlgebraicNotation();

  /** The index of the starting tile (a8). */
  public static final int START_TILE_INDEX = 0;

  /** The number of tiles per row on the chessboard. */
  public static final int NUM_TILES_PER_ROW = 8;

  /** The total number of tiles on the chessboard. */
  public static final int NUM_TILES = 64;

  /**
   * Initializes a list of boolean values representing the tiles in a specified column.
   * Each element corresponds to a position on the board, with true indicating that
   * the position is in the specified column.
   *
   * @param columnNumber The column number for which to generate the list (0-7).
   * @return A list of boolean values indicating the tiles in the specified column.
   */
  private static List<Boolean> initColumn(int columnNumber) {
    final Boolean[] column = new Boolean[NUM_TILES];
    Arrays.fill(column, false);
    do {
      column[columnNumber] = true;
      columnNumber += NUM_TILES_PER_ROW;
    } while (columnNumber < NUM_TILES);
    return List.of((column));
  }

  /**
   * Initializes a list of boolean values representing the tiles in a specified row.
   * Each element corresponds to a position on the board, with true indicating that
   * the position is in the specified row.
   *
   * @param rowNumber The starting index of the row for which to generate the list.
   * @return A list of boolean values indicating the tiles in the specified row.
   */
  private static List<Boolean> initRow(int rowNumber) {
    final Boolean[] row = new Boolean[NUM_TILES];
    Arrays.fill(row, false);
    do {
      row[rowNumber] = true;
      rowNumber++;
    } while (rowNumber % NUM_TILES_PER_ROW != 0);
    return List.of(row);
  }

  /**
   * Initializes a list of strings representing algebraic notation positions for each tile on the chessboard.
   * The positions range from a8 to h1, corresponding to tile indices 0 to 63.
   *
   * @return A list of algebraic notation positions.
   */
  private static List<String> initializeAlgebraicNotation() {
    return List.of("a8", "b8", "c8", "d8", "e8", "f8", "g8", "h8",
            "a7", "b7", "c7", "d7", "e7", "f7", "g7", "h7",
            "a6", "b6", "c6", "d6", "e6", "f6", "g6", "h6",
            "a5", "b5", "c5", "d5", "e5", "f5", "g5", "h5",
            "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4",
            "a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3",
            "a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2",
            "a1", "b1", "c1", "d1", "e1", "f1", "g1", "h1");
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
    return coordinate >= START_TILE_INDEX && coordinate < NUM_TILES;
  }

  /**
   * Returns the algebraic notation position corresponding to a given tile coordinate.
   * For example, coordinate 0 corresponds to "a8", and coordinate 63 corresponds to "h1".
   *
   * @param coordinate The tile coordinate to convert (0-63).
   * @return The corresponding algebraic notation position.
   */
  public static String getPositionAtCoordinate(final int coordinate) {
    return ALGEBRAIC_NOTATION.get(coordinate);
  }

  /**
   * Checks whether making the given move would put the opposing king in check. Used for move
   * ordering, where checking moves are worth searching early.
   * <p>
   * The board is taken as a parameter rather than read from the move, because a move's own board
   * reference is a live board under the mutable board model and, at the root, is shared by every
   * search thread. The board given here must be the board the move was generated from, or one in
   * the same position, and must be private to the calling thread, since it is mutated and restored
   * over the course of this call.
   *
   * @param move The move to evaluate.
   * @param board The position the move would be played from, mutated and restored by this call.
   * @return True if the move puts the opposing king in check, false otherwise.
   */
  public static boolean kingThreat(final Move move, final Board board) {
    final Player mover = board.currentPlayer();
    final int opponentKingSquare = mover.getOpponent().getPlayerKing().getPiecePosition();
    final Alliance moverAlliance = mover.getAlliance();
    final UndoState undo = move.makeMove(board);
    try {
      return AttackDetector.isSquareAttacked(opponentKingSquare, moverAlliance, board);
    } finally {
      move.unmakeMove(board, undo);
    }
  }

  /**
   * Calculates the Modified Value, Victim, The Least Value, Victim Attacker (MVVLVA) score for a move.
   * This scoring system is used for move ordering in search algorithms, prioritizing captures of valuable
   * pieces by less valuable pieces.
   *
   * @param move The move for which to calculate the MVVLVA score.
   * @return The MVVLVA score.
   */
  public static int mvvlva(final Move move) {
    final Piece movingPiece = move.getMovedPiece();
    if (move.isAttack()) {
      final Piece attackedPiece = move.getAttackedPiece();
      return (attackedPiece.getPieceValue() - movingPiece.getPieceValue() + Piece.PieceType.KING.getPieceValue()) * 100;
    } return Piece.PieceType.KING.getPieceValue() - movingPiece.getPieceValue();
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