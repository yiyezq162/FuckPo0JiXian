import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/**
 * Draws the desktop icons from the shapes in art/icon.svg (a 200 × 200 tile): the window / Windows tray icon, the
 * macOS menu bar template, the macOS iconset and the Windows .ico. Run through scripts/render-icons.sh.
 * Usage: java RenderIcons.java <desktop dir> <iconset dir>
 */
public class RenderIcons {
    static final Color TOP = new Color(0xFFD54F), BOTTOM = new Color(0xFFA726), BEAK = new Color(0xFB8C00),
        EYE = new Color(0x4E342E), WING = new Color(0xFFE0B2);

    static Shape tuft() {
        Path2D p = new Path2D.Double();
        p.moveTo(97, 60); p.curveTo(92, 44, 104, 36, 113, 42); p.curveTo(107, 46, 104, 52, 103, 60); p.closePath();
        return p;
    }
    static Shape body() { return new Ellipse2D.Double(46, 60, 108, 108); }
    static Shape beak() {
        Path2D p = new Path2D.Double();
        p.moveTo(150, 104); p.lineTo(168, 113); p.lineTo(150, 122); p.closePath();
        Area a = new Area(p);
        a.add(new Area(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(p)));
        return a;
    }
    static Shape eye() { return new Ellipse2D.Double(115.5, 93.5, 13, 13); }
    static Shape wing() {
        Path2D p = new Path2D.Double();
        p.moveTo(60, 116); p.curveTo(62, 102, 80, 98, 96, 110); p.curveTo(88, 128, 70, 132, 60, 116); p.closePath();
        return p;
    }
    static Shape tile() { return new RoundRectangle2D.Double(0, 0, 200, 200, 92, 92); }

    /**
     * The one-colour shape for the menu bar: the chick with its eye cut out. Drawn about 18 px tall, so the outline is
     * thickened and the eye enlarged; otherwise the tuft, beak and eye vanish at that size.
     */
    static Area silhouette() {
        Area a = new Area(body());
        a.add(new Area(tuft()));
        a.add(new Area(beak()));
        a.add(new Area(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(a)));
        a.subtract(new Area(new Ellipse2D.Double(111, 89, 22, 22)));
        return a;
    }

    static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** The full-colour tile, [size] px wide, drawn at [x], [y]. */
    static void drawTile(Graphics2D g, double x, double y, double size) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y); g.scale(size / 200, size / 200);
        g.setPaint(new GradientPaint(0, 0, TOP, 0, 200, BOTTOM));
        g.fill(tile());
        g.setColor(Color.WHITE); g.fill(tuft()); g.fill(body());
        g.setColor(BEAK); g.fill(beak());
        g.setColor(EYE); g.fill(eye());
        g.setColor(WING); g.fill(wing());
        g.setTransform(saved);
    }

    /** Tile with a small margin; small sizes get less so the chick stays legible. */
    static BufferedImage flat(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        double margin = size <= 24 ? 0 : Math.round(size * 0.03);
        drawTile(g, margin, margin, size - 2 * margin);
        g.dispose();
        return image;
    }

    /** macOS layout: the tile is 824/1024 of the canvas with a soft shadow underneath, like the system icons. */
    static BufferedImage mac(int size) {
        int big = 1024;
        BufferedImage shadow = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D s = canvas(shadow);
        s.translate(100, 112); s.scale(824.0 / 200, 824.0 / 200);
        s.setColor(new Color(0, 0, 0, 70)); s.fill(tile());
        s.dispose();
        int radius = 14;
        float[] weights = new float[radius * 2 + 1];
        java.util.Arrays.fill(weights, 1f / weights.length);
        for (int i = 0; i < 3; i++) {
            shadow = new ConvolveOp(new Kernel(weights.length, 1, weights), ConvolveOp.EDGE_NO_OP, null).filter(shadow, null);
            shadow = new ConvolveOp(new Kernel(1, weights.length, weights), ConvolveOp.EDGE_NO_OP, null).filter(shadow, null);
        }
        BufferedImage full = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(full);
        g.drawImage(shadow, 0, 0, null);
        drawTile(g, 100, 100, 824);
        g.dispose();
        return scale(full, size);
    }

    /** Black shape on transparent, [fill] of the height; macOS tints it for light and dark menu bars. */
    static BufferedImage template(int size, double fill) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        Area shape = silhouette();
        Rectangle2D b = shape.getBounds2D();
        double scale = size * fill / Math.max(b.getWidth(), b.getHeight());
        g.translate((size - b.getWidth() * scale) / 2, (size - b.getHeight() * scale) / 2);
        g.scale(scale, scale); g.translate(-b.getX(), -b.getY());
        g.setColor(Color.BLACK); g.fill(shape);
        g.dispose();
        return image;
    }

    static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage current = source;
        // Halve step by step: one big jump with bilinear filtering would alias.
        while (current.getWidth() / 2 >= size) current = resize(current, current.getWidth() / 2);
        return current.getWidth() == size ? current : resize(current, size);
    }
    static BufferedImage resize(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(out);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A .ico whose entries are PNGs (supported since Windows Vista). */
    static void ico(File file, int... sizes) throws Exception {
        byte[][] images = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) images[i] = png(flat(sizes[i]));
        int offset = 6 + 16 * sizes.length, total = offset;
        for (byte[] b : images) total += b.length;
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i] >= 256 ? 0 : sizes[i];
            buf.put((byte) s).put((byte) s).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
                .putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] b : images) buf.put(b);
        Files.write(file.toPath(), buf.array());
    }

    public static void main(String[] args) throws Exception {
        File desktop = new File(args[0]), iconset = new File(args[1]);
        File resources = new File(desktop, "src/main/resources");
        ImageIO.write(flat(256), "png", new File(resources, "icon.png"));
        ImageIO.write(template(22, 0.8), "png", new File(resources, "tray-22.png"));
        ImageIO.write(template(44, 0.8), "png", new File(resources, "tray-44.png"));
        ico(new File(desktop, "icons/icon.ico"), 16, 24, 32, 48, 64, 128, 256);
        iconset.mkdirs();
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(mac(s), "png", new File(iconset, "icon_" + s + "x" + s + ".png"));
            ImageIO.write(mac(s * 2), "png", new File(iconset, "icon_" + s + "x" + s + "@2x.png"));
        }
    }
}
