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
 * The OpeningGameEvaluator class provides specialized board evaluation for opening positions in chess.
 * This evaluator prioritizes opening principles such as piece development, center control, king safety,
 * and tempo over pure material advantage. It implements the BoardEvaluator interface and follows
 * the singleton pattern to ensure consistent evaluation throughout the chess engine.
 *
 * @author Aaron Ho
 */
public class OpeningGameEvaluator implements BoardEvaluator {

  /** Singleton instance of the OpeningGameEvaluator. */
  private static final OpeningGameEvaluator Instance = new OpeningGameEvaluator();

  /** The fraction of a threatened piece's value charged against the side that owns it. */
  private static final double THREAT_FRACTION = 0.25;

  /**
   * Constructs a new OpeningGameEvaluator instance.
   * Private constructor prevents external instantiation to enforce singleton pattern.
   */
  private OpeningGameEvaluator() {}

  /**
   * Returns the singleton instance of OpeningGameEvaluator.
   *
   * @return The singleton instance of OpeningGameEvaluator.
   */
  public static OpeningGameEvaluator get() {
    return Instance;
  }

  /**
   * Evaluates the given board position and returns a numerical score.
   * The evaluation considers opening-specific factors with appropriate weightings.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board from white's perspective.
   */
  @Override
  public double evaluate(final Board board) {
    return (score(board.whitePlayer(), board) - score(board.blackPlayer(), board));
  }

  /**
   * Calculates the overall positional score for a given player focusing on opening principles.
   * Combines material, development, center control, king safety, pawn structure, mobility,
   * piece coordination, and tempo evaluations with opening-specific weightings.
   *
   * @param player The player for whom the board position is being evaluated.
   * @param board The current state of the chess board.
   * @return The evaluation score from the perspective of the specified player.
   */
  @VisibleForTesting
  private double score(final Player player, final Board board) {
    final int[] defenderCounts = defenderCountsBySquare(player.getActivePieces(), board);

    return materialScore(player.getActivePieces()) +
            developmentScore(player, board) +
            centerControlScore(player, board) +
            kingSafetyScore(player, board) +
            pawnStructureScore(player, board) +
            mobilityScore(player, board) +
            pieceCoordinationScore(player, board, defenderCounts) +
            tempoScore(player, board) +
            pieceSafetyScore(player, board, defenderCounts);
  }

  /**
   * Counts, for each square occupied by one of the given pieces, the number of other pieces in the
   * collection that defend that square. The piece standing on the square is not counted, and a
   * piece whose line to the square is blocked by another piece is not counted. Squares occupied by
   * a king and squares occupied by no piece in the collection hold zero.
   *
   * @param playerPieces The pieces to test.
   * @param board The current chess board state.
   * @return A defender count for every square, indexed by board coordinate.
   */
  private int[] defenderCountsBySquare(final Collection<Piece> playerPieces, final Board board) {
    final int[] defenderCounts = new int[BoardUtils.NUM_TILES];

    for (final Piece occupant : playerPieces) {
      if (occupant.getPieceType() == Piece.PieceType.KING) continue;

      final int square = occupant.getPiecePosition();
      for (final Piece defender : playerPieces) {
        if (defender.getPiecePosition() != square && defender.defendsSquare(square, board)) {
          defenderCounts[square]++;
        }
      }
    }

    return defenderCounts;
  }

  /**
   * Evaluates material balance with reduced weight during the opening phase.
   * Material sacrifices for initiative are more acceptable in opening positions.
   *
   * @param playerPieces The collection of pieces belonging to the player.
   * @return The material evaluation score.
   */
  private double materialScore(final Collection<Piece> playerPieces) {
    double materialValue = 0;
    for (final Piece piece : playerPieces) {
      materialValue += piece.getPieceValue();
    }
    return materialValue;
  }

  /**
   * Evaluates piece development for the given player.
   * Scores each knight and bishop by whether it stands off its own back rank, penalises a queen
   * that has left its home square in proportion to the number of minor pieces still undeveloped,
   * and scores whether the king stands on a square castling can produce.
   *
   * @param player The player whose development is being evaluated.
   * @param board The current chess board state.
   * @return The development evaluation score.
   */
  private double developmentScore(final Player player, final Board board) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    Alliance alliance = player.getAlliance();
    boolean isWhite = alliance.isWhite();

    final int queenHomeSquare = isWhite ? 59 : 3;

    int developedMinorPieces = 0;
    int undevelopedMinorPieces = 0;
    boolean queenSortied = false;
    boolean queenPastMidline = false;
    boolean castled = player.getPlayerKing().isOnCastledSquare();

    for (Piece piece : playerPieces) {
      final int pieceRank = piece.getPiecePosition() / 8;

      if (piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) {

        if ((isWhite && pieceRank != 7) || (!isWhite && pieceRank != 0)) {
          score += 8;
          developedMinorPieces++;

          if (isCentralPosition(piece.getPiecePosition(), piece.getPieceType())) {
            score += 4;
          }
        } else {
          score -= 10;
          undevelopedMinorPieces++;
        }
      }

      if (piece.getPieceType() == Piece.PieceType.QUEEN &&
              piece.getPiecePosition() != queenHomeSquare) {
        queenSortied = true;

        final int queenRank = piece.getPiecePosition() / 8;
        if ((isWhite && queenRank < 4) || (!isWhite && queenRank > 3)) {
          queenPastMidline = true;
        }
      }
    }

    if (queenSortied) {
      score -= undevelopedMinorPieces * 10;

      if (queenPastMidline) {
        score -= undevelopedMinorPieces * 8;
      }
    }

    if (castled) {
      score += 50;
    } else if (canCastle(player)) {
      score += 12;
    } else if (!canCastle(player)) {
      score -= 30;
    }

    if (developedMinorPieces >= 3 && castled && !queenSortied) {
      score += 10;
    }

    return score;
  }

  /**
   * Determines if the given position is centralized for the specified piece type.
   * Central positions are generally favorable for piece development in the opening.
   *
   * @param position The board position to evaluate.
   * @param pieceType The type of piece being evaluated.
   * @return True if the position is considered centralized for the piece type.
   */
  private boolean isCentralPosition(int position, Piece.PieceType pieceType) {
    int file = position % 8;
    int rank = position / 8;

    boolean isExtendedCenter = (file >= 2 && file <= 5 && rank >= 2 && rank <= 5);

    if (pieceType == Piece.PieceType.KNIGHT) {
      return (file >= 2 && file <= 5 && rank >= 2 && rank <= 5);
    } else if (pieceType == Piece.PieceType.BISHOP) {
      return isExtendedCenter ||
              position == 16 || position == 23 ||
              position == 40 || position == 47;
    }

    return isExtendedCenter;
  }

  /**
   * Checks if the player retains castling rights on either side.
   *
   * @param player The player to check for castling capabilities.
   * @return True if the player can still castle king-side or queen-side.
   */
  private boolean canCastle(Player player) {
    return player.getPlayerKing().isKingSideCastleCapable() ||
            player.getPlayerKing().isQueenSideCastleCapable();
  }

  /**
   * Evaluates center control which is fundamental to opening theory.
   * Rewards occupation and control of central squares with pieces and pawns.
   *
   * @param player The player whose center control is being evaluated.
   * @param board The current chess board state.
   * @return The center control evaluation score.
   */
  private double centerControlScore(final Player player, final Board board) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    Collection<Move> playerMoves = player.getLegalMoves();

    final int[] centralSquares = {27, 28, 35, 36};
    final int[] extendedCenterSquares = {
            18, 19, 20, 21,
            26, 27, 28, 29,
            34, 35, 36, 37,
            42, 43, 44, 45
    };

    for (Piece piece : playerPieces) {
      int position = piece.getPiecePosition();

      for (int centralSquare : centralSquares) {
        if (position == centralSquare) {
          if (piece.getPieceType() == Piece.PieceType.PAWN) {
            score += 40;
          } else if (piece.getPieceType() == Piece.PieceType.KNIGHT ||
                  piece.getPieceType() == Piece.PieceType.BISHOP) {
            score += 20;
          } else {
            score += 10;
          }
        }
      }

      for (int extendedSquare : extendedCenterSquares) {
        if (position == extendedSquare) {
          if (piece.getPieceType() == Piece.PieceType.PAWN) {
            score += 15;
          } else {
            score += 8;
          }
        }
      }
    }

    int[] controlledSquares = new int[BoardUtils.NUM_TILES];
    for (Move move : playerMoves) {
      controlledSquares[move.getDestinationCoordinate()]++;
    }

    for (int centralSquare : centralSquares) {
      score += controlledSquares[centralSquare] * 8;
    }

    for (int extendedSquare : extendedCenterSquares) {
      score += controlledSquares[extendedSquare] * 3;
    }

    return score;
  }

  /**
   * Evaluates king safety which is critical during the opening phase.
   * Rewards a king standing on a square castling can produce and penalizes king exposure in
   * the center.
   *
   * @param player The player whose king safety is being evaluated.
   * @param board The current chess board state.
   * @return The king safety evaluation score.
   */
  private double kingSafetyScore(final Player player, final Board board) {
    double score = 0;
    final King playerKing = player.getPlayerKing();
    final int kingPosition = playerKing.getPiecePosition();

    if (playerKing.isOnCastledSquare()) {
      score += 40;
      score += evaluatePawnShield(player, kingPosition);
    } else {
      int file = kingPosition % 8;
      int rank = kingPosition / 8;

      boolean inCenter = (file >= 2 && file <= 5);
      if (inCenter) {
        score -= 25;
      }
    }

    score -= evaluateKingAttackPotential(kingPosition, player.getOpponent());

    return score;
  }

  /**
   * Evaluates the quality of the pawn shield protecting the castled king.
   * Counts pawns in shield positions and awards bonuses for intact formations.
   * The shield spans the rank in front of the king across the king's file and its neighbours,
   * clamped to the board edge, so a king on the a or h file has two shield squares rather than
   * three. The king is assumed to stand on its own back rank.
   *
   * @param player The player whose pawn shield is being evaluated.
   * @param kingPosition The position of the king on the board.
   * @return The pawn shield evaluation score.
   */
  private double evaluatePawnShield(Player player, int kingPosition) {
    double score = 0;
    Collection<Piece> playerPieces = player.getActivePieces();
    List<Piece> pawns = playerPieces.stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.PAWN)
            .toList();

    int kingFile = kingPosition % 8;
    int kingRank = kingPosition / 8;
    int shieldRank = player.getAlliance().isWhite() ? kingRank - 1 : kingRank + 1;

    List<Integer> shieldSquares = new ArrayList<>();
    for (int file = Math.max(0, kingFile - 1); file <= Math.min(7, kingFile + 1); file++) {
      shieldSquares.add(shieldRank * 8 + file);
    }

    int pawnsInShield = 0;
    for (Integer shieldSquare : shieldSquares) {
      for (Piece pawn : pawns) {
        if (pawn.getPiecePosition() == shieldSquare) {
          pawnsInShield++;
          break;
        }
      }
    }

    if (pawnsInShield == 3) {
      score += 20;
    } else if (pawnsInShield == 2) {
      score += 10;
    } else if (pawnsInShield == 1) {
      score += 4;
    } else {
      score -= 15;
    }

    return score;
  }

  /**
   * Evaluates the potential for king attacks by calculating opponent piece proximity.
   * Awards penalties based on the attacking potential of nearby opponent pieces.
   *
   * @param kingPosition The position of the king being evaluated.
   * @param opponent The opposing player whose pieces pose attack threats.
   * @return The king attack potential penalty score.
   */
  private double evaluateKingAttackPotential(int kingPosition, Player opponent) {
    double attackPotential = 0;
    Collection<Piece> opponentPieces = opponent.getActivePieces();

    for (Piece piece : opponentPieces) {
      int piecePos = piece.getPiecePosition();
      int rankDistance = Math.abs((kingPosition / 8) - (piecePos / 8));
      int fileDistance = Math.abs((kingPosition % 8) - (piecePos % 8));
      int distance = Math.max(rankDistance, fileDistance);

      if (distance <= 2) {
        switch (piece.getPieceType()) {
          case QUEEN:
            attackPotential += (3 - distance) * 40;
            break;
          case ROOK:
            attackPotential += (3 - distance) * 25;
            break;
          case BISHOP:
            attackPotential += (3 - distance) * 15;
            break;
          case KNIGHT:
            attackPotential += (3 - distance) * 20;
            break;
          case PAWN:
            attackPotential += (3 - distance) * 5;
            break;
        }
      }
    }

    return attackPotential;
  }

  /**
   * Evaluates pawn structure with emphasis on opening considerations.
   * Analyzes center pawns, doubled pawns, isolated pawns, pawn chains, and pawn advances.
   *
   * @param player The player whose pawn structure is being evaluated.
   * @param board The current chess board state.
   * @return The pawn structure evaluation score.
   */
  private double pawnStructureScore(final Player player, final Board board) {
    double score = 0;
    Alliance alliance = player.getAlliance();
    List<Piece> pawns = getPawns(player);

    score += evaluateCenterPawns(pawns, alliance);
    score += evaluateDoubledPawns(pawns);
    score += evaluateIsolatedPawns(pawns);
    score += evaluatePawnChains(pawns);
    score += evaluatePawnAdvances(pawns, alliance);

    return score;
  }

  /**
   * Evaluates the central pawn structure for opening control.
   * Rewards presence of pawns on central files and supporting pawn formations.
   *
   * @param pawns The collection of pawns to evaluate.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The center pawn evaluation score.
   */
  private double evaluateCenterPawns(final List<Piece> pawns, final Alliance alliance) {
    double score = 0;

    boolean hasDPawn = false;
    boolean hasEPawn = false;
    boolean hasCPawn = false;
    boolean hasFPawn = false;

    for (Piece pawn : pawns) {
      int position = pawn.getPiecePosition();
      int file = position % 8;

      switch (file) {
        case 2: hasCPawn = true; break;
        case 3: hasDPawn = true; break;
        case 4: hasEPawn = true; break;
        case 5: hasFPawn = true; break;
      }
    }

    if (hasDPawn && hasEPawn) {
      score += 60;
    } else if (hasDPawn || hasEPawn) {
      score += 30;
    }

    if ((hasDPawn && hasCPawn) || (hasEPawn && hasFPawn)) {
      score += 20;
    }

    return score;
  }

  /**
   * Evaluates doubled pawns which are particularly problematic in the opening.
   * Awards severe penalties for multiple pawns on the same file.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The doubled pawn penalty score.
   */
  private double evaluateDoubledPawns(final List<Piece> pawns) {
    double score = 0;

    int[] pawnsPerFile = new int[8];
    for (Piece pawn : pawns) {
      pawnsPerFile[pawn.getPiecePosition() % 8]++;
    }

    for (int count : pawnsPerFile) {
      if (count > 1) {
        score -= (count - 1) * 35;
      }
    }

    return score;
  }

  /**
   * Evaluates isolated pawns which lack support from adjacent files.
   * Awards higher penalties for isolated center pawns versus wing pawns.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The isolated pawn penalty score.
   */
  private double evaluateIsolatedPawns(final List<Piece> pawns) {
    double score = 0;

    boolean[] filesWithPawns = new boolean[8];
    for (Piece pawn : pawns) {
      filesWithPawns[pawn.getPiecePosition() % 8] = true;
    }

    for (Piece pawn : pawns) {
      int file = pawn.getPiecePosition() % 8;
      boolean isIsolated = file <= 0 || !filesWithPawns[file - 1];

      if (file < 7 && filesWithPawns[file + 1]) {
        isIsolated = false;
      }

      if (isIsolated) {
        if (file == 3 || file == 4) {
          score -= 40;
        } else {
          score -= 25;
        }
      }
    }

    return score;
  }

  /**
   * Evaluates pawn chains which provide mutual protection and structural strength.
   * Awards bonuses for connected pawns on adjacent files.
   *
   * @param pawns The collection of pawns to evaluate.
   * @return The pawn chain bonus score.
   */
  private double evaluatePawnChains(final List<Piece> pawns) {
    double score = 0;

    Map<Integer, List<Piece>> pawnsByFile = new HashMap<>();
    for (Piece pawn : pawns) {
      int file = pawn.getPiecePosition() % 8;
      if (!pawnsByFile.containsKey(file)) {
        pawnsByFile.put(file, new ArrayList<>());
      }
      pawnsByFile.get(file).add(pawn);
    }

    int chainLength = 0;
    for (int file = 0; file < 7; file++) {
      if (pawnsByFile.containsKey(file) && pawnsByFile.containsKey(file + 1)) {
        chainLength++;
      } else if (chainLength > 0) {
        score += 10 * chainLength;
        chainLength = 0;
      }
    }

    if (chainLength > 0) {
      score += 10 * chainLength;
    }

    return score;
  }

  /**
   * Evaluates pawn advances and penalizes excessive early advances.
   * Encourages conservative pawn play except for central files in the opening.
   *
   * @param pawns The collection of pawns to evaluate.
   * @param alliance The alliance of the pawns being evaluated.
   * @return The pawn advance evaluation score.
   */
  private double evaluatePawnAdvances(final List<Piece> pawns, final Alliance alliance) {
    double score = 0;

    for (Piece pawn : pawns) {
      int position = pawn.getPiecePosition();
      int rank = position / 8;
      int file = position % 8;

      int advanceLevel = alliance.isWhite() ?
              (6 - rank) : (rank - 1);

      if (advanceLevel > 2) {
        if (file == 3 || file == 4) {
          if (advanceLevel > 3) {
            score -= (advanceLevel - 3) * 15;
          }
        } else {
          score -= (advanceLevel - 2) * 20;
        }
      }

      if ((file == 0 || file == 7) && advanceLevel > 0) {
        score -= 15;
      }

      if (advanceLevel > 0 &&
              ((alliance.isWhite() && rank <= 6 && (file >= 5 || file <= 2)) ||
                      (!alliance.isWhite() && rank >= 1 && (file >= 5 || file <= 2)))) {
        score -= 15;
      }
    }

    return score;
  }

  /**
   * Evaluates piece mobility for the given player.
   * Counts the player's legal moves and adds a further weighting for the moves available to each
   * knight and bishop.
   *
   * @param player The player whose mobility is being evaluated.
   * @param board The current chess board state.
   * @return The mobility evaluation score.
   */
  private double mobilityScore(final Player player, final Board board) {
    double score = 0;
    Collection<Move> playerMoves = player.getLegalMoves();

    score += playerMoves.size();

    for (Piece piece : player.getActivePieces()) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        Collection<Move> knightMoves = piece.calculateLegalMoves(board);
        score += knightMoves.size() * 1.5;
      } else if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        Collection<Move> bishopMoves = piece.calculateLegalMoves(board);
        score += bishopMoves.size() * 1.5;
      }
    }

    return score;
  }

  /**
   * Evaluates piece coordination and harmony in opening positions.
   * Scores each defended queen, rook, bishop and knight in proportion to the number of pieces
   * defending it, up to a per-piece-type cap, and adds a fixed bonus for a defended bishop or
   * knight. Kings and pawns are not scored. Also scores the player's development patterns.
   *
   * @param player The player whose piece coordination is being evaluated.
   * @param board The current chess board state.
   * @param defenderCounts The per-square defender counts for the player's pieces.
   * @return The piece coordination evaluation score.
   */
  private double pieceCoordinationScore(final Player player,
                                        final Board board,
                                        final int[] defenderCounts) {
    double score = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();

    for (final Piece piece : playerPieces) {
      final int defenderCount = defenderCounts[piece.getPiecePosition()];
      if (defenderCount == 0) continue;

      switch (piece.getPieceType()) {
        case QUEEN -> score += Math.min(defenderCount * 15, 45);
        case ROOK -> score += Math.min(defenderCount * 10, 30);
        case BISHOP, KNIGHT -> score += 15 + Math.min(defenderCount * 5, 15);
        default -> { }
      }
    }

    score += evaluateDevelopmentPatterns(player, board);

    return score;
  }

  /**
   * Evaluates specific good development patterns common in opening theory.
   * Rewards fianchetto formations, connected rooks, and penalizes poorly placed knights.
   *
   * @param player The player whose development patterns are being evaluated.
   * @param board The current chess board state.
   * @return The development pattern evaluation score.
   */
  private double evaluateDevelopmentPatterns(Player player, Board board) {
    double score = 0;
    Collection<Piece> pieces = player.getActivePieces();
    Alliance alliance = player.getAlliance();

    boolean kingsideFianchetto = false;
    boolean queensideFianchetto = false;

    for (Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.BISHOP) {
        int position = piece.getPiecePosition();

        if (alliance.isWhite()) {
          if (position == 49) queensideFianchetto = true;
          if (position == 54) kingsideFianchetto = true;
        } else {
          if (position == 9) queensideFianchetto = true;
          if (position == 14) kingsideFianchetto = true;
        }
      }
    }

    if (kingsideFianchetto) score += 25;
    if (queensideFianchetto) score += 20;

    boolean rooksConnected = areRooksConnected(pieces);
    if (rooksConnected) score += 30;

    for (Piece piece : pieces) {
      if (piece.getPieceType() == Piece.PieceType.KNIGHT) {
        int position = piece.getPiecePosition();
        int file = position % 8;
        int rank = position / 8;

        if (file == 0 || file == 7 || rank == 0 || rank == 7) {
          score -= 30;
        }
      }
    }

    return score;
  }

  /**
   * Checks if rooks are connected by being placed on the same rank.
   * Connected rooks provide mutual protection and increased activity.
   *
   * @param pieces The collection of pieces to check for rook connections.
   * @return True if two or more rooks are connected on the same rank.
   */
  private boolean areRooksConnected(Collection<Piece> pieces) {
    List<Piece> rooks = pieces.stream()
            .filter(p -> p.getPieceType() == Piece.PieceType.ROOK)
            .toList();

    if (rooks.size() >= 2) {
      int firstRookRank = rooks.get(0).getPiecePosition() / 8;

      for (int i = 1; i < rooks.size(); i++) {
        int rookRank = rooks.get(i).getPiecePosition() / 8;
        if (rookRank == firstRookRank) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Evaluates tempo for the given player. Rewards each attacking move, adds a further bonus for
   * every attack on an undefended piece, and rewards a lead in developed minor pieces.
   *
   * @param player The player whose tempo is being evaluated.
   * @param board The current chess board state.
   * @return The tempo evaluation score.
   */
  private double tempoScore(final Player player, final Board board) {
    double score = 0;
    Collection<Move> playerMoves = player.getLegalMoves();
    Collection<Piece> playerPieces = player.getActivePieces();
    Collection<Piece> opponentPieces = player.getOpponent().getActivePieces();

    int attackingMoves = 0;
    for (Move move : playerMoves) {
      if (move.isAttack()) {
        attackingMoves++;

        Piece attackedPiece = move.getAttackedPiece();
        if (attackedPiece != null &&
                !isPieceDefended(attackedPiece, player.getOpponent(), board)) {
          score += 15;
        }
      }
    }

    score += Math.min(attackingMoves, 10) * 3;

    int developedMinorPieces = countDevelopedMinorPieces(playerPieces, player.getAlliance());
    int opponentDevelopedMinorPieces = countDevelopedMinorPieces(opponentPieces, player.getOpponent().getAlliance());

    if (developedMinorPieces > opponentDevelopedMinorPieces) {
      score += (developedMinorPieces - opponentDevelopedMinorPieces) * 30;
    }

    return score;
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
   * @param defenderCounts The per-square defender counts for the player's pieces.
   * @return The piece safety evaluation score (negative values indicate unsafe pieces).
   */
  private double pieceSafetyScore(final Player player,
                                  final Board board,
                                  final int[] defenderCounts) {
    double largestThreat = 0;
    final Collection<Piece> playerPieces = player.getActivePieces();
    final Collection<Move> opponentMoves = player.getOpponent().getLegalMoves();

    final int[] attackerCounts = new int[BoardUtils.NUM_TILES];
    final int[] pawnAttackerCounts = new int[BoardUtils.NUM_TILES];
    for (final Move move : opponentMoves) {
      final int destination = move.getDestinationCoordinate();
      attackerCounts[destination]++;

      if (move.getMovedPiece().getPieceType() == Piece.PieceType.PAWN) {
        pawnAttackerCounts[destination]++;
      }
    }

    for (final Piece piece : playerPieces) {
      if (piece.getPieceType() == Piece.PieceType.KING) continue;

      final int position = piece.getPiecePosition();
      final int pieceValue = piece.getPieceValue();

      final boolean outnumbered = attackerCounts[position] > defenderCounts[position];
      final boolean harriedByPawn = pawnAttackerCounts[position] > 0
              && pieceValue > Piece.PieceType.PAWN.getPieceValue();

      if (outnumbered || harriedByPawn) {
        largestThreat = Math.max(largestThreat, pieceValue * THREAT_FRACTION);
      }
    }

    return -largestThreat;
  }

  /**
   * Checks if a piece is defended by another piece of the same alliance.
   * Used to determine if attacks target undefended pieces.
   *
   * @param piece The piece to check for defense.
   * @param owner The player who owns the piece.
   * @param board The current chess board state.
   * @return True if the piece is defended by a friendly piece.
   */
  private boolean isPieceDefended(Piece piece, Player owner, Board board) {
    final int position = piece.getPiecePosition();

    for (final Piece defender : owner.getActivePieces()) {
      if (defender.getPiecePosition() != position && defender.defendsSquare(position, board)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Counts the number of developed minor pieces for the given alliance.
   * A piece is considered developed if it has moved from its starting rank.
   *
   * @param pieces The collection of pieces to count.
   * @param alliance The alliance of the pieces being counted.
   * @return The number of developed minor pieces.
   */
  private int countDevelopedMinorPieces(Collection<Piece> pieces, Alliance alliance) {
    int count = 0;

    for (Piece piece : pieces) {
      if ((piece.getPieceType() == Piece.PieceType.KNIGHT ||
              piece.getPieceType() == Piece.PieceType.BISHOP) &&
              !piece.isFirstMove()) {

        int rank = piece.getPiecePosition() / 8;
        if ((alliance.isWhite() && rank != 7) ||
                (!alliance.isWhite() && rank != 0)) {
          count++;
        }
      }
    }

    return count;
  }

  /**
   * Returns a list of pawn pieces belonging to the specified player.
   * Utility method for pawn structure evaluation functions.
   *
   * @param player The player whose pawns are to be retrieved.
   * @return A list of pawn pieces belonging to the player.
   */
  private List<Piece> getPawns(final Player player) {
    return player.getActivePieces().stream()
            .filter(piece -> piece.getPieceType() == Piece.PieceType.PAWN)
            .collect(Collectors.toList());
  }
}