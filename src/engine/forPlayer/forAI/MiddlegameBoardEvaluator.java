package engine.forPlayer.forAI;

import com.google.common.annotations.VisibleForTesting;
import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.*;
import engine.forPlayer.Player;

import java.util.*;

/**
 * The MiddlegameBoardEvaluator class provides comprehensive position evaluation specifically
 * tuned for middlegame positions in chess. This evaluator implements modern chess engine
 * evaluation principles including material balance, piece mobility, king safety, pawn structure,
 * piece coordination, space control, attacking potential, and special positional patterns.
 * <p>
 * The evaluation function considers multiple strategic and tactical factors that are most
 * relevant during the middlegame phase, where piece development is complete and complex
 * tactical and strategic battles typically occur.
 *
 * @author Aaron Ho
 */
public class MiddlegameBoardEvaluator implements BoardEvaluator {

  /** Singleton instance of the MiddlegameBoardEvaluator. */
  private static final MiddlegameBoardEvaluator Instance = new MiddlegameBoardEvaluator();

  /**
   * The scores of both players' pawn structure terms that read nothing but pawns, keyed by the
   * tiles their pawns occupy.
   */
  private static final PawnStructureCache PAWN_STRUCTURE_CACHE = new PawnStructureCache();

  /**
   * The weights this evaluator scores with. Each index constant below names one weight, and its
   * description states what the weight scores. A bonus with a negative value acts as a penalty and
   * a penalty with a negative value as a bonus. Changing a weight clears the pawn structure cache.
   */
  private static final EvaluationWeights WEIGHTS =
          new EvaluationWeights(PAWN_STRUCTURE_CACHE::clear);

  /** The material value of a pawn. */
  private static final int PAWN_VALUE = WEIGHTS.add("PAWN_VALUE", 81);

  /** The material value of a knight. */
  private static final int KNIGHT_VALUE = WEIGHTS.add("KNIGHT_VALUE", 310);

  /** The material value of a bishop. */
  private static final int BISHOP_VALUE = WEIGHTS.add("BISHOP_VALUE", 323);

  /** The material value of a rook. */
  private static final int ROOK_VALUE = WEIGHTS.add("ROOK_VALUE", 491);

  /** The material value of a queen. */
  private static final int QUEEN_VALUE = WEIGHTS.add("QUEEN_VALUE", 1052);

  /** The bonus per legal move. */
  private static final int MOBILITY = WEIGHTS.add("MOBILITY", 1.65);

  /** The further bonus per legal knight move. */
  private static final int KNIGHT_MOBILITY = WEIGHTS.add("KNIGHT_MOBILITY", -1.03);

  /** The further bonus per legal bishop move. */
  private static final int BISHOP_MOBILITY = WEIGHTS.add("BISHOP_MOBILITY", 2.06);

  /** The further bonus per legal rook move. */
  private static final int ROOK_MOBILITY = WEIGHTS.add("ROOK_MOBILITY", 1.65);

  /** The further bonus per legal queen move. */
  private static final int QUEEN_MOBILITY = WEIGHTS.add("QUEEN_MOBILITY", -0.82);

  /** The bonus for a king standing on a square that castling can produce. */
  private static final int KING_ON_CASTLED_SQUARE = WEIGHTS.add("KING_ON_CASTLED_SQUARE", 22);

  /** The bonus for three or more pawns in the king's shield. */
  private static final int SHIELD_THREE_PAWNS = WEIGHTS.add("SHIELD_THREE_PAWNS", 17);

  /** The bonus for two pawns in the king's shield. */
  private static final int SHIELD_TWO_PAWNS = WEIGHTS.add("SHIELD_TWO_PAWNS", 5);

  /** The bonus for one pawn in the king's shield. */
  private static final int SHIELD_ONE_PAWN = WEIGHTS.add("SHIELD_ONE_PAWN", 4);

  /** The penalty for no pawn in the king's shield. */
  private static final int SHIELD_NO_PAWN = WEIGHTS.add("SHIELD_NO_PAWN", 11);

  /** The bonus per file holding a pawn of the king's shield. */
  private static final int SHIELD_FILE = WEIGHTS.add("SHIELD_FILE", 5);

  /** The penalty per opposing queen move landing near the king. */
  private static final int QUEEN_MOVE_NEAR_KING = WEIGHTS.add("QUEEN_MOVE_NEAR_KING", -5);

  /** The penalty per opposing rook move landing near the king. */
  private static final int ROOK_MOVE_NEAR_KING = WEIGHTS.add("ROOK_MOVE_NEAR_KING", -7);

  /** The penalty per opposing bishop move landing near the king. */
  private static final int BISHOP_MOVE_NEAR_KING = WEIGHTS.add("BISHOP_MOVE_NEAR_KING", -3);

  /** The penalty per opposing knight move landing near the king. */
  private static final int KNIGHT_MOVE_NEAR_KING = WEIGHTS.add("KNIGHT_MOVE_NEAR_KING", -7);

  /** The penalty per opposing pawn move landing near the king. */
  private static final int PAWN_MOVE_NEAR_KING = WEIGHTS.add("PAWN_MOVE_NEAR_KING", 12);

  /** The penalty per opposing king move landing near the king. */
  private static final int KING_MOVE_NEAR_KING = WEIGHTS.add("KING_MOVE_NEAR_KING", -29);

  /** The penalty per opposing move landing near the king beyond the second. */
  private static final int EXCESS_MOVE_NEAR_KING = WEIGHTS.add("EXCESS_MOVE_NEAR_KING", 8);

  /** The penalty for being in check. */
  private static final int IN_CHECK = WEIGHTS.add("IN_CHECK", 57);

  /** The factor of an opposing queen's tropism toward the king. */
  private static final int QUEEN_TROPISM = WEIGHTS.add("QUEEN_TROPISM", -0.41);

  /** The factor of an opposing rook's tropism toward the king. */
  private static final int ROOK_TROPISM = WEIGHTS.add("ROOK_TROPISM", 0.00);

  /** The factor of an opposing bishop's tropism toward the king. */
  private static final int BISHOP_TROPISM = WEIGHTS.add("BISHOP_TROPISM", -0.41);

  /** The factor of an opposing knight's tropism toward the king. */
  private static final int KNIGHT_TROPISM = WEIGHTS.add("KNIGHT_TROPISM", 4);

  /** The factor of an opposing pawn's tropism toward the king. */
  private static final int PAWN_TROPISM = WEIGHTS.add("PAWN_TROPISM", 18);

  /** The penalty for no own pawn on the king's file. */
  private static final int KING_FILE_OPEN = WEIGHTS.add("KING_FILE_OPEN", 19);

  /** The further penalty for an opposing rook or queen on that open king file. */
  private static final int KING_FILE_HEAVY_PIECE = WEIGHTS.add("KING_FILE_HEAVY_PIECE", 16);

  /** The penalty per file beside the king with no own pawn on it. */
  private static final int ADJACENT_FILE_OPEN = WEIGHTS.add("ADJACENT_FILE_OPEN", 13);

  /** The further penalty per such open file holding an opposing rook or queen. */
  private static final int ADJACENT_FILE_HEAVY_PIECE =
          WEIGHTS.add("ADJACENT_FILE_HEAVY_PIECE", 5);

  /** The bonus per passed pawn. */
  private static final int PASSED_PAWN = WEIGHTS.add("PASSED_PAWN", -45);

  /** The further bonus per passed pawn per rank it has advanced. */
  private static final int PASSED_PAWN_RANK = WEIGHTS.add("PASSED_PAWN_RANK", 24);

  /** The further bonus per passed pawn protected by a pawn. */
  private static final int PASSED_PAWN_PROTECTED = WEIGHTS.add("PASSED_PAWN_PROTECTED", -16);

  /** The further bonus per passed pawn whose stop square no opposing piece controls. */
  private static final int PASSED_PAWN_FREE_STOP_SQUARE =
          WEIGHTS.add("PASSED_PAWN_FREE_STOP_SQUARE", 24);

  /** The penalty per pawn island beyond the first. */
  private static final int PAWN_ISLAND = WEIGHTS.add("PAWN_ISLAND", 0.82);

  /** The penalty per pawn on a file beyond the first. */
  private static final int DOUBLED_PAWN = WEIGHTS.add("DOUBLED_PAWN", 1.65);

  /** The penalty per isolated pawn. */
  private static final int ISOLATED_PAWN = WEIGHTS.add("ISOLATED_PAWN", 13);

  /** The further penalty per isolated pawn on a file with no opposing pawn. */
  private static final int ISOLATED_PAWN_SEMI_OPEN = WEIGHTS.add("ISOLATED_PAWN_SEMI_OPEN", 7);

  /** The further penalty per isolated pawn on the c to f files. */
  private static final int ISOLATED_PAWN_CENTRAL = WEIGHTS.add("ISOLATED_PAWN_CENTRAL", 2.47);

  /** The penalty per backward pawn. */
  private static final int BACKWARD_PAWN = WEIGHTS.add("BACKWARD_PAWN", 7);

  /** The further penalty per backward pawn on a file with no opposing pawn. */
  private static final int BACKWARD_PAWN_SEMI_OPEN = WEIGHTS.add("BACKWARD_PAWN_SEMI_OPEN", 9);

  /** The bonus per pawn protected by a pawn. */
  private static final int PAWN_CHAIN_LINK = WEIGHTS.add("PAWN_CHAIN_LINK", 9);

  /** The bonus for three or more pawns protected by pawns. */
  private static final int PAWN_CHAIN = WEIGHTS.add("PAWN_CHAIN", 1.24);

  /** The bonus per pawn on one of the four central squares. */
  private static final int CENTRAL_PAWN = WEIGHTS.add("CENTRAL_PAWN", 18);

  /** The bonus per pawn attack on one of the four central squares. */
  private static final int CENTRAL_PAWN_ATTACK = WEIGHTS.add("CENTRAL_PAWN_ATTACK", 3);

  /** The bonus for bishops on both colours of square. */
  private static final int BISHOP_PAIR = WEIGHTS.add("BISHOP_PAIR", 42);

  /** The bonus per pair of rooks sharing a rank. */
  private static final int ROOKS_SHARING_RANK = WEIGHTS.add("ROOKS_SHARING_RANK", 12);

  /** The bonus per pair of rooks sharing a file. */
  private static final int ROOKS_SHARING_FILE = WEIGHTS.add("ROOKS_SHARING_FILE", 18);

  /** The bonus for any pair of rooks sharing a rank or file. */
  private static final int ROOKS_CONNECTED = WEIGHTS.add("ROOKS_CONNECTED", 5);

  /** The bonus per rook on a file with no own pawn and an opposing pawn. */
  private static final int ROOK_SEMI_OPEN_FILE = WEIGHTS.add("ROOK_SEMI_OPEN_FILE", 10);

  /** The bonus per rook on a file with no pawn. */
  private static final int ROOK_OPEN_FILE = WEIGHTS.add("ROOK_OPEN_FILE", 23);

  /** The bonus per protected piece that no opposing move attacks. */
  private static final int PROTECTED_PIECE = WEIGHTS.add("PROTECTED_PIECE", 0.41);

  /** The further bonus per defender of such a queen. */
  private static final int QUEEN_DEFENDER = WEIGHTS.add("QUEEN_DEFENDER", 5);

  /** The further bonus per defender of such a rook. */
  private static final int ROOK_DEFENDER = WEIGHTS.add("ROOK_DEFENDER", 2.47);

  /** The fraction of a threatened piece's value charged against the side that owns it. */
  private static final int THREAT_FRACTION = WEIGHTS.add("THREAT_FRACTION", 0.04);

  /** The factor of a knight's closeness to the centre. */
  private static final int KNIGHT_CENTRALITY = WEIGHTS.add("KNIGHT_CENTRALITY", 9);

  /** The factor of a bishop's closeness to the centre. */
  private static final int BISHOP_CENTRALITY = WEIGHTS.add("BISHOP_CENTRALITY", 2.47);

  /** The factor of a rook's closeness to the central files. */
  private static final int ROOK_CENTRALITY = WEIGHTS.add("ROOK_CENTRALITY", 3);

  /** The factor of a queen's closeness to the centre. */
  private static final int QUEEN_CENTRALITY = WEIGHTS.add("QUEEN_CENTRALITY", 3);

  /** The bonus per unit of space count. */
  private static final int SPACE = WEIGHTS.add("SPACE", -1.89);

  /** The bonus per legal move held over the opponent, up to ten. */
  private static final int MOVE_ADVANTAGE = WEIGHTS.add("MOVE_ADVANTAGE", 1.65);

  /** The bonus per legal move landing on one of the four central squares. */
  private static final int CENTRAL_SQUARE_MOVE = WEIGHTS.add("CENTRAL_SQUARE_MOVE", 1.65);

  /** The attack bonus per kind of piece with a move near the opposing king. */
  private static final int KING_ATTACKER = WEIGHTS.add("KING_ATTACKER", 5);

  /** The attack bonus per move landing near the opposing king. */
  private static final int KING_ATTACK_MOVE = WEIGHTS.add("KING_ATTACK_MOVE", 7);

  /** The further attack bonus for a queen move near the opposing king. */
  private static final int QUEEN_KING_ATTACK = WEIGHTS.add("QUEEN_KING_ATTACK", 1.24);

  /** The further attack bonus for a rook move near the opposing king. */
  private static final int ROOK_KING_ATTACK = WEIGHTS.add("ROOK_KING_ATTACK", 5);

  /** The further attack bonus for a bishop move near the opposing king. */
  private static final int BISHOP_KING_ATTACK = WEIGHTS.add("BISHOP_KING_ATTACK", 2.06);

  /** The further attack bonus for a knight move near the opposing king. */
  private static final int KNIGHT_KING_ATTACK = WEIGHTS.add("KNIGHT_KING_ATTACK", 5);

  /** The further attack bonus for a pawn move near the opposing king. */
  private static final int PAWN_KING_ATTACK = WEIGHTS.add("PAWN_KING_ATTACK", 4);

  /** The further attack bonus when the opposing king is in check. */
  private static final int KING_ATTACK_CHECK = WEIGHTS.add("KING_ATTACK_CHECK", 12);

  /** The further attack bonus when the opposing king is off its castled squares. */
  private static final int KING_ATTACK_UNCASTLED = WEIGHTS.add("KING_ATTACK_UNCASTLED", 2.47);

  /** The attack penalty per opposing piece within two tiles of the opposing king. */
  private static final int KING_ZONE_DEFENDER = WEIGHTS.add("KING_ZONE_DEFENDER", 7);

  /** The bonus per rook on the seventh rank. */
  private static final int ROOK_ON_SEVENTH = WEIGHTS.add("ROOK_ON_SEVENTH", 16);

  /** The further bonus per such rook per file holding an opposing pawn on that rank. */
  private static final int ROOK_ON_SEVENTH_PAWN = WEIGHTS.add("ROOK_ON_SEVENTH_PAWN", -12);

  /** The further bonus per such rook when the opposing king is on its back rank. */
  private static final int ROOK_ON_SEVENTH_KING = WEIGHTS.add("ROOK_ON_SEVENTH_KING", 32);

  /** The bonus for a bishop on the kingside fianchetto square. */
  private static final int KINGSIDE_FIANCHETTO = WEIGHTS.add("KINGSIDE_FIANCHETTO", 22);

  /** The bonus for a bishop on the queenside fianchetto square. */
  private static final int QUEENSIDE_FIANCHETTO = WEIGHTS.add("QUEENSIDE_FIANCHETTO", 15);

  /** The penalty per bishop sharing its square colour with three or more own pawns. */
  private static final int BAD_BISHOP_THREE_PAWNS = WEIGHTS.add("BAD_BISHOP_THREE_PAWNS", 4);

  /** The penalty per bishop sharing its square colour with two own pawns. */
  private static final int BAD_BISHOP_TWO_PAWNS = WEIGHTS.add("BAD_BISHOP_TWO_PAWNS", 0.41);

  /** The bonus per knight outpost. */
  private static final int KNIGHT_OUTPOST = WEIGHTS.add("KNIGHT_OUTPOST", -9);

  /** The further bonus per knight outpost protected by a pawn. */
  private static final int KNIGHT_OUTPOST_PROTECTED =
          WEIGHTS.add("KNIGHT_OUTPOST_PROTECTED", 13);

  /** The further bonus per knight outpost on the c to f files. */
  private static final int KNIGHT_OUTPOST_CENTRAL = WEIGHTS.add("KNIGHT_OUTPOST_CENTRAL", -3);

  /** The bonus per queen in the central sixteen squares. */
  private static final int QUEEN_CENTRAL = WEIGHTS.add("QUEEN_CENTRAL", -12);

  /** The penalty per queen on its first two ranks with fewer than two supporting pieces. */
  private static final int QUEEN_UNSUPPORTED = WEIGHTS.add("QUEEN_UNSUPPORTED", 20);

  /** The bonus per queen within two tiles of the opposing king. */
  private static final int QUEEN_NEAR_KING = WEIGHTS.add("QUEEN_NEAR_KING", 43);

  /** The bonus per queen three tiles from the opposing king. */
  private static final int QUEEN_THREE_FROM_KING = WEIGHTS.add("QUEEN_THREE_FROM_KING", -3);

  /**
   * The value of both players' knights, bishops, rooks and queens combined at and above which the
   * king safety, space control and attacking potential terms count in full.
   */
  private static final int KING_TERMS_FULL_MATERIAL =
          WEIGHTS.add("KING_TERMS_FULL_MATERIAL", 4200);

  /**
   * The value of both players' knights, bishops, rooks and queens combined at and below which the
   * king safety, space control and attacking potential terms count for nothing.
   */
  private static final int KING_TERMS_ZERO_MATERIAL =
          WEIGHTS.add("KING_TERMS_ZERO_MATERIAL", 2040);

  /**
   * The tiles within two ranks and two files of each tile, indexed by tile, as one bit per tile.
   */
  private static final long[] KING_ZONES = computeKingZones();

  /**
   * The space count a white move earns by landing on each tile, indexed by tile. A tile counts
   * once for lying in white's forward half and once for lying in the extended centre.
   */
  private static final int[] WHITE_SPACE_WEIGHTS = computeSpaceWeights(Alliance.WHITE);

  /**
   * The space count a black move earns by landing on each tile, indexed by tile. A tile counts
   * once for lying in black's forward half and once for lying in the extended centre.
   */
  private static final int[] BLACK_SPACE_WEIGHTS = computeSpaceWeights(Alliance.BLACK);

  /** The tiles on which a white pawn promotes, as one bit per tile. */
  private static final long WHITE_PROMOTION_TILES = computePromotionTiles(Alliance.WHITE);

  /** The tiles on which a black pawn promotes, as one bit per tile. */
  private static final long BLACK_PROMOTION_TILES = computePromotionTiles(Alliance.BLACK);

  /**
   * The tiles forming the pawn shield of a white king standing on each tile, indexed by tile, as
   * one bit per tile.
   */
  private static final long[] WHITE_KING_SHIELDS = computeKingShields(Alliance.WHITE);

  /**
   * The tiles forming the pawn shield of a black king standing on each tile, indexed by tile, as
   * one bit per tile.
   */
  private static final long[] BLACK_KING_SHIELDS = computeKingShields(Alliance.BLACK);

  /**
   * The tiles ahead of a white pawn standing on each tile, on its own file and the files beside
   * it, indexed by tile, as one bit per tile.
   */
  private static final long[] WHITE_FRONT_SPANS = computeFrontSpans(Alliance.WHITE);

  /**
   * The tiles ahead of a black pawn standing on each tile, on its own file and the files beside
   * it, indexed by tile, as one bit per tile.
   */
  private static final long[] BLACK_FRONT_SPANS = computeFrontSpans(Alliance.BLACK);

  /**
   * The tiles of file zero, as one bit per tile. Shifting left by a file gives that file's tiles.
   */
  private static final long FILE_TILES = 0x0101010101010101L;

  /**
   * The tiles of rank zero, as one bit per tile. Shifting left by eight times a rank gives that
   * rank's tiles.
   */
  private static final long RANK_TILES = 0xFFL;

  /** The tiles whose rank and file sum to an even number, as one bit per tile. */
  private static final long LIGHT_TILES = 0xAA55AA55AA55AA55L;

  /**
   * Private constructor to prevent instantiation outside of the class.
   * Enforces the singleton pattern.
   */
  private MiddlegameBoardEvaluator() {}

  /**
   * Returns the singleton instance of MiddlegameBoardEvaluator.
   *
   * @return The instance of MiddlegameBoardEvaluator.
   */
  public static MiddlegameBoardEvaluator get() {
    return Instance;
  }

  /**
   * Returns the weights this evaluator scores with.
   *
   * @return The weights of this evaluator.
   */
  public static EvaluationWeights weights() {
    return WEIGHTS;
  }

  /**
   * Evaluates the given board from the perspective of the current player and returns a score.
   * The evaluation is calculated as white's score minus black's score, making positive values
   * favorable for white and negative values favorable for black.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board.
   */
  @Override
  public double evaluate(final Board board) {
    final MoveStatistics whiteStatistics = moveStatistics(board, board.whitePlayer());
    final MoveStatistics blackStatistics = moveStatistics(board, board.blackPlayer());
    final PawnStructure whitePawns = pawnStructure(board.whitePlayer());
    final PawnStructure blackPawns = pawnStructure(board.blackPlayer());
    final PieceLayout whiteLayout = pieceLayout(board.whitePlayer());
    final PieceLayout blackLayout = pieceLayout(board.blackPlayer());
    final PawnStructureCache.Entry pawnStructureScores =
            pawnStructureScores(whitePawns, blackPawns);
    final double kingWeight = kingTermWeight(whiteLayout, blackLayout);

    return (score(board.whitePlayer(), board, whiteStatistics, blackStatistics,
                    whitePawns, blackPawns, pawnStructureScores, whiteLayout, blackLayout,
                    kingWeight) -
            score(board.blackPlayer(), board, blackStatistics, whiteStatistics,
                    blackPawns, whitePawns, pawnStructureScores, blackLayout, whiteLayout,
                    kingWeight));
  }

  /**
   * Returns the scores of both players' pawn structure terms that read nothing but pawns, from
   * the cache when it holds them for these pawns and otherwise computed and stored.
   *
   * @param whitePawns The structure of white's pawns.
   * @param blackPawns The structure of black's pawns.
   * @return The pawns-only pawn structure scores of both players.
   */
  private PawnStructureCache.Entry pawnStructureScores(final PawnStructure whitePawns,
                                                       final PawnStructure blackPawns) {
    final PawnStructureCache.Entry cached =
            PAWN_STRUCTURE_CACHE.probe(whitePawns.occupancy(), blackPawns.occupancy());

    if (cached != null) {
      return cached;
    }

    final PawnStructureCache.Entry computed = new PawnStructureCache.Entry(
            whitePawns.occupancy(), blackPawns.occupancy(),
            pawnOnlyStructureEvaluation(Alliance.WHITE, whitePawns, blackPawns),
            pawnOnlyStructureEvaluation(Alliance.BLACK, blackPawns, whitePawns));
    PAWN_STRUCTURE_CACHE.store(computed);
    return computed;
  }

  /**
   * The quantities drawn from one player's legal moves that the scoring terms read. The
   * arrays belong to this record and must not be modified by a caller. The near king quantities
   * are measured against the king of the player's opponent.
   *
   * @param moveCount The number of legal moves the player has.
   * @param moveCountByPieceType The number of legal moves per moving piece type, indexed by
   *                             {@link Piece.PieceType#ordinal()}.
   * @param destinationCount The number of legal moves landing on each tile, indexed by tile.
   * @param pawnDestinationCount The number of legal pawn moves landing on each tile, indexed by
   *                             tile.
   * @param spaceCount The space count accumulated over the move destinations, which counts a
   *                   destination once for lying in the player's forward half and once for lying
   *                   in the extended centre.
   * @param nearKingSquareCount The number of legal moves landing within two tiles of the
   *                            opposing king.
   * @param nearKingCountByPieceType The number of legal moves per moving piece type landing
   *                                 within two tiles of the opposing king, indexed by {@link
   *                                 Piece.PieceType#ordinal()}.
   */
  private record MoveStatistics(int moveCount,
                                int[] moveCountByPieceType,
                                int[] destinationCount,
                                int[] pawnDestinationCount,
                                int spaceCount,
                                int nearKingSquareCount,
                                int[] nearKingCountByPieceType) {
  }

  /**
   * Counts the given player's legal moves from each piece's destination squares and returns the
   * quantities the scoring terms read from them, without generating the player's legal move
   * list. A pawn move onto the promotion rank counts once per promotion piece, and the player's
   * castling moves count as king moves.
   *
   * @param board The current state of the chess board.
   * @param player The player whose legal moves are being counted.
   * @return The statistics of that player's legal moves.
   */
  private MoveStatistics moveStatistics(final Board board, final Player player) {
    final int[] spaceWeights = player.getAlliance().isWhite() ?
            WHITE_SPACE_WEIGHTS :
            BLACK_SPACE_WEIGHTS;
    final long promotionTiles = player.getAlliance().isWhite() ?
            WHITE_PROMOTION_TILES :
            BLACK_PROMOTION_TILES;
    final long opposingKingZone =
            KING_ZONES[player.getOpponent().getPlayerKing().getPiecePosition()];

    long castleDestinations = 0L;
    for (final Move castle : player.getCastleMoves()) {
      castleDestinations |= 1L << castle.getDestinationCoordinate();
    }

    final int[] moveCountByPieceType = new int[Piece.PieceType.values().length];
    final int[] destinationCount = new int[BoardUtils.NUM_TILES];
    final int[] pawnDestinationCount = new int[BoardUtils.NUM_TILES];
    final int[] nearKingCountByPieceType = new int[Piece.PieceType.values().length];
    int moveCount = 0;
    int spaceCount = 0;
    int nearKingSquareCount = 0;

    for (final Piece piece : player.getActivePieces()) {
      final Piece.PieceType pieceType = piece.getPieceType();
      final boolean isPawn = pieceType == Piece.PieceType.PAWN;

      long destinations = piece.legalDestinations(board);
      if (pieceType == Piece.PieceType.KING) {
        destinations |= castleDestinations;
      }

      for (long remaining = destinations; remaining != 0L; remaining &= remaining - 1) {
        final int destination = Long.numberOfTrailingZeros(remaining);
        final int moves = isPawn && ((promotionTiles >>> destination) & 1L) != 0L ? 4 : 1;

        moveCount += moves;
        moveCountByPieceType[pieceType.ordinal()] += moves;
        destinationCount[destination] += moves;

        if (isPawn) {
          pawnDestinationCount[destination] += moves;
        }

        spaceCount += spaceWeights[destination] * moves;

        if (((opposingKingZone >>> destination) & 1L) != 0L) {
          nearKingSquareCount += moves;
          nearKingCountByPieceType[pieceType.ordinal()] += moves;
        }
      }
    }

    return new MoveStatistics(moveCount, moveCountByPieceType, destinationCount,
            pawnDestinationCount, spaceCount, nearKingSquareCount, nearKingCountByPieceType);
  }

  /**
   * The facts about the placement of one player's pawns that the scoring terms read. The arrays
   * belong to this record and must not be modified by a caller. Rank indices run from zero on
   * black's back rank to seven on white's, so a smaller index is more advanced for white and a
   * larger index is more advanced for black.
   *
   * @param positions The tiles holding the player's pawns.
   * @param occupancy The tiles holding the player's pawns, as one bit per tile.
   * @param countByFile The number of the player's pawns on each file, indexed by file.
   * @param rearmostRankByFile The rank of the least advanced pawn on each file, indexed by file,
   *                           and zero on a file holding no pawn.
   * @param filesByRank The files holding a pawn on each rank, indexed by rank, as one bit per
   *                    file.
   * @param attackCountBySquare The number of the player's pawns bearing on each tile, indexed by
   *                            tile.
   * @param lightSquareCount The number of the player's pawns whose rank and file sum to an even
   *                         number.
   * @param darkSquareCount The number of the player's pawns whose rank and file sum to an odd
   *                        number.
   */
  private record PawnStructure(int[] positions,
                               long occupancy,
                               int[] countByFile,
                               int[] rearmostRankByFile,
                               int[] filesByRank,
                               int[] attackCountBySquare,
                               int lightSquareCount,
                               int darkSquareCount) {

    /**
     * Reports whether one of these pawns stands on the given tile.
     *
     * @param position The tile to check.
     * @return True if a pawn stands on the tile, false otherwise.
     */
    private boolean hasPawnAt(final int position) {
      return ((occupancy >>> position) & 1L) != 0L;
    }
  }

  /**
   * Walks the given player's active pieces once and returns the placement facts of that player's
   * pawns.
   *
   * @param player The player whose pawns are being read.
   * @return The structure of that player's pawns.
   */
  private PawnStructure pawnStructure(final Player player) {
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Alliance alliance = player.getAlliance();

    int pawnCount = 0;
    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.PAWN) {
        pawnCount++;
      }
    }

    final int[] positions = new int[pawnCount];
    final int[] countByFile = new int[8];
    final int[] rearmostRankByFile = new int[8];
    final int[] filesByRank = new int[8];
    final int[] attackCountBySquare = new int[BoardUtils.NUM_TILES];
    final int attackedRankStep = alliance.isWhite() ? -1 : 1;
    long occupancy = 0L;
    int lightSquareCount = 0;
    int darkSquareCount = 0;
    int index = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() != Piece.PieceType.PAWN) {
        continue;
      }

      final int position = piece.getPiecePosition();
      final int file = position % 8;
      final int rank = position / 8;

      positions[index++] = position;
      occupancy |= 1L << position;
      filesByRank[rank] |= 1 << file;

      if ((rank + file) % 2 == 0) {
        lightSquareCount++;
      } else {
        darkSquareCount++;
      }

      final int attackedRank = rank + attackedRankStep;

      if (attackedRank >= 0 && attackedRank < 8) {
        if (file > 0) {
          attackCountBySquare[(attackedRank * 8) + (file - 1)]++;
        }

        if (file < 7) {
          attackCountBySquare[(attackedRank * 8) + (file + 1)]++;
        }
      }

      if (countByFile[file] == 0) {
        rearmostRankByFile[file] = rank;
      } else if (alliance.isWhite()) {
        rearmostRankByFile[file] = Math.max(rearmostRankByFile[file], rank);
      } else {
        rearmostRankByFile[file] = Math.min(rearmostRankByFile[file], rank);
      }

      countByFile[file]++;
    }

    return new PawnStructure(positions, occupancy, countByFile, rearmostRankByFile, filesByRank,
            attackCountBySquare, lightSquareCount, darkSquareCount);
  }

  /**
   * The facts about the placement of one player's pieces that the scoring terms read.
   *
   * @param material The summed material value of all the player's pieces, the king included at
   *                 its piece value.
   * @param knights The tiles holding the player's knights, as one bit per tile.
   * @param bishops The tiles holding the player's bishops, as one bit per tile.
   * @param rooks The tiles holding the player's rooks, as one bit per tile.
   * @param queens The tiles holding the player's queens, as one bit per tile.
   * @param nonKingPieces The tiles holding the player's pieces other than the king, pawns
   *                      included, as one bit per tile.
   * @param heavyPieceFiles The files holding one of the player's rooks or queens, as one bit per
   *                        file.
   * @param kingTropism The tropism of the player's pieces toward the opposing king, summed in the
   *                    order of the player's piece list. A higher value means more danger to the
   *                    opposing king.
   */
  private record PieceLayout(double material,
                             long knights,
                             long bishops,
                             long rooks,
                             long queens,
                             long nonKingPieces,
                             int heavyPieceFiles,
                             double kingTropism) {
  }

  /**
   * Walks the given player's active pieces once and returns the placement facts of those pieces.
   *
   * @param player The player whose pieces are being read.
   * @return The layout of that player's pieces.
   */
  private PieceLayout pieceLayout(final Player player) {
    final int opposingKingPosition = player.getOpponent().getPlayerKing().getPiecePosition();

    double material = 0;
    long knights = 0L;
    long bishops = 0L;
    long rooks = 0L;
    long queens = 0L;
    long nonKingPieces = 0L;
    int heavyPieceFiles = 0;
    double kingTropism = 0;

    for (final Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() == Piece.PieceType.KING) {
        material += piece.getPieceValue();
        continue;
      }

      final int position = piece.getPiecePosition();
      final long tile = 1L << position;
      nonKingPieces |= tile;

      int distance = calculateChebyshevDistance(opposingKingPosition, position);
      double pieceValue = piece.getPieceValue() / 100.0;

      switch (piece.getPieceType()) {
        case QUEEN:
          material += WEIGHTS.get(QUEEN_VALUE);
          queens |= tile;
          heavyPieceFiles |= 1 << (position % 8);
          kingTropism += (7 - distance) * WEIGHTS.get(QUEEN_TROPISM) * pieceValue;
          break;
        case ROOK:
          material += WEIGHTS.get(ROOK_VALUE);
          rooks |= tile;
          heavyPieceFiles |= 1 << (position % 8);
          kingTropism += (7 - distance) * WEIGHTS.get(ROOK_TROPISM) * pieceValue;
          break;
        case BISHOP:
          material += WEIGHTS.get(BISHOP_VALUE);
          bishops |= tile;
          kingTropism += (7 - distance) * WEIGHTS.get(BISHOP_TROPISM) * pieceValue;
          break;
        case KNIGHT:
          material += WEIGHTS.get(KNIGHT_VALUE);
          knights |= tile;
          if (distance <= 3) {
            kingTropism += (4 - distance) * WEIGHTS.get(KNIGHT_TROPISM) * pieceValue;
          }
          break;
        case PAWN:
          material += WEIGHTS.get(PAWN_VALUE);
          if (distance <= 2) {
            kingTropism += (3 - distance) * WEIGHTS.get(PAWN_TROPISM) * pieceValue;
          }
          break;
      }
    }

    return new PieceLayout(material, knights, bishops, rooks, queens, nonKingPieces,
            heavyPieceFiles, kingTropism);
  }

  /**
   * Returns the weight applied to the king safety, space control and attacking potential terms of
   * both players. The weight is one when both players' knights, bishops, rooks and queens are
   * worth {@link #KING_TERMS_FULL_MATERIAL} or more combined, zero when they are worth
   * {@link #KING_TERMS_ZERO_MATERIAL} or less, and linear in that value between the two.
   *
   * @param whiteLayout The layout of white's pieces.
   * @param blackLayout The layout of black's pieces.
   * @return The weight of the king safety, space control and attacking potential terms, from zero
   *         to one.
   */
  private static double kingTermWeight(final PieceLayout whiteLayout,
                                       final PieceLayout blackLayout) {
    final int material = nonPawnMaterial(whiteLayout) + nonPawnMaterial(blackLayout);
    final double fullMaterial = WEIGHTS.get(KING_TERMS_FULL_MATERIAL);
    final double zeroMaterial = WEIGHTS.get(KING_TERMS_ZERO_MATERIAL);
    if (material >= fullMaterial) {
      return 1.0;
    }
    if (material <= zeroMaterial) {
      return 0.0;
    }
    return (material - zeroMaterial) / (fullMaterial - zeroMaterial);
  }

  /**
   * Returns the combined value of the knights, bishops, rooks and queens in the given layout.
   *
   * @param layout The layout of one player's pieces.
   * @return The value of that player's knights, bishops, rooks and queens.
   */
  private static int nonPawnMaterial(final PieceLayout layout) {
    return Long.bitCount(layout.knights()) * Piece.PieceType.KNIGHT.getPieceValue() +
            Long.bitCount(layout.bishops()) * Piece.PieceType.BISHOP.getPieceValue() +
            Long.bitCount(layout.rooks()) * Piece.PieceType.ROOK.getPieceValue() +
            Long.bitCount(layout.queens()) * Piece.PieceType.QUEEN.getPieceValue();
  }

  /**
   * Calculates the overall score of the current board position for a given player
   * using modern chess engine evaluation principles tuned for middlegame positions.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @param playerStatistics The statistics of the player's legal move list.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param pawnStructureScores The pawns-only pawn structure scores of both players.
   * @param playerLayout The layout of the player's pieces.
   * @param opponentLayout The layout of the opponent's pieces.
   * @param kingWeight The weight applied to the king safety, space control and attacking
   *                   potential terms, from {@link #kingTermWeight}.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board,
                       final MoveStatistics playerStatistics,
                       final MoveStatistics opponentStatistics,
                       final PawnStructure playerPawns,
                       final PawnStructure opponentPawns,
                       final PawnStructureCache.Entry pawnStructureScores,
                       final PieceLayout playerLayout,
                       final PieceLayout opponentLayout,
                       final double kingWeight) {
    return materialEvaluation(playerLayout) +
            mobilityEvaluation(playerStatistics) +
            kingWeight * kingSafetyEvaluation(player, playerPawns, opponentStatistics,
                    opponentLayout) +
            pawnStructureEvaluation(player, board, playerPawns, opponentPawns,
                    pawnStructureScores) +
            pieceCoordinationEvaluation(player, board, opponentStatistics,
                    playerPawns, opponentPawns, playerLayout) +
            kingWeight * spaceControlEvaluation(playerStatistics, opponentStatistics) +
            kingWeight * attackingPotentialEvaluation(player, playerStatistics, opponentLayout) +
            specialPatternsEvaluation(player, board, playerPawns, opponentPawns, playerLayout);
  }

  /**
   * Evaluates the material value of the player's pieces. The bishop pair is scored by
   * {@link #evaluateBishopPair}.
   *
   * @param playerLayout The layout of the player's pieces.
   * @return The material evaluation score.
   */
  private double materialEvaluation(final PieceLayout playerLayout) {
    return playerLayout.material();
  }

  /**
   * Evaluates piece mobility for the given player.
   * Counts the player's legal moves and adds a further weighting for the moves available to each
   * knight, bishop, rook and queen. The result is a per-player quantity and the caller forms the
   * difference between the two players.
   *
   * @param playerStatistics The statistics of the player's legal move list.
   * @return The mobility evaluation score.
   */
  private double mobilityEvaluation(final MoveStatistics playerStatistics) {
    final int[] moveCountByPieceType = playerStatistics.moveCountByPieceType();
    double mobilityScore = 0;

    mobilityScore += moveCountByPieceType[Piece.PieceType.KNIGHT.ordinal()] *
            WEIGHTS.get(KNIGHT_MOBILITY);
    mobilityScore += moveCountByPieceType[Piece.PieceType.BISHOP.ordinal()] *
            WEIGHTS.get(BISHOP_MOBILITY);
    mobilityScore += moveCountByPieceType[Piece.PieceType.ROOK.ordinal()] *
            WEIGHTS.get(ROOK_MOBILITY);
    mobilityScore += moveCountByPieceType[Piece.PieceType.QUEEN.ordinal()] *
            WEIGHTS.get(QUEEN_MOBILITY);

    mobilityScore += playerStatistics.moveCount() * WEIGHTS.get(MOBILITY);

    return mobilityScore;
  }

  /**
   * Evaluates king safety, considering pawn shield, attacking pieces,
   * open lines, and king attackers. This is a critical middlegame factor.
   *
   * @param player The player whose king safety is being evaluated.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @param opponentLayout The layout of the opponent's pieces.
   * @return The king safety evaluation score.
   */
  private double kingSafetyEvaluation(final Player player,
                                      final PawnStructure playerPawns,
                                      final MoveStatistics opponentStatistics,
                                      final PieceLayout opponentLayout) {
    double kingSafetyScore = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    if (playerKing.isOnCastledSquare()) {
      kingSafetyScore += WEIGHTS.get(KING_ON_CASTLED_SQUARE);
    }

    kingSafetyScore += evaluatePawnShield(player.getAlliance(), kingPosition, playerPawns);
    kingSafetyScore -= evaluateKingExposure(player, opponentStatistics);
    kingSafetyScore -= opponentLayout.kingTropism();
    kingSafetyScore -= evaluateOpenFilesToKing(kingPosition, playerPawns, opponentLayout);

    return kingSafetyScore;
  }

  /**
   * Evaluates the pawn shield protecting the king.
   *
   * @param alliance The alliance of the king.
   * @param kingPosition The position of the king on the board.
   * @param playerPawns The structure of the king's own pawns.
   * @return The pawn shield evaluation score.
   */
  private double evaluatePawnShield(final Alliance alliance, final int kingPosition,
                                    final PawnStructure playerPawns) {
    double shieldScore = 0;

    final long shieldTiles = alliance.isWhite() ?
            WHITE_KING_SHIELDS[kingPosition] :
            BLACK_KING_SHIELDS[kingPosition];
    final long shieldPawns = shieldTiles & playerPawns.occupancy();

    final int pawnsInShield = Long.bitCount(shieldPawns);
    final int intactFileCount = Integer.bitCount(occupiedFiles(shieldPawns));

    if (pawnsInShield >= 3) {
      shieldScore += WEIGHTS.get(SHIELD_THREE_PAWNS);
    } else if (pawnsInShield == 2) {
      shieldScore += WEIGHTS.get(SHIELD_TWO_PAWNS);
    } else if (pawnsInShield == 1) {
      shieldScore += WEIGHTS.get(SHIELD_ONE_PAWN);
    } else {
      shieldScore -= WEIGHTS.get(SHIELD_NO_PAWN);
    }

    shieldScore += intactFileCount * WEIGHTS.get(SHIELD_FILE);

    return shieldScore;
  }

  /**
   * Evaluates the king's exposure to attacks.
   *
   * @param player The player whose king's exposure is being evaluated.
   * @param opponentStatistics The statistics of the opposing player's legal move list, whose near
   *                           king quantities are measured against this player's king.
   * @return The king exposure score (higher values indicate more exposure).
   */
  private double evaluateKingExposure(final Player player,
                                      final MoveStatistics opponentStatistics) {
    double exposureScore = 0;
    final int[] nearKingCount = opponentStatistics.nearKingCountByPieceType();
    final int attacksNearKing = opponentStatistics.nearKingSquareCount();

    exposureScore += nearKingCount[Piece.PieceType.QUEEN.ordinal()] *
            WEIGHTS.get(QUEEN_MOVE_NEAR_KING);
    exposureScore += nearKingCount[Piece.PieceType.ROOK.ordinal()] *
            WEIGHTS.get(ROOK_MOVE_NEAR_KING);
    exposureScore += nearKingCount[Piece.PieceType.BISHOP.ordinal()] *
            WEIGHTS.get(BISHOP_MOVE_NEAR_KING);
    exposureScore += nearKingCount[Piece.PieceType.KNIGHT.ordinal()] *
            WEIGHTS.get(KNIGHT_MOVE_NEAR_KING);
    exposureScore += nearKingCount[Piece.PieceType.PAWN.ordinal()] *
            WEIGHTS.get(PAWN_MOVE_NEAR_KING);
    exposureScore += nearKingCount[Piece.PieceType.KING.ordinal()] *
            WEIGHTS.get(KING_MOVE_NEAR_KING);

    if (attacksNearKing > 2) {
      exposureScore += (attacksNearKing - 2) * WEIGHTS.get(EXCESS_MOVE_NEAR_KING);
    }

    if (player.isInCheck()) {
      exposureScore += WEIGHTS.get(IN_CHECK);
    }

    return exposureScore;
  }

  /**
   * Evaluates open files leading to the king, which can be dangerous.
   *
   * @param kingPosition The position of the king on the board.
   * @param playerPawns The structure of the king's own pawns.
   * @param opponentLayout The layout of the opponent's pieces.
   * @return The open files evaluation score (higher values indicate more exposure).
   */
  private double evaluateOpenFilesToKing(final int kingPosition,
                                         final PawnStructure playerPawns,
                                         final PieceLayout opponentLayout) {
    double openFileScore = 0;
    final int kingFile = kingPosition % 8;
    final int heavyPieceFiles = opponentLayout.heavyPieceFiles();

    if (!isPawnOnFile(kingFile, playerPawns)) {
      openFileScore += WEIGHTS.get(KING_FILE_OPEN);

      if ((heavyPieceFiles & (1 << kingFile)) != 0) {
        openFileScore += WEIGHTS.get(KING_FILE_HEAVY_PIECE);
      }
    }

    for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
      if (file == kingFile) continue;

      if (!isPawnOnFile(file, playerPawns)) {
        openFileScore += WEIGHTS.get(ADJACENT_FILE_OPEN);

        if ((heavyPieceFiles & (1 << file)) != 0) {
          openFileScore += WEIGHTS.get(ADJACENT_FILE_HEAVY_PIECE);
        }
      }
    }

    return openFileScore;
  }

  /**
   * Checks if a pawn of the given structure stands on the specified file.
   *
   * @param file The file to check for pawns (0-7).
   * @param pawns The pawn structure to check.
   * @return True if a pawn is found on the file, false otherwise.
   */
  private boolean isPawnOnFile(final int file, final PawnStructure pawns) {
    return pawns.countByFile()[file] > 0;
  }

  /**
   * Evaluates the pawn structure, considering passed pawns, isolated pawns,
   * doubled pawns, pawn chains, and pawn islands.
   *
   * @param player The player whose pawn structure is being evaluated.
   * @param board The current state of the chess board.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param pawnStructureScores The pawns-only pawn structure scores of both players.
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureEvaluation(final Player player, final Board board,
                                         final PawnStructure playerPawns,
                                         final PawnStructure opponentPawns,
                                         final PawnStructureCache.Entry pawnStructureScores) {
    return pawnStructureScores.scoreOf(player.getAlliance()) +
            evaluateUncontrolledStopSquares(playerPawns, opponentPawns,
                    player.getOpponent().getActivePieces(), player.getAlliance(), board);
  }

  /**
   * Evaluates the pawn structure terms that read nothing but the pawns of both players.
   *
   * @param alliance The alliance of the player whose pawn structure is being evaluated.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The pawns-only pawn structure evaluation score.
   */
  private double pawnOnlyStructureEvaluation(final Alliance alliance,
                                             final PawnStructure playerPawns,
                                             final PawnStructure opponentPawns) {
    double pawnStructureScore = 0;

    pawnStructureScore += evaluatePassedPawns(playerPawns, opponentPawns, alliance);
    pawnStructureScore += evaluatePawnIslands(playerPawns);
    pawnStructureScore += evaluateDoubledPawns(playerPawns);
    pawnStructureScore += evaluateIsolatedPawns(playerPawns, opponentPawns);
    pawnStructureScore += evaluateBackwardPawns(playerPawns, opponentPawns, alliance);
    pawnStructureScore += evaluatePawnChains(playerPawns, alliance);
    pawnStructureScore += evaluateCentralPawnControl(playerPawns);

    return pawnStructureScore;
  }

  /**
   * Evaluates passed pawns, which are more valuable in the middlegame. The bonus for a passed
   * pawn whose stop square no opponent piece controls is left to
   * {@link #evaluateUncontrolledStopSquares}.
   *
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param alliance The alliance of the player.
   * @return The passed pawns evaluation score.
   */
  private double evaluatePassedPawns(final PawnStructure playerPawns,
                                     final PawnStructure opponentPawns,
                                     final Alliance alliance) {
    double passedPawnScore = 0;

    for (final int pawnPosition : playerPawns.positions()) {
      if (isPassedPawn(pawnPosition, opponentPawns, alliance)) {
        final int rank = pawnPosition / 8;
        final int advancementBonus = alliance.isWhite() ? 7 - rank : rank;

        passedPawnScore += WEIGHTS.get(PASSED_PAWN) +
                (advancementBonus * WEIGHTS.get(PASSED_PAWN_RANK));

        if (isPawnProtected(pawnPosition, playerPawns, alliance)) {
          passedPawnScore += WEIGHTS.get(PASSED_PAWN_PROTECTED);
        }
      }
    }

    return passedPawnScore;
  }

  /**
   * Evaluates the player's passed pawns whose stop square no opponent piece controls.
   *
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param opponentPieces The opponent's pieces.
   * @param alliance The alliance of the player.
   * @param board The current state of the chess board.
   * @return The uncontrolled stop square evaluation score.
   */
  private double evaluateUncontrolledStopSquares(final PawnStructure playerPawns,
                                                 final PawnStructure opponentPawns,
                                                 final Collection<Piece> opponentPieces,
                                                 final Alliance alliance,
                                                 final Board board) {
    double stopSquareScore = 0;

    for (final int pawnPosition : playerPawns.positions()) {
      if (isPassedPawn(pawnPosition, opponentPawns, alliance) &&
              !opponentControlsStopSquare(pawnPosition, alliance, opponentPieces, board)) {
        stopSquareScore += WEIGHTS.get(PASSED_PAWN_FREE_STOP_SQUARE);
      }
    }

    return stopSquareScore;
  }

  /**
   * Checks if a pawn is a passed pawn (no opposing pawns in front or on adjacent files).
   *
   * @param pawnPosition The tile holding the pawn to check.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is passed, false otherwise.
   */
  private boolean isPassedPawn(final int pawnPosition, final PawnStructure opponentPawns,
                               final Alliance alliance) {
    final long[] frontSpans = alliance.isWhite() ? WHITE_FRONT_SPANS : BLACK_FRONT_SPANS;

    return (frontSpans[pawnPosition] & opponentPawns.occupancy()) == 0L;
  }

  /**
   * Checks if a pawn is protected by another pawn.
   *
   * @param pawnPosition The tile holding the pawn to check for protection.
   * @param playerPawns The structure of the player's pawns.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtected(final int pawnPosition, final PawnStructure playerPawns,
                                  final Alliance alliance) {
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;

    final int rankBehind = alliance.isWhite() ? pawnRank + 1 : pawnRank - 1;

    if (rankBehind < 0 || rankBehind >= 8) {
      return false;
    }

    if (pawnFile > 0 && playerPawns.hasPawnAt(rankBehind * 8 + (pawnFile - 1))) {
      return true;
    }

    return pawnFile < 7 && playerPawns.hasPawnAt(rankBehind * 8 + (pawnFile + 1));
  }

  /**
   * Checks if any opponent piece controls the stop square of the pawn, meaning the square
   * directly ahead of it. A piece standing on the stop square counts only if it also bears on
   * that square, and a pawn with no square ahead of it is reported as uncontrolled.
   *
   * @param pawnPosition The tile holding the pawn to check.
   * @param alliance The alliance of the pawn.
   * @param opponentPieces The opponent's pieces.
   * @param board The current state of the chess board.
   * @return True if an opponent piece controls the stop square, false otherwise.
   */
  private boolean opponentControlsStopSquare(final int pawnPosition,
                                             final Alliance alliance,
                                             final Collection<Piece> opponentPieces,
                                             final Board board) {
    final int pawnRank = pawnPosition / 8;

    final int rankDirection = alliance.isWhite() ? -1 : 1;
    final int frontRank = pawnRank + rankDirection;

    if (frontRank < 0 || frontRank >= 8) {
      return false;
    }

    final int stopSquare = (frontRank * 8) + (pawnPosition % 8);

    for (final Piece opponentPiece : opponentPieces) {
      if (opponentPiece.defendsSquare(stopSquare, board)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Evaluates pawn islands (groups of connected pawns).
   *
   * @param playerPawns The structure of the player's pawns.
   * @return The pawn islands evaluation score.
   */
  private double evaluatePawnIslands(final PawnStructure playerPawns) {
    double islandScore = 0;

    final int[] countByFile = playerPawns.countByFile();

    int islands = 0;
    boolean inIsland = false;

    for (int file = 0; file < 8; file++) {
      if (countByFile[file] > 0) {
        if (!inIsland) {
          islands++;
          inIsland = true;
        }
      } else {
        inIsland = false;
      }
    }

    if (islands > 1) {
      islandScore -= (islands - 1) * WEIGHTS.get(PAWN_ISLAND);
    }

    return islandScore;
  }

  /**
   * Evaluates doubled pawns (multiple pawns on the same file).
   *
   * @param playerPawns The structure of the player's pawns.
   * @return The doubled pawns evaluation score.
   */
  private double evaluateDoubledPawns(final PawnStructure playerPawns) {
    double doubledPawnScore = 0;

    for (int count : playerPawns.countByFile()) {
      if (count > 1) {
        doubledPawnScore -= (count - 1) * WEIGHTS.get(DOUBLED_PAWN);
      }
    }

    return doubledPawnScore;
  }

  /**
   * Evaluates isolated pawns (pawns with no friendly pawns on adjacent files).
   *
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The isolated pawns evaluation score.
   */
  private double evaluateIsolatedPawns(final PawnStructure playerPawns,
                                       final PawnStructure opponentPawns) {
    double isolatedPawnScore = 0;

    final int[] countByFile = playerPawns.countByFile();

    for (final int pawnPosition : playerPawns.positions()) {
      final int pawnFile = pawnPosition % 8;
      boolean isIsolated = pawnFile <= 0 || countByFile[pawnFile - 1] == 0;

      if (pawnFile < 7 && countByFile[pawnFile + 1] > 0) {
        isIsolated = false;
      }

      if (isIsolated) {
        isolatedPawnScore -= WEIGHTS.get(ISOLATED_PAWN);

        if (isOnSemiOpenFile(pawnFile, opponentPawns)) {
          isolatedPawnScore -= WEIGHTS.get(ISOLATED_PAWN_SEMI_OPEN);
        }

        if (pawnFile > 1 && pawnFile < 6) {
          isolatedPawnScore -= WEIGHTS.get(ISOLATED_PAWN_CENTRAL);
        }
      }
    }

    return isolatedPawnScore;
  }

  /**
   * Checks if a file is semi-open, meaning no opponent pawn stands on it.
   *
   * @param file The file to check (0-7).
   * @param opponentPawns The structure of the opponent's pawns.
   * @return True if the file is semi-open, false otherwise.
   */
  private boolean isOnSemiOpenFile(final int file, final PawnStructure opponentPawns) {
    return !isPawnOnFile(file, opponentPawns);
  }

  /**
   * Evaluates backward pawns (pawns that can't be protected by adjacent pawns).
   *
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param alliance The alliance of the player.
   * @return The backward pawns evaluation score.
   */
  private double evaluateBackwardPawns(final PawnStructure playerPawns,
                                       final PawnStructure opponentPawns,
                                       final Alliance alliance) {
    double backwardPawnScore = 0;

    for (final int pawnPosition : playerPawns.positions()) {
      if (isBackward(alliance, pawnPosition, playerPawns)) {
        backwardPawnScore -= WEIGHTS.get(BACKWARD_PAWN);

        if (isOnSemiOpenFile(pawnPosition % 8, opponentPawns)) {
          backwardPawnScore -= WEIGHTS.get(BACKWARD_PAWN_SEMI_OPEN);
        }
      }
    }

    return backwardPawnScore;
  }

  /**
   * Determines if a pawn is backward, meaning it has at least one friendly pawn on an adjacent
   * file and every such pawn is strictly more advanced than it is. Rank indices run from zero on
   * black's back rank to seven on white's, so a smaller index is more advanced for white and a
   * larger index is more advanced for black.
   *
   * @param alliance The alliance of the pawn.
   * @param pawnPosition The tile holding the pawn to check.
   * @param playerPawns The structure of the player's pawns.
   * @return True if the pawn is backward, false otherwise.
   */
  private static boolean isBackward(final Alliance alliance,
                                    final int pawnPosition,
                                    final PawnStructure playerPawns) {
    final int file = pawnPosition % 8;
    final int rank = pawnPosition / 8;

    final int[] countByFile = playerPawns.countByFile();
    final int[] rearmostRankByFile = playerPawns.rearmostRankByFile();

    boolean hasAdjacentPawn = false;

    for (int adjacentFile = file - 1; adjacentFile <= file + 1; adjacentFile += 2) {
      if (adjacentFile < 0 || adjacentFile > 7 || countByFile[adjacentFile] == 0) {
        continue;
      }

      hasAdjacentPawn = true;
      final int adjacentRank = rearmostRankByFile[adjacentFile];

      if (alliance.isWhite() ? adjacentRank >= rank : adjacentRank <= rank) {
        return false;
      }
    }

    return hasAdjacentPawn;
  }

  /**
   * Evaluates pawn chains (connected pawns that protect each other).
   *
   * @param playerPawns The structure of the player's pawns.
   * @param alliance The alliance of the pawns.
   * @return The pawn chains evaluation score.
   */
  private double evaluatePawnChains(final PawnStructure playerPawns, final Alliance alliance) {
    double pawnChainScore = 0;

    final int[] filesByRank = playerPawns.filesByRank();

    int chainLinks = 0;
    for (final int pawnPosition : playerPawns.positions()) {
      final int rank = pawnPosition / 8;
      final int file = pawnPosition % 8;

      if (isPawnProtectingFile(filesByRank, rank, file, alliance)) {
        chainLinks++;
      }
    }

    pawnChainScore += chainLinks * WEIGHTS.get(PAWN_CHAIN_LINK);

    if (chainLinks >= 3) {
      pawnChainScore += WEIGHTS.get(PAWN_CHAIN);
    }

    return pawnChainScore;
  }

  /**
   * Checks if a pawn is protected by another pawn on an adjacent file. Rank indices run from zero
   * on black's back rank to seven on white's, so a protecting pawn stands one index higher for
   * white and one index lower for black.
   *
   * @param filesByRank The files holding a pawn on each rank, indexed by rank, as one bit per
   *                    file.
   * @param rank The rank of the pawn.
   * @param file The file of the pawn.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtectingFile(final int[] filesByRank,
                                       final int rank,
                                       final int file,
                                       final Alliance alliance) {
    final int protectingRank = alliance.isWhite() ? rank + 1 : rank - 1;

    if (protectingRank < 0 || protectingRank > 7) {
      return false;
    }

    final int filesWithPawns = filesByRank[protectingRank];

    return (file > 0 && (filesWithPawns & (1 << (file - 1))) != 0) ||
            (file < 7 && (filesWithPawns & (1 << (file + 1))) != 0);
  }

  /**
   * Evaluates central pawn control, which is important in the middlegame.
   *
   * @param playerPawns The structure of the player's pawns.
   * @return The central pawn control evaluation score.
   */
  private double evaluateCentralPawnControl(final PawnStructure playerPawns) {
    double centralControlScore = 0;
    final int[] centralSquares = {27, 28, 35, 36};
    final int[] attackCountBySquare = playerPawns.attackCountBySquare();

    for (final int centralSquare : centralSquares) {
      if (playerPawns.hasPawnAt(centralSquare)) {
        centralControlScore += WEIGHTS.get(CENTRAL_PAWN);
      }

      centralControlScore += attackCountBySquare[centralSquare] *
              WEIGHTS.get(CENTRAL_PAWN_ATTACK);
    }

    return centralControlScore;
  }

  /**
   * Evaluates piece coordination, synergy and cooperative control.
   *
   * @param player The player whose piece coordination is being evaluated.
   * @param board The current chess board.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param playerLayout The layout of the player's pieces.
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationEvaluation(final Player player, final Board board,
                                             final MoveStatistics opponentStatistics,
                                             final PawnStructure playerPawns,
                                             final PawnStructure opponentPawns,
                                             final PieceLayout playerLayout) {
    double coordinationScore = 0;

    coordinationScore += evaluateBishopPair(playerLayout.bishops());
    coordinationScore += evaluateRookCoordination(playerLayout.rooks(), playerPawns, opponentPawns);
    coordinationScore += evaluatePieceProtection(player.getActivePieces(), board,
            opponentStatistics);
    coordinationScore += evaluatePieceActivity(playerLayout);

    return coordinationScore;
  }

  /**
   * Evaluates bishop pair bonus, which is significant in middlegame.
   *
   * @param bishops The tiles holding the player's bishops, as one bit per tile.
   * @return The bishop pair evaluation score.
   */
  private double evaluateBishopPair(final long bishops) {
    double bishopPairScore = 0;
    final boolean hasLightSquareBishop = (bishops & LIGHT_TILES) != 0L;
    final boolean hasDarkSquareBishop = (bishops & ~LIGHT_TILES) != 0L;

    if (hasLightSquareBishop && hasDarkSquareBishop) {
      bishopPairScore += WEIGHTS.get(BISHOP_PAIR);
    }

    return bishopPairScore;
  }

  /**
   * Evaluates rook coordination, including connected rooks and rooks on open and semi-open files.
   *
   * @param rooks The tiles holding the player's rooks, as one bit per tile.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The rook coordination evaluation score.
   */
  private double evaluateRookCoordination(final long rooks,
                                          final PawnStructure playerPawns,
                                          final PawnStructure opponentPawns) {
    double rookScore = 0;

    if (Long.bitCount(rooks) >= 2) {
      int sameRankPairs = 0;
      int sameFilePairs = 0;

      for (int line = 0; line < 8; line++) {
        final int rooksOnRank = Long.bitCount(rooks & (RANK_TILES << (line * 8)));
        final int rooksOnFile = Long.bitCount(rooks & (FILE_TILES << line));

        sameRankPairs += rooksOnRank * (rooksOnRank - 1) / 2;
        sameFilePairs += rooksOnFile * (rooksOnFile - 1) / 2;
      }

      rookScore += sameRankPairs * WEIGHTS.get(ROOKS_SHARING_RANK);
      rookScore += sameFilePairs * WEIGHTS.get(ROOKS_SHARING_FILE);

      if (sameRankPairs + sameFilePairs > 0) {
        rookScore += WEIGHTS.get(ROOKS_CONNECTED);
      }
    }

    for (long remaining = rooks; remaining != 0L; remaining &= remaining - 1) {
      final int rookFile = Long.numberOfTrailingZeros(remaining) % 8;

      if (!isPawnOnFile(rookFile, playerPawns)) {
        rookScore += isPawnOnFile(rookFile, opponentPawns) ?
                WEIGHTS.get(ROOK_SEMI_OPEN_FILE) :
                WEIGHTS.get(ROOK_OPEN_FILE);
      }
    }

    return rookScore;
  }

  /**
   * Evaluates knight outposts - knights protected by pawns and in opponent's territory.
   *
   * @param knights The tiles holding the player's knights, as one bit per tile.
   * @param alliance The alliance of the player.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The knight outposts evaluation score.
   */
  private double evaluateKnightOutposts(final long knights,
                                        final Alliance alliance,
                                        final PawnStructure playerPawns,
                                        final PawnStructure opponentPawns) {
    double outpostScore = 0;

    for (long remaining = knights; remaining != 0L; remaining &= remaining - 1) {
      final int position = Long.numberOfTrailingZeros(remaining);
      final int file = position % 8;
      final int rank = position / 8;

      boolean inOpponentTerritory = (alliance.isWhite() && rank < 4) ||
              (!alliance.isWhite() && rank > 3);

      if (inOpponentTerritory) {
        boolean canBeAttackedByPawn = opponentPawns.attackCountBySquare()[position] > 0;

        if (!canBeAttackedByPawn) {
          outpostScore += WEIGHTS.get(KNIGHT_OUTPOST);

          if (playerPawns.attackCountBySquare()[position] > 0) {
            outpostScore += WEIGHTS.get(KNIGHT_OUTPOST_PROTECTED);
          }

          if (file >= 2 && file <= 5) {
            outpostScore += WEIGHTS.get(KNIGHT_OUTPOST_CENTRAL);
          }
        }
      }
    }

    return outpostScore;
  }

  /**
   * Evaluates how well pieces protect each other and charges the player for the single most
   * valuable piece the opponent threatens. A piece is threatened when the opponent attacks its
   * square more times than the player defends it, or when it is worth more than a pawn and stands
   * on a square an opposing pawn attacks. Only the largest such threat is charged, at
   * {@link #THREAT_FRACTION} of the threatened piece's value, so the penalty this term can produce
   * is bounded by that fraction of a queen.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board state.
   * @param opponentStatistics The statistics of the opposing player's legal move list.
   * @return The piece protection evaluation score.
   */
  private double evaluatePieceProtection(final Collection<Piece> playerPieces,
                                         final Board board,
                                         final MoveStatistics opponentStatistics) {
    double protectionScore = 0;
    double largestThreat = 0;

    final int[] squareAttackCount = opponentStatistics.destinationCount();
    final int[] squarePawnAttackCount = opponentStatistics.pawnDestinationCount();
    final int[] defenderCount = new int[BoardUtils.NUM_TILES];

    for (final Piece piece : playerPieces) {
      piece.addDefendedSquares(board, defenderCount);
    }

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int protectionCount = defenderCount[position];
      final int attackCount = squareAttackCount[position];
      final int pieceValue = piece.getPieceValue();

      final boolean outnumbered = attackCount > protectionCount;
      final boolean harriedByPawn = squarePawnAttackCount[position] > 0
              && pieceValue > Piece.PieceType.PAWN.getPieceValue();

      if (outnumbered || harriedByPawn) {
        largestThreat = Math.max(largestThreat, pieceValue * WEIGHTS.get(THREAT_FRACTION));
      } else if (attackCount == 0 && protectionCount > 0) {
        protectionScore += WEIGHTS.get(PROTECTED_PIECE);

        if (piece.getPieceType() == Piece.PieceType.QUEEN) {
          protectionScore += WEIGHTS.get(QUEEN_DEFENDER) * protectionCount;
        } else if (piece.getPieceType() == Piece.PieceType.ROOK) {
          protectionScore += WEIGHTS.get(ROOK_DEFENDER) * protectionCount;
        }
      }
    }

    return protectionScore - largestThreat;
  }

  /**
   * Evaluates piece activity, particularly centralization of pieces.
   *
   * @param playerLayout The layout of the player's pieces.
   * @return The piece activity evaluation score.
   */
  private double evaluatePieceActivity(final PieceLayout playerLayout) {
    double activityScore = 0;

    final long knights = playerLayout.knights();
    final long bishops = playerLayout.bishops();
    final long rooks = playerLayout.rooks();
    final long scoredPieces = knights | bishops | rooks | playerLayout.queens();

    for (long remaining = scoredPieces; remaining != 0L; remaining &= remaining - 1) {
      final int position = Long.numberOfTrailingZeros(remaining);
      final long tile = remaining & -remaining;
      final int file = position % 8;
      final int rank = position / 8;

      int fileDistance = Math.min(Math.abs(file - 3), Math.abs(file - 4));
      int rankDistance = Math.min(Math.abs(rank - 3), Math.abs(rank - 4));
      int distanceFromCenter = fileDistance + rankDistance;

      if ((knights & tile) != 0L) {
        activityScore += (6 - distanceFromCenter) * WEIGHTS.get(KNIGHT_CENTRALITY);
      } else if ((bishops & tile) != 0L) {
        activityScore += (6 - distanceFromCenter) * WEIGHTS.get(BISHOP_CENTRALITY);
      } else if ((rooks & tile) != 0L) {
        activityScore += (3 - fileDistance) * WEIGHTS.get(ROOK_CENTRALITY);
      } else {
        activityScore += (6 - distanceFromCenter) * WEIGHTS.get(QUEEN_CENTRALITY);
      }
    }

    return activityScore;
  }

  /**
   * Evaluates space control for the given player.
   * Scores the player's moves into the enemy half and into the extended center, the player's
   * control of the four central squares, and the player's clamped legal move advantage over the
   * opponent. Every term but the clamped move advantage is a per-player quantity and the caller
   * forms the difference between the two players.
   *
   * @param playerStatistics The statistics of the player's legal move list.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @return The space control evaluation score.
   */
  private double spaceControlEvaluation(final MoveStatistics playerStatistics,
                                        final MoveStatistics opponentStatistics) {
    double spaceScore = 0;
    final int[] controlledSquares = playerStatistics.destinationCount();

    spaceScore += playerStatistics.spaceCount() * WEIGHTS.get(SPACE);
    final int moveAdvantage = playerStatistics.moveCount() - opponentStatistics.moveCount();
    spaceScore += Math.max(-10, Math.min(moveAdvantage, 10)) * WEIGHTS.get(MOVE_ADVANTAGE);

    final int[] keySquares = {27, 28, 35, 36};
    for (final int square : keySquares) {
      spaceScore += controlledSquares[square] * WEIGHTS.get(CENTRAL_SQUARE_MOVE);
    }

    return spaceScore;
  }

  /**
   * Evaluates the attacking potential against the opponent's king.
   *
   * @param player The player whose attacking potential is being evaluated.
   * @param playerStatistics The statistics of the player's legal move list.
   * @param opponentLayout The layout of the opponent's pieces.
   * @return The attacking potential evaluation score.
   */
  private double attackingPotentialEvaluation(final Player player,
                                              final MoveStatistics playerStatistics,
                                              final PieceLayout opponentLayout) {
    double attackScore = 0;
    final King opponentKing = player.getOpponent().getPlayerKing();
    final int kingPosition = opponentKing.getPiecePosition();
    final int[] nearKingCount = playerStatistics.nearKingCountByPieceType();

    final int attackingSquaresCount = playerStatistics.nearKingSquareCount();

    final boolean queenAttacking = nearKingCount[Piece.PieceType.QUEEN.ordinal()] > 0;
    final boolean rookAttacking = nearKingCount[Piece.PieceType.ROOK.ordinal()] > 0;
    final boolean bishopAttacking = nearKingCount[Piece.PieceType.BISHOP.ordinal()] > 0;
    final boolean knightAttacking = nearKingCount[Piece.PieceType.KNIGHT.ordinal()] > 0;
    final boolean pawnAttacking = nearKingCount[Piece.PieceType.PAWN.ordinal()] > 0;

    int attackingPiecesCount = 0;
    if (queenAttacking) attackingPiecesCount++;
    if (rookAttacking) attackingPiecesCount++;
    if (bishopAttacking) attackingPiecesCount++;
    if (knightAttacking) attackingPiecesCount++;
    if (pawnAttacking) attackingPiecesCount++;

    if (attackingPiecesCount >= 2) {
      attackScore += WEIGHTS.get(KING_ATTACKER) * attackingPiecesCount;
      attackScore += WEIGHTS.get(KING_ATTACK_MOVE) * attackingSquaresCount;

      if (queenAttacking) attackScore += WEIGHTS.get(QUEEN_KING_ATTACK);
      if (rookAttacking) attackScore += WEIGHTS.get(ROOK_KING_ATTACK);
      if (bishopAttacking) attackScore += WEIGHTS.get(BISHOP_KING_ATTACK);
      if (knightAttacking) attackScore += WEIGHTS.get(KNIGHT_KING_ATTACK);
      if (pawnAttacking) attackScore += WEIGHTS.get(PAWN_KING_ATTACK);

      final boolean opponentKingOnCastledSquare = opponentKing.isOnCastledSquare();
      final boolean opponentChecked = player.getOpponent().isInCheck();

      if (opponentChecked) {
        attackScore += WEIGHTS.get(KING_ATTACK_CHECK);
      }

      if (!opponentKingOnCastledSquare) {
        attackScore += WEIGHTS.get(KING_ATTACK_UNCASTLED);
      }

      final int defendingPiecesCount =
              Long.bitCount(KING_ZONES[kingPosition] & opponentLayout.nonKingPieces());
      attackScore -= defendingPiecesCount * WEIGHTS.get(KING_ZONE_DEFENDER);
    }

    return attackScore;
  }

  /**
   * Evaluates special positional patterns that are important in the middlegame.
   *
   * @param player The player whose special patterns are being evaluated.
   * @param board The current chess board.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param playerLayout The layout of the player's pieces.
   * @return The special patterns evaluation score.
   */
  private double specialPatternsEvaluation(final Player player, final Board board,
                                           final PawnStructure playerPawns,
                                           final PawnStructure opponentPawns,
                                           final PieceLayout playerLayout) {
    double patternScore = 0;
    final Alliance alliance = player.getAlliance();

    patternScore += evaluateRooksOn7thRank(playerLayout.rooks(), board, alliance, opponentPawns);
    patternScore += evaluateFianchetto(playerLayout.bishops(), alliance);
    patternScore += evaluateBadBishops(playerLayout.bishops(), playerPawns);
    patternScore += evaluateKnightOutposts(playerLayout.knights(), alliance, playerPawns,
            opponentPawns);
    patternScore += evaluateQueenPositioning(playerLayout, board, alliance);

    return patternScore;
  }

  /**
   * Evaluates rooks on the 7th rank (or 2nd for black), which is often very strong.
   *
   * @param rooks The tiles holding the player's rooks, as one bit per tile.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The rooks on 7th rank evaluation score.
   */
  private double evaluateRooksOn7thRank(final long rooks,
                                        final Board board,
                                        final Alliance alliance,
                                        final PawnStructure opponentPawns) {
    double rookScore = 0;
    final int seventhRank = alliance.isWhite() ? 1 : 6;
    final long rooksOnSeventh = rooks & (RANK_TILES << (seventhRank * 8));

    for (long remaining = rooksOnSeventh; remaining != 0L; remaining &= remaining - 1) {
      rookScore += WEIGHTS.get(ROOK_ON_SEVENTH);
      rookScore += WEIGHTS.get(ROOK_ON_SEVENTH_PAWN) *
              Integer.bitCount(opponentPawns.filesByRank()[seventhRank]);

      final King opponentKing = opposingKing(board, alliance);
      final int kingRank = opponentKing.getPiecePosition() / 8;

      if ((alliance.isWhite() && kingRank == 0) || (!alliance.isWhite() && kingRank == 7)) {
        rookScore += WEIGHTS.get(ROOK_ON_SEVENTH_KING);
      }
    }

    return rookScore;
  }

  /**
   * Evaluates bishops standing on the fianchetto squares b2 and g2 for white, or b7 and g7 for
   * black. The supporting pawn structure is not inspected.
   *
   * @param bishops The tiles holding the player's bishops, as one bit per tile.
   * @param alliance The alliance of the player.
   * @return The fianchetto evaluation score.
   */
  private double evaluateFianchetto(final long bishops, final Alliance alliance) {
    double fianchettoScore = 0;

    final int kingsideBishopPosition = alliance.isWhite() ? 54 : 14;
    final int queensideBishopPosition = alliance.isWhite() ? 49 : 9;

    if (((bishops >>> kingsideBishopPosition) & 1L) != 0L) {
      fianchettoScore += WEIGHTS.get(KINGSIDE_FIANCHETTO);
    }

    if (((bishops >>> queensideBishopPosition) & 1L) != 0L) {
      fianchettoScore += WEIGHTS.get(QUEENSIDE_FIANCHETTO);
    }

    return fianchettoScore;
  }

  /**
   * Evaluates bad bishops - bishops blocked by their own pawns.
   *
   * @param bishops The tiles holding the player's bishops, as one bit per tile.
   * @param playerPawns The structure of the player's pawns.
   * @return The bad bishops evaluation score.
   */
  private double evaluateBadBishops(final long bishops,
                                    final PawnStructure playerPawns) {
    double badBishopScore = 0;

    for (long remaining = bishops; remaining != 0L; remaining &= remaining - 1) {
      int pawnsOnSameColor =
              getPawnsOnSameColor(playerPawns, Long.numberOfTrailingZeros(remaining));

      if (pawnsOnSameColor >= 3) {
        badBishopScore -= WEIGHTS.get(BAD_BISHOP_THREE_PAWNS);
      } else if (pawnsOnSameColor == 2) {
        badBishopScore -= WEIGHTS.get(BAD_BISHOP_TWO_PAWNS);
      }
    }

    return badBishopScore;
  }

  /**
   * Counts the number of pawns on the same colored squares as the bishop.
   *
   * @param playerPawns The structure of the player's pawns.
   * @param position The tile holding the bishop.
   * @return The number of pawns on the same colored squares.
   */
  private static int getPawnsOnSameColor(final PawnStructure playerPawns, final int position) {
    final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

    return isLightSquare ? playerPawns.lightSquareCount() : playerPawns.darkSquareCount();
  }

  /**
   * Evaluates queen positioning in the middlegame.
   *
   * @param playerLayout The layout of the player's pieces.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @return The queen positioning evaluation score.
   */
  private double evaluateQueenPositioning(final PieceLayout playerLayout,
                                          final Board board,
                                          final Alliance alliance) {
    double queenScore = 0;

    for (long remaining = playerLayout.queens(); remaining != 0L; remaining &= remaining - 1) {
      final int position = Long.numberOfTrailingZeros(remaining);
      final int rank = position / 8;
      final int file = position % 8;

      if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5) {
        queenScore += WEIGHTS.get(QUEEN_CENTRAL);
      }

      boolean queenTooAdvanced = false;
      if (alliance.isWhite() && rank < 2) {
        queenTooAdvanced = true;
      } else if (!alliance.isWhite() && rank > 5) {
        queenTooAdvanced = true;
      }

      if (queenTooAdvanced) {
        int supportingPieces = getSupportingPieces(playerLayout, alliance, rank);

        if (supportingPieces < 2) {
          queenScore -= WEIGHTS.get(QUEEN_UNSUPPORTED);
        }
      }

      final King opponentKing = opposingKing(board, alliance);
      final int kingPosition = opponentKing.getPiecePosition();

      final int distance = calculateChebyshevDistance(position, kingPosition);
      if (distance <= 2) {
        queenScore += WEIGHTS.get(QUEEN_NEAR_KING);
      } else if (distance == 3) {
        queenScore += WEIGHTS.get(QUEEN_THREE_FROM_KING);
      }
    }

    return queenScore;
  }

  /**
   * Counts the player's pieces other than the king and the queens that stand on the given rank or
   * further advanced than it.
   *
   * @param playerLayout The layout of the player's pieces.
   * @param alliance The alliance of the player.
   * @param rank The rank of the queen.
   * @return The number of supporting pieces.
   */
  private static int getSupportingPieces(final PieceLayout playerLayout,
                                         final Alliance alliance,
                                         final int rank) {
    final long ranksBehind = alliance.isWhite() ?
            -1L >>> (64 - ((rank + 1) * 8)) :
            -1L << (rank * 8);

    return Long.bitCount(playerLayout.nonKingPieces() & ~playerLayout.queens() & ranksBehind);
  }

  /**
   * Calculates the Chebyshev distance between two squares.
   *
   * @param position1 The first position.
   * @param position2 The second position.
   * @return The Chebyshev distance between the positions.
   */
  private int calculateChebyshevDistance(final int position1, final int position2) {
    final int file1 = position1 % 8;
    final int rank1 = position1 / 8;
    final int file2 = position2 % 8;
    final int rank2 = position2 / 8;

    return Math.max(Math.abs(file1 - file2), Math.abs(rank1 - rank2));
  }

  /**
   * Retrieves the king of the alliance opposing the given one. The returned king is null if the
   * opposing alliance has no king on the board.
   *
   * @param board The current chess board.
   * @param alliance The alliance whose opposing king to retrieve.
   * @return The king opposing the given alliance.
   */
  private static King opposingKing(final Board board, final Alliance alliance) {
    return board.getKing(alliance.isWhite() ? Alliance.BLACK : Alliance.WHITE);
  }

  /**
   * Builds the table of tiles lying within two ranks and two files of each tile.
   *
   * @return The tiles near each tile, indexed by tile, as one bit per tile.
   */
  private static long[] computeKingZones() {
    final long[] kingZones = new long[BoardUtils.NUM_TILES];

    for (int center = 0; center < BoardUtils.NUM_TILES; center++) {
      for (int tile = 0; tile < BoardUtils.NUM_TILES; tile++) {
        final int rankDistance = Math.abs((center / 8) - (tile / 8));
        final int fileDistance = Math.abs((center % 8) - (tile % 8));

        if (Math.max(rankDistance, fileDistance) <= 2) {
          kingZones[center] |= 1L << tile;
        }
      }
    }

    return kingZones;
  }

  /**
   * Builds the table of the space count a move of the given alliance earns by landing on each
   * tile. A tile counts once for lying in the alliance's forward half and once for lying in the
   * extended centre.
   *
   * @param alliance The alliance whose moves the table scores.
   * @return The space count of each tile, indexed by tile.
   */
  private static int[] computeSpaceWeights(final Alliance alliance) {
    final int[] spaceWeights = new int[BoardUtils.NUM_TILES];

    for (int tile = 0; tile < BoardUtils.NUM_TILES; tile++) {
      final int rank = tile / 8;
      final int file = tile % 8;

      if ((alliance.isWhite() && rank < 4) || (!alliance.isWhite() && rank > 3)) {
        spaceWeights[tile]++;
      }

      if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5) {
        spaceWeights[tile]++;
      }
    }

    return spaceWeights;
  }

  /**
   * Builds the set of tiles on which a pawn of the given alliance promotes.
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

  /**
   * Builds the table of the tiles forming the pawn shield of a king of the given alliance standing
   * on each tile. The shield is the up to three tiles directly ahead of the king, and for a king on
   * its back rank on file 1, 2, 5 or 6 also the up to three tiles one rank further ahead. A king on
   * the rank furthest from its own side has no shield.
   *
   * @param alliance The alliance of the king.
   * @return The shield tiles of each tile, indexed by tile, as one bit per tile.
   */
  private static long[] computeKingShields(final Alliance alliance) {
    final long[] kingShields = new long[BoardUtils.NUM_TILES];

    for (int kingPosition = 0; kingPosition < BoardUtils.NUM_TILES; kingPosition++) {
      final int kingFile = kingPosition % 8;
      final int kingRank = kingPosition / 8;
      final int shieldRank = alliance.isWhite() ? kingRank - 1 : kingRank + 1;

      if (shieldRank < 0 || shieldRank >= 8) {
        continue;
      }

      final boolean onFlankFile = kingFile == 1 || kingFile == 2 || kingFile == 5 || kingFile == 6;
      final boolean onBackRank = alliance.isWhite() ? kingRank == 7 : kingRank == 0;
      final int secondShieldRank = alliance.isWhite() ? kingRank - 2 : kingRank + 2;

      for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
        kingShields[kingPosition] |= 1L << (shieldRank * 8 + file);

        if (onFlankFile && onBackRank) {
          kingShields[kingPosition] |= 1L << (secondShieldRank * 8 + file);
        }
      }
    }

    return kingShields;
  }

  /**
   * Builds the table of the tiles ahead of a pawn of the given alliance standing on each tile, on
   * its own file and the files beside it, up to and including the rank furthest from its own side.
   *
   * @param alliance The alliance of the pawn.
   * @return The front span of each tile, indexed by tile, as one bit per tile.
   */
  private static long[] computeFrontSpans(final Alliance alliance) {
    final long[] frontSpans = new long[BoardUtils.NUM_TILES];

    for (int pawnPosition = 0; pawnPosition < BoardUtils.NUM_TILES; pawnPosition++) {
      final int pawnFile = pawnPosition % 8;
      final int pawnRank = pawnPosition / 8;

      for (int rank = 0; rank < 8; rank++) {
        if (alliance.isWhite() ? rank >= pawnRank : rank <= pawnRank) {
          continue;
        }

        for (int file = Math.max(0, pawnFile - 1); file <= Math.min(7, pawnFile + 1); file++) {
          frontSpans[pawnPosition] |= 1L << (rank * 8 + file);
        }
      }
    }

    return frontSpans;
  }

  /**
   * Returns the files holding at least one of the given tiles.
   *
   * @param tiles The tiles, as one bit per tile.
   * @return The files holding at least one of the tiles, as one bit per file.
   */
  private static int occupiedFiles(final long tiles) {
    long files = tiles;

    files |= files >>> 32;
    files |= files >>> 16;
    files |= files >>> 8;

    return (int) (files & RANK_TILES);
  }
}