package engine.forGUI;

import javax.swing.*;
import java.awt.*;

/**
 * The strip along the bottom of the window that shows the engine's latest search report. The
 * engine hands it the report text after each completed iteration, from any thread.
 *
 * @author dareTo81
 * @author Aaron Ho
 */
final class DebugPanel extends JPanel {

  /** The size the strip asks the window for. */
  private static final Dimension PREFERRED_SIZE = new Dimension(550, 50);

  /** The read-only text showing the latest report. */
  private final JTextArea report = new JTextArea();

  /** Creates the strip, empty. */
  DebugPanel() {
    super(new BorderLayout());
    this.report.setEditable(false);
    add(this.report, BorderLayout.CENTER);
    setPreferredSize(PREFERRED_SIZE);
  }

  /** Lays the strip out again after its contents change. */
  void redo() {
    validate();
  }

  /**
   * Shows the given report, on the event dispatch thread. May be called from any thread.
   *
   * @param message The report.
   */
  void showReport(final String message) {
    SwingUtilities.invokeLater(() -> {
      this.report.setText(message.trim());
      redo();
    });
  }
}