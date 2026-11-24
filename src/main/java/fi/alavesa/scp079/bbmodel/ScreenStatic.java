package fi.alavesa.scp079.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Old-monitor static over SCP-079's screen: every cube named "screen" (or inside a group named so) gets a twin a
 * hair larger, whose faces show a see-through, animated noise texture. It is part of the item model, so it moves,
 * scales and turns with the unit and needs nothing at run time. Item models are drawn translucent, so the screen
 * shows through the static.
 */
public final class ScreenStatic {

    /** Texture variable in the item model. */
    public static final String KEY = "screen_static";
    /** Texture location inside the namespace, without "textures/" and ".png". */
    public static final String TEXTURE = "item/screen_static";

    static final int SIZE = 32, FRAMES = 12;
    /** How far the static layer stands in front of the screen, in model pixels: enough to never z-fight. */
    static final double LIFT = 0.02;

    private ScreenStatic() { }

    public static boolean isScreen(BbModel.Cube cube) {
        return cube.name.toLowerCase(Locale.ROOT).startsWith("screen") || (cube.group != null && cube.group.isOrIsUnder("screen"));
    }

    /** The static twin of a written element: same box and rotation, a little larger, every face the static texture, full bright. */
    static JsonObject overlay(JsonObject element) {
        JsonObject out = new JsonObject();
        JsonArray from = element.getAsJsonArray("from"), to = element.getAsJsonArray("to");
        JsonArray f = new JsonArray(), t = new JsonArray();
        for (int i = 0; i < 3; i++) {
            f.add(JavaModelWriter.round(Math.max(-16, from.get(i).getAsDouble() - LIFT)));
            t.add(JavaModelWriter.round(Math.min(32, to.get(i).getAsDouble() + LIFT)));
        }
        out.add("from", f);
        out.add("to", t);
        if (element.has("rotation")) out.add("rotation", element.get("rotation").deepCopy());
        out.addProperty("light_emission", 15);
        JsonObject faces = new JsonObject();
        for (Map.Entry<String, JsonElement> face : element.getAsJsonObject("faces").entrySet()) {
            JsonObject s = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(0); uv.add(0); uv.add(16); uv.add(16);
            s.add("uv", uv);
            s.addProperty("texture", "#" + KEY);
            faces.add(face.getKey(), s);
        }
        out.add("faces", faces);
        return out;
    }

    /**
     * {@link #FRAMES} frames of {@link #SIZE} px grey noise stacked vertically, with a brighter band that rolls down the
     * screen. Seeded, so the same settings always give the same bytes (and the same pack hash).
     *
     * @param opacity 0..1 alpha of the brightest grains
     */
    public static byte[] png(double opacity) {
        BufferedImage image = new BufferedImage(SIZE, SIZE * FRAMES, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(79);
        int maxAlpha = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        for (int frame = 0; frame < FRAMES; frame++) {
            int band = frame * SIZE / FRAMES;
            for (int y = 0; y < SIZE; y++) {
                int distance = Math.min(Math.abs(y - band), SIZE - Math.abs(y - band));
                double boost = distance < 3 ? 1.0 : 0.55;
                double scanline = y % 2 == 0 ? 1.0 : 0.7;
                for (int x = 0; x < SIZE; x++) {
                    double grain = random.nextDouble();
                    int grey = 120 + (int) (grain * 135);
                    int alpha = (int) Math.round(maxAlpha * grain * boost * scanline);
                    image.setRGB(x, frame * SIZE + y, alpha << 24 | grey << 16 | grey << 8 | grey);
                }
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One frame per tick, no blending: static jumps, it does not fade. */
    public static byte[] mcmeta() {
        return "{\"animation\":{\"frametime\":1}}".getBytes(StandardCharsets.UTF_8);
    }
}
