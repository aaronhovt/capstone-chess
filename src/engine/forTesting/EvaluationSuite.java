package engine.forTesting;

import engine.forBoard.Board;
import engine.forPlayer.forAI.BoardEvaluator;
import engine.forPlayer.forAI.EndgameBoardEvaluator;
import engine.forPlayer.forAI.MiddlegameBoardEvaluator;
import engine.forPlayer.forAI.OpeningGameEvaluator;

import java.util.ArrayList;
import java.util.List;

/**
 * The EvaluationSuite class measures the engine's evaluators against a fixed set of positions and
 * reports four quantities: the raw score of each position, the spread between the highest and
 * lowest score any evaluator gives a position, the residual left when a position is compared with
 * its colour-mirrored twin, and the difference between evaluating a position with white to move
 * and with black to move.
 * <p>
 * No search is run. Every evaluator is called directly rather than through
 * {@link engine.forPlayer.forAI.GameStateDetector}, so each number is attributable to a named
 * evaluator and the phase detector is not part of the measurement. Every evaluator is run against
 * every position, including positions of a phase it would not normally be given, so that a defect
 * can be located in one evaluator or seen to be shared.
 * <p>
 * Only the mirror check carries a verdict. A balance score, an agreement spread, and a side to move
 * difference are reported without judgement, since no threshold has been established for any of
 * them. The mirror check is compared against a tolerance rather than for exact equality, because a
 * mirrored position sums the same terms in a different order.
 * <p>
 * This class is designed to be run from the command line and its entry point returns a non-zero
 * exit status when any position fails the mirror check or cannot be prepared.
 *
 * @author Aaron Ho
 */
@SuppressWarnings("JavaPrintToLogpoint")
public class EvaluationSuite {

  /** The largest residual, in centipawns, a position may leave and still be reported as symmetric. */
  private static final double MIRROR_TOLERANCE = 1.0E-6;

  /** The command line flag requesting the full per position detail. */
  private static final String VERBOSE_FLAG = "--verbose";

  /** The command line flag requesting usage information. */
  private static final String HELP_FLAG = "--help";

  /** The index of the piece placement field of a Forsyth-Edwards Notation string. */
  private static final int PLACEMENT_FIELD = 0;

  /** The index of the side to move field of a Forsyth-Edwards Notation string. */
  private static final int MOVER_FIELD = 1;

  /** The index of the castling rights field of a Forsyth-Edwards Notation string. */
  private static final int CASTLING_FIELD = 2;

  /** The index of the en passant target field of a Forsyth-Edwards Notation string. */
  private static final int EN_PASSANT_FIELD = 3;

  /** The side to move field naming white. */
  private static final String WHITE_TO_MOVE = "w";

  /** The side to move field naming black. */
  private static final String BLACK_TO_MOVE = "b";

  /** The field value meaning that no castling right or en passant target is present. */
  private static final String EMPTY_FIELD = "-";

  /** The separator between ranks in the piece placement field. */
  private static final String RANK_SEPARATOR = "/";

  /** The separator between the fields of a Forsyth-Edwards Notation string. */
  private static final String FIELD_SEPARATOR = " ";

  /** The castling rights in the order a notation string lists them. */
  private static final String CASTLING_ORDER = "KQkq";

  /** The rank of an en passant target square when white is to move. */
  private static final char WHITE_EN_PASSANT_RANK = '6';

  /** The rank of an en passant target square when black is to move. */
  private static final char BLACK_EN_PASSANT_RANK = '3';

  /** The format of a table header naming the three evaluator columns. */
  private static final String HEADER_FORMAT = "  %-32s%12s%12s%12s%n";

  /** The format of a table header naming a single value column. */
  private static final String AGREEMENT_HEADER_FORMAT = "  %-32s%12s%n";

  /** The format of a table row carrying the spread between the highest and lowest evaluator score. */
  private static final String AGREEMENT_ROW_FORMAT = "  %-32s%12.2f%n";

  /** The format of a table row carrying a number for each of the three evaluators. */
  private static final String SCORE_ROW_FORMAT = "  %-32s%12.2f%12.2f%12.2f%n";

  /** The format of a mirror check row, whose numbers are followed by a verdict. */
  private static final String MIRROR_ROW_FORMAT = "  %-32s%12.2f%12.2f%12.2f  %s%n";

  /** The format of a table row carrying an explanation in place of numbers. */
  private static final String NOTE_ROW_FORMAT = "  %-32s%s%n";

  /** The format of a line of verbose detail. */
  private static final String DETAIL_FORMAT = "      %s%n";

  /** The evaluators measured, each paired with the name used for its column. */
  private static final List<NamedEvaluator> EVALUATORS = List.of(
          new NamedEvaluator("opening", OpeningGameEvaluator.get()),
          new NamedEvaluator("middlegame", MiddlegameBoardEvaluator.get()),
          new NamedEvaluator("endgame", EndgameBoardEvaluator.get()));

  /**
   * The positions the suite measures. Each is a position whose evaluation is expected to be close
   * to level, so that a large score is a finding rather than a description of the position. The
   * openings are the two that reported implausible scores in a self play match, and the endgames
   * are included so that the endgame evaluator is measured on positions it would really be given.
   */
  private static final List<EvaluationPosition> STANDARD_POSITIONS = List.of(
          new EvaluationPosition("Start position",
                  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
          new EvaluationPosition("Queen's Gambit Declined, 5.Bg5",
                  "rnbqkb1r/ppp2ppp/4pn2/6B1/2pP4/2N2N2/PP2PPPP/R2QKB1R b KQkq - 1 5"),
          new EvaluationPosition("Pirc Defence, 4.Bg5",
                  "rnbqkb1r/ppp1pp1p/3p1np1/6B1/3PP3/2N5/PPP2PPP/R2QKBNR b KQkq - 1 4"),
          new EvaluationPosition("Four Knights, symmetrical",
                  "r1bq1rk1/ppp2ppp/2np1n2/1B2p3/1b2P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 7"),
          new EvaluationPosition("Ruy Lopez, closed middlegame",
                  "r1b2rk1/2q1bppp/p2p1n2/npp1p3/3PP3/2P2N1P/PPB2PP1/RNBQR1K1 w - - 1 12"),
          new EvaluationPosition("Rook endgame, three pawns each",
                  "8/5ppp/4k3/8/8/4K3/5PPP/2R1r3 w - - 0 40"),
          new EvaluationPosition("King and pawn endgame",
                  "8/5p2/4k3/6p1/4K3/8/5PP1/8 w - - 0 45"),
          new EvaluationPosition("Bishop against knight endgame",
                  "8/4kp2/3p2p1/2n4p/7P/4B1P1/4KP2/8 w - - 0 40"));

  /**
   * Runs the suite from the command line. With no arguments a summary of the three checks is
   * printed, and the verbose flag adds the notation of every position and variant along with the
   * scores the mirror check compares.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    boolean verbose = false;
    for (final String argument : args) {
      if (VERBOSE_FLAG.equals(argument)) {
        verbose = true;
      } else if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else {
        System.out.println("Unrecognised argument: " + argument);
        printUsage();
        return;
      }
    }
    System.exit(run(verbose) ? 0 : 1);
  }

  /**
   * Measures every position in the suite and prints a report of the three checks.
   *
   * @param verbose Whether to print the notation of every position and variant along with the
   *                scores the mirror check compares.
   * @return True if every position was prepared and found symmetric, false otherwise.
   */
  public static boolean run(final boolean verbose) {
    System.out.printf("Evaluation suite: %d positions against %d evaluators, no search%n%n",
            STANDARD_POSITIONS.size(), EVALUATORS.size());

    final List<PreparedPosition> prepared = new ArrayList<>(STANDARD_POSITIONS.size());
    for (final EvaluationPosition position : STANDARD_POSITIONS) {
      prepared.add(prepare(position));
    }

    reportBalance(prepared, verbose);
    reportAgreement(prepared, verbose);
    final int symmetric = reportMirrorSymmetry(prepared, verbose);
    reportSideToMove(prepared, verbose);

    System.out.printf("%d of %d positions symmetric under every evaluator%n",
            symmetric, STANDARD_POSITIONS.size());
    return symmetric == STANDARD_POSITIONS.size();
  }

  /**
   * Prints the score every evaluator gives each position. No verdict is reached, since no threshold
   * has been established for what a level position should score.
   *
   * @param prepared The positions to report on.
   * @param verbose Whether to print the notation of each position.
   */
  private static void reportBalance(final List<PreparedPosition> prepared, final boolean verbose) {
    System.out.println("Balance, the score of each position");
    printHeader();
    for (final PreparedPosition position : prepared) {
      if (position.board() == null) {
        System.out.printf(NOTE_ROW_FORMAT, position.name(), position.note());
        continue;
      }
      printScoreRow(position.name(), scores(position.board()));
      if (verbose) {
        System.out.printf(DETAIL_FORMAT, position.fen());
      }
    }
    System.out.println();
  }

  /**
   * Prints the spread between the highest and lowest score any evaluator gives each position. The
   * three evaluators are not calibrated onto a common scale, so this number is the size of that
   * miscalibration at the position rather than a defect in any one evaluator. No verdict is
   * reached, since no threshold has been established for how far the evaluators may agree.
   *
   * @param prepared The positions to report on.
   * @param verbose Whether to print which evaluator gave the highest and lowest score.
   */
  private static void reportAgreement(final List<PreparedPosition> prepared, final boolean verbose) {
    System.out.println("Agreement, the spread between the highest and lowest evaluator score");
    System.out.printf(AGREEMENT_HEADER_FORMAT, "position", "spread");
    for (final PreparedPosition position : prepared) {
      if (position.board() == null) {
        System.out.printf(NOTE_ROW_FORMAT, position.name(), position.note());
        continue;
      }
      final double[] values = scores(position.board());
      int highest = 0;
      int lowest = 0;
      for (int index = 1; index < values.length; index++) {
        if (values[index] > values[highest]) {
          highest = index;
        }
        if (values[index] < values[lowest]) {
          lowest = index;
        }
      }
      System.out.printf(AGREEMENT_ROW_FORMAT, position.name(), values[highest] - values[lowest]);
      if (verbose) {
        System.out.printf(DETAIL_FORMAT, String.format("%s %.2f, %s %.2f",
                EVALUATORS.get(highest).name(), values[highest],
                EVALUATORS.get(lowest).name(), values[lowest]));
      }
    }
    System.out.println();
  }

  /**
   * Prints the residual each position leaves when its score is added to the score of its
   * colour-mirrored twin, which is zero for an evaluation that treats both colours alike, and
   * returns how many positions were within tolerance under every evaluator.
   *
   * @param prepared The positions to report on.
   * @param verbose Whether to print the mirrored notation and the pair of scores being compared.
   * @return The number of positions found symmetric under every evaluator.
   */
  private static int reportMirrorSymmetry(final List<PreparedPosition> prepared,
                                          final boolean verbose) {
    System.out.println("Mirror symmetry, the residual of each position added to its mirror");
    printHeader();
    int symmetric = 0;
    for (final PreparedPosition position : prepared) {
      if (position.board() == null || position.mirrorBoard() == null) {
        System.out.printf(NOTE_ROW_FORMAT, position.name(), position.note());
        continue;
      }
      final double[] original = scores(position.board());
      final double[] mirrored = scores(position.mirrorBoard());
      final double[] residuals = new double[EVALUATORS.size()];
      boolean withinTolerance = true;
      for (int index = 0; index < EVALUATORS.size(); index++) {
        residuals[index] = original[index] + mirrored[index];
        withinTolerance &= Math.abs(residuals[index]) <= MIRROR_TOLERANCE;
      }
      if (withinTolerance) {
        symmetric++;
      }
      System.out.printf(MIRROR_ROW_FORMAT, position.name(), residuals[0], residuals[1],
              residuals[2], withinTolerance ? "PASS" : "FAIL");
      if (verbose) {
        System.out.printf(DETAIL_FORMAT, position.mirrorFen());
        for (int index = 0; index < EVALUATORS.size(); index++) {
          System.out.printf(DETAIL_FORMAT, String.format("%-12s%12.2f%12.2f",
                  EVALUATORS.get(index).name(), original[index], mirrored[index]));
        }
      }
    }
    System.out.println();
    return symmetric;
  }

  /**
   * Prints the difference each evaluator reports between a position with white to move and the same
   * piece placement with black to move, which is the size of the side to move term. A position
   * whose flipped variant would leave the side not on move in check is skipped with a note, since
   * such a position cannot occur.
   *
   * @param prepared The positions to report on.
   * @param verbose Whether to print the notation of both variants.
   */
  private static void reportSideToMove(final List<PreparedPosition> prepared,
                                       final boolean verbose) {
    System.out.println("Side to move, white to move less black to move from one placement");
    printHeader();
    for (final PreparedPosition position : prepared) {
      if (position.whiteToMove() == null || position.blackToMove() == null) {
        System.out.printf(NOTE_ROW_FORMAT, position.name(), position.note());
        continue;
      }
      final double[] whiteScores = scores(position.whiteToMove());
      final double[] blackScores = scores(position.blackToMove());
      final double[] differences = new double[EVALUATORS.size()];
      for (int index = 0; index < EVALUATORS.size(); index++) {
        differences[index] = whiteScores[index] - blackScores[index];
      }
      printScoreRow(position.name(), differences);
      if (verbose) {
        System.out.printf(DETAIL_FORMAT, position.whiteToMoveFen());
        System.out.printf(DETAIL_FORMAT, position.blackToMoveFen());
      }
    }
    System.out.println();
  }

  /**
   * Evaluates the given board with every evaluator.
   *
   * @param board The position to evaluate.
   * @return The scores, in the order the evaluators are listed in.
   */
  private static double[] scores(final Board board) {
    final double[] scores = new double[EVALUATORS.size()];
    for (int index = 0; index < EVALUATORS.size(); index++) {
      scores[index] = EVALUATORS.get(index).evaluator().evaluate(board);
    }
    return scores;
  }

  /**
   * Prints the header naming the evaluator columns.
   */
  private static void printHeader() {
    System.out.printf(HEADER_FORMAT, "position", EVALUATORS.get(0).name(),
            EVALUATORS.get(1).name(), EVALUATORS.get(2).name());
  }

  /**
   * Prints a table row carrying one number per evaluator.
   *
   * @param name The name of the position the row reports on.
   * @param values The numbers, in the order the evaluators are listed in.
   */
  private static void printScoreRow(final String name, final double[] values) {
    System.out.printf(SCORE_ROW_FORMAT, name, values[0], values[1], values[2]);
  }

  /**
   * Builds every board a position is measured on. A board that could not be built is left null and
   * the reason is carried in the note.
   *
   * @param position The position to prepare.
   * @return The prepared position.
   */
  private static PreparedPosition prepare(final EvaluationPosition position) {
    final Board board;
    try {
      board = FenParser.parse(position.fen());
    } catch (final IllegalArgumentException exception) {
      return new PreparedPosition(position.name(), position.fen(), null, null, null, null, null,
              null, null, "the position could not be parsed: " + exception.getMessage());
    }

    final String mirrorFen = mirrorFen(position.fen());
    Board mirrorBoard = null;
    String note = null;
    try {
      mirrorBoard = FenParser.parse(mirrorFen);
    } catch (final IllegalArgumentException exception) {
      note = "the mirrored position could not be parsed: " + exception.getMessage();
    }

    final String whiteToMoveFen = sideToMoveFen(position.fen(), WHITE_TO_MOVE);
    final String blackToMoveFen = sideToMoveFen(position.fen(), BLACK_TO_MOVE);
    Board whiteToMove = null;
    Board blackToMove = null;
    try {
      whiteToMove = legalOrNull(FenParser.parse(whiteToMoveFen));
      blackToMove = legalOrNull(FenParser.parse(blackToMoveFen));
      if (whiteToMove == null || blackToMove == null) {
        note = note != null ? note
                : "skipped, flipping the side to move leaves the side not on move in check";
      }
    } catch (final IllegalArgumentException exception) {
      note = note != null ? note
              : "the side to move variant could not be parsed: " + exception.getMessage();
    }

    return new PreparedPosition(position.name(), position.fen(), board, mirrorBoard, whiteToMove,
            blackToMove, mirrorFen, whiteToMoveFen, blackToMoveFen, note);
  }

  /**
   * Returns the given board when the side not on move is not in check, and null otherwise.
   *
   * @param board The board to test.
   * @return The board, or null when it describes a position that cannot occur.
   */
  private static Board legalOrNull(final Board board) {
    return board.currentPlayer().getOpponent().isInCheck() ? null : board;
  }

  /**
   * Builds the colour-mirrored twin of a position, in which the ranks are reversed, every piece
   * changes colour, and the side to move, castling rights, and en passant target follow.
   *
   * @param fen The Forsyth-Edwards Notation string to mirror.
   * @return The notation string of the mirrored position.
   */
  private static String mirrorFen(final String fen) {
    final String[] fields = fen.trim().split("\\s+");
    final String[] ranks = fields[PLACEMENT_FIELD].split(RANK_SEPARATOR);
    final StringBuilder placement = new StringBuilder();
    for (int index = ranks.length - 1; index >= 0; index--) {
      if (index != ranks.length - 1) {
        placement.append(RANK_SEPARATOR);
      }
      placement.append(swapCase(ranks[index]));
    }
    fields[PLACEMENT_FIELD] = placement.toString();
    fields[MOVER_FIELD] = WHITE_TO_MOVE.equals(fields[MOVER_FIELD]) ? BLACK_TO_MOVE : WHITE_TO_MOVE;
    fields[CASTLING_FIELD] = mirrorCastling(fields[CASTLING_FIELD]);
    fields[EN_PASSANT_FIELD] = mirrorEnPassant(fields[EN_PASSANT_FIELD]);
    return String.join(FIELD_SEPARATOR, fields);
  }

  /**
   * Builds a variant of a position with the given side to move. The piece placement and castling
   * rights are carried over unchanged, the en passant target is cleared, and the halfmove clock and
   * fullmove number are dropped, so that the side to move is the only difference between the two
   * variants of a position.
   *
   * @param fen The Forsyth-Edwards Notation string to build the variant from.
   * @param mover The side to move field of the variant.
   * @return The notation string of the variant.
   */
  private static String sideToMoveFen(final String fen, final String mover) {
    final String[] fields = fen.trim().split("\\s+");
    return String.join(FIELD_SEPARATOR, fields[PLACEMENT_FIELD], mover, fields[CASTLING_FIELD],
            EMPTY_FIELD);
  }

  /**
   * Mirrors a castling rights field by changing the colour of every right and listing the results
   * in the order a notation string lists them. A field carrying an unrecognised right is returned
   * with its colours changed and its order left alone.
   *
   * @param castling The castling rights field of a notation string.
   * @return The mirrored castling rights field.
   */
  private static String mirrorCastling(final String castling) {
    if (EMPTY_FIELD.equals(castling)) {
      return castling;
    }
    final String swapped = swapCase(castling);
    final StringBuilder ordered = new StringBuilder(swapped.length());
    for (final char right : CASTLING_ORDER.toCharArray()) {
      if (swapped.indexOf(right) >= 0) {
        ordered.append(right);
      }
    }
    return ordered.length() == swapped.length() ? ordered.toString() : swapped;
  }

  /**
   * Mirrors an en passant target square by moving it between the third and sixth ranks.
   *
   * @param target The en passant target field of a notation string.
   * @return The mirrored target field.
   */
  private static String mirrorEnPassant(final String target) {
    if (EMPTY_FIELD.equals(target)) {
      return target;
    }
    final char rank = target.charAt(1) == BLACK_EN_PASSANT_RANK ? WHITE_EN_PASSANT_RANK
            : BLACK_EN_PASSANT_RANK;
    return target.charAt(0) + String.valueOf(rank);
  }

  /**
   * Swaps the case of every letter in the given text, leaving other characters unchanged.
   *
   * @param text The text to swap the case of.
   * @return The text with the case of every letter swapped.
   */
  private static String swapCase(final String text) {
    final StringBuilder swapped = new StringBuilder(text.length());
    for (final char character : text.toCharArray()) {
      swapped.append(Character.isUpperCase(character) ? Character.toLowerCase(character)
              : Character.toUpperCase(character));
    }
    return swapped.toString();
  }

  /**
   * Prints the usage text describing how the suite is run from the command line.
   */
  private static void printUsage() {
    System.out.println("""
            Usage:
              EvaluationSuite [--verbose]
              EvaluationSuite --help                   print this message

            Four checks are reported. Balance prints the score every evaluator gives each
            position. Agreement prints the spread between the highest and lowest of those scores,
            which is the size of the evaluators' miscalibration at the position. Mirror symmetry
            prints the residual left when a position is added to its colour-mirrored twin, which is
            zero for an evaluation that treats both colours alike. Side to move prints the
            difference between evaluating one piece placement with white to move and with black to
            move, which is the size of the side to move term.

            Only the mirror check carries a verdict. The verbose flag adds the notation of every
            position and variant along with the scores the mirror check compares.

            The suite exits with a non-zero status when any position fails the mirror check.""");
  }

  /**
   * The NamedEvaluator record pairs an evaluator with the name used for its column.
   *
   * @param name The name of the evaluator.
   * @param evaluator The evaluator itself.
   */
  private record NamedEvaluator(String name, BoardEvaluator evaluator) { }

  /**
   * The EvaluationPosition record names a position the suite measures.
   *
   * @param name The name of the position.
   * @param fen The Forsyth-Edwards Notation string describing the position.
   */
  public record EvaluationPosition(String name, String fen) { }

  /**
   * The PreparedPosition record holds every board a position is measured on. A board is null when
   * it could not be built, in which case the note carries the reason.
   *
   * @param name The name of the position.
   * @param fen The notation string of the position.
   * @param board The position itself.
   * @param mirrorBoard The colour-mirrored twin of the position.
   * @param whiteToMove The position's piece placement with white to move.
   * @param blackToMove The position's piece placement with black to move.
   * @param mirrorFen The notation string of the mirrored position.
   * @param whiteToMoveFen The notation string of the white to move variant.
   * @param blackToMoveFen The notation string of the black to move variant.
   * @param note The reason a board is missing, or null when every board was built.
   */
  private record PreparedPosition(String name, String fen, Board board, Board mirrorBoard,
                                  Board whiteToMove, Board blackToMove, String mirrorFen,
                                  String whiteToMoveFen, String blackToMoveFen, String note) { }
}