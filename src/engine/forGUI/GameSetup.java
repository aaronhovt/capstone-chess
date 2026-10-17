package engine.forGUI;

import engine.Alliance;
import engine.forPlayer.Player;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;

/**
 * The Setup Game dialog, which chooses whether a human or the computer plays each side and the
 * depth the computer searches to. The choices take effect when the dialog is confirmed with OK;
 * cancelling or closing it keeps the previous ones.
 *
 * @author dareTo81
 * @author Aaron Ho
 */
final class GameSetup extends JDialog {

  /** The depth the computer searches to until another is chosen. */
  private static final int DEFAULT_SEARCH_DEPTH = 6;

  /** The entries of each side's player choice, the human first. */
  private static final String[] PLAYER_CHOICES = { "Human", "Computer" };

  /** The index of the computer in {@link #PLAYER_CHOICES}. */
  private static final int COMPUTER_CHOICE = 1;

  /** The player choice for white. */
  private final JComboBox<String> whiteChoice = new JComboBox<>(PLAYER_CHOICES);

  /** The player choice for black. */
  private final JComboBox<String> blackChoice = new JComboBox<>(PLAYER_CHOICES);

  /** The search depth choice. */
  private final JSpinner depthChoice = new JSpinner(
          new SpinnerNumberModel(DEFAULT_SEARCH_DEPTH, 0, Integer.MAX_VALUE, 1));

  /** Whether the computer plays white. */
  private boolean whiteIsComputer;

  /** Whether the computer plays black. */
  private boolean blackIsComputer;

  /** The depth the computer searches to. */
  private int searchDepth = DEFAULT_SEARCH_DEPTH;

  /**
   * Creates the dialog, hidden, with a human playing both sides.
   *
   * @param owner The frame the dialog belongs to.
   * @param modal Whether the dialog blocks input to the other windows while it is shown.
   */
  GameSetup(final JFrame owner, final boolean modal) {
    super(owner, "Setup Game", modal);

    final JPanel choices = new JPanel(new GridLayout(0, 2, 8, 4));
    choices.add(new JLabel("White"));
    choices.add(this.whiteChoice);
    choices.add(new JLabel("Black"));
    choices.add(this.blackChoice);
    choices.add(new JLabel("Search depth"));
    choices.add(this.depthChoice);

    final JButton confirm = new JButton("OK");
    confirm.addActionListener(event -> confirm());
    final JButton cancel = new JButton("Cancel");
    cancel.addActionListener(event -> setVisible(false));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(confirm);
    buttons.add(cancel);

    final JPanel content = new JPanel(new BorderLayout(0, 8));
    content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    content.add(choices, BorderLayout.CENTER);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
    getRootPane().setDefaultButton(confirm);
    pack();
    setLocationRelativeTo(owner);
  }

  /** Shows the dialog with the choices now in effect, and returns once it is closed. */
  void promptUser() {
    this.whiteChoice.setSelectedIndex(this.whiteIsComputer ? COMPUTER_CHOICE : 0);
    this.blackChoice.setSelectedIndex(this.blackIsComputer ? COMPUTER_CHOICE : 0);
    this.depthChoice.setValue(this.searchDepth);
    setVisible(true);
  }

  /** Puts the choices shown in the dialog into effect and hides it. */
  private void confirm() {
    try {
      this.depthChoice.commitEdit();
    } catch (final ParseException unparsable) {
      // The spinner keeps its last valid value, which is the one used.
    }
    this.whiteIsComputer = this.whiteChoice.getSelectedIndex() == COMPUTER_CHOICE;
    this.blackIsComputer = this.blackChoice.getSelectedIndex() == COMPUTER_CHOICE;
    this.searchDepth = (Integer) this.depthChoice.getValue();
    setVisible(false);
  }

  /**
   * Returns whether the computer plays the given player's side.
   *
   * @param player The player.
   * @return True if the computer plays that side, false if a human does.
   */
  boolean isAIPlayer(final Player player) {
    return player.getAlliance() == Alliance.WHITE ? this.whiteIsComputer : this.blackIsComputer;
  }

  /**
   * Returns the depth the computer searches to.
   *
   * @return The search depth.
   */
  int getSearchDepth() {
    return this.searchDepth;
  }
}
