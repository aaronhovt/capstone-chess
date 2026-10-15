package engine.forTesting;

import engine.Alliance;
import engine.forBoard.AttackDetector;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forBoard.MoveCode;
import engine.forBoard.MoveGenerator;
import engine.forBoard.ZobristHashing;
import engine.forPiece.King;
import engine.forPiece.Pawn;
import engine.forPiece.Piece;
import engine.forPlayer.Player;
import engine.forPlayer.forAI.StaticExchangeEvaluator;

import java.util.Iterator;
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
   * Counts the leaf nodes reachable from the given position at exactly the given depth, as
   * {@link #perft} does, but generating moves with {@link MoveGenerator} and applying them through
   * {@link Board#makeMove(int)} and {@link Board#unmakeMove(int)}.
   *
   * @param board The position from which to count. Mutated during the walk and restored before
   *              this method returns.
   * @param depth The number of plies to walk before counting.
   * @return The number of leaf nodes at the requested depth.
   */
  public static long perftInt(final Board board, final int depth) {
    return depth <= 0 ? 1L : perftInt(board, depth, new int[depth + 1][MoveGenerator.MAX_MOVES]);
  }

  /**
   * Counts the leaf nodes reachable from the given position at exactly the given depth with
   * packed moves, writing each depth's moves into its own row of the given buffers.
   *
   * @param board The position from which to count.
   * @param depth The number of plies to walk before counting, at least one.
   * @param buffers The move arrays, indexed by remaining depth.
   * @return The number of leaf nodes at the requested depth.
   */
  private static long perftInt(final Board board, final int depth, final int[][] buffers) {
    final int[] moves = buffers[depth];
    final int count = MoveGenerator.generate(board, board.currentPlayer().getAlliance(), moves);
    long nodes = 0L;
    for (int index = 0; index < count; index++) {
      final int move = moves[index];
      board.makeMove(move);
      try {
        if (!board.currentPlayer().getOpponent().isInCheck()) {
          nodes += depth == 1 ? 1L : perftInt(board, depth - 1, buffers);
        }
      } finally {
        board.unmakeMove(move);
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
   * round trip fails to reproduce the position it was given, or at which the board's bitboards or
   * piece lists disagree with its pieces, checked on arriving at each position and again after its
   * moves have been unmade, or at which unmaking a move does not restore the position's player
   * objects, or at which {@link MoveGenerator} disagrees with either side's legal move list, or
   * at which applying, testing or reversing a move as a packed int disagrees with doing so as a
   * {@link Move}. Nothing in the engine calls either
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
    final String generatorFault = describeGeneratorFault(board);
    if (generatorFault != null) {
      return generatorFault + System.lineSeparator() + board;
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
    final Player whitePlayer = board.whitePlayer();
    final Player blackPlayer = board.blackPlayer();
    final Player currentPlayer = board.currentPlayer();
    final int[] codes = new int[MoveGenerator.MAX_MOVES];
    MoveGenerator.generate(board, currentPlayer.getAlliance(), codes);
    int moveIndex = 0;
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      final String playerFault = describePlayerFault(board, whitePlayer, blackPlayer, currentPlayer);
      if (playerFault != null) {
        return "After unmaking a move: " + playerFault + System.lineSeparator() + board;
      }
      final String intMoveFault = describeIntMoveFault(board, move, codes[moveIndex++]);
      if (intMoveFault != null) {
        return move + ": " + intMoveFault + System.lineSeparator() + board;
      }
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
    String unmadeFault = describeBitboardFault(board);
    if (unmadeFault == null) {
      unmadeFault = describePlayerFault(board, whitePlayer, blackPlayer, currentPlayer);
    }
    if (unmadeFault != null) {
      return "After unmaking every move: " + unmadeFault + System.lineSeparator() + board;
    }
    return null;
  }

  /**
   * Checks that {@link MoveGenerator} writes, for each side, exactly the moves that side's player
   * lists, in the same order, each with the squares, promotion and flags of the listed move, and
   * that its quiescence moves are exactly the captures and queen promotions among them, in order.
   *
   * @param board The board to check.
   * @return A description of the first difference found, or null if the lists agree.
   */
  private static String describeGeneratorFault(final Board board) {
    final int[] generated = new int[MoveGenerator.MAX_MOVES];
    for (final Player player : new Player[] { board.whitePlayer(), board.blackPlayer() }) {
      final int count = MoveGenerator.generate(board, player.getAlliance(), generated);
      int index = 0;
      for (final Move move : player.getLegalMoves()) {
        if (index == count) {
          return "The generator writes " + count + " " + player.getAlliance() + " moves, " +
                  "expected " + player.getLegalMoves().size() + ".";
        }
        final String fault = describeCodeFault(move, generated[index]);
        if (fault != null) {
          return player.getAlliance() + " move " + index + " (" + move + "): " + fault;
        }
        index++;
      }
      if (index != count) {
        return "The generator writes " + count + " " + player.getAlliance() + " moves, " +
                "expected " + index + ".";
      }
      final int[] quiescence = new int[MoveGenerator.MAX_MOVES];
      final int quiescenceCount =
              MoveGenerator.generateQuiescence(board, player.getAlliance(), quiescence);
      int expected = 0;
      for (int i = 0; i < count; i++) {
        final int move = generated[i];
        if (!MoveCode.isCapture(move) &&
                MoveCode.promotionType(move) != Piece.PieceType.QUEEN) {
          continue;
        }
        if (expected == quiescenceCount || quiescence[expected] != move) {
          return "The " + player.getAlliance() + " quiescence moves differ at index " + expected +
                  " from the captures and queen promotions among all its moves.";
        }
        expected++;
      }
      if (expected != quiescenceCount) {
        return "The generator writes " + quiescenceCount + " " + player.getAlliance() +
                " quiescence moves, expected " + expected + ".";
      }
    }
    return null;
  }

  /**
   * Checks that applying the given packed move through {@link Board#makeMove(int)} leaves the
   * position {@link Board#makeMove(Move)} leaves for the equal move, with pieces compared by
   * value, that {@link Board#unmakeMove(int)} restores the board exactly, with pieces and players
   * compared by identity, that {@link Board#isLegal(int)} and the static exchange score of the
   * packed move agree with those of the move, and that {@link AttackDetector#givesCheck} answers
   * whether making the move gives check.
   *
   * @param board The board, standing in the position the move belongs to. Restored before this
   *              method returns.
   * @param move The move.
   * @param code The packed move equal to it.
   * @return A description of the first difference found, or null if the two paths agree.
   */
  private static String describeIntMoveFault(final Board board, final Move move, final int code) {
    final Snapshot before = Snapshot.of(board);
    if (board.isLegal(move) != board.isLegal(code)) {
      return "isLegal(int) answers " + board.isLegal(code) + ".";
    }
    final StaticExchangeEvaluator exchange = StaticExchangeEvaluator.get();
    if (exchange.evaluate(board, move) != exchange.evaluate(board, code)) {
      return "the static exchange score of the packed move is " +
              exchange.evaluate(board, code) + ", expected " + exchange.evaluate(board, move) + ".";
    }
    final String unmovedFault = before.describeIdentityDifference(Snapshot.of(board));
    if (unmovedFault != null) {
      return "isLegal(int) did not restore the board: " + unmovedFault;
    }

    final boolean predictedCheck = AttackDetector.givesCheck(board, code);
    board.makeMove(move);
    final Snapshot objectAfter = Snapshot.of(board);
    final boolean givesCheck = board.currentPlayer().isInCheck();
    board.unmakeMove();
    if (predictedCheck != givesCheck) {
      return "givesCheck(int) answers " + predictedCheck + ".";
    }

    board.makeMove(code);
    try {
      if (board.getTransitionCode() != code) {
        return "makeMove(int) left transition code " + board.getTransitionCode() + ".";
      }
      final String valueFault = objectAfter.describeValueDifference(Snapshot.of(board));
      if (valueFault != null) {
        return "makeMove(int) differs from makeMove(Move): " + valueFault;
      }
      final String bitboardFault = describeBitboardFault(board);
      if (bitboardFault != null) {
        return "makeMove(int): " + bitboardFault;
      }
    } finally {
      board.unmakeMove(code);
    }
    final String restoreFault = before.describeIdentityDifference(Snapshot.of(board));
    if (restoreFault != null) {
      return "unmakeMove(int) did not restore the board: " + restoreFault;
    }
    return describeBitboardFault(board);
  }

  /**
   * The observable state of a board at one moment.
   *
   * @param pieces The piece on each square, or null.
   * @param hash The Zobrist hash.
   * @param enPassantPawn The en passant pawn, or null.
   * @param halfMoveClock The halfmove clock.
   * @param plyCount The ply count.
   * @param repetitionCount The repetition count.
   * @param transitionMove The transition move.
   * @param transitionCode The transition code.
   * @param whitePlayer The white player.
   * @param blackPlayer The black player.
   * @param currentPlayer The player to move.
   * @param whiteKing The white king.
   * @param blackKing The black king.
   */
  private record Snapshot(Piece[] pieces, long hash, Pawn enPassantPawn, int halfMoveClock,
                          int plyCount, int repetitionCount, Move transitionMove,
                          int transitionCode, Player whitePlayer, Player blackPlayer,
                          Player currentPlayer, King whiteKing, King blackKing) {

    /**
     * Records the given board's state.
     *
     * @param board The board.
     * @return Its state.
     */
    static Snapshot of(final Board board) {
      final Piece[] pieces = new Piece[BoardUtils.NUM_TILES];
      for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
        pieces[square] = board.getPiece(square);
      }
      return new Snapshot(pieces, board.getZobristHash(), board.getEnPassantPawn(),
              board.getHalfMoveClock(), board.getPlyCount(), board.repetitionCount(),
              board.getTransitionMove(), board.getTransitionCode(), board.whitePlayer(),
              board.blackPlayer(), board.currentPlayer(), board.getKing(Alliance.WHITE),
              board.getKing(Alliance.BLACK));
    }

    /**
     * Describes the first way the given state differs from this one, comparing pieces, kings
     * and the en passant pawn by value and leaving out the transition move and the players
     * other than the side to move.
     *
     * @param other The other state.
     * @return A description of the difference, or null if there is none.
     */
    String describeValueDifference(final Snapshot other) {
      for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
        if (!samePiece(this.pieces[square], other.pieces[square])) {
          return "the piece on " + BoardUtils.getPositionAtCoordinate(square) + " differs.";
        }
      }
      if (!samePiece(this.whiteKing, other.whiteKing) || !samePiece(this.blackKing, other.blackKing)) {
        return "a king differs.";
      }
      if (!samePiece(this.enPassantPawn, other.enPassantPawn)) {
        return "the en passant pawn differs.";
      }
      return describeCountDifference(other);
    }

    /**
     * Describes the first way the given state differs from this one, comparing pieces, kings,
     * the en passant pawn, the transition move and the players by identity.
     *
     * @param other The other state.
     * @return A description of the difference, or null if there is none.
     */
    String describeIdentityDifference(final Snapshot other) {
      for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
        if (this.pieces[square] != other.pieces[square]) {
          return "the piece on " + BoardUtils.getPositionAtCoordinate(square) + " differs.";
        }
      }
      if (this.whiteKing != other.whiteKing || this.blackKing != other.blackKing) {
        return "a king differs.";
      }
      if (this.enPassantPawn != other.enPassantPawn) {
        return "the en passant pawn differs.";
      }
      if (this.transitionMove != other.transitionMove || this.transitionCode != other.transitionCode) {
        return "the transition move differs.";
      }
      if (this.whitePlayer != other.whitePlayer || this.blackPlayer != other.blackPlayer) {
        return "a player differs.";
      }
      return describeCountDifference(other);
    }

    /**
     * Describes the first way the given state's hash, clocks, counts or side to move differ from
     * this one's.
     *
     * @param other The other state.
     * @return A description of the difference, or null if there is none.
     */
    private String describeCountDifference(final Snapshot other) {
      if (this.hash != other.hash) {
        return "the hash differs.";
      }
      if (this.halfMoveClock != other.halfMoveClock) {
        return "the halfmove clock differs.";
      }
      if (this.plyCount != other.plyCount || this.repetitionCount != other.repetitionCount) {
        return "the ply or repetition count differs.";
      }
      if (this.currentPlayer.getAlliance() != other.currentPlayer.getAlliance()) {
        return "the side to move differs.";
      }
      return null;
    }

    /**
     * Determines whether two pieces are equal in type, alliance, square, moved status and, for
     * kings, castling status and rights.
     *
     * @param first One piece, or null.
     * @param second The other piece, or null.
     * @return True if both are null or the pieces are equal in those respects.
     */
    private static boolean samePiece(final Piece first, final Piece second) {
      if (first == null || second == null) {
        return first == second;
      }
      if (first.getPieceType() != second.getPieceType() ||
              first.getPieceAllegiance() != second.getPieceAllegiance() ||
              first.getPiecePosition() != second.getPiecePosition() ||
              first.isFirstMove() != second.isFirstMove()) {
        return false;
      }
      if (first instanceof King firstKing && second instanceof King secondKing) {
        return firstKing.isCastled() == secondKing.isCastled() &&
                firstKing.isKingSideCastleCapable() == secondKing.isKingSideCastleCapable() &&
                firstKing.isQueenSideCastleCapable() == secondKing.isQueenSideCastleCapable();
      }
      return true;
    }
  }

  /**
   * Checks that a packed move describes the given move.
   *
   * @param move The move.
   * @param code The packed move generated in its place.
   * @return A description of the first difference found, or null if they agree.
   */
  private static String describeCodeFault(final Move move, final int code) {
    final Piece promotion = move.getPromotionPiece();
    if (MoveCode.origin(code) != move.getCurrentCoordinate() ||
            MoveCode.destination(code) != move.getDestinationCoordinate()) {
      return "generated " + BoardUtils.getPositionAtCoordinate(MoveCode.origin(code)) +
              BoardUtils.getPositionAtCoordinate(MoveCode.destination(code)) + ".";
    }
    if (MoveCode.promotionType(code) != (promotion == null ? null : promotion.getPieceType())) {
      return "generated promotion " + MoveCode.promotionType(code) + ".";
    }
    if (MoveCode.isCapture(code) != move.isAttack()) {
      return "generated the capture flag as " + MoveCode.isCapture(code) + ".";
    }
    if (MoveCode.isEnPassant(code) != (move instanceof Move.PawnEnPassantAttack)) {
      return "generated the en passant flag as " + MoveCode.isEnPassant(code) + ".";
    }
    if (MoveCode.isCastle(code) != move.isCastlingMove()) {
      return "generated the castle flag as " + MoveCode.isCastle(code) + ".";
    }
    if (MoveCode.isPawnJump(code) != (move instanceof Move.PawnJump)) {
      return "generated the pawn jump flag as " + MoveCode.isPawnJump(code) + ".";
    }
    return null;
  }

  /**
   * Checks that the given board's players are the given player objects.
   *
   * @param board The board to check.
   * @param whitePlayer The white player the board must have.
   * @param blackPlayer The black player the board must have.
   * @param currentPlayer The player to move the board must have.
   * @return A description of the first difference found, or null if the players are the same.
   */
  private static String describePlayerFault(final Board board, final Player whitePlayer,
                                            final Player blackPlayer, final Player currentPlayer) {
    if (board.whitePlayer() != whitePlayer) {
      return "The white player is not the one the position had before.";
    }
    if (board.blackPlayer() != blackPlayer) {
      return "The black player is not the one the position had before.";
    }
    if (board.currentPlayer() != currentPlayer) {
      return "The player to move is not the one the position had before.";
    }
    return null;
  }

  /**
   * Checks that the given board's bitboards, occupancies and piece lists agree with the piece on
   * every square. Each piece list must hold that alliance's pieces in ascending square order.
   *
   * @param board The board to check.
   * @return A description of the first disagreement found, or null if they all agree.
   */
  private static String describeBitboardFault(final Board board) {
    for (final Alliance alliance : Alliance.values()) {
      final Iterator<Piece> listed =
              (alliance.isWhite() ? board.getWhitePieces() : board.getBlackPieces()).iterator();
      for (int coordinate = 0; coordinate < BoardUtils.NUM_TILES; coordinate++) {
        final Piece piece = board.getPiece(coordinate);
        if (piece != null && piece.getPieceAllegiance() == alliance &&
                (!listed.hasNext() || listed.next() != piece)) {
          return "Piece list of " + alliance + " differs at square " +
                  BoardUtils.getPositionAtCoordinate(coordinate) + ".";
        }
      }
      if (listed.hasNext()) {
        return "Piece list of " + alliance + " holds a piece not on the board.";
      }
    }
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
    final Player priorWhitePlayer = board.whitePlayer();
    final Player priorBlackPlayer = board.blackPlayer();
    final Player priorCurrentPlayer = board.currentPlayer();

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
    final String playerFault =
            describePlayerFault(board, priorWhitePlayer, priorBlackPlayer, priorCurrentPlayer);
    if (playerFault != null) {
      return "Unmaking the null move: " + playerFault;
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