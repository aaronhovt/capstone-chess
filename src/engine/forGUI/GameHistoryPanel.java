package engine.forGUI;

import engine.forBoard.Board;
import engine.forBoard.Move;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static engine.forGUI.Table.*;

/**
 * The GameHistoryPanel class displays the move history of the game in a table format.
 * It tracks moves made by both white and black players, indicating if a move leads
 * to check or checkmate for the opposing player.
 *
 * @author Aaron Ho
 */
class GameHistoryPanel extends JPanel {

  /** The DataModel containing the moves for the history table. */
  private final DataModel model;

  /** The scroll pane for the panel. */
  private final JScrollPane scrollPane;

  /** The dimension of the history panel. */
  private static final Dimension HistoryPanelDimension = new Dimension(250, 40);

  /**
   * Constructs a new GameHistoryPanel by setting up the layout and initializing the data model.
   * The panel displays move history in a table with "White" and "Black" columns.
   */
  GameHistoryPanel() {
    this.setLayout(new BorderLayout());
    this.model = new DataModel();
    final JTable table = new JTable(model);
    table.setRowHeight(15);
    this.scrollPane = new JScrollPane(table);
    scrollPane.setColumnHeaderView(table.getTableHeader());
    scrollPane.setPreferredSize(HistoryPanelDimension);
    this.add(scrollPane, BorderLayout.CENTER);
    this.setVisible(true);
  }

  void redo(final Board board, final MoveLog moveHistory) {
    int currentRow = 0;
    this.model.clear();
    final List<Move> moves = moveHistory.getMoves();
    for (int i = 0; i < moves.size(); i++) {
      final Move move = moves.get(i);
      final String moveText = moveHistory.getNotation(i);
      if (move.getMovedPiece().getPieceAllegiance().isWhite()) {
        this.model.setValueAt(moveText, currentRow, 0);
      } else if (move.getMovedPiece().getPieceAllegiance().isBlack()) {
        this.model.setValueAt(moveText, currentRow, 1);
        currentRow++;
      }
    }
    if (!moves.isEmpty()) {
      final int lastIndex = moves.size() - 1;
      final Move lastMove = moves.get(lastIndex);
      final String moveText = moveHistory.getNotation(lastIndex);
      if (lastMove.getMovedPiece().getPieceAllegiance().isWhite()) {
        this.model.setValueAt(moveText + calculateCheckAndCheckMateHash(board), currentRow, 0);
      } else if (lastMove.getMovedPiece().getPieceAllegiance().isBlack()) {
        this.model.setValueAt(moveText + calculateCheckAndCheckMateHash(board), currentRow - 1, 1);
      }
    }
    final JScrollBar vertical = scrollPane.getVerticalScrollBar();
    vertical.setValue(vertical.getMaximum());
  }

  /**
   * Calculates and returns a string hash indicating check or checkmate status.
   *
   * @param board The current game board.
   * @return A hash string representing the game status: "+" for check, "#" for checkmate, or empty.
   */
  private static String calculateCheckAndCheckMateHash(final Board board) {
    if (board.currentPlayer().isInCheckMate()) {
      return "#";
    } else if (board.currentPlayer().isInCheck()) {
      return "+";
    }
    return "";
  }

  /**
   * The Row class represents a row of data in the move history table.
   * Contains move texts for both white and black players.
   */
  private class Row {

    /** The move text for the white player. */
    private String whiteMove;

    /** The move text for the black player. */
    private String blackMove;

    /** Constructs a new Row instance. */
    Row() {
    }

    /**
     * Gets the move text for the white player.
     *
     * @return The move text for the white player.
     */
    public String getWhiteMove() {
      return this.whiteMove;
    }

    /**
     * Gets the move text for the black player.
     *
     * @return The move text for the black player.
     */
    public String getBlackMove() {
      return this.blackMove;
    }

    /**
     * Sets the move text for the white player.
     *
     * @param move The move text to be set.
     */
    public void setWhiteMove(final String move) {
      this.whiteMove = model.getRowCount() + ". " + move;
    }

    /**
     * Sets the move text for the black player.
     *
     * @param move The move text to be set.
     */
    public void setBlackMove(final String move) {
      this.blackMove = model.getRowCount() + ". " + move;
    }
  }

  /**
   * The DataModel class provides a custom data model for the move history table.
   * Manages the storage and retrieval of move texts for display in the table.
   */
  private class DataModel extends DefaultTableModel {

    /** The list of row objects representing data in the move history table. */
    private final List<Row> values;

    /** The column names for the move history table. */
    private static final String[] NAMES = {
            "White",
            "Black"
    };

    /** Constructs a new DataModel with an empty list of values. */
    DataModel() {
      this.values = new ArrayList<>();
    }

    /** Clears the data model, removing all stored values. */
    public void clear() {
      this.values.clear();
      setRowCount(0);
    }

    /**
     * Returns the total row count for the DataModel.
     *
     * @return The number of rows in the model.
     */
    @Override
    public int getRowCount() {
      if (this.values == null) {
        return 0;
      }
      return this.values.size();
    }

    /**
     * Returns the column count for the DataModel.
     *
     * @return The number of columns in the model.
     */
    @Override
    public int getColumnCount() {
      return NAMES.length;
    }

    /**
     * Returns the value in a specific row and column.
     *
     * @param row The row index.
     * @param col The column index.
     * @return The value at the specified position.
     */
    @Override
    public Object getValueAt(final int row, final int col) {
      final Row currentRow = this.values.get(row);
      if (col == 0) {
        return currentRow.getWhiteMove();
      } else if (col == 1) {
        return currentRow.getBlackMove();
      }
      return null;
    }

    /**
     * Sets the value in a specific row and column.
     *
     * @param aValue The value to be set.
     * @param row    The row index.
     * @param col    The column index.
     */
    @Override
    public void setValueAt(final Object aValue, final int row, final int col) {
      final Row currentRow;
      if (this.values.size() <= row) {
        currentRow = new Row();
        this.values.add(currentRow);
      } else {
        currentRow = this.values.get(row);
      }
      if (col == 0) {
        currentRow.setWhiteMove((String) aValue);
        fireTableRowsInserted(row, row);
      } else if (col == 1) {
        currentRow.setBlackMove((String) aValue);
        fireTableCellUpdated(row, col);
      }
    }

    /**
     * Returns the class type for the specified column.
     *
     * @param col The column index.
     * @return The class type of the column.
     */
    @Override
    public Class<?> getColumnClass(final int col) {
      return Move.class;
    }

    /**
     * Returns the name of the specified column.
     *
     * @param col The column index.
     * @return The name of the column.
     */
    @Override
    public String getColumnName(final int col) {
      return NAMES[col];
    }
  }
}