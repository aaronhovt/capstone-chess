package engine.forGUI;

import javax.swing.*;
import java.awt.*;
import java.util.Observable;
import java.util.Observer;

/**
 * The strip along the bottom of the window that shows the engine's latest search report. It
 * observes the engine, which notifies it with the report text after each completed iteration,
 * and may be notified from any thread.
 *
 * @author dareTo81
 * @author Aaron Ho
 */
final class DebugPanel extends JPanel implements Observer {

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
   * Shows the given report, on the event dispatch thread.
   *
   * @param source The engine that sent the report.
   * @param message The report, shown through its string form.
   */
  @Override
  public void update(final Observable source, final Object message) {
    SwingUtilities.invokeLater(() -> {
      this.report.setText(String.valueOf(message).trim());
      redo();
    });
  }
}