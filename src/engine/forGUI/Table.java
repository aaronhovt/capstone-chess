package engine.forGUI;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.Piece;
import engine.forPlayer.Player;
import engine.forPlayer.forAI.AlphaBeta;
import engine.forTesting.FenParser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The game window: the board, the move list, the search report strip and the Options menu. A
 * human plays a side by clicking squares, and the computer plays the sides the Setup Game dialog
 * gives it, searching on a background thread. Everything else runs on the event dispatch thread.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Table {

  /** The view of the board. */
  private final BoardView boardView;

  /** The move list beside the board. */
  private final GameHistoryPanel historyPanel;

  /** The search report strip below the board. */
  private final DebugPanel reportPanel;

  /** The Setup Game dialog, which says who plays each side and how deep the computer searches. */
  private final GameSetup setup;

  /** The moves played since the game or the loaded position began. */
  private final MoveLog moveLog = new MoveLog();

  /** The position being played. */
  private Board board = Board.createStandardBoard();

  /** The computer's last move, marked on the board, or null. */
  private Move engineMove;

  /** The square of the piece the human has selected, or -1. */
  private int selectedSquare = -1;

  /** The search running now, or null. */
  private EngineSearch activeSearch;

  /**
   * Whether the position changed while a search was running, so the game must be looked at again
   * once that search finishes.
   */
  private boolean restartEngineAfterSearch;

  /**
   * The engine that plays the computer's moves, created on the first search and reused for the
   * rest of the session.
   */
  private AlphaBeta engine;

  /** Builds and shows the window, starting from the standard position. */
  private Table() {
    final JFrame frame = new JFrame("Capstone Chess");
    frame.setJMenuBar(createMenuBar());
    this.boardView = new BoardView(this::squareClicked);
    this.historyPanel = new GameHistoryPanel();
    this.reportPanel = new DebugPanel();
    this.setup = new GameSetup(frame, true);
    final JPanel boardHolder = new JPanel(new GridBagLayout());
    boardHolder.add(this.boardView);
    frame.add(boardHolder, BorderLayout.CENTER);
    frame.add(this.historyPanel, BorderLayout.EAST);
    frame.add(this.reportPanel, BorderLayout.SOUTH);
    JDialog.setDefaultLookAndFeelDecorated(true);
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    frame.setVisible(true);
  }

  /** Opens the game window, on the event dispatch thread. May be called from any thread. */
  public static void open() {
    SwingUtilities.invokeLater(() -> new Table().afterPositionChange());
  }

  /**
   * Creates the menu bar and its Options menu.
   *
   * @return The menu bar.
   */
  private JMenuBar createMenuBar() {
    final JMenu options = new JMenu("Options");
    options.setMnemonic(KeyEvent.VK_O);
    options.add(menuItem("New Game", KeyEvent.VK_P, () -> startFrom(Board.createStandardBoard())));
    options.add(menuItem("Undo last move", KeyEvent.VK_M, this::undoLastMove));
    options.add(menuItem("Load FEN", KeyEvent.VK_F, this::loadPosition));
    options.add(menuItem("Setup Game", KeyEvent.VK_S, () -> {
      this.setup.promptUser();
      afterPositionChange();
    }));
    options.add(menuItem("Flip board", KeyEvent.VK_UNDEFINED, () -> {
      this.boardView.flip();
      redrawBoard();
    }));
    final JMenuBar menuBar = new JMenuBar();
    menuBar.add(options);
    return menuBar;
  }

  /**
   * Creates a menu item that runs the given action when chosen.
   *
   * @param text The item's text.
   * @param mnemonic The item's mnemonic key, or {@link KeyEvent#VK_UNDEFINED} for none.
   * @param action The action.
   * @return The menu item.
   */
  private static JMenuItem menuItem(final String text, final int mnemonic, final Runnable action) {
    final JMenuItem item = new JMenuItem(text, mnemonic);
    item.addActionListener(event -> action.run());
    return item;
  }

  /**
   * Brings every panel up to date with the position and moves the game on: reports the end of
   * the game if it has ended, or starts a search if the computer is to move. While a search is
   * still running, moving the game on waits until it finishes, so that two searches never run at
   * once.
   */
  private void afterPositionChange() {
    this.historyPanel.redo(this.board, this.moveLog);
    redrawBoard();
    this.reportPanel.redo();
    if (this.activeSearch != null) {
      this.restartEngineAfterSearch = true;
      return;
    }
    final Player toMove = this.board.currentPlayer();
    final String ending = endOfGameMessage(toMove);
    if (ending != null) {
      JOptionPane.showMessageDialog(this.boardView, ending, "Game Over",
              JOptionPane.INFORMATION_MESSAGE);
    } else if (this.setup.isAIPlayer(toMove)) {
      System.out.println(toMove + " is thinking....");
      this.activeSearch = new EngineSearch();
      this.activeSearch.execute();
    }
  }

  /**
   * Returns the message announcing the end of the game, if it has ended by checkmate, stalemate,
   * threefold repetition or the fifty-move rule.
   *
   * @param toMove The player to move.
   * @return The message, or null if the game goes on.
   */
  private String endOfGameMessage(final Player toMove) {
    if (toMove.isInCheckMate()) {
      return "Game Over: Player " + toMove + " is in checkmate!";
    }
    if (toMove.isInStaleMate()) {
      return "Game Over: Player " + toMove + " is in stalemate!";
    }
    if (this.board.isThreefoldRepetition()) {
      return "Game Over: Draw by threefold repetition.";
    }
    if (this.board.isFiftyMoveRule()) {
      return "Game Over: Draw by the fifty-move rule.";
    }
    return null;
  }

  /** Looks at the game again if the position changed while the search that just ended ran. */
  private void restartEngineIfRequested() {
    if (this.restartEngineAfterSearch) {
      this.restartEngineAfterSearch = false;
      afterPositionChange();
    }
  }

  /**
   * Shows the position on the board view, with the selected piece outlined and the computer's
   * last move marked.
   */
  private void redrawBoard() {
    this.boardView.drawBoard(this.board, this.selectedSquare, this.engineMove);
  }

  /**
   * Returns the engine, creating it on first use with the report strip receiving its search
   * reports.
   *
   * @return The engine that plays the computer's moves.
   */
  private AlphaBeta engine() {
    if (this.engine == null) {
      this.engine = new AlphaBeta(this.setup.getSearchDepth());
      this.engine.setReportListener(this.reportPanel::showReport);
    }
    return this.engine;
  }

  /**
   * Replaces the game with one starting from the given position, with no moves played.
   *
   * @param position The position to start from.
   */
  private void startFrom(final Board position) {
    this.board = position;
    this.moveLog.clear();
    positionReplaced();
  }

  /**
   * Clears the selection and the mark of the computer's last move, which belong to the position
   * just left, and moves the game on.
   */
  private void positionReplaced() {
    this.engineMove = null;
    this.selectedSquare = -1;
    afterPositionChange();
  }

  /**
   * Asks for a Forsyth-Edwards Notation string and starts a game from the position it describes.
   * A malformed string is reported and leaves the game as it was.
   */
  private void loadPosition() {
    final String fen = JOptionPane.showInputDialog(null, "Enter a FEN string:",
            "Load FEN", JOptionPane.PLAIN_MESSAGE);
    if (fen == null || fen.isBlank()) {
      return;
    }
    final Board loaded;
    try {
      loaded = FenParser.parse(fen);
    } catch (final IllegalArgumentException exception) {
      JOptionPane.showMessageDialog(null, exception.getMessage(), "Invalid FEN",
              JOptionPane.ERROR_MESSAGE);
      return;
    }
    startFrom(loaded);
  }

  /**
   * Takes back moves to the last position a human is to move from, or one move when the computer
   * plays both sides.
   */
  private void undoLastMove() {
    if (this.moveLog.size() == 0) {
      return;
    }
    final boolean computerPlaysBoth = this.setup.isAIPlayer(this.board.whitePlayer()) &&
            this.setup.isAIPlayer(this.board.blackPlayer());
    takeBackOneMove();
    while (!computerPlaysBoth && this.moveLog.size() > 0 &&
            this.setup.isAIPlayer(this.board.currentPlayer())) {
      takeBackOneMove();
    }
    positionReplaced();
  }

  /** Takes the last move off the board and the move log together. */
  private void takeBackOneMove() {
    this.moveLog.removeLast();
    this.board.unmakeMove();
  }

  /**
   * Handles a click on a square of the board. A left click selects a piece of the side to move,
   * or, with one selected, plays the move from its square to this one if that is a legal move,
   * and clears the selection either way. A right click clears the selection. Clicks are ignored
   * while the computer is to move or the game is over.
   *
   * @param square The square clicked.
   * @param event The click.
   */
  private void squareClicked(final int square, final MouseEvent event) {
    if (this.setup.isAIPlayer(this.board.currentPlayer()) || BoardUtils.isEndOfGame(this.board) ||
            BoardUtils.isDrawnPosition(this.board)) {
      return;
    }
    if (SwingUtilities.isRightMouseButton(event)) {
      this.selectedSquare = -1;
      redrawBoard();
      return;
    }
    if (!SwingUtilities.isLeftMouseButton(event)) {
      return;
    }
    if (this.selectedSquare < 0) {
      final Piece piece = this.board.getPiece(square);
      if (piece != null && piece.getPieceAllegiance() == this.board.currentPlayer().getAlliance()) {
        this.selectedSquare = square;
      }
      redrawBoard();
      return;
    }
    final Move move = chooseMove(this.selectedSquare, square);
    this.selectedSquare = -1;
    if (move == null) {
      redrawBoard();
      return;
    }
    final String notation = move.toNotation(this.board);
    this.board.makeMove(move);
    this.moveLog.add(move, notation);
    afterPositionChange();
  }

  /**
   * Returns the legal move of the side to move between the given squares, asking which piece to
   * promote to when it is a promotion.
   *
   * @param origin The square the move leaves.
   * @param destination The square it arrives on.
   * @return The move, or null if there is no such legal move or the promotion was cancelled.
   */
  private Move chooseMove(final int origin, final int destination) {
    Move move = Move.find(this.board, origin, destination);
    if (move == Move.nullMove() ||
            move.getMovedPiece().getPieceAllegiance() != this.board.currentPlayer().getAlliance()) {
      return null;
    }
    if (move.getPromotionPiece() != null) {
      final Piece.PieceType promotion = askForPromotion();
      if (promotion == null) {
        return null;
      }
      move = Move.find(this.board, origin, destination, promotion);
    }
    return this.board.isLegal(move) ? move : null;
  }

  /**
   * Asks the player which piece a promoting pawn should become.
   *
   * @return The chosen piece type, or null if the player dismissed the dialog.
   */
  private Piece.PieceType askForPromotion() {
    final String[] choices = { "Queen", "Rook", "Bishop", "Knight" };
    final int chosen = JOptionPane.showOptionDialog(this.boardView, "Promote the pawn to:",
            "Pawn Promotion", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
            choices, choices[0]);
    return switch (chosen) {
      case 0 -> Piece.PieceType.QUEEN;
      case 1 -> Piece.PieceType.ROOK;
      case 2 -> Piece.PieceType.BISHOP;
      case 3 -> Piece.PieceType.KNIGHT;
      default -> null;
    };
  }

  /**
   * A search for the computer's move, run on a background thread against a private copy of the
   * position. The move it finds is played when it finishes, unless the game has moved on in the
   * meantime.
   */
  private final class EngineSearch extends SwingWorker<Move, Void> {

    /**
     * The position the search runs against, copied from the game board when this search is
     * created. The search and the event dispatch thread must not share a board, since boards carry
     * no synchronization.
     */
    private final Board searchBoard;

    /** The search depth, read from the Setup Game dialog when this search is created. */
    private final int searchDepth;

    /** The engine this search runs on. */
    private final AlphaBeta searchEngine;

    /**
     * The game board this search was started against, held for identity comparison and never read
     * from the background thread.
     */
    private final Board gameBoard;

    /** The game board's ply count when this search started. */
    private final int plyCountAtStart;

    /** The game board's Zobrist hash when this search started. */
    private final long zobristHashAtStart;

    /**
     * Creates the search, taking everything it needs on the event dispatch thread, since none of
     * it may be read from {@link #doInBackground}.
     */
    private EngineSearch() {
      this.gameBoard = Table.this.board;
      this.plyCountAtStart = this.gameBoard.getPlyCount();
      this.zobristHashAtStart = this.gameBoard.getZobristHash();
      this.searchBoard = this.gameBoard.copy();
      this.searchDepth = Table.this.setup.getSearchDepth();
      this.searchEngine = engine();
    }

    /**
     * Searches this search's private copy of the position.
     *
     * @return The move found.
     */
    @Override
    protected Move doInBackground() {
      return this.searchEngine.execute(this.searchBoard, this.searchDepth);
    }

    /**
     * Returns whether the game has moved on from the position this search was run against, in
     * which case the move it found belongs to a position that no longer exists and must not be
     * played.
     *
     * @param current The game board as it stands now.
     * @return True if the position changed and the search result must be discarded.
     */
    private boolean positionHasMovedOn(final Board current) {
      return current != this.gameBoard
          || current.getPlyCount() != this.plyCountAtStart
          || current.getZobristHash() != this.zobristHashAtStart;
    }

    /**
     * Plays the move the search found and moves the game on, unless the position the search was
     * run against has since changed.
     */
    @Override
    protected void done() {
      Table.this.activeSearch = null;
      try {
        final Move bestMove = get();
        assert this.searchBoard.getZobristHash() == this.zobristHashAtStart :
            "The search left its own board somewhere other than the position it was handed.";
        if (positionHasMovedOn(Table.this.board)) {
          System.out.println("Discarding the computer's move: the position changed while it was thinking.");
          return;
        }
        if (bestMove == Move.nullMove()) {
          System.out.println("The search returned no move, so there is nothing to play.");
          return;
        }
        final String notation = bestMove.toNotation(Table.this.board);
        Table.this.engineMove = bestMove;
        Table.this.board.makeMove(bestMove);
        Table.this.moveLog.add(bestMove, notation);
        afterPositionChange();
      } catch (final Exception failure) {
        System.out.println("Exception in AI move handling!");
        failure.printStackTrace();
      } finally {
        restartEngineIfRequested();
      }
    }
  }

  /** The moves of the game in the order they were played, each with its algebraic notation. */
  public static final class MoveLog {

    /** The moves. */
    private final List<Move> moves = new ArrayList<>();

    /**
     * The notation of each move, rendered against the position it was played from, at the same
     * index as the move.
     */
    private final List<String> notations = new ArrayList<>();

    /** Creates an empty log. */
    MoveLog() {
    }

    /**
     * Returns the moves, in the order they were played. The list belongs to this log.
     *
     * @return The moves.
     */
    public List<Move> getMoves() {
      return this.moves;
    }

    /**
     * Returns the notation of the move at the given index.
     *
     * @param index The index of the move.
     * @return The move's notation.
     */
    public String getNotation(final int index) {
      return this.notations.get(index);
    }

    /**
     * Returns the number of moves.
     *
     * @return The number of moves.
     */
    public int size() {
      return this.moves.size();
    }

    /**
     * Appends a move and its notation.
     *
     * @param move The move.
     * @param notation Its notation, rendered against the position it was played from.
     */
    void add(final Move move, final String notation) {
      this.moves.add(move);
      this.notations.add(notation);
    }

    /** Removes the last move. */
    void removeLast() {
      this.moves.removeLast();
      this.notations.removeLast();
    }

    /** Removes every move. */
    void clear() {
      this.moves.clear();
      this.notations.clear();
    }
  }
}