package engine.forPlayer.forAI;

import engine.forBoard.Board;
import engine.forPiece.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GameStateDetector class provides precise identification of the current chess game phase
 * and selects the appropriate evaluation function based on comprehensive position analysis.
 * This class replaces the TaperedEvaluator with specialized evaluators for opening,
 * middlegame, and endgame positions, enhancing evaluation accuracy.
 *
 * @author Aaron Ho
 */
public class GameStateDetector {

  /** The singleton instance of the GameStateDetector. */
  private static final GameStateDetector INSTANCE = new GameStateDetector();

  /**
   * Enumeration representing the three phases of a chess game.
   */
  public enum GamePhase {
    /** The opening phase of the game. */
    OPENING,

    /** The middlegame phase of the game. */
    MIDDLEGAME,

    /** The endgame phase of the game. */
    ENDGAME
  }

  /**
   * Private constructor to enforce the singleton pattern.
   */
  private GameStateDetector() {}

  /**
   * Returns the singleton instance of the GameStateDetector.
   *
   * @return The singleton instance of GameStateDetector.
   */
  public static GameStateDetector get() {
    return INSTANCE;
  }

  /**
   * Determines the appropriate evaluator for the current board position
   * based on detailed analysis of the game state.
   *
   * @param board The current chess board.
   * @return The appropriate BoardEvaluator for the detected game phase.
   */
  public BoardEvaluator determineEvaluator(final Board board) {
    GamePhase gamePhase = detectGamePhase(board);

    return switch (gamePhase) {
      case OPENING -> OpeningGameEvaluator.get();
      case MIDDLEGAME -> MiddlegameBoardEvaluator.get();
      case ENDGAME -> EndgameBoardEvaluator.get();
      default -> MiddlegameBoardEvaluator.get();
    };
  }

  /**
   * Determines the current phase of the game by analyzing the board position. The phase is
   * recalculated on every call, because it depends on the ply count and on first move and castled
   * status, which the Zobrist hash does not cover.
   *
   * @param board The current chess board.
   * @return The detected game phase.
   */
  public GamePhase detectGamePhase(final Board board) {
    final PieceTally tally = new PieceTally();
    tally.add(board.getWhitePieces());
    tally.add(board.getBlackPieces());

    int materialScore = tally.material;
    int developmentScore = calculateDevelopmentScore(tally, board);
    int moveCount = calculateMoveCount(board);
    int pieceCount = tally.pieceCount;
    int pawnStructureScore = evaluatePawnStructure(tally);
    int kingActivityScore = evaluateKingActivity(board);

    GamePhase phase = determinePhaseFromIndicators(
            materialScore, developmentScore, moveCount,
            pieceCount, pawnStructureScore, kingActivityScore, tally, board);

    return phase;
  }

  /**
   * The piece counts that phase detection reads, gathered in a single pass over each side's
   * pieces.
   */
  private static final class PieceTally {
    /** The number of pieces of both sides, kings and pawns included. */
    int pieceCount;
    /** The combined value of every piece that is neither a pawn nor a king. */
    int material;
    /** The number of pieces that are neither pawns nor kings. */
    int nonPawnPieceCount;
    /** The number of knights and bishops that have moved and stand off their starting rank. */
    int developedMinorPieces;
    /** The number of knights and bishops that have not moved. */
    int undevelopedMinorPieces;
    /** The number of pawns. */
    int pawnCount;
    /** The number of pawns on their side's sixth rank or beyond. */
    int advancedPawnCount;
    /** Whether white has a queen. */
    boolean whiteQueenPresent;
    /** Whether black has a queen. */
    boolean blackQueenPresent;

    /**
     * Adds the given pieces to this tally.
     *
     * @param pieces The pieces to count.
     */
    void add(final Collection<Piece> pieces) {
      for (final Piece piece : pieces) {
        this.pieceCount++;
        final Piece.PieceType pieceType = piece.getPieceType();
        final boolean isWhite = piece.getPieceAllegiance().isWhite();
        final int rank = piece.getPiecePosition() / 8;
        switch (pieceType) {
          case PAWN -> {
            this.pawnCount++;
            if ((isWhite && rank <= 2) || (!isWhite && rank >= 5)) {
              this.advancedPawnCount++;
            }
          }
          case KING -> {
          }
          default -> {
            this.material += piece.getPieceValue();
            this.nonPawnPieceCount++;
            if (pieceType == Piece.PieceType.QUEEN) {
              if (isWhite) {
                this.whiteQueenPresent = true;
              } else {
                this.blackQueenPresent = true;
              }
            } else if (pieceType == Piece.PieceType.KNIGHT
                    || pieceType == Piece.PieceType.BISHOP) {
              if (piece.isFirstMove()) {
                this.undevelopedMinorPieces++;
              } else if ((isWhite && rank != 7) || (!isWhite && rank != 0)) {
                this.developedMinorPieces++;
              }
            }
          }
        }
      }
    }
  }

  /**
   * Calculates a development score for phase detection.
   * Higher scores indicate more development which suggests middlegame.
   *
   * @param tally The piece counts of the current board.
   * @param board The current chess board.
   * @return A development score for phase detection.
   */
  private int calculateDevelopmentScore(final PieceTally tally, final Board board) {
    int developmentScore = tally.developedMinorPieces * 10;

    if (board.whitePlayer().isCastled()) {
      developmentScore += 20;
    }
    if (board.blackPlayer().isCastled()) {
      developmentScore += 20;
    }

    return developmentScore;
  }

  /**
   * Returns the number of plies played on this board since it was constructed.
   *
   * @param board The current chess board.
   * @return The number of plies played so far.
   */
  private int calculateMoveCount(final Board board) {
    return board.getPlyCount();
  }

  /**
   * Evaluates pawn structure to help determine game phase.
   *
   * @param tally The piece counts of the current board.
   * @return A pawn structure score for phase detection.
   */
  private int evaluatePawnStructure(final PieceTally tally) {
    return tally.advancedPawnCount * 10 + (16 - tally.pawnCount) * 5;
  }

  /**
   * Evaluates king activity to help determine game phase.
   * More active kings indicate endgame.
   *
   * @param board The current chess board.
   * @return A king activity score for phase detection.
   */
  private int evaluateKingActivity(final Board board) {
    int score = 0;

    King whiteKing = board.whitePlayer().getPlayerKing();
    King blackKing = board.blackPlayer().getPlayerKing();

    score += calculateKingCentralization(whiteKing.getPiecePosition());
    score += calculateKingCentralization(blackKing.getPiecePosition());

    if (!isKingOnBackRank(whiteKing)) {
      score += 30;
    }

    if (!isKingOnBackRank(blackKing)) {
      score += 30;
    }

    return score;
  }

  /**
   * Calculates a king centralization score.
   * Higher values indicate more central kings which is typical in endgame.
   *
   * @param kingPosition The position of the king.
   * @return A centralization score.
   */
  private int calculateKingCentralization(final int kingPosition) {
    final int file = kingPosition % 8;
    final int rank = kingPosition / 8;

    final int fileDistance = Math.min(file, 7 - file);
    final int rankDistance = Math.min(rank, 7 - rank);

    return (fileDistance + rankDistance) * 5;
  }

  /**
   * Checks if a king is on its original back rank.
   *
   * @param king The king to check.
   * @return True if the king is on its back rank, false otherwise.
   */
  private boolean isKingOnBackRank(final King king) {
    final int kingRank = king.getPiecePosition() / 8;
    return (king.getPieceAllegiance().isWhite() && kingRank == 7) ||
            (!king.getPieceAllegiance().isWhite() && kingRank == 0);
  }

  /**
   * Determines the game phase based on all calculated indicators.
   * Uses a sophisticated weighted approach to handle edge cases.
   *
   * @param materialScore Material-based score.
   * @param developmentScore Development-based score.
   * @param moveCount Approximate number of moves made.
   * @param pieceCount Total pieces remaining.
   * @param pawnStructureScore Pawn structure evaluation.
   * @param kingActivityScore King activity evaluation.
   * @param tally The piece counts of the current board.
   * @param board The current chess board for additional checks.
   * @return The determined game phase.
   */
  private GamePhase determinePhaseFromIndicators(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, int pawnStructureScore, int kingActivityScore,
          PieceTally tally, Board board) {

    int openingScore = calculateOpeningScore(
            materialScore, developmentScore, moveCount, pieceCount, tally, board);

    int middlegameScore = calculateMiddlegameScore(
            materialScore, developmentScore, moveCount, pieceCount, tally, board);

    int endgameScore = calculateEndgameScore(
            materialScore, moveCount, pieceCount,
            pawnStructureScore, kingActivityScore, tally);

    if (endgameScore > middlegameScore && endgameScore > openingScore) {
      return GamePhase.ENDGAME;
    } else if (middlegameScore > openingScore) {
      return GamePhase.MIDDLEGAME;
    } else {
      return GamePhase.OPENING;
    }
  }

  /**
   * Calculates a score indicating how much the position resembles an opening.
   *
   * @param materialScore The material score.
   * @param developmentScore The development score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param tally The piece counts of the current board.
   * @param board The current chess board.
   * @return A score indicating opening characteristics.
   */
  private int calculateOpeningScore(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, PieceTally tally, Board board) {

    int score = 0;

    if (materialScore > 7000) {
      score += 50;
    } else if (materialScore > 6000) {
      score += 30;
    }

    if (developmentScore < 40) {
      score += 40;
    } else if (developmentScore < 80) {
      score += 20;
    }

    if (moveCount < 10) {
      score += 50;
    } else if (moveCount < 20) {
      score += 30;
    }

    if (pieceCount >= 30) {
      score += 40;
    } else if (pieceCount >= 26) {
      score += 20;
    }

    score += tally.undevelopedMinorPieces * 5;

    if (isKingOnBackRank(board.whitePlayer().getPlayerKing()) &&
            isKingOnBackRank(board.blackPlayer().getPlayerKing())) {
      score += 30;
    }

    return score;
  }

  /**
   * Calculates a score indicating how much the position resembles a middlegame.
   *
   * @param materialScore The material score.
   * @param developmentScore The development score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param tally The piece counts of the current board.
   * @param board The current chess board.
   * @return A score indicating middlegame characteristics.
   */
  private int calculateMiddlegameScore(
          int materialScore, int developmentScore, int moveCount,
          int pieceCount, PieceTally tally, Board board) {

    int score = 0;

    if (materialScore >= 4000 && materialScore <= 7000) {
      score += 40;
    }

    if (developmentScore >= 80 && developmentScore <= 120) {
      score += 40;
    } else if (developmentScore > 40) {
      score += 20;
    }

    if (moveCount >= 15 && moveCount <= 40) {
      score += 40;
    } else if (moveCount > 10) {
      score += 20;
    }

    if (pieceCount >= 20 && pieceCount < 30) {
      score += 40;
    } else if (pieceCount >= 16) {
      score += 20;
    }

    if (tally.whiteQueenPresent && tally.blackQueenPresent) {
      score += 40;
    } else if (tally.whiteQueenPresent || tally.blackQueenPresent) {
      score += 20;
    }

    if (board.whitePlayer().isCastled() || board.blackPlayer().isCastled()) {
      score += 20;
    }

    return score;
  }

  /**
   * Calculates a score indicating how much the position resembles an endgame.
   *
   * @param materialScore The material score.
   * @param moveCount The number of moves made.
   * @param pieceCount The total piece count.
   * @param pawnStructureScore The pawn structure score.
   * @param kingActivityScore The king activity score.
   * @param tally The piece counts of the current board.
   * @return A score indicating endgame characteristics.
   */
  private int calculateEndgameScore(
          int materialScore, int moveCount, int pieceCount,
          int pawnStructureScore, int kingActivityScore, PieceTally tally) {

    int score = 0;

    if (materialScore < 3000) {
      score += 60;
    } else if (materialScore < 4000) {
      score += 40;
    }

    if (moveCount > 40) {
      score += 30;
    } else if (moveCount > 30) {
      score += 15;
    }

    if (pieceCount < 16) {
      score += 60;
    } else if (pieceCount < 20) {
      score += 30;
    }

    score += pawnStructureScore;
    score += kingActivityScore;

    if (!tally.whiteQueenPresent && !tally.blackQueenPresent) {
      score += 50;
    }

    if (tally.advancedPawnCount > 0) {
      score += 30;
    }

    if (tally.nonPawnPieceCount <= 6) {
      score += 40;
    } else if (tally.nonPawnPieceCount <= 10) {
      score += 20;
    }

    return score;
  }
}