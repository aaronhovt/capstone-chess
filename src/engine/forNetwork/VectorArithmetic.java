package engine.forNetwork;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The VectorArithmetic class holds the accumulator and evaluation loops of {@link Network},
 * written with the Vector API. Every array it is given must hold a multiple of {@link #LANES}
 * values, and short arithmetic wraps, so the caller must guarantee that every accumulator value
 * written fits in a short.
 * <p>
 * This class must only be loaded when the module {@value VectorModule#NAME} is in the boot layer.
 *
 * @author Aaron Ho
 */
final class VectorArithmetic {

  /** The vector shape of short values used for accumulators and feature weights. */
  private static final VectorSpecies<Short> SHORTS = ShortVector.SPECIES_PREFERRED;

  /** The int vector of the same size as {@link #SHORTS}, holding half as many values. */
  private static final VectorSpecies<Integer> INTS =
          VectorSpecies.of(int.class, SHORTS.vectorShape());

  /** The long vector of the same size as {@link #SHORTS}, holding a quarter as many values. */
  private static final VectorSpecies<Long> LONGS =
          VectorSpecies.of(long.class, SHORTS.vectorShape());

  /** The number of short values one step of every loop handles. */
  static final int LANES = SHORTS.length();

  /** The number of int values in one half of a short vector. */
  private static final int HALF = INTS.length();

  private VectorArithmetic() {
  }

  /**
   * Adds a weight row to an accumulator.
   *
   * @param accumulator The accumulator, written.
   * @param row The weight row, as long as the accumulator.
   */
  static void add(final short[] accumulator, final short[] row) {
    for (int i = 0; i < accumulator.length; i += LANES) {
      ShortVector.fromArray(SHORTS, accumulator, i).add(ShortVector.fromArray(SHORTS, row, i))
              .intoArray(accumulator, i);
    }
  }

  /**
   * Writes into one accumulator another with one row subtracted and one added.
   *
   * @param source The accumulator read.
   * @param destination The accumulator written, as long as the source.
   * @param removed The row subtracted.
   * @param added The row added.
   */
  static void move(final short[] source, final short[] destination, final short[] removed,
                   final short[] added) {
    for (int i = 0; i < source.length; i += LANES) {
      ShortVector.fromArray(SHORTS, source, i)
              .sub(ShortVector.fromArray(SHORTS, removed, i))
              .add(ShortVector.fromArray(SHORTS, added, i))
              .intoArray(destination, i);
    }
  }

  /**
   * Writes into one accumulator another with two rows subtracted and one added.
   *
   * @param source The accumulator read.
   * @param destination The accumulator written, as long as the source.
   * @param removed The first row subtracted.
   * @param alsoRemoved The second row subtracted.
   * @param added The row added.
   */
  static void capture(final short[] source, final short[] destination, final short[] removed,
                      final short[] alsoRemoved, final short[] added) {
    for (int i = 0; i < source.length; i += LANES) {
      ShortVector.fromArray(SHORTS, source, i)
              .sub(ShortVector.fromArray(SHORTS, removed, i))
              .sub(ShortVector.fromArray(SHORTS, alsoRemoved, i))
              .add(ShortVector.fromArray(SHORTS, added, i))
              .intoArray(destination, i);
    }
  }

  /**
   * Writes into one accumulator another with two rows subtracted and two added.
   *
   * @param source The accumulator read.
   * @param destination The accumulator written, as long as the source.
   * @param removed The first row subtracted.
   * @param alsoRemoved The second row subtracted.
   * @param added The first row added.
   * @param alsoAdded The second row added.
   */
  static void castle(final short[] source, final short[] destination, final short[] removed,
                     final short[] alsoRemoved, final short[] added, final short[] alsoAdded) {
    for (int i = 0; i < source.length; i += LANES) {
      ShortVector.fromArray(SHORTS, source, i)
              .sub(ShortVector.fromArray(SHORTS, removed, i))
              .sub(ShortVector.fromArray(SHORTS, alsoRemoved, i))
              .add(ShortVector.fromArray(SHORTS, added, i))
              .add(ShortVector.fromArray(SHORTS, alsoAdded, i))
              .intoArray(destination, i);
    }
  }

  /**
   * Returns the sum over an accumulator pair of each value clipped to [0, ceiling], squared, and
   * multiplied by its output weight.
   * <p>
   * Products are summed in int lanes and moved into long lanes after every
   * {@code productsPerLane} steps, so the caller must choose it so that that many products of
   * the largest magnitude fit in an int.
   *
   * @param mover The side to move's accumulator.
   * @param other The other side's accumulator, as long as the first.
   * @param moverWeights The output weights of the side to move's accumulator.
   * @param otherWeights The output weights of the other side's accumulator.
   * @param ceiling The clip ceiling.
   * @param productsPerLane The number of steps whose products an int lane may hold, at least 1.
   * @return The sum.
   */
  static long squaredClippedProducts(final short[] mover, final short[] other,
                                     final int[] moverWeights, final int[] otherWeights,
                                     final short ceiling, final int productsPerLane) {
    final LongVector total = clippedProducts(mover, moverWeights, ceiling, productsPerLane,
            LongVector.zero(LONGS));
    return clippedProducts(other, otherWeights, ceiling, productsPerLane, total)
            .reduceLanes(VectorOperators.ADD);
  }

  /**
   * Adds one accumulator's squared clipped products to long lanes.
   *
   * @param accumulator The accumulator.
   * @param weights The accumulator's output weights.
   * @param ceiling The clip ceiling.
   * @param productsPerLane The number of steps whose products an int lane may hold.
   * @param total The long lanes added to.
   * @return The long lanes with the products added.
   */
  private static LongVector clippedProducts(final short[] accumulator, final int[] weights,
                                            final short ceiling, final int productsPerLane,
                                            LongVector total) {
    final ShortVector zero = ShortVector.zero(SHORTS);
    final ShortVector top = ShortVector.broadcast(SHORTS, ceiling);
    IntVector low = IntVector.zero(INTS);
    IntVector high = IntVector.zero(INTS);
    int pending = 0;
    for (int i = 0; i < accumulator.length; i += LANES) {
      final ShortVector clipped = ShortVector.fromArray(SHORTS, accumulator, i).max(zero).min(top);
      final IntVector lowValues = (IntVector) clipped.convertShape(VectorOperators.S2I, INTS, 0);
      final IntVector highValues = (IntVector) clipped.convertShape(VectorOperators.S2I, INTS, 1);
      low = low.add(lowValues.mul(lowValues).mul(IntVector.fromArray(INTS, weights, i)));
      high = high.add(highValues.mul(highValues)
              .mul(IntVector.fromArray(INTS, weights, i + HALF)));
      if (++pending == productsPerLane) {
        total = widenedSum(total, low, high);
        low = IntVector.zero(INTS);
        high = IntVector.zero(INTS);
        pending = 0;
      }
    }
    return pending == 0 ? total : widenedSum(total, low, high);
  }

  /**
   * Returns long lanes with two int vectors' values added.
   *
   * @param total The long lanes.
   * @param low The first int vector.
   * @param high The second int vector.
   * @return The sum, in long lanes.
   */
  private static LongVector widenedSum(final LongVector total, final IntVector low,
                                       final IntVector high) {
    return total.add(low.convertShape(VectorOperators.I2L, LONGS, 0))
            .add(low.convertShape(VectorOperators.I2L, LONGS, 1))
            .add(high.convertShape(VectorOperators.I2L, LONGS, 0))
            .add(high.convertShape(VectorOperators.I2L, LONGS, 1));
  }
}
