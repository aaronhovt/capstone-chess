package engine;

import engine.forGUI.Table;
import engine.forNetwork.VectorModule;

/**
 * The entry point of the graphical chess game, which opens the game window on the standard
 * starting position.
 *
 * @author Aaron Ho
 */
public class engineDriver {

  /**
   * Opens the game window. Started without the module {@value VectorModule#NAME}, opens it from a
   * new Java process that has it, as {@link VectorModule#relaunchIfMissing} describes.
   *
   * @param args Command line arguments, which are ignored.
   */
  public static void main(String[] args) {
    VectorModule.relaunchIfMissing(engineDriver.class, args);
    Table.open();
  }
}