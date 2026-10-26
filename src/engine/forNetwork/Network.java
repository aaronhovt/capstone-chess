package engine.forNetwork;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Network class holds the weights of the evaluation network and computes its output from a
 * pair of accumulators. The network has 768 inputs, an accumulator of {@link #hidden()} values for
 * each side computed from one shared weight matrix, a squared clipped ReLU, and one output.
 * <p>
 * Each piece sets one input in each side's accumulator, given by {@link #featureIndex}. An
 * accumulator is the feature bias plus the feature weight rows of the pieces on the board, and is
 * kept by {@link engine.forBoard.Board} as moves are made and unmade.
 * <p>
 * The weights are read from the quantised file the trainer writes (trainer/nnue.py describes the
 * format), and the evaluation is integer arithmetic that reproduces trainer/nnue.py's
 * evaluate_quantised exactly. The default network is read once, from the classpath resource
 * {@value #DEFAULT_NETWORK} or, failing that, the file of that name under the working directory,
 * and is shared read-only by every board and thread.
 *
 * @author Aaron Ho
 */
public final class Network {

  /** The path of the default network, as a classpath resource and relative to the working directory. */
  public static final String DEFAULT_NETWORK = "nets/default.nnue";

  /** The number of inputs: six piece types of two colours on 64 squares. */
  public static final int INPUTS = 768;

  /** The quantisation factor of the feature weights and biases, and the accumulator's clip ceiling. */
  public static final int QA = 255;

  /** The quantisation factor of the output weights. */
  public static final int QB = 1024;

  /** The factor that turns the network's output into centipawns. */
  public static final int SCALE = 400;

  /** The four bytes every network file starts with. */
  private static final int MAGIC = 'C' | 'C' << 8 | 'N' << 16 | 'N' << 24;

  /** The network file format this class reads. */
  private static final int VERSION = 1;

  /** The number of bytes before the weights: the magic and six int32 fields. */
  private static final int HEADER_BYTES = 28;

  /** The number of inputs each colour's pieces occupy. */
  private static final int COLOUR_INPUTS = 384;

  /** The number of inputs each piece type occupies. */
  private static final int TYPE_INPUTS = 64;

  /** The value that turns an engine square, numbered from a8, into a square numbered from a1. */
  private static final int VERTICAL_FLIP = 56;

  /** The default network, read when this class is first used. */
  private static final Network DEFAULT = loadDefault();

  /** The size of each side's accumulator. */
  private final int hidden;

  /** The feature weights, {@link #INPUTS} rows of {@link #hidden} values, row = feature index. */
  private final int[] featureWeights;

  /** The feature biases, {@link #hidden} values. */
  private final int[] featureBias;

  /** The output weights, the side to move's {@link #hidden} values followed by the other side's. */
  private final int[] outputWeights;

  /** The output bias, in units of QA * QB. */
  private final int outputBias;

  /**
   * Constructs a network from the contents of a network file.
   *
   * @param data The bytes of the file.
   * @throws IllegalArgumentException If the bytes are not a network file this class reads.
   */
  private Network(final byte[] data) {
    final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (data.length < HEADER_BYTES || buffer.getInt() != MAGIC) {
      throw new IllegalArgumentException("Not a network file.");
    }
    final int version = buffer.getInt();
    final int inputs = buffer.getInt();
    this.hidden = buffer.getInt();
    final int qa = buffer.getInt();
    final int qb = buffer.getInt();
    final int scale = buffer.getInt();
    if (version != VERSION || inputs != INPUTS || qa != QA || qb != QB || scale != SCALE
            || this.hidden <= 0) {
      throw new IllegalArgumentException("Unsupported network: version " + version + ", inputs " +
              inputs + ", hidden " + this.hidden + ", QA " + qa + ", QB " + qb + ", scale " + scale + ".");
    }
    final long expected = HEADER_BYTES + 2L * (INPUTS * (long) this.hidden + 3L * this.hidden) + 4;
    if (data.length != expected) {
      throw new IllegalArgumentException("A network of hidden size " + this.hidden + " is " +
              expected + " bytes, found " + data.length + ".");
    }
    this.featureWeights = readShorts(buffer, INPUTS * this.hidden);
    this.featureBias = readShorts(buffer, this.hidden);
    this.outputWeights = readShorts(buffer, 2 * this.hidden);
    this.outputBias = buffer.getInt();
  }

  /**
   * Reads the next values of a buffer as 16-bit integers, advancing its position past them.
   *
   * @param buffer The buffer to read, in little-endian order.
   * @param count The number of values to read.
   * @return The values, widened to ints.
   */
  private static int[] readShorts(final ByteBuffer buffer, final int count) {
    final int[] values = new int[count];
    for (int i = 0; i < count; i++) {
      values[i] = buffer.getShort();
    }
    return values;
  }

  /**
   * Returns the default network.
   *
   * @return The network read from {@value #DEFAULT_NETWORK}.
   */
  public static Network get() {
    return DEFAULT;
  }

  /**
   * Reads a network from a file.
   *
   * @param path The network file.
   * @return The network.
   * @throws IOException If the file cannot be read.
   * @throws IllegalArgumentException If the file is not a network file this class reads.
   */
  public static Network load(final Path path) throws IOException {
    return new Network(Files.readAllBytes(path));
  }

  /**
   * Reads the default network from the classpath, or from the working directory if the classpath
   * does not hold it.
   *
   * @return The default network.
   * @throws IllegalStateException If neither place holds a readable network.
   */
  private static Network loadDefault() {
    try (InputStream resource = Network.class.getResourceAsStream("/" + DEFAULT_NETWORK)) {
      if (resource != null) {
        return new Network(resource.readAllBytes());
      }
      return load(Path.of(DEFAULT_NETWORK));
    } catch (final IOException | IllegalArgumentException exception) {
      throw new IllegalStateException("The evaluation network " + DEFAULT_NETWORK + " could not " +
              "be read from the classpath or the working directory.", exception);
    }
  }

  /**
   * Returns the size of each side's accumulator.
   *
   * @return The number of values in one side's accumulator.
   */
  public int hidden() {
    return this.hidden;
  }

  /**
   * Returns the input a piece sets in one side's accumulator.
   *
   * @param perspectiveWhite True for White's accumulator, false for Black's.
   * @param pieceWhite True if the piece is White's.
   * @param pieceType The piece type's ordinal: pawn, knight, bishop, rook, queen, king.
   * @param square The piece's square, numbered from a8 as the engine numbers squares.
   * @return The feature index, from 0 to {@link #INPUTS} - 1.
   */
  public static int featureIndex(final boolean perspectiveWhite, final boolean pieceWhite,
                                 final int pieceType, final int square) {
    return (perspectiveWhite == pieceWhite ? 0 : COLOUR_INPUTS) + pieceType * TYPE_INPUTS +
            (perspectiveWhite ? square ^ VERTICAL_FLIP : square);
  }

  /**
   * Sets an accumulator to the feature biases.
   *
   * @param accumulators The array holding the accumulator.
   * @param offset The index of the accumulator's first value.
   */
  public void reset(final int[] accumulators, final int offset) {
    System.arraycopy(this.featureBias, 0, accumulators, offset, this.hidden);
  }

  /**
   * Adds a feature's weight row to an accumulator.
   *
   * @param accumulators The array holding the accumulator.
   * @param offset The index of the accumulator's first value.
   * @param feature The feature index.
   */
  public void add(final int[] accumulators, final int offset, final int feature) {
    final int row = feature * this.hidden;
    for (int i = 0; i < this.hidden; i++) {
      accumulators[offset + i] += this.featureWeights[row + i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with one feature removed and one added.
   *
   * @param accumulators The array holding both accumulators.
   * @param from The index of the source accumulator's first value.
   * @param to The index of the destination accumulator's first value.
   * @param removed The feature index removed.
   * @param added The feature index added.
   */
  public void move(final int[] accumulators, final int from, final int to, final int removed,
                   final int added) {
    final int removedRow = removed * this.hidden;
    final int addedRow = added * this.hidden;
    for (int i = 0; i < this.hidden; i++) {
      accumulators[to + i] = accumulators[from + i] - this.featureWeights[removedRow + i]
              + this.featureWeights[addedRow + i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with two features removed and one added.
   *
   * @param accumulators The array holding both accumulators.
   * @param from The index of the source accumulator's first value.
   * @param to The index of the destination accumulator's first value.
   * @param removed The first feature index removed.
   * @param alsoRemoved The second feature index removed.
   * @param added The feature index added.
   */
  public void capture(final int[] accumulators, final int from, final int to, final int removed,
                      final int alsoRemoved, final int added) {
    final int removedRow = removed * this.hidden;
    final int alsoRemovedRow = alsoRemoved * this.hidden;
    final int addedRow = added * this.hidden;
    for (int i = 0; i < this.hidden; i++) {
      accumulators[to + i] = accumulators[from + i] - this.featureWeights[removedRow + i]
              - this.featureWeights[alsoRemovedRow + i] + this.featureWeights[addedRow + i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with two features removed and two added.
   *
   * @param accumulators The array holding both accumulators.
   * @param from The index of the source accumulator's first value.
   * @param to The index of the destination accumulator's first value.
   * @param removed The first feature index removed.
   * @param alsoRemoved The second feature index removed.
   * @param added The first feature index added.
   * @param alsoAdded The second feature index added.
   */
  public void castle(final int[] accumulators, final int from, final int to, final int removed,
                     final int alsoRemoved, final int added, final int alsoAdded) {
    final int removedRow = removed * this.hidden;
    final int alsoRemovedRow = alsoRemoved * this.hidden;
    final int addedRow = added * this.hidden;
    final int alsoAddedRow = alsoAdded * this.hidden;
    for (int i = 0; i < this.hidden; i++) {
      accumulators[to + i] = accumulators[from + i] - this.featureWeights[removedRow + i]
              - this.featureWeights[alsoRemovedRow + i] + this.featureWeights[addedRow + i]
              + this.featureWeights[alsoAddedRow + i];
    }
  }

  /**
   * Returns the evaluation of a position from its two accumulators.
   *
   * @param accumulators The array holding both accumulators.
   * @param mover The index of the first value of the side to move's accumulator.
   * @param other The index of the first value of the other side's accumulator.
   * @return The evaluation in centipawns from the side to move's point of view.
   */
  public int evaluate(final int[] accumulators, final int mover, final int other) {
    long sum = 0;
    for (int i = 0; i < this.hidden; i++) {
      final int value = Math.min(Math.max(accumulators[mover + i], 0), QA);
      sum += value * value * this.outputWeights[i];
    }
    for (int i = 0; i < this.hidden; i++) {
      final int value = Math.min(Math.max(accumulators[other + i], 0), QA);
      sum += value * value * this.outputWeights[this.hidden + i];
    }
    return (int) ((sum / QA + this.outputBias) * SCALE / ((long) QA * QB));
  }
}
