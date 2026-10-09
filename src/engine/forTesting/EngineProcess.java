package engine.forTesting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The EngineProcess class drives one chess engine running as a separate process and speaking the
 * Universal Chess Interface over a pipe. It starts the process, carries out the handshake, sends
 * the commands a self play game needs, and reads the replies. Running each engine as its own
 * process is what lets two builds of this engine, or two entirely different engines, play each
 * other without either one knowing the other exists.
 * <p>
 * Standard output of the process is the protocol channel and is read by a thread of this class
 * into a queue, so that every read can carry a deadline. Standard error is drained by a second
 * thread into a log file, since an engine that writes a stack trace and stops must not be able to
 * block by filling its error pipe. Both threads run as daemons and end when the process ends.
 * <p>
 * Every failure is reported as a {@link Fault}, including the ones that arrive as an
 * {@link IOException}, so that a caller playing a game has one thing to catch. A faulted process is
 * not usable afterwards and should be closed.
 *
 * @author Aaron Ho
 */
public class EngineProcess implements AutoCloseable {

  /** The longest wait between checks of the reply queue, in milliseconds. */
  private static final long POLL_SLICE_MILLIS = 50;

  /** The time a process is given to exit after the quit command, in milliseconds. */
  private static final long QUIT_GRACE_MILLIS = 2_000;

  /** The time a process is given to exit before a fault reports it as still running. */
  private static final long EXIT_GRACE_MILLIS = 500;

  /** The name used for this engine in fault messages. */
  private final String name;

  /** The command line this engine was started from. */
  private final List<String> command;

  /** The running process. */
  private final Process process;

  /** The channel commands are written to. */
  private final BufferedWriter commands;

  /** The protocol lines read from the process and not yet consumed. */
  private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

  /** The path of the log file the standard error of the process is written to. */
  private final Path logFile;

  /** The log file the standard error of the process is written to. */
  private final PrintWriter log;

  /** The time a single read is allowed to wait for the next protocol line, in milliseconds. */
  private final long timeoutMillis;

  /** True once the process has closed its standard output. */
  private volatile boolean outputEnded;

  /**
   * Starts an engine process and prepares its channels. The handshake is not carried out here.
   *
   * @param name The name used for this engine in fault messages.
   * @param command The command line to start, already split into its arguments.
   * @param logFile The file the standard error of the process is written to.
   * @param timeoutMillis The time a single read is allowed to wait for the next protocol line.
   * @throws Fault If the process cannot be started or the log file cannot be opened.
   */
  public EngineProcess(final String name, final List<String> command, final Path logFile,
                       final long timeoutMillis) {
    this.name = name;
    this.command = List.copyOf(command);
    this.logFile = logFile;
    this.timeoutMillis = timeoutMillis;
    try {
      if (logFile.getParent() != null) {
        Files.createDirectories(logFile.getParent());
      }
      this.log = new PrintWriter(Files.newBufferedWriter(logFile), true);
      this.process = new ProcessBuilder(this.command).start();
    } catch (final IOException exception) {
      throw new Fault(name, "could not be started: " + exception.getMessage());
    }
    this.commands = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(),
            StandardCharsets.UTF_8));
    startReader();
    startErrorDrain();
  }

  /**
   * Sends the uci command and reads replies until the engine reports uciok.
   *
   * @throws Fault If the engine does not report uciok before the timeout.
   */
  public void handshake() {
    send("uci");
    while (!"uciok".equals(readLine("uciok"))) {
      continue;
    }
  }

  /**
   * Sends an option value. The engine applies it to the search the next new game starts.
   *
   * @param option The name of the option.
   * @param value The value to set.
   * @throws Fault If the command cannot be written.
   */
  public void setOption(final String option, final int value) {
    send("setoption name " + option + " value " + value);
  }

  /**
   * Sends the isready command and reads replies until the engine reports readyok.
   *
   * @throws Fault If the engine does not report readyok before the timeout.
   */
  public void awaitReady() {
    send("isready");
    while (!"readyok".equals(readLine("readyok"))) {
      continue;
    }
  }

  /**
   * Sends the ucinewgame command and waits for the engine to be ready again.
   *
   * @throws Fault If the engine does not report readyok before the timeout.
   */
  public void newGame() {
    send("ucinewgame");
    awaitReady();
  }

  /**
   * Sets the position as the standard starting position followed by the given moves.
   *
   * @param moves The moves of the game so far, in long algebraic notation.
   * @throws Fault If the command cannot be written.
   */
  public void setPosition(final List<String> moves) {
    if (moves.isEmpty()) {
      send("position startpos");
    } else {
      send("position startpos moves " + String.join(" ", moves));
    }
  }

  /**
   * Searches the current position under a limit and reads the reply. Every information line the
   * engine writes before its move is read, and the fields of the last one reporting a depth or a
   * score are the ones the reply holds. Fields this class does not know are passed over.
   *
   * @param limit What stops the search.
   * @return The move the engine chose, the depth and score it reported, and the time the search
   *         took.
   * @throws Fault If the engine does not report a move before the timeout.
   */
  public Reply go(final Limit limit) {
    final long start = System.nanoTime();
    send("go" + limit.fields());
    Integer depth = null;
    Integer score = null;
    Integer mateIn = null;
    while (true) {
      final String line = readLine("bestmove");
      final String[] tokens = line.split("\\s+");
      if ("bestmove".equals(tokens[0])) {
        if (tokens.length < 2) {
          throw new Fault(this.name, "reported a move command with no move: " + line);
        }
        final long elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new Reply(tokens[1], depth, score, mateIn, elapsed);
      }
      if (!"info".equals(tokens[0])) {
        continue;
      }
      for (int index = 1; index < tokens.length; index++) {
        if ("depth".equals(tokens[index])) {
          depth = readNumber(tokens, index + 1);
        } else if ("score".equals(tokens[index]) && index + 1 < tokens.length) {
          if ("cp".equals(tokens[index + 1])) {
            score = readNumber(tokens, index + 2);
            mateIn = null;
          } else if ("mate".equals(tokens[index + 1])) {
            mateIn = readNumber(tokens, index + 2);
            score = null;
          }
        }
      }
    }
  }

  /**
   * Reports whether the process is still running.
   *
   * @return True if the process has not exited.
   */
  public boolean isAlive() {
    return this.process.isAlive();
  }

  /**
   * Returns the name this engine is reported under.
   *
   * @return The name given when this engine was started.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Asks the process to quit, kills it if it does not, and closes the log file. Does nothing that
   * can throw, so that it is safe to call while handling a fault.
   */
  @Override
  public void close() {
    try {
      if (this.process.isAlive()) {
        this.commands.write("quit\n");
        this.commands.flush();
        this.process.waitFor(QUIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
      }
    } catch (final IOException exception) {
      this.log.println("The quit command could not be written: " + exception.getMessage());
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      this.process.destroyForcibly();
      this.log.flush();
      this.log.close();
    }
  }

  /**
   * Splits an engine command line into its arguments. Whitespace separates arguments except inside
   * double quotes, which are removed, so that a path holding a space can be given as one argument.
   *
   * @param commandLine The command line to split.
   * @return The arguments the command line holds.
   * @throws IllegalArgumentException If a double quote is never closed or the line holds no
   *                                  arguments.
   */
  public static List<String> tokenize(final String commandLine) {
    final List<String> tokens = new ArrayList<>();
    final StringBuilder token = new StringBuilder();
    boolean quoted = false;
    boolean started = false;
    for (final char character : commandLine.toCharArray()) {
      if (character == '"') {
        quoted = !quoted;
        started = true;
      } else if (!quoted && Character.isWhitespace(character)) {
        if (started) {
          tokens.add(token.toString());
          token.setLength(0);
          started = false;
        }
      } else {
        token.append(character);
        started = true;
      }
    }
    if (quoted) {
      throw new IllegalArgumentException("A double quote is never closed: " + commandLine);
    }
    if (started) {
      tokens.add(token.toString());
    }
    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("An engine command line holds no arguments");
    }
    return tokens;
  }

  /**
   * Writes one command to the process.
   *
   * @param command The command to write, without its line ending.
   * @throws Fault If the command cannot be written.
   */
  private void send(final String command) {
    try {
      this.commands.write(command);
      this.commands.write('\n');
      this.commands.flush();
    } catch (final IOException exception) {
      throw new Fault(this.name, "could not be sent the command \"" + command + "\": " +
              exception.getMessage() + exitDetail());
    }
  }

  /**
   * Reads the next protocol line, waiting no longer than the timeout this process was constructed
   * with.
   *
   * @param expectation The line the caller is waiting for, used in the fault message.
   * @return The next protocol line.
   * @throws Fault If no line arrives before the timeout or the process closes its output first.
   */
  private String readLine(final String expectation) {
    final long deadline = System.nanoTime() + this.timeoutMillis * 1_000_000L;
    while (true) {
      final String line = poll();
      if (line != null) {
        return line;
      }
      if (this.outputEnded && this.replies.isEmpty()) {
        throw new Fault(this.name, "ended its output while it was expected to report " +
                expectation + exitDetail());
      }
      if (System.nanoTime() >= deadline) {
        throw new Fault(this.name, "did not report " + expectation + " within " +
                this.timeoutMillis + " milliseconds");
      }
    }
  }

  /**
   * Describes how the process ended, for a fault message reporting that it stopped answering. The
   * process is given a moment to exit first, since its output ends before it does.
   *
   * @return The exit status of the process if it has exited, followed by the path of its log file.
   */
  private String exitDetail() {
    boolean exited = false;
    try {
      exited = this.process.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    return (exited ? ", exiting with status " + this.process.exitValue() : "") + ". Its log is " +
            this.logFile;
  }

  /**
   * Takes the next protocol line from the queue if one arrives within a single poll slice.
   *
   * @return The next protocol line, or null if none arrived.
   * @throws Fault If the wait is interrupted.
   */
  private String poll() {
    try {
      return this.replies.poll(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new Fault(this.name, "was interrupted while it was being read");
    }
  }

  /**
   * Reads the number an information line holds at one of its positions.
   *
   * @param tokens The whitespace separated tokens of the line.
   * @param index The position the number is read from.
   * @return The number at that position, or null if the line ends before it or it is not a number.
   */
  private static Integer readNumber(final String[] tokens, final int index) {
    if (index >= tokens.length) {
      return null;
    }
    try {
      return Integer.valueOf(tokens[index]);
    } catch (final NumberFormatException exception) {
      return null;
    }
  }

  /**
   * Starts the thread reading the standard output of the process into the reply queue.
   */
  private void startReader() {
    final Thread reader = new Thread(() -> {
      try (BufferedReader output = new BufferedReader(new InputStreamReader(
              this.process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = output.readLine()) != null) {
          final String trimmed = line.trim();
          if (!trimmed.isEmpty()) {
            this.replies.add(trimmed);
          }
        }
      } catch (final IOException exception) {
        this.log.println("The protocol channel could not be read: " + exception.getMessage());
      } finally {
        this.outputEnded = true;
      }
    }, this.name + "-protocol");
    reader.setDaemon(true);
    reader.start();
  }

  /**
   * Starts the thread draining the standard error of the process into the log file.
   */
  private void startErrorDrain() {
    final Thread drain = new Thread(() -> {
      try (BufferedReader errors = new BufferedReader(new InputStreamReader(
              this.process.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = errors.readLine()) != null) {
          this.log.println(line);
        }
      } catch (final IOException exception) {
        this.log.println("The error channel could not be read: " + exception.getMessage());
      }
    }, this.name + "-log");
    drain.setDaemon(true);
    drain.start();
  }

  /**
   * The Limit record holds what stops one search. A limit names a node count, a move time, or
   * both, and a search under both is stopped by whichever is reached first.
   *
   * @param nodes The largest number of nodes the search may visit, or UNLIMITED to hold the
   *              search to its move time alone.
   * @param moveTimeMillis The longest the search may run for, or UNLIMITED to hold the search to
   *                       its node count alone.
   */
  public record Limit(long nodes, long moveTimeMillis) {

    /** The value naming a measure this limit does not hold. */
    public static final long UNLIMITED = Long.MAX_VALUE;

    /**
     * Constructs a limit naming a node count, a move time, or both.
     *
     * @throws IllegalArgumentException If the limit names neither a node count nor a move time.
     */
    public Limit {
      if (nodes == UNLIMITED && moveTimeMillis == UNLIMITED) {
        throw new IllegalArgumentException("A limit must name a node count or a move time");
      }
    }

    /**
     * Returns a limit holding a search to a number of nodes.
     *
     * @param nodes The largest number of nodes the search may visit.
     * @return The limit that node count states.
     */
    public static Limit ofNodes(final long nodes) {
      return new Limit(nodes, UNLIMITED);
    }

    /**
     * Returns a limit holding a search to a length of time.
     *
     * @param moveTimeMillis The longest the search may run for, in milliseconds.
     * @return The limit that move time states.
     */
    public static Limit ofMoveTime(final long moveTimeMillis) {
      return new Limit(UNLIMITED, moveTimeMillis);
    }

    /**
     * Describes this limit as it is reported to a reader.
     *
     * @return The measures this limit holds, named and separated by the word and.
     */
    public String description() {
      if (this.nodes == UNLIMITED) {
        return this.moveTimeMillis + " ms";
      }
      if (this.moveTimeMillis == UNLIMITED) {
        return this.nodes + " nodes";
      }
      return this.nodes + " nodes and " + this.moveTimeMillis + " ms";
    }

    /**
     * Renders this limit as the fields following a go command, each led by a space.
     *
     * @return The fields naming this limit.
     */
    private String fields() {
      final StringBuilder fields = new StringBuilder();
      if (this.nodes != UNLIMITED) {
        fields.append(" nodes ").append(this.nodes);
      }
      if (this.moveTimeMillis != UNLIMITED) {
        fields.append(" movetime ").append(this.moveTimeMillis);
      }
      return fields.toString();
    }
  }

  /**
   * The Reply record holds what one search command returned. At most one of the score and the
   * distance to mate is held, since a search reports a position as either an evaluation or a mate.
   *
   * @param move The move the engine chose, in long algebraic notation.
   * @param depth The depth the engine reported its search reached, or null if it reported nothing.
   * @param score The score the engine reported in centipawns, or null if it reported a mate or
   *              reported nothing.
   * @param mateIn The number of moves to the mate the engine reported, positive when the engine
   *               delivers the mate and negative when it is mated, or null if it reported a score
   *               in centipawns or reported nothing.
   * @param elapsedMillis The time the search took, in milliseconds.
   */
  public record Reply(String move, Integer depth, Integer score, Integer mateIn,
                      long elapsedMillis) {
  }

  /**
   * The Fault class reports that an engine process failed. A fault names the engine it came from,
   * so that a caller holding two of them can tell which one failed.
   */
  public static class Fault extends RuntimeException {

    /**
     * Constructs a fault naming the engine it came from.
     *
     * @param name The name of the engine that failed.
     * @param detail What the engine did, written to follow the name.
     */
    public Fault(final String name, final String detail) {
      super("The " + name + " engine " + detail);
    }
  }
}