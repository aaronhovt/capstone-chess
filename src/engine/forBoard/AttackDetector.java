package engine.forBoard;

import engine.Alliance;
import engine.forPiece.Piece;

import java.util.Arrays;

/**
 * AttackDetector answers whether a given square is attacked by a given side. It scans outward from
 * the square in question and reads what stands on the squares it reaches, rather than asking every
 * one of the attacking side's pieces whether it bears on that square.
 * <p>
 * The two formulations agree exactly. A sliding piece attacks a square when it stands on one of the
 * rays out of that square with nothing in between, which is what a scan outward from the square
 * finds at the first occupied square along each ray. A knight or a king attacks a square when it
 * stands on a square the same step pattern reaches from there, the pattern being its own mirror
 * image. A pawn is the one piece whose pattern is not its own mirror image, so the squares a pawn
 * of each alliance attacks from are tabulated separately per alliance.
 * <p>
 * Whatever occupies the square being tested is disregarded, except that a square held by a piece of
 * the attacking side is reported as unattacked, matching {@link Piece#attacksSquare(int, Board)}.
 *
 * @author Aaron Ho
 */
public final class AttackDetector {

  /** The number of files on the board, which is also the number of ranks. */
  private static final int BOARD_SIZE = BoardUtils.NUM_TILES_PER_ROW;

  /** The greatest number of squares a ray can hold, being a full rank or file less its origin. */
  private static final int LONGEST_RAY = BOARD_SIZE - 1;

  /** The number of squares a pawn of one alliance can attack a given square from. */
  private static final int PAWN_ATTACKER_COUNT = 2;

  /** The per step file displacements of the rays a rook or a queen travels along. */
  private static final int[] ORTHOGONAL_FILE_STEPS = { 0, -1, 1, 0 };

  /** The per step rank displacements of the rays a rook or a queen travels along. */
  private static final int[] ORTHOGONAL_RANK_STEPS = { -1, 0, 0, 1 };

  /** The per step file displacements of the rays a bishop or a queen travels along. */
  private static final int[] DIAGONAL_FILE_STEPS = { -1, 1, -1, 1 };

  /** The per step rank displacements of the rays a bishop or a queen travels along. */
  private static final int[] DIAGONAL_RANK_STEPS = { -1, -1, 1, 1 };

  /** The file displacements of the squares a knight reaches, paired with {@link #KNIGHT_RANK_STEPS}. */
  private static final int[] KNIGHT_FILE_STEPS = { -1, 1, -1, 1, -2, 2, -2, 2 };

  /** The rank displacements of the squares a knight reaches, paired with {@link #KNIGHT_FILE_STEPS}. */
  private static final int[] KNIGHT_RANK_STEPS = { -2, -2, 2, 2, -1, -1, 1, 1 };

  /** The file displacements of the squares a king reaches, paired with {@link #KING_RANK_STEPS}. */
  private static final int[] KING_FILE_STEPS = { -1, 0, 1, -1, 1, -1, 0, 1 };

  /** The rank displacements of the squares a king reaches, paired with {@link #KING_FILE_STEPS}. */
  private static final int[] KING_RANK_STEPS = { -1, -1, -1, 0, 0, 1, 1, 1 };

  /**
   * The rays out of each square along which a rook or a queen would stand to attack it, indexed by
   * square and then by direction, each ray listing its squares in increasing distance from the
   * origin.
   */
  private static final int[][][] ORTHOGONAL_RAYS =
          computeRays(ORTHOGONAL_FILE_STEPS, ORTHOGONAL_RANK_STEPS);

  /**
   * The rays out of each square along which a bishop or a queen would stand to attack it, indexed
   * by square and then by direction, each ray listing its squares in increasing distance from the
   * origin.
   */
  private static final int[][][] DIAGONAL_RAYS =
          computeRays(DIAGONAL_FILE_STEPS, DIAGONAL_RANK_STEPS);

  /** The squares from which a knight attacks each square, indexed by square. */
  private static final int[][] KNIGHT_ATTACKERS =
          computeStepAttackers(KNIGHT_FILE_STEPS, KNIGHT_RANK_STEPS);

  /** The squares from which a king attacks each square, indexed by square. */
  private static final int[][] KING_ATTACKERS =
          computeStepAttackers(KING_FILE_STEPS, KING_RANK_STEPS);

  /** The squares from which a white pawn attacks each square, indexed by square. */
  private static final int[][] WHITE_PAWN_ATTACKERS = computePawnAttackers(Alliance.WHITE);

  /** The squares from which a black pawn attacks each square, indexed by square. */
  private static final int[][] BLACK_PAWN_ATTACKERS = computePawnAttackers(Alliance.BLACK);

  /** Marks a square that a move does not use. */
  private static final int NO_SQUARE = -1;

  /** The squares a piece with no step attack attacks from. */
  private static final int[] NO_SQUARES = {};

  /** Prevents instantiation, since this class holds only tables and static queries against them. */
  private AttackDetector() {
  }

  /**
   * Determines whether the given side attacks the given square on the given board. Whatever
   * occupies the square is disregarded, except that a square held by a piece of the attacking side
   * is reported as unattacked.
   *
   * @param square The square to test.
   * @param attackerAlliance The side whose pieces are the candidate attackers.
   * @param board The current board.
   * @return True if the given side attacks the square, false otherwise.
   */
  public static boolean isSquareAttacked(final int square, final Alliance attackerAlliance,
                                         final Board board) {
    final Piece occupant = board.getPiece(square);
    if (occupant != null && occupant.getPieceAllegiance() == attackerAlliance) {
      return false;
    }
    final int[] pawnAttackers = attackerAlliance.isWhite() ? WHITE_PAWN_ATTACKERS[square]
            : BLACK_PAWN_ATTACKERS[square];
    if (stepAttackerStandsOn(pawnAttackers, Piece.PieceType.PAWN, attackerAlliance, board) ||
            stepAttackerStandsOn(KNIGHT_ATTACKERS[square], Piece.PieceType.KNIGHT, attackerAlliance, board) ||
            stepAttackerStandsOn(KING_ATTACKERS[square], Piece.PieceType.KING, attackerAlliance, board)) {
      return true;
    }
    return sliderBearsAlong(DIAGONAL_RAYS[square], Piece.PieceType.BISHOP, attackerAlliance, board) ||
            sliderBearsAlong(ORTHOGONAL_RAYS[square], Piece.PieceType.ROOK, attackerAlliance, board);
  }

  /**
   * Determines whether the given move would leave the opponent of the side making it in check,
   * without making the move. The answer is the one making the move and testing the side then to
   * move for check would give, provided that side is not in check before the move, which holds in
   * any position reached by legal play. Whether the move is legal is not considered.
   *
   * @param board The board the move was generated from, in the position before the move.
   * @param move The move to test.
   * @return True if the move would give check, false otherwise.
   */
  public static boolean givesCheck(final Board board, final Move move) {
    final Alliance mover = move.getMovedPiece().getPieceAllegiance();
    final int kingSquare =
            board.getKing(mover.isWhite() ? Alliance.BLACK : Alliance.WHITE).getPiecePosition();
    final int origin = move.getCurrentCoordinate();
    final int destination = move.getDestinationCoordinate();
    final Piece.PieceType arrivingType = move instanceof Move.PawnPromotion promotion ?
            promotion.promotionPiece.getPieceType() : move.getMovedPiece().getPieceType();

    int rookOrigin = NO_SQUARE;
    int rookDestination = NO_SQUARE;
    int capturedPawnSquare = NO_SQUARE;
    if (move instanceof Move.CastleMove castle) {
      rookOrigin = castle.castleRookStart;
      rookDestination = castle.castleRookDestination;
    } else if (move instanceof Move.PawnEnPassantAttack) {
      capturedPawnSquare = move.getAttackedPiece().getPiecePosition();
    }

    final int[] stepOrigins = switch (arrivingType) {
      case PAWN -> mover.isWhite() ? WHITE_PAWN_ATTACKERS[kingSquare]
              : BLACK_PAWN_ATTACKERS[kingSquare];
      case KNIGHT -> KNIGHT_ATTACKERS[kingSquare];
      case KING -> KING_ATTACKERS[kingSquare];
      default -> NO_SQUARES;
    };
    for (final int stepOrigin : stepOrigins) {
      if (stepOrigin == destination) {
        return true;
      }
    }

    return sliderBearsAfterMove(DIAGONAL_RAYS[kingSquare], Piece.PieceType.BISHOP, mover,
            board, origin, destination, arrivingType, rookOrigin, rookDestination,
            capturedPawnSquare) ||
            sliderBearsAfterMove(ORTHOGONAL_RAYS[kingSquare], Piece.PieceType.ROOK, mover,
                    board, origin, destination, arrivingType, rookOrigin, rookDestination,
                    capturedPawnSquare);
  }

  /**
   * Determines whether a queen, or a slider of the given type, of the given alliance would stand at
   * the near end of any of the given rays with nothing in between once a move is made. The move is
   * described by the squares it empties and fills; a square it does not use is given as
   * {@link #NO_SQUARE}.
   *
   * @param rays The rays out of the square being tested.
   * @param sliderType The slider other than the queen that travels along these rays.
   * @param mover The alliance making the move, being the alliance of the piece looked for.
   * @param board The board in the position before the move.
   * @param origin The square the moving piece leaves.
   * @param destination The square the moving piece arrives on.
   * @param arrivingType The type of the piece arriving on the destination square.
   * @param rookOrigin The square a castling rook leaves.
   * @param rookDestination The square a castling rook arrives on.
   * @param capturedPawnSquare The square of a pawn captured en passant.
   * @return True if such a piece would bear along one of the rays, false otherwise.
   */
  private static boolean sliderBearsAfterMove(final int[][] rays,
                                              final Piece.PieceType sliderType,
                                              final Alliance mover, final Board board,
                                              final int origin, final int destination,
                                              final Piece.PieceType arrivingType,
                                              final int rookOrigin, final int rookDestination,
                                              final int capturedPawnSquare) {
    for (final int[] ray : rays) {
      for (final int candidate : ray) {
        final Piece.PieceType type;
        final Alliance alliance;
        if (candidate == destination) {
          type = arrivingType;
          alliance = mover;
        } else if (candidate == rookDestination) {
          type = Piece.PieceType.ROOK;
          alliance = mover;
        } else if (candidate == origin || candidate == rookOrigin ||
                candidate == capturedPawnSquare) {
          continue;
        } else {
          final Piece piece = board.getPiece(candidate);
          if (piece == null) {
            continue;
          }
          type = piece.getPieceType();
          alliance = piece.getPieceAllegiance();
        }
        if (alliance == mover && (type == sliderType || type == Piece.PieceType.QUEEN)) {
          return true;
        }
        break;
      }
    }
    return false;
  }

  /**
   * Determines whether a piece of the given type and alliance stands on any of the given squares.
   *
   * @param origins The squares an attacker of this kind would stand on.
   * @param attackerType The type of piece being looked for.
   * @param attackerAlliance The alliance of the piece being looked for.
   * @param board The current board.
   * @return True if such a piece stands on one of the squares, false otherwise.
   */
  private static boolean stepAttackerStandsOn(final int[] origins,
                                              final Piece.PieceType attackerType,
                                              final Alliance attackerAlliance, final Board board) {
    for (final int origin : origins) {
      final Piece piece = board.getPiece(origin);
      if (piece != null && piece.getPieceType() == attackerType &&
              piece.getPieceAllegiance() == attackerAlliance) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines whether a queen, or a slider of the given type, of the given alliance stands at the
   * near end of any of the given rays with nothing in between. Each ray is walked outward only as
   * far as its first occupied square, since anything past that square is blocked by it.
   *
   * @param rays The rays out of the square being tested.
   * @param sliderType The slider other than the queen that travels along these rays.
   * @param attackerAlliance The alliance of the piece being looked for.
   * @param board The current board.
   * @return True if such a piece bears along one of the rays, false otherwise.
   */
  private static boolean sliderBearsAlong(final int[][] rays, final Piece.PieceType sliderType,
                                          final Alliance attackerAlliance, final Board board) {
    for (final int[] ray : rays) {
      for (final int candidate : ray) {
        final Piece piece = board.getPiece(candidate);
        if (piece == null) {
          continue;
        }
        if (piece.getPieceAllegiance() == attackerAlliance) {
          final Piece.PieceType type = piece.getPieceType();
          if (type == sliderType || type == Piece.PieceType.QUEEN) {
            return true;
          }
        }
        break;
      }
    }
    return false;
  }

  /**
   * Computes, for every square, the rays leading out of it in each of the given directions.
   *
   * @param fileSteps The per step file displacement of each direction.
   * @param rankSteps The per step rank displacement of each direction, paired with fileSteps.
   * @return The rays, indexed by square and then by direction.
   */
  private static int[][][] computeRays(final int[] fileSteps, final int[] rankSteps) {
    final int[][][] rays = new int[BoardUtils.NUM_TILES][fileSteps.length][];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      for (int direction = 0; direction < fileSteps.length; direction++) {
        rays[square][direction] = computeRay(square, fileSteps[direction], rankSteps[direction]);
      }
    }
    return rays;
  }

  /**
   * Computes the ray leading out of the given square in the given direction, stopping at the edge
   * of the board.
   *
   * @param square The square the ray leads out of, which is not itself part of the ray.
   * @param fileStep The per step file displacement of the direction.
   * @param rankStep The per step rank displacement of the direction.
   * @return The squares of the ray, in increasing distance from the origin.
   */
  private static int[] computeRay(final int square, final int fileStep, final int rankStep) {
    final int[] ray = new int[LONGEST_RAY];
    int length = 0;
    int file = square % BOARD_SIZE;
    int rank = square / BOARD_SIZE;
    while (true) {
      file += fileStep;
      rank += rankStep;
      if (file < 0 || file >= BOARD_SIZE || rank < 0 || rank >= BOARD_SIZE) {
        break;
      }
      ray[length++] = rank * BOARD_SIZE + file;
    }
    return Arrays.copyOf(ray, length);
  }

  /**
   * Computes, for every square, the squares a stepping piece with the given pattern attacks it
   * from. The knight pattern and the king pattern are each their own mirror image, so the squares
   * such a piece attacks a square from are the same squares that pattern reaches from there.
   *
   * @param fileSteps The file displacement of each step in the pattern.
   * @param rankSteps The rank displacement of each step in the pattern, paired with fileSteps.
   * @return The attacking squares, indexed by square.
   */
  private static int[][] computeStepAttackers(final int[] fileSteps, final int[] rankSteps) {
    final int[][] attackers = new int[BoardUtils.NUM_TILES][];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final int file = square % BOARD_SIZE;
      final int rank = square / BOARD_SIZE;
      final int[] origins = new int[fileSteps.length];
      int count = 0;
      for (int step = 0; step < fileSteps.length; step++) {
        final int originFile = file + fileSteps[step];
        final int originRank = rank + rankSteps[step];
        if (originFile >= 0 && originFile < BOARD_SIZE &&
                originRank >= 0 && originRank < BOARD_SIZE) {
          origins[count++] = originRank * BOARD_SIZE + originFile;
        }
      }
      attackers[square] = Arrays.copyOf(origins, count);
    }
    return attackers;
  }

  /**
   * Computes, for every square, the squares a pawn of the given alliance attacks it from, being the
   * two squares one rank back along that alliance's direction of travel and one file to either
   * side.
   *
   * @param alliance The alliance of the attacking pawns.
   * @return The attacking squares, indexed by square.
   */
  private static int[][] computePawnAttackers(final Alliance alliance) {
    final int[][] attackers = new int[BoardUtils.NUM_TILES][];
    for (int square = 0; square < BoardUtils.NUM_TILES; square++) {
      final int file = square % BOARD_SIZE;
      final int originRank = (square / BOARD_SIZE) - alliance.getDirection();
      final int[] origins = new int[PAWN_ATTACKER_COUNT];
      int count = 0;
      if (originRank >= 0 && originRank < BOARD_SIZE) {
        if (file > 0) {
          origins[count++] = originRank * BOARD_SIZE + file - 1;
        }
        if (file < BOARD_SIZE - 1) {
          origins[count++] = originRank * BOARD_SIZE + file + 1;
        }
      }
      attackers[square] = Arrays.copyOf(origins, count);
    }
    return attackers;
  }
}
