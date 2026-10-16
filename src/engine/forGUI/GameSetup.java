package engine.forGUI;

import engine.Alliance;
import engine.forGUI.Table.PlayerType;
import engine.forPlayer.Player;

import javax.swing.*;
import java.awt.*;

/**
 * The `GameSetup` class represents a dialog for configuring game setup options using Java Swing.
 * This dialog allows users to specify player types (human or computer) for both white and black sides, as well
 * as set the search depth for AI move calculation.
 *
 * @author dareTo81
 * @author Aaron Ho
 */
class GameSetup extends JDialog {

  /*** The PlayerType representing the current white player. */
  private PlayerType whitePlayerType;

  /*** The PlayerType representing the current black player. */
  private PlayerType blackPlayerType;

  /*** The search depth selector. */
  private final JSpinner searchDepthSpinner;

  /*** String used for representing a human player. */
  private static final String HUMAN_TEXT = "Human";

  /*** String used for representing a computer player. */
  private static final String COMPUTER_TEXT = "Computer";

  /**
   * Constructs a new GameSetup dialog with the specified parent frame and modality.
   *
   * @param frame The parent frame for the dialog.
   * @param modal Specifies whether the dialog should be modal.
   */
  GameSetup(final JFrame frame,
            final boolean modal) {
    super(frame, modal);
    final JPanel myPanel = new JPanel(new GridLayout(0, 1));
    final JRadioButton whiteHumanButton = new JRadioButton(HUMAN_TEXT);
    final JRadioButton whiteComputerButton = new JRadioButton(COMPUTER_TEXT);
    final JRadioButton blackHumanButton = new JRadioButton(HUMAN_TEXT);
    final JRadioButton blackComputerButton = new JRadioButton(COMPUTER_TEXT);
    
    whiteHumanButton.setActionCommand(HUMAN_TEXT);
    final ButtonGroup whiteGroup = new ButtonGroup();
    whiteGroup.add(whiteHumanButton);
    whiteGroup.add(whiteComputerButton);
    whiteHumanButton.setSelected(true);
    
    final ButtonGroup blackGroup = new ButtonGroup();
    blackGroup.add(blackHumanButton);
    blackGroup.add(blackComputerButton);
    blackHumanButton.setSelected(true);
    
    getContentPane().add(myPanel);
    myPanel.add(new JLabel("White"));
    myPanel.add(whiteHumanButton);
    myPanel.add(whiteComputerButton);
    myPanel.add(new JLabel("Black"));
    myPanel.add(blackHumanButton);
    myPanel.add(blackComputerButton);
    myPanel.add(new JLabel("Search"));
    
    this.searchDepthSpinner = addLabeledSpinner(myPanel, new SpinnerNumberModel(6, 0, Integer.MAX_VALUE, 1));
    final JButton cancelButton = new JButton("Cancel");
    final JButton okButton = new JButton("OK");
    okButton.addActionListener(e -> {
      whitePlayerType = whiteComputerButton.isSelected() ? PlayerType.COMPUTER : PlayerType.HUMAN;
      blackPlayerType = blackComputerButton.isSelected() ? PlayerType.COMPUTER : PlayerType.HUMAN;
      GameSetup.this.setVisible(false);
    });
    cancelButton.addActionListener(e -> GameSetup.this.setVisible(false));
    myPanel.add(cancelButton);
    myPanel.add(okButton);
    setLocationRelativeTo(frame);
    pack();
    setVisible(false);
  }

  /*** Displays the dialog to prompt the user for game setup options. */
  void promptUser() {
    setVisible(true);
    repaint();
  }

  /**
   * Checks if AI controls the given player on the setup configuration.
   *
   * @param player The player for which to check AI status.
   * @return       True if the player is AI-controlled, indicating a computer player; false if the player is human-controlled.
   */
  boolean isAIPlayer(final Player player) {
    if (player.getAlliance() == Alliance.WHITE) {
      return getWhitePlayerType() == PlayerType.COMPUTER;
    }
    return getBlackPlayerType() == PlayerType.COMPUTER;
  }

  /**
   * Returns the type of player (human or computer) for the white side.
   *
   * @return The player type for the white side: either human or computer, as configured in the setup dialog.
   */
  PlayerType getWhitePlayerType() {
    return this.whitePlayerType;
  }

  /**
   * Returns the type of player (human or computer) for the black side.
   *
   * @return The player type for the black side: either human or computer, as configured in the setup dialog.
   */
  PlayerType getBlackPlayerType() {
    return this.blackPlayerType;
  }

  /**
   * Returns a JSpinner that will represent a customizable option in the GUI.
   *
   * @param c The container to which the label and spinner components will be added.
   * @param model The spinner model that defines the spinner's behavior, value range, and step size.
   * @return a label JSpinner.
   */
  private static JSpinner addLabeledSpinner(final Container c,
                                            final SpinnerModel model) {
    final JLabel l = new JLabel("Search Depth");
    c.add(l);
    final JSpinner spinner = new JSpinner(model);
    l.setLabelFor(spinner);
    c.add(spinner);
    return spinner;
  }

  /**
   * Returns the search depth specified for AI moves.
   *
   * @return The search depth for AI moves.
   */
  int getSearchDepth() {
    return (Integer) this.searchDepthSpinner.getValue();
  }
}
