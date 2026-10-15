package engine.forPiece;

import engine.Alliance;
import engine.forBoard.BoardUtils;

/**
 * The PieceUtils class provides utility methods for creating and accessing pre-computed instances
 * of moved chess pieces. This singleton enum holds one piece instance for each alliance and
 * destination coordinate combination, so that move generation and move application never create
 * a new piece for a pawn, knight, bishop, rook or queen move.
 * <p>
 * The class maintains separate tables for pawns, knights, bishops, rooks, and queens, indexed by
 * alliance and then by destination coordinate. All stored pieces are configured as having been
 * moved (isFirstMove = false) with a move count of 1.
 *
 * @author dareTo81
 * @author Aaron Ho
 */
enum PieceUtils {

  /**
   * The singleton instance of the PieceUtils class providing access to all utility methods
   * and pre-computed piece tables.
   */
  Instance;

  /** The number of alliances, and so the number of rows in each table. */
  private static final int ALLIANCE_COUNT = 2;

  /**
   * Table storing all possible moved Queen instances, indexed by {@link #allianceIndex} and then
   * by destination coordinate.
   */
  private final Queen[][] ALL_POSSIBLE_QUEENS = PieceUtils.createAllPossibleMovedQueens();

  /**
   * Table storing all possible moved Rook instances, indexed by {@link #allianceIndex} and then
   * by destination coordinate.
   */
  private final Rook[][] ALL_POSSIBLE_ROOKS = PieceUtils.createAllPossibleMovedRooks();

  /**
   * Table storing all possible moved Knight instances, indexed by {@link #allianceIndex} and then
   * by destination coordinate.
   */
  private final Knight[][] ALL_POSSIBLE_KNIGHTS = PieceUtils.createAllPossibleMovedKnights();

  /**
   * Table storing all possible moved Bishop instances, indexed by {@link #allianceIndex} and then
   * by destination coordinate.
   */
  private final Bishop[][] ALL_POSSIBLE_BISHOPS = PieceUtils.createAllPossibleMovedBishops();

  /**
   * Table storing all possible moved Pawn instances, indexed by {@link #allianceIndex} and then
   * by destination coordinate.
   */
  private final Pawn[][] ALL_POSSIBLE_PAWNS = PieceUtils.createAllPossibleMovedPawns();

  /**
   * Table storing all possible moved King instances, indexed by {@link #allianceIndex}, then by
   * one for a king that has castled and zero otherwise, and then by destination coordinate. Each
   * keeps no castling right.
   */
  private final King[][][] ALL_POSSIBLE_KINGS = PieceUtils.createAllPossibleMovedKings();

  /**
   * Retrieves a moved Pawn instance for the specified alliance and destination coordinate.
   *
   * @param alliance The alliance of the Pawn piece.
   * @param destinationCoordinate The destination coordinate for the moved Pawn piece.
   * @return The moved Pawn piece instance.
   */
  Pawn getMovedPawn(final Alliance alliance,
                    final int destinationCoordinate) {
    return ALL_POSSIBLE_PAWNS[allianceIndex(alliance)][destinationCoordinate];
  }

  /**
   * Retrieves a moved Knight instance for the specified alliance and destination coordinate.
   *
   * @param alliance The alliance of the Knight piece.
   * @param destinationCoordinate The destination coordinate for the moved Knight piece.
   * @return The moved Knight piece instance.
   */
  Knight getMovedKnight(final Alliance alliance,
                        final int destinationCoordinate) {
    return ALL_POSSIBLE_KNIGHTS[allianceIndex(alliance)][destinationCoordinate];
  }

  /**
   * Retrieves a moved Bishop instance for the specified alliance and destination coordinate.
   *
   * @param alliance The alliance of the Bishop piece.
   * @param destinationCoordinate The destination coordinate for the moved Bishop piece.
   * @return The moved Bishop piece instance.
   */
  Bishop getMovedBishop(final Alliance alliance,
                        final int destinationCoordinate) {
    return ALL_POSSIBLE_BISHOPS[allianceIndex(alliance)][destinationCoordinate];
  }

  /**
   * Retrieves a moved Rook instance for the specified alliance and destination coordinate.
   *
   * @param alliance The alliance of the Rook piece.
   * @param destinationCoordinate The destination coordinate for the moved Rook piece.
   * @return The moved Rook piece instance.
   */
  Rook getMovedRook(final Alliance alliance,
                    final int destinationCoordinate) {
    return ALL_POSSIBLE_ROOKS[allianceIndex(alliance)][destinationCoordinate];
  }

  /**
   * Retrieves a moved Queen instance for the specified alliance and destination coordinate.
   *
   * @param alliance The alliance of the Queen piece.
   * @param destinationCoordinate The destination coordinate for the moved Queen piece.
   * @return The moved Queen piece instance.
   */
  Queen getMovedQueen(final Alliance alliance,
                      final int destinationCoordinate) {
    return ALL_POSSIBLE_QUEENS[allianceIndex(alliance)][destinationCoordinate];
  }

  /**
   * Retrieves a moved King instance for the specified alliance and destination coordinate, which
   * keeps no castling right and has castled exactly when the move castles.
   *
   * @param alliance The alliance of the King piece.
   * @param destinationCoordinate The destination coordinate for the moved King piece.
   * @param castling Whether the move castles.
   * @return The moved King piece instance.
   */
  King getMovedKing(final Alliance alliance, final int destinationCoordinate,
                    final boolean castling) {
    return ALL_POSSIBLE_KINGS[allianceIndex(alliance)][castling ? 1 : 0][destinationCoordinate];
  }

  /**
   * Returns the row of each table holding the given alliance's pieces.
   *
   * @param alliance The alliance.
   * @return The row index of that alliance.
   */
  private static int allianceIndex(final Alliance alliance) {
    return alliance.isWhite() ? 0 : 1;
  }

  /**
   * Creates and populates a table containing all possible moved Pawn instances
   * for each alliance and board coordinate combination.
   *
   * @return A table containing all possible moved Pawn instances.
   */
  private static Pawn[][] createAllPossibleMovedPawns() {
    final Pawn[][] pieces = new Pawn[ALLIANCE_COUNT][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][i] = new Pawn(alliance, i, false, 1);
      }
    }
    return pieces;
  }

  /**
   * Creates and populates a table containing all possible moved Knight instances
   * for each alliance and board coordinate combination.
   *
   * @return A table containing all possible moved Knight instances.
   */
  private static Knight[][] createAllPossibleMovedKnights() {
    final Knight[][] pieces = new Knight[ALLIANCE_COUNT][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][i] = new Knight(alliance, i, false, 1);
      }
    }
    return pieces;
  }

  /**
   * Creates and populates a table containing all possible moved Bishop instances
   * for each alliance and board coordinate combination.
   *
   * @return A table containing all possible moved Bishop instances.
   */
  private static Bishop[][] createAllPossibleMovedBishops() {
    final Bishop[][] pieces = new Bishop[ALLIANCE_COUNT][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][i] = new Bishop(alliance, i, false, 1);
      }
    }
    return pieces;
  }

  /**
   * Creates and populates a table containing all possible moved Rook instances
   * for each alliance and board coordinate combination.
   *
   * @return A table containing all possible moved Rook instances.
   */
  private static Rook[][] createAllPossibleMovedRooks() {
    final Rook[][] pieces = new Rook[ALLIANCE_COUNT][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][i] = new Rook(alliance, i, false, 1);
      }
    }
    return pieces;
  }

  /**
   * Creates and populates a table containing all possible moved Queen instances
   * for each alliance and board coordinate combination.
   *
   * @return A table containing all possible moved Queen instances.
   */
  private static Queen[][] createAllPossibleMovedQueens() {
    final Queen[][] pieces = new Queen[ALLIANCE_COUNT][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][i] = new Queen(alliance, i, false, 1);
      }
    }
    return pieces;
  }

  /**
   * Creates and populates a table containing all possible moved King instances for each
   * alliance, castled status and board coordinate combination.
   *
   * @return A table containing all possible moved King instances.
   */
  private static King[][][] createAllPossibleMovedKings() {
    final King[][][] pieces = new King[ALLIANCE_COUNT][2][BoardUtils.NUM_TILES];
    for(final Alliance alliance : Alliance.values()) {
      for(int i = 0; i < BoardUtils.NUM_TILES; i++) {
        pieces[allianceIndex(alliance)][0][i] = new King(alliance, i, false, false, false, false);
        pieces[allianceIndex(alliance)][1][i] = new King(alliance, i, false, true, false, false);
      }
    }
    return pieces;
  }
}
