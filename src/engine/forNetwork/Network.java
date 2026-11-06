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
 * each side computed from one shared weight matrix, a squared clipped ReLU, and one output. The
 * output weights and bias come in {@link #buckets()} sets, and the number of pieces on the board
 * chooses the set an evaluation uses.
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

  /** The network file format with one set of output weights. */
  private static final int SINGLE_BUCKET_VERSION = 1;

  /** The network file format that gives the number of output buckets. */
  private static final int BUCKETED_VERSION = 2;

  /** The number of bytes before the weights in a single bucket file: the magic and six int32 fields. */
  private static final int SINGLE_BUCKET_HEADER_BYTES = 28;

  /** The number of bytes before the weights in a bucketed file: the magic and seven int32 fields. */
  private static final int BUCKETED_HEADER_BYTES = 32;

  /** The most pieces a board holds; the bucket count must divide it. */
  private static final int MAX_PIECES = 32;

  /** The number of pieces, the two kings, below which no bucket starts. */
  private static final int MIN_PIECES = 2;

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

  /** The feature weights, {@link #INPUTS} rows of {@link #hidden} values, indexed by feature. */
  private final int[][] featureWeights;

  /** The feature biases, {@link #hidden} values. */
  private final int[] featureBias;

  /** The number of sets of output weights and biases. */
  private final int buckets;

  /** The output bucket of each piece count from 0 to {@link Long#SIZE}. */
  private final int[] bucketOfPieceCount;

  /**
   * The output weights applied to the side to move's accumulator, {@link #hidden} values per
   * bucket.
   */
  private final int[][] moverWeights;

  /**
   * The output weights applied to the other side's accumulator, {@link #hidden} values per bucket.
   */
  private final int[][] otherWeights;

  /** The output bias of each bucket, in units of QA * QB. */
  private final int[] outputBias;

  /**
   * Constructs a network from the contents of a network file.
   *
   * @param data The bytes of the file.
   * @throws IllegalArgumentException If the bytes are not a network file this class reads.
   */
  private Network(final byte[] data) {
    final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (data.length < SINGLE_BUCKET_HEADER_BYTES || buffer.getInt() != MAGIC) {
      throw new IllegalArgumentException("Not a network file.");
    }
    final int version = buffer.getInt();
    final int inputs = buffer.getInt();
    this.hidden = buffer.getInt();
    final int qa = buffer.getInt();
    final int qb = buffer.getInt();
    final int scale = buffer.getInt();
    final int headerBytes;
    if (version == SINGLE_BUCKET_VERSION) {
      headerBytes = SINGLE_BUCKET_HEADER_BYTES;
      this.buckets = 1;
    } else if (version == BUCKETED_VERSION && data.length >= BUCKETED_HEADER_BYTES) {
      headerBytes = BUCKETED_HEADER_BYTES;
      this.buckets = buffer.getInt();
    } else {
      throw new IllegalArgumentException("Unsupported network file version " + version + ".");
    }
    if (inputs != INPUTS || qa != QA || qb != QB || scale != SCALE || this.hidden <= 0 ||
            this.buckets <= 0 || MAX_PIECES % this.buckets != 0) {
      throw new IllegalArgumentException("Unsupported network: version " + version + ", inputs " +
              inputs + ", hidden " + this.hidden + ", QA " + qa + ", QB " + qb + ", scale " + scale +
              ", buckets " + this.buckets + ".");
    }
    final int piecesPerBucket = MAX_PIECES / this.buckets;
    this.bucketOfPieceCount = new int[Long.SIZE + 1];
    for (int pieceCount = 0; pieceCount <= Long.SIZE; pieceCount++) {
      this.bucketOfPieceCount[pieceCount] =
              Math.min(Math.max(pieceCount - MIN_PIECES, 0) / piecesPerBucket, this.buckets - 1);
    }
    final long expected = headerBytes + 2L * (INPUTS * (long) this.hidden + this.hidden +
            2L * this.buckets * this.hidden) + 4L * this.buckets;
    if (data.length != expected) {
      throw new IllegalArgumentException("A network of hidden size " + this.hidden + " and " +
              this.buckets + " buckets is " + expected + " bytes, found " + data.length + ".");
    }
    this.featureWeights = new int[INPUTS][];
    for (int feature = 0; feature < INPUTS; feature++) {
      this.featureWeights[feature] = readShorts(buffer, this.hidden);
    }
    this.featureBias = readShorts(buffer, this.hidden);
    this.moverWeights = new int[this.buckets][];
    this.otherWeights = new int[this.buckets][];
    for (int bucket = 0; bucket < this.buckets; bucket++) {
      this.moverWeights[bucket] = readShorts(buffer, this.hidden);
      this.otherWeights[bucket] = readShorts(buffer, this.hidden);
    }
    this.outputBias = new int[this.buckets];
    for (int bucket = 0; bucket < this.buckets; bucket++) {
      this.outputBias[bucket] = buffer.getInt();
    }
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
   * Returns the number of sets of output weights and biases.
   *
   * @return The number of output buckets.
   */
  public int buckets() {
    return this.buckets;
  }

  /**
   * Returns the output bucket an evaluation uses: the number of pieces beyond the two kings,
   * divided by the number of piece counts each bucket covers, and capped at the last bucket.
   *
   * @param pieceCount The number of pieces on the board, kings included, from 0 to
   *                   {@link Long#SIZE}.
   * @return The bucket, from 0 to {@link #buckets()} - 1.
   */
  public int bucket(final int pieceCount) {
    return this.bucketOfPieceCount[pieceCount];
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
   * @param accumulator The accumulator, {@link #hidden()} values.
   */
  public void reset(final int[] accumulator) {
    System.arraycopy(this.featureBias, 0, accumulator, 0, this.hidden);
  }

  /**
   * Adds a feature's weight row to an accumulator.
   *
   * @param accumulator The accumulator, {@link #hidden()} values.
   * @param feature The feature index.
   */
  public void add(final int[] accumulator, final int feature) {
    final int[] row = this.featureWeights[feature];
    final int size = this.hidden;
    for (int i = 0; i < size; i++) {
      accumulator[i] += row[i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with one feature removed and one added.
   *
   * @param source The accumulator read, {@link #hidden()} values.
   * @param destination The accumulator written, {@link #hidden()} values.
   * @param removed The feature index removed.
   * @param added The feature index added.
   */
  public void move(final int[] source, final int[] destination, final int removed,
                   final int added) {
    final int[] removedRow = this.featureWeights[removed];
    final int[] addedRow = this.featureWeights[added];
    final int size = this.hidden;
    for (int i = 0; i < size; i++) {
      destination[i] = source[i] - removedRow[i] + addedRow[i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with two features removed and one added.
   *
   * @param source The accumulator read, {@link #hidden()} values.
   * @param destination The accumulator written, {@link #hidden()} values.
   * @param removed The first feature index removed.
   * @param alsoRemoved The second feature index removed.
   * @param added The feature index added.
   */
  public void capture(final int[] source, final int[] destination, final int removed,
                      final int alsoRemoved, final int added) {
    final int[] removedRow = this.featureWeights[removed];
    final int[] alsoRemovedRow = this.featureWeights[alsoRemoved];
    final int[] addedRow = this.featureWeights[added];
    final int size = this.hidden;
    for (int i = 0; i < size; i++) {
      destination[i] = source[i] - removedRow[i] - alsoRemovedRow[i] + addedRow[i];
    }
  }

  /**
   * Writes into one accumulator another accumulator with two features removed and two added.
   *
   * @param source The accumulator read, {@link #hidden()} values.
   * @param destination The accumulator written, {@link #hidden()} values.
   * @param removed The first feature index removed.
   * @param alsoRemoved The second feature index removed.
   * @param added The first feature index added.
   * @param alsoAdded The second feature index added.
   */
  public void castle(final int[] source, final int[] destination, final int removed,
                     final int alsoRemoved, final int added, final int alsoAdded) {
    final int[] removedRow = this.featureWeights[removed];
    final int[] alsoRemovedRow = this.featureWeights[alsoRemoved];
    final int[] addedRow = this.featureWeights[added];
    final int[] alsoAddedRow = this.featureWeights[alsoAdded];
    final int size = this.hidden;
    for (int i = 0; i < size; i++) {
      destination[i] = source[i] - removedRow[i] - alsoRemovedRow[i] + addedRow[i]
              + alsoAddedRow[i];
    }
  }

  /**
   * Returns the evaluation of a position from its two accumulators.
   *
   * @param mover The side to move's accumulator, {@link #hidden()} values.
   * @param other The other side's accumulator, {@link #hidden()} values.
   * @param pieceCount The number of pieces on the board, kings included, which chooses the output
   *                   bucket.
   * @return The evaluation in centipawns from the side to move's point of view.
   */
  public int evaluate(final int[] mover, final int[] other, final int pieceCount) {
    final int bucket = bucket(pieceCount);
    final int[] moverOutput = this.moverWeights[bucket];
    final int[] otherOutput = this.otherWeights[bucket];
    final int size = this.hidden;
    long sum = 0;
    for (int i = 0; i < size; i++) {
      final int value = Math.min(Math.max(mover[i], 0), QA);
      sum += value * value * moverOutput[i];
    }
    for (int i = 0; i < size; i++) {
      final int value = Math.min(Math.max(other[i], 0), QA);
      sum += value * value * otherOutput[i];
    }
    return (int) ((sum / QA + this.outputBias[bucket]) * SCALE / ((long) QA * QB));
  }
}
