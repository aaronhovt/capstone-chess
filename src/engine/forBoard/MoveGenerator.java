package engine.forBoard;

import engine.Alliance;
import engine.forPiece.King;
import engine.forPiece.Pawn;
import engine.forPiece.Piece;

import java.util.Arrays;

/**
 * MoveGenerator writes a side's pseudo-legal moves into an int array as {@link MoveCode}s. It
 * produces exactly the moves {@link engine.forPlayer.Player#getLegalMoves()} produces for that
 * side, in the same order: the side's pieces in ascending square order, each piece's moves in the
 * order its own class generates them, and the castling moves last, king side first.
 *
 * @author Aaron Ho
 */
public final class MoveGenerator {

  /** The least length of an array that can hold every move {@link #generate} writes. */
  public static final int MAX_MOVES = 256;

  /** The rank and file steps of the knight, in the order the knight generates them. */
  private static final int[][] KNIGHT_STEPS = {
          { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 }
  };

  /** The rank and file steps of the king and queen, in the order those pieces generate them. */
  private static final int[][] QUEEN_STEPS = {
          { -1, -1 }, { -1, 0 }, { -1, 1 }, { 0, -1 }, { 0, 1 }, { 1, -1 }, { 1, 0 }, { 1, 1 }
  };

  /** The rank and file steps of the bishop, in the order the bishop generates them. */
  private static final int[][] BISHOP_STEPS = { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };

  /** The rank and file steps of the rook, in the order the rook generates them. */
  private static final int[][] ROOK_STEPS = { { -1, 0 }, { 0, -1 }, { 0, 1 }, { 1, 0 } };

  /** The squares a knight reaches from each square, in generation order. */
  private static final int[][] KNIGHT_TARGETS = computeSteps(KNIGHT_STEPS);

  /** The squares a king reaches from each square, in generation order. */
  private static final int[][] KING_TARGETS = computeSteps(QUEEN_STEPS);

  /** The rays a bishop slides along from each square, in generation order. */
  private static final int[][][] BISHOP_RAYS = computeRays(BISHOP_STEPS);

  /** The rays a rook slides along from each square, in generation order. */
  private static final int[][][] ROOK_RAYS = computeRays(ROOK_STEPS);

  /** The rays a queen slides along from each square, in generation order. */
  private static final int[][][] QUEEN_RAYS = computeRays(QUEEN_STEPS);

  /** The promotion piece types, in the order a promotion is generated. */
  private static final Piece.PieceType[] PROMOTION_TYPES = {
          Piece.PieceType.QUEEN, Piece.PieceType.ROOK, Piece.PieceType.BISHOP, Piece.PieceType.KNIGHT
  };

  /** Prevents instantiation, since this class holds only tables and static generation. */
  private MoveGenerator() {
  }

  /**
   * Writes the given side's pseudo-legal moves on the given board into the given array, from
   * index zero.
   *
   * @param board The board to generate on.
   * @param side The side whose moves are generated.
   * @param moves The array to write into, of length at least {@link #MAX_MOVES}.
   * @return The number of moves written.
   */
  public static int generate(final Board board, final Alliance side, final int[] moves) {
    return generate(board, side, moves, false);
  }

  /**
   * Writes the moves the given side's quiescence search considers into the given array, from
   * index zero: every capture, en passant captures and capturing promotions to each piece
   * included, and every promotion to a queen that captures nothing. They are written in the order
   * {@link #generate} writes them.
   *
   * @param board The board to generate on.
   * @param side The side whose moves are generated.
   * @param moves The array to write into, of length at least {@link #MAX_MOVES}.
   * @return The number of moves written.
   */
  public static int generateQuiescence(final Board board, final Alliance side, final int[] moves) {
    return generate(board, side, moves, true);
  }

  /**
   * Writes the given side's pseudo-legal moves, or only those {@link #generateQuiescence}
   * describes, into the given array, from index zero.
   *
   * @param board The board to generate on.
   * @param side The side whose moves are generated.
   * @param moves The array to write into, of length at least {@link #MAX_MOVES}.
   * @param quiescence Whether to write only the quiescence moves.
   * @return The number of moves written.
   */
  private static int generate(final Board board, final Alliance side, final int[] moves,
                              final boolean quiescence) {
    final long own = board.getOccupancy(side);
    final long occupied = board.getOccupancy();
    int count = 0;
    for (long remaining = own; remaining != 0L; remaining &= remaining - 1) {
      final int square = Long.numberOfTrailingZeros(remaining);
      final Piece piece = board.getPiece(square);
      count = switch (piece.getPieceType()) {
        case PAWN -> addPawnMoves(board, (Pawn) piece, own, occupied, quiescence, moves, count);
        case KNIGHT -> addSteps(KNIGHT_TARGETS[square], square, own, occupied, quiescence, moves,
                count);
        case BISHOP -> addSlides(BISHOP_RAYS[square], square, own, occupied, quiescence, moves,
                count);
        case ROOK -> addSlides(ROOK_RAYS[square], square, own, occupied, quiescence, moves, count);
        case QUEEN -> addSlides(QUEEN_RAYS[square], square, own, occupied, quiescence, moves,
                count);
        case KING -> addSteps(KING_TARGETS[square], square, own, occupied, quiescence, moves,
                count);
      };
    }
    return quiescence ? count : addCastles(board, side, moves, count);
  }

  /**
   * Appends the moves of a knight or a king on the given square.
   *
   * @param targets The squares the piece reaches, in generation order.
   * @param origin The square the piece stands on.
   * @param own The squares of the moving side's pieces.
   * @param occupied The occupied squares.
   * @param capturesOnly Whether to leave out the moves that capture nothing.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addSteps(final int[] targets, final int origin, final long own,
                              final long occupied, final boolean capturesOnly, final int[] moves,
                              int count) {
    for (final int target : targets) {
      final long bit = 1L << target;
      if ((occupied & bit) == 0L) {
        if (!capturesOnly) {
          moves[count++] = MoveCode.of(origin, target, null, 0);
        }
      } else if ((own & bit) == 0L) {
        moves[count++] = MoveCode.of(origin, target, null, MoveCode.CAPTURE);
      }
    }
    return count;
  }

  /**
   * Appends the moves of a bishop, a rook or a queen on the given square.
   *
   * @param rays The rays the piece slides along, in generation order.
   * @param origin The square the piece stands on.
   * @param own The squares of the moving side's pieces.
   * @param occupied The occupied squares.
   * @param capturesOnly Whether to leave out the moves that capture nothing.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addSlides(final int[][] rays, final int origin, final long own,
                               final long occupied, final boolean capturesOnly, final int[] moves,
                               int count) {
    for (final int[] ray : rays) {
      for (final int target : ray) {
        final long bit = 1L << target;
        if ((occupied & bit) == 0L) {
          if (!capturesOnly) {
            moves[count++] = MoveCode.of(origin, target, null, 0);
          }
          continue;
        }
        if ((own & bit) == 0L) {
          moves[count++] = MoveCode.of(origin, target, null, MoveCode.CAPTURE);
        }
        break;
      }
    }
    return count;
  }

  /**
   * Appends the moves of the given pawn: its single advance, its two-square advance, and its two
   * diagonal captures, each promoting where it reaches the last rank, and its en passant capture.
   *
   * @param board The board to generate on.
   * @param pawn The pawn.
   * @param own The squares of the moving side's pieces.
   * @param occupied The occupied squares.
   * @param quiescence Whether to write only the captures and the quiet promotions to a queen.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addPawnMoves(final Board board, final Pawn pawn, final long own,
                                  final long occupied, final boolean quiescence, final int[] moves,
                                  int count) {
    final Alliance alliance = pawn.getPieceAllegiance();
    final boolean white = alliance.isWhite();
    final int origin = pawn.getPiecePosition();
    final int direction = alliance.getDirection();
    final int file = origin % BoardUtils.NUM_TILES_PER_ROW;
    final int rank = origin / BoardUtils.NUM_TILES_PER_ROW;

    final int advance = origin + direction * 8;
    if (BoardUtils.isValidTileCoordinate(advance) && (occupied & (1L << advance)) == 0L) {
      if (!quiescence) {
        count = addPawnMove(alliance, origin, advance, 0, moves, count);
      } else if (alliance.isPawnPromotionSquare(advance)) {
        moves[count++] = MoveCode.of(origin, advance, Piece.PieceType.QUEEN, 0);
      }
    }

    final int jump = origin + direction * 16;
    if (!quiescence && BoardUtils.isValidTileCoordinate(jump) && pawn.isFirstMove() &&
            ((rank == 1 && !white) || (rank == 6 && white)) &&
            (occupied & (1L << jump)) == 0L && (occupied & (1L << advance)) == 0L) {
      moves[count++] = MoveCode.of(origin, jump, null, MoveCode.PAWN_JUMP);
    }

    final int sevenCapture = origin + direction * 7;
    if (BoardUtils.isValidTileCoordinate(sevenCapture) &&
            !((file == 7 && white) || (file == 0 && !white))) {
      count = addPawnCapture(board, alliance, origin, sevenCapture,
              origin + alliance.getOppositeDirection(), own, occupied, moves, count);
    }

    final int nineCapture = origin + direction * 9;
    if (BoardUtils.isValidTileCoordinate(nineCapture) &&
            !((file == 0 && white) || (file == 7 && !white))) {
      count = addPawnCapture(board, alliance, origin, nineCapture,
              origin - alliance.getOppositeDirection(), own, occupied, moves, count);
    }
    return count;
  }

  /**
   * Appends a pawn's diagonal capture onto the given square, or its en passant capture when that
   * square is empty and the en passant pawn stands on the given square beside it.
   *
   * @param board The board to generate on.
   * @param alliance The pawn's alliance.
   * @param origin The pawn's square.
   * @param target The square the pawn captures on.
   * @param enPassantSquare The square an en passant pawn must stand on for this capture.
   * @param own The squares of the moving side's pieces.
   * @param occupied The occupied squares.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addPawnCapture(final Board board, final Alliance alliance, final int origin,
                                    final int target, final int enPassantSquare, final long own,
                                    final long occupied, final int[] moves, int count) {
    final long bit = 1L << target;
    if ((occupied & bit) != 0L) {
      if ((own & bit) == 0L) {
        count = addPawnMove(alliance, origin, target, MoveCode.CAPTURE, moves, count);
      }
      return count;
    }
    final Pawn enPassantPawn = board.getEnPassantPawn();
    if (enPassantPawn != null && enPassantPawn.getPiecePosition() == enPassantSquare &&
            enPassantPawn.getPieceAllegiance() != alliance) {
      moves[count++] = MoveCode.of(origin, target, null, MoveCode.CAPTURE | MoveCode.EN_PASSANT);
    }
    return count;
  }

  /**
   * Appends a pawn move onto the given square, as the four promotions when the square is on the
   * pawn's last rank.
   *
   * @param alliance The pawn's alliance.
   * @param origin The pawn's square.
   * @param target The square the pawn moves to.
   * @param flags The flags of the move.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addPawnMove(final Alliance alliance, final int origin, final int target,
                                 final int flags, final int[] moves, int count) {
    if (alliance.isPawnPromotionSquare(target)) {
      for (final Piece.PieceType promotionType : PROMOTION_TYPES) {
        moves[count++] = MoveCode.of(origin, target, promotionType, flags);
      }
    } else {
      moves[count++] = MoveCode.of(origin, target, null, flags);
    }
    return count;
  }

  /**
   * Appends the given side's castling moves, king side first, under the conditions the players
   * apply: the king has not castled, keeps a castling right on either side, is not in check, has
   * not moved and stands on its home square, and for each side the squares between king and rook
   * are empty, the piece on the rook's home square is an unmoved rook, and the two squares the
   * king's path covers next to the rook are not attacked.
   *
   * @param board The board to generate on.
   * @param side The side whose castling moves are appended.
   * @param moves The array to append to.
   * @param count The number of moves already in the array.
   * @return The number of moves in the array afterwards.
   */
  private static int addCastles(final Board board, final Alliance side, final int[] moves,
                                int count) {
    final King king = board.getKing(side);
    if (king.isCastled() ||
            !(king.isKingSideCastleCapable() || king.isQueenSideCastleCapable())) {
      return count;
    }
    final Alliance opponent = side.isWhite() ? Alliance.BLACK : Alliance.WHITE;
    final int home = side.isWhite() ? 60 : 4;
    if (AttackDetector.isSquareAttacked(king.getPiecePosition(), opponent, board) ||
            !king.isFirstMove() || king.getPiecePosition() != home) {
      return count;
    }
    if (board.getPiece(home + 1) == null && board.getPiece(home + 2) == null &&
            isUnmovedRook(board.getPiece(home + 3)) &&
            !AttackDetector.isSquareAttacked(home + 1, opponent, board) &&
            !AttackDetector.isSquareAttacked(home + 2, opponent, board)) {
      moves[count++] = MoveCode.of(home, home + 2, null, MoveCode.CASTLE);
    }
    if (board.getPiece(home - 1) == null && board.getPiece(home - 2) == null &&
            board.getPiece(home - 3) == null && isUnmovedRook(board.getPiece(home - 4)) &&
            !AttackDetector.isSquareAttacked(home - 2, opponent, board) &&
            !AttackDetector.isSquareAttacked(home - 1, opponent, board)) {
      moves[count++] = MoveCode.of(home, home - 2, null, MoveCode.CASTLE);
    }
    return count;
  }

  /**
   * Determines whether the given piece is a rook that has not moved, of either alliance.
   *
   * @param piece The piece on a rook's home square, or null.
   * @return True if the piece is an unmoved rook, false otherwise.
   */
  private static boolean isUnmovedRook(final Piece piece) {
    return piece != null && piece.isFirstMove() && piece.getPieceType() == Piece.PieceType.ROOK;
  }

  /**
   * Computes, for every square, the squares a stepping piece reaches with the given steps,
   * leaving out steps that leave the board.
   *
   * @param steps The rank and file steps, in generation order.
   * @return The squares reached, indexed by square, in generation order.
   */
  private static int[][] computeSteps(final int[][] steps) {
    final int[][] targets = new int[BoardUtils.NUM_TILES][];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final int[] reached = new int[steps.length];
      int count = 0;
      for (final int[] step : steps) {
        final int rank = square / 8 + step[0];
        final int file = square % 8 + step[1];
        if (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
          reached[count++] = rank * 8 + file;
        }
      }
      targets[square] = Arrays.copyOf(reached, count);
    }
    return targets;
  }

  /**
   * Computes, for every square, the rays a sliding piece follows with the given steps, each ray
   * running outward to the edge of the board.
   *
   * @param steps The rank and file steps, in generation order.
   * @return The rays, indexed by square and then by step, each listing its squares outward.
   */
  private static int[][][] computeRays(final int[][] steps) {
    final int[][][] rays = new int[BoardUtils.NUM_TILES][steps.length][];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      for (int index = 0; index < steps.length; index++) {
        final int[] ray = new int[7];
        int length = 0;
        int rank = square / 8 + steps[index][0];
        int file = square % 8 + steps[index][1];
        while (rank >= 0 && rank < 8 && file >= 0 && file < 8) {
          ray[length++] = rank * 8 + file;
          rank += steps[index][0];
          file += steps[index][1];
        }
        rays[square][index] = Arrays.copyOf(ray, length);
      }
    }
    return rays;
  }
}
