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
    final MoveStatistics whiteStatistics = moveStatistics(board.whitePlayer());
    final MoveStatistics blackStatistics = moveStatistics(board.blackPlayer());
    final PawnStructure whitePawns = pawnStructure(board.whitePlayer());
    final PawnStructure blackPawns = pawnStructure(board.blackPlayer());

    return (score(board.whitePlayer(), board, whiteStatistics, blackStatistics,
                    whitePawns, blackPawns) -
            score(board.blackPlayer(), board, blackStatistics, whiteStatistics,
                    blackPawns, whitePawns));
  }

  /**
   * The quantities drawn from one player's legal move list that the scoring terms read. The
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
   * Walks the given player's legal move list once and returns the quantities the scoring terms
   * read from it.
   *
   * @param player The player whose legal move list is being read.
   * @return The statistics of that player's legal move list.
   */
  private MoveStatistics moveStatistics(final Player player) {
    final Collection<Move> playerMoves = player.getLegalMoves();
    final Alliance alliance = player.getAlliance();
    final int opposingKingPosition = player.getOpponent().getPlayerKing().getPiecePosition();

    final int[] moveCountByPieceType = new int[Piece.PieceType.values().length];
    final int[] destinationCount = new int[BoardUtils.NUM_TILES];
    final int[] pawnDestinationCount = new int[BoardUtils.NUM_TILES];
    final int[] nearKingCountByPieceType = new int[Piece.PieceType.values().length];
    int spaceCount = 0;
    int nearKingSquareCount = 0;

    for (final Move move : playerMoves) {
      final int destination = move.getDestinationCoordinate();
      final Piece.PieceType pieceType = move.getMovedPiece().getPieceType();

      moveCountByPieceType[pieceType.ordinal()]++;
      destinationCount[destination]++;

      if (pieceType == Piece.PieceType.PAWN) {
        pawnDestinationCount[destination]++;
      }

      final int rank = destination / 8;
      final int file = destination % 8;

      if ((alliance.isWhite() && rank < 4) || (!alliance.isWhite() && rank > 3)) {
        spaceCount++;
      }

      if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5) {
        spaceCount++;
      }

      if (isSquareNearKing(opposingKingPosition, destination)) {
        nearKingSquareCount++;
        nearKingCountByPieceType[pieceType.ordinal()]++;
      }
    }

    return new MoveStatistics(playerMoves.size(), moveCountByPieceType, destinationCount,
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
   * Calculates the overall score of the current board position for a given player
   * using modern chess engine evaluation principles tuned for middlegame positions.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @param playerStatistics The statistics of the player's legal move list.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board,
                       final MoveStatistics playerStatistics,
                       final MoveStatistics opponentStatistics,
                       final PawnStructure playerPawns,
                       final PawnStructure opponentPawns) {
    return materialEvaluation(player.getActivePieces()) +
            mobilityEvaluation(playerStatistics) +
            kingSafetyEvaluation(player, board, playerPawns, opponentStatistics) +
            pawnStructureEvaluation(player, board, playerPawns, opponentPawns) +
            pieceCoordinationEvaluation(player, board, opponentStatistics) +
            spaceControlEvaluation(playerStatistics, opponentStatistics) +
            attackingPotentialEvaluation(player, playerStatistics) +
            specialPatternsEvaluation(player, board, playerPawns, opponentPawns);
  }

  /**
   * Evaluates material balance with refined piece values and contextual adjustments.
   * Modern engines use dynamic piece values based on the position.
   *
   * @param playerPieces The collection of pieces belonging to the player.
   * @return The material evaluation score.
   */
  private double materialEvaluation(final Collection<Piece> playerPieces) {
    double materialScore = 0;
    int numBishops = 0;

    for (final Piece piece : playerPieces) {
      materialScore += piece.getPieceValue();

      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        numBishops++;
      }
    }

    if (numBishops >= 2) {
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
   * @param board The current chess board.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentStatistics The statistics of the opponent's legal move list.
   * @return The king safety evaluation score.
   */
  private double kingSafetyEvaluation(final Player player, final Board board,
                                      final PawnStructure playerPawns,
                                      final MoveStatistics opponentStatistics) {
    double kingSafetyScore = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    if (playerKing.isOnCastledSquare()) {
      kingSafetyScore += 40;
    }

    kingSafetyScore += evaluatePawnShield(player, board, kingPosition);
    kingSafetyScore -= evaluateKingExposure(player, opponentStatistics);
    kingSafetyScore -= evaluateKingTropism(player, kingPosition);
    kingSafetyScore -= evaluateOpenFilesToKing(player, kingPosition, playerPawns);

    return kingSafetyScore;
  }

  /**
   * Evaluates the pawn shield protecting the king.
   *
   * @param player The player whose king's pawn shield is being evaluated.
   * @param board The current chess board.
   * @param kingPosition The position of the king on the board.
   * @return The pawn shield evaluation score.
   */
  private double evaluatePawnShield(final Player player, final Board board, final int kingPosition) {
    double shieldScore = 0;
    final Alliance playerAlliance = player.getAlliance();

    List<Integer> shieldSquares = getKingShieldSquares(kingPosition, playerAlliance);

    int pawnsInShield = 0;
    int intactFileCount = 0;
    Set<Integer> shieldFiles = new HashSet<>();

    for (Integer shieldSquare : shieldSquares) {
      Piece piece = board.getPiece(shieldSquare);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN &&
              piece.getPieceAllegiance() == playerAlliance) {
        pawnsInShield++;
        int file = shieldSquare % 8;
        if (!shieldFiles.contains(file)) {
          intactFileCount++;
          shieldFiles.add(file);
        }
      }
    }

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
   * Gets the squares that form a pawn shield for the king.
   *
   * @param kingPosition The position of the king on the board.
   * @param alliance The alliance of the king.
   * @return A list of coordinates representing the king's pawn shield squares.
   */
  private List<Integer> getKingShieldSquares(final int kingPosition, final Alliance alliance) {
    List<Integer> shieldSquares = new ArrayList<>();
    final int kingFile = kingPosition % 8;
    final int kingRank = kingPosition / 8;

    boolean kingSide = kingFile >= 4;
    int shieldRank = alliance.isWhite() ? kingRank - 1 : kingRank + 1;

    if (shieldRank >= 0 && shieldRank < 8) {
      for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
        shieldSquares.add(shieldRank * 8 + file);
      }

      if ((kingSide && (kingFile == 6 || kingFile == 5)) ||
              (!kingSide && (kingFile == 1 || kingFile == 2))) {
        if (alliance.isWhite()) {
          if (kingRank == 7) {
            for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
              shieldSquares.add((kingRank - 2) * 8 + file);
            }
          }
        } else {
          if (kingRank == 0) {
            for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
              shieldSquares.add((kingRank + 2) * 8 + file);
            }
          }
        }
      }
    }

    return shieldSquares;
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
   * Determines if a square is near the king.
   *
   * @param kingPosition The position of the king on the board.
   * @param square The square to check proximity to the king.
   * @return True if the square is within 2 squares of the king, false otherwise.
   */
  private boolean isSquareNearKing(final int kingPosition, final int square) {
    final int kingRank = kingPosition / 8;
    final int kingFile = kingPosition % 8;
    final int squareRank = square / 8;
    final int squareFile = square % 8;

    return Math.max(Math.abs(kingRank - squareRank), Math.abs(kingFile - squareFile)) <= 2;
  }

  /**
   * Evaluates king tropism - the proximity of opponent pieces to the king.
   *
   * @param player The player whose king is being evaluated for tropism.
   * @param kingPosition The position of the king on the board.
   * @return The king tropism score (higher values indicate more danger).
   */
  private double evaluateKingTropism(final Player player, final int kingPosition) {
    double tropismScore = 0;
    final Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();

    for (final Piece piece : opponentPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) {
        continue;
      }

      int distance = calculateChebyshevDistance(kingPosition, piece.getPiecePosition());
      double pieceValue = piece.getPieceValue() / 100.0;

      switch (piece.getPieceType()) {
        case QUEEN:
          tropismScore += (7 - distance) * 6 * pieceValue;
          break;
        case ROOK:
          tropismScore += (7 - distance) * 4 * pieceValue;
          break;
        case BISHOP:
          tropismScore += (7 - distance) * 3 * pieceValue;
          break;
        case KNIGHT:
          if (distance <= 3) {
            tropismScore += (4 - distance) * 4 * pieceValue;
          }
          break;
        case PAWN:
          if (distance <= 2) {
            tropismScore += (3 - distance) * 2 * pieceValue;
          }
          break;
      }
    }

    return tropismScore;
  }

  /**
   * Evaluates open files leading to the king, which can be dangerous.
   *
   * @param player The player whose king is being evaluated for open file exposure.
   * @param kingPosition The position of the king on the board.
   * @param playerPawns The structure of the player's pawns.
   * @return The open files evaluation score (higher values indicate more exposure).
   */
  private double evaluateOpenFilesToKing(final Player player, final int kingPosition,
                                         final PawnStructure playerPawns) {
    double openFileScore = 0;
    final int kingFile = kingPosition % 8;

    if (!isPawnOnFile(kingFile, playerPawns)) {
      openFileScore += 25;

      if (hasHeavyPieceOnFile(kingFile, player.getOpponent().getActivePieces())) {
        openFileScore += 35;
      }
    }

    for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
      if (file == kingFile) continue;

      if (!isPawnOnFile(file, playerPawns)) {
        openFileScore += 15;

        if (hasHeavyPieceOnFile(file, player.getOpponent().getActivePieces())) {
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
   * Checks if there's a heavy piece (rook or queen) on the specified file.
   *
   * @param file The file to check for heavy pieces (0-7).
   * @param pieces The collection of pieces to check.
   * @return True if a heavy piece is found on the file, false otherwise.
   */
  private boolean hasHeavyPieceOnFile(final int file, final Collection<Piece> pieces) {
    for (final Piece piece : pieces) {
      if ((piece.getPieceType() == Piece.PieceType.ROOK ||
              piece.getPieceType() == Piece.PieceType.QUEEN) &&
              piece.getPiecePosition() % 8 == file) {
        return true;
      }
    }
    return false;
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
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationEvaluation(final Player player, final Board board,
                                             final MoveStatistics opponentStatistics) {
    double coordinationScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();

    coordinationScore += evaluateBishopPair(playerPieces);
    coordinationScore += evaluateRookCoordination(playerPieces, board);
    coordinationScore += evaluatePieceProtection(playerPieces, board, opponentStatistics);
    coordinationScore += evaluatePieceActivity(playerPieces);

    return coordinationScore;
  }

  /**
   * Evaluates bishop pair bonus, which is significant in middlegame.
   *
   * @param playerPieces The player's pieces.
   * @return The bishop pair evaluation score.
   */
  private double evaluateBishopPair(final Collection<Piece> playerPieces) {
    double bishopPairScore = 0;
    boolean hasLightSquareBishop = false;
    boolean hasDarkSquareBishop = false;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        final int square = piece.getPiecePosition();
        final boolean isLightSquare = ((square / 8) + (square % 8)) % 2 == 0;

        if (isLightSquare) {
          hasLightSquareBishop = true;
        } else {
          hasDarkSquareBishop = true;
        }
      }
    }

    if (hasLightSquareBishop && hasDarkSquareBishop) {
      bishopPairScore += 50;
    }

    return bishopPairScore;
  }

  /**
   * Evaluates rook coordination, including connected rooks and rooks on open and semi-open files.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @return The rook coordination evaluation score.
   */
  private double evaluateRookCoordination(final Collection<Piece> playerPieces, final Board board) {
    double rookScore = 0;
    List<Piece> rooks = new ArrayList<>();

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.ROOK) {
        rooks.add(piece);
      }
    }

    if (rooks.size() >= 2) {
      boolean rooksConnected = false;
      for (int i = 0; i < rooks.size() - 1; i++) {
        for (int j = i + 1; j < rooks.size(); j++) {
          final int rookPos1 = rooks.get(i).getPiecePosition();
          final int rookPos2 = rooks.get(j).getPiecePosition();

          if (rookPos1 / 8 == rookPos2 / 8) {
            rookScore += 20;
            rooksConnected = true;
          }

          if (rookPos1 % 8 == rookPos2 % 8) {
            rookScore += 15;
            rooksConnected = true;
          }
        }
      }

      if (rooksConnected) {
        rookScore += 10;
      }
    }

    for (final Piece rook : rooks) {
      final int rookFile = rook.getPiecePosition() % 8;

      if (isOpenFile(rookFile, board)) {
        rookScore += 20;
      } else if (isSemiOpenFile(rookFile, board, rook.getPieceAllegiance())) {
        rookScore += 10;
      }
    }

    return rookScore;
  }

  /**
   * Checks if a file is completely open (no pawns on it).
   *
   * @param file The file to check (0-7).
   * @param board The current chess board.
   * @return True if the file is open, false otherwise.
   */
  private boolean isOpenFile(final int file, final Board board) {
    for (int rank = 0; rank < 8; rank++) {
      final Piece piece = board.getPiece(rank * 8 + file);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if a file is semi-open (no friendly pawns on it).
   *
   * @param file The file to check (0-7).
   * @param board The current chess board.
   * @param alliance The alliance to check for absence of pawns.
   * @return True if the file is semi-open, false otherwise.
   */
  private boolean isSemiOpenFile(final int file, final Board board, final Alliance alliance) {
    for (int rank = 0; rank < 8; rank++) {
      final Piece piece = board.getPiece(rank * 8 + file);
      if (piece != null && piece.getPieceType() == Piece.PieceType.PAWN &&
              piece.getPieceAllegiance() == alliance) {
        return false;
      }
    }
    return true;
  }

  /**
   * Evaluates knight outposts - knights protected by pawns and in opponent's territory.
   *
   * @param playerPieces The player's pieces.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The knight outposts evaluation score.
   */
  private double evaluateKnightOutposts(final Collection<Piece> playerPieces,
                                        final PawnStructure playerPawns,
                                        final PawnStructure opponentPawns) {
    double outpostScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        final int position = piece.getPiecePosition();
        final int file = position % 8;
        final int rank = position / 8;
        final Alliance alliance = piece.getPieceAllegiance();

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
   * @param playerPieces The player's pieces.
   * @return The piece activity evaluation score.
   */
  private double evaluatePieceActivity(final Collection<Piece> playerPieces) {
    double activityScore = 0;

    for (final Piece piece : playerPieces) {
      final int position = piece.getPiecePosition();
      final int file = position % 8;
      final int rank = position / 8;

      int fileDistance = Math.min(Math.abs(file - 3), Math.abs(file - 4));
      int rankDistance = Math.min(Math.abs(rank - 3), Math.abs(rank - 4));
      int distanceFromCenter = fileDistance + rankDistance;

      switch (piece.getPieceType()) {
        case KNIGHT:
          activityScore += (6 - distanceFromCenter) * 5;
          break;
        case BISHOP:
          activityScore += (6 - distanceFromCenter) * 4;
          break;
        case ROOK:
          activityScore += (3 - fileDistance) * 3;
          break;
        case QUEEN:
          activityScore += (6 - distanceFromCenter) * 2;
          break;
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
   * @return The attacking potential evaluation score.
   */
  private double attackingPotentialEvaluation(final Player player,
                                              final MoveStatistics playerStatistics) {
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

      final int defendingPiecesCount = countDefendingPieces(player.getOpponent(), kingPosition);
      attackScore -= defendingPiecesCount * 15;
    }

    return attackScore;
  }

  /**
   * Counts pieces that can help defend the king.
   *
   * @param player The defending player.
   * @param kingPosition The position of the king being defended.
   * @return The number of pieces that can defend the king.
   */
  private int countDefendingPieces(final Player player, final int kingPosition) {
    int defendingCount = 0;

    for (final Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() == Piece.PieceType.KING) {
        continue;
      }

      final int piecePosition = piece.getPiecePosition();

      if (isSquareNearKing(kingPosition, piecePosition)) {
        defendingCount++;
      }
    }

    return defendingCount;
  }

  /**
   * Evaluates special positional patterns that are important in the middlegame.
   *
   * @param player The player whose special patterns are being evaluated.
   * @param board The current chess board.
   * @param playerPawns The structure of the player's pawns.
   * @param opponentPawns The structure of the opponent's pawns.
   * @return The special patterns evaluation score.
   */
  private double specialPatternsEvaluation(final Player player, final Board board,
                                           final PawnStructure playerPawns,
                                           final PawnStructure opponentPawns) {
    double patternScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Alliance alliance = player.getAlliance();

    patternScore += evaluateRooksOn7thRank(playerPieces, board, alliance);
    patternScore += evaluateFianchetto(playerPieces, alliance);
    patternScore += evaluateBadBishops(playerPieces, playerPawns);
    patternScore += evaluateKnightOutposts(playerPieces, playerPawns, opponentPawns);
    patternScore += evaluateQueenPositioning(playerPieces, board, alliance);

    return patternScore;
  }

  /**
   * Evaluates rooks on the 7th rank (or 2nd for black), which is often very strong.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @return The rooks on 7th rank evaluation score.
   */
  private double evaluateRooksOn7thRank(final Collection<Piece> playerPieces,
                                        final Board board,
                                        final Alliance alliance) {
    double rookScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.ROOK) {
        final int position = piece.getPiecePosition();
        final int rank = position / 8;

        if ((alliance.isWhite() && rank == 1) || (!alliance.isWhite() && rank == 6)) {
          rookScore += 30;

          final Collection<Piece> opponentPieces = alliance.isWhite() ?
                  board.getBlackPieces() :
                  board.getWhitePieces();

          for (final Piece opponentPiece : opponentPieces) {
            if (opponentPiece.getPieceType() == Piece.PieceType.PAWN) {
              final int opponentRank = opponentPiece.getPiecePosition() / 8;

              if ((alliance.isWhite() && opponentRank == 1) ||
                      (!alliance.isWhite() && opponentRank == 6)) {
                rookScore += 10;
              }
            }
          }

          final King opponentKing = opposingKing(board, alliance);
          final int kingRank = opponentKing.getPiecePosition() / 8;

          if ((alliance.isWhite() && kingRank == 0) || (!alliance.isWhite() && kingRank == 7)) {
            rookScore += 20;
          }
        }
      }
    }

    return rookScore;
  }

  /**
   * Evaluates bishops standing on the fianchetto squares b2 and g2 for white, or b7 and g7 for
   * black. The supporting pawn structure is not inspected.
   *
   * @param playerPieces The player's pieces.
   * @param alliance The alliance of the player.
   * @return The fianchetto evaluation score.
   */
  private double evaluateFianchetto(final Collection<Piece> playerPieces, final Alliance alliance) {
    double fianchettoScore = 0;

    final int kingsideBishopPosition = alliance.isWhite() ? 54 : 14;
    final int queensideBishopPosition = alliance.isWhite() ? 49 : 9;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        final int position = piece.getPiecePosition();

        if (position == kingsideBishopPosition) {
          fianchettoScore += 20;
        } else if (position == queensideBishopPosition) {
          fianchettoScore += 15;
        }
      }
    }

    return fianchettoScore;
  }

  /**
   * Evaluates bad bishops - bishops blocked by their own pawns.
   *
   * @param playerPieces The player's pieces.
   * @param playerPawns The structure of the player's pawns.
   * @return The bad bishops evaluation score.
   */
  private double evaluateBadBishops(final Collection<Piece> playerPieces,
                                    final PawnStructure playerPawns) {
    double badBishopScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        int pawnsOnSameColor = getPawnsOnSameColor(playerPawns, piece);

        if (pawnsOnSameColor >= 3) {
          badBishopScore -= 20;
        } else if (pawnsOnSameColor == 2) {
          badBishopScore -= 10;
        }
      }
    }

    return badBishopScore;
  }

  /**
   * Counts the number of pawns on the same colored squares as the bishop.
   *
   * @param playerPawns The structure of the player's pawns.
   * @param piece The bishop piece.
   * @return The number of pawns on the same colored squares.
   */
  private static int getPawnsOnSameColor(PawnStructure playerPawns, Piece piece) {
    final int position = piece.getPiecePosition();
    final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

    return isLightSquare ? playerPawns.lightSquareCount() : playerPawns.darkSquareCount();
  }

  /**
   * Evaluates queen positioning in the middlegame.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board.
   * @param alliance The alliance of the player.
   * @return The queen positioning evaluation score.
   */
  private double evaluateQueenPositioning(final Collection<Piece> playerPieces,
                                          final Board board,
                                          final Alliance alliance) {
    double queenScore = 0;

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.QUEEN) {
        final int position = piece.getPiecePosition();
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
          int supportingPieces = getSupportingPieces(playerPieces, alliance, rank);

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
    }

    return queenScore;
  }

  /**
   * Counts the number of supporting pieces for a queen in an advanced position.
   *
   * @param playerPieces The player's pieces.
   * @param alliance The alliance of the player.
   * @param rank The rank of the queen.
   * @return The number of supporting pieces.
   */
  private static int getSupportingPieces(Collection<Piece> playerPieces, Alliance alliance, int rank) {
    int supportingPieces = 0;
    for (final Piece supportPiece : playerPieces) {
      if (supportPiece.getPieceType() != Piece.PieceType.QUEEN &&
              supportPiece.getPieceType() != Piece.PieceType.KING) {
        final int supportPosition = supportPiece.getPiecePosition();
        final int supportRank = supportPosition / 8;

        if ((alliance.isWhite() && supportRank <= rank) ||
                (!alliance.isWhite() && supportRank >= rank)) {
          supportingPieces++;
        }
      }
    }
    return supportingPieces;
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
}