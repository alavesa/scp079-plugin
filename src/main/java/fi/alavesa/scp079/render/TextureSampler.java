package fi.alavesa.scp079.render;

import fi.alavesa.scp079.bbmodel.BbModel;
import fi.alavesa.scp079.bbmodel.ModelLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Average colour of the texture area a cube shows. Decodes each texture once (ImageIO, headless) and
 * averages the pixels under every face's UV rectangle, weighted by alpha so transparent texels do not
 * darken the result.
 */
public final class TextureSampler {

    /**
     * @param rgb      average colour of the opaque texels, {@code 0xRRGGBB}
     * @param coverage 0..1: average alpha of the sampled area (0 = fully transparent, 1 = solid)
     * @param sampled  false when no face had a readable texture
     */
    public record Sample(int rgb, double coverage, boolean sampled) { }

    private final BbModel model;
    private final ModelLog log;
    private final Map<Integer, int[]> pixels = new HashMap<>();
    private boolean imageIoBroken;

    public TextureSampler(BbModel model, ModelLog log) {
        this.model = model;
        this.log = log;
    }

    public Sample sample(BbModel.Cube cube) {
        double r = 0, g = 0, b = 0, alpha = 0, area = 0;
        for (BbModel.Face face : cube.faces.values()) {
            BbModel.Texture texture = model.textures.get(face.texture());
            int[] argb = pixels(face.texture(), texture);
            if (argb == null) continue;
            int w = texture.pngWidth(), frameHeight = texture.pngHeight() / Math.max(1, texture.frames());
            // UV space -> pixels of the first animation frame; a flipped face (u2 < u1) covers the same area
            double sx = w / texture.uvWidth(), sy = frameHeight / texture.uvHeight();
            int x1 = clamp((int) Math.floor(Math.min(face.uv()[0], face.uv()[2]) * sx), 0, w - 1);
            int x2 = clamp((int) Math.ceil(Math.max(face.uv()[0], face.uv()[2]) * sx), x1 + 1, w);
            int y1 = clamp((int) Math.floor(Math.min(face.uv()[1], face.uv()[3]) * sy), 0, frameHeight - 1);
            int y2 = clamp((int) Math.ceil(Math.max(face.uv()[1], face.uv()[3]) * sy), y1 + 1, frameHeight);
            double fr = 0, fg = 0, fb = 0, fa = 0;
            for (int y = y1; y < y2; y++) {
                for (int x = x1; x < x2; x++) {
                    int p = argb[y * w + x];
                    double a = (p >>> 24) / 255.0;
                    fr += ((p >> 16) & 0xFF) * a;
                    fg += ((p >> 8) & 0xFF) * a;
                    fb += (p & 0xFF) * a;
                    fa += a;
                }
            }
            int count = (x2 - x1) * (y2 - y1);
            // every face counts the same, however many texels it happens to show
            r += fr / count;
            g += fg / count;
            b += fb / count;
            alpha += fa / count;
            area += 1;
        }
        if (area == 0) return new Sample(0x808080, 1, false);
        if (alpha < 1e-6) return new Sample(0, 0, true);
        int rgb = ((int) Math.round(r / alpha) << 16) | ((int) Math.round(g / alpha) << 8) | (int) Math.round(b / alpha);
        return new Sample(rgb, alpha / area, true);
    }

    /** @return row-major ARGB pixels of the texture, or null when it cannot be read */
    private int[] pixels(int index, BbModel.Texture texture) {
        if (pixels.containsKey(index)) return pixels.get(index);
        int[] argb = null;
        if (texture.png() != null && !imageIoBroken) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(texture.png()));
                if (image != null && image.getWidth() == texture.pngWidth() && image.getHeight() == texture.pngHeight()) {
                    argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                } else {
                    log.warn("texture '" + texture.key() + "'", "could not be decoded - cubes using it get the default block");
                }
            } catch (LinkageError e) {
                // a stripped-down Java runtime without the java.desktop module
                imageIoBroken = true;
                log.warn("", "this Java runtime has no image decoder (java.desktop module missing) - every cube gets the default block; use visual.block-overrides or a full JRE");
            } catch (Exception e) {
                log.warn("texture '" + texture.key() + "'", "could not be decoded (" + e.getMessage() + ") - cubes using it get the default block");
            }
        }
        pixels.put(index, argb);
        return argb;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
