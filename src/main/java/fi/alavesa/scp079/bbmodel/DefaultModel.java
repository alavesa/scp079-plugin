package fi.alavesa.scp079.bbmodel;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import fi.alavesa.scp079.util.Png;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * The starter content of the models folder: a small retro computer, written as an ordinary Blockbench
 * project (generic "free" format, one embedded 32x32 texture). It is only ever written when the folder has
 * no .bbmodel at all - from then on the file on disk is the model, and the plugin knows nothing about this
 * design.
 */
public final class DefaultModel {

    public static final String FILE_NAME = "scp079.bbmodel";

    /** Texture atlas regions, {@code [u1, v1, u2, v2]} in a 32 x 32 UV space. */
    private static final double[] CASE = {0, 0, 16, 16}, DARK = {16, 0, 32, 16}, SCREEN = {0, 16, 16, 28},
        LED = {16, 16, 20, 20}, KEYS = {16, 20, 32, 28};

    private final JsonArray elements = new JsonArray();

    private DefaultModel() { }

    public static String json() {
        DefaultModel m = new DefaultModel();

        JsonArray computer = new JsonArray();
        computer.add(m.cube("base", -7, 0, -6, 7, 4, 6, CASE, CASE, 0));
        computer.add(m.cube("monitor", -6, 4, -5, 6, 14, 5, CASE, CASE, 0));
        computer.add(m.cube("bezel", -5, 5, -5.4, 5, 13, -5, DARK, DARK, 0));
        computer.add(m.cube("screen", -4, 6, -5.6, 4, 12, -5.4, SCREEN, DARK, 15));
        computer.add(m.cube("led_glow", 4.5, 1.5, -6.2, 5.5, 2.5, -6, LED, LED, 15));
        computer.add(m.cube("vent", -5, 14, -3, 5, 14.5, 3, DARK, DARK, 0));

        // The keyboard sits in its own group, tilted about its front edge - a small example of a rotated,
        // nested group for anyone opening the file in Blockbench.
        JsonArray keyboard = new JsonArray();
        keyboard.add(m.cube("keyboard", -6, 0, -12, 6, 1, -7.5, DARK, DARK, 0));
        keyboard.add(m.cube("keys", -5.5, 1, -11.5, 5.5, 1.3, -8, DARK, KEYS, 0));

        JsonArray outliner = new JsonArray();
        outliner.add(group("computer", new double[] {0, 0, 0}, new double[] {0, 0, 0}, computer));
        outliner.add(group("keyboard", new double[] {0, 0, -12}, new double[] {-8, 0, 0}, keyboard));

        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);

        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", 32);
        resolution.addProperty("height", 32);

        JsonObject texture = new JsonObject();
        texture.addProperty("name", "scp079.png");
        texture.addProperty("id", "0");
        texture.addProperty("uuid", uuid("texture"));
        texture.addProperty("width", 32);
        texture.addProperty("height", 32);
        texture.addProperty("uv_width", 32);
        texture.addProperty("uv_height", 32);
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(texture()));
        JsonArray textures = new JsonArray();
        textures.add(texture);

        JsonObject root = new JsonObject();
        root.add("meta", meta);
        root.addProperty("name", "scp079");
        root.add("resolution", resolution);
        root.add("elements", m.elements);
        root.add("outliner", outliner);
        root.add("textures", textures);
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root);
    }

    /**
     * @param sides atlas region of the four side faces and the bottom
     * @param front atlas region of the north face (the model's front) and the top
     */
    private JsonElement cube(String name, double x1, double y1, double z1, double x2, double y2, double z2,
                             double[] sides, double[] front, int lightEmission) {
        JsonObject cube = new JsonObject();
        cube.addProperty("name", name);
        cube.addProperty("type", "cube");
        cube.addProperty("uuid", uuid(name));
        cube.add("from", array(x1, y1, z1));
        cube.add("to", array(x2, y2, z2));
        cube.add("origin", array((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2));
        cube.add("rotation", array(0, 0, 0));
        cube.addProperty("box_uv", false);
        if (lightEmission > 0) cube.addProperty("light_emission", lightEmission);
        boolean topIsFront = front == KEYS;
        JsonObject faces = new JsonObject();
        faces.add("north", face(topIsFront ? sides : front));
        faces.add("east", face(sides));
        faces.add("south", face(sides));
        faces.add("west", face(sides));
        faces.add("up", face(topIsFront ? front : sides));
        faces.add("down", face(sides));
        cube.add("faces", faces);
        elements.add(cube);
        return new JsonPrimitive(uuid(name));
    }

    private static JsonObject face(double[] region) {
        JsonObject face = new JsonObject();
        face.add("uv", array(region));
        face.addProperty("texture", 0);
        return face;
    }

    private static JsonObject group(String name, double[] origin, double[] rotation, JsonArray children) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        group.addProperty("uuid", uuid("group:" + name));
        group.add("origin", array(origin));
        group.add("rotation", array(rotation));
        group.addProperty("isOpen", true);
        group.add("children", children);
        return group;
    }

    /** Stable uuids, so the generated file is the same on every server. */
    private static String uuid(String element) {
        return UUID.nameUUIDFromBytes(("scp079-default/" + element).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray array(double... values) {
        JsonArray a = new JsonArray();
        for (double v : values) a.add(v);
        return a;
    }

    /** 32 x 32 atlas: beige case, dark plastic, the green screen with 079's face, a red LED, the key caps. */
    static byte[] texture() {
        int size = 32;
        int[] px = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int c = 0;
                if (y < 16 && x < 16) {
                    c = (x + y) % 7 == 0 ? 0xFFB9B09A : 0xFFC9C0AA;          // case, with a little grain
                    if (y % 4 == 3 && x > 2 && x < 13) c = 0xFFA9A08B;      // cooling slits
                } else if (y < 16) {
                    c = (x * 3 + y) % 5 == 0 ? 0xFF2C2E33 : 0xFF34373C;     // dark plastic
                } else if (y < 28 && x < 16) {
                    c = y % 2 == 0 ? 0xFF0E5A22 : 0xFF0A4419;               // screen scanlines
                } else if (y < 20 && x < 20) {
                    c = 0xFFE0281E;                                         // LED
                } else if (y >= 20 && y < 28 && x >= 16) {
                    c = (x % 2 == 0 && y % 2 == 0) ? 0xFF8D9096 : 0xFF2C2E33; // key caps
                }
                px[y * size + x] = c;
            }
        }
        // 079's face on the screen: two eyes and a flat mouth, drawn in bright phosphor green
        int green = 0xFF5CFF7A;
        int[][] face = {{4, 19}, {5, 19}, {4, 20}, {5, 20}, {10, 19}, {11, 19}, {10, 20}, {11, 20},
            {5, 24}, {6, 24}, {7, 24}, {8, 24}, {9, 24}, {10, 24}, {4, 25}, {11, 25}};
        for (int[] p : face) px[p[1] * size + p[0]] = green;
        return Png.encode(size, size, px);
    }
}
