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

  /** The fraction of a threatened piece's value charged against the side that owns it. */
  private static final double THREAT_FRACTION = 0.25;

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

    return (score(board.whitePlayer(), board, whiteStatistics, blackStatistics,
                    whitePawns, blackPawns, whiteLayout, blackLayout) -
            score(board.blackPlayer(), board, blackStatistics, whiteStatistics,
                    blackPawns, whitePawns, blackLayout, whiteLayout));
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
   * @param material The summed value of all the player's pieces, the king included.
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
  private record PieceLayout(int material,
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

    int material = 0;
    long knights = 0L;
    long bishops = 0L;
    long rooks = 0L;
    long queens = 0L;
    long nonKingPieces = 0L;
    int heavyPieceFiles = 0;
    double kingTropism = 0;

    for (final Piece piece : player.getActivePieces()) {
      material += piece.getPieceValue();

      if (piece.getPieceType() == Piece.PieceType.KING) {
        continue;
      }

      final int position = piece.getPiecePosition();
      final long tile = 1L << position;
      nonKingPieces |= tile;

      int distance = calculateChebyshevDistance(opposingKingPosition, position);
      double pieceValue = piece.getPieceValue() / 100.0;

      switch (piece.getPieceType()) {
        case QUEEN:
          queens |= tile;
          heavyPieceFiles |= 1 << (position % 8);
          kingTropism += (7 - distance) * 6 * pieceValue;
          break;
        case ROOK:
          rooks |= tile;
          heavyPieceFiles |= 1 << (position % 8);
          kingTropism += (7 - distance) * 4 * pieceValue;
          break;
        case BISHOP:
          bishops |= tile;
          kingTropism += (7 - distance) * 3 * pieceValue;
          break;
        case KNIGHT:
          knights |= tile;
          if (distance <= 3) {
            kingTropism += (4 - distance) * 4 * pieceValue;
          }
          break;
        case PAWN:
          if (distance <= 2) {
            kingTropism += (3 - distance) * 2 * pieceValue;
          }
          break;
      }
    }

    return new PieceLayout(material, knights, bishops, rooks, queens, nonKingPieces,
            heavyPieceFiles, kingTropism);
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
   * @param playerLayout The layout of the player's pieces.
   * @param opponentLayout The layout of the opponent's pieces.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board,
                       final MoveStatistics playerStatistics,
                       final MoveStatistics opponentStatistics,
                       final PawnStructure playerPawns,
                       final PawnStructure opponentPawns,
                       final PieceLayout playerLayout,
                       final PieceLayout opponentLayout) {
    return materialEvaluation(playerLayout) +
            mobilityEvaluation(playerStatistics) +
            kingSafetyEvaluation(player, playerPawns, opponentStatistics, opponentLayout) +
            pawnStructureEvaluation(player, board, playerPawns, opponentPawns) +
            pieceCoordinationEvaluation(player, board, opponentStatistics,
                    playerPawns, opponentPawns, playerLayout) +
            spaceControlEvaluation(playerStatistics, opponentStatistics) +
            attackingPotentialEvaluation(player, playerStatistics, opponentLayout) +
            specialPatternsEvaluation(player, board, playerPawns, opponentPawns, playerLayout);
  }

  /**
   * Evaluates material balance with refined piece values and contextual adjustments.
   * Modern engines use dynamic piece values based on the position.
   *
   * @param playerLayout The layout of the player's pieces.
   * @return The material evaluation score.
   */
  private double materialEvaluation(final PieceLayout playerLayout) {
    double materialScore = playerLayout.material();

    if (Long.bitCount(playerLayout.bishops()) >= 2) {
      materialScore += 45;
    }

    return materialScore;
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

    mobilityScore += moveCountByPieceType[Piece.PieceType.KNIGHT.ordinal()] * 4.5;
    mobilityScore += moveCountByPieceType[Piece.PieceType.BISHOP.ordinal()] * 5.0;
    mobilityScore += moveCountByPieceType[Piece.PieceType.ROOK.ordinal()] * 4.0;
    mobilityScore += moveCountByPieceType[Piece.PieceType.QUEEN.ordinal()] * 2.0;

    mobilityScore += playerStatistics.moveCount() * 5.0;

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
      kingSafetyScore += 40;
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
      shieldScore += 35;
    } else if (pawnsInShield == 2) {
      shieldScore += 20;
    } else if (pawnsInShield == 1) {
      shieldScore += 5;
    } else {
      shieldScore -= 20;
    }

    shieldScore += intactFileCount * 10;

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

    exposureScore += nearKingCount[Piece.PieceType.QUEEN.ordinal()] * 10;
    exposureScore += nearKingCount[Piece.PieceType.ROOK.ordinal()] * 7;
    exposureScore += nearKingCount[Piece.PieceType.BISHOP.ordinal()] * 5;
    exposureScore += nearKingCount[Piece.PieceType.KNIGHT.ordinal()] * 5;
    exposureScore += nearKingCount[Piece.PieceType.PAWN.ordinal()] * 2;
    exposureScore += nearKingCount[Piece.PieceType.KING.ordinal()] * 2;

    if (attacksNearKing > 2) {
      exposureScore += (attacksNearKing - 2) * 15;
    }

    if (player.isInCheck()) {
      exposureScore += 30;
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
      openFileScore += 25;

      if ((heavyPieceFiles & (1 << kingFile)) != 0) {
        openFileScore += 35;
      }
    }

    for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
      if (file == kingFile) continue;

      if (!isPawnOnFile(file, playerPawns)) {
        openFileScore += 15;

        if ((heavyPieceFiles & (1 << file)) != 0) {
          openFileScore += 25;
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
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureEvaluation(final Player player, final Board board,
                                         final PawnStructure playerPawns,
                                         final PawnStructure opponentPawns) {
    final Player opponent = player.getOpponent();
    double pawnStructureScore = 0;

    pawnStructureScore += evaluatePassedPawns(playerPawns, opponentPawns,
            opponent.getActivePieces(), player.getAlliance(), board);
    pawnStructureScore += evaluatePawnIslands(playerPawns);
    pawnStructureScore += evaluateDoubledPawns(playerPawns);
    pawnStructureScore += evaluateIsolatedPawns(playerPawns, opponentPawns);
    pawnStructureScore += evaluateBackwardPawns(playerPawns, opponentPawns, player.getAlliance());
    pawnStructureScore += evaluatePawnChains(playerPawns, player.getAlliance());
    pawnStructureScore += evaluateCentralPawnControl(playerPawns);

    return pawnStructureScore;
  }

  /**
   * Evaluates passed pawns, which are more valuable in the middlegame.
   *
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @param opponentPieces The opponent's pieces.
   * @param alliance The alliance of the player.
   * @param board The current state of the chess board.
   * @return The passed pawns evaluation score.
   */
  private double evaluatePassedPawns(final PawnStructure playerPawns,
                                     final PawnStructure opponentPawns,
                                     final Collection<Piece> opponentPieces,
                                     final Alliance alliance,
                                     final Board board) {
    double passedPawnScore = 0;

    for (final int pawnPosition : playerPawns.positions()) {
      if (isPassedPawn(pawnPosition, opponentPawns, alliance)) {
        final int rank = pawnPosition / 8;
        final int advancementBonus = alliance.isWhite() ? 7 - rank : rank;

        passedPawnScore += 20 + (advancementBonus * 10);

        if (isPawnProtected(pawnPosition, playerPawns, alliance)) {
          passedPawnScore += 15;
        }

        if (!opponentControlsStopSquare(pawnPosition, alliance, opponentPieces, board)) {
          passedPawnScore += 10;
        }
      }
    }

    return passedPawnScore;
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
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;

    final int rankDirection = alliance.isWhite() ? -1 : 1;

    for (int rank = pawnRank + rankDirection; alliance.isWhite() ? (rank >= 0) : (rank < 8); rank += rankDirection) {
      if (opponentPawns.hasPawnAt(rank * 8 + pawnFile)) {
        return false;
      }

      if (pawnFile > 0 && opponentPawns.hasPawnAt(rank * 8 + (pawnFile - 1))) {
        return false;
      }

      if (pawnFile < 7 && opponentPawns.hasPawnAt(rank * 8 + (pawnFile + 1))) {
        return false;
      }
    }

    return true;
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
      islandScore -= (islands - 1) * 10;
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
        doubledPawnScore -= (count - 1) * 20;
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
        isolatedPawnScore -= 15;

        if (isOnSemiOpenFile(pawnFile, opponentPawns)) {
          isolatedPawnScore -= 5;
        }

        if (pawnFile > 1 && pawnFile < 6) {
          isolatedPawnScore -= 5;
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
        backwardPawnScore -= 20;

        if (isOnSemiOpenFile(pawnPosition % 8, opponentPawns)) {
          backwardPawnScore -= 5;
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

    pawnChainScore += chainLinks * 8;

    if (chainLinks >= 3) {
      pawnChainScore += 10;
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
        centralControlScore += 25;
      }

      centralControlScore += attackCountBySquare[centralSquare] * 15;
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
      bishopPairScore += 50;
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

      rookScore += sameRankPairs * 20;
      rookScore += sameFilePairs * 15;

      if (sameRankPairs + sameFilePairs > 0) {
        rookScore += 10;
      }
    }

    for (long remaining = rooks; remaining != 0L; remaining &= remaining - 1) {
      final int rookFile = Long.numberOfTrailingZeros(remaining) % 8;

      if (!isPawnOnFile(rookFile, playerPawns)) {
        rookScore += isPawnOnFile(rookFile, opponentPawns) ? 10 : 20;
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
          outpostScore += 20;

          if (playerPawns.attackCountBySquare()[position] > 0) {
            outpostScore += 15;
          }

          if (file >= 2 && file <= 5) {
            outpostScore += 10;
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
   * is bounded by a third of a queen.
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
        largestThreat = Math.max(largestThreat, pieceValue * THREAT_FRACTION);
      } else if (attackCount == 0 && protectionCount > 0) {
        protectionScore += 8;

        if (piece.getPieceType() == Piece.PieceType.QUEEN) {
          protectionScore += 6 * protectionCount;
        } else if (piece.getPieceType() == Piece.PieceType.ROOK) {
          protectionScore += 4 * protectionCount;
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
        activityScore += (6 - distanceFromCenter) * 5;
      } else if ((bishops & tile) != 0L) {
        activityScore += (6 - distanceFromCenter) * 4;
      } else if ((rooks & tile) != 0L) {
        activityScore += (3 - fileDistance) * 3;
      } else {
        activityScore += (6 - distanceFromCenter) * 2;
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

    spaceScore += playerStatistics.spaceCount() * 1.0;
    final int moveAdvantage = playerStatistics.moveCount() - opponentStatistics.moveCount();
    spaceScore += Math.max(-10, Math.min(moveAdvantage, 10)) * 3;

    final int[] keySquares = {27, 28, 35, 36};
    for (final int square : keySquares) {
      spaceScore += controlledSquares[square] * 5;
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
      attackScore += 20 * attackingPiecesCount;
      attackScore += 10 * attackingSquaresCount;

      if (queenAttacking) attackScore += 40;
      if (rookAttacking) attackScore += 30;
      if (bishopAttacking) attackScore += 20;
      if (knightAttacking) attackScore += 15;
      if (pawnAttacking) attackScore += 10;

      final boolean opponentKingOnCastledSquare = opponentKing.isOnCastledSquare();
      final boolean opponentChecked = player.getOpponent().isInCheck();

      if (opponentChecked) {
        attackScore += 50;
      }

      if (!opponentKingOnCastledSquare) {
        attackScore += 30;
      }

      final int defendingPiecesCount =
              Long.bitCount(KING_ZONES[kingPosition] & opponentLayout.nonKingPieces());
      attackScore -= defendingPiecesCount * 15;
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
      rookScore += 30;
      rookScore += 10 * Integer.bitCount(opponentPawns.filesByRank()[seventhRank]);

      final King opponentKing = opposingKing(board, alliance);
      final int kingRank = opponentKing.getPiecePosition() / 8;

      if ((alliance.isWhite() && kingRank == 0) || (!alliance.isWhite() && kingRank == 7)) {
        rookScore += 20;
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
      fianchettoScore += 20;
    }

    if (((bishops >>> queensideBishopPosition) & 1L) != 0L) {
      fianchettoScore += 15;
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
        badBishopScore -= 20;
      } else if (pawnsOnSameColor == 2) {
        badBishopScore -= 10;
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
        queenScore += 10;
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
          queenScore -= 25;
        }
      }

      final King opponentKing = opposingKing(board, alliance);
      final int kingPosition = opponentKing.getPiecePosition();

      final int distance = calculateChebyshevDistance(position, kingPosition);
      if (distance <= 2) {
        queenScore += 20;
      } else if (distance == 3) {
        queenScore += 10;
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