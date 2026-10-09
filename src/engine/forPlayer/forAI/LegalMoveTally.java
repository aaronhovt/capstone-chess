package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

import java.util.Collection;

/**
 * The LegalMoveTally class counts one player's legal moves from each piece's destination squares,
 * without generating the player's legal move list. A pawn move onto the promotion rank counts once
 * per promotion piece, and the player's castling moves count as king moves. The arrays a tally
 * returns belong to it and must not be modified by a caller.
 *
 * @author Aaron Ho
 */
final class LegalMoveTally {

  /** The tiles on which a white pawn promotes, as one bit per tile. */
  private static final long WHITE_PROMOTION_TILES = computePromotionTiles(Alliance.WHITE);

  /** The tiles on which a black pawn promotes, as one bit per tile. */
  private static final long BLACK_PROMOTION_TILES = computePromotionTiles(Alliance.BLACK);

  /** The type of each of the player's pieces, in the order the player lists its pieces. */
  private final Piece.PieceType[] pieceTypes;

  /** The destination tiles of each piece's legal moves, as one bit per tile, in the same order. */
  private final long[] pieceDestinations;

  /** The tiles on which the player's pawns promote, as one bit per tile. */
  private final long promotionTiles;

  /** The number of legal moves. */
  private final int moveCount;

  /** The number of legal moves ending on each tile, indexed by tile. */
  private final int[] destinationCount;

  /** The tiles on which at least one pawn move ends, as one bit per tile. */
  private final long pawnDestinations;

  /**
   * Counts the legal moves of the given player on the given board.
   *
   * @param board The current state of the chess board.
   * @param player The player whose legal moves are counted.
   */
  private LegalMoveTally(final Board board, final Player player) {
    this.promotionTiles = player.getAlliance().isWhite() ?
            WHITE_PROMOTION_TILES :
            BLACK_PROMOTION_TILES;

    long castleDestinations = 0L;
    for (final Move castle : player.getCastleMoves()) {
      castleDestinations |= 1L << castle.getDestinationCoordinate();
    }

    final Collection<Piece> pieces = player.getActivePieces();
    this.pieceTypes = new Piece.PieceType[pieces.size()];
    this.pieceDestinations = new long[pieces.size()];
    this.destinationCount = new int[BoardUtils.NUM_TILES];
    int moves = 0;
    long pawnTiles = 0L;

    int index = 0;
    for (final Piece piece : pieces) {
      final Piece.PieceType pieceType = piece.getPieceType();
      final boolean isPawn = pieceType == Piece.PieceType.PAWN;

      long destinations = piece.legalDestinations(board);
      if (pieceType == Piece.PieceType.KING) {
        destinations |= castleDestinations;
      }
      if (isPawn) {
        pawnTiles |= destinations;
      }

      for (long remaining = destinations; remaining != 0L; remaining &= remaining - 1) {
        final int destination = Long.numberOfTrailingZeros(remaining);
        final int count = isPawn && ((this.promotionTiles >>> destination) & 1L) != 0L ? 4 : 1;

        moves += count;
        this.destinationCount[destination] += count;
      }

      this.pieceTypes[index] = pieceType;
      this.pieceDestinations[index] = destinations;
      index++;
    }

    this.moveCount = moves;
    this.pawnDestinations = pawnTiles;
  }

  /**
   * Counts the legal moves of the given player on the given board.
   *
   * @param board The current state of the chess board.
   * @param player The player whose legal moves are counted.
   * @return The tally of that player's legal moves.
   */
  static LegalMoveTally of(final Board board, final Player player) {
    return new LegalMoveTally(board, player);
  }

  /**
   * Returns the number of the player's pieces, each of which has an index below this number.
   *
   * @return The number of pieces.
   */
  int pieceCount() {
    return this.pieceTypes.length;
  }

  /**
   * Returns the type of the piece at the given index.
   *
   * @param piece The index of the piece.
   * @return The type of the piece.
   */
  Piece.PieceType pieceType(final int piece) {
    return this.pieceTypes[piece];
  }

  /**
   * Returns how many legal moves of the piece at the given index end on the given tiles, counting
   * a pawn move onto the promotion rank once per promotion piece. A king's moves include the
   * player's castling moves.
   *
   * @param piece The index of the piece.
   * @param tiles The tiles, as one bit per tile.
   * @return The number of the piece's moves ending on those tiles.
   */
  int movesOnto(final int piece, final long tiles) {
    final long destinations = this.pieceDestinations[piece] & tiles;
    final int moves = Long.bitCount(destinations);
    return this.pieceTypes[piece] == Piece.PieceType.PAWN ?
            moves + 3 * Long.bitCount(destinations & this.promotionTiles) :
            moves;
  }

  /**
   * Returns the number of legal moves.
   *
   * @return The number of legal moves.
   */
  int moveCount() {
    return this.moveCount;
  }

  /**
   * Returns the number of legal moves ending on each tile.
   *
   * @return The counts, indexed by tile.
   */
  int[] destinationCount() {
    return this.destinationCount;
  }

  /**
   * Returns the tiles on which at least one pawn move ends.
   *
   * @return The tiles, as one bit per tile.
   */
  long pawnDestinations() {
    return this.pawnDestinations;
  }

  /**
   * Computes the tiles on which a pawn of the given alliance promotes.
   *
   * @param alliance The alliance of the pawn.
   * @return The promotion tiles, as one bit per tile.
   */
  private static long computePromotionTiles(final Alliance alliance) {
    long promotionTiles = 0L;

    for (int tile = 0; tile < BoardUtils.NUM_TILES; tile++) {
      if (alliance.isPawnPromotionSquare(tile)) {
        promotionTiles |= 1L << tile;
      }
    }

    return promotionTiles;
  }
}
