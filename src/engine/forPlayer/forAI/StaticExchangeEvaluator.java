package engine.forPlayer.forAI;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forBoard.MoveCode;
import engine.forPiece.Piece;

/**
 * The StaticExchangeEvaluator class implements Static Exchange Evaluation (SEE) for accurate assessment
 * of capture sequences in chess positions. SEE recursively evaluates the best possible outcome of a
 * capture sequence for each side, providing critical information for move ordering and pruning decisions
 * in search algorithms. This evaluation helps distinguish between profitable and unprofitable exchanges.
 * <p>
 * The class follows the singleton pattern to ensure consistent evaluation across the chess engine.
 * It uses simplified piece values optimized for exchange calculations and considers factors such as
 * piece defense and attack sequences to determine the material outcome of captures.
 * <p>
 * The attackers are read from the board's bitboards through {@link AttackDetector}, which
 * disregards whatever occupies the target square, so pieces defending it are found as well as
 * pieces that could capture on it.
 *
 * @author Aaron Ho
 */
public class StaticExchangeEvaluator {

  /** The singleton instance of the StaticExchangeEvaluator for global access. */
  private static final StaticExchangeEvaluator INSTANCE = new StaticExchangeEvaluator();

  /**
   * Simplified piece values used for SEE calculations, indexed by piece type ordinal.
   * Values are optimized for exchange evaluation rather than full position assessment.
   */
  private static final int[] SEE_PIECE_VALUES = {
          100,
          320,
          330,
          500,
          900,
          20000
  };

  /** The piece types in ascending order of SEE value. */
  private static final Piece.PieceType[] TYPES_BY_VALUE = Piece.PieceType.values();

  /** The most pieces a board can hold, which bounds the length of an exchange sequence. */
  private static final int MAX_PIECES = 32;

  /**
   * The order in which the queen lines out of a square are searched for attackers, by the tile
   * offset of their first step.
   */
  private static final int[] LINE_OFFSETS = { -9, -8, -7, -1, 1, 7, 8, 9 };

  /** The list position given to a knight ahead of its square, placing knights after every line. */
  private static final int KNIGHT_POSITIONS = LINE_OFFSETS.length;

  /**
   * Constructs a new StaticExchangeEvaluator instance.
   * Private constructor enforces the singleton pattern.
   */
  private StaticExchangeEvaluator() {}

  /**
   * Returns the singleton instance of the StaticExchangeEvaluator.
   *
   * @return The singleton StaticExchangeEvaluator instance.
   */
  public static StaticExchangeEvaluator get() {
    return INSTANCE;
  }

  /**
   * Evaluates the material gain or loss from a capture move using static exchange evaluation.
   * The method simulates the optimal capture sequence for both sides and returns the expected
   * material outcome. Non-capture moves return a value of zero.
   *
   * @param board The current chess board state.
   * @param move The capture move to evaluate.
   * @return The estimated material gain or loss, positive values favor the side making the initial capture.
   */
  public int evaluate(final Board board, final Move move) {
    if (!move.isAttack()) {
      return 0;
    }

    final int targetSquare = move.getDestinationCoordinate();
    final Piece attackingPiece = move.getMovedPiece();
    final int attackerValue = getPieceValue(attackingPiece.getPieceType());
    final Piece capturedPiece = move.getAttackedPiece();
    final int capturedValue = getPieceValue(capturedPiece.getPieceType());

    if (move instanceof Move.PawnEnPassantAttack) {
      return SEE_PIECE_VALUES[0];
    }

    return swapOffValue(board, targetSquare, capturedValue, attackerValue,
            attackingPiece.getPiecePosition(), board.currentPlayer().getOpponent().getAlliance());
  }

  /**
   * Evaluates the material gain or loss from a packed capture move, giving the value
   * {@link #evaluate(Board, Move)} gives for the equal move. Non-capture moves return zero.
   *
   * @param board The current chess board state.
   * @param move The packed capture move to evaluate, for the side to move.
   * @return The estimated material gain or loss, positive values favor the side making the initial capture.
   */
  public int evaluate(final Board board, final int move) {
    if (!MoveCode.isCapture(move)) {
      return 0;
    }
    if (MoveCode.isEnPassant(move)) {
      return SEE_PIECE_VALUES[0];
    }

    final int origin = MoveCode.origin(move);
    final int targetSquare = MoveCode.destination(move);
    final int attackerValue = getPieceValue(board.getPiece(origin).getPieceType());
    final int capturedValue = getPieceValue(board.getPiece(targetSquare).getPieceType());
    return swapOffValue(board, targetSquare, capturedValue, attackerValue, origin,
            board.currentPlayer().getOpponent().getAlliance());
  }

  /**
   * Computes the material a capture sequence leaves the side making the first capture with,
   * from that side's perspective. Each side captures with its least valuable remaining attacker,
   * and a side that would lose material by continuing the sequence stops instead. A slider
   * standing behind a capturing piece on its line joins the sequence once that piece has captured.
   * Of two attackers of the same type, the one found first captures first: the attackers present
   * at the start in the order {@link #listPosition} gives, then the revealed sliders in the order
   * they were revealed.
   *
   * @param board The current chess board state.
   * @param targetSquare The square the exchange takes place on.
   * @param capturedValue The value of the piece taken by the first capture.
   * @param attackerValue The value of the piece making the first capture.
   * @param firstAttackerSquare The square of the piece making the first capture.
   * @param defendingSide The alliance that recaptures first.
   * @return The material outcome of the sequence for the side making the first capture.
   */
  private int swapOffValue(final Board board, final int targetSquare,
                           final int capturedValue, final int attackerValue,
                           final int firstAttackerSquare, final Alliance defendingSide) {
    final int[] gain = new int[MAX_PIECES];
    final int[] revealed = new int[MAX_PIECES];
    final long initialAttackers = AttackDetector.attackersOf(targetSquare, board);
    long available = initialAttackers & ~(1L << firstAttackerSquare);
    int revealedCount = 0;

    final int firstRevealed = revealedBehind(board, targetSquare, firstAttackerSquare);
    if (firstRevealed >= 0) {
      available |= 1L << firstRevealed;
      revealed[revealedCount++] = firstRevealed;
    }

    gain[0] = capturedValue;
    int depth = 0;
    int movedValue = attackerValue;
    Alliance side = defendingSide;

    while (true) {
      depth++;
      gain[depth] = movedValue - gain[depth - 1];

      final int attackerSquare = leastValuableAttacker(board, targetSquare, available,
              initialAttackers, revealed, revealedCount, side);
      if (attackerSquare < 0) {
        break;
      }

      available &= ~(1L << attackerSquare);
      final int revealedSquare = revealedBehind(board, targetSquare, attackerSquare);
      if (revealedSquare >= 0) {
        available |= 1L << revealedSquare;
        revealed[revealedCount++] = revealedSquare;
      }
      movedValue = getPieceValue(board.getPiece(attackerSquare).getPieceType());
      side = side.isWhite() ? Alliance.BLACK : Alliance.WHITE;
    }

    while (depth > 1) {
      depth--;
      gain[depth - 1] = -Math.max(-gain[depth - 1], gain[depth]);
    }

    return gain[0];
  }

  /**
   * Returns the slider that bears on the target square once the piece on the vacated square has
   * captured, if there is one. The vacated square must hold the nearest piece to the target
   * square on its queen line. The next piece beyond it on that line qualifies when it is a queen,
   * a rook on a rank or file, or a bishop on a diagonal.
   *
   * @param board The current chess board state.
   * @param targetSquare The square the exchange takes place on.
   * @param vacatedSquare The square of the piece that has just captured.
   * @return The square of the revealed slider, or -1 if none is revealed.
   */
  private int revealedBehind(final Board board, final int targetSquare, final int vacatedSquare) {
    final int beyond = AttackDetector.nearestBeyond(targetSquare, vacatedSquare, board.getOccupancy());
    if (beyond < 0) {
      return -1;
    }
    final Piece.PieceType type = board.getPiece(beyond).getPieceType();
    final boolean orthogonal = AttackDetector.onOrthogonalLine(targetSquare, vacatedSquare);
    return type == Piece.PieceType.QUEEN ||
            type == (orthogonal ? Piece.PieceType.ROOK : Piece.PieceType.BISHOP) ? beyond : -1;
  }

  /**
   * Finds the least valuable attacker of the given alliance still available. Of several of the
   * same type, the one found first is returned.
   *
   * @param board The current chess board state.
   * @param targetSquare The square the exchange takes place on.
   * @param available The squares of the attackers still available, as one bit per tile.
   * @param initialAttackers The squares of the attackers present at the start, as one bit per tile.
   * @param revealed The squares of the revealed sliders, in the order they were revealed.
   * @param revealedCount The number of entries of revealed in use.
   * @param side The alliance of the attacking side.
   * @return The square of the least valuable available attacker of that alliance, or -1 if there
   *         is none.
   */
  private int leastValuableAttacker(final Board board, final int targetSquare,
                                    final long available, final long initialAttackers,
                                    final int[] revealed, final int revealedCount,
                                    final Alliance side) {
    for (final Piece.PieceType type : TYPES_BY_VALUE) {
      final long candidates = available & board.getPieces(side, type);
      if (candidates == 0L) {
        continue;
      }
      if ((candidates & (candidates - 1)) == 0L) {
        return Long.numberOfTrailingZeros(candidates);
      }
      final long initialCandidates = candidates & initialAttackers;
      if (initialCandidates != 0L) {
        int first = -1;
        int firstPosition = Integer.MAX_VALUE;
        for (long remaining = initialCandidates; remaining != 0L; remaining &= remaining - 1) {
          final int square = Long.numberOfTrailingZeros(remaining);
          final int position = listPosition(targetSquare, square, type);
          if (position < firstPosition) {
            firstPosition = position;
            first = square;
          }
        }
        return first;
      }
      for (int index = 0; index < revealedCount; index++) {
        if ((candidates & (1L << revealed[index])) != 0L) {
          return revealed[index];
        }
      }
    }
    return -1;
  }

  /**
   * Returns the position an attacker present at the start of an exchange takes in the order the
   * attackers are found: a piece on a queen line from the target square by the position of that
   * line in {@link #LINE_OFFSETS}, and a knight after every line, by its square.
   *
   * @param targetSquare The square the exchange takes place on.
   * @param square The square of the attacker.
   * @param type The type of the attacker.
   * @return The attacker's position in the order.
   */
  private static int listPosition(final int targetSquare, final int square, final Piece.PieceType type) {
    if (type == Piece.PieceType.KNIGHT) {
      return KNIGHT_POSITIONS + square;
    }
    final int offset = 8 * Integer.signum(square / 8 - targetSquare / 8) +
            Integer.signum(square % 8 - targetSquare % 8);
    for (int position = 0; position < LINE_OFFSETS.length; position++) {
      if (LINE_OFFSETS[position] == offset) {
        return position;
      }
    }
    throw new IllegalStateException("Square " + square + " is not on a queen line from " + targetSquare + ".");
  }

  /**
   * Returns the SEE-specific value for a given piece type.
   * These values are optimized for exchange evaluation calculations.
   *
   * @param pieceType The type of piece to evaluate.
   * @return The SEE value for the piece type.
   */
  private int getPieceValue(Piece.PieceType pieceType) {
    return switch (pieceType) {
      case PAWN -> SEE_PIECE_VALUES[0];
      case KNIGHT -> SEE_PIECE_VALUES[1];
      case BISHOP -> SEE_PIECE_VALUES[2];
      case ROOK -> SEE_PIECE_VALUES[3];
      case QUEEN -> SEE_PIECE_VALUES[4];
      case KING -> SEE_PIECE_VALUES[5];
    };
  }
}
