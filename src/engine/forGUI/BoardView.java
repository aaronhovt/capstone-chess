package engine.forGUI;

import engine.forBoard.Board;
import engine.forBoard.BoardUtils;
import engine.forBoard.Move;
import engine.forPiece.Piece;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The chess board as one component. It paints the squares, the pieces, the outline of the
 * selected piece and the squares of the computer's last move, and reports which square a click
 * lands on. The board can be shown from either side.
 *
 * @author Aaron Ho
 */
final class BoardView extends JComponent {

  /** Receives the clicks on the board's squares. */
  @FunctionalInterface
  interface SquareClickListener {

    /**
     * Called on the event dispatch thread when a square is clicked.
     *
     * @param square The square clicked, 0 for a8 through 63 for h1.
     * @param event The click.
     */
    void squareClicked(int square, MouseEvent event);
  }

  /** The size the board asks the window for. */
  private static final Dimension PREFERRED_SIZE = new Dimension(620, 620);

  /** The width of the frame around the squares, in pixels. */
  private static final int FRAME_WIDTH = 10;

  /** The width and height a piece is drawn at, in pixels. */
  private static final int PIECE_SIZE = 40;

  /** The directory holding the piece images, one SVG file per piece such as "WN.svg". */
  private static final String PIECE_IMAGE_DIRECTORY = "art/simple/";

  /** The colour of the frame around the squares. */
  private static final Color FRAME_COLOR = Color.decode("#8B4726");

  /** The colour of the light squares. */
  private static final Color LIGHT_SQUARE_COLOR = Color.decode("#FFFACD");

  /** The colour of the dark squares. */
  private static final Color DARK_SQUARE_COLOR = Color.decode("#593E1A");

  /** The colour of the square the computer's last move left. */
  private static final Color ENGINE_ORIGIN_COLOR = Color.pink;

  /** The colour of the square the computer's last move arrived on. */
  private static final Color ENGINE_DESTINATION_COLOR = Color.red;

  /** The outline of a square. */
  private static final Color OUTLINE_COLOR = Color.GRAY;

  /** The outline of the square of the selected piece. */
  private static final Color SELECTED_OUTLINE_COLOR = Color.cyan;

  /**
   * The rendered piece images, keyed by SVG file path. Only ever touched on the event dispatch
   * thread.
   */
  private static final Map<String, BufferedImage> PIECE_IMAGES = new HashMap<>();

  /** The position shown, or null before the first one is given. */
  private Board board;

  /** The square outlined as selected, or -1. */
  private int selectedSquare = -1;

  /** The square the computer's last move left, or -1. */
  private int engineOrigin = -1;

  /** The square the computer's last move arrived on, or -1. */
  private int engineDestination = -1;

  /** Whether the board is shown from black's side. */
  private boolean flipped;

  /**
   * Creates the board view, showing no position until {@link #drawBoard} is called.
   *
   * @param listener The receiver of clicks on the squares.
   */
  BoardView(final SquareClickListener listener) {
    setPreferredSize(PREFERRED_SIZE);
    setFocusable(false);
    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(final MouseEvent event) {
        final int square = squareAt(event.getPoint());
        if (square >= 0) {
          listener.squareClicked(square, event);
        }
      }
    });
  }

  /**
   * Shows the given position, on the event dispatch thread.
   *
   * @param position The position to show.
   * @param selected The square to outline as selected, or -1 for none.
   * @param engineMove The computer's last move, whose squares are marked, or null for none.
   */
  void drawBoard(final Board position, final int selected, final Move engineMove) {
    SwingUtilities.invokeLater(() -> {
      this.board = position;
      this.selectedSquare = selected;
      this.engineOrigin = engineMove == null ? -1 : engineMove.getCurrentCoordinate();
      this.engineDestination = engineMove == null ? -1 : engineMove.getDestinationCoordinate();
      repaint();
    });
  }

  /** Turns the board round, to be shown from the other side on the next {@link #drawBoard}. */
  void flip() {
    this.flipped = !this.flipped;
  }

  @Override
  protected void paintComponent(final Graphics graphics) {
    final int size = squareSize();
    graphics.setColor(FRAME_COLOR);
    graphics.fillRect(0, 0, getWidth(), getHeight());
    for (int row = 0; row < BoardUtils.NUM_TILES_PER_ROW; row++) {
      for (int column = 0; column < BoardUtils.NUM_TILES_PER_ROW; column++) {
        final int square = squareShownAt(row, column);
        final int x = FRAME_WIDTH + column * size;
        final int y = FRAME_WIDTH + row * size;
        graphics.setColor(squareColor(square));
        graphics.fillRect(x, y, size, size);
        graphics.setColor(square == this.selectedSquare ? SELECTED_OUTLINE_COLOR : OUTLINE_COLOR);
        graphics.drawRect(x, y, size - 1, size - 1);
        final Piece piece = this.board == null ? null : this.board.getPiece(square);
        final BufferedImage image = piece == null ? null : pieceImage(piece);
        if (image != null) {
          graphics.drawImage(image, x + (size - PIECE_SIZE) / 2, y + (size - PIECE_SIZE) / 2,
                  null);
        }
      }
    }
  }

  /**
   * Returns the width and height of a square at the component's current size.
   *
   * @return The square size, in pixels.
   */
  private int squareSize() {
    return (Math.min(getWidth(), getHeight()) - 2 * FRAME_WIDTH) / BoardUtils.NUM_TILES_PER_ROW;
  }

  /**
   * Returns the square shown in the given row and column of the view, counted from the top left.
   *
   * @param row The row of the view.
   * @param column The column of the view.
   * @return The square shown there.
   */
  private int squareShownAt(final int row, final int column) {
    final int square = row * BoardUtils.NUM_TILES_PER_ROW + column;
    return this.flipped ? BoardUtils.NUM_TILES - 1 - square : square;
  }

  /**
   * Returns the square under the given point of the component.
   *
   * @param point The point.
   * @return The square, or -1 if the point is on the frame or outside the squares.
   */
  private int squareAt(final Point point) {
    final int size = squareSize();
    if (size <= 0) {
      return -1;
    }
    final int x = point.x - FRAME_WIDTH;
    final int y = point.y - FRAME_WIDTH;
    final int span = size * BoardUtils.NUM_TILES_PER_ROW;
    if (x < 0 || y < 0 || x >= span || y >= span) {
      return -1;
    }
    return squareShownAt(y / size, x / size);
  }

  /**
   * Returns the colour the given square is filled with.
   *
   * @param square The square.
   * @return The square's colour.
   */
  private Color squareColor(final int square) {
    if (square == this.engineOrigin) {
      return ENGINE_ORIGIN_COLOR;
    }
    if (square == this.engineDestination) {
      return ENGINE_DESTINATION_COLOR;
    }
    return (BoardUtils.rowOf(square) + square) % 2 == 0 ? LIGHT_SQUARE_COLOR : DARK_SQUARE_COLOR;
  }

  /**
   * Returns the image of the given piece, rendering it from its SVG file the first time it is
   * needed.
   *
   * @param piece The piece.
   * @return The image, or null if the file could not be rendered.
   */
  private static BufferedImage pieceImage(final Piece piece) {
    final String path = PIECE_IMAGE_DIRECTORY + piece.getPieceAllegiance().toString().charAt(0) +
            piece + ".svg";
    BufferedImage image = PIECE_IMAGES.get(path);
    if (image == null && !PIECE_IMAGES.containsKey(path)) {
      try {
        image = renderSvg(new File(path), PIECE_SIZE);
      } catch (final Exception failure) {
        System.out.println("Could not render the piece image " + path + ": " + failure.getMessage());
        failure.printStackTrace();
      }
      PIECE_IMAGES.put(path, image);
    }
    return image;
  }

  /**
   * Renders an SVG file to an image of the given width and height.
   *
   * @param svgFile The SVG file.
   * @param size The width and height of the image, in pixels.
   * @return The image.
   * @throws Exception If the file cannot be read or rendered.
   */
  private static BufferedImage renderSvg(final File svgFile, final int size) throws Exception {
    final PNGTranscoder transcoder = new PNGTranscoder();
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, (float) size);
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, (float) size);
    final ByteArrayOutputStream png = new ByteArrayOutputStream();
    transcoder.transcode(new TranscoderInput(svgFile.toURI().toString()), new TranscoderOutput(png));
    return ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
  }
}