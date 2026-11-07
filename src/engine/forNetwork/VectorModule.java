package engine.forNetwork;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * The VectorModule class tells whether the module {@value #NAME}, which the network's arithmetic
 * needs, is in the boot layer, and starts the program again with it when it is not. The module is
 * only resolved when the java command names it with {@code --add-modules}.
 *
 * @author Aaron Ho
 */
public final class VectorModule {

  /** The name of the module the network's arithmetic needs. */
  public static final String NAME = "jdk.incubator.vector";

  /** The system property set to true in a process started by {@link #relaunchIfMissing}. */
  private static final String RELAUNCHED_PROPERTY = "engine.forNetwork.relaunched";

  private VectorModule() {
  }

  /**
   * Returns whether the module {@value #NAME} is in the boot layer.
   *
   * @return True if it is.
   */
  public static boolean isPresent() {
    return ModuleLayer.boot().findModule(NAME).isPresent();
  }

  /**
   * Returns when the module {@value #NAME} is in the boot layer. Otherwise runs the given main
   * class again in a new Java process with the module added and the same JVM options, class path,
   * arguments and standard streams, waits for it, and exits with its exit status. A process
   * started this way exits when the process that started it ends. Must be called first in main,
   * before any class that uses the network is loaded.
   *
   * @param mainClass The class whose main method is running.
   * @param args The arguments main was given.
   */
  public static void relaunchIfMissing(final Class<?> mainClass, final String[] args) {
    final boolean relaunched = Boolean.getBoolean(RELAUNCHED_PROPERTY);
    if (isPresent()) {
      if (relaunched) {
        ProcessHandle.current().parent().ifPresent(parent ->
                parent.onExit().thenRun(() -> Runtime.getRuntime().halt(1)));
      }
      return;
    }
    if (relaunched) {
      System.err.println("The module " + NAME + " is missing although java was started with " +
              "--add-modules " + NAME + ".");
      System.exit(1);
    }
    final List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("--add-modules");
    command.add(NAME);
    command.add("-D" + RELAUNCHED_PROPERTY + "=true");
    command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass.getName());
    command.addAll(List.of(args));
    int status;
    try {
      status = new ProcessBuilder(command).inheritIO().start().waitFor();
    } catch (final IOException | InterruptedException exception) {
      System.err.println("Could not start java again with --add-modules " + NAME + ": " +
              exception.getMessage());
      status = 1;
    }
    System.exit(status);
  }
}
