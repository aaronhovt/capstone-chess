package engine.forPlayer.forAI;

import com.google.common.annotations.VisibleForTesting;
import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.*;
import engine.forPlayer.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The EndgameBoardEvaluator class provides specialized evaluation functions for chess endgame positions.
 * This evaluator focuses on endgame-specific factors such as king activity, passed pawns, pawn structure,
 * and piece coordination that become critical when few pieces remain on the board. The evaluation considers
 * material balance, king centralization, pawn promotion potential, and various endgame patterns to provide
 * accurate position assessments for late-game scenarios.
 *
 * @author Aaron Ho
 */
public class EndgameBoardEvaluator implements BoardEvaluator {

  /** Singleton instance of the EndgameBoardEvaluator. */
  private static final EndgameBoardEvaluator Instance = new EndgameBoardEvaluator();

  /**
   * The weights this evaluator scores with. Each index constant below names one weight, and its
   * description states what the weight scores.
   */
  private static final EvaluationWeights WEIGHTS = new EvaluationWeights();

  /** The material value of a pawn. */
  private static final int PAWN_VALUE = WEIGHTS.add("PAWN_VALUE", 100);

  /** The material value of a knight. */
  private static final int KNIGHT_VALUE = WEIGHTS.add("KNIGHT_VALUE", 310);

  /** The material value of a knight with four or fewer pieces other than pawns and kings left. */
  private static final int KNIGHT_VALUE_DEEP = WEIGHTS.add("KNIGHT_VALUE_DEEP", 290);

  /** The material value of a bishop. */
  private static final int BISHOP_VALUE = WEIGHTS.add("BISHOP_VALUE", 320);

  /** The material value of a bishop with four or fewer pieces other than pawns and kings left. */
  private static final int BISHOP_VALUE_DEEP = WEIGHTS.add("BISHOP_VALUE_DEEP", 330);

  /** The material value of a rook. */
  private static final int ROOK_VALUE = WEIGHTS.add("ROOK_VALUE", 500);

  /** The material value of a rook with four or fewer pieces other than pawns and kings left. */
  private static final int ROOK_VALUE_DEEP = WEIGHTS.add("ROOK_VALUE_DEEP", 530);

  /** The material value of a queen. */
  private static final int QUEEN_VALUE = WEIGHTS.add("QUEEN_VALUE", 900);

  /** The material bonus for a favourable combination such as queen against rook. */
  private static final int FAVOURABLE_MATERIAL = WEIGHTS.add("FAVOURABLE_MATERIAL", 100);

  /** The factor of the king's closeness to the centre. */
  private static final int KING_CENTRALITY = WEIGHTS.add("KING_CENTRALITY", 10);

  /** The bonus for the opposition, kings two tiles apart on a rank or file. */
  private static final int OPPOSITION = WEIGHTS.add("OPPOSITION", 20);

  /** The bonus per own pawn within one tile of the king. */
  private static final int KING_BESIDE_OWN_PAWN = WEIGHTS.add("KING_BESIDE_OWN_PAWN", 10);

  /** The bonus per own pawn two tiles from the king. */
  private static final int KING_NEAR_OWN_PAWN = WEIGHTS.add("KING_NEAR_OWN_PAWN", 5);

  /** The bonus per opposing pawn within one tile of the king. */
  private static final int KING_BESIDE_OPPOSING_PAWN =
          WEIGHTS.add("KING_BESIDE_OPPOSING_PAWN", 8);

  /** The bonus per opposing pawn two tiles from the king. */
  private static final int KING_NEAR_OPPOSING_PAWN = WEIGHTS.add("KING_NEAR_OPPOSING_PAWN", 4);

  /** The exposure per opposing move landing on the king's square, halved when charged. */
  private static final int KING_SQUARE_MOVE = WEIGHTS.add("KING_SQUARE_MOVE", 30);

  /** The exposure per opposing move landing beside or on the king, halved when charged. */
  private static final int KING_ZONE_MOVE = WEIGHTS.add("KING_ZONE_MOVE", 5);

  /** The exposure for being in check, halved when charged. */
  private static final int IN_CHECK = WEIGHTS.add("IN_CHECK", 20);

  /** The bonus per passed pawn per rank it has advanced. */
  private static final int PASSED_PAWN_RANK = WEIGHTS.add("PASSED_PAWN_RANK", 20);

  /** The further bonus per passed pawn within two ranks of promotion. */
  private static final int PASSED_PAWN_NEAR_PROMOTION =
          WEIGHTS.add("PASSED_PAWN_NEAR_PROMOTION", 50);

  /** The further bonus per passed pawn three or four ranks from promotion. */
  private static final int PASSED_PAWN_MIDWAY = WEIGHTS.add("PASSED_PAWN_MIDWAY", 30);

  /** The bonus per passed pawn per tile the own king is closer than eight. */
  private static final int PASSED_PAWN_OWN_KING = WEIGHTS.add("PASSED_PAWN_OWN_KING", 5);

  /** The bonus per passed pawn per tile the opposing king is away from it. */
  private static final int PASSED_PAWN_OPPOSING_KING =
          WEIGHTS.add("PASSED_PAWN_OPPOSING_KING", 3);

  /** The further bonus per passed pawn with no piece ahead of it on its file. */
  private static final int PASSED_PAWN_CLEAR_PATH = WEIGHTS.add("PASSED_PAWN_CLEAR_PATH", 40);

  /** The further bonus per passed pawn protected by a pawn. */
  private static final int PASSED_PAWN_PROTECTED = WEIGHTS.add("PASSED_PAWN_PROTECTED", 25);

  /** The bonus per pair of passed pawns on adjacent files. */
  private static final int CONNECTED_PASSED_PAWNS = WEIGHTS.add("CONNECTED_PASSED_PAWNS", 80);

  /** The further bonus per such pair whose more advanced pawn is within two ranks of promotion. */
  private static final int CONNECTED_PASSED_PAWNS_ADVANCED =
          WEIGHTS.add("CONNECTED_PASSED_PAWNS_ADVANCED", 50);

  /** The penalty per pawn island beyond the first. */
  private static final int PAWN_ISLAND = WEIGHTS.add("PAWN_ISLAND", 15);

  /** The penalty per pawn on a file beyond the first. */
  private static final int DOUBLED_PAWN = WEIGHTS.add("DOUBLED_PAWN", 25);

  /** The penalty per isolated pawn. */
  private static final int ISOLATED_PAWN = WEIGHTS.add("ISOLATED_PAWN", 20);

  /** The further penalty per isolated pawn on a file with no opposing pawn. */
  private static final int ISOLATED_PAWN_SEMI_OPEN = WEIGHTS.add("ISOLATED_PAWN_SEMI_OPEN", 10);

  /** The bonus per wing on which the player has more pawns than the opponent. */
  private static final int PAWN_MAJORITY = WEIGHTS.add("PAWN_MAJORITY", 15);

  /** The further bonus per pawn of such a majority. */
  private static final int PAWN_MAJORITY_PAWN = WEIGHTS.add("PAWN_MAJORITY_PAWN", 5);

  /** The bonus per pawn protected by a pawn. */
  private static final int PAWN_CHAIN_LINK = WEIGHTS.add("PAWN_CHAIN_LINK", 5);

  /** The penalty per backward pawn. */
  private static final int BACKWARD_PAWN = WEIGHTS.add("BACKWARD_PAWN", 15);

  /** The further penalty per backward pawn on a file with no opposing pawn. */
  private static final int BACKWARD_PAWN_SEMI_OPEN = WEIGHTS.add("BACKWARD_PAWN_SEMI_OPEN", 10);

  /** The penalty per knight with four or fewer pawns left. */
  private static final int KNIGHT_FEW_PAWNS = WEIGHTS.add("KNIGHT_FEW_PAWNS", 10);

  /** The bonus per bishop with an own pawn ahead of it on its file or beside. */
  private static final int BISHOP_BEHIND_PAWNS = WEIGHTS.add("BISHOP_BEHIND_PAWNS", 10);

  /** The bonus per bishop on one of the long diagonal squares this evaluator recognises. */
  private static final int BISHOP_LONG_DIAGONAL = WEIGHTS.add("BISHOP_LONG_DIAGONAL", 15);

  /** The factor of a knight's closeness to each own pawn within two tiles. */
  private static final int KNIGHT_NEAR_PAWN = WEIGHTS.add("KNIGHT_NEAR_PAWN", 5);

  /** The bonus per piece within one tile of an own passed pawn. */
  private static final int PASSED_PAWN_ESCORT = WEIGHTS.add("PASSED_PAWN_ESCORT", 20);

  /** The bonus per piece two tiles from an own passed pawn. */
  private static final int PASSED_PAWN_NEAR_ESCORT = WEIGHTS.add("PASSED_PAWN_NEAR_ESCORT", 10);

  /** The bonus per piece with a legal move to an own passed pawn's promotion square. */
  private static final int PROMOTION_SQUARE_CONTROL =
          WEIGHTS.add("PROMOTION_SQUARE_CONTROL", 15);

  /** The bonus per rook on a file with no pawn. */
  private static final int ROOK_OPEN_FILE = WEIGHTS.add("ROOK_OPEN_FILE", 30);

  /** The bonus per rook on a file with only opposing pawns. */
  private static final int ROOK_SEMI_OPEN_FILE = WEIGHTS.add("ROOK_SEMI_OPEN_FILE", 15);

  /** The bonus per rook behind an own passed pawn on its file. */
  private static final int ROOK_BEHIND_OWN_PASSER = WEIGHTS.add("ROOK_BEHIND_OWN_PASSER", 30);

  /** The bonus per rook behind an opposing passed pawn on its file. */
  private static final int ROOK_BEHIND_OPPOSING_PASSER =
          WEIGHTS.add("ROOK_BEHIND_OPPOSING_PASSER", 20);

  /** The bonus per rook on the seventh rank. */
  private static final int ROOK_ON_SEVENTH = WEIGHTS.add("ROOK_ON_SEVENTH", 30);

  /** The further bonus per such rook per opposing pawn on that rank. */
  private static final int ROOK_ON_SEVENTH_PAWN = WEIGHTS.add("ROOK_ON_SEVENTH_PAWN", 10);

  /** The bonus per pair of rooks sharing a rank. */
  private static final int ROOKS_SHARING_RANK = WEIGHTS.add("ROOKS_SHARING_RANK", 40);

  /** The bonus per pair of rooks sharing a file. */
  private static final int ROOKS_SHARING_FILE = WEIGHTS.add("ROOKS_SHARING_FILE", 30);

  /** The bonus per legal bishop move. */
  private static final int BISHOP_MOBILITY = WEIGHTS.add("BISHOP_MOBILITY", 5);

  /** The bonus for bishops on both colours of square. */
  private static final int BISHOP_PAIR = WEIGHTS.add("BISHOP_PAIR", 150);

  /** The bonus for a single-coloured bishop whose colour holds fewer pawns. */
  private static final int GOOD_BISHOP_COLOUR = WEIGHTS.add("GOOD_BISHOP_COLOUR", 20);

  /** The penalty for a single-coloured bishop whose colour holds more pawns. */
  private static final int BAD_BISHOP_COLOUR = WEIGHTS.add("BAD_BISHOP_COLOUR", 15);

  /** The bonus for bishop against knight with five or fewer pawns left. */
  private static final int BISHOP_AGAINST_KNIGHT_OPEN =
          WEIGHTS.add("BISHOP_AGAINST_KNIGHT_OPEN", 20);

  /** The penalty for bishop against knight with eight or more pawns left. */
  private static final int BISHOP_AGAINST_KNIGHT_CLOSED =
          WEIGHTS.add("BISHOP_AGAINST_KNIGHT_CLOSED", 10);

  /** The factor applied to the score when the sides hold bishops on opposite colours. */
  private static final int OPPOSITE_BISHOPS_SCALE = WEIGHTS.add("OPPOSITE_BISHOPS_SCALE", 0.75);

  /**
   * The factor applied to the score when the sides hold bishops on opposite colours and two or
   * fewer pawns each.
   */
  private static final int OPPOSITE_BISHOPS_FEW_PAWNS_SCALE =
          WEIGHTS.add("OPPOSITE_BISHOPS_FEW_PAWNS_SCALE", 0.5);

  /** The bonus for rook and pawn against rook with the pawn on its seventh rank or beyond. */
  private static final int ROOK_PAWN_ADVANCED = WEIGHTS.add("ROOK_PAWN_ADVANCED", 100);

  /** The penalty for rook and pawn against rook with the pawn short of its seventh rank. */
  private static final int ROOK_PAWN_BEHIND = WEIGHTS.add("ROOK_PAWN_BEHIND", 100);

  /** The bonus per legal move. */
  private static final int MOBILITY = WEIGHTS.add("MOBILITY", 4);

  /** The factor applied to mobility in a pawn endgame. */
  private static final int PAWN_ENDGAME_MOBILITY_FACTOR =
          WEIGHTS.add("PAWN_ENDGAME_MOBILITY_FACTOR", 0.5);

  /** The factor applied to mobility with opposite coloured bishops. */
  private static final int OPPOSITE_BISHOPS_MOBILITY_FACTOR =
          WEIGHTS.add("OPPOSITE_BISHOPS_MOBILITY_FACTOR", 1.5);

  /** The fraction of a threatened piece's value charged against the side that owns it. */
  private static final int THREAT_FRACTION = WEIGHTS.add("THREAT_FRACTION", 0.25);

  /** The number of distinct piece types. */
  private static final int PIECE_TYPE_COUNT = Piece.PieceType.values().length;

  /** The tiles on which a white pawn promotes, as one bit per tile. */
  private static final long WHITE_PROMOTION_TILES = computePromotionTiles(Alliance.WHITE);

  /** The tiles on which a black pawn promotes, as one bit per tile. */
  private static final long BLACK_PROMOTION_TILES = computePromotionTiles(Alliance.BLACK);

  /** The pawn structure scores of both players, keyed by the tiles their pawns occupy. */
  private static final PawnStructureCache PAWN_STRUCTURE_CACHE = new PawnStructureCache();

  /** Private constructor to prevent instantiation outside of class. */
  private EndgameBoardEvaluator() {}

  /**
   * Returns the singleton instance of EndgameBoardEvaluator.
   *
   * @return The instance of EndgameBoardEvaluator.
   */
  public static EndgameBoardEvaluator get() {
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
   * Evaluates the given board from the perspective of both players and returns a score.
   * The evaluation considers endgame-specific factors and returns a positive score when
   * white has an advantage and a negative score when black has an advantage.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board position.
   */
  @Override
  public double evaluate(final Board board) {
    final List<Piece> whitePawns = getPlayerPawns(board.whitePlayer());
    final List<Piece> blackPawns = getPlayerPawns(board.blackPlayer());
    final long whitePawnOccupancy = PawnLists.occupancy(whitePawns);
    final long blackPawnOccupancy = PawnLists.occupancy(blackPawns);
    final PawnLists pawns = new PawnLists(whitePawns, blackPawns, whitePawnOccupancy,
            blackPawnOccupancy, passedPawns(whitePawns, blackPawnOccupancy, Alliance.WHITE),
            passedPawns(blackPawns, whitePawnOccupancy, Alliance.BLACK));
    final Collection<Piece> whitePieces = board.whitePlayer().getActivePieces();
    final Collection<Piece> blackPieces = board.blackPlayer().getActivePieces();
    final Material material = new Material(countPieceTypes(whitePieces),
            countPieceTypes(blackPieces), hasOppositeColoredBishops(whitePieces, blackPieces));

    if (isDrawnByMaterial(material)) {
      return 0;
    }

    final MoveTargets whiteTargets = moveTargets(board, board.whitePlayer());
    final MoveTargets blackTargets = moveTargets(board, board.blackPlayer());
    final PawnStructureCache.Entry pawnStructure = pawnStructureScores(board, pawns);

    return (score(board.whitePlayer(), board, pawns, pawnStructure, material, whiteTargets,
                    blackTargets) -
            score(board.blackPlayer(), board, pawns, pawnStructure, material, blackTargets,
                    whiteTargets)) * drawishScale(material);
  }

  /**
   * Returns whether neither side can win with the material on the board. That is the case for a
   * bare king against a bare king, a single knight or bishop against a bare king, one bishop each
   * on squares of the same colour, and two knights against a bare king, which cannot force mate.
   * Every case requires that no pawn, rook or queen remains.
   *
   * @param material The piece counts of both players.
   * @return True if the material on the board is drawn, false otherwise.
   */
  private static boolean isDrawnByMaterial(final Material material) {
    final PieceCounts white = material.white();
    final PieceCounts black = material.black();

    if (white.of(Piece.PieceType.PAWN) + black.of(Piece.PieceType.PAWN) +
            white.of(Piece.PieceType.ROOK) + black.of(Piece.PieceType.ROOK) +
            white.of(Piece.PieceType.QUEEN) + black.of(Piece.PieceType.QUEEN) > 0) {
      return false;
    }

    final int knights = white.of(Piece.PieceType.KNIGHT) + black.of(Piece.PieceType.KNIGHT);
    final int bishops = white.of(Piece.PieceType.BISHOP) + black.of(Piece.PieceType.BISHOP);

    if (knights + bishops <= 1) {
      return true;
    }

    if (knights == 0 && white.of(Piece.PieceType.BISHOP) == 1 &&
            black.of(Piece.PieceType.BISHOP) == 1) {
      return !material.oppositeColoredBishops();
    }

    return bishops == 0 && knights == 2 &&
            (white.of(Piece.PieceType.KNIGHT) == 2 || black.of(Piece.PieceType.KNIGHT) == 2);
  }

  /**
   * Returns the factor by which the score is scaled toward a draw. The score is scaled by
   * {@link #OPPOSITE_BISHOPS_FEW_PAWNS_SCALE} when the sides hold bishops on opposite colours and
   * two or fewer pawns each, by {@link #OPPOSITE_BISHOPS_SCALE} when they hold bishops on opposite
   * colours and more pawns, and is otherwise left unscaled.
   *
   * @param material The piece counts of both players.
   * @return The factor applied to the score.
   */
  private static double drawishScale(final Material material) {
    if (!material.oppositeColoredBishops()) {
      return 1.0;
    }

    if (material.white().of(Piece.PieceType.PAWN) <= 2 &&
            material.black().of(Piece.PieceType.PAWN) <= 2) {
      return WEIGHTS.get(OPPOSITE_BISHOPS_FEW_PAWNS_SCALE);
    }

    return WEIGHTS.get(OPPOSITE_BISHOPS_SCALE);
  }

  /**
   * Returns the pawn structure scores of both players, from the cache when it holds them for
   * these pawns and otherwise computed and stored.
   *
   * @param board The current state of the chess board.
   * @param pawns The pawns of both players.
   * @return The pawn structure scores of both players.
   */
  private PawnStructureCache.Entry pawnStructureScores(final Board board, final PawnLists pawns) {
    final PawnStructureCache.Entry cached =
            PAWN_STRUCTURE_CACHE.probe(pawns.whiteOccupancy(), pawns.blackOccupancy());

    if (cached != null) {
      return cached;
    }

    final PawnStructureCache.Entry computed = new PawnStructureCache.Entry(
            pawns.whiteOccupancy(), pawns.blackOccupancy(),
            pawnStructureEvaluation(board.whitePlayer(), board, pawns),
            pawnStructureEvaluation(board.blackPlayer(), board, pawns));
    PAWN_STRUCTURE_CACHE.store(computed);
    return computed;
  }

  /**
   * The piece counts of both players, read once per evaluation.
   *
   * @param white The number of white's pieces of each type.
   * @param black The number of black's pieces of each type.
   * @param oppositeColoredBishops Whether each player holds exactly one bishop and the two bishops
   *                               stand on squares of different colours.
   */
  private record Material(PieceCounts white, PieceCounts black, boolean oppositeColoredBishops) {

    /**
     * Returns the piece counts of the given player.
     *
     * @param player The player whose piece counts are requested.
     * @return That player's piece counts.
     */
    private PieceCounts of(final Player player) {
      return player.getAlliance().isWhite() ? white : black;
    }

    /**
     * Returns the number of pieces of both players that are neither pawns nor kings.
     *
     * @return The number of such pieces on the board.
     */
    private int nonPawnPieceCount() {
      return white.nonPawnPieceCount() + black.nonPawnPieceCount();
    }
  }

  /**
   * The pawns of both players, read once per evaluation. The lists belong to this record and must
   * not be modified by a caller.
   *
   * @param white The tiles holding white's pawns, in board iteration order.
   * @param black The tiles holding black's pawns, in board iteration order.
   * @param whiteOccupancy The tiles holding white's pawns, as one bit per tile.
   * @param blackOccupancy The tiles holding black's pawns, as one bit per tile.
   * @param whitePassed The tiles holding white's passed pawns, as one bit per tile.
   * @param blackPassed The tiles holding black's passed pawns, as one bit per tile.
   */
  private record PawnLists(List<Piece> white, List<Piece> black, long whiteOccupancy,
                           long blackOccupancy, long whitePassed, long blackPassed) {

    /**
     * Returns the pawns belonging to the given player.
     *
     * @param player The player whose pawns are requested.
     * @return That player's pawns.
     */
    private List<Piece> of(final Player player) {
      return player.getAlliance().isWhite() ? white : black;
    }

    /**
     * Returns the tiles held by the given player's pawns, as one bit per tile.
     *
     * @param player The player whose pawn occupancy is requested.
     * @return That player's pawn occupancy.
     */
    private long occupancyOf(final Player player) {
      return player.getAlliance().isWhite() ? whiteOccupancy : blackOccupancy;
    }

    /**
     * Returns the tiles held by the given player's passed pawns, as one bit per tile.
     *
     * @param player The player whose passed pawns are requested.
     * @return That player's passed pawn occupancy.
     */
    private long passedOf(final Player player) {
      return player.getAlliance().isWhite() ? whitePassed : blackPassed;
    }

    /**
     * Returns the tiles held by the given pawns, as one bit per tile.
     *
     * @param pawns The pawns to read.
     * @return The occupancy of those pawns.
     */
    private static long occupancy(final List<Piece> pawns) {
      long occupancy = 0L;

      for (final Piece pawn : pawns) {
        occupancy |= 1L << pawn.getPiecePosition();
      }

      return occupancy;
    }
  }

  /**
   * The destinations of one player's legal moves. The array belongs to this record and must not
   * be modified by a caller.
   *
   * @param moveCount The number of legal moves.
   * @param destinationCount The number of legal moves ending on each tile, indexed by tile.
   * @param pawnDestinations The tiles on which at least one pawn move ends, as one bit per tile.
   */
  private record MoveTargets(int moveCount, int[] destinationCount, long pawnDestinations) { }

  /**
   * Counts the given player's legal moves from each piece's destination squares, without
   * generating the player's legal move list. A pawn move onto the promotion rank counts once per
   * promotion piece, and the player's castling moves count as king moves.
   *
   * @param board The current state of the chess board.
   * @param player The player whose legal moves are being counted.
   * @return The destinations of that player's legal moves.
   */
  private static MoveTargets moveTargets(final Board board, final Player player) {
    final long promotionTiles = player.getAlliance().isWhite() ?
            WHITE_PROMOTION_TILES :
            BLACK_PROMOTION_TILES;

    long castleDestinations = 0L;
    for (final Move castle : player.getCastleMoves()) {
      castleDestinations |= 1L << castle.getDestinationCoordinate();
    }

    final int[] destinationCount = new int[BoardUtils.NUM_TILES];
    long pawnDestinations = 0L;
    int moveCount = 0;

    for (final Piece piece : player.getActivePieces()) {
      final Piece.PieceType pieceType = piece.getPieceType();
      final boolean isPawn = pieceType == Piece.PieceType.PAWN;

      long destinations = piece.legalDestinations(board);
      if (pieceType == Piece.PieceType.KING) {
        destinations |= castleDestinations;
      }

      if (isPawn) {
        pawnDestinations |= destinations;
      }

      for (long remaining = destinations; remaining != 0L; remaining &= remaining - 1) {
        final int destination = Long.numberOfTrailingZeros(remaining);
        final int moves = isPawn && ((promotionTiles >>> destination) & 1L) != 0L ? 4 : 1;

        moveCount += moves;
        destinationCount[destination] += moves;
      }
    }

    return new MoveTargets(moveCount, destinationCount, pawnDestinations);
  }

  /**
   * Calculates the overall score of the current board position for a given player
   * using modern chess engine evaluation principles tuned for endgame positions.
   * The evaluation combines material assessment, king activity, passed pawn evaluation,
   * pawn structure analysis, piece coordination, and endgame-specific patterns.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @param pawns The pawns of both players.
   * @param pawnStructure The pawn structure scores of both players.
   * @param material The piece counts of both players.
   * @param playerTargets The destinations of the player's legal moves.
   * @param opponentTargets The destinations of the opponent's legal moves.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board, final PawnLists pawns,
                       final PawnStructureCache.Entry pawnStructure, final Material material,
                       final MoveTargets playerTargets, final MoveTargets opponentTargets) {
    return materialEvaluation(player, material) +
            kingActivityEvaluation(player, pawns, opponentTargets) +
            passedPawnEvaluation(player, board, pawns) +
            pawnStructure.scoreOf(player.getAlliance()) +
            pieceCoordinationEvaluation(player, board, pawns, material) +
            rookEndgameEvaluation(player, board, pawns) +
            bishopEndgameEvaluation(player, board, pawns, material) +
            drawPatternEvaluation(player, pawns, material) +
            mobilityEvaluation(player, material, playerTargets) +
            pieceSafetyEvaluation(player, board, opponentTargets);
  }

  /**
   * Evaluates material balance with specific endgame piece values and recognizes
   * favorable endgame material combinations.
   *
   * @param player The player whose material is being evaluated.
   * @param material The piece counts of both players.
   * @return The material evaluation score for the player.
   */
  private double materialEvaluation(final Player player, final Material material) {
    double materialScore = 0;
    final boolean isEndgame = isDeepEndgame(material.nonPawnPieceCount());

    final PieceCounts playerPieceCounts = material.of(player);
    final PieceCounts opponentPieceCounts = material.of(player.getOpponent());

    materialScore += playerPieceCounts.of(Piece.PieceType.PAWN) * WEIGHTS.get(PAWN_VALUE);
    materialScore += playerPieceCounts.of(Piece.PieceType.KNIGHT) *
            WEIGHTS.get(isEndgame ? KNIGHT_VALUE_DEEP : KNIGHT_VALUE);
    materialScore += playerPieceCounts.of(Piece.PieceType.BISHOP) *
            WEIGHTS.get(isEndgame ? BISHOP_VALUE_DEEP : BISHOP_VALUE);
    materialScore += playerPieceCounts.of(Piece.PieceType.ROOK) *
            WEIGHTS.get(isEndgame ? ROOK_VALUE_DEEP : ROOK_VALUE);
    materialScore += playerPieceCounts.of(Piece.PieceType.QUEEN) * WEIGHTS.get(QUEEN_VALUE);
    materialScore += playerPieceCounts.of(Piece.PieceType.KING) * 10000;

    if (evaluateSpecialMaterialCombinations(playerPieceCounts, opponentPieceCounts, player.getAlliance())) {
      materialScore += WEIGHTS.get(FAVOURABLE_MATERIAL);
    }

    return materialScore;
  }

  /**
   * The number of pieces of each type held by one player. The array belongs to this record and
   * must not be modified by a caller.
   *
   * @param byType The number of pieces of each type, indexed by piece type ordinal.
   */
  private record PieceCounts(int[] byType) {

    /**
     * Returns the number of pieces of the given type.
     *
     * @param type The piece type to read.
     * @return The number of pieces of that type.
     */
    private int of(final Piece.PieceType type) {
      return byType[type.ordinal()];
    }

    /**
     * Returns the number of pieces that are neither pawns nor kings.
     *
     * @return The number of such pieces.
     */
    private int nonPawnPieceCount() {
      return of(Piece.PieceType.KNIGHT) + of(Piece.PieceType.BISHOP) +
              of(Piece.PieceType.ROOK) + of(Piece.PieceType.QUEEN);
    }
  }

  /**
   * Counts the number of each piece type in the given collection of pieces.
   *
   * @param pieces The collection of pieces to count.
   * @return The counts of each piece type.
   */
  private PieceCounts countPieceTypes(final Collection<Piece> pieces) {
    final int[] byType = new int[PIECE_TYPE_COUNT];

    for (final Piece piece : pieces) {
      byType[piece.getPieceType().ordinal()]++;
    }

    return new PieceCounts(byType);
  }

  /**
   * Checks if the position is in a deep endgame state with few pieces remaining.
   * A deep endgame is characterized by having 4 or fewer non-pawn, non-king pieces.
   *
   * @param nonPawnPieceCount The number of pieces of both players that are neither pawns nor kings.
   * @return True if the position is in a deep endgame, false otherwise.
   */
  private boolean isDeepEndgame(final int nonPawnPieceCount) {
    return nonPawnPieceCount <= 4;
  }

  /**
   * Evaluates special material combinations that could be advantageous in the endgame.
   * This includes combinations like queen versus rook, rook versus minor piece,
   * or bishop pair versus knight.
   *
   * @param playerPieceCounts The piece counts for the player.
   * @param opponentPieceCounts The piece counts for the opponent.
   * @param playerAlliance The alliance of the player being evaluated.
   * @return True if the player has a favorable material combination, false otherwise.
   */
  private boolean evaluateSpecialMaterialCombinations(final PieceCounts playerPieceCounts,
                                                      final PieceCounts opponentPieceCounts,
                                                      final Alliance playerAlliance) {
    if (playerPieceCounts.of(Piece.PieceType.QUEEN) > 0 &&
            opponentPieceCounts.of(Piece.PieceType.ROOK) > 0 &&
            opponentPieceCounts.of(Piece.PieceType.QUEEN) == 0) {
      return true;
    }

    if (playerPieceCounts.of(Piece.PieceType.ROOK) > 0 &&
            (opponentPieceCounts.of(Piece.PieceType.BISHOP) > 0 ||
                    opponentPieceCounts.of(Piece.PieceType.KNIGHT) > 0) &&
            opponentPieceCounts.of(Piece.PieceType.ROOK) == 0 &&
            opponentPieceCounts.of(Piece.PieceType.QUEEN) == 0) {
      return true;
    }

    return playerPieceCounts.of(Piece.PieceType.BISHOP) >= 2 &&
            opponentPieceCounts.of(Piece.PieceType.KNIGHT) > 0 &&
            opponentPieceCounts.of(Piece.PieceType.BISHOP) == 0;
  }

  /**
   * Checks if the position has opposite-colored bishops, which often leads to
   * drawish tendencies in the endgame. The check requires each side to hold exactly one bishop,
   * and reports false for any other bishop count.
   *
   * @param playerPieces The player's pieces.
   * @param opponentPieces The opponent's pieces.
   * @return True if opposite-colored bishops exist, false otherwise.
   */
  private boolean hasOppositeColoredBishops(final Collection<Piece> playerPieces,
                                            final Collection<Piece> opponentPieces) {
    final Bishop playerBishop = onlyBishop(playerPieces);
    final Bishop opponentBishop = onlyBishop(opponentPieces);

    if (playerBishop == null || opponentBishop == null) {
      return false;
    }

    final int playerSquareColor = squareColor(playerBishop.getPiecePosition());
    final int opponentSquareColor = squareColor(opponentBishop.getPiecePosition());

    return playerSquareColor != opponentSquareColor;
  }

  /**
   * Returns the single bishop held by a side.
   *
   * @param pieces The pieces of one side.
   * @return The only bishop among the pieces, or null when the side holds no bishop or more
   *         than one.
   */
  private static Bishop onlyBishop(final Collection<Piece> pieces) {
    Bishop found = null;

    for (final Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        if (found != null) {
          return null;
        }

        found = (Bishop) piece;
      }
    }

    return found;
  }

  /**
   * Returns the colour of a square as zero or one, where squares sharing a value share a colour.
   *
   * @param position The position of the square.
   * @return The colour of the square.
   */
  private static int squareColor(final int position) {
    return (position / 8 + position % 8) % 2;
  }

  /**
   * Evaluates king activity, which is a critical factor in endgames. Active kings
   * that can participate in the game by attacking pawns, supporting their own pawns,
   * or controlling key squares are highly valued in endgame positions.
   *
   * @param player The player whose king activity is being evaluated.
   * @param pawns The pawns of both players.
   * @param opponentTargets The destinations of the opponent's legal moves.
   * @return The king activity evaluation score.
   */
  private double kingActivityEvaluation(final Player player, final PawnLists pawns,
                                        final MoveTargets opponentTargets) {
    double kingActivityScore = 0;
    final King playerKing = player.getPlayerKing();
    final King opponentKing = player.getOpponent().getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    kingActivityScore += evaluateKingCentralization(kingPosition);
    kingActivityScore += evaluateKingProximity(kingPosition, opponentKing.getPiecePosition());
    kingActivityScore += evaluateKingPawnDefense(player, pawns);
    kingActivityScore -= evaluateKingExposure(player, opponentTargets) * 0.5;

    return kingActivityScore;
  }

  /**
   * Evaluates king centralization, which is crucial in endgames. Kings positioned
   * near the center of the board are generally more active and effective.
   *
   * @param kingPosition The position of the king on the board.
   * @return The centralization score for the king.
   */
  private double evaluateKingCentralization(final int kingPosition) {
    final int kingFile = kingPosition % 8;
    final int kingRank = kingPosition / 8;

    final double fileDistance = Math.abs(kingFile - 3.5);
    final double rankDistance = Math.abs(kingRank - 3.5);
    final double distanceFromCenter = fileDistance + rankDistance;

    return (7 - distanceFromCenter) * WEIGHTS.get(KING_CENTRALITY);
  }

  /**
   * Evaluates king proximity to the opponent king, which is important in certain
   * endgames, particularly king and pawn endings where opposition matters.
   *
   * @param kingPosition The position of the player's king.
   * @param opponentKingPosition The position of the opponent's king.
   * @return The proximity evaluation score.
   */
  private double evaluateKingProximity(final int kingPosition, final int opponentKingPosition) {
    final int kingFile = kingPosition % 8;
    final int kingRank = kingPosition / 8;
    final int opponentKingFile = opponentKingPosition % 8;
    final int opponentKingRank = opponentKingPosition / 8;

    final int kingDistance = Math.max(
            Math.abs(kingFile - opponentKingFile),
            Math.abs(kingRank - opponentKingRank)
    );

    if (kingDistance == 2 && ((kingFile == opponentKingFile) || (kingRank == opponentKingRank))) {
      return WEIGHTS.get(OPPOSITION);
    }

    return 0;
  }

  /**
   * Evaluates how well the king defends friendly pawns and attacks enemy pawns.
   * In endgames, kings become active pieces that should participate in both
   * defense and attack operations.
   *
   * @param player The player whose king-pawn cooperation is being evaluated.
   * @param pawns The pawns of both players.
   * @return The king-pawn defense evaluation score.
   */
  private double evaluateKingPawnDefense(final Player player, final PawnLists pawns) {
    double kingPawnDefenseScore = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();
    final List<Piece> playerPawns = pawns.of(player);
    final List<Piece> opponentPawns = pawns.of(player.getOpponent());

    for (final Piece pawn : playerPawns) {
      final int distance = calculateChebyshevDistance(kingPosition, pawn.getPiecePosition());

      if (distance <= 1) {
        kingPawnDefenseScore += WEIGHTS.get(KING_BESIDE_OWN_PAWN);
      } else if (distance == 2) {
        kingPawnDefenseScore += WEIGHTS.get(KING_NEAR_OWN_PAWN);
      }
    }

    for (final Piece pawn : opponentPawns) {
      final int distance = calculateChebyshevDistance(kingPosition, pawn.getPiecePosition());

      if (distance <= 1) {
        kingPawnDefenseScore += WEIGHTS.get(KING_BESIDE_OPPOSING_PAWN);
      } else if (distance == 2) {
        kingPawnDefenseScore += WEIGHTS.get(KING_NEAR_OPPOSING_PAWN);
      }
    }

    return kingPawnDefenseScore;
  }

  /**
   * Evaluates king exposure to checks and attacks. While less critical in endgames
   * than in middlegames, king safety is still relevant, especially when queens
   * remain on the board.
   *
   * @param player The player whose king safety is being evaluated.
   * @param opponentTargets The destinations of the opponent's legal moves.
   * @return The king exposure evaluation score.
   */
  private double evaluateKingExposure(final Player player, final MoveTargets opponentTargets) {
    double exposureScore = 0;
    final int[] destinationCount = opponentTargets.destinationCount();
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();
    final int kingFile = kingPosition % 8;
    final int kingRank = kingPosition / 8;

    exposureScore += destinationCount[kingPosition] * WEIGHTS.get(KING_SQUARE_MOVE);

    for (int rank = Math.max(kingRank - 1, 0); rank <= Math.min(kingRank + 1, 7); rank++) {
      for (int file = Math.max(kingFile - 1, 0); file <= Math.min(kingFile + 1, 7); file++) {
        exposureScore += destinationCount[rank * 8 + file] * WEIGHTS.get(KING_ZONE_MOVE);
      }
    }

    if (player.isInCheck()) {
      exposureScore += WEIGHTS.get(IN_CHECK);
    }

    return exposureScore;
  }

  /**
   * Evaluates passed pawns, which are especially important in endgames due to
   * their promotion potential. This evaluation considers pawn advancement,
   * king support, and path clearance to the promotion square.
   *
   * @param player The player whose passed pawns are being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @return The passed pawn evaluation score.
   */
  private double passedPawnEvaluation(final Player player, final Board board,
                                      final PawnLists pawns) {
    double passedPawnScore = 0;
    final List<Piece> playerPawns = pawns.of(player);
    final long playerPawnOccupancy = pawns.occupancyOf(player);
    final long passedPawns = pawns.passedOf(player);
    final Alliance alliance = player.getAlliance();
    final King playerKing = player.getPlayerKing();
    final King opponentKing = player.getOpponent().getPlayerKing();

    for (final Piece pawn : playerPawns) {
      if (isPawnAtPosition(passedPawns, pawn.getPiecePosition())) {
        final int pawnPosition = pawn.getPiecePosition();
        final int pawnRank = pawnPosition / 8;
        final int rankFromPromotion = alliance.isWhite() ? pawnRank : (7 - pawnRank);
        final double advancementScore = (7 - rankFromPromotion) * WEIGHTS.get(PASSED_PAWN_RANK);
        passedPawnScore += advancementScore;

        if (rankFromPromotion <= 2) {
          passedPawnScore += WEIGHTS.get(PASSED_PAWN_NEAR_PROMOTION);
        } else if (rankFromPromotion <= 4) {
          passedPawnScore += WEIGHTS.get(PASSED_PAWN_MIDWAY);
        }

        final int kingDistance = calculateChebyshevDistance(playerKing.getPiecePosition(), pawnPosition);
        passedPawnScore += (8 - kingDistance) * WEIGHTS.get(PASSED_PAWN_OWN_KING);

        final int opponentKingDistance = calculateChebyshevDistance(opponentKing.getPiecePosition(), pawnPosition);
        passedPawnScore += opponentKingDistance * WEIGHTS.get(PASSED_PAWN_OPPOSING_KING);

        if (isPathToPromotionClear(pawn, alliance, board)) {
          passedPawnScore += WEIGHTS.get(PASSED_PAWN_CLEAR_PATH);
        }

        if (isPawnProtected(pawn, playerPawnOccupancy, alliance)) {
          passedPawnScore += WEIGHTS.get(PASSED_PAWN_PROTECTED);
        }
      }
    }

    passedPawnScore += evaluateConnectedPassedPawns(playerPawns, passedPawns, alliance);

    return passedPawnScore;
  }

  /**
   * Checks if a pawn is a passed pawn by verifying that no opposing pawns
   * block its path to promotion on the same file or adjacent files.
   *
   * @param pawn The pawn to check.
   * @param opponentPawnOccupancy The opponent's pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the pawn being checked.
   * @return True if the pawn is passed, false otherwise.
   */
  private boolean isPassedPawn(final Piece pawn, final long opponentPawnOccupancy,
                               final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int rankDirection = alliance.isWhite() ? -1 : 1;

    for (int rank = pawnRank + rankDirection; alliance.isWhite() ? (rank >= 0) : (rank < 8); rank += rankDirection) {
      if (isPawnAtPosition(opponentPawnOccupancy, rank * 8 + pawnFile)) {
        return false;
      }

      if (pawnFile > 0 && isPawnAtPosition(opponentPawnOccupancy, rank * 8 + (pawnFile - 1))) {
        return false;
      }

      if (pawnFile < 7 && isPawnAtPosition(opponentPawnOccupancy, rank * 8 + (pawnFile + 1))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Returns the tiles of those given pawns that are passed.
   *
   * @param playerPawns The pawns to test, all of one alliance.
   * @param opponentPawnOccupancy The opposing pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the pawns being tested.
   * @return The tiles holding passed pawns, as one bit per tile.
   */
  private long passedPawns(final List<Piece> playerPawns, final long opponentPawnOccupancy,
                           final Alliance alliance) {
    long passedPawns = 0L;

    for (final Piece pawn : playerPawns) {
      if (isPassedPawn(pawn, opponentPawnOccupancy, alliance)) {
        passedPawns |= 1L << pawn.getPiecePosition();
      }
    }

    return passedPawns;
  }

  /**
   * Checks if the given pawn occupancy holds the specified position.
   *
   * @param pawnOccupancy The pawn occupancy to search, as one bit per tile.
   * @param position The position to check.
   * @return True if a pawn stands on the position, false otherwise.
   */
  private boolean isPawnAtPosition(final long pawnOccupancy, final int position) {
    return ((pawnOccupancy >>> position) & 1L) != 0L;
  }

  /**
   * Checks if the path to promotion is clear of all pieces, not just pawns.
   * This is important for evaluating the immediate promotion potential of passed pawns.
   *
   * @param pawn The pawn whose promotion path is being checked.
   * @param alliance The alliance of the pawn.
   * @param board The current chess board state.
   * @return True if the path is clear, false otherwise.
   */
  private boolean isPathToPromotionClear(final Piece pawn, final Alliance alliance, final Board board) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int rankDirection = alliance.isWhite() ? -1 : 1;

    for (int rank = pawnRank + rankDirection; alliance.isWhite() ? (rank >= 0) : (rank < 8); rank += rankDirection) {
      final int squareToCheck = rank * 8 + pawnFile;
      if (board.getPiece(squareToCheck) != null) {
        return false;
      }
    }

    return true;
  }

  /**
   * Checks if a pawn is protected by another pawn of the same alliance.
   * Protected passed pawns are generally more valuable than unprotected ones.
   *
   * @param pawn The pawn to check for protection.
   * @param playerPawnOccupancy The friendly pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtected(final Piece pawn, final long playerPawnOccupancy,
                                  final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int rankBehind = alliance.isWhite() ? pawnRank + 1 : pawnRank - 1;

    if (rankBehind < 0 || rankBehind >= 8) {
      return false;
    }

    if (pawnFile > 0 && isPawnAtPosition(playerPawnOccupancy, rankBehind * 8 + (pawnFile - 1))) {
      return true;
    }

    return pawnFile < 7 && isPawnAtPosition(playerPawnOccupancy, rankBehind * 8 + (pawnFile + 1));
  }

  /**
   * Evaluates connected passed pawns, which are extremely powerful in endgames.
   * Connected passed pawns are adjacent pawns that are both passed and can
   * support each other's advancement.
   *
   * @param playerPawns The player's pawns.
   * @param playerPassedPawns The player's passed pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The connected passed pawns evaluation score.
   */
  private double evaluateConnectedPassedPawns(final List<Piece> playerPawns,
                                              final long playerPassedPawns,
                                              final Alliance alliance) {
    double connectedScore = 0;
    List<Piece> passedPawns = new ArrayList<>();

    for (final Piece pawn : playerPawns) {
      if (isPawnAtPosition(playerPassedPawns, pawn.getPiecePosition())) {
        passedPawns.add(pawn);
      }
    }

    for (int i = 0; i < passedPawns.size(); i++) {
      for (int j = i + 1; j < passedPawns.size(); j++) {
        final int file1 = passedPawns.get(i).getPiecePosition() % 8;
        final int file2 = passedPawns.get(j).getPiecePosition() % 8;

        if (Math.abs(file1 - file2) == 1) {
          connectedScore += WEIGHTS.get(CONNECTED_PASSED_PAWNS);

          final int rank1 = passedPawns.get(i).getPiecePosition() / 8;
          final int rank2 = passedPawns.get(j).getPiecePosition() / 8;
          final int advancedRank = alliance.isWhite() ?
                  Math.min(rank1, rank2) :
                  Math.max(rank1, rank2);

          if ((alliance.isWhite() && advancedRank <= 2) ||
                  (!alliance.isWhite() && advancedRank >= 5)) {
            connectedScore += WEIGHTS.get(CONNECTED_PASSED_PAWNS_ADVANCED);
          }
        }
      }
    }

    return connectedScore;
  }

  /**
   * Evaluates pawn structure factors that are particularly important in endgames,
   * including pawn islands, isolated pawns, doubled pawns, and pawn majorities.
   *
   * @param player The player whose pawn structure is being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureEvaluation(final Player player, final Board board,
                                         final PawnLists pawns) {
    final List<Piece> playerPawns = pawns.of(player);
    final List<Piece> opponentPawns = pawns.of(player.getOpponent());
    final Alliance alliance = player.getAlliance();
    double pawnStructureScore = 0;

    pawnStructureScore += evaluatePawnIslands(playerPawns);
    pawnStructureScore += evaluateDoubledPawns(playerPawns);
    pawnStructureScore += evaluateIsolatedPawns(playerPawns, opponentPawns);
    pawnStructureScore += evaluatePawnMajorities(playerPawns, opponentPawns);
    pawnStructureScore += evaluatePawnChains(playerPawns, pawns.occupancyOf(player), alliance);
    pawnStructureScore += evaluateBackwardPawns(playerPawns, opponentPawns, alliance);

    return pawnStructureScore;
  }

  /**
   * Evaluates pawn islands, which are groups of connected pawns separated by
   * files without pawns. Fewer pawn islands are generally better.
   *
   * @param playerPawns The player's pawns.
   * @return The pawn islands evaluation score.
   */
  private double evaluatePawnIslands(final List<Piece> playerPawns) {
    double islandScore = 0;
    boolean[] filesWithPawns = new boolean[8];
    for (final Piece pawn : playerPawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    int islands = 0;
    boolean inIsland = false;

    for (int file = 0; file < 8; file++) {
      if (filesWithPawns[file]) {
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
   * Evaluates doubled pawns, which are multiple pawns on the same file.
   * Doubled pawns are generally considered a weakness, especially in endgames.
   *
   * @param playerPawns The player's pawns.
   * @return The doubled pawns evaluation score.
   */
  private double evaluateDoubledPawns(final List<Piece> playerPawns) {
    double doubledPawnScore = 0;
    int[] pawnsPerFile = new int[8];
    for (final Piece pawn : playerPawns) {
      pawnsPerFile[pawn.getPiecePosition() % 8]++;
    }

    for (int count : pawnsPerFile) {
      if (count > 1) {
        doubledPawnScore -= (count - 1) * WEIGHTS.get(DOUBLED_PAWN);
      }
    }

    return doubledPawnScore;
  }

  /**
   * Evaluates isolated pawns, which are pawns with no friendly pawns on adjacent files.
   * Isolated pawns are particularly weak in endgames.
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The isolated pawns evaluation score.
   */
  private double evaluateIsolatedPawns(final List<Piece> playerPawns,
                                       final List<Piece> opponentPawns) {
    double isolatedPawnScore = 0;
    boolean[] filesWithPawns = new boolean[8];
    for (final Piece pawn : playerPawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    for (final Piece pawn : playerPawns) {
      final int pawnFile = pawn.getPiecePosition() % 8;
      boolean isIsolated = pawnFile <= 0 || !filesWithPawns[pawnFile - 1];

      if (pawnFile < 7 && filesWithPawns[pawnFile + 1]) {
        isIsolated = false;
      }

      if (isIsolated) {
        isolatedPawnScore -= WEIGHTS.get(ISOLATED_PAWN);

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
          isolatedPawnScore -= WEIGHTS.get(ISOLATED_PAWN_SEMI_OPEN);
        }
      }
    }

    return isolatedPawnScore;
  }

  /**
   * Checks if a pawn is on a semi-open file, meaning no opponent pawn stands on that file.
   *
   * @param pawn The pawn to check.
   * @param opponentPawns The opponent's pawns.
   * @return True if the pawn is on a semi-open file, false otherwise.
   */
  private boolean isOnSemiOpenFile(final Piece pawn, final List<Piece> opponentPawns) {
    final int pawnFile = pawn.getPiecePosition() % 8;

    for (final Piece opponentPawn : opponentPawns) {
      if (opponentPawn.getPiecePosition() % 8 == pawnFile) {
        return false;
      }
    }

    return true;
  }

  /**
   * Evaluates pawn majorities on different sides of the board. Having more pawns
   * on one side of the board can be advantageous for creating passed pawns.
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @return The pawn majorities evaluation score.
   */
  private double evaluatePawnMajorities(final List<Piece> playerPawns, final List<Piece> opponentPawns) {
    double majorityScore = 0;
    int playerKingsidePawns = 0;
    int playerQueensidePawns = 0;
    int opponentKingsidePawns = 0;
    int opponentQueensidePawns = 0;

    for (final Piece pawn : playerPawns) {
      final int file = pawn.getPiecePosition() % 8;
      if (file < 4) {
        playerQueensidePawns++;
      } else {
        playerKingsidePawns++;
      }
    }

    for (final Piece pawn : opponentPawns) {
      final int file = pawn.getPiecePosition() % 8;
      if (file < 4) {
        opponentQueensidePawns++;
      } else {
        opponentKingsidePawns++;
      }
    }

    if (playerKingsidePawns > opponentKingsidePawns) {
      majorityScore += WEIGHTS.get(PAWN_MAJORITY) +
              (playerKingsidePawns - opponentKingsidePawns) * WEIGHTS.get(PAWN_MAJORITY_PAWN);
    }

    if (playerQueensidePawns > opponentQueensidePawns) {
      majorityScore += WEIGHTS.get(PAWN_MAJORITY) +
              (playerQueensidePawns - opponentQueensidePawns) * WEIGHTS.get(PAWN_MAJORITY_PAWN);
    }

    return majorityScore;
  }

  /**
   * Evaluates pawn chains, which are connected pawns that protect each other.
   * Pawn chains provide mutual support and are generally advantageous.
   *
   * @param playerPawns The player's pawns.
   * @param playerPawnOccupancy The player's pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the pawns.
   * @return The pawn chains evaluation score.
   */
  private double evaluatePawnChains(final List<Piece> playerPawns, final long playerPawnOccupancy,
                                    final Alliance alliance) {
    double pawnChainScore = 0;

    int chainLinks = 0;
    for (final Piece pawn : playerPawns) {
      if (isPawnProtected(pawn, playerPawnOccupancy, alliance)) {
        chainLinks++;
      }
    }

    pawnChainScore += chainLinks * WEIGHTS.get(PAWN_CHAIN_LINK);

    return pawnChainScore;
  }

  /**
   * Evaluates backward pawns, which are pawns that cannot be protected by
   * adjacent pawns and are often targets for attack.
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the pawns.
   * @return The backward pawns evaluation score.
   */
  private double evaluateBackwardPawns(final List<Piece> playerPawns,
                                       final List<Piece> opponentPawns,
                                       final Alliance alliance) {
    double backwardPawnScore = 0;

    final int[] rearmostRankOnFile = new int[8];
    final boolean[] fileHasPawn = new boolean[8];

    for (final Piece pawn : playerPawns) {
      final int file = pawn.getPiecePosition() % 8;
      final int rank = pawn.getPiecePosition() / 8;

      if (!fileHasPawn[file]) {
        fileHasPawn[file] = true;
        rearmostRankOnFile[file] = rank;
      } else if (alliance.isWhite()) {
        rearmostRankOnFile[file] = Math.max(rearmostRankOnFile[file], rank);
      } else {
        rearmostRankOnFile[file] = Math.min(rearmostRankOnFile[file], rank);
      }
    }

    for (final Piece pawn : playerPawns) {
      if (isBackward(alliance, pawn, rearmostRankOnFile, fileHasPawn)) {
        backwardPawnScore -= WEIGHTS.get(BACKWARD_PAWN);

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
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
   * @param pawn The pawn to check.
   * @param rearmostRankOnFile The rank of the least advanced friendly pawn on each file.
   * @param fileHasPawn Whether each file holds at least one friendly pawn.
   * @return True if the pawn is backward, false otherwise.
   */
  private static boolean isBackward(final Alliance alliance,
                                    final Piece pawn,
                                    final int[] rearmostRankOnFile,
                                    final boolean[] fileHasPawn) {
    final int file = pawn.getPiecePosition() % 8;
    final int rank = pawn.getPiecePosition() / 8;

    boolean hasAdjacentPawn = false;

    for (int adjacentFile = file - 1; adjacentFile <= file + 1; adjacentFile += 2) {
      if (adjacentFile < 0 || adjacentFile > 7 || !fileHasPawn[adjacentFile]) {
        continue;
      }

      hasAdjacentPawn = true;
      final int adjacentRank = rearmostRankOnFile[adjacentFile];

      if (alliance.isWhite() ? adjacentRank >= rank : adjacentRank <= rank) {
        return false;
      }
    }

    return hasAdjacentPawn;
  }

  /**
   * Evaluates piece coordination in endgames, focusing on cooperation between
   * pieces and their support for pawns and the king.
   *
   * @param player The player whose piece coordination is being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @param material The piece counts of both players.
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationEvaluation(final Player player, final Board board,
                                             final PawnLists pawns, final Material material) {
    double coordinationScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();

    coordinationScore += evaluateMinorPieceCoordination(material.of(player), pawns);
    coordinationScore += evaluatePiecePlacement(playerPieces, pawns.of(player));
    coordinationScore += evaluatePiecesSupportingPassedPawns(player, board, pawns);

    return coordinationScore;
  }

  /**
   * Evaluates minor piece coordination in endgames by penalising knights when few pawns remain.
   * The bishop pair is scored by {@link #evaluateColorComplexControl}.
   *
   * @param playerPieceCounts The piece counts for the player.
   * @param pawns The pawns of both players.
   * @return The minor piece coordination evaluation score.
   */
  private double evaluateMinorPieceCoordination(final PieceCounts playerPieceCounts,
                                                final PawnLists pawns) {
    double minorPieceScore = 0;

    if (pawns.white().size() + pawns.black().size() <= 4) {
      minorPieceScore -= playerPieceCounts.of(Piece.PieceType.KNIGHT) *
              WEIGHTS.get(KNIGHT_FEW_PAWNS);
    }

    return minorPieceScore;
  }

  /**
   * Evaluates piece placement relative to pawns, particularly the positioning
   * of bishops and knights in relation to the pawn structure.
   *
   * @param playerPieces The player's pieces.
   * @param playerPawns The player's pawns.
   * @return The piece placement evaluation score.
   */
  private double evaluatePiecePlacement(final Collection<Piece> playerPieces,
                                        final List<Piece> playerPawns) {
    double placementScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        placementScore += evaluateBishopPlacement((Bishop) piece, playerPawns);
      } else if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        placementScore += evaluateKnightPlacement((Knight) piece, playerPawns);
      }
    }

    return placementScore;
  }

  /**
   * Evaluates bishop placement relative to pawns. Bishops positioned behind
   * pawns on long diagonals are generally well-placed.
   *
   * @param bishop The bishop to evaluate.
   * @param playerPawns The player's pawns.
   * @return The bishop placement evaluation score.
   */
  private double evaluateBishopPlacement(final Bishop bishop, final List<Piece> playerPawns) {
    double bishopScore = 0;
    final int bishopPosition = bishop.getPiecePosition();
    final int bishopFile = bishopPosition % 8;
    final int bishopRank = bishopPosition / 8;
    final Alliance alliance = bishop.getPieceAllegiance();

    int pawnsInFront = 0;
    for (final Piece pawn : playerPawns) {
      final int pawnFile = pawn.getPiecePosition() % 8;
      final int pawnRank = pawn.getPiecePosition() / 8;

      if (Math.abs(pawnFile - bishopFile) <= 1) {
        if ((alliance.isWhite() && pawnRank < bishopRank) ||
                (!alliance.isWhite() && pawnRank > bishopRank)) {
          pawnsInFront++;
        }
      }
    }

    if (pawnsInFront > 0) {
      bishopScore += WEIGHTS.get(BISHOP_BEHIND_PAWNS);
    }

    if ((bishopPosition % 9 == 0) || (bishopPosition % 7 == 0 && bishopPosition % 8 != 0 && bishopPosition % 8 != 7)) {
      bishopScore += WEIGHTS.get(BISHOP_LONG_DIAGONAL);
    }

    return bishopScore;
  }

  /**
   * Evaluates knight placement relative to pawns. Knights positioned near
   * pawns are generally more effective in endgames.
   *
   * @param knight The knight to evaluate.
   * @param playerPawns The player's pawns.
   * @return The knight placement evaluation score.
   */
  private double evaluateKnightPlacement(final Knight knight, final List<Piece> playerPawns) {
    double knightScore = 0;
    final int knightPosition = knight.getPiecePosition();

    for (final Piece pawn : playerPawns) {
      final int distance = calculateChebyshevDistance(knightPosition, pawn.getPiecePosition());

      if (distance <= 2) {
        knightScore += (3 - distance) * WEIGHTS.get(KNIGHT_NEAR_PAWN);
      }
    }

    return knightScore;
  }

  /**
   * Evaluates how well pieces support passed pawns by providing protection
   * or controlling key squares in the pawn's path to promotion.
   *
   * @param player The player whose piece support is being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @return The piece support evaluation score.
   */
  private double evaluatePiecesSupportingPassedPawns(final Player player, final Board board,
                                                     final PawnLists pawns) {
    double supportScore = 0;
    final List<Piece> playerPawns = pawns.of(player);
    final long passedPawns = pawns.passedOf(player);
    final Alliance alliance = player.getAlliance();
    final Collection<Piece> playerPieces = player.getActivePieces();

    for (final Piece pawn : playerPawns) {
      if (isPawnAtPosition(passedPawns, pawn.getPiecePosition())) {
        final int pawnPosition = pawn.getPiecePosition();
        final int promotionSquare = alliance.isWhite() ?
                (pawnPosition % 8) :
                ((7 * 8) + (pawnPosition % 8));

        for (final Piece piece : playerPieces) {
          if (piece.getPieceType() != Piece.PieceType.PAWN &&
                  piece.getPieceType() != Piece.PieceType.KING) {

            final int piecePosition = piece.getPiecePosition();
            final int distance = calculateChebyshevDistance(piecePosition, pawnPosition);

            if (distance <= 1) {
              supportScore += WEIGHTS.get(PASSED_PAWN_ESCORT);
            } else if (distance == 2) {
              supportScore += WEIGHTS.get(PASSED_PAWN_NEAR_ESCORT);
            }

            if (((piece.legalDestinations(board) >>> promotionSquare) & 1L) != 0L) {
              supportScore += WEIGHTS.get(PROMOTION_SQUARE_CONTROL);
            }
          }
        }
      }
    }

    return supportScore;
  }

  /**
   * Evaluates rook-specific endgame patterns, including rook activity on open files,
   * rook placement behind passed pawns, and rook coordination.
   *
   * @param player The player whose rook endgame factors are being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @return The rook endgame evaluation score.
   */
  private double rookEndgameEvaluation(final Player player, final Board board,
                                       final PawnLists pawns) {
    double rookScore = 0;
    final List<Piece> playerRooks = player.getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.ROOK)
            .collect(Collectors.toList());

    if (playerRooks.isEmpty()) {
      return 0;
    }

    final List<Piece> playerPawns = pawns.of(player);
    final List<Piece> opponentPawns = pawns.of(player.getOpponent());
    final long playerPassedPawns = pawns.passedOf(player);
    final long opponentPassedPawns = pawns.passedOf(player.getOpponent());
    final Alliance alliance = player.getAlliance();

    for (final Piece rook : playerRooks) {
      rookScore += evaluateRookOnOpenFile(rook, board);
      rookScore += evaluateRookBehindPassedPawn(rook, playerPawns, opponentPawns,
              playerPassedPawns, opponentPassedPawns, alliance);
      rookScore += evaluateRookOn7thRank(rook, opponentPawns, alliance);
    }

    if (playerRooks.size() >= 2) {
      rookScore += evaluateConnectedRooks(playerRooks);
    }

    return rookScore;
  }

  /**
   * Evaluates rooks on open or semi-open files, which are generally advantageous
   * positions for rooks in endgames.
   *
   * @param rook The rook to evaluate.
   * @param board The current chess board state.
   * @return The rook file evaluation score.
   */
  private double evaluateRookOnOpenFile(final Piece rook, final Board board) {
    double fileScore = 0;
    final int rookFile = rook.getPiecePosition() % 8;
    boolean openFile = true;
    boolean semiOpenFile = true;

    for (int rank = 0; rank < 8; rank++) {
      final Piece piece = board.getPiece(rank * 8 + rookFile);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN) {
        openFile = false;

        if (piece.getPieceAllegiance() == rook.getPieceAllegiance()) {
          semiOpenFile = false;
        }
      }
    }

    if (openFile) {
      fileScore += WEIGHTS.get(ROOK_OPEN_FILE);
    } else if (semiOpenFile) {
      fileScore += WEIGHTS.get(ROOK_SEMI_OPEN_FILE);
    }

    return fileScore;
  }

  /**
   * Evaluates rooks behind passed pawns, following the Tarrasch rule that
   * rooks belong behind passed pawns to support their advancement.
   *
   * @param rook The rook to evaluate.
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @param playerPassedPawns The player's passed pawn occupancy, as one bit per tile.
   * @param opponentPassedPawns The opponent's passed pawn occupancy, as one bit per tile.
   * @param alliance The alliance of the rook.
   * @return The rook-pawn cooperation evaluation score.
   */
  private double evaluateRookBehindPassedPawn(final Piece rook,
                                              final List<Piece> playerPawns,
                                              final List<Piece> opponentPawns,
                                              final long playerPassedPawns,
                                              final long opponentPassedPawns,
                                              final Alliance alliance) {
    double behindPawnScore = 0;
    final int rookPosition = rook.getPiecePosition();
    final int rookFile = rookPosition % 8;
    final int rookRank = rookPosition / 8;

    for (final Piece pawn : playerPawns) {
      if (pawn.getPiecePosition() % 8 == rookFile &&
              isPawnAtPosition(playerPassedPawns, pawn.getPiecePosition())) {
        final int pawnRank = pawn.getPiecePosition() / 8;

        if ((alliance.isWhite() && rookRank > pawnRank) ||
                (!alliance.isWhite() && rookRank < pawnRank)) {
          behindPawnScore += WEIGHTS.get(ROOK_BEHIND_OWN_PASSER);
        }
      }
    }

    for (final Piece pawn : opponentPawns) {
      if (pawn.getPiecePosition() % 8 == rookFile &&
              isPawnAtPosition(opponentPassedPawns, pawn.getPiecePosition())) {
        final int pawnRank = pawn.getPiecePosition() / 8;

        if ((alliance.isWhite() && rookRank > pawnRank) ||
                (!alliance.isWhite() && rookRank < pawnRank)) {
          behindPawnScore += WEIGHTS.get(ROOK_BEHIND_OPPOSING_PASSER);
        }
      }
    }

    return behindPawnScore;
  }

  /**
   * Evaluates rooks on the 7th rank (or 2nd rank for black), which is often
   * a very strong position in endgames for attacking pawns and restricting the king.
   *
   * @param rook The rook to evaluate.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the rook.
   * @return The 7th rank rook evaluation score.
   */
  private double evaluateRookOn7thRank(final Piece rook,
                                       final List<Piece> opponentPawns,
                                       final Alliance alliance) {
    double seventhRankScore = 0;
    final int rookRank = rook.getPiecePosition() / 8;

    if ((alliance.isWhite() && rookRank == 1) || (!alliance.isWhite() && rookRank == 6)) {
      seventhRankScore += WEIGHTS.get(ROOK_ON_SEVENTH);

      for (final Piece pawn : opponentPawns) {
        final int pawnRank = pawn.getPiecePosition() / 8;
        if ((alliance.isWhite() && pawnRank == 1) || (!alliance.isWhite() && pawnRank == 6)) {
          seventhRankScore += WEIGHTS.get(ROOK_ON_SEVENTH_PAWN);
        }
      }
    }

    return seventhRankScore;
  }

  /**
   * Evaluates connected rooks that protect each other on the same rank or file.
   * Connected rooks can provide mutual support and control important squares.
   *
   * @param playerRooks The player's rooks.
   * @return The connected rooks evaluation score.
   */
  private double evaluateConnectedRooks(final List<Piece> playerRooks) {
    double connectedScore = 0;

    for (int i = 0; i < playerRooks.size() - 1; i++) {
      for (int j = i + 1; j < playerRooks.size(); j++) {
        final int rookPos1 = playerRooks.get(i).getPiecePosition();
        final int rookPos2 = playerRooks.get(j).getPiecePosition();

        if (rookPos1 / 8 == rookPos2 / 8) {
          connectedScore += WEIGHTS.get(ROOKS_SHARING_RANK);
        }

        if (rookPos1 % 8 == rookPos2 % 8) {
          connectedScore += WEIGHTS.get(ROOKS_SHARING_FILE);
        }
      }
    }

    return connectedScore;
  }

  /**
   * Evaluates bishop endgame patterns, including color complex control,
   * bishop mobility, and bishop versus knight dynamics.
   *
   * @param player The player whose bishop endgame factors are being evaluated.
   * @param board The current chess board state.
   * @param pawns The pawns of both players.
   * @param material The piece counts of both players.
   * @return The bishop endgame evaluation score.
   */
  private double bishopEndgameEvaluation(final Player player, final Board board,
                                         final PawnLists pawns, final Material material) {
    double bishopScore = 0;
    final List<Piece> playerBishops = player.getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.BISHOP)
            .collect(Collectors.toList());

    if (playerBishops.isEmpty()) {
      return 0;
    }

    bishopScore += evaluateColorComplexControl(playerBishops, pawns);

    for (final Piece bishop : playerBishops) {
      bishopScore += Long.bitCount(bishop.legalDestinations(board)) * WEIGHTS.get(BISHOP_MOBILITY);
    }

    final int playerKnightCount = material.of(player).of(Piece.PieceType.KNIGHT);
    final int opponentKnightCount = material.of(player.getOpponent()).of(Piece.PieceType.KNIGHT);

    if (!playerBishops.isEmpty() && opponentKnightCount > 0 && playerKnightCount == 0) {
      bishopScore += evaluateBishopVsKnight(pawns);
    }

    return bishopScore;
  }

  /**
   * Evaluates color complex control, which is important in bishop endgames.
   * Bishops on both colours earn the bishop pair bonus. Otherwise, bishops that control squares
   * of the opposite color from most pawns are generally more effective.
   *
   * @param playerBishops The player's bishops.
   * @param pawns The pawns of both players.
   * @return The color complex control evaluation score.
   */
  private double evaluateColorComplexControl(final List<Piece> playerBishops,
                                             final PawnLists pawns) {
    double colorScore = 0;
    boolean hasLightSquareBishop = false;
    boolean hasDarkSquareBishop = false;

    for (final Piece bishop : playerBishops) {
      final int position = bishop.getPiecePosition();
      final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

      if (isLightSquare) {
        hasLightSquareBishop = true;
      } else {
        hasDarkSquareBishop = true;
      }
    }

    if (hasLightSquareBishop && hasDarkSquareBishop) {
      colorScore += WEIGHTS.get(BISHOP_PAIR);
      return colorScore;
    }

    int lightSquarePawns = 0;
    int darkSquarePawns = 0;

    for (final List<Piece> sidePawns : List.of(pawns.white(), pawns.black())) {
      for (final Piece pawn : sidePawns) {
        final int position = pawn.getPiecePosition();
        final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

        if (isLightSquare) {
          lightSquarePawns++;
        } else {
          darkSquarePawns++;
        }
      }
    }

    if (hasLightSquareBishop && darkSquarePawns > lightSquarePawns) {
      colorScore += WEIGHTS.get(GOOD_BISHOP_COLOUR);
    } else if (!hasLightSquareBishop && hasDarkSquareBishop && lightSquarePawns > darkSquarePawns) {
      colorScore += WEIGHTS.get(GOOD_BISHOP_COLOUR);
    }

    if (hasLightSquareBishop && lightSquarePawns > darkSquarePawns) {
      colorScore -= WEIGHTS.get(BAD_BISHOP_COLOUR);
    } else if (!hasLightSquareBishop && hasDarkSquareBishop && darkSquarePawns > lightSquarePawns) {
      colorScore -= WEIGHTS.get(BAD_BISHOP_COLOUR);
    }

    return colorScore;
  }

  /**
   * Evaluates bishop versus knight dynamics in specific positions.
   * Bishops are generally better in open positions while knights prefer closed positions.
   *
   * @param pawns The pawns of both players.
   * @return The bishop versus knight evaluation score.
   */
  private double evaluateBishopVsKnight(final PawnLists pawns) {
    double dynamicScore = 0;
    final int pawnCount = pawns.white().size() + pawns.black().size();

    if (pawnCount <= 5) {
      dynamicScore += WEIGHTS.get(BISHOP_AGAINST_KNIGHT_OPEN);
    } else if (pawnCount >= 8) {
      dynamicScore -= WEIGHTS.get(BISHOP_AGAINST_KNIGHT_CLOSED);
    }

    return dynamicScore;
  }

  /**
   * Evaluates rook and pawn against rook, rewarding the pawn's side when its pawn stands on its
   * seventh rank or beyond and penalising it otherwise. Material that cannot win is handled by
   * {@link #isDrawnByMaterial} and opposite-coloured bishops by {@link #drawishScale}.
   *
   * @param player The player whose draw patterns are being evaluated.
   * @param pawns The pawns of both players.
   * @param material The piece counts of both players.
   * @return The draw pattern evaluation score.
   */
  private double drawPatternEvaluation(final Player player, final PawnLists pawns,
                                       final Material material) {
    double drawScore = 0;
    final PieceCounts playerPieceCounts = material.of(player);
    final PieceCounts opponentPieceCounts = material.of(player.getOpponent());

    if (playerPieceCounts.of(Piece.PieceType.ROOK) == 1 &&
            playerPieceCounts.of(Piece.PieceType.PAWN) == 1 &&
            opponentPieceCounts.of(Piece.PieceType.ROOK) == 1 &&
            opponentPieceCounts.of(Piece.PieceType.PAWN) == 0) {
      List<Piece> playerPawns = pawns.of(player);
      if (!playerPawns.isEmpty()) {
        Piece pawn = playerPawns.get(0);
        int pawnRank = pawn.getPiecePosition() / 8;

        if ((player.getAlliance().isWhite() && pawnRank <= 1) ||
                (!player.getAlliance().isWhite() && pawnRank >= 6)) {
          drawScore += WEIGHTS.get(ROOK_PAWN_ADVANCED);
        } else {
          drawScore -= WEIGHTS.get(ROOK_PAWN_BEHIND);
        }
      }
    }

    return drawScore;
  }

  /**
   * Evaluates mobility for the given player.
   * Counts the player's legal moves, halving the weight in a pawn endgame and raising it when
   * the two sides hold opposite colored bishops. The result is a per-player quantity and the
   * caller forms the difference between the two players.
   *
   * @param player The player whose mobility is being evaluated.
   * @param material The piece counts of both players.
   * @param playerTargets The destinations of the player's legal moves.
   * @return The mobility evaluation score.
   */
  private double mobilityEvaluation(final Player player, final Material material,
                                    final MoveTargets playerTargets) {
    double mobilityScore = 0;

    double mobilityWeight = 1.0;

    if (isPawnEndgame(material.nonPawnPieceCount())) {
      mobilityWeight = WEIGHTS.get(PAWN_ENDGAME_MOBILITY_FACTOR);
    }

    if (material.oppositeColoredBishops()) {
      mobilityWeight = WEIGHTS.get(OPPOSITE_BISHOPS_MOBILITY_FACTOR);
    }

    mobilityScore += playerTargets.moveCount() * WEIGHTS.get(MOBILITY) * mobilityWeight;

    return mobilityScore;
  }

  /**
   * Evaluates piece safety by charging the player for the single most valuable piece the opponent
   * threatens. A piece is threatened when the opponent attacks its square more times than the
   * player defends it, or when it is worth more than a pawn and stands on a square an opposing
   * pawn attacks. Only the largest such threat is charged, at {@link #THREAT_FRACTION} of the
   * threatened piece's value, so the score this term can produce is bounded by a quarter of a
   * queen. The king is not scored.
   *
   * @param player The player whose piece safety is being evaluated.
   * @param board The current chess board state.
   * @param opponentTargets The destinations of the opponent's legal moves.
   * @return The piece safety evaluation score.
   */
  private double pieceSafetyEvaluation(final Player player, final Board board,
                                       final MoveTargets opponentTargets) {
    double largestThreat = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final int[] attackCount = opponentTargets.destinationCount();
    final long pawnAttacks = opponentTargets.pawnDestinations();

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int pieceValue = piece.getPieceValue();

      final boolean harriedByPawn = ((pawnAttacks >>> position) & 1L) != 0L
              && pieceValue > Piece.PieceType.PAWN.getPieceValue();
      final boolean outnumbered = attackCount[position] > 0
              && attackCount[position] > countDefenders(playerPieces, position, board);

      if (outnumbered || harriedByPawn) {
        largestThreat = Math.max(largestThreat, pieceValue * WEIGHTS.get(THREAT_FRACTION));
      }
    }

    return -largestThreat;
  }

  /**
   * Counts the pieces in the given collection that defend the given square. The piece standing on
   * the square is not counted, and a piece whose line to the square is blocked by another piece
   * does not count.
   *
   * @param playerPieces The pieces to test.
   * @param square The square to test.
   * @param board The current chess board state.
   * @return The number of pieces in the collection that defend the square.
   */
  private static int countDefenders(final Collection<Piece> playerPieces,
                                    final int square,
                                    final Board board) {
    int defenders = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPiecePosition() != square && piece.defendsSquare(square, board)) {
        defenders++;
      }
    }

    return defenders;
  }

  /**
   * Checks if the position is a pawn endgame with only kings and pawns remaining.
   *
   * @param nonPawnPieceCount The number of pieces of both players that are neither pawns nor kings.
   * @return True if the position is a pawn endgame, false otherwise.
   */
  private boolean isPawnEndgame(final int nonPawnPieceCount) {
    return nonPawnPieceCount == 0;
  }

  /**
   * Calculates the Chebyshev distance between two squares on the chess board.
   * This is the maximum of the horizontal and vertical distances.
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
   * Gets a list of pawn pieces for the specified player.
   *
   * @param player The player whose pawns are requested.
   * @return A list of the player's pawn pieces.
   */
  private static List<Piece> getPlayerPawns(final Player player) {
    return player.getActivePieces().stream()
            .filter(piece -> piece.getPieceType() == Piece.PieceType.PAWN)
            .collect(Collectors.toList());
  }
}