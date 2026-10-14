package engine.forTesting;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forBoard.ZobristHashing;
import engine.forPiece.Pawn;
import engine.forPiece.Piece;

import java.util.Map;
import java.util.TreeMap;

/**
 * The Perft class counts the leaf nodes reachable from a position within a fixed number of plies.
 * A performance test, universally abbreviated to perft, walks the full legal move tree to a given
 * depth and counts only the positions at that depth. Comparing those counts against published
 * reference values proves whether move generation, move execution, and the special rules for
 * castling, en passant, and promotion are implemented correctly, since a single missing or spurious
 * move changes the count in a way that no amount of testing by inspection reliably reveals.
 * <p>
 * The class also provides a divide operation, which reports the node count contributed by each move
 * available at the root. Divide is the standard debugging tool for a failing perft: comparing the
 * per move subtotals against a reference engine identifies which branch contains the fault, and
 * repeating the process inside that branch narrows the fault to a single position. A Zobrist
 * consistency walk is provided alongside these, since the incrementally updated hash carried by each
 * board can be checked against a hash recalculated from scratch at every node of the same tree.
 * <p>
 * Every walk here mutates a single board in place through {@link Board#makeMove(Move)} and
 * {@link Board#unmakeMove()} rather than building a new board per move, which is the only way a
 * move is applied anywhere in the engine. A node count that matches a published reference value is
 * therefore also evidence that the unmake exactly reverses the make, since an imperfect reversal
 * leaves a corrupted position behind for every sibling move that follows it and the count diverges.
 * This class is designed as a non-instantiable utility class with static methods.
 *
 * @author Aaron Ho
 */
public class Perft {

  /** The long algebraic notation used for a move that has no origin square, matching the null move convention. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private Perft() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Counts the leaf nodes reachable from the given position at exactly the given depth.
   * Positions at intermediate depths are not counted, and terminal positions reached before the
   * requested depth contribute nothing, which matches the convention used by published reference
   * counts. A depth of zero counts the given position itself as a single node.
   * <p>
   * Legality is established by applying each generated move and rejecting it if it leaves the
   * player who just moved in check, because the engine generates moves pseudo-legally. The walk
   * mutates a single board in place through {@link Board#makeMove(Move)} and
   * {@link Board#unmakeMove()} rather than building a new board per move, and the given board is
   * restored to its original state before this method returns, whether it completes normally or
   * throws, since every unmake happens in a finally block.
   *
   * @param board The position from which to count. Mutated during the walk and restored before
   *              this method returns.
   * @param depth The number of plies to walk before counting.
   * @return The number of leaf nodes at the requested depth.
   */
  public static long perft(final Board board, final int depth) {
    if (depth <= 0) {
      return 1L;
    }
    long nodes = 0L;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      board.makeMove(move);
      try {
        if (!board.currentPlayer().getOpponent().isInCheck()) {
          nodes += depth == 1 ? 1L : perft(board, depth - 1);
        }
      } finally {
        board.unmakeMove();
      }
    }
    return nodes;
  }

  /**
   * Counts the leaf nodes contributed by each legal move available in the given position.
   * The subtotals sum to the perft value of the same position at the same depth, so comparing
   * this breakdown against a reference engine identifies the branch responsible for a discrepancy.
   * Moves are keyed by long algebraic notation and ordered by that notation for readable output.
   *
   * @param board The position whose root moves are to be broken down. Mutated during the walk and
   *              restored before this method returns.
   * @param depth The number of plies to walk before counting, which must be at least one.
   * @return An ordered map of long algebraic notation to the node count contributed by that move.
   */
  public static Map<String, Long> divide(final Board board, final int depth) {
    final Map<String, Long> subtotals = new TreeMap<>();
    if (depth <= 0) {
      return subtotals;
    }
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      board.makeMove(move);
      try {
        if (board.currentPlayer().getOpponent().isInCheck()) {
          continue;
        }
        final long nodes = depth == 1 ? 1L : perft(board, depth - 1);
        subtotals.merge(longAlgebraicNotation(move, board), nodes, Long::sum);
      } finally {
        board.unmakeMove();
      }
    }
    return subtotals;
  }

  /**
   * Counts the legal moves available in the given position, which is the perft value at depth one.
   * This is provided separately because a wrong move count in the position under examination is the
   * most common cause of a failing perft, and reading it directly avoids a recursive walk.
   *
   * @param board The position whose legal moves are to be counted. Mutated during the count and
   *              restored before this method returns.
   * @return The number of legal moves available to the player about to move.
   */
  public static int countLegalMoves(final Board board) {
    int legalMoves = 0;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      if (board.isLegal(move)) {
        legalMoves++;
      }
    }
    return legalMoves;
  }

  /**
   * Walks the legal move tree and returns the first position whose incrementally updated Zobrist
   * hash disagrees with a hash recalculated from scratch. The two values must agree at every node,
   * because the transposition table treats equal hashes as equal positions, and an incremental
   * update that drifts from the true hash silently corrupts every table lookup that follows.
   *
   * The divergent position is returned as a copy rather than as the walked board itself, because
   * the walk mutates one board in place and unwinds it on the way out, so the board handed back to
   * the caller would otherwise have been restored to the root by the time it was read.
   *
   * @param board The position from which to begin the walk. Mutated during the walk and restored
   *              before this method returns.
   * @param depth The number of plies to walk.
   * @return A copy of the first position whose hash is inconsistent, or null if every position
   *         agrees.
   */
  public static Board findZobristDivergence(final Board board, final int depth) {
    if (board.getZobristHash() != ZobristHashing.calculateBoardHash(board)) {
      return board.copy();
    }
    if (depth <= 0) {
      return null;
    }
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      board.makeMove(move);
      try {
        if (board.currentPlayer().getOpponent().isInCheck()) {
          continue;
        }
        final Board divergentBoard = findZobristDivergence(board, depth - 1);
        if (divergentBoard != null) {
          return divergentBoard;
        }
      } finally {
        board.unmakeMove();
      }
    }
    return null;
  }

  /**
   * Walks the legal move tree and returns a description of the first position at which either
   * {@link Board#copy()} or a {@link Board#makeNullMove()} and {@link Board#unmakeNullMove()}
   * round trip fails to reproduce the position it was given, or at which the board's bitboards
   * disagree with its pieces, checked on arriving at each position and again after its moves
   * have been unmade. Nothing in the engine calls either
   * of those yet, so this rather than a node count is what validates them.
   *
   * @param board The position from which to begin the walk. Mutated during the walk and restored
   *              before this method returns.
   * @param depth The number of plies to walk.
   * @return A description of the first fault found, or null if every position checks out.
   */
  public static String findMutationDivergence(final Board board, final int depth) {
    final String bitboardFault = describeBitboardFault(board);
    if (bitboardFault != null) {
      return bitboardFault + System.lineSeparator() + board;
    }
    final String copyFault = describeCopyFault(board);
    if (copyFault != null) {
      return copyFault + System.lineSeparator() + board;
    }
    final String nullMoveFault = describeNullMoveFault(board);
    if (nullMoveFault != null) {
      return nullMoveFault + System.lineSeparator() + board;
    }
    if (depth <= 0) {
      return null;
    }
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      board.makeMove(move);
      try {
        if (board.currentPlayer().getOpponent().isInCheck()) {
          continue;
        }
        final String fault = findMutationDivergence(board, depth - 1);
        if (fault != null) {
          return fault;
        }
      } finally {
        board.unmakeMove();
      }
    }
    final String unmadeFault = describeBitboardFault(board);
    if (unmadeFault != null) {
      return "After unmaking every move: " + unmadeFault + System.lineSeparator() + board;
    }
    return null;
  }

  /**
   * Checks that the given board's bitboards and occupancies agree with the piece on every square.
   *
   * @param board The board to check.
   * @return A description of the first disagreement found, or null if they all agree.
   */
  private static String describeBitboardFault(final Board board) {
    for (final Alliance alliance : Alliance.values()) {
      long occupancy = 0L;
      for (final Piece.PieceType pieceType : Piece.PieceType.values()) {
        long expected = 0L;
        for (int coordinate = 0; coordinate < BoardUtils.NUM_TILES; coordinate++) {
          final Piece piece = board.getPiece(coordinate);
          if (piece != null && piece.getPieceAllegiance() == alliance && piece.getPieceType() == pieceType) {
            expected |= 1L << coordinate;
          }
        }
        if (board.getPieces(alliance, pieceType) != expected) {
          return "Bitboard of " + alliance + " " + pieceType + " is " +
                  Long.toHexString(board.getPieces(alliance, pieceType)) + ", expected " +
                  Long.toHexString(expected) + ".";
        }
        occupancy |= expected;
      }
      if (board.getOccupancy(alliance) != occupancy) {
        return "Occupancy of " + alliance + " is " + Long.toHexString(board.getOccupancy(alliance)) +
                ", expected " + Long.toHexString(occupancy) + ".";
      }
    }
    return null;
  }

  /**
   * Checks that a copy of the given board reproduces it exactly. Pieces are compared by identity
   * rather than equality, because a copy is expected to share the source's piece objects.
   *
   * @param board The board to copy and compare against.
   * @return A description of the first difference found, or null if the copy is faithful.
   */
  private static String describeCopyFault(final Board board) {
    final Board clone = board.copy();
    if (clone.getZobristHash() != board.getZobristHash()) {
      return "Copy carries hash " + clone.getZobristHash() + ", expected " + board.getZobristHash() + ".";
    }
    if (clone.getZobristHash() != ZobristHashing.calculateBoardHash(clone)) {
      return "Copy carries a hash that disagrees with a hash recalculated from its own position.";
    }
    if (clone.currentPlayer().getAlliance() != board.currentPlayer().getAlliance()) {
      return "Copy has the wrong side to move.";
    }
    if (clone.getHalfMoveClock() != board.getHalfMoveClock()) {
      return "Copy carries halfmove clock " + clone.getHalfMoveClock() +
              ", expected " + board.getHalfMoveClock() + ".";
    }
    if (clone.getEnPassantPawn() != board.getEnPassantPawn()) {
      return "Copy carries a different en passant pawn.";
    }
    for (int coordinate = 0; coordinate < BoardUtils.NUM_TILES; coordinate++) {
      if (clone.getPiece(coordinate) != board.getPiece(coordinate)) {
        return "Copy differs at square " + BoardUtils.getPositionAtCoordinate(coordinate) + ".";
      }
    }
    final String bitboardFault = describeBitboardFault(clone);
    if (bitboardFault != null) {
      return "Copy: " + bitboardFault;
    }
    if (clone.currentPlayer().getLegalMoves().size() != board.currentPlayer().getLegalMoves().size()) {
      return "Copy generates a different number of legal moves for the player to move.";
    }
    return null;
  }

  /**
   * Checks that a null move applied to the given board produces a consistent position and that
   * unmaking it restores the board exactly. The hash after the null move is checked against a
   * hash recalculated from scratch, which is the same standard {@link #findZobristDivergence}
   * holds real moves to.
   *
   * @param board The board to null move and restore. Restored before this method returns.
   * @return A description of the first fault found, or null if the round trip is exact.
   */
  private static String describeNullMoveFault(final Board board) {
    final Pawn priorEnPassantPawn = board.getEnPassantPawn();
    final long priorZobristHash = board.getZobristHash();
    final int priorHalfMoveClock = board.getHalfMoveClock();
    final Move priorTransitionMove = board.getTransitionMove();
    final Alliance priorMoveMaker = board.currentPlayer().getAlliance();

    board.makeNullMove();
    try {
      if (board.currentPlayer().getAlliance() == priorMoveMaker) {
        return "Null move left the same side to move.";
      }
      if (board.getEnPassantPawn() != null) {
        return "Null move left an en passant pawn set.";
      }
      if (board.getHalfMoveClock() != priorHalfMoveClock + 1) {
        return "Null move did not advance the halfmove clock by one.";
      }
      if (board.getZobristHash() != ZobristHashing.calculateBoardHash(board)) {
        return "Null move produced a hash that disagrees with a hash recalculated from scratch.";
      }
    } finally {
      board.unmakeNullMove();
    }

    if (board.getZobristHash() != priorZobristHash) {
      return "Unmaking the null move did not restore the hash.";
    }
    if (board.getEnPassantPawn() != priorEnPassantPawn) {
      return "Unmaking the null move did not restore the en passant pawn.";
    }
    if (board.getHalfMoveClock() != priorHalfMoveClock) {
      return "Unmaking the null move did not restore the halfmove clock.";
    }
    if (board.getTransitionMove() != priorTransitionMove) {
      return "Unmaking the null move did not restore the transition move.";
    }
    if (board.currentPlayer().getAlliance() != priorMoveMaker) {
      return "Unmaking the null move did not restore the side to move.";
    }
    return null;
  }

  /**
   * Renders a move in the long algebraic notation used by reference engines and divide output,
   * consisting of the origin square, the destination square, and for a promotion the lowercase
   * letter of the promoted piece. The promoted piece is read from the resulting board rather than
   * from the move itself, so that every promotion variant produces a distinct notation.
   *
   * @param move The move to render.
   * @param resultingBoard The board produced by executing the move.
   * @return The long algebraic notation of the move.
   */
  public static String longAlgebraicNotation(final Move move, final Board resultingBoard) {
    final int origin = move.getCurrentCoordinate();
    if (!BoardUtils.isValidTileCoordinate(origin)) {
      return NULL_MOVE_NOTATION;
    }
    final int destination = move.getDestinationCoordinate();
    final String notation = BoardUtils.getPositionAtCoordinate(origin) +
            BoardUtils.getPositionAtCoordinate(destination);
    final Piece placedPiece = resultingBoard.getPiece(destination);
    if (move.getMovedPiece().getPieceType() == Piece.PieceType.PAWN &&
            placedPiece != null && placedPiece.getPieceType() != Piece.PieceType.PAWN) {
      return notation + placedPiece.getPieceType().toString().toLowerCase();
    }
    return notation;
  }
}