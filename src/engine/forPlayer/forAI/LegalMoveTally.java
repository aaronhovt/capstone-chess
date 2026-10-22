package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.MoveCode;
import engine.forBoard.MoveGenerator;
import engine.forPiece.Piece;
import engine.forPlayer.Player;

/**
 * The LegalMoveTally class counts one player's legal moves from each piece's destination squares,
 * without generating the player's legal move list. A pawn move onto the promotion rank counts once
 * per promotion piece, and the player's castling moves count as king moves.
 *
 * @author Aaron Ho
 */
final class LegalMoveTally {

  /** The tiles on which a white pawn promotes, as one bit per tile. */
  private static final long WHITE_PROMOTION_TILES = computePromotionTiles(Alliance.WHITE);

  /** The tiles on which a black pawn promotes, as one bit per tile. */
  private static final long BLACK_PROMOTION_TILES = computePromotionTiles(Alliance.BLACK);

  /** The type of each of the player's pieces, in ascending order of square. */
  private final Piece.PieceType[] pieceTypes;

  /** The destination tiles of each piece's legal moves, as one bit per tile, in the same order. */
  private final long[] pieceDestinations;

  /** The tiles on which the player's pawns promote, as one bit per tile. */
  private final long promotionTiles;

  /** The number of legal moves. */
  private final int moveCount;

  /** The tiles on which the number of legal moves ending there has the bit of value 1 set. */
  private final long countOnes;

  /** The tiles on which the number of legal moves ending there has the bit of value 2 set. */
  private final long countTwos;

  /** The tiles on which the number of legal moves ending there has the bit of value 4 set. */
  private final long countFours;

  /** The tiles on which the number of legal moves ending there has the bit of value 8 set. */
  private final long countEights;

  /** The tiles on which the number of legal moves ending there has the bit of value 16 set. */
  private final long countSixteens;

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

    final int[] castles = new int[2];
    final int castleCount = MoveGenerator.generateCastles(board, player.getAlliance(), castles);
    long castleDestinations = 0L;
    for (int castle = 0; castle < castleCount; castle++) {
      castleDestinations |= 1L << MoveCode.destination(castles[castle]);
    }

    final long occupancy = board.getOccupancy(player.getAlliance());
    this.pieceTypes = new Piece.PieceType[Long.bitCount(occupancy)];
    this.pieceDestinations = new long[this.pieceTypes.length];
    int moves = 0;
    long pawnTiles = 0L;
    long ones = 0L;
    long twos = 0L;
    long fours = 0L;
    long eights = 0L;
    long sixteens = 0L;

    int index = 0;
    for (long remaining = occupancy; remaining != 0L; remaining &= remaining - 1) {
      final Piece piece = board.getPiece(Long.numberOfTrailingZeros(remaining));
      final Piece.PieceType pieceType = piece.getPieceType();

      long destinations = piece.legalDestinations(board);
      if (pieceType == Piece.PieceType.KING) {
        destinations |= castleDestinations;
      }

      long single = destinations;
      if (pieceType == Piece.PieceType.PAWN) {
        pawnTiles |= destinations;
        final long promotions = destinations & this.promotionTiles;
        if (promotions != 0L) {
          single &= ~promotions;
          moves += 4 * Long.bitCount(promotions);

          // Adds four on each promotion tile, starting from the bit of value 4.
          final long foursCarry = fours & promotions;
          fours ^= promotions;
          final long eightsCarry = eights & foursCarry;
          eights ^= foursCarry;
          sixteens ^= eightsCarry;
        }
      }
      moves += Long.bitCount(single);

      // Adds one on each destination tile, carrying through the higher bits.
      long carry = ones & single;
      ones ^= single;
      long nextCarry = twos & carry;
      twos ^= carry;
      carry = fours & nextCarry;
      fours ^= nextCarry;
      nextCarry = eights & carry;
      eights ^= carry;
      sixteens ^= nextCarry;

      this.pieceTypes[index] = pieceType;
      this.pieceDestinations[index] = destinations;
      index++;
    }

    this.moveCount = moves;
    this.pawnDestinations = pawnTiles;
    this.countOnes = ones;
    this.countTwos = twos;
    this.countFours = fours;
    this.countEights = eights;
    this.countSixteens = sixteens;
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
   * Returns the number of legal moves ending on the given tile.
   *
   * @param tile The tile.
   * @return The number of moves ending there.
   */
  int movesTo(final int tile) {
    return (int) ((this.countOnes >>> tile) & 1L)
            | (int) ((this.countTwos >>> tile) & 1L) << 1
            | (int) ((this.countFours >>> tile) & 1L) << 2
            | (int) ((this.countEights >>> tile) & 1L) << 3
            | (int) ((this.countSixteens >>> tile) & 1L) << 4;
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
