package fi.alavesa.scp079.config;

import fi.alavesa.scp079.render.Palette;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml as immutable values, read once per (re)load. Every value is range-checked here, so the rest of
 * the plugin never sees a negative radius or an unknown material. Safe to read from any thread.
 */
public record Settings(Gemini gemini, Conversation conversation, Proximity proximity, Chat chat, Captions captions,
                       Units units, Visual visual, Models models, Pack pack) {

    public static final String NAMESPACE = "scp079";

    public enum RenderMode { LIVE_CUBES, PACK_MODEL }

    public record Gemini(String model, String endpoint, double temperature, int maxOutputTokens, String thinkingLevel,
                         int timeoutSeconds, long playerCooldownMillis, int maxConcurrent) { }

    public record Conversation(int historyTurns, long memoryMillis, int maxMessageLength, int maxSentences, String greetingPrompt) { }

    public record Proximity(int intervalTicks, double talkRadius, long greetingCooldownMillis) { }

    /** @param sound null when the sound is switched off or its name is not a valid key */
    public record Chat(boolean isPrivate, String format, String echoFormat, boolean typingDelay, int typingBaseTicks,
                       double typingTicksPerChar, int typingMaxTicks, Key sound, float soundVolume, float soundPitch) { }

    /**
     * @param colorRgb caption text colour, {@code 0xRRGGBB}
     * @param inChat   NEVER, FALLBACK (only for players who cannot see captions) or ALWAYS
     */
    public record Captions(boolean enabled, double scale, int bottomOffset, double backgroundOpacity, int colorRgb,
                           double charsPerTick, int holdBaseTicks, double holdTicksPerChar, int holdMaxTicks, InChat inChat) { }

    public enum InChat { NEVER, FALLBACK, ALWAYS }

    public enum Delivery { AUTO, SAME_PORT, HTTP, URL, NONE }

    public record Units(boolean punchToRemove, boolean dropItem, Material itemMaterial, String itemName, List<String> itemLore,
                        boolean itemUsesPackModel) { }

    /** @param brightness 0..15, or -1 for natural light */
    public record Visual(RenderMode renderMode, double scale, boolean facePlacer, double rotationSnap, double rotationOffset,
                         float viewRange, int brightness, int emissiveBrightness, boolean mergeCubes, int maxCubes, int warnCubes,
                         Palette palette) { }

    public record Models(String activeModel, boolean watchFolder, long watchDebounceMillis) { }

    /** @param shaderZips other plugins' pack zips whose text shader is merged, "name -> path relative to plugins/", most complete first */
    public record Pack(int formatMin, int formatMax, String description, Delivery delivery, String publicHost, String httpBind,
                       int httpPort, String url, String sha1, boolean required, String prompt, int sendDelayTicks,
                       Map<String, String> shaderZips) { }

    public static Settings read(FileConfiguration c, Logger log) {
        Gemini gemini = new Gemini(
            c.getString("gemini.model", "gemini-3.5-flash-lite").trim(),
            stripSlash(c.getString("gemini.endpoint", "https://generativelanguage.googleapis.com/v1beta").trim()),
            clamp(c.getDouble("gemini.temperature", 0.9), 0, 2),
            Math.max(16, c.getInt("gemini.max-output-tokens", 120)),
            c.getString("gemini.thinking-level", "").trim(),
            Math.max(2, c.getInt("gemini.timeout-seconds", 15)),
            (long) (Math.max(0, c.getDouble("gemini.player-cooldown-seconds", 3)) * 1000),
            Math.max(1, c.getInt("gemini.max-concurrent-requests", 4)));

        Conversation conversation = new Conversation(
            Math.max(1, c.getInt("conversation.history-turns", 10)),
            (long) (Math.max(0, c.getDouble("conversation.memory-minutes", 30)) * 60_000),
            Math.max(20, c.getInt("conversation.max-message-length", 300)),
            Math.max(1, c.getInt("conversation.max-sentences", 3)),
            c.getString("conversation.greeting-prompt", "(A human has just walked up to your terminal. Greet them in one or two short sentences.)"));

        Proximity proximity = new Proximity(
            Math.max(1, c.getInt("proximity.check-interval-ticks", 20)),
            Math.max(1, c.getDouble("proximity.talk-radius", 5)),
            (long) (Math.max(0, c.getDouble("proximity.greeting-cooldown-seconds", 120)) * 1000));

        Key sound = null;
        if (c.getBoolean("chat.sound.enabled", true)) {
            String name = c.getString("chat.sound.name", "minecraft:block.note_block.bit").trim().toLowerCase(Locale.ROOT);
            if (Key.parseable(name)) sound = Key.key(name);
            else log.warning("chat.sound.name '" + name + "' is not a valid sound id - no sound is played");
        }
        Chat chat = new Chat(
            c.getBoolean("chat.private", true),
            c.getString("chat.format", "<green>\\<SCP-079></green> <message>"),
            c.getString("chat.echo-format", ""),
            c.getBoolean("chat.typing-delay.enabled", true),
            Math.max(0, c.getInt("chat.typing-delay.base-ticks", 10)),
            Math.max(0, c.getDouble("chat.typing-delay.ticks-per-character", 0.25)),
            Math.max(0, c.getInt("chat.typing-delay.max-ticks", 50)),
            sound,
            (float) clamp(c.getDouble("chat.sound.volume", 0.6), 0, 4),
            (float) clamp(c.getDouble("chat.sound.pitch", 0.7), 0.5, 2));

        int captionColor = 0x7DFF99;
        String rawColor = c.getString("captions.color", "#7DFF99").trim().replace("#", "");
        try {
            if (rawColor.length() != 6) throw new NumberFormatException();
            captionColor = Integer.parseInt(rawColor, 16);
        } catch (NumberFormatException e) {
            log.warning("captions.color '" + c.getString("captions.color") + "' is not a colour like \"#7DFF99\" - using the default");
        }
        InChat inChat = InChat.FALLBACK;
        try {
            inChat = InChat.valueOf(c.getString("captions.in-chat", "FALLBACK").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("captions.in-chat '" + c.getString("captions.in-chat") + "' is not NEVER, FALLBACK or ALWAYS - using FALLBACK");
        }
        Captions captions = new Captions(
            c.getBoolean("captions.enabled", true),
            clamp(c.getDouble("captions.scale", 1), 0.5, 4),
            (int) clamp(c.getInt("captions.bottom-offset", 57), 0, 400),
            clamp(c.getDouble("captions.background-opacity", 0.55), 0.11, 1),
            captionColor,
            clamp(c.getDouble("captions.characters-per-tick", 1), 0.1, 40),
            Math.max(0, c.getInt("captions.hold.base-ticks", 40)),
            Math.max(0, c.getDouble("captions.hold.ticks-per-character", 1)),
            Math.max(10, c.getInt("captions.hold.max-ticks", 160)),
            inChat);

        Material material = Material.matchMaterial(c.getString("units.item.material", "OBSERVER"));
        if (material == null || !material.isItem() || material.isAir()) {
            log.warning("units.item.material '" + c.getString("units.item.material") + "' is not an item - using OBSERVER");
            material = Material.OBSERVER;
        }
        Units units = new Units(
            c.getBoolean("units.punch-to-remove", true),
            c.getBoolean("units.drop-item", true),
            material,
            c.getString("units.item.name", "<green>SCP-079"),
            List.copyOf(c.getStringList("units.item.lore")),
            c.getBoolean("units.item.use-pack-model", true));

        RenderMode mode = RenderMode.PACK_MODEL;
        String rawMode = c.getString("visual.render-mode", "PACK_MODEL").trim().toUpperCase(Locale.ROOT);
        try {
            mode = RenderMode.valueOf(rawMode);
        } catch (IllegalArgumentException e) {
            log.warning("visual.render-mode '" + rawMode + "' is not PACK_MODEL or LIVE_CUBES - using PACK_MODEL");
        }
        Visual visual = new Visual(
            mode,
            clamp(c.getDouble("visual.scale", 1), 0.05, 16),
            c.getBoolean("visual.face-placer", true),
            clamp(c.getDouble("visual.rotation-snap", 45), 0, 180),
            c.getDouble("visual.rotation-offset", 0),
            (float) clamp(c.getDouble("visual.view-range", 1), 0.05, 16),
            (int) clamp(c.getInt("visual.brightness", -1), -1, 15),
            (int) clamp(c.getInt("visual.emissive-brightness", 15), 0, 15),
            c.getBoolean("visual.merge-cubes", true),
            Math.max(1, c.getInt("visual.max-cubes", 256)),
            Math.max(0, c.getInt("visual.warn-cubes", 96)),
            palette(c, log));

        Models models = new Models(
            c.getString("models.active-model", "").trim(),
            c.getBoolean("models.watch-folder", false),
            Math.max(200, c.getLong("models.watch-debounce-ms", 1500)));

        int formatMin = Math.max(1, c.getInt("resourcepack.format.min", 97));
        Delivery delivery = Delivery.AUTO;
        String rawDelivery = c.getString("resourcepack.delivery", "auto").trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            delivery = Delivery.valueOf(rawDelivery);
        } catch (IllegalArgumentException e) {
            log.warning("resourcepack.delivery '" + c.getString("resourcepack.delivery") + "' is not auto, same-port, http, url or none - using auto");
        }
        Map<String, String> shaderZips = new LinkedHashMap<>();
        ConfigurationSection zips = c.getConfigurationSection("resourcepack.text-shader-sources");
        if (zips != null) for (String key : zips.getKeys(false)) if (!zips.getString(key, "").isBlank()) shaderZips.put(key, zips.getString(key));
        Pack pack = new Pack(
            formatMin,
            Math.max(formatMin, c.getInt("resourcepack.format.max", 97)),
            c.getString("resourcepack.description", "SCP-079"),
            delivery,
            c.getString("resourcepack.public-host", "").trim(),
            c.getString("resourcepack.http.bind", "0.0.0.0").trim(),
            (int) clamp(c.getInt("resourcepack.http.port", 8079), 1, 65535),
            c.getString("resourcepack.url", "").trim(),
            c.getString("resourcepack.sha1", "").trim().toLowerCase(Locale.ROOT),
            c.getBoolean("resourcepack.required", false),
            c.getString("resourcepack.prompt", ""),
            Math.max(0, c.getInt("resourcepack.send-delay-ticks", 80)),
            shaderZips);

        return new Settings(gemini, conversation, proximity, chat, captions, units, visual, models, pack);
    }

    private static Palette palette(FileConfiguration c, Logger log) {
        Palette defaults = Palette.defaults();
        boolean useDefaults = c.getBoolean("visual.palette.use-defaults", true);
        Map<String, Integer> solid = useDefaults ? new LinkedHashMap<>(defaults.solid()) : new LinkedHashMap<>();
        Map<String, Integer> glass = useDefaults ? new LinkedHashMap<>(defaults.glass()) : new LinkedHashMap<>();
        Map<String, Integer> emissive = useDefaults ? new LinkedHashMap<>(defaults.emissive()) : new LinkedHashMap<>();
        readColours(c.getConfigurationSection("visual.palette.solid"), solid, log);
        readColours(c.getConfigurationSection("visual.palette.glass"), glass, log);
        readColours(c.getConfigurationSection("visual.palette.emissive"), emissive, log);

        String defaultBlock = block(c.getString("visual.palette.default-block", "gray_concrete"), "visual.palette.default-block", log);
        if (defaultBlock == null) defaultBlock = "gray_concrete";
        if (solid.isEmpty()) {
            log.warning("visual.palette.solid is empty and use-defaults is false - every cube becomes " + defaultBlock);
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        ConfigurationSection section = c.getConfigurationSection("visual.block-overrides");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String block = block(section.getString(key, ""), "visual.block-overrides." + key, log);
                if (block != null) overrides.put(key.toLowerCase(Locale.ROOT), block);
            }
        }
        List<String> keywords = new ArrayList<>();
        for (String keyword : c.getStringList("visual.emissive-keywords")) keywords.add(keyword.trim().toLowerCase(Locale.ROOT));

        return new Palette(solid, glass, emissive, overrides, defaultBlock, List.copyOf(keywords),
            clamp(c.getDouble("visual.palette.glass-below-opacity", 0.6), 0, 1),
            clamp(c.getDouble("visual.palette.invisible-below-opacity", 0.05), 0, 1));
    }

    private static void readColours(ConfigurationSection section, Map<String, Integer> into, Logger log) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String block = block(key, section.getCurrentPath() + "." + key, log);
            if (block == null) continue;
            String hex = String.valueOf(section.get(key)).trim().replace("#", "");
            try {
                if (hex.length() != 6) throw new NumberFormatException();
                into.put(block, Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
                log.warning(section.getCurrentPath() + "." + key + ": '" + section.get(key) + "' is not a colour like \"#48484E\" - entry skipped");
            }
        }
    }

    /** @return the normalized block name, or null (with a warning) when it is not a placeable block */
    private static String block(String raw, String path, Logger log) {
        String name = Palette.normalize(raw == null ? "" : raw);
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock() || material.isAir()) {
            log.warning(path + ": '" + raw + "' is not a block - entry skipped");
            return null;
        }
        return name;
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
