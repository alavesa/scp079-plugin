package fi.alavesa.scp079.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which vanilla block stands in for which colour in LIVE_CUBES mode. Block names are lower-case material
 * names without the namespace ("gray_concrete"); colours are {@code 0xRRGGBB} - roughly the average colour
 * of the block's texture.
 *
 * @param solid             opaque blocks, for ordinary cubes
 * @param glass             translucent blocks, for cubes whose texture is mostly see-through
 * @param emissive          extra candidates for glowing cubes (they may also use any solid block)
 * @param overrides         lower-case cube, group or texture name -> block, checked before any colour matching
 * @param defaultBlock      used when a cube's texture cannot be read
 * @param emissiveKeywords  a cube or group whose lower-case name contains one of these glows
 * @param glassBelow        texture coverage (average alpha, 0..1) below which a cube becomes glass
 * @param invisibleBelow    coverage below which a cube is left out entirely
 */
public record Palette(Map<String, Integer> solid, Map<String, Integer> glass, Map<String, Integer> emissive,
                      Map<String, String> overrides, String defaultBlock, List<String> emissiveKeywords,
                      double glassBelow, double invisibleBelow) {

    public static Palette defaults() {
        return new Palette(defaultSolid(), defaultGlass(), defaultEmissive(), Map.of(), "gray_concrete",
            List.of("screen", "glow", "light", "led", "emissive"), 0.6, 0.05);
    }

    /** @return the block whose colour is closest to {@code rgb}, or null when {@code candidates} is empty */
    public static String nearest(int rgb, Map<String, Integer> candidates) {
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> e : candidates.entrySet()) {
            double d = distance(rgb, e.getValue());
            if (d < bestDistance) { bestDistance = d; best = e.getKey(); }
        }
        return best;
    }

    /**
     * "Redmean" colour distance: a cheap approximation of how different two sRGB colours look, which
     * weights red and blue by how much red there is. Far better than plain RGB distance at keeping dark
     * greens green and beiges beige.
     */
    static double distance(int a, int b) {
        int r1 = (a >> 16) & 0xFF, g1 = (a >> 8) & 0xFF, b1 = a & 0xFF;
        int r2 = (b >> 16) & 0xFF, g2 = (b >> 8) & 0xFF, b2 = b & 0xFF;
        double rMean = (r1 + r2) / 2.0;
        int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        return (2 + rMean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rMean) / 256) * db * db;
    }

    public static String normalize(String block) {
        String s = block.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static void dyed(Map<String, Integer> out, String suffix, int... colours) {
        for (int i = 0; i < DYES.length; i++) out.put(DYES[i] + "_" + suffix, colours[i]);
    }

    public static Map<String, Integer> defaultSolid() {
        Map<String, Integer> m = new LinkedHashMap<>();
        dyed(m, "concrete", 0xCFD5D6, 0xE06101, 0xA9309F, 0x2489C7, 0xF1AF15, 0x5EA918, 0xD5658F, 0x373A3E,
            0x7D7D73, 0x157788, 0x64209C, 0x2D2F8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F);
        dyed(m, "terracotta", 0xD1B2A1, 0xA15325, 0x95586C, 0x716C89, 0xBA8523, 0x677535, 0xA14E4E, 0x392A23,
            0x876A61, 0x565B5B, 0x764656, 0x4A3B5B, 0x4D3323, 0x4C532A, 0x8F3D2E, 0x251610);
        dyed(m, "wool", 0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C527, 0x70B919, 0xED8DAC, 0x3E4447,
            0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519);
        m.put("terracotta", 0x985E43);
        m.put("smooth_stone", 0x9E9E9E);
        m.put("iron_block", 0xDCDCDC);
        m.put("quartz_block", 0xECE6DF);
        m.put("polished_deepslate", 0x484849);
        m.put("polished_blackstone", 0x35303B);
        m.put("smooth_sandstone", 0xDFD6AA);
        m.put("birch_planks", 0xC0AF79);
        return m;
    }

    public static Map<String, Integer> defaultGlass() {
        Map<String, Integer> m = new LinkedHashMap<>();
        dyed(m, "stained_glass", 0xFFFFFF, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C,
            0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919);
        return m;
    }

    public static Map<String, Integer> defaultEmissive() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("sea_lantern", 0xACC8BE);
        m.put("glowstone", 0xAB8354);
        m.put("shroomlight", 0xF19347);
        m.put("verdant_froglight", 0xD3EBD0);
        m.put("ochre_froglight", 0xFAF5CE);
        m.put("pearlescent_froglight", 0xF5EBEF);
        m.put("emerald_block", 0x2ACB58);
        m.put("redstone_block", 0xAF1805);
        m.put("diamond_block", 0x62EDE4);
        m.put("gold_block", 0xF6D03D);
        m.put("lapis_block", 0x1F438C);
        m.put("lime_stained_glass", 0x7FCC19);
        m.put("green_stained_glass", 0x667F33);
        return m;
    }
}
