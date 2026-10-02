package engine.forPlayer.forAI;

import java.util.Arrays;

/**
 * The EvaluationWeights class holds the named weights an evaluator scores with, each addressed by
 * the index it was given when it was added. An evaluator adds all of its weights while its class
 * is initialized and reads them by index from then on.
 *
 * @author Aaron Ho
 */
public final class EvaluationWeights {

  /** The action run after a weight changes, which discards whatever the old value produced. */
  private final Runnable onChange;

  /** The name of each weight, indexed by the weight's index. */
  private String[] names = new String[0];

  /** The value of each weight, indexed by the weight's index. */
  private double[] values = new double[0];

  /**
   * Constructs an empty set of weights.
   *
   * @param onChange The action to run after any weight is changed by {@link #set}, such as
   *                 clearing a cache of scores computed with the old values.
   */
  EvaluationWeights(final Runnable onChange) {
    this.onChange = onChange;
  }

  /**
   * Adds a weight with the given name and value and returns its index. Weights must be added
   * before any is read, and the name must not already be in use.
   *
   * @param name The name of the weight.
   * @param value The value of the weight.
   * @return The index of the new weight.
   * @throws IllegalArgumentException If a weight with the given name already exists.
   */
  int add(final String name, final double value) {
    if (Arrays.asList(this.names).contains(name)) {
      throw new IllegalArgumentException("Duplicate weight name: " + name);
    }

    final int index = this.values.length;
    this.names = Arrays.copyOf(this.names, index + 1);
    this.values = Arrays.copyOf(this.values, index + 1);
    this.names[index] = name;
    this.values[index] = value;
    return index;
  }

  /**
   * Returns the number of weights.
   *
   * @return The number of weights.
   */
  public int size() {
    return this.values.length;
  }

  /**
   * Returns the name of the weight at the given index.
   *
   * @param index The index of the weight.
   * @return The name of the weight.
   */
  public String name(final int index) {
    return this.names[index];
  }

  /**
   * Returns the value of the weight at the given index.
   *
   * @param index The index of the weight.
   * @return The value of the weight.
   */
  public double get(final int index) {
    return this.values[index];
  }

  /**
   * Sets the value of the weight at the given index and runs the change action given at
   * construction. It must not be called while the owning evaluator is evaluating on any thread,
   * and an evaluation on another thread sees the new value only if that thread synchronizes with
   * the caller afterwards, as a task submitted to an executor after this returns does.
   *
   * @param index The index of the weight.
   * @param value The new value of the weight.
   */
  public void set(final int index, final double value) {
    this.values[index] = value;
    this.onChange.run();
  }
}
