package engine.forPlayer.forAI;

import java.io.PrintWriter;

/**
 * The SearchStatistics class counts what the search does: where its nodes go, how often each
 * pruning and reduction fires, how well its moves are ordered, and how its iterations and
 * aspiration windows behave. Each search thread counts into its own set of counters, which the
 * caller collects with {@link #takeCurrent()} and merges with {@link #addTo(SearchStatistics)}.
 * <p>
 * Nothing is counted unless the system property {@value #PROPERTY} is set to true before
 * {@link AlphaBeta} is first used, since the search reads it once when that class is loaded.
 * <p>
 * Remaining depths are counted in buckets from zero to {@value #DEPTH_BUCKETS} less one, and a
 * deeper node is counted in the last bucket. A node in a subtree is counted once however many of
 * the counted events it lies beneath, so no count here is a share of nodes beneath an event.
 */
public final class SearchStatistics {

  /** The system property that turns counting on when it is set to true. */
  public static final String PROPERTY = "engine.searchStatistics";

  /** The number of remaining depth buckets. */
  static final int DEPTH_BUCKETS = 32;

  /** The cutoff class of the transposition table move. */
  static final int CUT_BY_TABLE_MOVE = 0;

  /** The cutoff class of a capture that does not lose material. */
  static final int CUT_BY_GOOD_CAPTURE = 1;

  /** The cutoff class of a killer move. */
  static final int CUT_BY_KILLER = 2;

  /** The cutoff class of the countermove. */
  static final int CUT_BY_COUNTERMOVE = 3;

  /** The cutoff class of any other quiet move. */
  static final int CUT_BY_OTHER_QUIET = 4;

  /** The cutoff class of a capture that loses material. */
  static final int CUT_BY_BAD_CAPTURE = 5;

  /** The names of the cutoff classes, in the order of their numbers. */
  private static final String[] CUT_CLASS_NAMES =
          {"table move", "good capture", "killer", "countermove", "other quiet", "bad capture"};

  /** The number of move numbers the cutoff histogram tells apart; later moves share its last entry. */
  private static final int CUT_MOVE_NUMBERS = 12;

  /** The counters of the search running on each thread. */
  private static final ThreadLocal<SearchStatistics> CURRENT =
          ThreadLocal.withInitial(SearchStatistics::new);

  // Searches and iterations.
  private long searches;
  private long completedDepthSum;
  private long searchMillis;
  private long searchNodes;
  private final long[] iterationNodes = new long[DEPTH_BUCKETS];
  private final long[] iterations = new long[DEPTH_BUCKETS];
  private long stoppedIterationNodes;

  // Aspiration windows.
  private long aspirationIterations;
  private long aspirationFailLow;
  private long aspirationFailHigh;
  private long aspirationFullWindow;
  private long aspirationNodes;
  private long aspirationFailedNodes;

  // Node entries.
  private long negamaxNodes;
  private final long[] negamaxByDepth = new long[DEPTH_BUCKETS];
  private long quiescenceNodes;

  // Main search pruning and reductions.
  private long tableCutoffs;
  private long razored;
  private long reverseFutility;
  private long nullMovesTried;
  private long nullMoveCutoffs;
  private long verifications;
  private long verificationsOverturned;
  private long internalReductions;
  private final long[] zeroWindowAtMoves = new long[DEPTH_BUCKETS];
  private final long[] zeroWindowWithoutTableMove = new long[DEPTH_BUCKETS];
  private final long[] openWindowAtMoves = new long[DEPTH_BUCKETS];
  private final long[] openWindowWithoutTableMove = new long[DEPTH_BUCKETS];
  private long exchangeSkips;
  private long lateMoveSkips;
  private long futileMoveSkips;
  private long illegalMoves;

  // Searches of moves.
  private long zeroWindowSearches;
  private long reducedSearches;
  private long reductionReSearches;
  private long windowReSearches;

  // Node outcomes and ordering.
  private long failHigh;
  private long failLow;
  private long exact;
  private long movesSearchedAtFailLow;
  private final long[] failHighByDepth = new long[DEPTH_BUCKETS];
  private final long[] firstMoveCutoffsByDepth = new long[DEPTH_BUCKETS];
  private final long[] cutoffsByMoveNumber = new long[CUT_MOVE_NUMBERS];
  private final long[] cutoffsByClass = new long[CUT_CLASS_NAMES.length];

  // Quiescence.
  private long quiescenceTableCutoffs;
  private long quiescenceInCheck;
  private long standPatCutoffs;
  private long capturesIllegal;
  private long capturesExchangePruned;
  private long capturesDeltaPruned;
  private long capturesSearched;
  private long captureCutoffs;

  /**
   * Returns whether the search counts into these statistics, which is fixed when
   * {@link AlphaBeta} is loaded. Calling this loads that class if it is not yet loaded.
   *
   * @return True if the search counts.
   */
  public static boolean isCollecting() {
    return AlphaBeta.COLLECT_STATISTICS;
  }

  /**
   * Returns the counters of the calling thread.
   *
   * @return The calling thread's counters.
   */
  static SearchStatistics current() {
    return CURRENT.get();
  }

  /**
   * Returns the counters the calling thread has counted into and gives it a fresh set.
   *
   * @return The calling thread's counters up to now.
   */
  public static SearchStatistics takeCurrent() {
    final SearchStatistics taken = CURRENT.get();
    CURRENT.set(new SearchStatistics());
    return taken;
  }

  /**
   * Returns the bucket a remaining depth is counted in.
   *
   * @param depth The remaining depth.
   * @return The bucket, with every depth below one in bucket zero.
   */
  private static int bucketOf(final int depth) {
    return Math.max(0, Math.min(DEPTH_BUCKETS - 1, depth));
  }

  /**
   * Counts a finished search.
   *
   * @param completedDepth The depth of the deepest iteration it finished.
   * @param millis The time it took in milliseconds.
   * @param nodes The nodes it reported searching on this thread.
   */
  void searchFinished(final int completedDepth, final long millis, final long nodes) {
    this.searches++;
    this.completedDepthSum += completedDepth;
    this.searchMillis += millis;
    this.searchNodes += nodes;
  }

  /**
   * Counts an iteration that finished.
   *
   * @param depth The depth of the iteration.
   * @param nodes The nodes it searched.
   */
  void iterationFinished(final int depth, final long nodes) {
    this.iterations[bucketOf(depth)]++;
    this.iterationNodes[bucketOf(depth)] += nodes;
  }

  /**
   * Counts the nodes of an iteration the limit stopped.
   *
   * @param nodes The nodes it searched before it stopped.
   */
  void iterationStopped(final long nodes) {
    this.stoppedIterationNodes += nodes;
  }

  /** Counts an iteration searched with an aspiration window. */
  void aspirationStarted() {
    this.aspirationIterations++;
  }

  /**
   * Counts one attempt at an iteration searched with an aspiration window.
   *
   * @param nodes The nodes the attempt searched.
   * @param failedLow True if the root score fell at or below the window.
   * @param failedHigh True if the root score rose to or above the window.
   */
  void aspirationAttempt(final long nodes, final boolean failedLow, final boolean failedHigh) {
    this.aspirationNodes += nodes;
    if (failedLow || failedHigh) {
      this.aspirationFailedNodes += nodes;
    }
    if (failedLow) {
      this.aspirationFailLow++;
    }
    if (failedHigh) {
      this.aspirationFailHigh++;
    }
  }

  /** Counts an iteration whose aspiration attempts all failed, searched again with no window. */
  void aspirationFullWindow() {
    this.aspirationFullWindow++;
  }

  /**
   * Counts a main search node.
   *
   * @param depth The remaining depth it was entered with.
   */
  void negamaxEntered(final int depth) {
    this.negamaxNodes++;
    this.negamaxByDepth[bucketOf(depth)]++;
  }

  /** Counts a quiescence node. */
  void quiescenceEntered() {
    this.quiescenceNodes++;
  }

  /** Counts a main search node cut off by its transposition table entry. */
  void tableCutoff() {
    this.tableCutoffs++;
  }

  /** Counts a main search node razored into quiescence. */
  void razored() {
    this.razored++;
  }

  /** Counts a main search node pruned by reverse futility. */
  void reverseFutility() {
    this.reverseFutility++;
  }

  /** Counts a null move searched. */
  void nullMoveTried() {
    this.nullMovesTried++;
  }

  /** Counts a node cut off by its null move. */
  void nullMoveCutoff() {
    this.nullMoveCutoffs++;
  }

  /**
   * Counts a null move cutoff checked by a verification search.
   *
   * @param overturned True if the verification search did not confirm the cutoff.
   */
  void verification(final boolean overturned) {
    this.verifications++;
    if (overturned) {
      this.verificationsOverturned++;
    }
  }

  /**
   * Counts a main search node that reached its moves.
   *
   * @param depth The remaining depth of the node before any internal reduction.
   * @param openWindow True if the node has an open window.
   * @param tableMove True if the node has a transposition table move.
   */
  void movesReached(final int depth, final boolean openWindow, final boolean tableMove) {
    final int bucket = bucketOf(depth);
    if (openWindow) {
      this.openWindowAtMoves[bucket]++;
      if (!tableMove) {
        this.openWindowWithoutTableMove[bucket]++;
      }
    } else {
      this.zeroWindowAtMoves[bucket]++;
      if (!tableMove) {
        this.zeroWindowWithoutTableMove[bucket]++;
      }
    }
  }

  /** Counts a node searched one ply shallower for having no transposition table move. */
  void internalReduction() {
    this.internalReductions++;
  }

  /** Counts a capture skipped for losing material in the static exchange. */
  void exchangeSkip() {
    this.exchangeSkips++;
  }

  /** Counts a quiet move skipped by late move pruning. */
  void lateMoveSkip() {
    this.lateMoveSkips++;
  }

  /** Counts a quiet move skipped by quiet move futility pruning. */
  void futileMoveSkip() {
    this.futileMoveSkips++;
  }

  /** Counts a main search move made and unmade for leaving its own king in check. */
  void illegalMove() {
    this.illegalMoves++;
  }

  /**
   * Counts a move searched against a zero window after the first move of a node.
   *
   * @param reduced True if the search was reduced.
   */
  void zeroWindowSearch(final boolean reduced) {
    this.zeroWindowSearches++;
    if (reduced) {
      this.reducedSearches++;
    }
  }

  /** Counts a reduced search searched again at its unreduced depth. */
  void reductionReSearch() {
    this.reductionReSearches++;
  }

  /** Counts a zero-window search searched again with the node's full window. */
  void windowReSearch() {
    this.windowReSearches++;
  }

  /**
   * Counts a main search node that failed high.
   *
   * @param depth The remaining depth its moves were searched at.
   * @param movesSearched The number of legal moves searched before the move that failed high.
   * @param cutClass The cutoff class of that move.
   */
  void failHigh(final int depth, final int movesSearched, final int cutClass) {
    this.failHigh++;
    this.failHighByDepth[bucketOf(depth)]++;
    if (movesSearched == 0) {
      this.firstMoveCutoffsByDepth[bucketOf(depth)]++;
    }
    this.cutoffsByMoveNumber[Math.min(movesSearched, CUT_MOVE_NUMBERS - 1)]++;
    this.cutoffsByClass[cutClass]++;
  }

  /**
   * Counts a main search node that searched its moves and failed low.
   *
   * @param movesSearched The number of legal moves it searched.
   */
  void failLow(final int movesSearched) {
    this.failLow++;
    this.movesSearchedAtFailLow += movesSearched;
  }

  /** Counts a main search node that searched its moves and returned a score inside its window. */
  void exact() {
    this.exact++;
  }

  /** Counts a quiescence node cut off by its transposition table entry. */
  void quiescenceTableCutoff() {
    this.quiescenceTableCutoffs++;
  }

  /** Counts a quiescence node in check, handed to the main search. */
  void quiescenceInCheck() {
    this.quiescenceInCheck++;
  }

  /** Counts a quiescence node cut off by its static evaluation. */
  void standPatCutoff() {
    this.standPatCutoffs++;
  }

  /** Counts a quiescence move made and unmade for leaving its own king in check. */
  void captureIllegal() {
    this.capturesIllegal++;
  }

  /** Counts a quiescence move made and unmade for losing material in the static exchange. */
  void captureExchangePruned() {
    this.capturesExchangePruned++;
  }

  /** Counts a quiescence move made and unmade by delta pruning. */
  void captureDeltaPruned() {
    this.capturesDeltaPruned++;
  }

  /** Counts a quiescence move searched. */
  void captureSearched() {
    this.capturesSearched++;
  }

  /** Counts a quiescence node cut off by a move. */
  void captureCutoff() {
    this.captureCutoffs++;
  }

  /**
   * Adds every count of these statistics to the given statistics.
   *
   * @param total The statistics to add to.
   */
  public void addTo(final SearchStatistics total) {
    total.searches += this.searches;
    total.completedDepthSum += this.completedDepthSum;
    total.searchMillis += this.searchMillis;
    total.searchNodes += this.searchNodes;
    add(total.iterationNodes, this.iterationNodes);
    add(total.iterations, this.iterations);
    total.stoppedIterationNodes += this.stoppedIterationNodes;
    total.aspirationIterations += this.aspirationIterations;
    total.aspirationFailLow += this.aspirationFailLow;
    total.aspirationFailHigh += this.aspirationFailHigh;
    total.aspirationFullWindow += this.aspirationFullWindow;
    total.aspirationNodes += this.aspirationNodes;
    total.aspirationFailedNodes += this.aspirationFailedNodes;
    total.negamaxNodes += this.negamaxNodes;
    add(total.negamaxByDepth, this.negamaxByDepth);
    total.quiescenceNodes += this.quiescenceNodes;
    total.tableCutoffs += this.tableCutoffs;
    total.razored += this.razored;
    total.reverseFutility += this.reverseFutility;
    total.nullMovesTried += this.nullMovesTried;
    total.nullMoveCutoffs += this.nullMoveCutoffs;
    total.verifications += this.verifications;
    total.verificationsOverturned += this.verificationsOverturned;
    total.internalReductions += this.internalReductions;
    add(total.zeroWindowAtMoves, this.zeroWindowAtMoves);
    add(total.zeroWindowWithoutTableMove, this.zeroWindowWithoutTableMove);
    add(total.openWindowAtMoves, this.openWindowAtMoves);
    add(total.openWindowWithoutTableMove, this.openWindowWithoutTableMove);
    total.exchangeSkips += this.exchangeSkips;
    total.lateMoveSkips += this.lateMoveSkips;
    total.futileMoveSkips += this.futileMoveSkips;
    total.illegalMoves += this.illegalMoves;
    total.zeroWindowSearches += this.zeroWindowSearches;
    total.reducedSearches += this.reducedSearches;
    total.reductionReSearches += this.reductionReSearches;
    total.windowReSearches += this.windowReSearches;
    total.failHigh += this.failHigh;
    total.failLow += this.failLow;
    total.exact += this.exact;
    total.movesSearchedAtFailLow += this.movesSearchedAtFailLow;
    add(total.failHighByDepth, this.failHighByDepth);
    add(total.firstMoveCutoffsByDepth, this.firstMoveCutoffsByDepth);
    add(total.cutoffsByMoveNumber, this.cutoffsByMoveNumber);
    add(total.cutoffsByClass, this.cutoffsByClass);
    total.quiescenceTableCutoffs += this.quiescenceTableCutoffs;
    total.quiescenceInCheck += this.quiescenceInCheck;
    total.standPatCutoffs += this.standPatCutoffs;
    total.capturesIllegal += this.capturesIllegal;
    total.capturesExchangePruned += this.capturesExchangePruned;
    total.capturesDeltaPruned += this.capturesDeltaPruned;
    total.capturesSearched += this.capturesSearched;
    total.captureCutoffs += this.captureCutoffs;
  }

  /**
   * Adds each entry of one array to the matching entry of another of the same length.
   *
   * @param to The array to add to.
   * @param from The array to add.
   */
  private static void add(final long[] to, final long[] from) {
    for (int i = 0; i < from.length; i++) {
      to[i] += from[i];
    }
  }

  /**
   * Formats a count as a percentage of another.
   *
   * @param part The count.
   * @param whole The count it is a share of.
   * @return The percentage to two decimals, or a dash if the whole is zero.
   */
  private static String percent(final long part, final long whole) {
    return whole == 0 ? "-" : String.format("%.2f%%", 100.0 * part / whole);
  }

  /**
   * Writes every count as a report. The node shares are of the nodes the search counted, which
   * are the nodes a node limit is charged.
   *
   * @param out The writer to report to.
   */
  public void report(final PrintWriter out) {
    final long nodes = this.negamaxNodes + this.quiescenceNodes;
    out.printf("searches %d, mean completed depth %.3f, %.0f nodes per search, %.0f nodes per " +
                    "second per search thread%n", this.searches,
            (double) this.completedDepthSum / Math.max(1, this.searches),
            (double) nodes / Math.max(1, this.searches),
            this.searchMillis == 0 ? 0.0 : nodes * 1000.0 / this.searchMillis);
    out.printf("nodes counted here %d, nodes the searches reported %d%n%n", nodes, this.searchNodes);

    out.println("== Node budget ==");
    out.printf("main search %d (%s), quiescence %d (%s)%n", this.negamaxNodes,
            percent(this.negamaxNodes, nodes), this.quiescenceNodes,
            percent(this.quiescenceNodes, nodes));
    out.printf("positions counted twice: main search entered at depth 0 or below %s, razoring %s," +
                    " quiescence in check %s%n", percent(this.negamaxByDepth[0], nodes),
            percent(this.razored, nodes), percent(this.quiescenceInCheck, nodes));
    out.println("main search entries by remaining depth (depth, entries, share of nodes):");
    for (int depth = 0; depth < DEPTH_BUCKETS; depth++) {
      if (this.negamaxByDepth[depth] > 0) {
        out.printf("  %2d %14d %8s%n", depth, this.negamaxByDepth[depth],
                percent(this.negamaxByDepth[depth], nodes));
      }
    }
    out.println();

    out.println("== Iterations ==");
    out.println("depth, finished, mean nodes, ratio to the depth before:");
    double previous = 0;
    for (int depth = 1; depth < DEPTH_BUCKETS; depth++) {
      if (this.iterations[depth] == 0) {
        continue;
      }
      final double mean = (double) this.iterationNodes[depth] / this.iterations[depth];
      out.printf("  %2d %10d %12.0f %8s%n", depth, this.iterations[depth], mean,
              previous == 0 ? "" : String.format("%.2f", mean / previous));
      previous = mean;
    }
    out.printf("nodes in iterations the limit stopped %s%n%n",
            percent(this.stoppedIterationNodes, nodes));

    out.println("== Aspiration windows ==");
    out.printf("iterations %d, fail low %s, fail high %s, full window %d; nodes in failed " +
                    "attempts %s of all nodes%n%n", this.aspirationIterations,
            percent(this.aspirationFailLow, this.aspirationIterations),
            percent(this.aspirationFailHigh, this.aspirationIterations), this.aspirationFullWindow,
            percent(this.aspirationFailedNodes, nodes));

    out.println("== Move ordering ==");
    final long searched = this.failHigh + this.failLow + this.exact;
    out.printf("nodes that searched a move %d: fail high %s, fail low %s, exact %s%n", searched,
            percent(this.failHigh, searched), percent(this.failLow, searched),
            percent(this.exact, searched));
    out.printf("fail highs on the first move %s; mean moves searched at a fail low %.2f%n",
            percent(this.cutoffsByMoveNumber[0], this.failHigh),
            (double) this.movesSearchedAtFailLow / Math.max(1, this.failLow));
    out.print("fail highs by move number, 1 to " + (CUT_MOVE_NUMBERS - 1) + " then later:");
    for (final long count : this.cutoffsByMoveNumber) {
      out.print(" " + percent(count, this.failHigh));
    }
    out.println();
    for (int cutClass = 0; cutClass < CUT_CLASS_NAMES.length; cutClass++) {
      out.printf("  cut by %-13s %s%n", CUT_CLASS_NAMES[cutClass],
              percent(this.cutoffsByClass[cutClass], this.failHigh));
    }
    out.println("fail highs on the first move by remaining depth:");
    for (int depth = 1; depth < DEPTH_BUCKETS; depth++) {
      if (this.failHighByDepth[depth] > 0) {
        out.printf("  %2d %12d %8s%n", depth, this.failHighByDepth[depth],
                percent(this.firstMoveCutoffsByDepth[depth], this.failHighByDepth[depth]));
      }
    }
    out.println();

    out.println("== Pruning and reductions ==");
    final long entered = this.negamaxNodes - this.negamaxByDepth[0];
    out.printf("of main search entries at depth 1 or more (%d): table cutoffs %s, razoring %s, " +
                    "reverse futility %s%n", entered, percent(this.tableCutoffs, entered),
            percent(this.razored, entered), percent(this.reverseFutility, entered));
    out.printf("null moves tried %d, cut %s; verified %d, overturned %s%n", this.nullMovesTried,
            percent(this.nullMoveCutoffs, this.nullMovesTried), this.verifications,
            percent(this.verificationsOverturned, this.verifications));
    out.printf("internal reductions %d%n", this.internalReductions);
    out.printf("moves skipped: exchange %d, late move %d, futile %d; illegal moves made %d%n",
            this.exchangeSkips, this.lateMoveSkips, this.futileMoveSkips, this.illegalMoves);
    out.printf("zero-window searches %d, reduced %s; reduced searches searched again at full " +
                    "depth %s; zero-window searches searched again with the full window %s%n%n",
            this.zeroWindowSearches, percent(this.reducedSearches, this.zeroWindowSearches),
            percent(this.reductionReSearches, this.reducedSearches),
            percent(this.windowReSearches, this.zeroWindowSearches));

    out.println("== Nodes reaching their moves without a table move ==");
    out.println("depth, zero window: reaching moves, without (share); open window: the same");
    for (int depth = 1; depth < DEPTH_BUCKETS; depth++) {
      if (this.zeroWindowAtMoves[depth] + this.openWindowAtMoves[depth] == 0) {
        continue;
      }
      out.printf("  %2d %13d %12d %8s %10d %9d %8s%n", depth, this.zeroWindowAtMoves[depth],
              this.zeroWindowWithoutTableMove[depth],
              percent(this.zeroWindowWithoutTableMove[depth], this.zeroWindowAtMoves[depth]),
              this.openWindowAtMoves[depth], this.openWindowWithoutTableMove[depth],
              percent(this.openWindowWithoutTableMove[depth], this.openWindowAtMoves[depth]));
    }
    out.println();

    out.println("== Quiescence ==");
    final long made = this.capturesIllegal + this.capturesExchangePruned + this.capturesDeltaPruned
            + this.capturesSearched;
    out.printf("of %d entries: table cutoffs %s, in check %s, stand-pat cutoffs %s; capture " +
                    "cutoffs %d%n", this.quiescenceNodes,
            percent(this.quiescenceTableCutoffs, this.quiescenceNodes),
            percent(this.quiescenceInCheck, this.quiescenceNodes),
            percent(this.standPatCutoffs, this.quiescenceNodes), this.captureCutoffs);
    out.printf("moves made %d: illegal %s, pruned by exchange %s, pruned by delta %s, searched %s%n",
            made, percent(this.capturesIllegal, made), percent(this.capturesExchangePruned, made),
            percent(this.capturesDeltaPruned, made), percent(this.capturesSearched, made));
  }
}
