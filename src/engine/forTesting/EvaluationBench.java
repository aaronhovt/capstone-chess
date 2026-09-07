package engine.forTesting;

import com.sun.management.ThreadMXBean;
import engine.forBoard.Board;
import engine.forPlayer.forAI.BoardEvaluator;
import engine.forPlayer.forAI.EndgameBoardEvaluator;
import engine.forPlayer.forAI.MiddlegameBoardEvaluator;
import engine.forPlayer.forAI.OpeningGameEvaluator;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The EvaluationBench class measures what one call into each of the engine's evaluators costs and
 * reports three quantities per evaluator: the bytes allocated per evaluation, the time taken per
 * evaluation, and a checksum over every score produced.
 * <p>
 * No search is run and no evaluator is reached through
 * {@link engine.forPlayer.forAI.GameStateDetector}, so every number is attributable to a named
 * evaluator. Every evaluator is measured on every position, including positions of a phase it
 * would not normally be given, so that the three are directly comparable.
 * <p>
 * The positions are parsed once and the resulting boards are reused for every pass. A player
 * computes its legal moves the first time they are asked for and holds them from then on, so the
 * cost of generating them is paid during the warmup and falls outside every measurement. What is
 * measured is therefore the evaluator's own work on a board whose move list already exists, which
 * is less than a leaf of a real search pays.
 * <p>
 * The allocation figure counts what is still allocated once the just in time compiler has finished
 * with the code, which is why it is taken after a warmup and why it can be smaller than reading the
 * source suggests. It is the smallest figure any measured pass produced, so a pass interrupted by
 * compilation or garbage collection cannot inflate it.
 * <p>
 * The checksum is taken over the raw bits of every score in a fixed order, so two builds that were
 * meant to evaluate alike can be shown to do so by the same command that measures their speed.
 * <p>
 * This class is designed to be run from the command line. Nothing here can fail, so its entry point
 * always returns a zero exit status.
 *
 * @author Aaron Ho
 */
@SuppressWarnings("JavaPrintToLogpoint")
public class EvaluationBench {

  /** The number of passes over the whole position set that make up one batch. */
  private static final int BATCH_PASSES = 40;

  /** The number of timed batches run when no batch count is given. */
  private static final int DEFAULT_BATCHES = 5;

  /** The number of batches run before any measurement is taken. */
  private static final int WARMUP_BATCHES = 10;

  /** The number of batches the allocation figure is taken as the smallest of. */
  private static final int ALLOCATION_PASSES = 5;

  /** The offset basis of the FNV-1a hash the score checksum is built with. */
  private static final long CHECKSUM_BASIS = -3750763034362895579L;

  /** The prime of the FNV-1a hash the score checksum is built with. */
  private static final long CHECKSUM_PRIME = 1099511628211L;

  /** The first multiplier of the mix applied to a finished checksum. */
  private static final long CHECKSUM_MIX_FIRST = -4658895280553007687L;

  /** The second multiplier of the mix applied to a finished checksum. */
  private static final long CHECKSUM_MIX_SECOND = -7723592293110705685L;

  /** The number of nanoseconds in one second. */
  private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;

  /** The command line flag requesting the full per group detail. */
  private static final String VERBOSE_FLAG = "--verbose";

  /** The command line flag requesting usage information. */
  private static final String HELP_FLAG = "--help";

  /** The batch count meaning that no batch count was given on the command line. */
  private static final int NO_BATCH_COUNT = -1;

  /** The format of the header naming the measured columns. */
  private static final String HEADER_FORMAT = "  %-14s%12s%15s%14s%15s  %s%n";

  /** The format of a row carrying the measurements of one evaluator or group. */
  private static final String ROW_FORMAT = "  %-14s%12s%,15d%,14d%,15d  0x%016x%n";

  /** The format of a line of verbose detail. */
  private static final String DETAIL_FORMAT = "      %s%n";

  /** The cell printed in place of an allocation figure the host cannot supply. */
  private static final String NO_ALLOCATION = "-";

  /** The value accumulated into so that no measured evaluation can be optimised away. */
  private static double sink;

  /** The evaluators measured, each paired with the name used for its row. */
  private static final List<NamedEvaluator> EVALUATORS = List.of(
          new NamedEvaluator("opening", OpeningGameEvaluator.get()),
          new NamedEvaluator("middlegame", MiddlegameBoardEvaluator.get()),
          new NamedEvaluator("endgame", EndgameBoardEvaluator.get()));

  /**
   * The positions the bench measures, grouped by the phase their material count places them in.
   * They were taken from games played out from the openings in the opening book, sampled evenly
   * across each group, and every one of them has a legal move. The set is fixed because the numbers
   * a run reports are only comparable with those of another run over the same positions.
   */
  private static final List<PositionGroup> POSITION_GROUPS = List.of(
          new PositionGroup("opening", List.of(
          "r1b1k2r/pp1pppb1/2n3p1/q1p4p/2P3n1/1QN2NPP/PP1PPPB1/R1B2RK1 w kq h6 0 9",
          "r1b1k2r/pp1pppbp/2n3p1/q1p5/2P3n1/1QN2NP1/PP1PPPBP/R1B2RK1 w kq - 6 8",
          "r1bqk1nr/ppppbppp/2n5/4p3/4P3/2P2N2/PP1P1PPP/RNBQKB1R w KQkq - 1 4",
          "r2q1rk1/pppb1pbp/n2p1np1/1N1Pp3/2P1P3/P3BP2/1P1Q2PP/2KR1BNR b - - 2 12",
          "r2qkb1r/2pb1p1p/p1np1np1/1p2p3/B2PP3/2P2N2/PP1N1PPP/R1BQ1RK1 w kq b6 0 9",
          "rn1q1rk1/pp1b1pp1/3ppn1p/2pP4/1bP4B/1QN5/PP2PPPP/R3KBNR w KQ - 4 9",
          "rn1qk2r/pb1pbppp/1p2pn2/2p5/2PP4/3BPN2/PP3PPP/RNBQ1RK1 w kq - 1 7",
          "rnb2rk1/ppp1bppp/3qp3/3p4/3P1Bn1/2NQ1NP1/PPP1PPBP/R4RK1 b - - 4 8",
          "2kr1bnr/pbppqppp/1pn5/4P3/4P3/2N2P2/PPP1N1PP/R1BQKB1R w KQ - 3 7",
          "r1b2rk1/p1p2ppp/1p1qpn2/3pn3/1bPP4/PPNQP3/1B3PPP/R3KB1R w KQ - 0 11",
          "r1bqk2r/ppppbppp/2n2n2/8/4Pp2/2NB1N2/PPPP2PP/R1BQ1RK1 b kq - 7 6",
          "r1bqkb1r/ppp2ppp/2n1pn2/8/2pPP3/2N2N2/PP3PPP/R1BQKB1R w KQkq - 1 6",
          "r2qkb1r/2pb1p1p/p1np1np1/1p2P3/B3P3/2P2N2/PP1N1PPP/R1BQ1RK1 b kq - 0 9",
          "r1b1k2r/pp1ppp1p/n5pB/2p5/2PPP1n1/2N2N2/Pq1QBPPP/R4RK1 b kq - 1 9",
          "r1bq1rk1/ppp1bppp/5n2/3pn3/Q3P3/2P5/PP1PBPPP/RNB2RK1 w - - 0 8",
          "r1bqkb1r/pp1ppppp/2n5/8/3pn3/5N2/PPP1BPPP/RNBQ1RK1 w kq - 0 6",
          "r1bqkb1r/ppp1pppp/2n5/3p2N1/3P4/8/PPPNPPPP/R2QKB1R w KQkq - 1 6",
          "r3kb1r/p2q1ppp/1p2pn2/1Q1p1b2/1n1P1B2/2N1PN2/PP3PPP/2KR1B1R w kq - 1 11",
          "rnb1k2r/pppnqppp/4p3/3pP3/3P4/2N2Q2/PPP2PPP/R3KBNR b KQkq - 1 7",
          "rnb2rk1/pppnqppp/4p3/3pP3/3P4/2N2Q2/PPP2PPP/R3KBNR w KQ - 2 8",
          "rnbqk2r/pppp1ppp/4pn2/8/2P5/P1P2N2/1P2PPPP/R1BQKB1R b KQkq - 0 5",
          "r1b1k2r/pp1ppp1p/n5pB/2p5/2PPP1n1/2N2N2/P2qBPPP/R4RK1 w kq - 0 10",
          "r1bq1rk1/ppp2p1p/2n1p3/3p2p1/2PN1B2/P1P1P3/1PQ2nPP/2KR1B1R b - - 1 11",
          "r1bqk2r/pppp1ppp/5n2/4N3/1n2P3/8/PPPP1PPP/R1BQKB1R b KQkq - 0 6",
          "r2q1rk1/ppp4p/2np2p1/3Nppb1/2P2P2/1P2Q3/PB1P2PP/R3KBNR b KQ f3 0 12",
          "r2qkbnr/pp3ppp/2n1p3/2pp4/3PP3/3Q4/PPPN1PPP/R1B2RK1 b kq - 2 8",
          "r3kb1r/ppp1nppp/2nq4/1B2Nb2/3p1B2/2N5/PPP2PPP/R2QR1K1 b kq - 5 10",
          "rnb2rk1/3pqppp/p3pn2/1Pp5/8/2NQ1N2/PP2PPPP/R1B1KB1R b KQ - 1 9",
          "r1b2rk1/ppp1bppp/5n2/3pq3/Q3P3/2P5/PP2BPPP/RNB2RK1 w - - 0 10",
          "r1bq1rk1/ppp2p1p/2n1p3/3p4/2PN1p2/P1P1P3/1PQ2nPP/2KR1B1R w - - 0 12",
          "r1bqk1Q1/ppppbp1p/2n2n2/8/4P3/2P5/PP3PPP/RNB1KBNR b KQq - 0 8",
          "r4rk1/pppbbppp/5n2/3pq3/Q3P3/2P1B3/PP3PPP/RN1B1RK1 b - - 3 11",
          "rn1qr1k1/pp1bppbp/3P2p1/1B6/3Pn3/2N5/PP3PPP/R1B1K1NR w KQ - 0 11",
          "rn2k2r/pQ3ppp/1qp1p3/3p1b2/2PP4/2N1P3/PP2BPPn/R1B2RK1 w kq - 1 11",
          "rnb2rk1/4qppp/3ppn2/1pp1N3/5B2/2NQ4/PP2PPPP/R3KB1R b KQ - 1 11",
          "rnbqk2r/ppp1ppbp/6p1/8/8/2P3P1/P2PPPBP/R1BQK1NR w KQkq - 1 7",
          "r1b1k2r/p1pqbppp/2p2P2/3p4/3P4/5P2/PPP1N2P/RNBQ1RK1 b kq - 0 11",
          "r1b1k2r/ppp2ppp/2n1p3/8/1qpPN3/5N2/PP2BPPP/R2QK2R w KQkq - 1 10",
          "r1b1k3/p4p2/2pp1q1p/1pb3r1/3pPp1N/1B1P1N1P/PP3PP1/R4RK1 w q - 1 19",
          "r1bqk3/ppppbp1p/2n2n2/3B4/4P3/2P5/PP3PPP/RNB1K1NR b KQq - 3 10",
          "B4rk1/p1p1bppp/3qb3/3p4/3Pn3/8/PPP1QPPP/RNB2RK1 w - - 1 11",
          "r1b1k2r/p1p2ppp/2p2b2/3p1q2/3P4/3Q1P2/PPP1N2P/RNB2RK1 w kq - 2 13",
          "r1b2b1r/p1pk3p/2p1pq2/3p2NQ/3p4/4PN2/PPP2PPP/R3K2R w KQ - 0 15",
          "r1b2r1k/ppp2p1p/2n1pP2/3p3Q/3P4/2P5/P2R1PPP/1q2KBNR w K - 4 14",
          "r1b2r1k/ppp2p1p/2n1pP2/3p3Q/3P4/2P5/Pq3PPP/3RKBNR w K - 2 13",
          "r1b2r1k/ppp2p1p/2n1pP2/3p3Q/3Pq3/2P5/P3BPPP/3RK1NR b K - 7 15",
          "r1bqk3/ppppbp1p/2n5/3n4/4P3/2P5/PP3PPP/RNB1K1NR w KQq - 0 11",
          "r3k1nr/pp3ppp/2n1b3/q1b3B1/4P3/2N2N2/PP1Q1PPP/R4RK1 b kq - 2 11",
          "rn1q1rk1/ppP1ppbp/8/5p2/5P2/3p1N2/PP2Q1PP/RNB2RK1 b - - 0 13",
          "rn1qkbnr/pp2pppp/3p4/8/3QP3/5N2/PP3PPP/RNB2K2 b kq - 0 8"
          )),
          new PositionGroup("middlegame", List.of(
          "1k1r1b1r/1pp2ppp/p1np1n2/1N6/2Q1P3/5N2/PP3PPP/R1R3K1 b - - 1 15",
          "1k1r1b1r/ppp2ppp/2np1n2/8/2Q1P3/2N2N2/PP3PPP/R3R1K1 w - - 1 14",
          "2b1k3/r4p2/3p1q1p/ppb3r1/3pPp1N/1B1P1N1P/1P3PP1/R4RK1 w - - 0 22",
          "4kb1r/p1p1pppp/2pqb3/8/3P4/2NQB2P/PrP1N1P1/R4RK1 w k - 0 13",
          "r1b1k2r/ppp2ppp/4p3/3p4/1QPP4/3BP3/q4PPP/1N2K1NR b Kkq - 1 13",
          "r1b1k2r/ppp3pp/4p3/5p2/1npPN3/5N2/PP2BPPP/R3K2R w KQkq - 0 12",
          "r1br2k1/ppp2ppp/4pn2/7q/2Q5/2N1P1PP/PP2P1B1/R4RK1 b - - 0 15",
          "1r5r/p1p1kpbp/2pp1n2/5p2/3P4/2N2N2/PPP2PPP/R1B3K1 w - - 0 13",
          "r1b1k2r/pp1n1ppp/4pb2/8/8/2N1PN2/PP2BPPP/3RK2R w Kkq - 5 15",
          "r1b1k2r/pp2bp2/4p1p1/2n4p/8/2N1PN2/PPB1KPPP/3R3R w kq h6 0 21",
          "2r5/pp2kp1p/3npbp1/3p3P/3P1B2/2PB2P1/PP3PK1/1R2R3 b - - 0 27",
          "r1b1k3/pppp1p1p/2n5/3P2q1/8/2P5/PP3PPP/RN2K1NR w KQq - 0 13",
          "3k2nr/pp3ppp/2n1b3/2b5/4P3/2N2N2/PP3PPP/R2R2K1 b - - 1 14",
          "r1b1k3/pppp1p1p/2n5/3q4/8/2P2P2/PP2N1PP/RN2K2R b KQq - 0 14",
          "r1b4N/p1pkb3/2p1p2p/3p3q/3R4/4P3/PPP2PPP/2K4R b - - 0 19",
          "r1br2k1/pp3p1p/4p3/2pp4/2P2P2/P1P5/1PK3PP/3R1B1R b - - 3 17",
          "r1r3k1/1b3pp1/4p3/1pB4p/2P3n1/2N5/1P2PPPP/2KR1B1R w - - 1 20",
          "r3k2r/3n1ppp/2p2n2/pp2p1N1/5P2/3PP1P1/1B4BP/R4K2 b kq - 0 22",
          "rn6/1p2kp1p/p2p2rb/3P1R2/8/4P1p1/PPPKB1PP/7R w - - 2 20",
          "r1b3k1/pp2R2p/3p1nr1/2B2p2/2PN4/5B1P/P4PP1/R5K1 w - - 0 24",
          "r3r3/pRp2pkp/2P3p1/3nN3/8/2PP4/P4PPP/2B1R1K1 b - - 6 18",
          "r4n2/1p2kpp1/p2bp3/3pN3/3P4/1P2PN1P/1P2KPP1/2R5 b - - 3 26",
          "r4rk1/ppp4p/2n1bPp1/4P3/3P1Q2/8/P4P1P/3RK1NR w K - 1 22",
          "r6r/pb2kppp/4p3/1p2b3/3P1P2/3KP3/P5PP/RN5R w - - 0 19",
          "2r5/pb2kppp/4p3/4P3/1p1PP3/4K3/P1r3PP/RN4R1 w - - 4 23",
          "6nr/pp2kppp/2nb4/6N1/4P3/8/PP3PPP/R2R2K1 w - - 2 19",
          "6nr/pp3ppp/2nbk3/6N1/4P3/8/PP3PPP/R2R2K1 b - - 1 18",
          "r7/pp1bkr2/4pp2/3p4/2B4Q/4qP2/PP4PP/3R1K1R b - - 0 22",
          "5r2/1pQ2pk1/p1n3rp/8/4N3/5N2/PP3PPP/2R1R1K1 b - - 1 25",
          "rn6/1p2kp1p/p2p2B1/3Pb3/5R2/4P3/PPPK2PR/8 b - - 0 23",
          "2r5/pp3pp1/4kn1p/8/2RnP3/5N2/PP3PPP/6K1 w - - 0 25",
          "3r3k/p1p2ppp/4b3/2b5/5B2/8/P1P2PPP/1R2R1K1 w - - 2 21",
          "8/3b2pp/Q1p1pk2/p1R5/3PpP2/4P3/q5PP/2R4K b - - 4 30",
          "r1b5/p1pk4/2p1p3/3p4/3P1qP1/8/PPP2P1P/3K3R w - - 2 25",
          "r3r1k1/p1p3pp/8/1p2p3/3p2R1/3K4/PPP3PP/4R3 b - - 1 24",
          "1b6/3n1k2/1p2p1p1/1p1p4/2rP3P/1R1NP3/1P3P2/3K4 w - - 3 39",
          "6k1/5pp1/3p2qp/pRr5/5P2/2b1P3/5PBP/2R3K1 b - - 0 28",
          "8/1Q3pk1/p1n3rp/2N5/8/5rP1/PP3P1P/2R1R1K1 b - - 0 28",
          "1k6/1b3p1p/1p5r/1N4p1/3N4/1K3P1P/PP4P1/2R5 b - - 0 27",
          "4r1k1/6p1/pBr5/2P5/3q1p2/8/P4PPP/R4RK1 w - - 0 25",
          "6k1/6pp/1prrp3/p3R3/P7/1P6/5PPP/3R2K1 w - - 0 30",
          "2k5/pp3pp1/5n1p/5N2/4P3/8/PP3PPP/6K1 b - - 1 27",
          "2rr4/1p3p1p/p4N2/3bR3/5Pk1/8/PP1K3P/8 b - - 6 31",
          "2rr4/1p3p1p/p7/3b3N/4kP2/4R3/PP1K3P/8 b - - 2 29",
          "3k4/1pR3R1/p2p3p/3PB2P/2P5/1P6/P1K2r2/8 w - - 1 38",
          "R4Q2/1p5p/rk3n2/8/8/8/PP1r1PPP/1R4K1 w - - 3 29",
          "1r6/p4p1p/5k2/5p2/2pN4/P1n1P2P/6P1/6K1 w - - 0 29",
          "6k1/2p3pp/p1p5/3r4/P3R3/1PP5/5KPP/8 b - - 4 27",
          "8/2p2kpp/p3r3/P7/1Pp4R/2P5/5KPP/8 b - - 10 38",
          "r2r4/2R2B2/p2p4/3P1pkp/1PK5/6P1/7P/8 w - - 2 36"
          )),
          new PositionGroup("endgame", List.of(
          "8/p3kp1R/2p1p3/2p5/2P5/PP4r1/3K4/8 b - - 2 34",
          "8/p5k1/Q5p1/7p/4B3/4P1PP/P3PK2/8 b - - 1 33",
          "1R2bk2/6pp/5p2/1B6/4P3/6rP/3K4/8 b - - 5 38",
          "1R2bk2/6pp/5p2/1B6/4P3/7P/3K2r1/8 w - - 6 39",
          "1R3k2/5bpp/5p2/8/4P3/3BK2P/6r1/8 b - - 1 36",
          "3R4/8/p7/4k2p/8/1Pr2RPP/4K2r/8 w - - 23 49",
          "5k2/p7/6Q1/7p/4B3/4P1PP/P3PK2/8 b - - 0 34",
          "8/3k4/3p2p1/3P1p1n/1p1K4/1P4P1/P7/8 w - - 1 39",
          "8/4kpR1/4p3/1r6/2N1b2P/2K1P2P/8/8 b - - 0 40",
          "8/8/p1pk3p/P5p1/1PPK3P/6P1/8/8 b - - 0 51",
          "4R1R1/8/8/3P1k1P/1KP1p3/1P6/P7/8 w - - 3 50",
          "8/8/R1k1p3/3rP3/1p6/1P2p1p1/8/2K5 b - - 5 62",
          "8/pp6/4p3/4b1k1/1P4P1/P2KP3/8/8 w - - 3 43",
          "2R5/6r1/8/3N4/4PKPP/k4P2/8/8 b - - 0 62",
          "2R5/7r/8/8/4PK1P/1k2NPP1/8/8 b - - 0 60",
          "2R5/8/5P2/3N4/4P1PP/4K3/1k6/5r2 b - - 4 68",
          "8/8/1p3p2/1b2pp2/1P5k/4P3/6K1/8 b - - 1 45",
          "8/8/6R1/1k1rP3/1p6/1P2p3/6p1/3K4 w - - 2 66",
          "8/8/p1p5/P1Pk3p/1P5P/6K1/8/8 w - - 5 57",
          "8/p7/4p2p/r3k2P/2p1P3/4K3/8/8 b - - 2 42",
          "r7/p2R3p/4P1pk/8/8/8/r7/4K3 b - - 0 33",
          "8/1k6/3p4/PP4p1/2K2n2/8/8/1q6 b - - 3 50",
          "8/1k6/3p4/PP4p1/5n2/1K6/8/1q6 w - - 2 50",
          "8/8/1p2p3/6k1/4P1P1/8/1b2K3/q7 b - - 1 50",
          "8/R7/P7/6R1/2k1P3/5P2/7r/4K3 w - - 7 52",
          "8/p4p2/3pk2p/8/2K4P/7r/8/8 b - - 4 34",
          "8/p7/3pk2p/1K3p2/7P/7r/8/8 b - - 1 35",
          "8/pp3p2/8/4PK2/8/5k2/PP6/8 w - - 1 52",
          "4Q3/6k1/6p1/8/5K1p/6P1/7P/8 w - - 0 50",
          "8/4R3/2b3k1/5p2/2r4P/6K1/8/8 w - - 31 73",
          "8/8/1p6/3b1p2/1P3p2/3k4/8/4K3 w - - 16 57",
          "8/8/1p6/5pk1/1Pb2p2/8/6K1/8 b - - 3 50",
          "8/8/4k3/p2p1p1p/8/8/3K4/r7 b - - 1 41",
          "8/8/8/1p2K3/k2N1Pb1/8/8/7q b - - 1 59",
          "8/8/8/1p2KN2/k4Pb1/8/7p/8 b - - 0 58",
          "8/8/8/3kr2p/6PP/5K2/1p6/8 b - - 0 50",
          "8/8/8/8/P1p2p1P/2R5/1K4k1/8 w - - 0 43",
          "6Q1/8/8/7K/7P/4k3/p7/B7 b - - 0 57",
          "7k/7P/6P1/8/p3K3/P7/8/8 b - - 8 66",
          "7k/7P/6P1/8/p7/P4K2/8/8 b - - 20 72",
          "8/6kP/6P1/8/p4K2/P7/8/8 b - - 10 67",
          "8/8/2k5/2p2p2/2P2K2/8/4R3/8 b - - 0 53",
          "8/8/8/5R2/p7/1rk3P1/5K2/8 w - - 6 61",
          "8/p3k3/8/5rp1/1K6/7p/8/8 w - - 2 45",
          "8/p3k3/8/5rp1/8/1K5p/8/8 b - - 3 45",
          "8/3k4/8/8/PK6/3q4/8/2R5 b - - 11 57",
          "8/8/8/2q2b2/k7/5K2/8/6q1 b - - 3 71",
          "4k3/8/3KP3/3N4/8/8/8/8 b - - 6 64",
          "8/1Q3K2/8/p7/8/2k5/8/8 w - a6 0 61",
          "8/8/8/8/1k4P1/4K3/8/q7 b - - 3 67"
          )));

  /**
   * Runs the bench from the command line. With no arguments a summary of every evaluator is
   * printed, and the verbose flag adds a breakdown by position group and the positions themselves.
   *
   * @param args The command line arguments, as described by the usage text.
   */
  public static void main(final String[] args) {
    int batches = NO_BATCH_COUNT;
    boolean verbose = false;
    for (final String argument : args) {
      if (VERBOSE_FLAG.equals(argument)) {
        verbose = true;
      } else if (HELP_FLAG.equals(argument)) {
        printUsage();
        return;
      } else {
        try {
          batches = Integer.parseInt(argument);
        } catch (final NumberFormatException exception) {
          System.out.println("Unrecognised argument: " + argument);
          printUsage();
          return;
        }
      }
    }
    if (batches != NO_BATCH_COUNT && batches < 1) {
      System.out.println("The batch count must be at least one.");
      printUsage();
      return;
    }
    run(batches == NO_BATCH_COUNT ? DEFAULT_BATCHES : batches, verbose);
  }

  /**
   * Measures every evaluator over every position and prints the report.
   *
   * @param batches The number of timed batches each measurement is taken from.
   * @param verbose Whether to add a breakdown by position group and print the positions.
   */
  public static void run(final int batches, final boolean verbose) {
    final List<PreparedGroup> groups = new ArrayList<>(POSITION_GROUPS.size());
    final List<Board> everyBoard = new ArrayList<>();
    for (final PositionGroup group : POSITION_GROUPS) {
      final Board[] boards = new Board[group.fens().size()];
      for (int index = 0; index < boards.length; index++) {
        boards[index] = FenParser.parse(group.fens().get(index));
        everyBoard.add(boards[index]);
      }
      groups.add(new PreparedGroup(group.name(), boards));
    }
    final Board[] allBoards = everyBoard.toArray(new Board[0]);

    System.out.printf("Evaluation bench: %d positions, %d evaluators, %d batches of %,d "
                    + "evaluations, no search%n%n",
            allBoards.length, EVALUATORS.size(), batches, BATCH_PASSES * allBoards.length);

    printHeader("evaluator");
    for (final NamedEvaluator evaluator : EVALUATORS) {
      printRow(evaluator.name(), measure(evaluator.evaluator(), allBoards, batches));
    }
    System.out.println();

    if (verbose) {
      for (final NamedEvaluator evaluator : EVALUATORS) {
        System.out.println(evaluator.name() + " evaluator by position group");
        printHeader("group");
        for (final PreparedGroup group : groups) {
          printRow(group.name(), measure(evaluator.evaluator(), group.boards(), batches));
        }
        System.out.println();
      }
      for (final PositionGroup group : POSITION_GROUPS) {
        System.out.println(group.name() + " positions");
        for (final String fen : group.fens()) {
          System.out.printf(DETAIL_FORMAT, fen);
        }
        System.out.println();
      }
    }
  }

  /**
   * Measures one evaluator over the given boards. The boards are evaluated repeatedly until the
   * just in time compiler has settled before any figure is taken.
   *
   * @param evaluator The evaluator to measure.
   * @param boards The positions to evaluate.
   * @param batches The number of timed batches to take the time from.
   * @return The measurement.
   */
  private static Measurement measure(final BoardEvaluator evaluator, final Board[] boards,
                                     final int batches) {
    for (int batch = 0; batch < WARMUP_BATCHES; batch++) {
      sink += runBatch(evaluator, boards);
    }
    final long evaluations = (long) BATCH_PASSES * boards.length;
    final long[] nanos = new long[batches];
    for (int batch = 0; batch < batches; batch++) {
      final long start = System.nanoTime();
      final double batchSink = runBatch(evaluator, boards);
      nanos[batch] = System.nanoTime() - start;
      sink += batchSink;
    }
    Arrays.sort(nanos);
    return new Measurement(allocatedBytesPerEvaluation(evaluator, boards),
            nanos[0] / evaluations, nanos[(batches - 1) / 2] / evaluations,
            checksum(evaluator, boards));
  }

  /**
   * Measures how many bytes one evaluation allocates, as the smallest figure any of several batches
   * produced less what a batch that evaluates nothing produces. A host whose runtime does not report
   * thread allocation returns a negative figure.
   *
   * @param evaluator The evaluator to measure.
   * @param boards The positions to evaluate.
   * @return The bytes allocated per evaluation, or a negative number when the host cannot report it.
   */
  private static double allocatedBytesPerEvaluation(final BoardEvaluator evaluator,
                                                    final Board[] boards) {
    if (!(ManagementFactory.getThreadMXBean() instanceof final ThreadMXBean bean)
            || !bean.isThreadAllocatedMemoryEnabled()) {
      return -1;
    }
    long best = Long.MAX_VALUE;
    for (int pass = 0; pass < ALLOCATION_PASSES; pass++) {
      final long before = bean.getCurrentThreadAllocatedBytes();
      final double batchSink = runBatch(evaluator, boards);
      final long allocated = bean.getCurrentThreadAllocatedBytes() - before;
      sink += batchSink;
      best = Math.min(best, allocated);
    }
    long calibration = Long.MAX_VALUE;
    for (int pass = 0; pass < ALLOCATION_PASSES; pass++) {
      final long before = bean.getCurrentThreadAllocatedBytes();
      final double batchSink = runEmptyBatch(boards);
      final long allocated = bean.getCurrentThreadAllocatedBytes() - before;
      sink += batchSink;
      calibration = Math.min(calibration, allocated);
    }
    return Math.max(0, best - calibration) / (double) (BATCH_PASSES * boards.length);
  }

  /**
   * Evaluates every board once in order and folds the raw bits of every score into one number. The
   * result is mixed before it is returned, since a score is often a value whose low mantissa bits
   * are all zero and an unmixed fold would leave the low half of the checksum carrying almost
   * nothing.
   *
   * @param evaluator The evaluator to take the scores from.
   * @param boards The positions to evaluate.
   * @return The checksum of the scores.
   */
  private static long checksum(final BoardEvaluator evaluator, final Board[] boards) {
    long checksum = CHECKSUM_BASIS;
    for (final Board board : boards) {
      checksum = (checksum ^ Double.doubleToRawLongBits(evaluator.evaluate(board)))
              * CHECKSUM_PRIME;
    }
    checksum = (checksum ^ (checksum >>> 30)) * CHECKSUM_MIX_FIRST;
    checksum = (checksum ^ (checksum >>> 27)) * CHECKSUM_MIX_SECOND;
    return checksum ^ (checksum >>> 31);
  }

  /**
   * Evaluates every board once per pass for the length of one batch.
   *
   * @param evaluator The evaluator to run.
   * @param boards The positions to evaluate.
   * @return The sum of every score produced.
   */
  private static double runBatch(final BoardEvaluator evaluator, final Board[] boards) {
    double total = 0;
    for (int pass = 0; pass < BATCH_PASSES; pass++) {
      for (final Board board : boards) {
        total += evaluator.evaluate(board);
      }
    }
    return total;
  }

  /**
   * Walks every board once per pass for the length of one batch without evaluating anything, which
   * is what the allocation figure is measured against.
   *
   * @param boards The positions to walk.
   * @return A number derived from every board walked.
   */
  private static double runEmptyBatch(final Board[] boards) {
    double total = 0;
    for (int pass = 0; pass < BATCH_PASSES; pass++) {
      for (final Board board : boards) {
        total += board.getPlyCount();
      }
    }
    return total;
  }

  /**
   * Prints the header naming the measured columns.
   *
   * @param firstColumn The name of the column the rows are labelled by.
   */
  private static void printHeader(final String firstColumn) {
    System.out.printf(HEADER_FORMAT, firstColumn, "bytes/eval", "ns/eval best", "ns/eval med",
            "evals/sec best", "checksum");
  }

  /**
   * Prints one row of measurements.
   *
   * @param name The name of the evaluator or group the row reports on.
   * @param measurement The measurement to print.
   */
  private static void printRow(final String name, final Measurement measurement) {
    final String bytes = measurement.bytesPerEvaluation() < 0 ? NO_ALLOCATION
            : String.format("%,.1f", measurement.bytesPerEvaluation());
    System.out.printf(ROW_FORMAT, name, bytes, measurement.bestNanos(), measurement.medianNanos(),
            NANOSECONDS_PER_SECOND / Math.max(1, measurement.bestNanos()),
            measurement.checksum());
  }

  /**
   * Prints the usage text describing how the bench is run from the command line.
   */
  private static void printUsage() {
    System.out.println("""
            Usage:
              EvaluationBench [batches] [--verbose]
              EvaluationBench --help                   print this message

            Every evaluator is measured on every position with no search. Four numbers are
            reported. The allocation figure is the bytes one evaluation allocates, measured after
            a warmup, so it counts what survives the just in time compiler rather than what the
            source appears to allocate. The two times are the fastest and the median batch, and
            the rate is the reciprocal of the fastest. The checksum folds the raw bits of every
            score, so two builds that were meant to evaluate alike can be compared by it.

            The boards are parsed once and reused, and a player holds its legal moves once they
            have been asked for, so generating them is paid during the warmup and is not part of
            any measurement.

            A batch is a fixed number of passes over the whole position set. The batch argument
            sets how many timed batches each measurement is taken from, five by default. The
            verbose flag adds a breakdown by position group and prints the positions.

            Nothing here can fail, so the bench always exits with a zero status.""");
  }

  /**
   * The NamedEvaluator record pairs an evaluator with the name used for its row.
   *
   * @param name The name of the evaluator.
   * @param evaluator The evaluator itself.
   */
  private record NamedEvaluator(String name, BoardEvaluator evaluator) { }

  /**
   * The PositionGroup record names a set of positions of one phase.
   *
   * @param name The name of the group.
   * @param fens The Forsyth-Edwards Notation strings describing the positions.
   */
  private record PositionGroup(String name, List<String> fens) { }

  /**
   * The PreparedGroup record holds the boards a position group was parsed into.
   *
   * @param name The name of the group.
   * @param boards The positions themselves.
   */
  private record PreparedGroup(String name, Board[] boards) { }

  /**
   * The Measurement record holds what one evaluator produced over one set of positions.
   *
   * @param bytesPerEvaluation The bytes one evaluation allocates, or a negative number when the
   *                           host cannot report it.
   * @param bestNanos The nanoseconds one evaluation took in the fastest batch.
   * @param medianNanos The nanoseconds one evaluation took in the median batch, taking the earlier
   *                    of the two middle batches when the count is even.
   * @param checksum The checksum of every score produced.
   */
  private record Measurement(double bytesPerEvaluation, long bestNanos, long medianNanos,
                             long checksum) { }
}
