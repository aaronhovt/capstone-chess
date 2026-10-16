package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Pawn;

/**
 * The NullMoveUndo record captures the board state that a null move overwrites, so that
 * {@link Board#unmakeNullMove()} can restore it exactly. A null move changes only whose turn it
 * is and the state that hangs off that: no piece moves, so nothing about piece placement needs
 * recording.
 *
 * @param priorEnPassantPawn The en passant pawn on the board immediately before the null move.
 * @param priorZobristHash The Zobrist hash of the board immediately before the null move.
 * @param priorHalfMoveClock The halfmove clock immediately before the null move.
 * @param priorTransitionMove The transition move recorded on the board immediately before the null move.
 * @param priorMoveMaker The alliance to move immediately before the null move.
 *
 * @author Aaron Ho
 */
public record NullMoveUndo(Pawn priorEnPassantPawn,
                           long priorZobristHash,
                           int priorHalfMoveClock,
                           Move priorTransitionMove,
                           Alliance priorMoveMaker) {
}