package engine.forGUI;

import engine.forBoard.Board;
import engine.forBoard.Move;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The move list beside the board: one row per move number, with white's move in the first column
 * and black's in the second, each cell reading "N. move". The last move carries "+" or "#" when
 * it gives check or mate.
 *
 * @author Aaron Ho
 */
final class GameHistoryPanel extends JPanel {

  /** The size the panel asks the window for. */
  private static final Dimension PREFERRED_SIZE = new Dimension(250, 40);

  /** The height of a row of the move table, in pixels. */
  private static final int ROW_HEIGHT = 15;

  /** The rows of the move table. */
  private final MoveRows rows = new MoveRows();

  /** The scroll pane holding the move table. */
  private final JScrollPane scrollPane;

  /** Creates the panel, empty. */
  GameHistoryPanel() {
    super(new BorderLayout());
    final JTable table = new JTable(this.rows);
    table.setRowHeight(ROW_HEIGHT);
    this.scrollPane = new JScrollPane(table);
    this.scrollPane.setPreferredSize(PREFERRED_SIZE);
    add(this.scrollPane, BorderLayout.CENTER);
  }

  /**
   * Rebuilds the move table from the given log and scrolls to its end. A black move with no white
   * move before it in its row, as when a game starts with black to move, is shown in a row of its
   * own.
   *
   * @param board The board in the position after the last logged move.
   * @param moveLog The moves played and the notation each was played with.
   */
  void redo(final Board board, final Table.MoveLog moveLog) {
    final List<String[]> table = new ArrayList<>();
    final List<Move> moves = moveLog.getMoves();
    for (int index = 0; index < moves.size(); index++) {
      final boolean white = moves.get(index).getMovedPiece().getPieceAllegiance().isWhite();
      if (white || table.isEmpty() || table.get(table.size() - 1)[1] != null) {
        table.add(new String[2]);
      }
      final String suffix = index == moves.size() - 1 ? checkSuffix(board) : "";
      table.get(table.size() - 1)[white ? 0 : 1] =
              table.size() + ". " + moveLog.getNotation(index) + suffix;
    }
    this.rows.replace(table);
    final JScrollBar vertical = this.scrollPane.getVerticalScrollBar();
    vertical.setValue(vertical.getMaximum());
  }

  /**
   * Returns the mark for whether the side to move on the given board is mated or in check.
   *
   * @param board The board.
   * @return "#" for mate, "+" for check, or an empty string.
   */
  private static String checkSuffix(final Board board) {
    if (board.currentPlayer().isInCheckMate()) {
      return "#";
    }
    return board.currentPlayer().isInCheck() ? "+" : "";
  }

  /** The table model of the move table, a list of rows of two cells each. */
  private static final class MoveRows extends AbstractTableModel {

    /** The column headings. */
    private static final String[] COLUMNS = { "White", "Black" };

    /** The rows, each holding white's and black's move text, null where there is none. */
    private List<String[]> rows = List.of();

    /**
     * Replaces every row of the table.
     *
     * @param rows The new rows.
     */
    void replace(final List<String[]> rows) {
      this.rows = rows;
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return this.rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(final int column) {
      return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(final int column) {
      return String.class;
    }

    @Override
    public Object getValueAt(final int row, final int column) {
      return this.rows.get(row)[column];
    }
  }
}