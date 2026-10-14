package engine.forTesting;

import engine.forPlayer.forAI.EndgameBoardEvaluator;
import engine.forPlayer.forAI.EvaluationWeights;
import engine.forPlayer.forAI.MiddlegameBoardEvaluator;
import engine.forPlayer.forAI.TaperedEvaluator;

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
 * The TexelTuner class tunes the weights of the tapered evaluator against the results of the games
 * a set of quiet positions was taken from, by the Texel method. The error of a set of weights is the mean
 * squared difference between each position's game result, scored one for a white win, one half for
 * a draw and zero for a black win, and the expected score 1 / (1 + 10^(-K * eval / 400)), where
 * eval is the evaluator's score of the position from white's side. The tuner adjusts one weight at
 * a time, stepping it up for as long as each step lowers the error and, if the first step up does
 * not, stepping it down the same way, and sweeps over every weight until a full sweep changes none.
 * The tapered evaluator is tuned through the middlegame and endgame weights together, and each
 * weight name is prefixed with the name of the evaluator that holds it and a dot.
 * <p>
 * The positions are read from an EPD file whose lines hold the first four fields of a FEN record
 * followed by {@code c9 "1-0";}, {@code c9 "0-1";} or {@code c9 "1/2-1/2";}, and every position
 * read is used. Positions are held as FEN text and parsed again on every pass, because holding
 * every board would take several gigabytes.
 * <p>
 * The scaling constant K is fitted to the evaluator's current weights unless it is given, and is
 * then held fixed while the weights are tuned, so that the tuned scores stay on the scale the
 * search was built around. After every sweep the weights are written to a checkpoint file, from
 * which a later run can resume.
 *
 * @author Aaron Ho
 */
public class TexelTuner {

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
  private record Position(String fen, double result) { }

  /**
   * The weights of one or more evaluators, addressed by a single index that runs through each set
   * of weights in turn. When there is more than one set, each name is prefixed with the name of its
   * set and a dot.
   */
  private static final class TunedWeights {

    /** The sets of weights, in index order. */
    private final List<EvaluationWeights> sets;

    /** The prefix of the names in each set, in the order of the sets. */
    private final List<String> prefixes;

    /**
     * Constructs a view over the given sets of weights.
     *
     * @param sets The sets of weights, in index order.
     * @param prefixes The prefix of the names in each set, in the order of the sets.
     */
    private TunedWeights(final List<EvaluationWeights> sets, final List<String> prefixes) {
      this.sets = sets;
      this.prefixes = prefixes;
    }

    /**
     * Returns a view over two sets of weights, the first set's indices before the second's, with
     * each name prefixed by its set's name and a dot.
     *
     * @param firstName The name of the first set.
     * @param first The first set of weights.
     * @param secondName The name of the second set.
     * @param second The second set of weights.
     * @return The view.
     */
    private static TunedWeights joined(final String firstName, final EvaluationWeights first,
                                       final String secondName, final EvaluationWeights second) {
      return new TunedWeights(List.of(first, second), List.of(firstName + ".", secondName + "."));
    }

    /**
     * Returns the number of weights in every set together.
     *
     * @return The number of weights.
     */
    private int size() {
      int size = 0;
      for (final EvaluationWeights set : sets) {
        size += set.size();
      }
      return size;
    }

    /**
     * Returns the name of the weight at the given index, with its set's prefix.
     *
     * @param index The index of the weight.
     * @return The name of the weight.
     */
    private String name(final int index) {
      final int set = setOf(index);
      return prefixes.get(set) + sets.get(set).name(indexInSet(index, set));
    }

    /**
     * Returns the value of the weight at the given index.
     *
     * @param index The index of the weight.
     * @return The value of the weight.
     */
    private double get(final int index) {
      final int set = setOf(index);
      return sets.get(set).get(indexInSet(index, set));
    }

    /**
     * Sets the value of the weight at the given index, which runs its set's change action.
     *
     * @param index The index of the weight.
     * @param value The new value of the weight.
     */
    private void set(final int index, final double value) {
      final int set = setOf(index);
      sets.get(set).set(indexInSet(index, set), value);
    }

    /**
     * Returns the position in the list of sets of the set holding the weight at the given index.
     *
     * @param index The index of the weight.
     * @return The position of its set.
     * @throws IndexOutOfBoundsException If no weight has the index.
     */
    private int setOf(final int index) {
      int end = 0;
      for (int set = 0; set < sets.size(); set++) {
        end += sets.get(set).size();
        if (index < end) {
          return set;
        }
      }
      throw new IndexOutOfBoundsException("No weight has index " + index);
    }

    /**
     * Returns the index within the given set of the weight at the given index.
     *
     * @param index The index of the weight.
     * @param set The position of the set holding it.
     * @return The weight's index within its set.
     */
    private int indexInSet(final int index, final int set) {
      int first = 0;
      for (int earlier = 0; earlier < set; earlier++) {
        first += sets.get(earlier).size();
      }
      return index - first;
    }
  }

  /**
   * Runs the tuner from the command line.
   *
   * @param args The command line arguments, as described by the usage text.
   * @throws Exception If the file cannot be read or a worker fails.
   */
  public static void main(final String[] args) throws Exception {
    Path file = null;
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

    if (file == null || limit < 1 || threads < 1 || sweeps < 0) {
      System.out.println("An EPD file is required, and the limit, thread count and sweep count " +
              "must be positive.");
      printUsage();
      return;
    }
    if (checkpoint == null) {
      checkpoint = Path.of("data", "tuned-tapered.txt");
    }

    final List<Position> positions = load(file, limit);
    System.out.printf(Locale.ROOT, "Tuning the tapered evaluator on %d positions with %d threads.%n",
            positions.size(), threads);
    if (positions.isEmpty()) {
      System.out.println("The file holds no positions.");
      return;
    }

    final TunedWeights weights = TunedWeights.joined("middlegame",
            MiddlegameBoardEvaluator.weights(), "endgame", EndgameBoardEvaluator.weights());
    if (resume) {
      loadCheckpoint(checkpoint, weights);
      System.out.println("Resumed from " + checkpoint);
    }

    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      final TexelTuner tuner = new TexelTuner(weights, positions, executor, threads);
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

  /** The weights being tuned. */
  private final TunedWeights weights;

  /** The positions the error is measured over. */
  private final List<Position> positions;

  /** The worker threads that evaluate the positions. */
  private final ExecutorService executor;

  /** The number of chunks the positions are split into. */
  private final int chunkCount;

  /**
   * Constructs a tuner.
   *
   * @param weights The weights to tune.
   * @param positions The positions the error is measured over.
   * @param executor The worker threads that evaluate the positions.
   * @param threads The number of worker threads.
   */
  private TexelTuner(final TunedWeights weights, final List<Position> positions,
                     final ExecutorService executor, final int threads) {
    this.weights = weights;
    this.positions = positions;
    this.executor = executor;
    this.chunkCount = Math.min(positions.size(), threads * CHUNKS_PER_THREAD);
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
    final int size = weights.size();
    final double[] initial = new double[size];
    final double[] steps = new double[size];
    for (int i = 0; i < size; i++) {
      initial[i] = weights.get(i);
      steps[i] = step(initial[i]);
    }
    final int fullIndex = indexOfUnprefixed(weights, FULL_MATERIAL_WEIGHT);
    final int zeroIndex = indexOfUnprefixed(weights, ZERO_MATERIAL_WEIGHT);

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
   * @return The mean squared error over the positions.
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
    return collect(sums) / positions.size();
  }

  /**
   * Returns the error of the given evaluations at the given scaling constant.
   *
   * @param evaluations The evaluation of each position, in position order.
   * @param k The scaling constant.
   * @return The mean squared error over the positions.
   */
  private double error(final double[] evaluations, final double k) {
    double sum = 0;
    for (int i = 0; i < evaluations.length; i++) {
      sum += squaredError(positions.get(i), evaluations[i], k);
    }
    return sum / positions.size();
  }

  /**
   * Returns a position's squared error.
   *
   * @param position The position.
   * @param evaluation The evaluation of the position from white's side.
   * @param k The scaling constant.
   * @return The squared error.
   */
  private double squaredError(final Position position, final double evaluation, final double k) {
    final double difference = position.result() - expectedScore(evaluation, k);
    return difference * difference;
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
   * Evaluates a position from white's side with the tapered evaluator.
   *
   * @param position The position.
   * @return The evaluator's score.
   */
  private double evaluate(final Position position) {
    return TaperedEvaluator.get().evaluate(FenParser.parse(position.fen()));
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
  private static boolean admissible(final TunedWeights weights, final int fullIndex,
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
  private static int indexOf(final TunedWeights weights, final String name) {
    for (int i = 0; i < weights.size(); i++) {
      if (weights.name(i).equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the index of the weight whose name, once any set prefix is removed, is the given name.
   *
   * @param weights The weights.
   * @param name The name without a prefix.
   * @return The first such weight's index, or -1 if there is none.
   */
  private static int indexOfUnprefixed(final TunedWeights weights, final String name) {
    for (int i = 0; i < weights.size(); i++) {
      final String candidate = weights.name(i);
      if (candidate.equals(name) || candidate.endsWith("." + name)) {
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
        positions.add(new Position(fen, result));
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
  private static void writeCheckpoint(final Path checkpoint, final TunedWeights weights,
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
  private static void loadCheckpoint(final Path checkpoint, final TunedWeights weights)
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

  /** Prints the command line usage. */
  private static void printUsage() {
    System.out.println("""
            Usage:
              TexelTuner <epd file> [options]

            Tunes the tapered evaluator's middlegame and endgame weights together, named
            middlegame.<NAME> and endgame.<NAME>.

            Options:
              --k=<value>           scaling constant to hold fixed. Fitted when absent.
              --fit-k-only          fit K, print it and the error, and stop.
              --limit=<n>           read at most n lines of the file.
              --threads=<n>         worker threads. Defaults to the processor count less two.
              --sweeps=<n>          stop after n sweeps over the weights.
              --checkpoint=<file>   where the weights are written after every sweep.
                                    Defaults to data/tuned-tapered.txt.
              --resume              load the checkpoint file before tuning.
              --help                print this message.

            Compile and run from the repository root:
              javac -nowarn -cp "lib/*" -sourcepath src -d out/tuner src/engine/forTesting/TexelTuner.java
              java -cp "out/tuner;lib/*" engine.forTesting.TexelTuner data/quiet-labeled.epd""");
  }
}
