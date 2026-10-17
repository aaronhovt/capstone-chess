package engine.forGUI;

import engine.forBoard.*;
import engine.forPiece.Piece;
import engine.forPlayer.Player;
import engine.forPlayer.forAI.AlphaBeta;
import engine.forTesting.FenParser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;

import static javax.swing.JDialog.setDefaultLookAndFeelDecorated;
import static javax.swing.SwingUtilities.*;

/**
 * The Table class represents the main user interface for the Capstone chess engine.
 * It creates a graphical chess board using Swing components and provides various features
 * such as a menu bar, game history panel, debug panel, and AI integration.
 * The class follows the singleton pattern to ensure only one instance exists.
 *
 * @author Aaron Ho
 * @author dareTo81
 */
public final class Table extends Observable {

  /** The panel displaying the game's move history. */
  private final GameHistoryPanel gameHistoryPanel;

  /** The panel for displaying debug information or messages. */
  private final DebugPanel debugPanel;

  /** The view of the chess board. */
  private final BoardView boardPanel;

  /** The log of moves made in the game. */
  public static MoveLog moveLog;

  /** The setup configuration for the current game. */
  private final GameSetup gameSetup;

  /** The current state of the chess board. */
  private Board chessBoard;

  /** The move made by the computer. */
  private Move computerMove;

  /** The source tile of the piece being moved. */
  private Piece sourceTile;

  /** The piece moved by the human player. */
  private Piece humanMovedPiece;

  /**
   * The search currently running, or null when none is. Only ever read or written on the event
   * dispatch thread: the watcher assigns it when it starts a worker, and the worker clears it
   * from {@link AIThinkTank#done()}, which also runs there.
   */
  private AIThinkTank activeSearch;

  /**
   * Whether the watcher should be notified once the search now running has finished. A reset
   * that happens mid-search cannot notify the watcher on the spot, because that would start a
   * second search alongside the one already running, each with its own transposition table and
   * its own pool of search threads.
   */
  private boolean restartEngineAfterSearch;

  /**
   * The engine that plays the computer's moves, created on the first search and reused for the
   * rest of the session. Only ever read or written on the event dispatch thread.
   */
  private AlphaBeta engine;

  /** The outer frame dimension of the chessboard. */
  private static final Dimension OUTER_FRAME_DIMENSION = Toolkit.getDefaultToolkit().getScreenSize();

  /** The single instance of the Table class (singleton). */
  private static final Table Instance = new Table();

  /**
   * Constructs an instance of the Table class, creating the main graphical user interface.
   * Initializes and configures various components including the game frame, menu bar,
   * chess board, game history panel, debug panel, and game setup options.
   */
  private Table() {
    JFrame gameFrame = new JFrame("Capstone Chess");
    final JMenuBar tableMenuBar = new JMenuBar();
    populateMenuBar(tableMenuBar);
    gameFrame.setJMenuBar(tableMenuBar);
    gameFrame.setLayout(new BorderLayout());
    this.chessBoard = Board.createStandardBoard();
    this.gameHistoryPanel = new GameHistoryPanel();
    this.debugPanel = new DebugPanel();
    this.boardPanel = new BoardView(this::squareClicked);
    moveLog = new MoveLog();
    this.addObserver(new TableGameAIWatcher());
    this.gameSetup = new GameSetup(gameFrame, true);
    JPanel centeringPanel = new JPanel(new GridBagLayout());
    centeringPanel.add(this.boardPanel);
    gameFrame.add(centeringPanel, BorderLayout.CENTER);
    gameFrame.add(this.gameHistoryPanel, BorderLayout.EAST);
    gameFrame.add(debugPanel, BorderLayout.SOUTH);
    setDefaultLookAndFeelDecorated(true);
    gameFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    gameFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    gameFrame.setVisible(true);
  }

  /**
   * Retrieves the singleton instance of the Table class.
   *
   * @return The singleton instance of the Table class.
   */
  public static Table get() {
    return Instance;
  }

  /**
   * Retrieves the Board object representing the current state of the chess board.
   *
   * @return The Board object representing the chess board.
   */
  private Board getGameBoard() {
    return this.chessBoard;
  }

  /**
   * Retrieves the MoveLog object for tracking the move history.
   *
   * @return The MoveLog object for tracking the move history.
   */
  private MoveLog getMoveLog() {
    return moveLog;
  }

  /**
   * Retrieves the view of the chess board.
   *
   * @return The board view.
   */
  private BoardView getBoardPanel() {
    return this.boardPanel;
  }

  /**
   * Retrieves the GameHistoryPanel object for displaying move history.
   *
   * @return The GameHistoryPanel object for displaying move history.
   */
  private GameHistoryPanel getGameHistoryPanel() {
    return this.gameHistoryPanel;
  }

  /**
   * Retrieves the DebugPanel object for displaying debugging information.
   *
   * @return The DebugPanel object for displaying debugging information.
   */
  private DebugPanel getDebugPanel() {
    return this.debugPanel;
  }

  /**
   * Returns the engine, creating it on first use with the debug panel receiving its search
   * reports. Must be called on the event dispatch thread.
   *
   * @return The engine that plays the computer's moves.
   */
  private AlphaBeta getEngine() {
    if (this.engine == null) {
      this.engine = new AlphaBeta(this.gameSetup.getSearchDepth());
      this.engine.setReportListener(this.debugPanel::showReport);
    }
    return this.engine;
  }

  /**
   * Retrieves the GameSetup object for configuring game parameters.
   *
   * @return The GameSetup object for configuring game settings.
   */
  private GameSetup getGameSetup() {
    return this.gameSetup;
  }

  /**
   * Refreshes and updates the graphical user interface to reflect the current game state.
   * Updates the game history panel, redraws the chess board, and updates the debug panel.
   */
  public void show() {
    if (moveLog.size() == 0) {
      Table.get().getGameHistoryPanel().redo(chessBoard, Table.get().getMoveLog());
      Table.get().redrawBoard(Table.get().getGameBoard());
      Table.get().getDebugPanel().redo();
      setChanged();
      notifyObservers();
    }
  }

  /**
   * Populates the menu bar with the Options menu.
   *
   * @param tableMenuBar The JMenuBar to populate.
   */
  private void populateMenuBar(final JMenuBar tableMenuBar) {
    tableMenuBar.add(createOptionsMenu());
  }

  /**
   * Creates a JMenu with options to interact with the game.
   * Includes starting a new game, undoing moves, loading a position from FEN,
   * setting up game parameters, and flipping the board.
   *
   * @return The JMenu containing gameplay options.
   */
  private JMenu createOptionsMenu() {

    final JMenu optionsMenu = new JMenu("Options");
    optionsMenu.setMnemonic(KeyEvent.VK_O);

    final JMenuItem resetMenuItem = new JMenuItem("New Game", KeyEvent.VK_P);
    resetMenuItem.addActionListener(e -> undoAllMoves());
    optionsMenu.add(resetMenuItem);

    final JMenuItem undoMoveMenuItem = new JMenuItem("Undo last move", KeyEvent.VK_M);
    undoMoveMenuItem.addActionListener(e -> undoLastMove());
    optionsMenu.add(undoMoveMenuItem);

    final JMenuItem loadFenMenuItem = new JMenuItem("Load FEN", KeyEvent.VK_F);
    loadFenMenuItem.addActionListener(e -> loadPosition());
    optionsMenu.add(loadFenMenuItem);

    final JMenuItem setupGameMenuItem = new JMenuItem("Setup Game", KeyEvent.VK_S);
    setupGameMenuItem.addActionListener(e -> {
      Table.get().getGameSetup().promptUser();
      Table.get().setupUpdate(Table.get().getGameSetup());
    }); optionsMenu.add(setupGameMenuItem);

    final JMenuItem flipBoardMenuItem = new JMenuItem("Flip board");
    flipBoardMenuItem.addActionListener(e -> {
      boardPanel.flip();
      redrawBoard(chessBoard);
    });
    optionsMenu.add(flipBoardMenuItem);

    return optionsMenu;
  }

  /**
   * Updates the internal state of the chess game's board to match the provided board.
   *
   * @param board The new board configuration to be applied to the game.
   */
  private void updateGameBoard(final Board board) {
    this.chessBoard = board;
  }

  /**
   * Records the search now running, so that a reset arriving mid-search can tell it must wait
   * rather than start a second search alongside it.
   *
   * @param search The worker that has just been started.
   */
  private void setActiveSearch(final AIThinkTank search) {
    this.activeSearch = search;
  }

  /** Clears the record of the running search, called from the worker as it finishes. */
  private void clearActiveSearch() {
    this.activeSearch = null;
  }

  /**
   * Returns whether a search is running right now.
   *
   * @return True if a worker has been started and has not yet finished.
   */
  private boolean isSearchRunning() {
    return this.activeSearch != null;
  }

  /**
   * Notifies the watcher if a reset asked for the engine to be restarted while a search was
   * running, so the engine picks up the position that reset produced. Clears the request as it
   * consumes it.
   */
  private void restartEngineIfRequested() {
    if (this.restartEngineAfterSearch) {
      this.restartEngineAfterSearch = false;
      moveMadeUpdate(PlayerType.HUMAN);
    }
  }

  /**
   * Updates the internal state of the computer's chosen move.
   *
   * @param move The move chosen by the computer player.
   */
  private void updateComputerMove(final Move move) {
    this.computerMove = move;
  }

  /**
   * Reverses all moves that have been made in the current game session.
   * Effectively resets the game to its initial state.
   */
  private void undoAllMoves() {
    updateGameBoard(Board.createStandardBoard());
    Table.get().getMoveLog().clear();
    refreshAfterPositionChange();
  }

  /**
   * Brings the interface back into agreement with the game board after the position has been
   * replaced or unwound. Clears any selection and computer move highlight left over from the
   * previous position, redraws every panel, and hands the new position to the engine, deferring
   * that last step when a search is still running on the position being discarded.
   */
  private void refreshAfterPositionChange() {
    this.computerMove = null;
    this.sourceTile = null;
    this.humanMovedPiece = null;
    Table.get().getGameHistoryPanel().redo(chessBoard, Table.get().getMoveLog());
    Table.get().redrawBoard(chessBoard);
    Table.get().getDebugPanel().redo();
    if (isSearchRunning()) {
      this.restartEngineAfterSearch = true;
    } else {
      moveMadeUpdate(PlayerType.HUMAN);
    }
  }

  /**
   * Prompts for a Forsyth-Edwards Notation string and replaces the game board with the position
   * it describes, discarding the move history since no moves have been played to reach it.
   * A malformed string is reported and leaves the current position untouched.
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
    updateGameBoard(loaded);
    Table.get().getMoveLog().clear();
    refreshAfterPositionChange();
  }

  /**
   * Reverses play to the last position a human is to move from, or by one ply when both sides
   * are engine-controlled. Unwinding a single ply against the engine would leave the engine to
   * move, which the mouse listener refuses to accept input on.
   */
  private void undoLastMove() {
    final MoveLog log = Table.get().getMoveLog();
    if (log.size() == 0) {
      return;
    }
    final GameSetup setup = Table.get().getGameSetup();
    final boolean bothSidesAreAI = setup.isAIPlayer(this.chessBoard.whitePlayer()) &&
            setup.isAIPlayer(this.chessBoard.blackPlayer());

    undoOnePly();
    while (!bothSidesAreAI && log.size() > 0 && setup.isAIPlayer(this.chessBoard.currentPlayer())) {
      undoOnePly();
    }

    refreshAfterPositionChange();
  }

  /** Takes one ply off the game board and the move log together, keeping the two in step. */
  private void undoOnePly() {
    Table.get().getMoveLog().removeMove(Table.get().getMoveLog().size() - 1);
    this.chessBoard.unmakeMove();
  }

  /**
   * Sets the state as changed and notifies registered observers with the specified player type.
   *
   * @param playerType The type of player (Human or Computer) who made the move.
   */
  private void moveMadeUpdate(final PlayerType playerType) {
    setChanged();
    notifyObservers(playerType);
  }

  /**
   * Sets the state as changed and notifies registered observers with the game setup configuration.
   *
   * @param gameSetup The updated game setup configuration.
   */
  private void setupUpdate(final GameSetup gameSetup) {
    setChanged();
    notifyObservers(gameSetup);
  }

  /**
   * The TableGameAIWatcher class observes changes in the game state and triggers AI actions
   * or displays game-over messages based on certain conditions.
   */
  private static class TableGameAIWatcher implements Observer {

    /**
     * Responds to updates from the observed object, triggering AI actions or
     * displaying game-over messages based on the current game state.
     *
     * @param o   The observable object.
     * @param arg An argument passed by the observed object.
     */
    @Override
    public void update(final Observable o,
                       final Object arg) {
      final Board board = Table.get().getGameBoard();
      final Player currentPlayer = board.currentPlayer();
      final boolean isInCheckMate = currentPlayer.isInCheckMate();
      final boolean isInStaleMate = currentPlayer.isInStaleMate();
      final boolean isThreefold = board.isThreefoldRepetition();
      final boolean isFiftyMove = board.isFiftyMoveRule();
      final boolean isGameOver = isInCheckMate || isInStaleMate || isThreefold || isFiftyMove;

      if (isInCheckMate) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
            "Game Over: Player " + currentPlayer + " is in checkmate!", "Game Over",
            JOptionPane.INFORMATION_MESSAGE);
      } else if (isInStaleMate) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
            "Game Over: Player " + currentPlayer + " is in stalemate!", "Game Over",
            JOptionPane.INFORMATION_MESSAGE);
      } else if (isThreefold) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
            "Game Over: Draw by threefold repetition.", "Game Over",
            JOptionPane.INFORMATION_MESSAGE);
      } else if (isFiftyMove) {
        JOptionPane.showMessageDialog(Table.get().getBoardPanel(),
            "Game Over: Draw by the fifty-move rule.", "Game Over",
            JOptionPane.INFORMATION_MESSAGE);
      } if (Table.get().getGameSetup().isAIPlayer(currentPlayer) && !isGameOver) {
        System.out.println(currentPlayer + " is thinking....");
        final AIThinkTank thinkTank = new AIThinkTank();
        Table.get().setActiveSearch(thinkTank);
        thinkTank.execute();
      }
    }
  }

  /**
   * The PlayerType enumeration represents the types of players in the chess game.
   */
  enum PlayerType {

    /** Represents a human player. */
    HUMAN,

    /** Represents a computer player. */
    COMPUTER
  }

  /**
   * The AIThinkTank class is an asynchronous worker responsible for AI move calculation and execution.
   */
  private static class AIThinkTank extends SwingWorker <Move, String> {

    /**
     * The position the search runs against, copied from the game board when this worker is
     * constructed. The search reads a board heavily, taking the root move list from it and
     * copying it once per search thread, while the event dispatch thread goes on mutating the
     * game board through undo and new game. Neither the board's piece collections nor its
     * players' lazily cached legal move lists carry any synchronization, so the two threads must
     * not be looking at the same object.
     */
    private final Board searchBoard;

    /** The search depth, read from the game setup dialog when this worker is constructed. */
    private final int searchDepth;

    /** The engine this search runs on, shared with every other search in the session. */
    private final AlphaBeta engine;

    /** The panel the search reports its per-depth progress to. */
    private final DebugPanel debugPanel;

    /**
     * The game board this search was started against, held for identity comparison and never
     * read from the background thread. If the game board has been replaced by the time the
     * search finishes, as a new game replaces it, the position that was searched is gone.
     */
    private final Board gameBoard;

    /** The game board's ply count when this search started. */
    private final int plyCountAtStart;

    /** The game board's Zobrist hash when this search started. */
    private final long zobristHashAtStart;

    /**
     * Constructs an instance of AIThinkTank, taking its private copy of the position and
     * everything else the search needs up front. This constructor runs on the event dispatch
     * thread, which is what makes reading the game board and the setup dialog's spinner here
     * safe. Neither may be read from {@link #doInBackground}.
     */
    private AIThinkTank() {
      this.gameBoard = Table.get().getGameBoard();
      this.plyCountAtStart = this.gameBoard.getPlyCount();
      this.zobristHashAtStart = this.gameBoard.getZobristHash();
      this.searchBoard = this.gameBoard.copy();
      this.searchDepth = Table.get().getGameSetup().getSearchDepth();
      this.debugPanel = Table.get().getDebugPanel();
      this.engine = Table.get().getEngine();
    }

    /**
     * Performs AI move calculation in the background, against this worker's private copy of the
     * position. This method deliberately reaches for nothing outside its own fields: everything
     * it needs was captured on the event dispatch thread in the constructor, and that is the
     * property that keeps the search off the shared game board.
     *
     * @return The best move calculated by the AI.
     */
    @Override
    protected Move doInBackground() {
      return this.engine.execute(this.searchBoard, this.searchDepth);
    }

    /**
     * Returns whether the game has moved on from the position this search was run against, in
     * which case the move the search found belongs to a position that no longer exists and must
     * not be played.
     *
     * @param board The game board as it stands now.
     * @return True if the position changed and the search result must be discarded.
     */
    private boolean positionHasMovedOn(final Board board) {
      return board != this.gameBoard
          || board.getPlyCount() != this.plyCountAtStart
          || board.getZobristHash() != this.zobristHashAtStart;
    }

    /**
     * Handles the completion of the AI move calculation, applying the move the search found to
     * the game board and refreshing the UI, unless the position the search was run against has
     * since changed.
     */
    @Override
    public void done() {
      Table.get().clearActiveSearch();
      try {
        final Move bestMove = get();
        final Board board = Table.get().getGameBoard();
        assert this.searchBoard.getZobristHash() == this.zobristHashAtStart :
            "The search left its own board somewhere other than the position it was handed.";

        if (positionHasMovedOn(board)) {
          System.out.println("Discarding the computer's move: the position changed while it was thinking.");
          return;
        }
        if (bestMove == Move.nullMove()) {
          System.out.println("The search returned no move, so there is nothing to play.");
          return;
        }

        final String notation = bestMove.toNotation(board);
        Table.get().updateComputerMove(bestMove);
        board.makeMove(bestMove);
        Table.get().getMoveLog().addMove(bestMove, notation);
        Table.get().getGameHistoryPanel().redo(board, Table.get().getMoveLog());
        Table.get().redrawBoard(board);
        Table.get().getDebugPanel().redo();
        Table.get().moveMadeUpdate(PlayerType.COMPUTER);
      } catch (final Exception e) {
        System.out.println("Exception in AI move handling!");
        e.printStackTrace();
      } finally {
        Table.get().restartEngineIfRequested();
      }
    }
  }

  /**
   * The MoveLog class represents a log of moves made during a chess game, together with the
   * algebraic notation each move was rendered with at the time it was played.
   */
  public static class MoveLog {

    /** A list of moves to be used in the move log. */
    private final List <Move> moves;

    /**
     * The algebraic notation for each logged move, held in the same order as {@link #moves} and
     * captured at the moment the move was played. Notation is frozen here rather than rendered
     * on demand because disambiguation has to be resolved against the position the move was
     * played from, and under the mutable board model that position no longer exists once the
     * game moves on. Every mutator on this class must keep the two lists in step.
     */
    private final List <String> notations;

    /** Constructs a new MoveLog. */
    MoveLog() {
      this.moves = new ArrayList <>();
      this.notations = new ArrayList <>();
    }

    /**
     * Gets the list of moves stored in the MoveLog.
     *
     * @return The list of moves.
     */
    public List <Move> getMoves() {
      return this.moves;
    }

    /**
     * Gets the notation captured for the move at the given index.
     *
     * @param index The index of the move.
     * @return The algebraic notation that move was rendered with when it was played.
     */
    public String getNotation(final int index) {
      return this.notations.get(index);
    }

    /**
     * Adds a move to the MoveLog, along with the notation it was rendered with.
     *
     * @param move The move to be added.
     * @param notation The algebraic notation for the move, rendered against the position it was played from.
     */
    void addMove(final Move move, final String notation) {
      this.moves.add(move);
      this.notations.add(notation);
    }

    /**
     * Gets the number of moves in the MoveLog.
     *
     * @return The number of moves.
     */
    public int size() {
      return this.moves.size();
    }

    /**
     * Clears all moves from the MoveLog.
     */
    void clear() {
      this.moves.clear();
      this.notations.clear();
    }

    /**
     * Removes a move at the specified index from the MoveLog.
     *
     * @param index The index of the move to be removed.
     * @return The removed move.
     */
    Move removeMove(final int index) {
      this.notations.remove(index);
      return this.moves.remove(index);
    }
  }

  /**
   * Shows the given position on the board view, outlining the selected piece when it belongs to
   * the side to move and marking the computer's last move.
   *
   * @param board The position to show.
   */
  private void redrawBoard(final Board board) {
    final int selected = this.humanMovedPiece != null &&
            this.humanMovedPiece.getPieceAllegiance() == board.currentPlayer().getAlliance() ?
            this.humanMovedPiece.getPiecePosition() : -1;
    this.boardPanel.drawBoard(board, selected, this.computerMove);
  }

  /**
   * Handles a click on a square of the board. A left click selects the piece on the square, or,
   * with a piece already selected, plays the move from its square to this one if that is a legal
   * move of the side to move. A right click clears the selection. Clicks are ignored while the
   * computer is to move or the game is over.
   *
   * @param tileId The square clicked.
   * @param event The click.
   */
  private void squareClicked(final int tileId, final MouseEvent event) {
    if (Table.get().getGameSetup().isAIPlayer(Table.get().getGameBoard().currentPlayer()) ||
        BoardUtils.isEndOfGame(Table.get().getGameBoard()) ||
        BoardUtils.isDrawnPosition(Table.get().getGameBoard())) {
      return;
    } if (isRightMouseButton(event)) {
      sourceTile = null;
      humanMovedPiece = null;
    } else if (isLeftMouseButton(event)) {
      if (sourceTile == null) {
        sourceTile = chessBoard.getPiece(tileId);
        humanMovedPiece = sourceTile;
      } else {
        final Move candidate = Move.find(chessBoard, sourceTile.getPiecePosition(), tileId);
        if (candidate != Move.nullMove() &&
            candidate.getMovedPiece().getPieceAllegiance() == chessBoard.currentPlayer().getAlliance()) {
          final Move move = resolvePromotion(candidate);
          if (move != Move.nullMove()) {
            final String notation = move.toNotation(chessBoard);
            chessBoard.makeMove(move);
            if (chessBoard.currentPlayer().getOpponent().isInCheck()) {
              chessBoard.unmakeMove();
            } else {
              moveLog.addMove(move, notation);
            }
          }
        } sourceTile = null;
        humanMovedPiece = null;
      }
    } invokeLater(() -> {
      gameHistoryPanel.redo(chessBoard, moveLog);
      Table.get().moveMadeUpdate(PlayerType.HUMAN);
      redrawBoard(chessBoard);
      debugPanel.redo();
    });
  }

  /**
   * Asks the player which piece a promoting pawn should become.
   *
   * @return The chosen promotion piece type, or null if the player dismissed the dialog.
   */
  private Piece.PieceType promptForPromotionPiece() {
    final String[] choices = { "Queen", "Rook", "Bishop", "Knight" };
    final int chosen = JOptionPane.showOptionDialog(
        Table.get().getBoardPanel(),
        "Promote the pawn to:",
        "Pawn Promotion",
        JOptionPane.DEFAULT_OPTION,
        JOptionPane.PLAIN_MESSAGE,
        null,
        choices,
        choices[0]);
    return switch (chosen) {
      case 0 -> Piece.PieceType.QUEEN;
      case 1 -> Piece.PieceType.ROOK;
      case 2 -> Piece.PieceType.BISHOP;
      case 3 -> Piece.PieceType.KNIGHT;
      default -> null;
    };
  }

  /**
   * Resolves a candidate move into the move the player actually intends, asking which piece to
   * promote to when the candidate is a promotion. Non promotions are returned untouched, and a
   * dismissed dialog cancels the move rather than silently promoting to a queen.
   *
   * @param candidate The move matched from the squares the player clicked.
   * @return The move to play, or the null move if the player cancelled.
   */
  private Move resolvePromotion(final Move candidate) {
    if (candidate.getPromotionPiece() == null) {
      return candidate;
    }
    final Piece.PieceType chosen = promptForPromotionPiece();
    if (chosen == null) {
      return Move.nullMove();
    }
    return Move.find(chessBoard, candidate.getCurrentCoordinate(),
        candidate.getDestinationCoordinate(), chosen);
  }
}