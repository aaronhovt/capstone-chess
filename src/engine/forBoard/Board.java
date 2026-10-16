package engine.forBoard;

import engine.Alliance;
import engine.forPiece.*;
import engine.forPlayer.BlackPlayer;
import engine.forPlayer.Player;
import engine.forPlayer.WhitePlayer;

import java.util.*;

import static engine.forBoard.Move.MoveFactory.getNullMove;

/**
 * The Board class represents a chess board containing 64 tiles and chess pieces.
 * It provides methods for creating and maintaining the chess board state, managing pieces
 * and players, and calculating legal moves. The board keeps track of white and black pieces,
 * the current player, en passant opportunities, and the transition move that created the current state.
 * <p>
 * A board built through {@link Builder} still behaves as a fixed snapshot in the sense that
 * nothing else mutates it implicitly. In addition, a board now supports being mutated in place
 * through {@link #makeMove(Move)} and {@link #unmakeMove()}, which relocate pieces and update
 * state directly rather than constructing a new Board instance, pushing an undo record onto an
 * internal stack so the change can be exactly reversed. This mutating path is the only way a
 * move is applied anywhere in the engine; there is no longer an immutable transition that builds
 * a fresh board per move.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Board {

  /** The pieces on the board, indexed by square, with a null entry for an empty square. */
  private final Piece[] boardConfig;

  /**
   * The white pieces on the board in ascending square order, as of the last time
   * {@link #getWhitePieces()} rebuilt it. Rebuilt from whiteOccupancy when
   * {@link #whitePiecesStale} is set.
   */
  private final List<Piece> whitePieces = new ArrayList<>();

  /**
   * The black pieces on the board in ascending square order, as of the last time
   * {@link #getBlackPieces()} rebuilt it. Rebuilt from blackOccupancy when
   * {@link #blackPiecesStale} is set.
   */
  private final List<Piece> blackPieces = new ArrayList<>();

  /** Whether a white piece has been placed or removed since {@link #whitePieces} was rebuilt. */
  private boolean whitePiecesStale = true;

  /** Whether a black piece has been placed or removed since {@link #blackPieces} was rebuilt. */
  private boolean blackPiecesStale = true;

  /** The number of piece types, and so the number of bitboards each alliance holds. */
  private static final int PIECE_TYPE_COUNT = Piece.PieceType.values().length;

  /**
   * The squares holding each kind of piece, as one bit per tile, indexed by
   * {@link #bitboardIndex}. Kept in sync with boardConfig by placePiece/removePiece.
   */
  private final long[] pieceBitboards;

  /** The squares holding a white piece, as one bit per tile. Kept in sync with boardConfig by placePiece/removePiece. */
  private long whiteOccupancy;

  /** The squares holding a black piece, as one bit per tile. Kept in sync with boardConfig by placePiece/removePiece. */
  private long blackOccupancy;

  /**
   * The white king currently on the board, or null if white has no king. Kept in sync by
   * {@link #placePiece(Piece)}. A king is never captured, and every king relocation removes the
   * king and then places it again, so this reference is current whenever a make or unmake has
   * run to completion.
   */
  private King whiteKing;

  /**
   * The black king currently on the board, or null if black has no king. Kept in sync by
   * {@link #placePiece(Piece)}. A king is never captured, and every king relocation removes the
   * king and then places it again, so this reference is current whenever a make or unmake has
   * run to completion.
   */
  private King blackKing;

  /**
   * The player controlling the white pieces on the board, being the white player in the slot of
   * the current {@link #mutationDepth}.
   */
  private WhitePlayer whitePlayer;

  /**
   * The player controlling the black pieces on the board, being the black player in the slot of
   * the current {@link #mutationDepth}.
   */
  private BlackPlayer blackPlayer;

  /** The player whose turn it currently is to move. */
  public Player currentPlayer;

  /**
   * The pawn that is susceptible to en passant capture in the current position.
   * Null if no en passant opportunity exists.
   */
  private Pawn enPassantPawn;

  /**
   * The move that resulted in the current board position.
   * A null move if this is the initial board position.
   */
  private Move transitionMove;

  /** The Zobrist hash value for this board position, used for position identification. */
  private long zobristHash;

  /** The number of plies since the last pawn move or capture, tracked for the fifty-move rule. */
  private int halfMoveClock;

  /**
   * The undo records for null moves applied through {@link #makeNullMove()} but not yet unmade.
   * Moves and null moves must be unwound in strict last-in-first-out order relative to each
   * other: a move made after a null move must be unmade before that null move is unmade.
   */
  private final Deque<NullMoveUndo> nullMoveUndoStack = new ArrayDeque<>();

  /** The initial number of slots in {@link #whitePlayerSlots} and {@link #blackPlayerSlots}. */
  private static final int PLAYER_SLOT_CAPACITY = 64;

  /**
   * The white player for each depth of mutation, indexed by {@link #mutationDepth}. Applying a
   * move or a null move moves to the slot above, reusing the player there through
   * {@link Player#reset()} or creating one if the slot is empty. Reversing it steps back to the
   * slot below, whose player still describes that position, including any legal move list it had
   * computed, since a mutation only ever writes the slot above. Grown by replacement when full.
   */
  private WhitePlayer[] whitePlayerSlots = new WhitePlayer[PLAYER_SLOT_CAPACITY];

  /**
   * The black player for each depth of mutation, indexed by {@link #mutationDepth}, kept in the
   * same way as {@link #whitePlayerSlots}.
   */
  private BlackPlayer[] blackPlayerSlots = new BlackPlayer[PLAYER_SLOT_CAPACITY];

  /** The number of moves and null moves applied to this board and not yet unmade. */
  private int mutationDepth;

  /**
   * The last move applied through {@link #makeMove(int)} and not yet reversed, as a
   * {@link MoveCode}, or {@link MoveCode#NONE} if there is none. It is the transition move while
   * {@link #transitionMove} is null.
   */
  private int transitionCode = MoveCode.NONE;

  /** The initial length of the arrays holding what each move applied as an int replaced. */
  private static final int UNDO_CAPACITY = 64;

  /**
   * The {@link #transitionCode} each move applied through {@link #makeMove(int)} replaced,
   * indexed by the {@link #mutationDepth} before the move. Grown by replacement when full,
   * together with the arrays below.
   */
  private int[] priorTransitionCodes = new int[UNDO_CAPACITY];

  /**
   * The moved piece, as it stood before the move, of each move applied through
   * {@link #makeMove(int)}, indexed by the {@link #mutationDepth} before the move.
   */
  private Piece[] priorMovedPieces = new Piece[UNDO_CAPACITY];

  /**
   * The piece captured by each move applied through {@link #makeMove(int)}, or null, indexed by
   * the {@link #mutationDepth} before the move.
   */
  private Piece[] capturedPieces = new Piece[UNDO_CAPACITY];

  /**
   * The castling rook, as it stood before the move, of each castling move applied through
   * {@link #makeMove(int)}, indexed by the {@link #mutationDepth} before the move.
   */
  private Piece[] priorCastleRooks = new Piece[UNDO_CAPACITY];

  /**
   * The en passant pawn each move applied through {@link #makeMove(int)} replaced, indexed by the
   * {@link #mutationDepth} before the move.
   */
  private Pawn[] priorEnPassantPawns = new Pawn[UNDO_CAPACITY];

  /**
   * The Zobrist hash each move applied through {@link #makeMove(int)} replaced, indexed by the
   * {@link #mutationDepth} before the move.
   */
  private long[] priorHashes = new long[UNDO_CAPACITY];

  /**
   * The halfmove clock each move applied through {@link #makeMove(int)} replaced, indexed by the
   * {@link #mutationDepth} before the move.
   */
  private int[] priorHalfMoveClocks = new int[UNDO_CAPACITY];

  /**
   * The transition move each move applied through {@link #makeMove(int)} replaced, indexed by the
   * {@link #mutationDepth} before the move.
   */
  private Move[] priorTransitionMoves = new Move[UNDO_CAPACITY];

  /**
   * How many null moves applied through {@link #makeNullMove()} have not yet been unmade. While
   * this is greater than zero, {@link #makeMove(Move)} and {@link #unmakeMove()} leave
   * {@link #positionHistory} alone and {@link #repetitionCount()} reports one, so no position
   * below a null move is counted toward or scored as a repetition.
   */
  private int nullMoveDepth;

  /** The initial capacity of {@link #positionHistory}. */
  private static final int POSITION_HISTORY_CAPACITY = 256;

  /**
   * The Zobrist hash of every position reached along this board instance's own make/unmake path,
   * oldest first, held in the first {@link #positionHistorySize} entries. Appended to by
   * {@link #makeMove(Move)} and truncated by {@link #unmakeMove()}, for threefold repetition
   * detection. Grown by replacement when full.
   */
  private long[] positionHistory;

  /** The number of entries of {@link #positionHistory} in use. */
  private int positionHistorySize;

  /**
   * A pre-constructed standard chess board configuration representing the starting position.
   * This is a constant instance shared across all instances of the Board class.
   */
  private static final Board STANDARD_BOARD = createStandardBoardImpl();

  /**
   * Constructs a board from a builder object containing the board configuration.
   *
   * @param builder The builder object containing board configuration details.
   */
  private Board(final Builder builder) {
    this.boardConfig = builder.BoardConfigurations.clone();
    this.pieceBitboards = new long[2 * PIECE_TYPE_COUNT];
    for (final Piece piece : this.boardConfig) {
      if (piece != null) {
        setBit(piece);
      }
    }
    establishKings();
    this.enPassantPawn = builder.enPassantPawn;
    refreshPlayers(builder.nextMoveMaker);
    this.transitionMove = getNullMove();
    this.zobristHash = builder.zobristHash != 0 ? builder.zobristHash :
            ZobristHashing.calculateBoardHash(this);
    this.halfMoveClock = builder.halfMoveClock;
    this.plyCount = builder.plyCount;
    this.positionHistory = new long[POSITION_HISTORY_CAPACITY];
    this.positionHistory[0] = this.zobristHash;
    this.positionHistorySize = 1;
  }

  /**
   * Constructs a board that is positionally identical to the given board, sharing its piece
   * objects but owning fresh mutable collections, so that mutating one board cannot affect the
   * other. The pieces themselves are safe to share because they are immutable.
   * <p>
   * The copy starts with empty undo stacks, so it can only be unwound back to the position it
   * was copied at, not past it. {@link #positionHistory} is seeded from the source so that a copy
   * taken mid-game carries the repetition history that preceded it.
   * <p>
   * A {@link Move} generated from a board may be applied to a board other than the exact
   * instance it was generated from, but only while both boards stand in the position the move
   * belongs to. It is not enough for the target board to be in the right position:
   * {@link Move#updateZobristHash} reads castling rights and the en passant pawn from the board
   * the move was generated against rather than from the board being mutated, so that board must
   * still be in that position too. A copy taken from a board that is never subsequently mutated
   * satisfies both halves, which is what makes a shared root move list safe to apply across
   * search threads that each own a private copy, and what lets a move found by searching a copy
   * be applied to the board the game is played on.
   *
   * @param source The board to copy.
   */
  private Board(final Board source) {
    this.boardConfig = source.boardConfig.clone();
    this.pieceBitboards = source.pieceBitboards.clone();
    this.whiteOccupancy = source.whiteOccupancy;
    this.blackOccupancy = source.blackOccupancy;
    this.whiteKing = source.whiteKing;
    this.blackKing = source.blackKing;
    this.enPassantPawn = source.enPassantPawn;
    this.transitionMove = source.transitionMove;
    this.transitionCode = source.transitionCode;
    this.zobristHash = source.zobristHash;
    this.halfMoveClock = source.halfMoveClock;
    this.plyCount = source.plyCount;
    this.positionHistory = source.positionHistory.clone();
    this.positionHistorySize = source.positionHistorySize;
    refreshPlayers(source.currentPlayer.getAlliance());
  }

  /**
   * Returns an independent board in the same position as this one, which may be mutated through
   * {@link #makeMove(Move)} without affecting this board.
   *
   * @return A copy of this board.
   */
  public Board copy() {
    return new Board(this);
  }

  /**
   * Returns a string representation of the board, showing the piece configuration.
   * The board is displayed as an 8x8 grid with pieces or empty spaces.
   *
   * @return A string representation of the board.
   */
  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    for (int i = 0; i < BoardUtils.NUM_TILES; i++) {
      builder.append(prettyPrint(this.boardConfig[i])).append(" ");
      if ((i + 1) % 8 == 0) {
        builder.append("\n");
      }
    } return builder.toString();
  }

  /**
   * Converts a piece to a string representation for display.
   * White pieces are displayed as uppercase letters, black pieces as lowercase.
   * A dash represents empty tiles.
   *
   * @param piece The piece to convert to string.
   * @return The string representation of the piece.
   */
  private static String prettyPrint(final Piece piece) {
    if(piece != null) {
      return piece.getPieceAllegiance().isBlack() ?
              piece.toString().toLowerCase() : piece.toString();
    } return "-";
  }

  /**
   * Returns the black pieces currently on the board, in ascending square order. The returned
   * collection belongs to this board and changes when the board does, so it must not be modified
   * and must not be iterated across a change to the board.
   *
   * @return A collection of black pieces.
   */
  public Collection<Piece> getBlackPieces() {
    if (this.blackPiecesStale) {
      rebuildPieces(this.blackPieces, this.blackOccupancy);
      this.blackPiecesStale = false;
    }
    return this.blackPieces;
  }

  /**
   * Returns the white pieces currently on the board, in ascending square order. The returned
   * collection belongs to this board and changes when the board does, so it must not be modified
   * and must not be iterated across a change to the board.
   *
   * @return A collection of white pieces.
   */
  public Collection<Piece> getWhitePieces() {
    if (this.whitePiecesStale) {
      rebuildPieces(this.whitePieces, this.whiteOccupancy);
      this.whitePiecesStale = false;
    }
    return this.whitePieces;
  }

  /**
   * Returns a collection of all pieces on the board, both white and black.
   *
   * @return A collection of all pieces on the board.
   */
  public Collection<Piece> getAllPieces() {
    final List<Piece> allPieces = new ArrayList<>();
    allPieces.addAll(getWhitePieces());
    allPieces.addAll(getBlackPieces());
    return allPieces;
  }

  /**
   * Refills the given list with the pieces on the given squares, in ascending square order.
   *
   * @param pieces The list to refill.
   * @param occupancy The squares whose pieces to list, as one bit per tile.
   */
  private void rebuildPieces(final List<Piece> pieces, final long occupancy) {
    pieces.clear();
    for (long remaining = occupancy; remaining != 0L; remaining &= remaining - 1) {
      pieces.add(this.boardConfig[Long.numberOfTrailingZeros(remaining)]);
    }
  }

  /**
   * Returns a collection of all legal moves that can be made by both players
   * on the current board.
   *
   * @return A collection of all legal moves.
   */
  public Collection<Move> getAllLegalMoves() {
    final List<Move> allLegalMoves = new ArrayList<>();
    allLegalMoves.addAll(this.whitePlayer.getLegalMoves());
    allLegalMoves.addAll(this.blackPlayer.getLegalMoves());
    return allLegalMoves;
  }

  /**
   * Returns how many times the current position has been reached along this board's make and
   * unmake path, counting the present occurrence. Returns one while a null move applied through
   * {@link #makeNullMove()} is still in effect. Only the positions reached since the last pawn
   * move or capture, as given by the halfmove clock, are compared.
   *
   * @return The number of occurrences of the current position.
   */
  public int repetitionCount() {
    if (this.nullMoveDepth > 0) {
      return 1;
    }
    final int oldest = Math.max(0, this.positionHistorySize - 1 - this.halfMoveClock);
    int count = 0;
    for (int index = this.positionHistorySize - 1; index >= oldest; index--) {
      if (this.positionHistory[index] == this.zobristHash) {
        count++;
      }
    }
    return Math.max(count, 1);
  }

  /**
   * Returns whether the current position has occurred three or more times along this board's make
   * and unmake path. The Zobrist hash covers the side to move, castling rights, and en passant
   * availability, so hash equality is the same test the rule itself specifies.
   *
   * @return True if the position has been repeated threefold.
   */
  public boolean isThreefoldRepetition() {
    return repetitionCount() >= 3;
  }

  /**
   * Returns whether one hundred plies, meaning fifty full moves, have passed with no pawn move
   * and no capture.
   *
   * @return True if the fifty-move rule applies.
   */
  public boolean isFiftyMoveRule() {
    return this.halfMoveClock >= 100;
  }

  /**
   * Returns whether the material left on the board makes checkmate impossible for both sides. A
   * king and two knights against a bare king is not included, since mate there is still legally
   * reachable.
   *
   * @return True if neither side can deliver checkmate.
   */
  public boolean isInsufficientMaterial() {
    int knightCount = 0;
    int bishopCount = 0;
    int bishopSquareColors = 0;
    for (final Piece piece : getAllPieces()) {
      switch (piece.getPieceType()) {
        case PAWN, ROOK, QUEEN -> {
          return false;
        }
        case KNIGHT -> knightCount++;
        case BISHOP -> {
          bishopCount++;
          final int position = piece.getPiecePosition();
          bishopSquareColors |= 1 << (((position / 8) + (position % 8)) % 2);
        }
        default -> { }
      }
    }
    if (knightCount == 0) {
      return bishopSquareColors != 3;
    }
    return knightCount == 1 && bishopCount == 0;
  }

  /**
   * Retrieves the white player controlling the white pieces.
   *
   * @return The white player.
   */
  public WhitePlayer whitePlayer() {
    return this.whitePlayer;
  }

  /**
   * Retrieves the black player controlling the black pieces.
   *
   * @return The black player.
   */
  public BlackPlayer blackPlayer() {
    return this.blackPlayer;
  }

  /**
   * Retrieves the player whose turn it is to move.
   *
   * @return The current player.
   */
  public Player currentPlayer() {
    return this.currentPlayer;
  }

  /**
   * Retrieves the piece at the specified coordinate on the board.
   *
   * @param coordinate The coordinate to check (0-63).
   * @return The piece at the specified coordinate, or null if the square is empty.
   * @throws ArrayIndexOutOfBoundsException If the coordinate lies outside 0 to 63.
   */
  public Piece getPiece(final int coordinate) {
    return this.boardConfig[coordinate];
  }

  /**
   * Returns the squares holding the given alliance's pieces of the given type. Bit n stands for
   * tile n, the numbering {@link #getPiece(int)} uses.
   *
   * @param alliance The alliance of the pieces.
   * @param pieceType The type of the pieces.
   * @return The squares holding those pieces, as one bit per tile.
   */
  public long getPieces(final Alliance alliance, final Piece.PieceType pieceType) {
    return this.pieceBitboards[bitboardIndex(alliance, pieceType)];
  }

  /**
   * Returns the squares holding any of the given alliance's pieces. Bit n stands for tile n.
   *
   * @param alliance The alliance of the pieces.
   * @return The squares holding that alliance's pieces, as one bit per tile.
   */
  public long getOccupancy(final Alliance alliance) {
    return alliance.isWhite() ? this.whiteOccupancy : this.blackOccupancy;
  }

  /**
   * Returns the squares holding any piece. Bit n stands for tile n.
   *
   * @return The occupied squares, as one bit per tile.
   */
  public long getOccupancy() {
    return this.whiteOccupancy | this.blackOccupancy;
  }

  /**
   * Retrieves the king belonging to the given alliance.
   *
   * @param alliance The alliance whose king to retrieve.
   * @return The king of that alliance, or null if that alliance has no king on the board.
   */
  public King getKing(final Alliance alliance) {
    return alliance.isWhite() ? this.whiteKing : this.blackKing;
  }

  /**
   * Retrieves the pawn susceptible to en passant capture, if any.
   *
   * @return The en passant pawn, or null if no pawn is susceptible.
   */
  public Pawn getEnPassantPawn() {
    return this.enPassantPawn;
  }

  /**
   * Retrieves the move that led to the current board state.
   *
   * @return The transition move, or a null move if no transition occurred, or null if the move
   *         was applied through {@link #makeMove(int)}.
   */
  public Move getTransitionMove() {
    return this.transitionMove;
  }

  /**
   * Retrieves the move that led to the current board state as a {@link MoveCode}, however it was
   * applied.
   *
   * @return The packed transition move, or {@link MoveCode#NONE} if it was the null move or no
   *         move has been applied.
   */
  public int getTransitionCode() {
    return this.transitionMove != null ? MoveCode.of(this.transitionMove) : this.transitionCode;
  }

  /**
   * Gets the Zobrist hash value for this board position.
   * This hash is used for efficient position identification and transposition table lookups.
   *
   * @return The 64-bit Zobrist hash.
   */
  public long getZobristHash() {
    return this.zobristHash;
  }

  /**
   * Returns the number of plies played since the last pawn move or capture, used by the
   * fifty-move rule. A value of one hundred or more means fifty full moves have passed without
   * a pawn move or capture.
   *
   * @return The current halfmove clock.
   */
  public int getHalfMoveClock() {
    return this.halfMoveClock;
  }

  /**
   * Returns the number of plies played on this board since it was constructed, through
   * {@link #makeMove(Move)}. Not advanced by {@link #makeNullMove()}.
   *
   * @return The current ply count.
   */
  public int getPlyCount() {
    return this.plyCount;
  }

  /**
   * Returns the hash code for this board, using the Zobrist hash value.
   *
   * @return The hash code.
   */
  @Override
  public int hashCode() {
    return (int) this.zobristHash;
  }

  /**
   * The number of plies played on this board since it was constructed, advanced by
   * {@link #makeMove(Move)} and reversed by {@link #unmakeMove()}. Not advanced by
   * {@link #makeNullMove()}, since a null move is synthetic and is only ever applied to a
   * private search copy, never to the board a game is actually played on.
   */
  private int plyCount;

  /**
   * Applies the given move to this board in place, as {@link #makeMove(int)} applies its packed
   * form, and records the move as the transition move. The players of the position before the
   * move are kept for {@link #unmakeMove()} to restore, but any player obtained after the move
   * describes a later position once that move is unmade and another is made.
   *
   * @param move A move of the side to move in this board's current position.
   */
  public void makeMove(final Move move) {
    makeMove(MoveCode.of(move));
    this.transitionMove = move;
  }

  /**
   * Reverses the most recent move, restoring this board's pieces, state, and players to exactly
   * what they were beforehand. The move may have been applied through {@link #makeMove(Move)} or
   * {@link #makeMove(int)}.
   *
   * @throws IllegalStateException If no move is applied, or a null move was applied after the
   *                               most recent move and is still applied.
   */
  public void unmakeMove() {
    final int move = getTransitionCode();
    if (this.mutationDepth == 0 || move == MoveCode.NONE) {
      throw new IllegalStateException("No move to unmake.");
    }
    unmakeMove(move);
  }

  /**
   * Passes the turn to the opponent without moving a piece, for null move pruning in search.
   * The side to move flips, any en passant opportunity is cleared because it cannot survive a
   * turn in which nothing was played, the halfmove clock advances, and the transition move
   * becomes the null move so that move ordering heuristics keyed on the previous move do not
   * read a real move that was not actually just played. Both players are refreshed against the
   * resulting position, and the players they replaced are kept for {@link #unmakeNullMove()} to
   * restore.
   * <p>
   * {@link #positionHistory} is deliberately not touched. A null move position is synthetic and
   * was never reached in a real game, so counting it toward threefold repetition would be wrong.
   * For the same reason, positions reached by real moves made while this null move is still in
   * effect are neither counted nor reported as repetitions.
   */
  public void makeNullMove() {
    final NullMoveUndo undo = new NullMoveUndo(this.enPassantPawn, this.zobristHash,
            this.halfMoveClock, this.transitionMove, this.currentPlayer.getAlliance());
    final Alliance nextMover = this.currentPlayer.getOpponent().getAlliance();

    long hash = ZobristHashing.updateHashSideToMove(this.zobristHash);
    if (this.enPassantPawn != null) {
      hash = ZobristHashing.updateHashEnPassant(hash, this.enPassantPawn.getPiecePosition() % 8);
    }

    this.zobristHash = hash;
    this.enPassantPawn = null;
    this.halfMoveClock = this.halfMoveClock + 1;
    this.transitionMove = getNullMove();
    this.mutationDepth++;
    refreshPlayers(nextMover);
    this.nullMoveUndoStack.push(undo);
    this.nullMoveDepth++;
  }

  /**
   * Reverses the most recent null move applied through {@link #makeNullMove()}, restoring this
   * board to exactly what it was beforehand.
   *
   * @throws IllegalStateException If no null move is on the null move undo stack to unmake.
   */
  public void unmakeNullMove() {
    if (this.nullMoveUndoStack.isEmpty()) {
      throw new IllegalStateException("No null move on the undo stack to unmake.");
    }
    final NullMoveUndo undo = this.nullMoveUndoStack.pop();
    this.enPassantPawn = undo.priorEnPassantPawn();
    this.zobristHash = undo.priorZobristHash();
    this.halfMoveClock = undo.priorHalfMoveClock();
    this.transitionMove = undo.priorTransitionMove();
    this.mutationDepth--;
    restorePlayers(undo.priorMoveMaker());
    this.nullMoveDepth--;
  }

  /**
   * Returns whether the given move is legal for the side to move, meaning it does not leave that
   * side's own king attacked, as {@link #isLegal(int)} answers for its packed form. Move
   * generation is pseudo-legal, so this is the check that separates the moves that may actually
   * be played from the moves that merely look playable.
   *
   * @param move A move of the side to move in this board's current position.
   * @return True if the move leaves the moving side's king unattacked, false otherwise.
   */
  public boolean isLegal(final Move move) {
    return isLegal(MoveCode.of(move));
  }

  /**
   * Applies the given packed move to this board in place: relocates the pieces and updates the
   * bitboards, Zobrist hash, en passant pawn, halfmove clock, players, repetition history and ply
   * count. The move is reversed by
   * {@link #unmakeMove(int)}, and moves and null moves applied by any of the board's methods must
   * be reversed in the opposite order to the one they were applied in. After this move
   * {@link #getTransitionMove()} returns null and {@link #getTransitionCode()} returns the move.
   *
   * @param move A move {@link MoveGenerator} generates for the side to move in this position.
   */
  public void makeMove(final int move) {
    final int depth = this.mutationDepth;
    ensureUndoCapacity();
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    final Piece moved = this.boardConfig[origin];
    final Alliance mover = moved.getPieceAllegiance();
    final long hash = hashAfter(move, moved);

    this.priorTransitionCodes[depth] = this.transitionCode;
    this.priorTransitionMoves[depth] = this.transitionMove;
    this.priorEnPassantPawns[depth] = this.enPassantPawn;
    this.priorHashes[depth] = this.zobristHash;
    this.priorHalfMoveClocks[depth] = this.halfMoveClock;
    this.priorMovedPieces[depth] = moved;
    if (MoveCode.isCastle(move)) {
      this.priorCastleRooks[depth] = this.boardConfig[castleRookOrigin(origin, destination)];
      this.capturedPieces[depth] = null;
      this.halfMoveClock++;
    } else {
      this.priorCastleRooks[depth] = null;
      this.capturedPieces[depth] = this.boardConfig[captureSquare(move)];
      this.halfMoveClock = moved.getPieceType() == Piece.PieceType.PAWN ||
              this.capturedPieces[depth] != null ? 0 : this.halfMoveClock + 1;
    }
    movePieces(move, moved, this.capturedPieces[depth]);

    this.enPassantPawn = MoveCode.isPawnJump(move) ? (Pawn) this.boardConfig[destination] : null;
    this.zobristHash = hash;
    this.transitionMove = null;
    this.transitionCode = move;
    this.mutationDepth++;
    refreshPlayers(mover.isWhite() ? Alliance.BLACK : Alliance.WHITE);
    if (this.nullMoveDepth == 0) {
      if (this.positionHistorySize == this.positionHistory.length) {
        this.positionHistory = Arrays.copyOf(this.positionHistory, this.positionHistory.length * 2);
      }
      this.positionHistory[this.positionHistorySize++] = this.zobristHash;
    }
    this.plyCount++;
  }

  /**
   * Reverses the given packed move, which must be the move most recently applied through
   * {@link #makeMove(int)} and not yet reversed, with no later mutation still applied, restoring
   * this board's pieces, state, and players to exactly what they were beforehand.
   *
   * @param move The move to reverse.
   */
  public void unmakeMove(final int move) {
    if (this.nullMoveDepth == 0) {
      this.positionHistorySize--;
    }
    this.mutationDepth--;
    final int depth = this.mutationDepth;
    final Piece moved = this.priorMovedPieces[depth];
    restorePieces(move, moved, this.capturedPieces[depth], this.priorCastleRooks[depth]);
    this.zobristHash = this.priorHashes[depth];
    this.enPassantPawn = this.priorEnPassantPawns[depth];
    this.halfMoveClock = this.priorHalfMoveClocks[depth];
    this.transitionMove = this.priorTransitionMoves[depth];
    this.transitionCode = this.priorTransitionCodes[depth];
    restorePlayers(moved.getPieceAllegiance());
    this.plyCount--;
  }

  /**
   * Returns whether the given packed move is legal for the side to move, meaning it does not
   * leave that side's own king attacked. Only the pieces are moved to answer, and they are
   * restored before this method returns, so the players and their cached move lists are left as
   * they were.
   *
   * @param move A move {@link MoveGenerator} generates for the side to move in this position.
   * @return True if the move leaves the moving side's king unattacked, false otherwise.
   */
  public boolean isLegal(final int move) {
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    final Piece moved = this.boardConfig[origin];
    final Alliance opponentAlliance = this.currentPlayer.getOpponent().getAlliance();
    final int kingSquare = moved.getPieceType() == Piece.PieceType.KING ?
            destination : this.currentPlayer.getPlayerKing().getPiecePosition();
    final Piece captured = MoveCode.isCastle(move) ? null : this.boardConfig[captureSquare(move)];
    final Piece castleRook = MoveCode.isCastle(move) ?
            this.boardConfig[castleRookOrigin(origin, destination)] : null;
    movePieces(move, moved, captured);
    try {
      return !AttackDetector.isSquareAttacked(kingSquare, opponentAlliance, this);
    } finally {
      restorePieces(move, moved, captured, castleRook);
    }
  }

  /**
   * Moves the pieces for the given packed move: removes the moved piece and any captured piece,
   * places the moved piece, or the promotion piece, on its destination, and moves the rook of a
   * castling move.
   *
   * @param move The move.
   * @param moved The piece on the move's origin square.
   * @param captured The piece the move captures, or null.
   */
  private void movePieces(final int move, final Piece moved, final Piece captured) {
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    final Alliance mover = moved.getPieceAllegiance();
    removePiece(origin);
    if (MoveCode.isCastle(move)) {
      removePiece(castleRookOrigin(origin, destination));
      placePiece(Piece.moved(Piece.PieceType.KING, mover, destination, true));
      placePiece(Piece.moved(Piece.PieceType.ROOK, mover,
              castleRookDestination(origin, destination), false));
      return;
    }
    if (captured != null) {
      removePiece(captured.getPiecePosition());
    }
    final Piece.PieceType promotionType = MoveCode.promotionType(move);
    placePiece(Piece.moved(promotionType != null ? promotionType : moved.getPieceType(), mover,
            destination, false));
  }

  /**
   * Reverses {@link #movePieces}, putting back the given pieces as they stood before the move.
   *
   * @param move The move.
   * @param moved The moved piece as it stood before the move.
   * @param captured The piece the move captured, or null.
   * @param castleRook The castling rook as it stood before the move, or null.
   */
  private void restorePieces(final int move, final Piece moved, final Piece captured,
                             final Piece castleRook) {
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    removePiece(destination);
    if (MoveCode.isCastle(move)) {
      removePiece(castleRookDestination(origin, destination));
      placePiece(moved);
      placePiece(castleRook);
      return;
    }
    placePiece(moved);
    if (captured != null) {
      placePiece(captured);
    }
  }

  /**
   * Returns the square holding the piece the given packed move captures, being the en passant
   * pawn's square for an en passant capture and the destination otherwise.
   *
   * @param move The move.
   * @return The square of the captured piece, which is empty if the move does not capture.
   */
  private int captureSquare(final int move) {
    return MoveCode.isEnPassant(move) ?
            this.enPassantPawn.getPiecePosition() : MoveCode.destination(move);
  }

  /**
   * Returns the square the rook of a castling move leaves.
   *
   * @param kingOrigin The king's origin.
   * @param kingDestination The king's destination.
   * @return The rook's origin.
   */
  private static int castleRookOrigin(final int kingOrigin, final int kingDestination) {
    return kingDestination > kingOrigin ? kingOrigin + 3 : kingOrigin - 4;
  }

  /**
   * Returns the square the rook of a castling move arrives on.
   *
   * @param kingOrigin The king's origin.
   * @param kingDestination The king's destination.
   * @return The rook's destination.
   */
  private static int castleRookDestination(final int kingOrigin, final int kingDestination) {
    return kingDestination > kingOrigin ? kingOrigin + 1 : kingOrigin - 1;
  }

  /**
   * Returns the Zobrist hash the given packed move leaves, computed from this board before the
   * move.
   *
   * @param move The move.
   * @param moved The piece on the move's origin square.
   * @return The hash after the move.
   */
  private long hashAfter(final int move, final Piece moved) {
    final int origin = MoveCode.origin(move);
    final int destination = MoveCode.destination(move);
    final boolean white = moved.getPieceAllegiance().isWhite();
    long hash = this.zobristHash;

    if (MoveCode.isCastle(move)) {
      final int rookOrigin = castleRookOrigin(origin, destination);
      hash = ZobristHashing.updateHashCastle(hash, moved, this.boardConfig[rookOrigin], origin,
              destination, rookOrigin, castleRookDestination(origin, destination));
      hash = ZobristHashing.updateHashSideToMove(hash);
      if (this.enPassantPawn != null) {
        hash = ZobristHashing.updateHashEnPassant(hash, this.enPassantPawn.getPiecePosition() % 8);
      }
      if (ZobristHashing.canCastle(this, white, true)) {
        hash = ZobristHashing.updateHashCastlingRight(hash, white ? 0 : 2);
      }
      if (ZobristHashing.canCastle(this, white, false)) {
        hash = ZobristHashing.updateHashCastlingRight(hash, white ? 1 : 3);
      }
      return hash;
    }

    final Piece captured = MoveCode.isCapture(move) ? this.boardConfig[captureSquare(move)] : null;
    if (captured != null) {
      hash = ZobristHashing.updateHashPieceCapture(hash, captured);
    }
    hash = ZobristHashing.updateHashPieceMove(hash, moved, origin, destination);
    hash = ZobristHashing.updateHashSideToMove(hash);
    if (moved.getPieceType() == Piece.PieceType.KING) {
      if (ZobristHashing.canCastle(this, white, true)) {
        hash = ZobristHashing.updateHashCastlingRight(hash, white ? 0 : 2);
      }
      if (ZobristHashing.canCastle(this, white, false)) {
        hash = ZobristHashing.updateHashCastlingRight(hash, white ? 1 : 3);
      }
    } else if (moved.getPieceType() == Piece.PieceType.ROOK) {
      hash = clearRookCastlingRight(hash, white, origin);
    }
    if (captured != null && captured.getPieceType() == Piece.PieceType.ROOK) {
      hash = clearRookCastlingRight(hash, captured.getPieceAllegiance().isWhite(),
              captured.getPiecePosition());
    }
    if (this.enPassantPawn != null) {
      hash = ZobristHashing.updateHashEnPassant(hash, this.enPassantPawn.getPiecePosition() % 8);
    }
    if (MoveCode.isPawnJump(move)) {
      hash = ZobristHashing.updateHashEnPassant(hash, destination % 8);
    }
    final Piece.PieceType promotionType = MoveCode.promotionType(move);
    if (promotionType != null) {
      hash = ZobristHashing.updateHashPromotion(hash, (Pawn) moved,
              Piece.moved(promotionType, moved.getPieceAllegiance(), destination, false),
              destination);
    }
    return hash;
  }

  /**
   * Removes from the given hash the castling right a rook on the given home square stands for,
   * if the board still holds that right.
   *
   * @param hash The hash.
   * @param white Whether the rook is white.
   * @param rookSquare The rook's square, which only clears a right when it is a home square.
   * @return The hash without that right.
   */
  private long clearRookCastlingRight(final long hash, final boolean white, final int rookSquare) {
    if (white) {
      if (rookSquare == 56 && ZobristHashing.canCastle(this, true, false)) {
        return ZobristHashing.updateHashCastlingRight(hash, 1);
      }
      if (rookSquare == 63 && ZobristHashing.canCastle(this, true, true)) {
        return ZobristHashing.updateHashCastlingRight(hash, 0);
      }
    } else {
      if (rookSquare == 0 && ZobristHashing.canCastle(this, false, false)) {
        return ZobristHashing.updateHashCastlingRight(hash, 3);
      }
      if (rookSquare == 7 && ZobristHashing.canCastle(this, false, true)) {
        return ZobristHashing.updateHashCastlingRight(hash, 2);
      }
    }
    return hash;
  }

  /**
   * Grows the arrays holding what each move applied as an int replaced when the current
   * {@link #mutationDepth} has no slot in them.
   */
  private void ensureUndoCapacity() {
    if (this.mutationDepth < this.priorTransitionCodes.length) {
      return;
    }
    final int length = this.priorTransitionCodes.length * 2;
    this.priorTransitionCodes = Arrays.copyOf(this.priorTransitionCodes, length);
    this.priorMovedPieces = Arrays.copyOf(this.priorMovedPieces, length);
    this.capturedPieces = Arrays.copyOf(this.capturedPieces, length);
    this.priorCastleRooks = Arrays.copyOf(this.priorCastleRooks, length);
    this.priorEnPassantPawns = Arrays.copyOf(this.priorEnPassantPawns, length);
    this.priorHashes = Arrays.copyOf(this.priorHashes, length);
    this.priorHalfMoveClocks = Arrays.copyOf(this.priorHalfMoveClocks, length);
    this.priorTransitionMoves = Arrays.copyOf(this.priorTransitionMoves, length);
  }

  /**
   * Makes the players in the slot of the current {@link #mutationDepth} describe this board's
   * current position, creating them if the slot is empty and resetting them otherwise, and makes
   * them the board's players. Called when a board is built or copied and when a move or a null
   * move is applied. Reversing a move or a null move goes through {@link #restorePlayers}
   * instead.
   * <p>
   * Neither creating nor resetting a {@link Player} computes its legal move list or its check
   * status; both are computed at most once per side, only if something asks for them.
   *
   * @param moveMaker The alliance to move in the resulting position.
   */
  private void refreshPlayers(final Alliance moveMaker) {
    if (this.mutationDepth == this.whitePlayerSlots.length) {
      this.whitePlayerSlots = Arrays.copyOf(this.whitePlayerSlots, this.mutationDepth * 2);
      this.blackPlayerSlots = Arrays.copyOf(this.blackPlayerSlots, this.mutationDepth * 2);
    }
    if (this.whitePlayerSlots[this.mutationDepth] == null) {
      this.whitePlayerSlots[this.mutationDepth] = new WhitePlayer(this);
      this.blackPlayerSlots[this.mutationDepth] = new BlackPlayer(this);
    } else {
      this.whitePlayerSlots[this.mutationDepth].reset();
      this.blackPlayerSlots[this.mutationDepth].reset();
    }
    restorePlayers(moveMaker);
  }

  /**
   * Makes the players in the slot of the current {@link #mutationDepth} the board's players.
   *
   * @param moveMaker The alliance to move in the current position.
   */
  private void restorePlayers(final Alliance moveMaker) {
    this.whitePlayer = this.whitePlayerSlots[this.mutationDepth];
    this.blackPlayer = this.blackPlayerSlots[this.mutationDepth];
    this.currentPlayer = moveMaker.choosePlayerByAlliance(this.whitePlayer, this.blackPlayer);
  }

  /**
   * Places a piece on the board at its own reported position, adding it to the board's piece
   * configuration and bitboards, marking the appropriate alliance's piece list for rebuilding,
   * and recording it as that alliance's king if it is one. The square must be empty.
   *
   * @param piece The piece to place, at the square given by its own position.
   */
  private void placePiece(final Piece piece) {
    this.boardConfig[piece.getPiecePosition()] = piece;
    setBit(piece);
    if (piece.getPieceType() == Piece.PieceType.KING) {
      recordKing((King) piece);
    }
  }

  /**
   * Removes whatever piece occupies the given square, if any, from the board's piece
   * configuration and bitboards, and marks the appropriate alliance's piece list for rebuilding.
   *
   * @param coordinate The square to clear.
   */
  private void removePiece(final int coordinate) {
    final Piece piece = this.boardConfig[coordinate];
    if (piece == null) {
      return;
    }
    this.boardConfig[coordinate] = null;
    final long bit = 1L << coordinate;
    this.pieceBitboards[bitboardIndex(piece.getPieceAllegiance(), piece.getPieceType())] &= ~bit;
    if (piece.getPieceAllegiance().isWhite()) {
      this.whiteOccupancy &= ~bit;
      this.whitePiecesStale = true;
    } else {
      this.blackOccupancy &= ~bit;
      this.blackPiecesStale = true;
    }
  }

  /**
   * Sets the bit of the given piece's square in its bitboard and its alliance's occupancy, and
   * marks that alliance's piece list for rebuilding.
   *
   * @param piece The piece to record, at the square given by its own position.
   */
  private void setBit(final Piece piece) {
    final long bit = 1L << piece.getPiecePosition();
    this.pieceBitboards[bitboardIndex(piece.getPieceAllegiance(), piece.getPieceType())] |= bit;
    if (piece.getPieceAllegiance().isWhite()) {
      this.whiteOccupancy |= bit;
      this.whitePiecesStale = true;
    } else {
      this.blackOccupancy |= bit;
      this.blackPiecesStale = true;
    }
  }

  /**
   * Returns the index in {@link #pieceBitboards} of the bitboard for the given alliance's pieces
   * of the given type.
   *
   * @param alliance The alliance of the pieces.
   * @param pieceType The type of the pieces.
   * @return The index of that bitboard.
   */
  private static int bitboardIndex(final Alliance alliance, final Piece.PieceType pieceType) {
    return (alliance.isWhite() ? 0 : PIECE_TYPE_COUNT) + pieceType.ordinal();
  }

  /**
   * Returns a standard chess board configuration representing the starting position.
   * This method uses a cached instance for better performance.
   *
   * @return A standard chess board with pieces in their initial positions.
   */
  public static Board createStandardBoard() {
    return STANDARD_BOARD.copy();
  }

  /**
   * Creates and returns a standard chess board configuration with all pieces
   * in their initial positions.
   *
   * @return A new Board instance with the standard starting positions of pieces.
   */
  private static Board createStandardBoardImpl() {
    final Builder builder = new Builder();

    builder.setPiece(new Rook(Alliance.BLACK, 0, 0));
    builder.setPiece(new Knight(Alliance.BLACK, 1, 0));
    builder.setPiece(new Bishop(Alliance.BLACK, 2, 0));
    builder.setPiece(new Queen(Alliance.BLACK, 3, 0));
    builder.setPiece(new King(Alliance.BLACK, 4, true, true));
    builder.setPiece(new Bishop(Alliance.BLACK, 5, 0));
    builder.setPiece(new Knight(Alliance.BLACK, 6, 0));
    builder.setPiece(new Rook(Alliance.BLACK, 7, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 8, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 9,0));
    builder.setPiece(new Pawn(Alliance.BLACK, 10, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 11, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 12, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 13, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 14, 0));
    builder.setPiece(new Pawn(Alliance.BLACK, 15, 0));

    builder.setPiece(new Pawn(Alliance.WHITE, 48, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 49, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 50, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 51, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 52, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 53, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 54, 0));
    builder.setPiece(new Pawn(Alliance.WHITE, 55, 0));
    builder.setPiece(new Rook(Alliance.WHITE, 56, 0));
    builder.setPiece(new Knight(Alliance.WHITE, 57, 0));
    builder.setPiece(new Bishop(Alliance.WHITE, 58, 0));
    builder.setPiece(new Queen(Alliance.WHITE, 59, 0));
    builder.setPiece(new King(Alliance.WHITE, 60, true, true));
    builder.setPiece(new Bishop(Alliance.WHITE, 61, 0));
    builder.setPiece(new Knight(Alliance.WHITE, 62, 0));
    builder.setPiece(new Rook(Alliance.WHITE, 63, 0));
    builder.setMoveMaker(Alliance.WHITE);

    return builder.build();
  }

  /**
   * Scans this board's piece configuration and records the king of each alliance. An alliance
   * with no king on the board is left with a null king.
   */
  private void establishKings() {
    for (final Piece piece : this.boardConfig) {
      if (piece != null && piece.getPieceType() == Piece.PieceType.KING) {
        recordKing((King) piece);
      }
    }
  }

  /**
   * Records the given king as the current king of its alliance.
   *
   * @param king The king to record.
   */
  private void recordKing(final King king) {
    if (king.getPieceAllegiance().isWhite()) {
      this.whiteKing = king;
    } else {
      this.blackKing = king;
    }
  }

  /**
   * A builder class for constructing instances of the Board class with specific configurations.
   * This class follows the Builder pattern to facilitate the creation of complex Board objects.
   */
  public static class Builder {
    /** The pieces for the board being built, indexed by square, null for an empty square. */
    private final Piece[] BoardConfigurations;
    /** The player who will make the next move on the board being built. */
    private Alliance nextMoveMaker;
    /** The pawn that can be captured via en passant, if any. */
    private Pawn enPassantPawn;
    /** The Zobrist hash value for the board being built. */
    private long zobristHash;
    /** The halfmove clock for the board being built, defaulting to zero. */
    private int halfMoveClock;
    /** The ply count for the board being built, defaulting to zero for a freshly started game. */
    private int plyCount;

    /*** Constructs a new Builder instance with empty configurations. */
    public Builder() {
      this.BoardConfigurations = new Piece[BoardUtils.NUM_TILES];
      this.zobristHash = 0;
    }

    /**
     * Sets a piece at a specific position on the board being built.
     *
     * @param piece The piece to be placed on the board at its specified position.
     * @return The current builder instance to continue configuring the board.
     */
    public Builder setPiece(final Piece piece) {
      this.BoardConfigurations[piece.getPiecePosition()] = piece;
      return this;
    }

    /**
     * Sets the alliance of the player who will make the next move.
     *
     * @param nextMoveMaker The alliance of the player who has the next move.
     */
    public void setMoveMaker(final Alliance nextMoveMaker) {
      this.nextMoveMaker = nextMoveMaker;
    }

    /**
     * Sets the en passant pawn for the current board state.
     *
     * @param enPassantPawn The pawn that can be captured en passant in the current move.
     */
    public void setEnPassantPawn(final Pawn enPassantPawn) {
      this.enPassantPawn = enPassantPawn;
    }

    /**
     * Sets the Zobrist hash value for the board being built.
     *
     * @param zobristHash The Zobrist hash value.
     */
    public void setZobristHash(final long zobristHash) {
      this.zobristHash = zobristHash;
    }

    /**
     * Sets the halfmove clock for the board being built, for the fifty-move rule. Defaults to
     * zero when not set, which is correct for a freshly started game but should be supplied
     * when building a position parsed from FEN or otherwise reached mid-game.
     *
     * @param halfMoveClock The number of plies since the last pawn move or capture.
     */
    public void setHalfMoveClock(final int halfMoveClock) {
      this.halfMoveClock = halfMoveClock;
    }

    /**
     * Sets the ply count for the board being built, for {@link Board#getPlyCount()}. Defaults
     * to zero, which is correct for a freshly started game.
     *
     * @param plyCount The number of plies already played.
     */
    public void setPlyCount(final int plyCount) {
      this.plyCount = plyCount;
    }

    /**
     * Constructs and returns a new instance of the Board class based on the configured parameters.
     *
     * @return A new Board instance with the specified configurations.
     */
    public Board build() {
      return new Board(this);
    }
  }
}