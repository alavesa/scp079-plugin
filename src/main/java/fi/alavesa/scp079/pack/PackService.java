package fi.alavesa.scp079.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.alavesa.scp079.bbmodel.BbModel;
import fi.alavesa.scp079.bbmodel.BbModelParser;
import fi.alavesa.scp079.bbmodel.DefaultModel;
import fi.alavesa.scp079.bbmodel.JavaModelWriter;
import fi.alavesa.scp079.bbmodel.ModelException;
import fi.alavesa.scp079.bbmodel.ModelGeometry;
import fi.alavesa.scp079.bbmodel.ModelLog;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * models/*.bbmodel -> parsed models + the resource pack (folder and zip). This is the one place the model
 * files are read: the LIVE_CUBES renderer and the pack are both fed from the {@link LoadedModel}s it
 * returns. Blocking disk work with no Bukkit access - run it off the server thread (except at start-up).
 *
 * <pre>
 *   pack.mcmeta
 *   assets/scp079/items/&lt;id&gt;.json                  item model definition - what item_model=scp079:&lt;id&gt; resolves to
 *   assets/scp079/models/item/&lt;id&gt;.json            the converted model
 *   assets/scp079/textures/item/&lt;id&gt;/&lt;tex&gt;.png     its textures (+ .png.mcmeta for animated ones)
 *   ...                                              the caption files, see {@link PackAssets}
 * </pre>
 */
public final class PackService {

    public static final String MODELS_DIR = "models", PACK_DIR = "resourcepack", ZIP_NAME = "SCP079-pack.zip";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** @param warnings everything worth telling the admin about this file, each line naming it */
    public record LoadedModel(String id, String fileName, BbModel model, ModelGeometry geometry, List<String> warnings) { }

    /**
     * @param models    id -> model, in file-name order; only the files that could be read
     * @param problems  files that were skipped entirely, and pack-writing errors
     * @param sha1      SHA-1 of the zip, or "" when it could not be written
     * @param fileCount files in the pack
     */
    public record Result(Map<String, LoadedModel> models, List<String> problems, String sha1, int fileCount, Path zip, Path folder) {

        public boolean packWritten() { return !sha1.isEmpty(); }

        /** The model named by models.active-model (file name, with or without extension), else the first one. */
        public LoadedModel active(String configured) {
            String wanted = configured.toLowerCase(Locale.ROOT);
            for (LoadedModel m : models.values()) {
                String file = m.fileName().toLowerCase(Locale.ROOT);
                if (!wanted.isEmpty() && (file.equals(wanted) || file.equals(wanted + ".bbmodel") || m.id().equals(wanted))) return m;
            }
            return null;
        }

        public LoadedModel first() { return models.isEmpty() ? null : models.values().iterator().next(); }
    }

    private PackService() { }

    /** @param extraFiles pack files that do not come from the models (captions: font, shader...), path -> bytes */
    public static Result build(Path dataFolder, Settings.Pack settings, Map<String, byte[]> extraFiles) {
        Path modelsDir = dataFolder.resolve(MODELS_DIR), folder = dataFolder.resolve(PACK_DIR), zip = dataFolder.resolve(ZIP_NAME);
        List<String> problems = new ArrayList<>();
        Map<String, LoadedModel> models = new LinkedHashMap<>();
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.putAll(extraFiles);
        files.put("pack.mcmeta", json(mcmeta(settings)));

        List<Path> sources = new ArrayList<>();
        try {
            Files.createDirectories(modelsDir);
            Path starter = modelsDir.resolve(DefaultModel.FILE_NAME);
            if (Files.isRegularFile(starter) && Hashing.sha256Hex(Files.readAllBytes(starter)).equals(DefaultModel.BROKEN_STARTER_SHA256)) {
                // the untouched starter model of an older version: its screen faced backwards
                Files.writeString(starter, DefaultModel.json());
            }
            sources = listModels(modelsDir);
            if (sources.isEmpty()) {
                // first run: give the admin something that works and can be opened in Blockbench
                Files.writeString(modelsDir.resolve(DefaultModel.FILE_NAME), DefaultModel.json());
                sources = listModels(modelsDir);
            }
        } catch (IOException e) {
            problems.add("The models folder could not be read: " + e.getMessage());
        }

        JavaModelWriter writer = new JavaModelWriter(Settings.NAMESPACE);
        for (Path source : sources) {
            String fileName = source.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - ".bbmodel".length()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (id.isEmpty()) id = "model";
            if (models.containsKey(id)) {
                problems.add("[" + fileName + "] skipped: its id '" + id + "' is already used by " + models.get(id).fileName());
                continue;
            }
            ModelLog log = new ModelLog(fileName);
            try {
                BbModel model = BbModelParser.parse(id, Files.readAllBytes(source), relative -> sibling(modelsDir, relative), log);
                ModelGeometry geometry = ModelGeometry.of(model);
                if (geometry.fitScale < 1) {
                    log.warn("", String.format(Locale.ROOT, "pack model: larger than the 48 px an item model may span - stored at %.0f%% size and scaled back up by the display entity (no visible difference)", geometry.fitScale * 100));
                }
                addToPack(files, writer, model, geometry, log);
                models.put(id, new LoadedModel(id, fileName, model, geometry, new ArrayList<>(log.warnings())));
            } catch (ModelException e) {
                problems.add("[" + fileName + "] skipped: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                problems.add("[" + fileName + "] skipped: could not be read (" + e + ")");
            }
        }

        String sha1 = "";
        try {
            byte[] zipBytes = DeterministicZip.write(files, false);
            writeFolder(folder, files);
            Path tmp = zip.resolveSibling(ZIP_NAME + ".tmp");
            Files.write(tmp, zipBytes);
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
            sha1 = Hashing.sha1Hex(zipBytes);
        } catch (IOException | RuntimeException e) {
            problems.add("The resource pack could not be written: " + e);
        }
        return new Result(models, problems, sha1, files.size(), zip, folder);
    }

    private static List<Path> listModels(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
                .sorted().toList();
        }
    }

    /** A file next to the models (a texture that is not embedded). Never leaves the models folder. */
    private static byte[] sibling(Path modelsDir, String relative) {
        try {
            Path path = modelsDir.resolve(relative).normalize();
            if (!path.startsWith(modelsDir) || !Files.isRegularFile(path)) return null;
            return Files.readAllBytes(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 26.3 is resource pack format 97. Since format 65 a pack declares the range it supports with
     * min_format / max_format; pack_format is still written for tools that only know the old field.
     */
    static JsonObject mcmeta(Settings.Pack settings) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", settings.description());
        pack.addProperty("pack_format", settings.formatMin());
        pack.addProperty("min_format", settings.formatMin());
        pack.addProperty("max_format", settings.formatMax());
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    private static void addToPack(SortedMap<String, byte[]> files, JavaModelWriter writer, BbModel model, ModelGeometry geometry, ModelLog log) {
        String assets = "assets/" + Settings.NAMESPACE + "/";
        files.put(assets + "models/item/" + model.id + ".json", json(writer.write(model, geometry, log)));

        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", Settings.NAMESPACE + ":item/" + model.id);
        JsonObject definition = new JsonObject();
        definition.add("model", reference);
        files.put(assets + "items/" + model.id + ".json", json(definition));

        for (BbModel.Texture texture : model.textures) {
            if (texture.png() == null) continue;
            String path = assets + "textures/item/" + model.id + "/" + texture.key() + ".png";
            files.put(path, texture.png());
            if (texture.mcmeta() != null) {
                files.put(path + ".mcmeta", texture.mcmeta());
            } else if (texture.frames() > 1) {
                JsonObject animation = new JsonObject();
                animation.addProperty("frametime", texture.frameTime());
                if (texture.interpolate()) animation.addProperty("interpolate", true);
                JsonObject root = new JsonObject();
                root.add("animation", animation);
                files.put(path + ".mcmeta", json(root));
            }
        }
    }

    /** Replaces the folder form of the pack: everything in resourcepack/ is generated, nothing else lives there. */
    private static void writeFolder(Path folder, SortedMap<String, byte[]> files) throws IOException {
        if (Files.exists(folder)) {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (!dir.equals(folder)) Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = folder.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
    }

    private static byte[] json(JsonObject o) { return GSON.toJson(o).getBytes(StandardCharsets.UTF_8); }
}
