package engine.forUCI;

import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forPlayer.forAI.AlphaBeta;
import engine.forTesting.FenParser;
import engine.forTesting.Perft;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * The UciEngine class drives the search from the Universal Chess Interface, the text protocol match
 * runners use to talk to a chess engine over a pipe. It reads commands from standard input, holds
 * the position they describe, and answers a search command with the move the search returns. It
 * carries no chess logic beyond resolving notation into a move and back.
 * <p>
 * Only the commands a self play match needs are implemented: uci, isready, setoption for the Hash
 * and Threads options, ucinewgame, position, go with a node, move time, depth or clock limit, and
 * quit.
 * Anything else is ignored, as the protocol requires. There is no stop command and no infinite
 * search, since the command loop is never reading while a search is running.
 * <p>
 * Standard output is the protocol channel and carries nothing but protocol lines. This class holds
 * a private handle to it and redirects {@link System#out} to standard error before anything else
 * runs, so that the per iteration report {@link AlphaBeta} prints and any other incidental output
 * land in the log rather than in the protocol.
 * <p>
 * Positions are built by replaying moves rather than by parsing notation, so the notation this
 * class accepts is exactly the notation {@link Perft#longAlgebraicNotation(Move, Board)} produces.
 * A command that names an illegal move or a malformed position is a fault in the caller rather than
 * a position this engine can play, so it is reported to the log and the process exits with status
 * two rather than searching a position nobody asked for.
 *
 * @author Aaron Ho
 */
public class UciEngine {

  /** The name this engine reports in answer to the uci command. */
  private static final String ENGINE_NAME = "capstone-chess";

  /** The author this engine reports in answer to the uci command. */
  private static final String ENGINE_AUTHOR = "Aaron Ho";

  /** The transposition table size in megabytes used until the Hash option sets another. */
  private static final int DEFAULT_TABLE_SIZE_MB = 256;

  /** The largest transposition table size in megabytes the Hash option accepts. */
  private static final int MAXIMUM_TABLE_SIZE_MB = 4096;

  /** The search thread count used until the Threads option sets another. */
  private static final int DEFAULT_THREAD_COUNT = 1;

  /** The largest search thread count the Threads option accepts. */
  private static final int MAXIMUM_THREAD_COUNT = 64;

  /** The iterative deepening ceiling used when a node or move time limit ends the search. */
  private static final int LIMIT_DEPTH = 64;

  /** The node limit used by a go command that names no limit of its own. */
  private static final long DEFAULT_NODE_LIMIT = 100_000;

  /** The moves a clock is divided over when the go command names no movestogo. */
  private static final int DEFAULT_MOVES_TO_GO = 30;

  /** The milliseconds of the remaining clock a search never plans to spend. */
  private static final long CLOCK_SAFETY_MARGIN_MILLIS = 50;

  /** The long algebraic notation reported when a search returns no move. */
  private static final String NULL_MOVE_NOTATION = "0000";

  /** The exit status used when a command names an illegal move or a malformed position. */
  private static final int PROTOCOL_FAULT_STATUS = 2;

  /** The channel protocol lines are written to, held separately from {@link System#out}. */
  private final PrintStream protocol;

  /** The position the next search runs from. */
  private Board board = Board.createStandardBoard();

  /** The engine the next search runs on, constructed on first use and replaced by a new game. */
  private AlphaBeta engine;

  /** The transposition table size in megabytes the next engine is constructed with. */
  private int tableSizeMB = DEFAULT_TABLE_SIZE_MB;

  /** The search thread count the next engine is constructed with. */
  private int threadCount = DEFAULT_THREAD_COUNT;

  /**
   * Constructs an adapter writing its protocol lines to the given channel.
   *
   * @param protocol The channel protocol lines are written to.
   */
  public UciEngine(final PrintStream protocol) {
    this.protocol = protocol;
  }

  /**
   * Runs the command loop on standard input. Standard output is captured as the protocol channel
   * and then redirected to standard error before any command is read.
   *
   * @param args The command line arguments, which are ignored.
   * @throws IOException If standard input cannot be read.
   */
  public static void main(final String[] args) throws IOException {
    final PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    System.setOut(System.err);
    try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
      new UciEngine(protocol).run(input);
    }
  }

  /**
   * Reads and answers commands until the quit command arrives or the input ends.
   *
   * @param input The channel commands are read from.
   * @throws IOException If the input cannot be read.
   */
  public void run(final BufferedReader input) throws IOException {
    String line;
    while ((line = input.readLine()) != null) {
      final String command = line.trim();
      if (command.isEmpty()) {
        continue;
      }
      try {
        if (!handle(command.split("\\s+"))) {
          break;
        }
      } catch (final IllegalArgumentException exception) {
        System.out.println("Protocol fault: " + exception.getMessage());
        System.out.println("Command: " + command);
        shutdown();
        System.exit(PROTOCOL_FAULT_STATUS);
      }
    }
    shutdown();
  }

  /**
   * Answers one command.
   *
   * @param tokens The whitespace separated tokens of the command.
   * @return True to keep reading commands, false to stop.
   * @throws IllegalArgumentException If the command names an illegal move or a malformed position.
   */
  private boolean handle(final String[] tokens) {
    switch (tokens[0]) {
      case "uci" -> identify();
      case "isready" -> this.protocol.println("readyok");
      case "setoption" -> setOption(tokens);
      case "ucinewgame" -> newGame();
      case "position" -> setPosition(tokens);
      case "go" -> go(tokens);
      case "quit" -> {
        return false;
      }
      default -> {
      }
    }
    return true;
  }

  /**
   * Reports the engine identity and the supported options.
   */
  private void identify() {
    this.protocol.println("id name " + ENGINE_NAME);
    this.protocol.println("id author " + ENGINE_AUTHOR);
    this.protocol.println("option name Hash type spin default " + DEFAULT_TABLE_SIZE_MB +
            " min 1 max " + MAXIMUM_TABLE_SIZE_MB);
    this.protocol.println("option name Threads type spin default " + DEFAULT_THREAD_COUNT +
            " min 1 max " + MAXIMUM_THREAD_COUNT);
    this.protocol.println("uciok");
  }

  /**
   * Records an option value. The value is applied to the engine constructed by the next new game,
   * not to an engine already constructed. Unrecognised options are ignored.
   *
   * @param tokens The whitespace separated tokens of the command.
   */
  private void setOption(final String[] tokens) {
    final int nameIndex = indexOf(tokens, "name");
    final int valueIndex = indexOf(tokens, "value");
    if (nameIndex < 0 || valueIndex < nameIndex + 2 || valueIndex + 1 >= tokens.length) {
      return;
    }
    final String name = String.join(" ",
            Arrays.copyOfRange(tokens, nameIndex + 1, valueIndex));
    final int value = parseNumber(tokens[valueIndex + 1], name);
    if ("Hash".equalsIgnoreCase(name)) {
      this.tableSizeMB = clamp(value, 1, MAXIMUM_TABLE_SIZE_MB);
    } else if ("Threads".equalsIgnoreCase(name)) {
      this.threadCount = clamp(value, 1, MAXIMUM_THREAD_COUNT);
    }
  }

  /**
   * Discards the current engine and constructs a new one, which clears the transposition table and
   * every heuristic table the search carries between moves.
   */
  private void newGame() {
    shutdown();
    this.engine = new AlphaBeta(LIMIT_DEPTH, this.tableSizeMB, this.threadCount);
  }

  /**
   * Sets the position the next search runs from, either the standard starting position or a
   * position in Forsyth-Edwards Notation, followed by the moves given after the moves keyword.
   *
   * @param tokens The whitespace separated tokens of the command.
   * @throws IllegalArgumentException If the notation is malformed or a move is not legal in the
   *                                  position it is played from.
   */
  private void setPosition(final String[] tokens) {
    final int movesIndex = indexOf(tokens, "moves");
    final int end = movesIndex < 0 ? tokens.length : movesIndex;
    if (tokens.length < 2) {
      throw new IllegalArgumentException("A position command names no position");
    }
    if ("startpos".equals(tokens[1])) {
      this.board = Board.createStandardBoard();
    } else if ("fen".equals(tokens[1])) {
      this.board = FenParser.parse(String.join(" ",
              Arrays.copyOfRange(tokens, 2, end)));
    } else {
      throw new IllegalArgumentException("A position command names neither startpos nor fen");
    }
    for (int index = movesIndex + 1; movesIndex >= 0 && index < tokens.length; index++) {
      final Move move = resolve(tokens[index]);
      if (move == null) {
        throw new IllegalArgumentException("Move " + (index - movesIndex) + " is not legal: " +
                tokens[index]);
      }
      this.board.makeMove(move);
    }
  }

  /**
   * Searches the current position and reports the depth reached, the score, and the best move.
   * Every limit the command names holds, and the search is stopped by whichever is reached first:
   * nodes bounds the positions evaluated, movetime bounds the milliseconds spent, and depth bounds
   * the iterations. The clock of the side to move, wtime or btime, bounds the milliseconds spent to
   * the share {@link #clockAllocation(long, long, int)} gives it, using that side's increment, winc
   * or binc, and movestogo when the command names them. The other side's clock is ignored. A command
   * naming no limit searches under the default node limit. A search that returns no move is reported
   * as the null move with no depth or score, which is what a terminal position produces. A score
   * holding a checkmate is reported as a distance to mate rather than in centipawns.
   *
   * @param tokens The whitespace separated tokens of the command.
   * @throws IllegalArgumentException If a limit is not a number.
   */
  private void go(final String[] tokens) {
    final boolean white = this.board.currentPlayer().getAlliance().isWhite();
    final int nodesIndex = indexOf(tokens, "nodes");
    final int moveTimeIndex = indexOf(tokens, "movetime");
    final int depthIndex = indexOf(tokens, "depth");
    final int clockIndex = indexOf(tokens, white ? "wtime" : "btime");
    final int incrementIndex = indexOf(tokens, white ? "winc" : "binc");
    final int movesToGoIndex = indexOf(tokens, "movestogo");
    long nodeLimit = AlphaBeta.UNLIMITED_NODES;
    long timeLimitMillis = AlphaBeta.UNLIMITED_TIME;
    int searchDepth = LIMIT_DEPTH;
    boolean limited = depthIndex >= 0;
    if (nodesIndex >= 0 && nodesIndex + 1 < tokens.length) {
      nodeLimit = parseLimit(tokens[nodesIndex + 1], "nodes");
      limited = true;
    }
    if (moveTimeIndex >= 0 && moveTimeIndex + 1 < tokens.length) {
      timeLimitMillis = parseLimit(tokens[moveTimeIndex + 1], "movetime");
      limited = true;
    }
    if (depthIndex >= 0 && depthIndex + 1 < tokens.length) {
      searchDepth = parseNumber(tokens[depthIndex + 1], "depth");
    }
    if (clockIndex >= 0 && clockIndex + 1 < tokens.length) {
      final long remainingMillis = parseLimit(tokens[clockIndex + 1], tokens[clockIndex]);
      long incrementMillis = 0;
      if (incrementIndex >= 0 && incrementIndex + 1 < tokens.length) {
        incrementMillis = parseLimit(tokens[incrementIndex + 1], tokens[incrementIndex]);
      }
      int movesToGo = DEFAULT_MOVES_TO_GO;
      if (movesToGoIndex >= 0 && movesToGoIndex + 1 < tokens.length) {
        movesToGo = Math.max(1, parseNumber(tokens[movesToGoIndex + 1], "movestogo"));
      }
      timeLimitMillis = Math.min(timeLimitMillis,
              clockAllocation(remainingMillis, incrementMillis, movesToGo));
      limited = true;
    }
    if (!limited) {
      nodeLimit = DEFAULT_NODE_LIMIT;
    }

    final Move bestMove = engine().execute(this.board, searchDepth, nodeLimit, timeLimitMillis);
    final String notation = notationOf(bestMove);
    if (!NULL_MOVE_NOTATION.equals(notation)) {
      final double score = engine().getLastScore();
      final double relativeScore = white ? score : -score;
      this.protocol.println("info depth " + engine().getLastDepth() + " score " +
              scoreOf(relativeScore));
    }
    this.protocol.println("bestmove " + notation);
  }

  /**
   * Returns the milliseconds a search under a game clock may spend on this move: an even share of
   * the remaining time over the moves left to play, plus three quarters of the increment. The
   * result never exceeds the remaining time less {@link #CLOCK_SAFETY_MARGIN_MILLIS}, and is at
   * least one millisecond even when the clock is empty or negative.
   *
   * @param remainingMillis The milliseconds left on the clock of the side to move.
   * @param incrementMillis The milliseconds added to that clock after each move.
   * @param movesToGo The moves left to play before the clock is next replenished, at least one.
   * @return The time limit for this move in milliseconds.
   */
  private static long clockAllocation(final long remainingMillis, final long incrementMillis,
                                      final int movesToGo) {
    final long share = remainingMillis / movesToGo + incrementMillis * 3 / 4;
    return Math.max(1, Math.min(share, remainingMillis - CLOCK_SAFETY_MARGIN_MILLIS));
  }

  /**
   * Renders a root score as the score field of a protocol line. A score at or beyond the mate
   * threshold is reported as the number of moves to the mate, positive when the side to move
   * delivers it and negative when the side to move is mated. Any other score is reported in
   * centipawns.
   *
   * @param relativeScore The root score from the point of view of the side to move.
   * @return The score field, holding either a distance to mate or a score in centipawns.
   */
  private static String scoreOf(final double relativeScore) {
    final double magnitude = Math.abs(relativeScore);
    if (magnitude < AlphaBeta.MATE_THRESHOLD) {
      return "cp " + Math.round(relativeScore);
    }
    final long plies = Math.round(AlphaBeta.MATE_VALUE - magnitude);
    final long moves = (plies + 1) / 2;
    return "mate " + (relativeScore < 0 ? -moves : moves);
  }

  /**
   * Returns the engine the next search runs on, constructing one if no new game has been started.
   *
   * @return The engine the next search runs on.
   */
  private AlphaBeta engine() {
    if (this.engine == null) {
      this.engine = new AlphaBeta(LIMIT_DEPTH, this.tableSizeMB, this.threadCount);
    }
    return this.engine;
  }

  /**
   * Shuts the current engine down and discards it. Does nothing if no engine has been constructed.
   */
  private void shutdown() {
    if (this.engine != null) {
      this.engine.shutdown();
      this.engine = null;
    }
  }

  /**
   * Finds the legal move the given long algebraic notation names in the current position. Each
   * legal move is applied and reversed so that its notation can be read from the position it
   * produces, which is what distinguishes the promotion variants of one move from each other. The
   * board is restored before this method returns.
   *
   * @param notation The long algebraic notation naming the move.
   * @return The move the notation names, or null if no legal move in this position carries it.
   */
  private Move resolve(final String notation) {
    for (final Move move : this.board.currentPlayer().getLegalMoves()) {
      if (!this.board.isLegal(move)) {
        continue;
      }
      this.board.makeMove(move);
      final boolean matches;
      try {
        matches = Perft.longAlgebraicNotation(move, this.board).equals(notation);
      } finally {
        this.board.unmakeMove();
      }
      if (matches) {
        return move;
      }
    }
    return null;
  }

  /**
   * Renders a move in long algebraic notation. The move is applied and reversed so that the
   * notation can be read from the position it produces. The board is restored before this method
   * returns.
   *
   * @param move The move to render.
   * @return The long algebraic notation of the move, or the null move notation if there is none.
   */
  private String notationOf(final Move move) {
    if (move == null || move == Move.MoveFactory.getNullMove()) {
      return NULL_MOVE_NOTATION;
    }
    this.board.makeMove(move);
    try {
      return Perft.longAlgebraicNotation(move, this.board);
    } finally {
      this.board.unmakeMove();
    }
  }

  /**
   * Finds the position of a keyword among the tokens of a command.
   *
   * @param tokens The whitespace separated tokens of the command.
   * @param keyword The keyword to find.
   * @return The index of the first occurrence of the keyword, or minus one if it is absent.
   */
  private static int indexOf(final String[] tokens, final String keyword) {
    for (int index = 0; index < tokens.length; index++) {
      if (keyword.equals(tokens[index])) {
        return index;
      }
    }
    return -1;
  }

  /**
   * Reads a whole number from a command token, allowing values beyond the range of an int.
   *
   * @param token The token to read.
   * @param field The name of the field the token belongs to, used in the fault message.
   * @return The number the token holds.
   * @throws IllegalArgumentException If the token is not a whole number.
   */
  private static long parseLimit(final String token, final String field) {
    try {
      return Long.parseLong(token);
    } catch (final NumberFormatException exception) {
      throw new IllegalArgumentException("The " + field + " field is not a number: " + token);
    }
  }

  /**
   * Reads a whole number from a command token.
   *
   * @param token The token to read.
   * @param field The name of the field the token belongs to, used in the fault message.
   * @return The number the token holds.
   * @throws IllegalArgumentException If the token is not a whole number.
   */
  private static int parseNumber(final String token, final String field) {
    try {
      return Integer.parseInt(token);
    } catch (final NumberFormatException exception) {
      throw new IllegalArgumentException("The " + field + " field is not a number: " + token);
    }
  }

  /**
   * Brings a value within the given range.
   *
   * @param value The value to bring within range.
   * @param minimum The smallest value allowed.
   * @param maximum The largest value allowed.
   * @return The value, or the nearer bound if it lies outside the range.
   */
  private static int clamp(final int value, final int minimum, final int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}