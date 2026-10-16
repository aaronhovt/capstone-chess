package engine.forPlayer.forAI;

import engine.forBoard.Board;
import engine.forBoard.Move;

/**
 * The MoveStrategy interface defines a contract for AI move selection algorithms used in chess gameplay.
 * Implementations of this interface represent different strategies that AI players can use to determine
 * the best move in a given board position. This interface follows the Strategy pattern, allowing
 * different AI algorithms to be plugged in interchangeably without modifying the client code.
 * <p>
 * Common implementations include minimax, alpha-beta pruning, Monte Carlo Tree Search, and other
 * chess engine algorithms. Each strategy implementation is responsible for analyzing the board
 * position and returning what it considers to be the optimal move for the current player.
 *
 * @author Aaron Ho
 */
public interface MoveStrategy {

  /**
   * Executes the move selection algorithm on the given board position and returns the best move
   * found according to this strategy's evaluation criteria. The implementation should analyze
   * the current board state, consider legal moves available to the current player, and apply
   * its specific algorithm to determine the optimal choice.
   *
   * @param board The current chess board position to analyze for move selection.
   * @return The best move determined by this strategy, or a null move if no valid moves are available.
   */
  Move execute(Board board);
}