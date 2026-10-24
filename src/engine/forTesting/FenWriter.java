package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.ZobristHashing;
import engine.forPiece.Pawn;
import engine.forPiece.Piece;

/**
 * The FenWriter class converts Board instances into Forsyth-Edwards Notation strings, the inverse
 * of {@link FenParser}. It writes all six fields: piece placement, side to move, castling rights,
 * en passant target, halfmove clock, and fullmove number.
 * <p>
 * A castling right is written when {@link ZobristHashing#canCastle} reports it held. The en passant
 * target is written whenever the board holds an en passant pawn, which is after every pawn double
 * step whether or not a capture is available. The fullmove number is derived from the board's ply
 * count. This class is designed as a non-instantiable utility class with static methods.
 *
 * @author Aaron Ho
 */
public class FenWriter {

  /** The number of ranks described by the piece placement field of a Forsyth-Edwards Notation string. */
  private static final int NUM_RANKS = 8;

  /** The number of files described by each rank of a Forsyth-Edwards Notation string. */
  private static final int NUM_FILES = 8;

  /** The number of plies in one full move, used to convert a ply count into a fullmove number. */
  private static final int PLIES_PER_FULL_MOVE = 2;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private FenWriter() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Returns the Forsyth-Edwards Notation string describing the given board.
   *
   * @param board The board to describe.
   * @return The notation string, with all six fields.
   */
  public static String write(final Board board) {
    final boolean whiteToMove = board.currentPlayer().getAlliance().isWhite();
    return placementOf(board) + ' ' + (whiteToMove ? 'w' : 'b') + ' ' + castlingRightsOf(board) +
            ' ' + enPassantTargetOf(board) + ' ' + board.getHalfMoveClock() + ' ' +
            fullMoveNumberOf(board, whiteToMove);
  }

  /**
   * Writes the piece placement field, listing the ranks from the eighth to the first, which matches
   * the coordinate ordering used by the engine, where coordinate zero is a8.
   *
   * @param board The board to describe.
   * @return The piece placement field.
   */
  private static String placementOf(final Board board) {
    final StringBuilder placement = new StringBuilder();
    for (int rank = 0; rank < NUM_RANKS; rank++) {
      if (rank > 0) {
        placement.append('/');
      }
      int emptySquares = 0;
      for (int file = 0; file < NUM_FILES; file++) {
        final Piece piece = board.getPiece((rank * NUM_FILES) + file);
        if (piece == null) {
          emptySquares++;
          continue;
        }
        if (emptySquares > 0) {
          placement.append(emptySquares);
          emptySquares = 0;
        }
        final String symbol = piece.getPieceType().toString();
        placement.append(piece.getPieceAllegiance().isWhite() ? symbol : symbol.toLowerCase());
      }
      if (emptySquares > 0) {
        placement.append(emptySquares);
      }
    }
    return placement.toString();
  }

  /**
   * Writes the castling rights field.
   *
   * @param board The board to describe.
   * @return The rights held, in the order KQkq, or "-" if neither side holds any.
   */
  private static String castlingRightsOf(final Board board) {
    final StringBuilder rights = new StringBuilder();
    if (ZobristHashing.canCastle(board, true, true)) {
      rights.append('K');
    }
    if (ZobristHashing.canCastle(board, true, false)) {
      rights.append('Q');
    }
    if (ZobristHashing.canCastle(board, false, true)) {
      rights.append('k');
    }
    if (ZobristHashing.canCastle(board, false, false)) {
      rights.append('q');
    }
    return rights.isEmpty() ? "-" : rights.toString();
  }

  /**
   * Writes the en passant target field: the square the en passant pawn passed over on its double
   * step.
   *
   * @param board The board to describe.
   * @return The target square in algebraic notation, or "-" if the board holds no en passant pawn.
   */
  private static String enPassantTargetOf(final Board board) {
    final Pawn enPassantPawn = board.getEnPassantPawn();
    if (enPassantPawn == null) {
      return "-";
    }
    final int pawnCoordinate = enPassantPawn.getPiecePosition();
    return BoardUtils.squareName(enPassantPawn.getPieceAllegiance().isWhite() ?
            pawnCoordinate + NUM_FILES : pawnCoordinate - NUM_FILES);
  }

  /**
   * Writes the fullmove number field, which starts at one and grows after each Black move.
   *
   * @param board The board to describe.
   * @param whiteToMove True if White is to move.
   * @return The fullmove number, at least one.
   */
  private static int fullMoveNumberOf(final Board board, final boolean whiteToMove) {
    final int fullMovePlies = board.getPlyCount() - (whiteToMove ? 0 : 1);
    return Math.max(1, (fullMovePlies / PLIES_PER_FULL_MOVE) + 1);
  }
}
