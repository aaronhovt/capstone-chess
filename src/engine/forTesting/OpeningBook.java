package engine.forTesting;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.Move;
import engine.forNetwork.VectorModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The OpeningBook class reads the opening book used to start self play games and replays its lines
 * on a board. A book line is a sequence of moves from the standard starting position rather than a
 * position in Forsyth-Edwards Notation, so replaying it leaves a board carrying the same first move
 * status, castled status, repetition counts, and ply count that a game reaching the position would
 * carry. Loading the same position through {@link FenParser} cannot reproduce all of that, since
 * Forsyth-Edwards Notation does not record whether a knight, bishop, or queen has moved.
 * <p>
 * Each book line holds the moves in long algebraic notation, the ECO code, and the opening name,
 * separated by tabs. Blank lines and lines beginning with a hash are ignored, which is how the book
 * carries its provenance. Moves are resolved against the board they are played on rather than
 * parsed, so the notation this class accepts is exactly the notation
 * {@link Perft#longAlgebraicNotation(Move, Board)} produces.
 * <p>
 * Run from the command line, this class verifies a book: every line must replay legally from the
 * standard starting position, leave White to move in a position that is neither check nor terminal,
 * and reach a position no other line reaches. This class is designed as a non-instantiable utility
 * class with static methods.
 *
 * @author Aaron Ho
 */
public class OpeningBook {

  /** The path the book is read from when no other path is given. */
  public static final String DEFAULT_BOOK_PATH = "book/openings.txt";

  /** The prefix marking a line of the book file as a comment. */
  private static final String COMMENT_PREFIX = "#";

  /** The separator between the fields of a book line. */
  private static final String FIELD_SEPARATOR = "\t";

  /** The number of tab separated fields a book line holds. */
  private static final int FIELD_COUNT = 3;

  /** The command line flag requesting the usage message. */
  private static final String HELP_FLAG = "--help";

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private OpeningBook() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * Verifies a book from the command line. The book is read from the path given as the only
   * argument, or from the default path when no argument is given.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    VectorModule.relaunchIfMissing(OpeningBook.class, args);
    String path = DEFAULT_BOOK_PATH;
    for (final String argument : args) {
      if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else if (argument.startsWith("-")) {
        System.out.println("Unrecognised argument: " + argument);
        printUsage();
        return;
      } else {
        path = argument;
      }
    }
    System.exit(verify(Path.of(path)) ? 0 : 1);
  }

  /**
   * Reads every opening the book at the given path holds, in the order the file lists them.
   *
   * @param path The path of the book file.
   * @return The openings the book holds.
   * @throws IOException If the book file cannot be read.
   * @throws IllegalArgumentException If a line does not hold exactly three tab separated fields
   *                                  or holds no moves.
   */
  public static List<Opening> load(final Path path) throws IOException {
    final List<String> lines = Files.readAllLines(path);
    final List<Opening> openings = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      final String line = lines.get(index);
      if (line.isBlank() || line.startsWith(COMMENT_PREFIX)) {
        continue;
      }
      final String[] fields = line.split(FIELD_SEPARATOR, -1);
      if (fields.length != FIELD_COUNT) {
        throw new IllegalArgumentException("Line " + (index + 1) + " of " + path +
                " holds " + fields.length + " tab separated fields rather than " + FIELD_COUNT);
      }
      final String moves = fields[0].trim();
      if (moves.isEmpty()) {
        throw new IllegalArgumentException("Line " + (index + 1) + " of " + path + " holds no moves");
      }
      openings.add(new Opening(List.of(moves.split("\\s+")), fields[1].trim(), fields[2].trim()));
    }
    return openings;
  }

  /**
   * Replays the given opening from the standard starting position.
   *
   * @param opening The opening to replay.
   * @return The board the opening reaches, carrying the opening on its undo stack.
   * @throws IllegalArgumentException If a move of the opening is not legal in the position it is
   *                                  played from.
   */
  public static Board play(final Opening opening) {
    final Board board = Board.createStandardBoard();
    for (int ply = 0; ply < opening.moves().size(); ply++) {
      final String notation = opening.moves().get(ply);
      final Move move = resolve(board, notation);
      if (move == null) {
        throw new IllegalArgumentException("Move " + (ply + 1) + " of " + opening.eco() + " " +
                opening.name() + " is not legal: " + notation);
      }
      board.makeMove(move);
    }
    return board;
  }

  /**
   * Finds the legal move the given long algebraic notation names in the given position. Each legal
   * move is applied and reversed so that its notation can be read from the position it produces,
   * which is what distinguishes the promotion variants of one move from each other. The board is
   * restored before this method returns.
   *
   * @param board The position the move is played from.
   * @param notation The long algebraic notation naming the move.
   * @return The move the notation names, or null if no legal move in this position carries it.
   */
  public static Move resolve(final Board board, final String notation) {
    for (final Move move : board.currentPlayer().getLegalMoves()) {
      if (!board.isLegal(move)) {
        continue;
      }
      board.makeMove(move);
      final boolean matches;
      try {
        matches = Perft.longAlgebraicNotation(move, board).equals(notation);
      } finally {
        board.unmakeMove();
      }
      if (matches) {
        return move;
      }
    }
    return null;
  }

  /**
   * Replays every opening in the book at the given path and prints a report of the results. An
   * opening fails verification if a move of it is not legal, if it does not leave White to move,
   * if it leaves the player to move in check or with no legal move, or if it reaches a position
   * another opening already reached.
   *
   * @param path The path of the book file.
   * @return True if every opening passed, false otherwise.
   */
  public static boolean verify(final Path path) {
    final List<Opening> openings;
    try {
      openings = load(path);
    } catch (final IOException | IllegalArgumentException exception) {
      System.out.println("The book could not be read: " + exception.getMessage());
      return false;
    }
    System.out.printf("Opening book: %d openings read from %s%n%n", openings.size(), path);

    final Map<Long, Opening> positions = new HashMap<>();
    final Map<Integer, Integer> plyCounts = new TreeMap<>();
    int failures = 0;
    for (final Opening opening : openings) {
      final String fault = check(opening, positions);
      if (fault == null) {
        plyCounts.merge(opening.moves().size(), 1, Integer::sum);
      } else {
        failures++;
        System.out.printf("%s %s: %s%n", opening.eco(), opening.name(), fault);
      }
    }
    if (failures > 0) {
      System.out.println();
    }
    for (final Map.Entry<Integer, Integer> entry : plyCounts.entrySet()) {
      System.out.printf("%4d opening%s of %d plies%n", entry.getValue(),
              entry.getValue() == 1 ? "" : "s", entry.getKey());
    }
    System.out.printf("%n%d of %d openings verified, %d distinct position%s%n",
            openings.size() - failures, openings.size(), positions.size(),
            positions.size() == 1 ? "" : "s");
    return failures == 0;
  }

  /**
   * Replays one opening and reports what is wrong with it, recording the position it reaches
   * against the positions the openings before it reached.
   *
   * @param opening The opening to check.
   * @param positions The position reached by each opening checked so far, keyed by Zobrist hash.
   * @return A description of the fault found, or null if the opening passed.
   */
  private static String check(final Opening opening, final Map<Long, Opening> positions) {
    final Board board;
    try {
      board = play(opening);
    } catch (final IllegalArgumentException exception) {
      return exception.getMessage();
    }
    if (board.currentPlayer().getAlliance() != Alliance.WHITE) {
      return "leaves Black to move after " + opening.moves().size() + " plies";
    }
    if (board.currentPlayer().isInCheck()) {
      return "leaves White in check";
    }
    if (Perft.countLegalMoves(board) == 0) {
      return "leaves White with no legal move";
    }
    final Opening duplicate = positions.putIfAbsent(board.getZobristHash(), opening);
    if (duplicate != null) {
      return "reaches the same position as " + duplicate.eco() + " " + duplicate.name();
    }
    return null;
  }

  /** Prints the command line usage of this class. */
  private static void printUsage() {
    System.out.println("""
            Usage:
              OpeningBook                 verify the book at book/openings.txt
              OpeningBook <path>          verify the book at the given path
              OpeningBook --help          print this message

            The default path is relative to the working directory.

            A book line holds the moves of one opening in long algebraic notation, the ECO code,
            and the opening name, separated by tabs. Blank lines and lines beginning with a hash
            are ignored.""");
  }

  /**
   * The Opening record pairs the moves of one book line with the ECO code and name recorded
   * against it.
   *
   * @param moves The moves of the opening, in long algebraic notation.
   * @param eco The ECO code of the opening.
   * @param name The name of the opening.
   */
  public record Opening(List<String> moves, String eco, String name) {

    /**
     * Constructs an opening, holding its moves in a list that cannot be modified afterwards.
     *
     * @param moves The moves of the opening, in long algebraic notation.
     * @param eco The ECO code of the opening.
     * @param name The name of the opening.
     */
    public Opening {
      moves = List.copyOf(moves);
    }
  }
}