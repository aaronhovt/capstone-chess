package engine.forTesting;

import engine.forBoard.Board;
import engine.forBoard.ZobristHashing;

import java.util.List;
import java.util.Map;

/**
 * The PerftSuite class runs the engine's move generation against published reference node counts.
 * It holds a set of positions whose leaf node counts are known and independently verified, walks the
 * legal move tree of each position to a requested depth, and reports whether the counts produced by
 * this engine match. The positions are the standard debugging set: the initial position, which
 * exercises ordinary movement, and six positions chosen by the wider chess programming community
 * because each one isolates rules that engines commonly implement incorrectly, including castling
 * through attacked squares, en passant captures that expose the king, and underpromotion.
 * <p>
 * A failing count is reported alongside a divide breakdown, which lists the node count contributed
 * by each move at the root so the faulty branch can be found by comparing against a reference engine.
 * The suite can also verify that the incrementally updated Zobrist hash carried by each board agrees
 * with a hash recalculated from scratch, since a hash that drifts corrupts transposition table
 * lookups without changing any node count. Alongside that it can verify the board copy and null move
 * round trips, which likewise change no node count and so would otherwise go unchecked by a perft,
 * the round trip of every position through {@link FenWriter} and {@link FenParser}, and the
 * evaluation network accumulators the board keeps from move to move.
 * This class is designed to be run from the command line and its entry point returns a non-zero exit
 * status when any check fails.
 *
 * @author Aaron Ho
 */
@SuppressWarnings("JavaPrintToLogpoint")
public class PerftSuite {

  /** The depth every position is tested to when no depth is requested on the command line. */
  private static final int DEFAULT_MAX_DEPTH = 4;

  /** The depth the Zobrist consistency walk is limited to, since it recalculates a hash at every node. */
  private static final int HASH_VERIFICATION_DEPTH = 3;

  /** The depth the mutation round trip walk is limited to, since it copies a board at every node. */
  private static final int MUTATION_VERIFICATION_DEPTH = 3;

  /** The depth the FEN round trip walk is limited to, since it parses a board at every node. */
  private static final int FEN_VERIFICATION_DEPTH = 3;

  /** The depth the network accumulator walk is limited to, since it recomputes them at every node. */
  private static final int NETWORK_VERIFICATION_DEPTH = 3;

  /** The command line flag requesting a Zobrist consistency walk alongside the node counts. */
  private static final String VERIFY_HASH_FLAG = "--verify-hash";

  /** The command line flag requesting a copy and null move round trip walk alongside the node counts. */
  private static final String VERIFY_MUTATION_FLAG = "--verify-mutation";

  /** The command line flag requesting a FEN writer and parser round trip walk alongside the node counts. */
  private static final String VERIFY_FEN_FLAG = "--verify-fen";

  /** The command line flag requesting a network accumulator walk alongside the node counts. */
  private static final String VERIFY_NNUE_FLAG = "--verify-nnue";

  /** The command line flag requesting that the node counts be taken with packed int moves. */
  private static final String INT_MOVES_FLAG = "--int-moves";

  /** The command line flag requesting usage information. */
  private static final String HELP_FLAG = "--help";

  /** The command line keyword requesting a divide breakdown of a single position. */
  private static final String DIVIDE_COMMAND = "divide";

  /** The command line keyword denoting the standard starting position in place of a notation string. */
  private static final String STARTING_POSITION_KEYWORD = "startpos";

  /** The number of moves listed per line of divide output. */
  private static final int DIVIDE_COLUMNS = 4;

  /** The number of nanoseconds in one second, used to convert elapsed times for reporting. */
  private static final double NANOSECONDS_PER_SECOND = 1_000_000_000.0;

  /**
   * The reference positions the suite tests, each paired with its published node counts.
   * The counts are those given by the chess programming community's perft results table, which have
   * been confirmed independently by several engines, and they are listed from depth one upward.
   */
  private static final List<PerftPosition> STANDARD_POSITIONS = List.of(
          new PerftPosition("Initial Position",
                  FenParser.STARTING_POSITION_FEN,
                  new long[] { 20L, 400L, 8902L, 197281L, 4865609L, 119060324L }),
          new PerftPosition("Position 2 (Kiwipete)",
                  "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                  new long[] { 48L, 2039L, 97862L, 4085603L, 193690690L }),
          new PerftPosition("Position 3",
                  "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                  new long[] { 14L, 191L, 2812L, 43238L, 674624L, 11030083L }),
          new PerftPosition("Position 4",
                  "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                  new long[] { 6L, 264L, 9467L, 422333L, 15833292L }),
          new PerftPosition("Position 4 (mirrored)",
                  "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
                  new long[] { 6L, 264L, 9467L, 422333L, 15833292L }),
          new PerftPosition("Position 5",
                  "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                  new long[] { 44L, 1486L, 62379L, 2103487L, 89941194L }),
          new PerftPosition("Position 6",
                  "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
                  new long[] { 46L, 2079L, 89890L, 3894594L, 164075551L }));

  /**
   * Runs the suite from the command line. With no arguments every position is tested to the default
   * depth. A single numeric argument raises or lowers that depth, the verify hash flag adds a Zobrist
   * consistency walk, the verify mutation flag adds a copy and null move round trip walk, the verify
   * FEN flag adds a FEN writer and parser round trip walk, the verify NNUE flag adds a network
   * accumulator walk, and the divide keyword breaks a single position down by root move instead of running the suite.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    if (args.length > 0 && DIVIDE_COMMAND.equalsIgnoreCase(args[0])) {
      runDivide(args);
      return;
    }
    int maxDepth = DEFAULT_MAX_DEPTH;
    boolean verifyHashes = false;
    boolean verifyMutation = false;
    boolean verifyFen = false;
    boolean verifyNetwork = false;
    boolean intMoves = false;
    for (final String argument : args) {
      if (VERIFY_HASH_FLAG.equals(argument)) {
        verifyHashes = true;
      } else if (VERIFY_MUTATION_FLAG.equals(argument)) {
        verifyMutation = true;
      } else if (VERIFY_FEN_FLAG.equals(argument)) {
        verifyFen = true;
      } else if (VERIFY_NNUE_FLAG.equals(argument)) {
        verifyNetwork = true;
      } else if (INT_MOVES_FLAG.equals(argument)) {
        intMoves = true;
      } else if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else {
        try {
          maxDepth = Integer.parseInt(argument);
        } catch (final NumberFormatException exception) {
          System.out.println("Unrecognised argument: " + argument);
          printUsage();
          return;
        }
      }
    }
    if (maxDepth < 1) {
      System.out.println("The maximum depth must be at least one.");
      printUsage();
      return;
    }
    System.exit(run(maxDepth, verifyHashes, verifyMutation, verifyFen, verifyNetwork, intMoves) ?
            0 : 1);
  }

  /**
   * Tests every reference position up to the requested depth without the mutation round trip walk.
   * This form is preserved so that callers written before that walk existed continue to work.
   *
   * @param maxDepth The greatest depth to test, limited per position by the depths with known counts.
   * @param verifyHashes Whether to walk each position checking Zobrist hash consistency.
   * @return True if every check passed, false otherwise.
   */
  public static boolean run(final int maxDepth, final boolean verifyHashes) {
    return run(maxDepth, verifyHashes, false);
  }

  /**
   * Tests every reference position up to the requested depth and prints a report of the results.
   * A position is abandoned as soon as one of its depths fails, because a fault that changes the
   * count at one depth changes it at every greater depth and the deeper walks are far more expensive.
   *
   * @param maxDepth The greatest depth to test, limited per position by the depths with known counts.
   * @param verifyHashes Whether to walk each position checking Zobrist hash consistency.
   * @param verifyMutation Whether to walk each position checking the copy and null move round trips.
   * @return True if every check passed, false otherwise.
   */
  public static boolean run(final int maxDepth, final boolean verifyHashes, final boolean verifyMutation) {
    return run(maxDepth, verifyHashes, verifyMutation, false);
  }

  /**
   * Tests every reference position up to the requested depth, taking the node counts either with
   * {@link engine.forBoard.Move} objects or with packed int moves.
   *
   * @param maxDepth The deepest depth to test each position to.
   * @param verifyHashes Whether to walk each position checking incremental hashes.
   * @param verifyMutation Whether to walk each position checking the copy and null move round trips.
   * @param intMoves Whether to count with {@link Perft#perftInt} rather than {@link Perft#perft}.
   * @return True if every check passed, false otherwise.
   */
  public static boolean run(final int maxDepth, final boolean verifyHashes,
                            final boolean verifyMutation, final boolean intMoves) {
    return run(maxDepth, verifyHashes, verifyMutation, false, false, intMoves);
  }

  /**
   * Tests every reference position up to the requested depth, taking the node counts either with
   * {@link engine.forBoard.Move} objects or with packed int moves, and optionally walking each
   * position checking the FEN writer and parser round trip and the network accumulators.
   *
   * @param maxDepth The deepest depth to test each position to.
   * @param verifyHashes Whether to walk each position checking incremental hashes.
   * @param verifyMutation Whether to walk each position checking the copy and null move round trips.
   * @param verifyFen Whether to walk each position checking the FEN writer and parser round trip.
   * @param verifyNetwork Whether to walk each position checking the network accumulators.
   * @param intMoves Whether to count with {@link Perft#perftInt} rather than {@link Perft#perft}.
   * @return True if every check passed, false otherwise.
   */
  public static boolean run(final int maxDepth, final boolean verifyHashes,
                            final boolean verifyMutation, final boolean verifyFen,
                            final boolean verifyNetwork, final boolean intMoves) {
    System.out.printf("Perft suite: %d positions, maximum depth %d%n%n",
            STANDARD_POSITIONS.size(), maxDepth);
    int checksRun = 0;
    int failures = 0;
    int positionsFailed = 0;
    for (final PerftPosition position : STANDARD_POSITIONS) {
      final PositionOutcome outcome =
              runPosition(position, maxDepth, verifyHashes, verifyMutation, verifyFen,
                      verifyNetwork, intMoves);
      checksRun += outcome.checksRun();
      failures += outcome.failures();
      if (outcome.failures() > 0) {
        positionsFailed++;
      }
    }
    System.out.printf("%d checks run, %d passed, %d failed, across %d positions of which %d failed%n",
            checksRun, checksRun - failures, failures, STANDARD_POSITIONS.size(), positionsFailed);
    return failures == 0;
  }

  /**
   * Tests a single reference position up to the requested depth and prints its results.
   * Each depth reports the expected count, the count this engine produced, the elapsed time, and
   * the resulting node rate, which doubles as a rough measure of move generation throughput.
   * <p>
   * A failing mutation, FEN or network check is counted as a failure but does not abandon the
   * position, since it says nothing about whether the node counts are right and both answers are
   * worth having in one report.
   *
   * @param position The reference position to test.
   * @param maxDepth The greatest depth to test, limited by the depths with known counts.
   * @param verifyHashes Whether to walk the position checking Zobrist hash consistency.
   * @param verifyMutation Whether to walk the position checking the copy and null move round trips.
   * @param verifyFen Whether to walk the position checking the FEN writer and parser round trip.
   * @param verifyNetwork Whether to walk the position checking the network accumulators.
   * @param intMoves Whether to count with {@link Perft#perftInt} rather than {@link Perft#perft}.
   * @return The number of checks run for this position and the number of them that failed.
   */
  private static PositionOutcome runPosition(final PerftPosition position,
                                             final int maxDepth,
                                             final boolean verifyHashes,
                                             final boolean verifyMutation,
                                             final boolean verifyFen,
                                             final boolean verifyNetwork,
                                             final boolean intMoves) {
    System.out.println(position.name());
    System.out.println("  " + position.fen());
    final Board board;
    try {
      board = FenParser.parse(position.fen());
    } catch (final IllegalArgumentException exception) {
      System.out.println("  FAIL  the position could not be parsed: " + exception.getMessage());
      System.out.println();
      return new PositionOutcome(1, 1);
    }
    if (verifyHashes) {
      reportHashConsistency(board);
    }
    int checksRun = 0;
    int failures = 0;
    if (verifyMutation) {
      checksRun++;
      if (!reportMutationConsistency(board)) {
        failures++;
      }
    }
    if (verifyFen) {
      checksRun++;
      if (!reportFenConsistency(board)) {
        failures++;
      }
    }
    if (verifyNetwork) {
      checksRun++;
      if (!reportNetworkConsistency(board)) {
        failures++;
      }
    }
    final int deepestDepth = Math.min(maxDepth, position.deepestKnownDepth());
    for (int depth = 1; depth <= deepestDepth; depth++) {
      final long expectedNodes = position.expectedNodesAt(depth);
      final long startTime = System.nanoTime();
      final long actualNodes = intMoves ? Perft.perftInt(board, depth) : Perft.perft(board, depth);
      final double elapsedSeconds = (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
      checksRun++;
      System.out.printf("  depth %d  expected %,14d  actual %,14d  %8.2fs  %,12d n/s  %s%n",
              depth, expectedNodes, actualNodes, elapsedSeconds,
              nodesPerSecond(actualNodes, elapsedSeconds), actualNodes == expectedNodes ? "PASS" : "FAIL");
      if (actualNodes != expectedNodes) {
        System.out.printf("  off by %+,d at depth %d, breaking the root down by move:%n",
                actualNodes - expectedNodes, depth);
        printDivide(board, depth);
        System.out.println();
        return new PositionOutcome(checksRun, failures + 1);
      }
    }
    System.out.println();
    return new PositionOutcome(checksRun, failures);
  }

  /**
   * Walks the given position checking that every reachable board carries a Zobrist hash matching a
   * hash recalculated from scratch, and reports the first position where the two disagree.
   *
   * @param board The position from which to begin the walk.
   */
  private static void reportHashConsistency(final Board board) {
    final Board divergentBoard = Perft.findZobristDivergence(board, HASH_VERIFICATION_DEPTH);
    if (divergentBoard == null) {
      System.out.printf("  zobrist hashes are consistent to depth %d%n", HASH_VERIFICATION_DEPTH);
      return;
    }
    System.out.printf("  zobrist hash mismatch: carried %d, recalculated %d, at this position:%n",
            divergentBoard.getZobristHash(), ZobristHashing.calculateBoardHash(divergentBoard));
    System.out.println(divergentBoard);
  }

  /**
   * Walks the given position checking that {@link Board#copy()} reproduces every reachable board
   * exactly and that a null move applied to it can be unmade without a trace, and reports the first
   * position where either fails. Neither operation changes a node count, so nothing in the perft
   * counts would reveal a fault in them.
   *
   * @param board The position from which to begin the walk.
   * @return True if every position checked out, false otherwise.
   */
  private static boolean reportMutationConsistency(final Board board) {
    final String fault = Perft.findMutationDivergence(board, MUTATION_VERIFICATION_DEPTH);
    if (fault == null) {
      System.out.printf("  copy and null move round trips are exact to depth %d%n",
              MUTATION_VERIFICATION_DEPTH);
      return true;
    }
    System.out.println("  FAIL  mutation round trip diverged:");
    System.out.println("  " + fault);
    return false;
  }

  /**
   * Walks the given position checking that every reachable board survives a round trip through
   * {@link FenWriter} and {@link FenParser}, and reports the first position where it does not.
   *
   * @param board The position from which to begin the walk.
   * @return True if every position checked out, false otherwise.
   */
  private static boolean reportFenConsistency(final Board board) {
    final String fault = Perft.findFenDivergence(board, FEN_VERIFICATION_DEPTH);
    if (fault == null) {
      System.out.printf("  FEN round trips are exact to depth %d%n", FEN_VERIFICATION_DEPTH);
      return true;
    }
    System.out.println("  FAIL  FEN round trip diverged:");
    System.out.println("  " + fault);
    return false;
  }

  /**
   * Walks the given position checking that the network accumulators the board keeps from move to
   * move equal those computed from its pieces, and reports the first position where they do not.
   *
   * @param board The position from which to begin the walk.
   * @return True if every position checked out, false otherwise.
   */
  private static boolean reportNetworkConsistency(final Board board) {
    final String fault = Perft.findNetworkDivergence(board, NETWORK_VERIFICATION_DEPTH);
    if (fault == null) {
      System.out.printf("  network accumulators are exact to depth %d%n", NETWORK_VERIFICATION_DEPTH);
      return true;
    }
    System.out.println("  FAIL  network accumulators diverged:");
    System.out.println("  " + fault);
    return false;
  }

  /**
   * Prints the node count contributed by each legal move available in the given position.
   * The listing is the standard divide output and is intended to be compared move by move against a
   * reference engine, since the move whose subtotal differs identifies the branch containing the fault.
   *
   * @param board The position whose root moves are to be broken down.
   * @param depth The depth at which to break the position down.
   */
  private static void printDivide(final Board board, final int depth) {
    final Map<String, Long> subtotals = Perft.divide(board, depth);
    long total = 0L;
    int column = 0;
    final StringBuilder line = new StringBuilder("   ");
    for (final Map.Entry<String, Long> subtotal : subtotals.entrySet()) {
      line.append(String.format(" %s %-12s", subtotal.getKey(), String.format("%,d", subtotal.getValue())));
      total += subtotal.getValue();
      if (++column % DIVIDE_COLUMNS == 0) {
        System.out.println(line.toString().stripTrailing());
        line.setLength(0);
        line.append("   ");
      }
    }
    if (column % DIVIDE_COLUMNS != 0) {
      System.out.println(line.toString().stripTrailing());
    }
    System.out.printf("    %d moves, %,d nodes total%n", subtotals.size(), total);
  }

  /**
   * Breaks a single position down by root move in response to the divide command.
   * The position may be given as a notation string or as the starting position keyword, and any
   * notation string containing spaces is reassembled from the remaining command line arguments.
   *
   * @param args The command line arguments, beginning with the divide keyword.
   */
  private static void runDivide(final String[] args) {
    if (args.length < 3) {
      printUsage();
      return;
    }
    final int depth;
    try {
      depth = Integer.parseInt(args[1]);
    } catch (final NumberFormatException exception) {
      System.out.println("The divide depth must be a whole number: " + args[1]);
      printUsage();
      return;
    }
    if (depth < 1) {
      System.out.println("The divide depth must be at least one.");
      return;
    }
    final String fen = STARTING_POSITION_KEYWORD.equalsIgnoreCase(args[2]) ? FenParser.STARTING_POSITION_FEN
            : String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
    final Board board;
    try {
      board = FenParser.parse(fen);
    } catch (final IllegalArgumentException exception) {
      System.out.println("The position could not be parsed: " + exception.getMessage());
      return;
    }
    System.out.println(fen);
    System.out.println(board);
    System.out.printf("divide at depth %d%n", depth);
    printDivide(board, depth);
  }

  /**
   * Calculates the node rate achieved by a walk, guarding against a division by an elapsed time that
   * rounds to zero for the shallowest depths.
   *
   * @param nodes The number of nodes counted.
   * @param elapsedSeconds The time the walk took in seconds.
   * @return The number of nodes counted per second.
   */
  private static long nodesPerSecond(final long nodes, final double elapsedSeconds) {
    return elapsedSeconds <= 0.0 ? nodes : (long) (nodes / elapsedSeconds);
  }

  /**
   * Prints the usage text describing how the suite is run from the command line.
   */
  private static void printUsage() {
    System.out.println("""
            Usage:
              PerftSuite [maxDepth] [--verify-hash] [--verify-mutation] [--verify-fen]
                         [--verify-nnue] [--int-moves]
                                                      run every reference position, default depth 4,
                                                      counting with packed int moves if asked
              PerftSuite divide <depth> <fen>         break one position down by root move
              PerftSuite divide <depth> startpos      break the starting position down by root move
              PerftSuite --help                       print this message

            The suite exits with a non-zero status when any check fails. Depths beyond five are
            expensive in this engine, which regenerates the legal moves of both players at every
            node, so raise the depth only for a position under active investigation.""");
  }

  /**
   * The PerftPosition record pairs a reference position with the node counts it is known to produce.
   * The counts are listed from depth one upward, so the entry at index zero is the number of legal
   * moves available in the position itself.
   *
   * @param name The name the position is known by in the reference tables.
   * @param fen The Forsyth-Edwards Notation string describing the position.
   * @param expectedNodeCounts The published node counts, ordered from depth one upward.
   */
  public record PerftPosition(String name, String fen, long[] expectedNodeCounts) {

    /**
     * Returns the published node count for the given depth.
     *
     * @param depth The depth whose count is required, counting from one.
     * @return The number of leaf nodes the position is known to produce at that depth.
     * @throws IllegalArgumentException If no count is published for the given depth.
     */
    public long expectedNodesAt(final int depth) {
      if (depth < 1 || depth > this.expectedNodeCounts.length) {
        throw new IllegalArgumentException("No published node count for " + this.name +
                " at depth " + depth);
      }
      return this.expectedNodeCounts[depth - 1];
    }

    /**
     * Returns the greatest depth for which this position has a published node count.
     *
     * @return The deepest depth that can be checked against a reference value.
     */
    public int deepestKnownDepth() {
      return this.expectedNodeCounts.length;
    }
  }

  /**
   * The PositionOutcome record reports how many checks a position ran and how many of them failed.
   * A position is abandoned after its first node count failure, so a failing position reports fewer
   * checks run than the requested depth would otherwise imply.
   *
   * @param checksRun The number of checks made for the position.
   * @param failures The number of those checks that failed.
   */
  private record PositionOutcome(int checksRun, int failures) {
  }
}