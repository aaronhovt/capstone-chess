package engine.forTesting;

import engine.Alliance;
import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forPiece.Bishop;
import engine.forPiece.King;
import engine.forPiece.Knight;
import engine.forPiece.Pawn;
import engine.forPiece.Queen;
import engine.forPiece.Rook;

import java.util.HashMap;
import java.util.Map;

/**
 * The FenParser class converts Forsyth-Edwards Notation strings into Board instances.
 * It exists so that arbitrary test positions can be loaded without playing a game up to them,
 * which is a prerequisite for move generation testing against published node counts.
 * The parser reads the piece placement, side to move, castling rights, en passant, halfmove
 * clock, and fullmove number fields. The two counter fields are optional, since a position may
 * be given without them, and default to a freshly started game when absent.
 * <p>
 * Castling rights and pawn double-step rights are expressed in this engine through the first
 * move status of the pieces involved rather than through a dedicated rights field, so the parser
 * translates the FEN castling field into the first move status of each king and rook, and marks
 * a pawn as unmoved only when it stands on its home rank. This class is designed as a
 * non-instantiable utility class with static methods.
 *
 * @author Aaron Ho
 */
public class FenParser {

  /** The Forsyth-Edwards Notation string describing the standard chess starting position. */
  public static final String STARTING_POSITION_FEN =
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

  /** The number of ranks described by the piece placement field of a Forsyth-Edwards Notation string. */
  private static final int NUM_RANKS = 8;

  /** The number of files described by each rank of a Forsyth-Edwards Notation string. */
  private static final int NUM_FILES = 8;

  /** The minimum number of whitespace separated fields a usable Forsyth-Edwards Notation string must contain. */
  private static final int MINIMUM_FIELD_COUNT = 4;

  /** The board coordinate of the square on which the white king side rook begins the game (h1). */
  private static final int WHITE_KING_SIDE_ROOK_TILE = 63;

  /** The board coordinate of the square on which the white queen side rook begins the game (a1). */
  private static final int WHITE_QUEEN_SIDE_ROOK_TILE = 56;

  /** The board coordinate of the square on which the black king side rook begins the game (h8). */
  private static final int BLACK_KING_SIDE_ROOK_TILE = 7;

  /** The board coordinate of the square on which the black queen side rook begins the game (a8). */
  private static final int BLACK_QUEEN_SIDE_ROOK_TILE = 0;

  /** The index of the halfmove clock field within a Forsyth-Edwards Notation string. */
  private static final int HALF_MOVE_CLOCK_FIELD = 4;

  /** The index of the fullmove number field within a Forsyth-Edwards Notation string. */
  private static final int FULL_MOVE_NUMBER_FIELD = 5;

  /** The number of plies in one full move, used to convert a fullmove number into a ply count. */
  private static final int PLIES_PER_FULL_MOVE = 2;

  /** The board coordinate of the first square of the white back rank (a1). */
  private static final int WHITE_BACK_RANK_ORIGIN = 56;

  /** The board coordinate of the first square of the black back rank (a8). */
  private static final int BLACK_BACK_RANK_ORIGIN = 0;

  /** The file on which each side's queen side knight begins the game (b). */
  private static final int QUEEN_SIDE_KNIGHT_FILE = 1;

  /** The file on which each side's king side knight begins the game (g). */
  private static final int KING_SIDE_KNIGHT_FILE = 6;

  /** The file on which each side's queen side bishop begins the game (c). */
  private static final int QUEEN_SIDE_BISHOP_FILE = 2;

  /** The file on which each side's king side bishop begins the game (f). */
  private static final int KING_SIDE_BISHOP_FILE = 5;

  /** The file on which each side's queen begins the game (d). */
  private static final int QUEEN_FILE = 3;

  /** The file on which each side's king stands after castling king side (g). */
  private static final int KING_SIDE_CASTLED_KING_FILE = 6;

  /** The file on which each side's king stands after castling queen side (c). */
  private static final int QUEEN_SIDE_CASTLED_KING_FILE = 2;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws RuntimeException Always thrown to prevent instantiation.
   */
  private FenParser() {
    throw new RuntimeException("Not instantiatable!");
  }

  /**
   * The halfmove clock and fullmove number fields are optional. When present, the halfmove clock
   * is carried into the board for the fifty-move rule and the fullmove number is converted into
   * a ply count. When absent, both default to zero.
   *
   * @param fen The Forsyth-Edwards Notation string to parse.
   * @return The board described by the given notation string.
   * @throws IllegalArgumentException If the notation string is malformed or describes an impossible position.
   */
  public static Board parse(final String fen) {
    final String[] fields = fen.trim().split("\\s+");
    if (fields.length < MINIMUM_FIELD_COUNT) {
      throw new IllegalArgumentException("A FEN string requires at least four fields, found " +
              fields.length + ": " + fen);
    }
    final Alliance nextMoveMaker = parseMoveMaker(fields[1]);
    final Board.Builder builder = new Board.Builder();
    final Map<Integer, Pawn> placedPawns = new HashMap<>();
    parsePiecePlacement(fields[0], fields[2], builder, placedPawns);
    if (!"-".equals(fields[3])) {
      builder.setEnPassantPawn(resolveEnPassantPawn(fields[3], nextMoveMaker, placedPawns));
    }
    builder.setMoveMaker(nextMoveMaker);
    if (fields.length > HALF_MOVE_CLOCK_FIELD) {
      builder.setHalfMoveClock(parseCounter(fields[HALF_MOVE_CLOCK_FIELD], 0, "halfmove clock"));
    } if (fields.length > FULL_MOVE_NUMBER_FIELD) {
      final int fullMoveNumber = parseCounter(fields[FULL_MOVE_NUMBER_FIELD], 1, "fullmove number");
      builder.setPlyCount(((fullMoveNumber - 1) * PLIES_PER_FULL_MOVE) +
              (nextMoveMaker.isWhite() ? 0 : 1));
    } return builder.build();
  }

  /**
   * Parses the piece placement field of a Forsyth-Edwards Notation string and adds every piece
   * it describes to the given builder. Ranks are listed from the eighth rank to the first,
   * which matches the coordinate ordering used by the engine, where coordinate zero is a8.
   *
   * @param placement The piece placement field of the notation string.
   * @param castlingRights The castling rights field of the notation string.
   * @param builder The builder collecting the pieces described by the notation string.
   * @param placedPawns A map recording every placed pawn by coordinate for later en passant resolution.
   * @throws IllegalArgumentException If a rank is missing, overfull, or contains an unrecognised symbol.
   */
  private static void parsePiecePlacement(final String placement,
                                          final String castlingRights,
                                          final Board.Builder builder,
                                          final Map<Integer, Pawn> placedPawns) {
    final String[] ranks = placement.split("/");
    if (ranks.length != NUM_RANKS) {
      throw new IllegalArgumentException("A FEN piece placement field requires eight ranks, found " +
              ranks.length + ": " + placement);
    }
    for (int rank = 0; rank < NUM_RANKS; rank++) {
      int file = 0;
      for (final char symbol : ranks[rank].toCharArray()) {
        if (Character.isDigit(symbol)) {
          file += Character.getNumericValue(symbol);
          continue;
        }
        if (file >= NUM_FILES) {
          throw new IllegalArgumentException("Rank " + (NUM_RANKS - rank) +
                  " describes more than eight files: " + ranks[rank]);
        }
        placePiece(symbol, (rank * NUM_FILES) + file, castlingRights, builder, placedPawns);
        file++;
      }
      if (file != NUM_FILES) {
        throw new IllegalArgumentException("Rank " + (NUM_RANKS - rank) +
                " describes " + file + " files rather than eight: " + ranks[rank]);
      }
    }
  }

  /**
   * Creates the piece denoted by a single placement symbol and adds it to the given builder.
   * Uppercase symbols denote white pieces and lowercase symbols denote black pieces. Rooks and
   * kings are created with the first move status implied by the castling rights field, and pawns
   * are created with the first move status implied by the rank they stand on.
   *
   * @param symbol The placement symbol describing the piece.
   * @param coordinate The board coordinate the piece occupies.
   * @param castlingRights The castling rights field of the notation string.
   * @param builder The builder collecting the pieces described by the notation string.
   * @param placedPawns A map recording every placed pawn by coordinate for later en passant resolution.
   * @throws IllegalArgumentException If the placement symbol does not denote a chess piece.
   */
  private static void placePiece(final char symbol,
                                 final int coordinate,
                                 final String castlingRights,
                                 final Board.Builder builder,
                                 final Map<Integer, Pawn> placedPawns) {
    final Alliance alliance = Character.isUpperCase(symbol) ? Alliance.WHITE : Alliance.BLACK;
    switch (Character.toLowerCase(symbol)) {
      case 'p' -> {
        final boolean isUnmoved = isOnPawnHomeRank(alliance, coordinate);
        final Pawn pawn = new Pawn(alliance, coordinate, isUnmoved, isUnmoved ? 0 : 1);
        placedPawns.put(coordinate, pawn);
        builder.setPiece(pawn);
      } case 'n' -> {
        final boolean isUnmoved = isOnHomeSquare(alliance, coordinate, 'n');
        builder.setPiece(new Knight(alliance, coordinate, isUnmoved, isUnmoved ? 0 : 1));
      } case 'b' -> {
        final boolean isUnmoved = isOnHomeSquare(alliance, coordinate, 'b');
        builder.setPiece(new Bishop(alliance, coordinate, isUnmoved, isUnmoved ? 0 : 1));
      } case 'r' -> {
        final boolean retainsRight = retainsRookCastlingRight(alliance, coordinate, castlingRights);
        builder.setPiece(new Rook(alliance, coordinate, retainsRight, retainsRight ? 0 : 1));
      } case 'q' -> {
        final boolean isUnmoved = isOnHomeSquare(alliance, coordinate, 'q');
        builder.setPiece(new Queen(alliance, coordinate, isUnmoved, isUnmoved ? 0 : 1));
      }       case 'k' -> {
        final boolean kingSide = castlingRights.indexOf(alliance.isWhite() ? 'K' : 'k') >= 0;
        final boolean queenSide = castlingRights.indexOf(alliance.isWhite() ? 'Q' : 'q') >= 0;
        final boolean retainsRight = kingSide || queenSide;
        builder.setPiece(new King(alliance, coordinate, retainsRight,
                !retainsRight && isOnCastledSquare(alliance, coordinate), kingSide, queenSide));
      } default -> throw new IllegalArgumentException("Unrecognised piece symbol in FEN: " + symbol);
    }
  }

  /**
   * Determines whether a pawn of the given alliance standing on the given coordinate is still
   * on its home rank. The engine permits a two square advance only for a pawn whose first move
   * status is set and which stands on its home rank, so a pawn placed anywhere else is created
   * as having already moved.
   *
   * @param alliance The alliance of the pawn being placed.
   * @param coordinate The board coordinate the pawn occupies.
   * @return True if the pawn stands on its home rank, false otherwise.
   */
  private static boolean isOnPawnHomeRank(final Alliance alliance, final int coordinate) {
    return BoardUtils.rowOf(coordinate) == (alliance.isWhite() ? 6 : 1);
  }

  /**
   * Determines whether a knight, bishop, or queen of the given alliance stands on a square it
   * begins the game on. Forsyth-Edwards Notation does not record whether these pieces have moved,
   * so their first move status is inferred from the square they occupy.
   *
   * @param alliance The alliance of the piece being placed.
   * @param coordinate The board coordinate the piece occupies.
   * @param symbol The lowercase placement symbol describing the piece.
   * @return True if the piece stands on one of its starting squares, false otherwise.
   */
  private static boolean isOnHomeSquare(final Alliance alliance, final int coordinate, final char symbol) {
    final int file = coordinate - (alliance.isWhite() ? WHITE_BACK_RANK_ORIGIN : BLACK_BACK_RANK_ORIGIN);
    if (file < 0 || file >= NUM_FILES) {
      return false;
    }
    return switch (symbol) {
      case 'n' -> file == QUEEN_SIDE_KNIGHT_FILE || file == KING_SIDE_KNIGHT_FILE;
      case 'b' -> file == QUEEN_SIDE_BISHOP_FILE || file == KING_SIDE_BISHOP_FILE;
      case 'q' -> file == QUEEN_FILE;
      default -> false;
    };
  }

  /**
   * Determines whether a king of the given alliance stands on a square that a castling move would
   * have placed it on. Forsyth-Edwards Notation does not record whether a king has castled, so a
   * king that has forfeited both castling rights and stands on such a square is taken to have
   * castled. Only a king on its own home rank can satisfy this, since the file arithmetic falls
   * outside the range of a rank for any other coordinate.
   *
   * @param alliance The alliance of the king being placed.
   * @param coordinate The board coordinate the king occupies.
   * @return True if the king stands on a castled king square, false otherwise.
   */
  private static boolean isOnCastledSquare(final Alliance alliance, final int coordinate) {
    final int file = coordinate - (alliance.isWhite() ? WHITE_BACK_RANK_ORIGIN : BLACK_BACK_RANK_ORIGIN);
    return file == KING_SIDE_CASTLED_KING_FILE || file == QUEEN_SIDE_CASTLED_KING_FILE;
  }

  /**
   * Determines whether a rook of the given alliance standing on the given coordinate retains
   * a castling right according to the castling rights field. Only a rook standing on its original
   * corner square can retain a right, and only when the corresponding character is present.
   *
   * @param alliance The alliance of the rook being placed.
   * @param coordinate The board coordinate the rook occupies.
   * @param castlingRights The castling rights field of the notation string.
   * @return True if the rook retains a castling right, false otherwise.
   */
  private static boolean retainsRookCastlingRight(final Alliance alliance,
                                                  final int coordinate,
                                                  final String castlingRights) {
    if (alliance.isWhite()) {
      return (coordinate == WHITE_KING_SIDE_ROOK_TILE && castlingRights.indexOf('K') >= 0) ||
              (coordinate == WHITE_QUEEN_SIDE_ROOK_TILE && castlingRights.indexOf('Q') >= 0);
    }
    return (coordinate == BLACK_KING_SIDE_ROOK_TILE && castlingRights.indexOf('k') >= 0) ||
            (coordinate == BLACK_QUEEN_SIDE_ROOK_TILE && castlingRights.indexOf('q') >= 0);
  }

  /**
   * Resolves the pawn that may be captured en passant from the en passant target square.
   * The target square names the square the capturing pawn moves to, so the vulnerable pawn stands
   * one rank beyond it, on the far side from the player about to move. The pawn instance returned
   * is the same instance placed on the board, since the engine compares en passant pawns by identity.
   *
   * @param targetSquare The en passant target square in algebraic notation.
   * @param nextMoveMaker The alliance of the player about to move.
   * @param placedPawns A map of every placed pawn by coordinate.
   * @return The pawn that may be captured en passant.
   * @throws IllegalArgumentException If the target square is unrecognised or no pawn stands beside it.
   */
  private static Pawn resolveEnPassantPawn(final String targetSquare,
                                           final Alliance nextMoveMaker,
                                           final Map<Integer, Pawn> placedPawns) {
    final int targetCoordinate = BoardUtils.squareOf(targetSquare.toLowerCase());
    if (targetCoordinate == -1) {
      throw new IllegalArgumentException("Unrecognised en passant target square in FEN: " + targetSquare);
    }
    final int pawnCoordinate = nextMoveMaker.isWhite() ? targetCoordinate + NUM_FILES
            : targetCoordinate - NUM_FILES;
    final Pawn enPassantPawn = placedPawns.get(pawnCoordinate);
    if (enPassantPawn == null) {
      throw new IllegalArgumentException("No pawn stands beyond the en passant target square " +
              targetSquare + " in FEN");
    }
    return enPassantPawn;
  }

  /**
   * Parses the side to move field of a Forsyth-Edwards Notation string.
   *
   * @param field The side to move field of the notation string.
   * @return The alliance of the player about to move.
   * @throws IllegalArgumentException If the field does not denote a side to move.
   */
  private static Alliance parseMoveMaker(final String field) {
    return switch (field.toLowerCase()) {
      case "w" -> Alliance.WHITE;
      case "b" -> Alliance.BLACK;
      default -> throw new IllegalArgumentException("Unrecognised side to move in FEN: " + field);
    };
  }

  /**
   * Parses one of the two trailing counter fields of a Forsyth-Edwards Notation string.
   *
   * @param field The field to parse.
   * @param minimum The smallest value the field is permitted to hold.
   * @param fieldName The name of the field, used in the error message.
   * @return The value the field holds.
   * @throws IllegalArgumentException If the field is not a whole number or falls below the minimum.
   */
  private static int parseCounter(final String field, final int minimum, final String fieldName) {
    final int value;
    try {
      value = Integer.parseInt(field);
    } catch (final NumberFormatException exception) {
      throw new IllegalArgumentException("Unrecognised " + fieldName + " in FEN: " + field);
    }
    if (value < minimum) {
      throw new IllegalArgumentException("A FEN " + fieldName + " may not fall below " + minimum +
              ", found " + value);
    }
    return value;
  }
}
