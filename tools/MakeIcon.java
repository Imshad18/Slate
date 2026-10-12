import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * The launcher icon: a document with a pen across it.
 *
 * Drawn rather than shipped as art so it is crisp at every density, and so
 * it says what the app is — a thing that opens documents and writes in them.
 */
public class MakeIcon {

    public static void main(String[] args) throws Exception {
        String res = args.length > 0 ? args[0] : "res";
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) {
            File dir = new File(res, "mipmap-" + dirs[i]);
            dir.mkdirs();
            ImageIO.write(icon(sizes[i]), "png", new File(dir, "ic_launcher.png"));
            System.out.println("  mipmap-" + dirs[i] + "/ic_launcher.png  "
                    + sizes[i] + "px");
        }
    }

    static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);

        float u = s / 48f;                       // one unit at the base size

        // ---- background: the app's own dark ground, rounded --------------
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B1F27),
                0, s, new Color(0x11141A)));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, 11 * u, 11 * u));

        // ---- the sheet -----------------------------------------------------
        float pw = 22 * u, ph = 28 * u;
        float px = (s - pw) / 2f - 1.5f * u, py = (s - ph) / 2f - 1f * u;
        float fold = 7 * u;

        Path2D sheet = new Path2D.Float();
        sheet.moveTo(px, py + 2 * u);
        sheet.quadTo(px, py, px + 2 * u, py);
        sheet.lineTo(px + pw - fold, py);
        sheet.lineTo(px + pw, py + fold);
        sheet.lineTo(px + pw, py + ph - 2 * u);
        sheet.quadTo(px + pw, py + ph, px + pw - 2 * u, py + ph);
        sheet.lineTo(px + 2 * u, py + ph);
        sheet.quadTo(px, py + ph, px, py + ph - 2 * u);
        sheet.closePath();

        g.setColor(new Color(0xF2F5FA));
        g.fill(sheet);

        // the folded corner, darker so it reads as a fold rather than a notch
        Path2D corner = new Path2D.Float();
        corner.moveTo(px + pw - fold, py);
        corner.lineTo(px + pw - fold, py + fold);
        corner.lineTo(px + pw, py + fold);
        corner.closePath();
        g.setColor(new Color(0xC2CBD8));
        g.fill(corner);

        // ---- text lines ------------------------------------------------------
        g.setColor(new Color(0x97A3B4));
        float lx = px + 4 * u;
        float lw = pw - 8 * u;
        for (int i = 0; i < 4; i++) {
            float ly = py + (10 + i * 4.4f) * u;
            float w = (i == 3) ? lw * 0.55f : lw;
            g.fill(new RoundRectangle2D.Float(lx, ly, w, 1.5f * u, u, u));
        }

        // ---- the pen, on the accent colour -----------------------------------
        g.rotate(Math.toRadians(-38), s * 0.62f, s * 0.64f);

        float nibW = 5.2f * u, bodyLen = 20 * u;
        float bx = s * 0.62f - nibW / 2, by = s * 0.64f - bodyLen / 2;

        // body
        g.setPaint(new GradientPaint(bx, by, new Color(0x6FD3FF),
                bx + nibW, by + bodyLen, new Color(0x2196C9)));
        g.fill(new RoundRectangle2D.Float(bx, by, nibW, bodyLen - 4 * u,
                1.6f * u, 1.6f * u));

        // nib
        Path2D nib = new Path2D.Float();
        nib.moveTo(bx, by + bodyLen - 4.2f * u);
        nib.lineTo(bx + nibW, by + bodyLen - 4.2f * u);
        nib.lineTo(bx + nibW / 2, by + bodyLen + 1.4f * u);
        nib.closePath();
        g.setColor(new Color(0x1B6E94));
        g.fill(nib);

        // ferrule
        g.setColor(new Color(0xBFEAFF));
        g.fill(new RoundRectangle2D.Float(bx, by + 4.6f * u, nibW, 1.5f * u,
                0.7f * u, 0.7f * u));

        g.dispose();
        return img;
    }
}
