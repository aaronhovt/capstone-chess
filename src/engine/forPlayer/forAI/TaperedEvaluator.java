package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forPiece.Piece;

import java.util.Collection;

/**
 * The TaperedEvaluator class scores a position as a blend of the middlegame and endgame
 * evaluators, weighted by the game phase of that position. The phase counts each knight and
 * bishop as one, each rook as two and each queen as four, over both players, and is capped at
 * {@link #FULL_PHASE}, the count of the starting position. The middlegame score carries a weight
 * of phase / {@link #FULL_PHASE} and the endgame score the remainder, so a position with every
 * piece is scored by the middlegame evaluator alone and a position with only kings and pawns by
 * the endgame evaluator alone. A position whose material the endgame evaluator rules drawn
 * scores zero.
 *
 * @author Aaron Ho
 */
public final class TaperedEvaluator implements BoardEvaluator {

  /** Singleton instance of the TaperedEvaluator. */
  private static final TaperedEvaluator INSTANCE = new TaperedEvaluator();

  /** The phase of the starting position, at and above which only the middlegame score counts. */
  private static final int FULL_PHASE = 24;

  /** The highest phase at which the material on the board can be drawn by material alone. */
  private static final int HIGHEST_DRAWN_PHASE = 2;

  /** Private constructor to prevent instantiation outside of class. */
  private TaperedEvaluator() {}

  /**
   * Returns the singleton instance of TaperedEvaluator.
   *
   * @return The instance of TaperedEvaluator.
   */
  public static TaperedEvaluator get() {
    return INSTANCE;
  }

  /**
   * Evaluates the given board as the phase-weighted blend of the middlegame and endgame scores.
   * Positive values favour white and negative values favour black.
   *
   * @param board The current state of the chess board.
   * @return The evaluation score of the board.
   */
  @Override
  public double evaluate(final Board board) {
    final int phase = phase(board.getWhitePieces()) + phase(board.getBlackPieces());

    if (phase >= FULL_PHASE) {
      return MiddlegameBoardEvaluator.get().evaluate(board);
    }
    if (phase == 0) {
      return EndgameBoardEvaluator.get().evaluate(board);
    }
    if (phase <= HIGHEST_DRAWN_PHASE && EndgameBoardEvaluator.get().isDrawnByMaterial(board)) {
      return 0;
    }

    final LegalMoveTally whiteTally = LegalMoveTally.of(board, board.whitePlayer());
    final LegalMoveTally blackTally = LegalMoveTally.of(board, board.blackPlayer());
    final PawnSpans.Pawns whitePawns = PawnSpans.Pawns.of(board, Alliance.WHITE);
    final PawnSpans.Pawns blackPawns = PawnSpans.Pawns.of(board, Alliance.BLACK);
    return (phase * MiddlegameBoardEvaluator.get().evaluate(board, whiteTally, blackTally,
                    whitePawns, blackPawns) +
            (FULL_PHASE - phase) * EndgameBoardEvaluator.get().evaluate(board, whiteTally,
                    blackTally, whitePawns, blackPawns)) / FULL_PHASE;
  }

  /**
   * Returns the phase contributed by the given pieces: one for each knight and bishop, two for
   * each rook and four for each queen.
   *
   * @param pieces The pieces of one player.
   * @return The phase those pieces contribute.
   */
  private static int phase(final Collection<Piece> pieces) {
    int phase = 0;

    for (final Piece piece : pieces) {
      phase += switch (piece.getPieceType()) {
        case KNIGHT, BISHOP -> 1;
        case ROOK -> 2;
        case QUEEN -> 4;
        case PAWN, KING -> 0;
      };
    }

    return phase;
  }
}
