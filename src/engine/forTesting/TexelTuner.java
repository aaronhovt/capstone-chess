package engine.forTesting;

import engine.forBoard.Board;
import engine.forPlayer.forAI.BoardEvaluator;
import engine.forPlayer.forAI.EndgameBoardEvaluator;
import engine.forPlayer.forAI.EvaluationWeights;
import engine.forPlayer.forAI.GameStateDetector;
import engine.forPlayer.forAI.GameStateDetector.GamePhase;
import engine.forPlayer.forAI.MiddlegameBoardEvaluator;
import engine.forPlayer.forAI.OpeningGameEvaluator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The TexelTuner class tunes the weights of one evaluator against the results of the games a set
 * of quiet positions was taken from, by the Texel method. The error of a set of weights is the mean
 * squared difference between each position's game result, scored one for a white win, one half for
 * a draw and zero for a black win, and the expected score 1 / (1 + 10^(-K * eval / 400)), where
 * eval is the evaluator's score of the position from white's side. The tuner adjusts one weight at
 * a time, stepping it up for as long as each step lowers the error and, if the first step up does
 * not, stepping it down the same way, and sweeps over every weight until a full sweep changes none.
 * <p>
 * The positions are read from an EPD file whose lines hold the first four fields of a FEN record
 * followed by {@code c9 "1-0";}, {@code c9 "0-1";} or {@code c9 "1/2-1/2";}. Each position is
 * assigned the phase the game state detector gives it as though 40 plies had been played, and only
 * the positions in the selected phases are used. Each phase either keeps its natural share of the
 * positions or is weighted so that it holds a given share of the error, so that an evaluator can
 * be fitted to the mix of phases it scores in search rather than the mix the file happens to hold.
 * Positions are held as FEN text and parsed again on every pass, because holding every board would
 * take several gigabytes.
 * <p>
 * The scaling constant K is fitted to the evaluator's current weights unless it is given, and is
 * then held fixed while the weights are tuned, so that the tuned scores stay on the scale the
 * search was built around. After every sweep the weights are written to a checkpoint file, from
 * which a later run can resume.
 *
 * @author Aaron Ho
 */
public class TexelTuner {

  /** The flag naming the evaluator to tune. */
  private static final String EVALUATOR_FLAG_PREFIX = "--evaluator=";

  /** The flag naming the phases whose positions are used. */
  private static final String PHASES_FLAG_PREFIX = "--phases=";

  /** The flag naming the phases whose positions are used and the share of the error each holds. */
  private static final String MIX_FLAG_PREFIX = "--mix=";

  /** The flag giving the scaling constant instead of fitting it. */
  private static final String K_FLAG_PREFIX = "--k=";

  /** The flag limiting how many lines of the file are read. */
  private static final String LIMIT_FLAG_PREFIX = "--limit=";

  /** The flag setting the number of worker threads. */
  private static final String THREADS_FLAG_PREFIX = "--threads=";

  /** The flag limiting the number of sweeps. */
  private static final String SWEEPS_FLAG_PREFIX = "--sweeps=";

  /** The flag naming the checkpoint file. */
  private static final String CHECKPOINT_FLAG_PREFIX = "--checkpoint=";

  /** The flag that loads the checkpoint file before tuning. */
  private static final String RESUME_FLAG = "--resume";

  /** The flag that fits K, prints it and stops. */
  private static final String FIT_K_ONLY_FLAG = "--fit-k-only";

  /** The flag that prints the usage text. */
  private static final String HELP_FLAG = "--help";

  /** The fullmove number given to every position so the detector treats it as reached at ply 40. */
  private static final String PHASE_DETECTION_COUNTERS = " 0 21";

  /** The marker separating the FEN fields of an EPD line from its result. */
  private static final String RESULT_MARKER = " c9 \"";

  /** The number of chunks each worker thread's share of the positions is split into. */
  private static final int CHUNKS_PER_THREAD = 4;

  /** The smallest scaling constant the fit considers. */
  private static final double MIN_K = 0.1;

  /** The largest scaling constant the fit considers. */
  private static final double MAX_K = 4.0;

  /** The width of the interval the scaling constant fit stops at. */
  private static final double K_TOLERANCE = 1e-4;

  /** The name of the middlegame weight that must stay above {@link #ZERO_MATERIAL_WEIGHT}. */
  private static final String FULL_MATERIAL_WEIGHT = "KING_TERMS_FULL_MATERIAL";

  /** The name of the middlegame weight that must stay below {@link #FULL_MATERIAL_WEIGHT}. */
  private static final String ZERO_MATERIAL_WEIGHT = "KING_TERMS_ZERO_MATERIAL";

  /** A position read from the file. */
  private record Position(String fen, double result, GamePhase phase) { }

  /**
   * An evaluator that can be tuned, together with the share of the error each phase holds by
   * default.
   *
   * @param name The name used on the command line.
   * @param evaluator The evaluator.
   * @param weights The evaluator's weights.
   * @param defaultMix The share of the error each phase holds when no phases are named, indexed by
   *                   phase ordinal. A phase with a share of zero is not used, and a negative
   *                   share leaves the phase at its natural share of the positions.
   */
  private record Target(String name, BoardEvaluator evaluator, EvaluationWeights weights,
                        double[] defaultMix) { }

  /** The share marking a used phase that keeps its natural share of the positions. */
  private static final double NATURAL_SHARE = -1;

  /**
   * Runs the tuner from the command line.
   *
   * @param args The command line arguments, as described by the usage text.
   * @throws Exception If the file cannot be read or a worker fails.
   */
  public static void main(final String[] args) throws Exception {
    Path file = null;
    Target target = null;
    double[] mix = null;
    double k = Double.NaN;
    int limit = Integer.MAX_VALUE;
    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    int sweeps = Integer.MAX_VALUE;
    Path checkpoint = null;
    boolean resume = false;
    boolean fitKOnly = false;

    try {
      for (final String argument : args) {
        if (HELP_FLAG.equals(argument)) {
          printUsage();
          return;
        } else if (argument.startsWith(EVALUATOR_FLAG_PREFIX)) {
          target = target(argument.substring(EVALUATOR_FLAG_PREFIX.length()));
        } else if (argument.startsWith(PHASES_FLAG_PREFIX)) {
          if (mix != null) {
            throw new IllegalArgumentException("Give either --phases or --mix, not both.");
          }
          mix = parsePhases(argument.substring(PHASES_FLAG_PREFIX.length()));
        } else if (argument.startsWith(MIX_FLAG_PREFIX)) {
          if (mix != null) {
            throw new IllegalArgumentException("Give either --phases or --mix, not both.");
          }
          mix = parseMix(argument.substring(MIX_FLAG_PREFIX.length()));
        } else if (argument.startsWith(K_FLAG_PREFIX)) {
          k = Double.parseDouble(argument.substring(K_FLAG_PREFIX.length()));
        } else if (argument.startsWith(LIMIT_FLAG_PREFIX)) {
          limit = Integer.parseInt(argument.substring(LIMIT_FLAG_PREFIX.length()));
        } else if (argument.startsWith(THREADS_FLAG_PREFIX)) {
          threads = Integer.parseInt(argument.substring(THREADS_FLAG_PREFIX.length()));
        } else if (argument.startsWith(SWEEPS_FLAG_PREFIX)) {
          sweeps = Integer.parseInt(argument.substring(SWEEPS_FLAG_PREFIX.length()));
        } else if (argument.startsWith(CHECKPOINT_FLAG_PREFIX)) {
          checkpoint = Path.of(argument.substring(CHECKPOINT_FLAG_PREFIX.length()));
        } else if (RESUME_FLAG.equals(argument)) {
          resume = true;
        } else if (FIT_K_ONLY_FLAG.equals(argument)) {
          fitKOnly = true;
        } else if (!argument.startsWith("--") && file == null) {
          file = Path.of(argument);
        } else {
          throw new IllegalArgumentException("Unrecognised argument: " + argument);
        }
      }
    } catch (final IllegalArgumentException exception) {
      System.out.println(exception.getMessage());
      printUsage();
      return;
    }

    if (file == null || target == null || limit < 1 || threads < 1 || sweeps < 0) {
      System.out.println("An EPD file and an evaluator are required, and the limit, thread " +
              "count and sweep count must be positive.");
      printUsage();
      return;
    }
    if (mix == null) {
      mix = target.defaultMix();
    }
    if (checkpoint == null) {
      checkpoint = Path.of("data", "tuned-" + target.name() + ".txt");
    }

    final List<Position> all = load(file, limit);
    final List<Position> positions = new ArrayList<>();
    final int[] phaseCounts = new int[GamePhase.values().length];
    final int[] usedCounts = new int[GamePhase.values().length];
    for (final Position position : all) {
      final int phase = position.phase().ordinal();
      phaseCounts[phase]++;
      if (mix[phase] != 0) {
        usedCounts[phase]++;
        positions.add(position);
      }
    }
    System.out.printf(Locale.ROOT, "Read %d positions: %d opening, %d middlegame, %d endgame.%n",
            all.size(), phaseCounts[GamePhase.OPENING.ordinal()],
            phaseCounts[GamePhase.MIDDLEGAME.ordinal()], phaseCounts[GamePhase.ENDGAME.ordinal()]);

    final double[] phaseWeights = new double[mix.length];
    for (final GamePhase phase : GamePhase.values()) {
      final int index = phase.ordinal();
      if (mix[index] > 0 && usedCounts[index] == 0) {
        System.out.println("No positions are in the " + phase + " phase, which the mix uses.");
        return;
      }
      phaseWeights[index] = mix[index] > 0 ? mix[index] / usedCounts[index] :
              mix[index] == NATURAL_SHARE ? 1.0 : 0.0;
    }
    System.out.printf(Locale.ROOT, "Tuning the %s evaluator on %d positions with %d threads, " +
                    "error shares %s.%n", target.name(), positions.size(), threads,
            describeShares(phaseWeights, usedCounts));
    if (positions.isEmpty()) {
      System.out.println("No positions are in the selected phases.");
      return;
    }

    final EvaluationWeights weights = target.weights();
    if (resume) {
      loadCheckpoint(checkpoint, weights);
      System.out.println("Resumed from " + checkpoint);
    }

    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      final TexelTuner tuner = new TexelTuner(target, positions, phaseWeights, executor, threads);
      if (Double.isNaN(k)) {
        k = tuner.fitK();
        System.out.printf(Locale.ROOT, "Fitted K = %.4f%n", k);
      }
      if (fitKOnly) {
        System.out.printf(Locale.ROOT, "Error at K = %.4f: %.8f%n", k, tuner.error(k));
        return;
      }
      tuner.tune(k, sweeps, checkpoint);
    } finally {
      executor.shutdownNow();
    }
  }

  /** The evaluator being tuned and its weights. */
  private final Target target;

  /** The positions the error is measured over. */
  private final List<Position> positions;

  /** The weight of each position's squared error, indexed by the position's phase ordinal. */
  private final double[] phaseWeights;

  /** The sum of the weights of all the positions. */
  private final double totalWeight;

  /** The worker threads that evaluate the positions. */
  private final ExecutorService executor;

  /** The number of chunks the positions are split into. */
  private final int chunkCount;

  /**
   * Constructs a tuner.
   *
   * @param target The evaluator to tune.
   * @param positions The positions the error is measured over.
   * @param phaseWeights The weight of each position's squared error, indexed by phase ordinal.
   * @param executor The worker threads that evaluate the positions.
   * @param threads The number of worker threads.
   */
  private TexelTuner(final Target target, final List<Position> positions,
                     final double[] phaseWeights, final ExecutorService executor,
                     final int threads) {
    this.target = target;
    this.positions = positions;
    this.phaseWeights = phaseWeights;
    this.executor = executor;
    this.chunkCount = Math.min(positions.size(), threads * CHUNKS_PER_THREAD);

    double total = 0;
    for (final Position position : positions) {
      total += phaseWeights[position.phase().ordinal()];
    }
    this.totalWeight = total;
  }

  /**
   * Tunes the weights by local search with the scaling constant held fixed, writing the weights to
   * the checkpoint file after every sweep and printing the changed weights at the end.
   *
   * @param k The scaling constant.
   * @param maxSweeps The largest number of sweeps to run.
   * @param checkpoint The file the weights are written to.
   * @throws Exception If a worker fails or the checkpoint cannot be written.
   */
  private void tune(final double k, final int maxSweeps, final Path checkpoint) throws Exception {
    final EvaluationWeights weights = target.weights();
    final int size = weights.size();
    final double[] initial = new double[size];
    final double[] steps = new double[size];
    for (int i = 0; i < size; i++) {
      initial[i] = weights.get(i);
      steps[i] = step(initial[i]);
    }
    final int fullIndex = indexOf(weights, FULL_MATERIAL_WEIGHT);
    final int zeroIndex = indexOf(weights, ZERO_MATERIAL_WEIGHT);

    double bestError = error(k);
    System.out.printf(Locale.ROOT, "Starting error %.8f at K = %.4f%n", bestError, k);

    for (int sweep = 1; sweep <= maxSweeps; sweep++) {
      final long start = System.nanoTime();
      int changed = 0;

      for (int i = 0; i < size; i++) {
        final double value = weights.get(i);
        double best = value;

        for (final double direction : new double[] {1, -1}) {
          double candidate = best + direction * steps[i];
          while (true) {
            weights.set(i, candidate);
            if (!admissible(weights, fullIndex, zeroIndex)) {
              break;
            }
            final double candidateError = error(k);
            if (candidateError >= bestError) {
              break;
            }
            bestError = candidateError;
            best = candidate;
            candidate += direction * steps[i];
          }
          if (best != value) {
            break;
          }
        }

        weights.set(i, best);
        if (best != value) {
          changed++;
        }
      }

      writeCheckpoint(checkpoint, weights, k, bestError, sweep);
      System.out.printf(Locale.ROOT, "Sweep %d: error %.8f, %d weights changed, %.1f s%n",
              sweep, bestError, changed, (System.nanoTime() - start) / 1e9);
      if (changed == 0) {
        break;
      }
    }

    System.out.println();
    System.out.println("Weights that changed:");
    for (int i = 0; i < size; i++) {
      if (weights.get(i) != initial[i]) {
        System.out.printf(Locale.ROOT, "  %-36s %10s -> %s%n", weights.name(i),
                format(initial[i]), format(weights.get(i)));
      }
    }
  }

  /**
   * Fits the scaling constant that minimises the error of the current weights, by golden section
   * search over the evaluations computed once.
   *
   * @return The fitted scaling constant.
   * @throws Exception If a worker fails.
   */
  private double fitK() throws Exception {
    final double[] evaluations = evaluations();
    final double ratio = (Math.sqrt(5) - 1) / 2;
    double low = MIN_K;
    double high = MAX_K;
    double a = high - ratio * (high - low);
    double b = low + ratio * (high - low);
    double errorA = error(evaluations, a);
    double errorB = error(evaluations, b);

    while (high - low > K_TOLERANCE) {
      if (errorA < errorB) {
        high = b;
        b = a;
        errorB = errorA;
        a = high - ratio * (high - low);
        errorA = error(evaluations, a);
      } else {
        low = a;
        a = b;
        errorA = errorB;
        b = low + ratio * (high - low);
        errorB = error(evaluations, b);
      }
    }

    return (low + high) / 2;
  }

  /**
   * Returns the error of the current weights at the given scaling constant.
   *
   * @param k The scaling constant.
   * @return The weighted mean squared error over the positions.
   * @throws Exception If a worker fails.
   */
  private double error(final double k) throws Exception {
    final List<Future<Double>> sums = new ArrayList<>();
    for (int chunk = 0; chunk < chunkCount; chunk++) {
      final int from = chunkStart(chunk);
      final int to = chunkStart(chunk + 1);
      sums.add(executor.submit(() -> {
        double sum = 0;
        for (int i = from; i < to; i++) {
          final Position position = positions.get(i);
          sum += squaredError(position, evaluate(position), k);
        }
        return sum;
      }));
    }
    return collect(sums) / totalWeight;
  }

  /**
   * Returns the error of the given evaluations at the given scaling constant.
   *
   * @param evaluations The evaluation of each position, in position order.
   * @param k The scaling constant.
   * @return The weighted mean squared error over the positions.
   */
  private double error(final double[] evaluations, final double k) {
    double sum = 0;
    for (int i = 0; i < evaluations.length; i++) {
      sum += squaredError(positions.get(i), evaluations[i], k);
    }
    return sum / totalWeight;
  }

  /**
   * Returns a position's squared error, multiplied by the weight of its phase.
   *
   * @param position The position.
   * @param evaluation The evaluation of the position from white's side.
   * @param k The scaling constant.
   * @return The weighted squared error.
   */
  private double squaredError(final Position position, final double evaluation, final double k) {
    final double difference = position.result() - expectedScore(evaluation, k);
    return phaseWeights[position.phase().ordinal()] * difference * difference;
  }

  /**
   * Evaluates every position with the current weights.
   *
   * @return The evaluation of each position, in position order.
   * @throws Exception If a worker fails.
   */
  private double[] evaluations() throws Exception {
    final double[] evaluations = new double[positions.size()];
    final List<Future<Double>> done = new ArrayList<>();
    for (int chunk = 0; chunk < chunkCount; chunk++) {
      final int from = chunkStart(chunk);
      final int to = chunkStart(chunk + 1);
      done.add(executor.submit(() -> {
        for (int i = from; i < to; i++) {
          evaluations[i] = evaluate(positions.get(i));
        }
        return 0.0;
      }));
    }
    collect(done);
    return evaluations;
  }

  /**
   * Returns the index of the first position in the given chunk.
   *
   * @param chunk The chunk, or the chunk count for the end of the last chunk.
   * @return The index of the chunk's first position.
   */
  private int chunkStart(final int chunk) {
    return (int) ((long) positions.size() * chunk / chunkCount);
  }

  /**
   * Evaluates a position from white's side with the evaluator being tuned.
   *
   * @param position The position.
   * @return The evaluator's score.
   */
  private double evaluate(final Position position) {
    return target.evaluator().evaluate(FenParser.parse(position.fen()));
  }

  /**
   * Waits for the given tasks and returns the sum of their results.
   *
   * @param tasks The tasks.
   * @return The sum of their results.
   * @throws Exception If a task failed.
   */
  private static double collect(final List<Future<Double>> tasks) throws Exception {
    double total = 0;
    try {
      for (final Future<Double> task : tasks) {
        total += task.get();
      }
    } catch (final ExecutionException exception) {
      throw exception.getCause() instanceof Exception cause ? cause : exception;
    }
    return total;
  }

  /**
   * Returns the expected score for white of a position with the given evaluation.
   *
   * @param evaluation The evaluation from white's side.
   * @param k The scaling constant.
   * @return The expected score, from zero to one.
   */
  private static double expectedScore(final double evaluation, final double k) {
    return 1.0 / (1.0 + Math.pow(10.0, -k * evaluation / 400.0));
  }

  /**
   * Returns the step a weight is adjusted by: a twentieth for a weight smaller than two in
   * magnitude, which is a factor or a fraction, a hundredth of the weight for one of a thousand
   * or more, and one otherwise.
   *
   * @param value The weight's starting value.
   * @return The step.
   */
  private static double step(final double value) {
    final double magnitude = Math.abs(value);
    if (magnitude < 2) {
      return 0.05;
    }
    if (magnitude >= 1000) {
      return Math.rint(magnitude / 100);
    }
    return 1.0;
  }

  /**
   * Returns whether the weights keep the middlegame king terms' full material value above their
   * zero material value. Weights without those two always pass.
   *
   * @param weights The weights.
   * @param fullIndex The index of the full material weight, or -1.
   * @param zeroIndex The index of the zero material weight, or -1.
   * @return True if the weights are admissible.
   */
  private static boolean admissible(final EvaluationWeights weights, final int fullIndex,
                                    final int zeroIndex) {
    return fullIndex < 0 || zeroIndex < 0 || weights.get(fullIndex) > weights.get(zeroIndex);
  }

  /**
   * Returns the index of the named weight.
   *
   * @param weights The weights.
   * @param name The name.
   * @return The weight's index, or -1 if there is none by that name.
   */
  private static int indexOf(final EvaluationWeights weights, final String name) {
    for (int i = 0; i < weights.size(); i++) {
      if (weights.name(i).equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Reads positions from an EPD file.
   *
   * @param file The file.
   * @param limit The largest number of lines to read.
   * @return The positions, in file order.
   * @throws IOException If the file cannot be read.
   * @throws IllegalArgumentException If a line is not in the expected form.
   */
  private static List<Position> load(final Path file, final int limit) throws IOException {
    final GameStateDetector detector = GameStateDetector.get();
    final List<Position> positions = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while (positions.size() < limit && (line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        final int marker = line.indexOf(RESULT_MARKER);
        if (marker < 0) {
          throw new IllegalArgumentException("No result in line: " + line);
        }
        final String fen = line.substring(0, marker);
        final String label = line.substring(marker + RESULT_MARKER.length(),
                line.indexOf('"', marker + RESULT_MARKER.length()));
        final double result = switch (label) {
          case "1-0" -> 1.0;
          case "0-1" -> 0.0;
          case "1/2-1/2" -> 0.5;
          default -> throw new IllegalArgumentException("Unknown result in line: " + line);
        };
        final Board board = FenParser.parse(fen + PHASE_DETECTION_COUNTERS);
        positions.add(new Position(fen, result, detector.detectGamePhase(board)));
      }
    }
    return positions;
  }

  /**
   * Writes the weights to a checkpoint file, one {@code NAME value} line per weight after a
   * comment line recording the scaling constant, the error and the sweep.
   *
   * @param checkpoint The file.
   * @param weights The weights.
   * @param k The scaling constant.
   * @param error The error of the weights.
   * @param sweep The sweep just finished.
   * @throws IOException If the file cannot be written.
   */
  private static void writeCheckpoint(final Path checkpoint, final EvaluationWeights weights,
                                      final double k, final double error, final int sweep)
          throws IOException {
    if (checkpoint.getParent() != null) {
      Files.createDirectories(checkpoint.getParent());
    }
    try (PrintWriter writer = new PrintWriter(
            Files.newBufferedWriter(checkpoint, StandardCharsets.UTF_8))) {
      writer.printf(Locale.ROOT, "# K %.6f error %.10f sweep %d%n", k, error, sweep);
      for (int i = 0; i < weights.size(); i++) {
        writer.printf(Locale.ROOT, "%s %s%n", weights.name(i), format(weights.get(i)));
      }
    }
  }

  /**
   * Sets the weights named in a checkpoint file to the values it records. Comment lines are
   * skipped.
   *
   * @param checkpoint The file.
   * @param weights The weights.
   * @throws IOException If the file cannot be read.
   * @throws IllegalArgumentException If the file names a weight that does not exist.
   */
  private static void loadCheckpoint(final Path checkpoint, final EvaluationWeights weights)
          throws IOException {
    for (final String line : Files.readAllLines(checkpoint, StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      final String[] fields = line.trim().split("\\s+");
      final int index = indexOf(weights, fields[0]);
      if (index < 0) {
        throw new IllegalArgumentException("Unknown weight in checkpoint: " + fields[0]);
      }
      weights.set(index, Double.parseDouble(fields[1]));
    }
  }

  /**
   * Formats a weight value without trailing zeros.
   *
   * @param value The value.
   * @return The formatted value.
   */
  private static String format(final double value) {
    final String text = String.format(Locale.ROOT, "%.4f", value);
    return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
  }

  /**
   * Returns the tuning target with the given name.
   *
   * @param name The name: opening, middlegame or endgame.
   * @return The target.
   * @throws IllegalArgumentException If the name is unknown.
   */
  private static Target target(final String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "opening" -> new Target("opening", OpeningGameEvaluator.get(),
              OpeningGameEvaluator.weights(), new double[] {NATURAL_SHARE, NATURAL_SHARE, 0});
      case "middlegame" -> new Target("middlegame", MiddlegameBoardEvaluator.get(),
              MiddlegameBoardEvaluator.weights(), new double[] {15, 56, 29});
      case "endgame" -> new Target("endgame", EndgameBoardEvaluator.get(),
              EndgameBoardEvaluator.weights(), new double[] {0, 0, NATURAL_SHARE});
      default -> throw new IllegalArgumentException("Unknown evaluator: " + name);
    };
  }

  /**
   * Parses a comma separated list of phase names, in any case, into a mix in which every named
   * phase keeps its natural share of the positions.
   *
   * @param value The list.
   * @return The mix, indexed by phase ordinal.
   * @throws IllegalArgumentException If a name is unknown or the list names no phase.
   */
  private static double[] parsePhases(final String value) {
    final double[] mix = new double[GamePhase.values().length];
    boolean any = false;
    for (final String name : value.split(",")) {
      if (!name.isBlank()) {
        mix[GamePhase.valueOf(name.trim().toUpperCase(Locale.ROOT)).ordinal()] = NATURAL_SHARE;
        any = true;
      }
    }
    if (!any) {
      throw new IllegalArgumentException("No phases named: " + value);
    }
    return mix;
  }

  /**
   * Parses a comma separated list of {@code phase:share} pairs, in any case, into a mix in which
   * each named phase holds the given share of the error. Shares are relative and need not sum to
   * any total.
   *
   * @param value The list.
   * @return The mix, indexed by phase ordinal.
   * @throws IllegalArgumentException If a pair is malformed, a name is unknown, a share is not
   *         positive or the list names no phase.
   */
  private static double[] parseMix(final String value) {
    final double[] mix = new double[GamePhase.values().length];
    boolean any = false;
    for (final String pair : value.split(",")) {
      if (pair.isBlank()) {
        continue;
      }
      final String[] parts = pair.split(":");
      if (parts.length != 2) {
        throw new IllegalArgumentException("A mix entry must be phase:share: " + pair);
      }
      final double share = Double.parseDouble(parts[1].trim());
      if (!(share > 0)) {
        throw new IllegalArgumentException("A share must be positive: " + pair);
      }
      mix[GamePhase.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)).ordinal()] = share;
      any = true;
    }
    if (!any) {
      throw new IllegalArgumentException("No phases named: " + value);
    }
    return mix;
  }

  /**
   * Describes the share of the error each used phase holds.
   *
   * @param phaseWeights The weight of each position's squared error, indexed by phase ordinal.
   * @param usedCounts The number of positions used in each phase, indexed by phase ordinal.
   * @return A description such as {@code opening 15.0%, middlegame 56.0%, endgame 29.0%}.
   */
  private static String describeShares(final double[] phaseWeights, final int[] usedCounts) {
    double total = 0;
    for (int i = 0; i < phaseWeights.length; i++) {
      total += phaseWeights[i] * usedCounts[i];
    }
    final List<String> parts = new ArrayList<>();
    for (final GamePhase phase : GamePhase.values()) {
      final int i = phase.ordinal();
      if (phaseWeights[i] > 0) {
        parts.add(String.format(Locale.ROOT, "%s %.1f%%", phase.name().toLowerCase(Locale.ROOT),
                100 * phaseWeights[i] * usedCounts[i] / total));
      }
    }
    return String.join(", ", parts);
  }

  /** Prints the command line usage. */
  private static void printUsage() {
    System.out.println("""
            Usage:
              TexelTuner <epd file> --evaluator=<opening|middlegame|endgame> [options]

            Options:
              --phases=<list>       comma separated phases to tune on (opening, middlegame,
                                    endgame), each at its natural share of the positions.
              --mix=<list>          comma separated phase:share pairs, such as
                                    opening:15,middlegame:56,endgame:29. Each phase's
                                    positions are weighted so it holds that share of the
                                    error. Give --phases or --mix, not both. Defaults:
                                    middlegame uses that mix, endgame uses endgame,
                                    opening uses opening and middlegame.
              --k=<value>           scaling constant to hold fixed. Fitted when absent.
              --fit-k-only          fit K, print it and the error, and stop.
              --limit=<n>           read at most n lines of the file.
              --threads=<n>         worker threads. Defaults to the processor count less two.
              --sweeps=<n>          stop after n sweeps over the weights.
              --checkpoint=<file>   where the weights are written after every sweep.
                                    Defaults to data/tuned-<evaluator>.txt.
              --resume              load the checkpoint file before tuning.
              --help                print this message.

            Compile and run from the repository root:
              javac -nowarn -cp "lib/*" -sourcepath src -d out/tuner src/engine/forTesting/TexelTuner.java
              java -cp "out/tuner;lib/*" engine.forTesting.TexelTuner data/quiet-labeled.epd --evaluator=middlegame""");
  }
}
