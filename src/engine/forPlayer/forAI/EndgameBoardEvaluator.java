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

  /** The fraction of a threatened piece's value charged against the side that owns it. */
  private static final double THREAT_FRACTION = 0.25;

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
   * Evaluates the given board from the perspective of both players and returns a score.
   * The evaluation considers endgame-specific factors and returns a positive score when
   * white has an advantage and a negative score when black has an advantage.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board position.
   */
  @Override
  public double evaluate(final Board board) {
    return (score(board.whitePlayer(), board) - score(board.blackPlayer(), board));
  }

  /**
   * Calculates the overall score of the current board position for a given player
   * using modern chess engine evaluation principles tuned for endgame positions.
   * The evaluation combines material assessment, king activity, passed pawn evaluation,
   * pawn structure analysis, piece coordination, and endgame-specific patterns.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @return The evaluation score of the board from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board) {
    return materialEvaluation(player, board) +
            kingActivityEvaluation(player, board) +
            passedPawnEvaluation(player, board) +
            pawnStructureEvaluation(player, board) +
            pieceCoordinationEvaluation(player, board) +
            rookEndgameEvaluation(player, board) +
            bishopEndgameEvaluation(player, board) +
            drawPatternEvaluation(player, board) +
            mobilityEvaluation(player, board) +
            pieceSafetyEvaluation(player, board);
  }

  /**
   * Evaluates material balance with specific endgame piece values and recognizes
   * special endgame material combinations. This method applies endgame-specific
   * piece values and identifies patterns like insufficient material or favorable
   * material combinations.
   *
   * @param player The player whose material is being evaluated.
   * @param board The current chess board state.
   * @return The material evaluation score for the player.
   */
  private double materialEvaluation(final Player player, final Board board) {
    double materialScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();
    final boolean isEndgame = isDeepEndgame(board);

    Map<Piece.PieceType, Integer> playerPieceCounts = countPieceTypes(playerPieces);
    Map<Piece.PieceType, Integer> opponentPieceCounts = countPieceTypes(opponentPieces);

    for (final Piece piece : playerPieces) {
      switch (piece.getPieceType()) {
        case PAWN:
          materialScore += 100;
          break;
        case KNIGHT:
          materialScore += isEndgame ? 290 : 310;
          break;
        case BISHOP:
          materialScore += isEndgame ? 330 : 320;
          break;
        case ROOK:
          materialScore += isEndgame ? 530 : 500;
          break;
        case QUEEN:
          materialScore += 900;
          break;
        case KING:
          materialScore += 10000;
          break;
      }
    }

    if (playerPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0) >= 2) {
      materialScore += 50;
    }

    if (isInsufficientMaterial(playerPieceCounts, opponentPieceCounts)) {
      materialScore -= 800;
    }

    if (evaluateSpecialMaterialCombinations(playerPieceCounts, opponentPieceCounts, player.getAlliance())) {
      materialScore += 100;
    }

    if (hasOppositeColoredBishops(playerPieces, opponentPieces)) {
      materialScore -= 50;
    }

    return materialScore;
  }

  /**
   * Counts the number of each piece type in the given collection of pieces.
   *
   * @param pieces The collection of pieces to count.
   * @return A map containing the count of each piece type.
   */
  private Map<Piece.PieceType, Integer> countPieceTypes(final Collection<Piece> pieces) {
    Map<Piece.PieceType, Integer> pieceCounts = new HashMap<>();

    for (final Piece piece : pieces) {
      pieceCounts.put(piece.getPieceType(),
              pieceCounts.getOrDefault(piece.getPieceType(), 0) + 1);
    }

    return pieceCounts;
  }

  /**
   * Checks if the position is in a deep endgame state with few pieces remaining.
   * A deep endgame is characterized by having 4 or fewer non-pawn, non-king pieces.
   *
   * @param board The current chess board.
   * @return True if the position is in a deep endgame, false otherwise.
   */
  private boolean isDeepEndgame(final Board board) {
    final Collection<Piece> allPieces = board.getAllPieces();
    int nonPawnPieceCount = 0;

    for (final Piece piece : allPieces) {
      if (piece.getPieceType() != Piece.PieceType.PAWN &&
              piece.getPieceType() != Piece.PieceType.KING) {
        nonPawnPieceCount++;
      }
    }

    return nonPawnPieceCount <= 4;
  }

  /**
   * Checks for insufficient material to force checkmate. This includes positions
   * like king versus king, king and minor piece versus king, or king and two
   * knights versus king.
   *
   * @param playerPieceCounts The piece counts for the player.
   * @param opponentPieceCounts The piece counts for the opponent.
   * @return True if insufficient material exists, false otherwise.
   */
  private boolean isInsufficientMaterial(final Map<Piece.PieceType, Integer> playerPieceCounts,
                                         final Map<Piece.PieceType, Integer> opponentPieceCounts) {
    boolean noPawns = playerPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) == 0 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) == 0;

    if (noPawns) {
      if (playerPieceCounts.size() == 1 && opponentPieceCounts.size() == 1) {
        return true;
      }

      if ((playerPieceCounts.size() == 2 && opponentPieceCounts.size() == 1) ||
              (playerPieceCounts.size() == 1 && opponentPieceCounts.size() == 2)) {
        int minorsCount = playerPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) +
                playerPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0) +
                opponentPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) +
                opponentPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0);

        return minorsCount <= 1;
      }

      return (playerPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) == 2 &&
              playerPieceCounts.size() == 2 && opponentPieceCounts.size() == 1) ||
              (opponentPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) == 2 &&
                      opponentPieceCounts.size() == 2 && playerPieceCounts.size() == 1);
    }

    return false;
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
  private boolean evaluateSpecialMaterialCombinations(final Map<Piece.PieceType, Integer> playerPieceCounts,
                                                      final Map<Piece.PieceType, Integer> opponentPieceCounts,
                                                      final Alliance playerAlliance) {
    if (playerPieceCounts.getOrDefault(Piece.PieceType.QUEEN, 0) > 0 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.ROOK, 0) > 0 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.QUEEN, 0) == 0) {
      return true;
    }

    if (playerPieceCounts.getOrDefault(Piece.PieceType.ROOK, 0) > 0 &&
            (opponentPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0) > 0 ||
                    opponentPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) > 0) &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.ROOK, 0) == 0 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.QUEEN, 0) == 0) {
      return true;
    }

    return playerPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0) >= 2 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.KNIGHT, 0) > 0 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.BISHOP, 0) == 0;
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
   * @param board The current chess board state.
   * @return The king activity evaluation score.
   */
  private double kingActivityEvaluation(final Player player, final Board board) {
    double kingActivityScore = 0;
    final King playerKing = player.getPlayerKing();
    final King opponentKing = player.getOpponent().getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    kingActivityScore += evaluateKingCentralization(kingPosition);
    kingActivityScore += evaluateKingProximity(kingPosition, opponentKing.getPiecePosition());
    kingActivityScore += evaluateKingPawnDefense(player, board);
    kingActivityScore -= evaluateKingExposure(player, board) * 0.5;

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

    return (7 - distanceFromCenter) * 10;
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
      return 20;
    }

    return 0;
  }

  /**
   * Evaluates how well the king defends friendly pawns and attacks enemy pawns.
   * In endgames, kings become active pieces that should participate in both
   * defense and attack operations.
   *
   * @param player The player whose king-pawn cooperation is being evaluated.
   * @param board The current chess board state.
   * @return The king-pawn defense evaluation score.
   */
  private double evaluateKingPawnDefense(final Player player, final Board board) {
    double kingPawnDefenseScore = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();
    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());

    for (final Piece pawn : playerPawns) {
      final int distance = calculateChebyshevDistance(kingPosition, pawn.getPiecePosition());

      if (distance <= 1) {
        kingPawnDefenseScore += 10;
      } else if (distance == 2) {
        kingPawnDefenseScore += 5;
      }
    }

    for (final Piece pawn : opponentPawns) {
      final int distance = calculateChebyshevDistance(kingPosition, pawn.getPiecePosition());

      if (distance <= 1) {
        kingPawnDefenseScore += 8;
      } else if (distance == 2) {
        kingPawnDefenseScore += 4;
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
   * @param board The current chess board state.
   * @return The king exposure evaluation score.
   */
  private double evaluateKingExposure(final Player player, final Board board) {
    double exposureScore = 0;
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    for (final Move move : opponentMoves) {
      final int destination = move.getDestinationCoordinate();

      if (destination == kingPosition) {
        exposureScore += 30;
      }

      if (calculateChebyshevDistance(kingPosition, destination) <= 1) {
        exposureScore += 5;
      }
    }

    if (player.isInCheck()) {
      exposureScore += 20;
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
   * @return The passed pawn evaluation score.
   */
  private double passedPawnEvaluation(final Player player, final Board board) {
    double passedPawnScore = 0;
    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());
    final Alliance alliance = player.getAlliance();
    final King playerKing = player.getPlayerKing();
    final King opponentKing = player.getOpponent().getPlayerKing();

    for (final Piece pawn : playerPawns) {
      if (isPassedPawn(pawn, opponentPawns, alliance)) {
        final int pawnPosition = pawn.getPiecePosition();
        final int pawnRank = pawnPosition / 8;
        final int rankFromPromotion = alliance.isWhite() ? pawnRank : (7 - pawnRank);
        final int advancementScore = (7 - rankFromPromotion) * 20;
        passedPawnScore += advancementScore;

        if (rankFromPromotion <= 2) {
          passedPawnScore += 50;
        } else if (rankFromPromotion <= 4) {
          passedPawnScore += 30;
        }

        final int kingDistance = calculateChebyshevDistance(playerKing.getPiecePosition(), pawnPosition);
        passedPawnScore += (8 - kingDistance) * 5;

        final int opponentKingDistance = calculateChebyshevDistance(opponentKing.getPiecePosition(), pawnPosition);
        passedPawnScore += opponentKingDistance * 3;

        if (isPathToPromotionClear(pawn, alliance, board)) {
          passedPawnScore += 40;
        }

        if (isPawnProtected(pawn, playerPawns, alliance)) {
          passedPawnScore += 25;
        }
      }
    }

    passedPawnScore += evaluateConnectedPassedPawns(playerPawns, opponentPawns, alliance);

    return passedPawnScore;
  }

  /**
   * Checks if a pawn is a passed pawn by verifying that no opposing pawns
   * block its path to promotion on the same file or adjacent files.
   *
   * @param pawn The pawn to check.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the pawn being checked.
   * @return True if the pawn is passed, false otherwise.
   */
  private boolean isPassedPawn(final Piece pawn, final List<Piece> opponentPawns, final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int rankDirection = alliance.isWhite() ? -1 : 1;

    for (int rank = pawnRank + rankDirection; alliance.isWhite() ? (rank >= 0) : (rank < 8); rank += rankDirection) {
      if (isPawnAtPosition(opponentPawns, rank * 8 + pawnFile)) {
        return false;
      }

      if (pawnFile > 0 && isPawnAtPosition(opponentPawns, rank * 8 + (pawnFile - 1))) {
        return false;
      }

      if (pawnFile < 7 && isPawnAtPosition(opponentPawns, rank * 8 + (pawnFile + 1))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Checks if there is a pawn at the specified position in the given list of pawns.
   *
   * @param pawns The list of pawns to search.
   * @param position The position to check.
   * @return True if a pawn exists at the position, false otherwise.
   */
  private boolean isPawnAtPosition(final List<Piece> pawns, final int position) {
    for (final Piece pawn : pawns) {
      if (pawn.getPiecePosition() == position) {
        return true;
      }
    }
    return false;
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
   * @param playerPawns The list of friendly pawns.
   * @param alliance The alliance of the pawn.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtected(final Piece pawn, final List<Piece> playerPawns, final Alliance alliance) {
    final int pawnPosition = pawn.getPiecePosition();
    final int pawnFile = pawnPosition % 8;
    final int pawnRank = pawnPosition / 8;
    final int rankBehind = alliance.isWhite() ? pawnRank + 1 : pawnRank - 1;

    if (rankBehind < 0 || rankBehind >= 8) {
      return false;
    }

    if (pawnFile > 0 && isPawnAtPosition(playerPawns, rankBehind * 8 + (pawnFile - 1))) {
      return true;
    }

    return pawnFile < 7 && isPawnAtPosition(playerPawns, rankBehind * 8 + (pawnFile + 1));
  }

  /**
   * Evaluates connected passed pawns, which are extremely powerful in endgames.
   * Connected passed pawns are adjacent pawns that are both passed and can
   * support each other's advancement.
   *
   * @param playerPawns The player's pawns.
   * @param opponentPawns The opponent's pawns.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The connected passed pawns evaluation score.
   */
  private double evaluateConnectedPassedPawns(final List<Piece> playerPawns,
                                              final List<Piece> opponentPawns,
                                              final Alliance alliance) {
    double connectedScore = 0;
    List<Piece> passedPawns = new ArrayList<>();

    for (final Piece pawn : playerPawns) {
      if (isPassedPawn(pawn, opponentPawns, alliance)) {
        passedPawns.add(pawn);
      }
    }

    for (int i = 0; i < passedPawns.size(); i++) {
      for (int j = i + 1; j < passedPawns.size(); j++) {
        final int file1 = passedPawns.get(i).getPiecePosition() % 8;
        final int file2 = passedPawns.get(j).getPiecePosition() % 8;

        if (Math.abs(file1 - file2) == 1) {
          connectedScore += 80;

          final int rank1 = passedPawns.get(i).getPiecePosition() / 8;
          final int rank2 = passedPawns.get(j).getPiecePosition() / 8;
          final int advancedRank = alliance.isWhite() ?
                  Math.min(rank1, rank2) :
                  Math.max(rank1, rank2);

          if ((alliance.isWhite() && advancedRank <= 2) ||
                  (!alliance.isWhite() && advancedRank >= 5)) {
            connectedScore += 50;
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
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureEvaluation(final Player player, final Board board) {
    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());
    final Alliance alliance = player.getAlliance();
    double pawnStructureScore = 0;

    pawnStructureScore += evaluatePawnIslands(playerPawns);
    pawnStructureScore += evaluateDoubledPawns(playerPawns);
    pawnStructureScore += evaluateIsolatedPawns(playerPawns, opponentPawns);
    pawnStructureScore += evaluatePawnMajorities(playerPawns, opponentPawns);
    pawnStructureScore += evaluatePawnChains(playerPawns, alliance);
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
      islandScore -= (islands - 1) * 15;
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
        doubledPawnScore -= (count - 1) * 25;
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
        isolatedPawnScore -= 20;

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
          isolatedPawnScore -= 10;
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
      majorityScore += 15 + (playerKingsidePawns - opponentKingsidePawns) * 5;
    }

    if (playerQueensidePawns > opponentQueensidePawns) {
      majorityScore += 15 + (playerQueensidePawns - opponentQueensidePawns) * 5;
    }

    return majorityScore;
  }

  /**
   * Evaluates pawn chains, which are connected pawns that protect each other.
   * Pawn chains provide mutual support and are generally advantageous.
   *
   * @param playerPawns The player's pawns.
   * @param alliance The alliance of the pawns.
   * @return The pawn chains evaluation score.
   */
  private double evaluatePawnChains(final List<Piece> playerPawns, final Alliance alliance) {
    double pawnChainScore = 0;
    Map<Integer, Set<Integer>> pawnsByRank = new HashMap<>();

    for (final Piece pawn : playerPawns) {
      final int rank = pawn.getPiecePosition() / 8;
      final int file = pawn.getPiecePosition() % 8;

      if (!pawnsByRank.containsKey(rank)) {
        pawnsByRank.put(rank, new HashSet<>());
      }

      pawnsByRank.get(rank).add(file);
    }

    int chainLinks = 0;
    for (final Piece pawn : playerPawns) {
      final int rank = pawn.getPiecePosition() / 8;
      final int file = pawn.getPiecePosition() % 8;

      if (isPawnProtectingFile(pawnsByRank, rank, file, alliance)) {
        chainLinks++;
      }
    }

    pawnChainScore += chainLinks * 5;

    return pawnChainScore;
  }

  /**
   * Checks if a pawn is protected by another pawn on an adjacent file. Rank indices run from zero
   * on black's back rank to seven on white's, so a protecting pawn stands one index higher for
   * white and one index lower for black.
   *
   * @param pawnsByRank A map of pawns organized by rank.
   * @param rank The rank of the pawn being checked.
   * @param file The file of the pawn being checked.
   * @param alliance The alliance of the pawn being checked.
   * @return True if the pawn is protected, false otherwise.
   */
  private boolean isPawnProtectingFile(final Map<Integer, Set<Integer>> pawnsByRank,
                                       final int rank,
                                       final int file,
                                       final Alliance alliance) {
    final int protectingRank = alliance.isWhite() ? rank + 1 : rank - 1;

    if (pawnsByRank.containsKey(protectingRank)) {
      final Set<Integer> filesWithPawns = pawnsByRank.get(protectingRank);
      return (file > 0 && filesWithPawns.contains(file - 1)) ||
              (file < 7 && filesWithPawns.contains(file + 1));
    }

    return false;
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
        backwardPawnScore -= 15;

        if (isOnSemiOpenFile(pawn, opponentPawns)) {
          backwardPawnScore -= 10;
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
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationEvaluation(final Player player, final Board board) {
    double coordinationScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();

    coordinationScore += evaluateMinorPieceCoordination(playerPieces, board);
    coordinationScore += evaluatePiecePlacement(playerPieces, getPlayerPawns(player));
    coordinationScore += evaluatePiecesSupportingPassedPawns(player, board);

    return coordinationScore;
  }

  /**
   * Evaluates minor piece coordination in endgames, including bishop pair
   * advantages and knight positioning relative to remaining pawns.
   *
   * @param playerPieces The player's pieces.
   * @param board The current chess board state.
   * @return The minor piece coordination evaluation score.
   */
  private double evaluateMinorPieceCoordination(final Collection<Piece> playerPieces, final Board board) {
    double minorPieceScore = 0;

    if (board.getAllPieces().stream().anyMatch(p -> p.getPieceType() == Piece.PieceType.PAWN)) {
      long bishopCount = playerPieces.stream()
              .filter(p -> p.getPieceType() == Piece.PieceType.BISHOP)
              .count();

      if (bishopCount >= 2) {
        minorPieceScore += 50;
      }
    }

    if (getPlayerPawns(board.whitePlayer()).size() + getPlayerPawns(board.blackPlayer()).size() <= 4) {
      long knightCount = playerPieces.stream()
              .filter(p -> p.getPieceType() == Piece.PieceType.KNIGHT)
              .count();

      minorPieceScore -= knightCount * 10;
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
      bishopScore += 10;
    }

    if ((bishopPosition % 9 == 0) || (bishopPosition % 7 == 0 && bishopPosition % 8 != 0 && bishopPosition % 8 != 7)) {
      bishopScore += 15;
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
        knightScore += (3 - distance) * 5;
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
   * @return The piece support evaluation score.
   */
  private double evaluatePiecesSupportingPassedPawns(final Player player, final Board board) {
    double supportScore = 0;
    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());
    final Alliance alliance = player.getAlliance();
    final Collection<Piece> playerPieces = player.getActivePieces();

    for (final Piece pawn : playerPawns) {
      if (isPassedPawn(pawn, opponentPawns, alliance)) {
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
              supportScore += 20;
            } else if (distance == 2) {
              supportScore += 10;
            }

            final Collection<Move> pieceMoves = piece.calculateLegalMoves(board);
            for (final Move move : pieceMoves) {
              if (move.getDestinationCoordinate() == promotionSquare) {
                supportScore += 15;
                break;
              }
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
   * @return The rook endgame evaluation score.
   */
  private double rookEndgameEvaluation(final Player player, final Board board) {
    double rookScore = 0;
    final List<Piece> playerRooks = player.getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.ROOK)
            .collect(Collectors.toList());

    if (playerRooks.isEmpty()) {
      return 0;
    }

    final List<Piece> playerPawns = getPlayerPawns(player);
    final List<Piece> opponentPawns = getPlayerPawns(player.getOpponent());
    final Alliance alliance = player.getAlliance();

    for (final Piece rook : playerRooks) {
      rookScore += evaluateRookOnOpenFile(rook, board);
      rookScore += evaluateRookBehindPassedPawn(rook, playerPawns, opponentPawns, alliance);
      rookScore += evaluateRookOn7thRank(rook, opponentPawns, alliance);

      if (playerRooks.size() >= 2) {
        rookScore += evaluateConnectedRooks(playerRooks);
      }
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
      fileScore += 30;
    } else if (semiOpenFile) {
      fileScore += 15;
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
   * @param alliance The alliance of the rook.
   * @return The rook-pawn cooperation evaluation score.
   */
  private double evaluateRookBehindPassedPawn(final Piece rook,
                                              final List<Piece> playerPawns,
                                              final List<Piece> opponentPawns,
                                              final Alliance alliance) {
    double behindPawnScore = 0;
    final int rookPosition = rook.getPiecePosition();
    final int rookFile = rookPosition % 8;
    final int rookRank = rookPosition / 8;

    for (final Piece pawn : playerPawns) {
      if (pawn.getPiecePosition() % 8 == rookFile &&
              isPassedPawn(pawn, opponentPawns, alliance)) {
        final int pawnRank = pawn.getPiecePosition() / 8;

        if ((alliance.isWhite() && rookRank > pawnRank) ||
                (!alliance.isWhite() && rookRank < pawnRank)) {
          behindPawnScore += 30;
        }
      }
    }

    for (final Piece pawn : opponentPawns) {
      if (pawn.getPiecePosition() % 8 == rookFile &&
              isPassedPawn(pawn, playerPawns, alliance.equals(Alliance.WHITE) ? Alliance.BLACK : Alliance.WHITE)) {
        final int pawnRank = pawn.getPiecePosition() / 8;

        if ((alliance.isWhite() && rookRank > pawnRank) ||
                (!alliance.isWhite() && rookRank < pawnRank)) {
          behindPawnScore += 20;
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
      seventhRankScore += 30;

      for (final Piece pawn : opponentPawns) {
        final int pawnRank = pawn.getPiecePosition() / 8;
        if ((alliance.isWhite() && pawnRank == 1) || (!alliance.isWhite() && pawnRank == 6)) {
          seventhRankScore += 10;
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
          connectedScore += 20;
        }

        if (rookPos1 % 8 == rookPos2 % 8) {
          connectedScore += 15;
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
   * @return The bishop endgame evaluation score.
   */
  private double bishopEndgameEvaluation(final Player player, final Board board) {
    double bishopScore = 0;
    final List<Piece> playerBishops = player.getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.BISHOP)
            .collect(Collectors.toList());

    if (playerBishops.isEmpty()) {
      return 0;
    }

    bishopScore += evaluateColorComplexControl(playerBishops, board);

    for (final Piece bishop : playerBishops) {
      final Collection<Move> bishopMoves = bishop.calculateLegalMoves(board);
      bishopScore += bishopMoves.size() * 5;
    }

    final List<Piece> playerKnights = player.getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.KNIGHT)
            .toList();

    final List<Piece> opponentKnights = player.getOpponent().getActivePieces().stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.KNIGHT)
            .toList();

    if (!playerBishops.isEmpty() && !opponentKnights.isEmpty() && playerKnights.isEmpty()) {
      bishopScore += evaluateBishopVsKnight(board);
    }

    return bishopScore;
  }

  /**
   * Evaluates color complex control, which is important in bishop endgames.
   * Bishops that control squares of the opposite color from most pawns are
   * generally more effective.
   *
   * @param playerBishops The player's bishops.
   * @param board The current chess board state.
   * @return The color complex control evaluation score.
   */
  private double evaluateColorComplexControl(final List<Piece> playerBishops, final Board board) {
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
      colorScore += 50;
      return colorScore;
    }

    final List<Piece> allPawns = new ArrayList<>();
    allPawns.addAll(getPlayerPawns(board.whitePlayer()));
    allPawns.addAll(getPlayerPawns(board.blackPlayer()));

    int lightSquarePawns = 0;
    int darkSquarePawns = 0;

    for (final Piece pawn : allPawns) {
      final int position = pawn.getPiecePosition();
      final boolean isLightSquare = ((position / 8) + (position % 8)) % 2 == 0;

      if (isLightSquare) {
        lightSquarePawns++;
      } else {
        darkSquarePawns++;
      }
    }

    if (hasLightSquareBishop && darkSquarePawns > lightSquarePawns) {
      colorScore += 20;
    } else if (!hasLightSquareBishop && hasDarkSquareBishop && lightSquarePawns > darkSquarePawns) {
      colorScore += 20;
    }

    if (hasLightSquareBishop && lightSquarePawns > darkSquarePawns) {
      colorScore -= 15;
    } else if (!hasLightSquareBishop && hasDarkSquareBishop && darkSquarePawns > lightSquarePawns) {
      colorScore -= 15;
    }

    return colorScore;
  }

  /**
   * Evaluates bishop versus knight dynamics in specific positions.
   * Bishops are generally better in open positions while knights prefer closed positions.
   *
   * @param board The current chess board state.
   * @return The bishop versus knight evaluation score.
   */
  private double evaluateBishopVsKnight(final Board board) {
    double dynamicScore = 0;
    final int pawnCount = getPlayerPawns(board.whitePlayer()).size() +
            getPlayerPawns(board.blackPlayer()).size();

    if (pawnCount <= 5) {
      dynamicScore += 20;
    } else if (pawnCount >= 8) {
      dynamicScore -= 10;
    }

    return dynamicScore;
  }

  /**
   * Evaluates common draw patterns in endgames, including insufficient material,
   * opposite-colored bishops, and other drawish tendencies.
   *
   * @param player The player whose draw patterns are being evaluated.
   * @param board The current chess board state.
   * @return The draw pattern evaluation score.
   */
  private double drawPatternEvaluation(final Player player, final Board board) {
    double drawScore = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();

    Map<Piece.PieceType, Integer> playerPieceCounts = countPieceTypes(playerPieces);
    Map<Piece.PieceType, Integer> opponentPieceCounts = countPieceTypes(opponentPieces);

    if (isInsufficientMaterial(playerPieceCounts, opponentPieceCounts)) {
      drawScore -= 800;
    }

    if (hasOppositeColoredBishops(playerPieces, opponentPieces) &&
            playerPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) <= 2 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) <= 2) {
      drawScore -= 200;
    }

    if (playerPieceCounts.getOrDefault(Piece.PieceType.ROOK, 0) == 1 &&
            playerPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) == 1 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.ROOK, 0) == 1 &&
            opponentPieceCounts.getOrDefault(Piece.PieceType.PAWN, 0) == 0) {
      List<Piece> playerPawns = getPlayerPawns(player);
      if (!playerPawns.isEmpty()) {
        Piece pawn = playerPawns.get(0);
        int pawnRank = pawn.getPiecePosition() / 8;

        if ((player.getAlliance().isWhite() && pawnRank <= 1) ||
                (!player.getAlliance().isWhite() && pawnRank >= 6)) {
          drawScore += 100;
        } else {
          drawScore -= 100;
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
   * @param board The current chess board state.
   * @return The mobility evaluation score.
   */
  private double mobilityEvaluation(final Player player, final Board board) {
    double mobilityScore = 0;
    final Collection<Move> playerMoves = player.getLegalMoves();

    double mobilityWeight = 1.0;

    if (isPawnEndgame(board)) {
      mobilityWeight = 0.5;
    }

    if (hasOppositeColoredBishops(player.getActivePieces(), player.getOpponent().getActivePieces())) {
      mobilityWeight = 1.5;
    }

    mobilityScore += playerMoves.size() * 4 * mobilityWeight;

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
   * @return The piece safety evaluation score.
   */
  private double pieceSafetyEvaluation(final Player player, final Board board) {
    double largestThreat = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();

    final int[] attackCount = new int[BoardUtils.NUM_TILES];
    final int[] pawnAttackCount = new int[BoardUtils.NUM_TILES];

    for (final Move move : opponentMoves) {
      final int destination = move.getDestinationCoordinate();
      attackCount[destination]++;

      if (move.getMovedPiece().getPieceType() == Piece.PieceType.PAWN) {
        pawnAttackCount[destination]++;
      }
    }

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int pieceValue = piece.getPieceValue();

      final boolean harriedByPawn = pawnAttackCount[position] > 0
              && pieceValue > Piece.PieceType.PAWN.getPieceValue();
      final boolean outnumbered = attackCount[position] > 0
              && attackCount[position] > countDefenders(playerPieces, position, board);

      if (outnumbered || harriedByPawn) {
        largestThreat = Math.max(largestThreat, pieceValue * THREAT_FRACTION);
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
   * @param board The current chess board state.
   * @return True if the position is a pawn endgame, false otherwise.
   */
  private boolean isPawnEndgame(final Board board) {
    for (final Piece piece : board.getAllPieces()) {
      if (piece.getPieceType() != Piece.PieceType.KING &&
              piece.getPieceType() != Piece.PieceType.PAWN) {
        return false;
      }
    }
    return true;
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