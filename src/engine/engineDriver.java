package engine;

import engine.forGUI.Table;

/**
 * The entry point of the graphical chess game, which opens the game window on the standard
 * starting position.
 *
 * @author Aaron Ho
 */
public class engineDriver {

  /**
   * Opens the game window.
   *
   * @param args Command line arguments, which are ignored.
   */
  public static void main(String[] args) {
    Table.open();
  }
}