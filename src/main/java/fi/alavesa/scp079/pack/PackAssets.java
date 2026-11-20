package fi.alavesa.scp079.pack;

import fi.alavesa.scp079.config.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The part of the resource pack that does not come from the models folder: the caption font, the blanked
 * boss bar, the caption settings for the shader and the (merged) core/text shader. Blocking file work with no
 * Bukkit access.
 */
public final class PackAssets {

    private static final String PREFIX = "pack/";
    private static final String BOSS_BAR = "assets/minecraft/textures/gui/sprites/boss_bar/";

    /**
     * @param files             pack path -> bytes
     * @param captionShader     true when the pack carries the text shader captions need
     * @param shaderDescription how the text shader came about
     */
    public record Extras(Map<String, byte[]> files, boolean captionShader, String shaderDescription, List<String> warnings) { }

    private PackAssets() { }

    public static Extras collect(File pluginJar, File pluginsFolder, Settings settings) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (!settings.captions().enabled()) return new Extras(files, false, "off (captions.enabled is false)", warnings);

        String vanillaVertex = null, vanillaFragment = null;
        try (JarFile jar = new JarFile(pluginJar)) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    if (entry.getName().startsWith(PREFIX)) files.put(entry.getName().substring(PREFIX.length()), in.readAllBytes());
                    else if (entry.getName().equals("shaders/vanilla_text.vsh")) vanillaVertex = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    else if (entry.getName().equals("shaders/vanilla_text.fsh")) vanillaFragment = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            warnings.add("The caption files could not be read from the plugin jar (" + e.getMessage() + ") - SCP-079 speaks in chat");
            return new Extras(new LinkedHashMap<>(), false, "none (jar not readable)", warnings);
        }
        if (vanillaVertex == null || vanillaFragment == null || files.isEmpty()) {
            warnings.add("The plugin jar has no caption files - SCP-079 speaks in chat");
            return new Extras(new LinkedHashMap<>(), false, "none (missing from the jar)", warnings);
        }

        List<Map.Entry<String, File>> candidates = new ArrayList<>();
        for (Map.Entry<String, String> zip : settings.pack().shaderZips().entrySet()) candidates.add(Map.entry(zip.getKey(), new File(pluginsFolder, zip.getValue())));
        TextShaderMerge.Result shader = TextShaderMerge.build(vanillaVertex, vanillaFragment, candidates, warnings::add);
        if (!shader.shipped()) {
            // without the shader the caption boss bar is never shown, so nothing of it belongs in the pack
            return new Extras(new LinkedHashMap<>(), false, shader.description(), warnings);
        }
        files.putAll(shader.files());

        Settings.Captions c = settings.captions();
        // read by scp079_text_vertex.glsl / scp079_text_fragment.glsl
        files.put("assets/scp079/shaders/include/scp079_caption_config.glsl", String.format(Locale.ROOT,
            "// Written by the SCP079 plugin from config.yml (captions.*).%n"
                + "const float SCP079_CAPTION_SCALE = %.3f;%n"
                + "const float SCP079_CAPTION_BOTTOM = %d.0;%n"
                + "const float SCP079_CAPTION_BG_ALPHA = %.3f;%n"
                + "const vec3  SCP079_CAPTION_COLOR = vec3(%.4f, %.4f, %.4f);%n",
            c.scale(), c.bottomOffset(), c.backgroundOpacity(),
            ((c.colorRgb() >> 16) & 0xFF) / 255.0, ((c.colorRgb() >> 8) & 0xFF) / 255.0, (c.colorRgb() & 0xFF) / 255.0).getBytes(StandardCharsets.UTF_8));
        return new Extras(files, true, shader.description(), warnings);
    }

    /** Size and time of the other plugins' pack zips: changes when one of them rebuilds its pack. */
    public static String sourceSignature(File pluginsFolder, Settings.Pack pack) {
        StringBuilder sb = new StringBuilder();
        for (String path : pack.shaderZips().values()) {
            File zip = new File(pluginsFolder, path);
            sb.append(zip.length()).append(':').append(zip.lastModified()).append(';');
        }
        return sb.toString();
    }
}
