package engine.forBoard;

/**
 * The MoveUtils class is a utility class that provides various helper methods related to chess moves and calculations.
 * It serves as a collection of static utilities for move management, evaluation, and handling special move cases
 * such as null moves.
 * <p>
 * This class follows the singleton pattern with a public Instance field to ensure consistent global access
 * to its functionality throughout the chess engine.
 *
 * @author Aaron Ho
 */
public class MoveUtils {

  /**
   * An instance of the MoveUtils class that can be used to access its methods.
   * This field provides global access to the utility functionality while maintaining singleton behavior.
   */
  public static final MoveUtils Instance = new MoveUtils();

  /**
   * A special move representing a lack of valid move, used to signify an invalid or non-existent move.
   * This constant serves as a null object pattern implementation for moves, allowing code to avoid
   * null checks when dealing with potentially absent moves.
   */
  public static final Move NULL_MOVE = new Move.NullMove();
}