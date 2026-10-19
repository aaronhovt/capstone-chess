package engine.forTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * The MatchStatistics record measures what a match came to. It holds the games one side won, lost,
 * and drew, and turns them into a score rate, an Elo difference, a confidence interval, and the
 * likelihood that the side is the stronger of the two.
 * <p>
 * Every figure is written from the point of view of the side the counts belong to, so a positive
 * Elo difference means that side is ahead.
 * <p>
 * The interval is a normal approximation taken on the score rate and then written as Elo through
 * the same logistic the point estimate uses. Its standard error is taken from the wins and draws
 * the match actually held rather than from an assumed draw rate. An endpoint that reaches a score
 * rate of zero or one is unbounded and is reported as such.
 * <p>
 * Each game is counted on its own, which leaves out the pairing of the openings a match is played
 * from. The two games of a pair are correlated, so an interval taken this way is wider than one
 * taken over pair results.
 * <p>
 * A match whose games all ended the same way has a standard error of zero and no interval can be
 * taken from it. A match of nothing but wins or nothing but losses is instead reported with a one
 * sided bound taken from the binomial, and a match of nothing but draws is reported with no
 * interval at all.
 * <p>
 * These counts also carry a sequential test, which weighs a null hypothesis and an alternative
 * hypothesis against one another and reports which of the two the games have settled on, or that
 * they have settled neither. The test is the log likelihood ratio of a normal model whose variance
 * is the one the match itself measures, so it is an approximation of the ratio rather than the
 * ratio of the trinomial the games really come from. It is stated in {@link SequentialTest} and
 * read through {@link #logLikelihoodRatio} and {@link #conclude}.
 *
 * @author Aaron Ho
 */
public record MatchStatistics(int wins, int losses, int draws) {

  /** The standard normal deviate the two sided interval is taken at. */
  private static final double CONFIDENCE_DEVIATE = 1.959964;

  /** The confidence the interval and the one sided bounds are stated at. */
  private static final double CONFIDENCE = 0.95;

  /** The scale of the logistic an Elo difference is written on. */
  private static final double ELO_SCALE = 400.0;

  /** The base of the logistic an Elo difference is written on. */
  private static final double ELO_BASE = 10.0;

  /** The coefficient of the argument in the error function approximation. */
  private static final double ERF_ARGUMENT = 0.3275911;

  /** The coefficients of the polynomial in the error function approximation, in order. */
  private static final double[] ERF_TERMS = {0.254829592, -0.284496736, 1.421413741, -1.453152027,
          1.061405429};

  /** The text an Elo figure is written as when it is unbounded. */
  private static final String UNBOUNDED = "unbounded";

  /**
   * Constructs the statistics of a match.
   *
   * @throws IllegalArgumentException If any count is negative or the match holds no games.
   */
  public MatchStatistics {
    if (wins < 0 || losses < 0 || draws < 0) {
      throw new IllegalArgumentException("A match cannot hold a negative number of games");
    }
    if (wins + losses + draws < 1) {
      throw new IllegalArgumentException("A match must hold at least one game");
    }
  }

  /**
   * The number of games the match held.
   *
   * @return The number of games.
   */
  public int games() {
    return wins + losses + draws;
  }

  /**
   * The points the side scored, counting a win as one and a draw as a half.
   *
   * @return The points scored.
   */
  public double points() {
    return wins + draws / 2.0;
  }

  /**
   * The share of the points on offer that the side scored.
   *
   * @return The score rate, between zero and one.
   */
  public double scoreRate() {
    return points() / games();
  }

  /**
   * The share of the games that were drawn.
   *
   * @return The draw rate, between zero and one.
   */
  public double drawRate() {
    return (double) draws / games();
  }

  /**
   * The variance of the score of a single game, taken from the wins and draws the match held.
   *
   * @return The variance, which is zero when every game ended the same way.
   */
  public double variance() {
    final double rate = scoreRate();
    final double meanSquare = (wins + draws * 0.25) / games();
    return Math.max(0.0, meanSquare - rate * rate);
  }

  /**
   * The standard error of the score rate, taken from the wins and draws the match held.
   *
   * @return The standard error, which is zero when every game ended the same way.
   */
  public double standardError() {
    return Math.sqrt(variance() / games());
  }

  /**
   * The Elo difference the score rate measures.
   *
   * @return The Elo difference, positive infinity if every game was won and negative infinity if
   *         every game was lost.
   */
  public double elo() {
    return eloOf(scoreRate());
  }

  /**
   * The lower end of the confidence interval on the Elo difference. A match of nothing but wins
   * has no interval, so the one sided binomial bound is returned in its place.
   *
   * @return The lower bound, or negative infinity if it is unbounded.
   */
  public double eloLowerBound() {
    if (standardError() == 0.0) {
      return wins == games() ? eloOf(oneSidedRate()) : Double.NEGATIVE_INFINITY;
    }
    return eloOf(scoreRate() - CONFIDENCE_DEVIATE * standardError());
  }

  /**
   * The upper end of the confidence interval on the Elo difference. A match of nothing but losses
   * has no interval, so the one sided binomial bound is returned in its place.
   *
   * @return The upper bound, or positive infinity if it is unbounded.
   */
  public double eloUpperBound() {
    if (standardError() == 0.0) {
      return losses == games() ? eloOf(1.0 - oneSidedRate()) : Double.POSITIVE_INFINITY;
    }
    return eloOf(scoreRate() + CONFIDENCE_DEVIATE * standardError());
  }

  /**
   * The likelihood that the side is the stronger of the two, taken from the decisive games alone.
   *
   * @return The likelihood, between zero and one, which is one half when no game was decisive.
   */
  public double likelihoodOfSuperiority() {
    final int decisive = wins + losses;
    if (decisive == 0) {
      return 0.5;
    }
    return 0.5 * (1.0 + erf((wins - losses) / Math.sqrt(2.0 * decisive)));
  }

  /**
   * The log likelihood ratio of the alternative hypothesis against the null hypothesis, taken
   * under a normal model whose variance is the one the match measures. A positive ratio favours
   * the alternative.
   *
   * @param test The hypotheses the ratio is taken between.
   * @return The ratio, which is zero for a match whose games all ended the same way, since such a
   *         match has no variance to take it against.
   */
  public double logLikelihoodRatio(final SequentialTest test) {
    final double variance = variance();
    if (variance == 0.0) {
      return 0.0;
    }
    final double nullRate = scoreRateOf(test.nullElo());
    final double gainRate = scoreRateOf(test.gainElo());
    return games() * (gainRate - nullRate) * (2.0 * scoreRate() - nullRate - gainRate) /
            (2.0 * variance);
  }

  /**
   * What the games have settled the test on.
   *
   * @param test The hypotheses the games are weighed between.
   * @return The hypothesis the ratio has reached the bound of, or that it has reached neither.
   */
  public Conclusion conclude(final SequentialTest test) {
    final double ratio = logLikelihoodRatio(test);
    if (variance() == 0.0) {
      return Conclusion.UNDECIDED;
    }
    if (ratio >= test.upperBound()) {
      return Conclusion.ACCEPTED;
    }
    if (ratio <= test.lowerBound()) {
      return Conclusion.REJECTED;
    }
    return Conclusion.UNDECIDED;
  }

  /**
   * Writes the statistics as lines of text, naming the side the figures belong to.
   *
   * @param name The name the side is reported under.
   * @return The lines to report, in order.
   */
  public List<String> report(final String name) {
    final List<String> lines = new ArrayList<>();
    final int percent = (int) Math.round(CONFIDENCE * 100.0);
    if (standardError() == 0.0 && draws == games()) {
      lines.add("Elo for " + name + ": " + eloText(elo()) + ", every game was drawn, so no " +
              "interval can be taken");
    } else if (standardError() == 0.0 && wins == games()) {
      lines.add("Elo for " + name + ": above " + eloText(eloLowerBound()) + " at " + percent +
              " percent confidence, every game was won");
    } else if (standardError() == 0.0) {
      lines.add("Elo for " + name + ": below " + eloText(eloUpperBound()) + " at " + percent +
              " percent confidence, every game was lost");
    } else {
      lines.add("Elo for " + name + ": " + eloText(elo()) + ", " + percent + " percent interval " +
              eloText(eloLowerBound()) + " to " + eloText(eloUpperBound()));
    }
    lines.add(String.format("Draw rate: %.1f percent, likelihood %s is the stronger engine: " +
            "%.1f percent", 100.0 * drawRate(), name, 100.0 * likelihoodOfSuperiority()));
    return lines;
  }

  /**
   * Writes the statistics as lines of text, naming the side the figures belong to, followed by
   * what the sequential test came to.
   *
   * @param name The name the side is reported under.
   * @param test The hypotheses the games are weighed between.
   * @return The lines to report, in order.
   */
  public List<String> report(final String name, final SequentialTest test) {
    final List<String> lines = report(name);
    lines.addAll(testReport(test));
    return lines;
  }

  /**
   * Writes what the sequential test came to over these games as lines of text.
   *
   * @param test The hypotheses the games are weighed between.
   * @return The lines to report, in order.
   */
  public List<String> testReport(final SequentialTest test) {
    final List<String> lines = new ArrayList<>();
    lines.add(String.format("Sequential test: LLR %+.2f after %d games, bounds %+.2f to %+.2f",
            logLikelihoodRatio(test), games(), test.lowerBound(), test.upperBound()));
    lines.add(switch (conclude(test)) {
      case ACCEPTED -> "The test chose the gain of " + eloFigure(test.gainElo()) + " Elo over " +
              "the null of " + eloFigure(test.nullElo()) + " Elo";
      case REJECTED -> "The test chose the null of " + eloFigure(test.nullElo()) + " Elo over " +
              "the gain of " + eloFigure(test.gainElo()) + " Elo";
      case UNDECIDED -> "The test chose neither hypothesis, so the match settled nothing";
    });
    return lines;
  }

  /**
   * Writes an Elo figure with its sign. A figure of negative zero is written as a positive zero.
   *
   * @param elo The figure to write.
   * @return The figure, or a word naming it as unbounded if it is infinite.
   */
  private static String eloText(final double elo) {
    if (Double.isInfinite(elo)) {
      return UNBOUNDED;
    }
    return String.format("%+.1f", elo == 0.0 ? 0.0 : elo);
  }

  /**
   * Writes an Elo figure without a sign, as a hypothesis is named by.
   *
   * @param elo The figure to write.
   * @return The figure.
   */
  private static String eloFigure(final double elo) {
    return String.format("%.1f", elo);
  }

  /**
   * The Elo difference a score rate measures.
   *
   * @param rate The score rate, between zero and one.
   * @return The Elo difference, infinite at either end of the range.
   */
  private static double eloOf(final double rate) {
    if (rate <= 0.0) {
      return Double.NEGATIVE_INFINITY;
    }
    if (rate >= 1.0) {
      return Double.POSITIVE_INFINITY;
    }
    return -ELO_SCALE * Math.log10(1.0 / rate - 1.0);
  }

  /**
   * The score rate an Elo difference predicts.
   *
   * @param elo The Elo difference.
   * @return The score rate, between zero and one.
   */
  private static double scoreRateOf(final double elo) {
    return 1.0 / (1.0 + Math.pow(ELO_BASE, -elo / ELO_SCALE));
  }

  /**
   * The score rate a one sided bound is taken at when every game ended the same way.
   *
   * @return The score rate the confidence and the number of games give.
   */
  private double oneSidedRate() {
    return Math.pow(1.0 - CONFIDENCE, 1.0 / games());
  }

  /**
   * The error function, to an accuracy of about one part in ten million.
   *
   * @param value The argument of the function.
   * @return The value of the function at that argument.
   */
  private static double erf(final double value) {
    final double magnitude = Math.abs(value);
    final double step = 1.0 / (1.0 + ERF_ARGUMENT * magnitude);
    double polynomial = 0.0;
    for (int term = ERF_TERMS.length - 1; term >= 0; term--) {
      polynomial = (polynomial + ERF_TERMS[term]) * step;
    }
    final double result = 1.0 - polynomial * Math.exp(-magnitude * magnitude);
    return value < 0.0 ? -result : result;
  }

  /**
   * The Conclusion enum names what a sequential test has settled on.
   */
  public enum Conclusion {

    /** The alternative hypothesis was accepted, so the games favour the gain. */
    ACCEPTED,

    /** The null hypothesis was accepted, so the games favour the null. */
    REJECTED,

    /** Neither hypothesis was accepted, so the games have settled nothing. */
    UNDECIDED
  }

  /**
   * The SequentialTest record holds the hypotheses a match is weighed between and the error rates
   * the bounds of the test are taken from.
   * <p>
   * The null hypothesis is that the side gains the null Elo, and the alternative is that it gains
   * the gain Elo. The false positive rate is the chance of accepting the alternative when the null
   * holds, and the false negative rate is the chance of accepting the null when the alternative
   * holds.
   *
   * @param nullElo The Elo difference the null hypothesis holds.
   * @param gainElo The Elo difference the alternative hypothesis holds, which is the larger of the
   *                two.
   * @param falsePositiveRate The chance of accepting the alternative when the null holds.
   * @param falseNegativeRate The chance of accepting the null when the alternative holds.
   */
  public record SequentialTest(double nullElo, double gainElo, double falsePositiveRate,
                               double falseNegativeRate) {

    /**
     * Constructs a sequential test.
     *
     * @throws IllegalArgumentException If either hypothesis is not a number, if the alternative
     *                                  does not hold a larger Elo difference than the null, or if
     *                                  either error rate is not above zero and below one.
     */
    public SequentialTest {
      if (!Double.isFinite(nullElo) || !Double.isFinite(gainElo)) {
        throw new IllegalArgumentException("A hypothesis must hold a finite Elo difference");
      }
      if (gainElo <= nullElo) {
        throw new IllegalArgumentException("The alternative hypothesis must hold a larger Elo " +
                "difference than the null hypothesis");
      }
      if (!(falsePositiveRate > 0.0) || !(falsePositiveRate < 1.0) ||
              !(falseNegativeRate > 0.0) || !(falseNegativeRate < 1.0)) {
        throw new IllegalArgumentException("Both error rates must be above zero and below one");
      }
    }

    /**
     * The ratio at or above which the alternative hypothesis is accepted.
     *
     * @return The upper bound.
     */
    public double upperBound() {
      return Math.log((1.0 - falseNegativeRate) / falsePositiveRate);
    }

    /**
     * The ratio at or below which the null hypothesis is accepted.
     *
     * @return The lower bound.
     */
    public double lowerBound() {
      return Math.log(falseNegativeRate / (1.0 - falsePositiveRate));
    }
  }
}