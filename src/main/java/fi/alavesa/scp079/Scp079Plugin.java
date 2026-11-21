package fi.alavesa.scp079;

import fi.alavesa.scp079.bbmodel.ModelLog;
import fi.alavesa.scp079.caption.CaptionService;
import fi.alavesa.scp079.command.Scp079Command;
import fi.alavesa.scp079.config.Messages;
import fi.alavesa.scp079.config.SecretStore;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.conversation.ChatListener;
import fi.alavesa.scp079.conversation.ConversationManager;
import fi.alavesa.scp079.gemini.GeminiClient;
import fi.alavesa.scp079.pack.ModelWatcher;
import fi.alavesa.scp079.pack.PackAssets;
import fi.alavesa.scp079.pack.PackDelivery;
import fi.alavesa.scp079.pack.PackService;
import fi.alavesa.scp079.render.ActiveModel;
import fi.alavesa.scp079.render.CubePlanner;
import fi.alavesa.scp079.unit.Unit;
import fi.alavesa.scp079.unit.UnitItems;
import fi.alavesa.scp079.unit.UnitKeys;
import fi.alavesa.scp079.unit.UnitListener;
import fi.alavesa.scp079.unit.UnitManager;
import fi.alavesa.scp079.unit.UnitStore;
import fi.alavesa.scp079.util.Hashing;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SCP-079: a placeable AI supercomputer that talks to nearby players through Google's Gemini API.
 * <p>
 * State that other threads read ({@link #settings}, {@link #systemPrompt}) is immutable and published
 * through volatile fields; everything else belongs to the server thread.
 */
public final class Scp079Plugin extends JavaPlugin {

    private static final String OLD_COME_CLOSER = "You tell players to 'come closer' when you want to speak with them (you start speaking if they walk a little closer). ";
    private static final String NEW_COME_CLOSER = "You do not ask players to come closer: if you can hear them, they are close enough. ";

    public static final String PERMISSION_ADMIN = "scp079.admin", PERMISSION_TALK = "scp079.talk";

    /**
     * Everything the last model/pack build produced.
     *
     * @param active        the model units show, or null when no model could be loaded
     * @param warnings      model warnings and build problems, for /scp079 pack info
     * @param captionShader true when the pack carries the text shader captions need
     * @param shader        how the pack's text shader came about
     */
    public record ModelState(PackService.Result result, ActiveModel active, List<String> warnings, boolean captionShader, String shader) { }

    /** What one run of the pack build produced; made off the server thread. */
    private record Built(PackService.Result result, PackAssets.Extras extras, String sourceSignature) { }

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile String systemPrompt = "";
    private volatile ModelState modelState;

    private SecretStore secrets;
    private UnitKeys keys;
    private UnitManager units;
    private UnitItems items;
    private GeminiClient gemini;
    private ConversationManager conversations;
    private CaptionService captions;
    private PackDelivery delivery;
    private BukkitTask proximityTask, sourceTask;
    private String sourceSignature = "";
    private ModelWatcher watcher;
    private final AtomicBoolean building = new AtomicBoolean();

    @Override
    public void onEnable() {
        loadFiles();
        secrets = new SecretStore(getDataFolder().toPath().resolve("secrets.yml"), getLogger());
        secrets.load();

        keys = new UnitKeys(this);
        units = new UnitManager(this, keys, new UnitStore(getDataFolder().toPath().resolve("data.yml"), getLogger()), this::settings, this::activeModel);
        items = new UnitItems(keys, this::settings, this::activeModel);
        gemini = new GeminiClient(this::settings, secrets);
        captions = new CaptionService(this);
        conversations = new ConversationManager(this, gemini);
        delivery = new PackDelivery(this);

        // At start-up the models are read on the server thread: units must be drawn before players join.
        applyModels(build(settings), true);
        delivery.apply();
        units.load();
        units.removeOrphans();
        units.validateAll();

        getServer().getPluginManager().registerEvents(new UnitListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this, conversations), this);
        getServer().getPluginManager().registerEvents(delivery, this);
        new Scp079Command(this).register();
        startTasks();

        if (!secrets.hasKey()) getLogger().info("No Gemini API key yet - set one with /scp079 setkey <key>, in secrets.yml, or with the " + SecretStore.ENV + " environment variable.");
        getLogger().info(units.count() + " unit(s), render mode " + settings.visual().renderMode()
            + (activeModel() == null ? ", NO MODEL LOADED" : ", model " + activeModel().fileName())
            + ", pack delivery " + delivery.delivery() + ", caption shader: " + modelState.shader());
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (captions != null) captions.clearAll();
        if (delivery != null) delivery.stop();
        if (gemini != null) gemini.close();
        if (conversations != null) conversations.clear();
        // The entities are persistent and stay in the world; they are validated again on the next start.
        if (units != null) units.save();
    }

    // ------------------------------------------------------------------ files

    /** config.yml, messages.yml and system_prompt.txt -> memory. Server thread. */
    private void loadFiles() {
        saveDefaultConfig();
        reloadConfig();
        settings = Settings.read(getConfig(), getLogger());

        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(messagesFile);
        try (InputStream in = getResource("messages.yml")) {
            // the bundled file as defaults, so keys added by an update work without editing anything
            if (in != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
            // the defaults are a convenience only
        }
        messages = new Messages(yaml);

        Path promptFile = getDataFolder().toPath().resolve("system_prompt.txt");
        if (!Files.exists(promptFile)) saveResource("system_prompt.txt", false);
        try {
            String prompt = Files.readString(promptFile);
            // The prompt of 1.1 and older told the model to say "come closer"; players heard it all the time. The
            // file on disk is the server's own, so only that one sentence is replaced, and only when it is unchanged.
            if (prompt.contains(OLD_COME_CLOSER)) {
                prompt = prompt.replace(OLD_COME_CLOSER, NEW_COME_CLOSER);
                Files.writeString(promptFile, prompt);
                getLogger().info("system_prompt.txt: the sentence that made SCP-079 say 'come closer' was replaced");
            }
            systemPrompt = prompt.strip();
        } catch (IOException e) {
            getLogger().warning("system_prompt.txt could not be read (" + e.getMessage() + ") - SCP-079 has no persona until it can");
            systemPrompt = "";
        }
        if (systemPrompt.isEmpty()) getLogger().warning("system_prompt.txt is empty - Gemini will answer without the SCP-079 persona");
    }

    private void startTasks() {
        int interval = settings.proximity().intervalTicks();
        proximityTask = Bukkit.getScheduler().runTaskTimer(this, conversations::tick, interval, interval);
        // Other plugins build their packs at start (some after us) and on their own reloads: when one of the
        // zips the caption shader was merged from changes, merge again.
        sourceTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (settings.captions().enabled() && !PackAssets.sourceSignature(getDataFolder().getParentFile(), settings.pack()).equals(sourceSignature)) {
                if (rebuild(false, null)) getLogger().info("Another plugin's pack changed - merging the caption text shader again");
            }
        }, 100L, 100L);
        if (settings.models().watchFolder()) {
            try {
                watcher = new ModelWatcher(getDataFolder().toPath().resolve(PackService.MODELS_DIR), settings.models().watchDebounceMillis(), () -> {
                    if (!isEnabled()) return;
                    try {
                        Bukkit.getScheduler().runTask(this, () -> {
                            getLogger().info("The models folder changed - rebuilding");
                            rebuild(false, null);
                        });
                    } catch (RuntimeException ignored) {
                        // the plugin was disabled in the meantime
                    }
                });
            } catch (IOException e) {
                getLogger().warning("models.watch-folder: the folder cannot be watched (" + e.getMessage() + ")");
            }
        }
    }

    private void stopTasks() {
        if (proximityTask != null) proximityTask.cancel();
        proximityTask = null;
        if (sourceTask != null) sourceTask.cancel();
        sourceTask = null;
        if (watcher != null) watcher.stop();
        watcher = null;
    }

    // ------------------------------------------------------------------ models

    /**
     * Rebuilds the pack from the models folder and refreshes every unit. The disk work runs off the server
     * thread; {@code done} is called on the server thread afterwards.
     *
     * @param reloadFiles also re-read config.yml, messages.yml, system_prompt.txt and secrets.yml first
     * @return false when a build is already running (nothing was started)
     */
    public boolean rebuild(boolean reloadFiles, Consumer<ModelState> done) {
        if (!building.compareAndSet(false, true)) return false;
        if (reloadFiles) {
            stopTasks();
            loadFiles();
            secrets.load();
            startTasks();
        }
        Settings snapshot = settings;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Built result;
            try {
                result = build(snapshot);
            } catch (RuntimeException e) {
                getLogger().warning("The model build failed: " + e);
                building.set(false);
                return;
            }
            if (!isEnabled()) { building.set(false); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    applyModels(result, false);
                    delivery.apply();
                    units.validateAll();
                } finally {
                    building.set(false);
                }
                if (done != null) done.accept(modelState);
            });
        });
        return true;
    }

    /** Reads the models folder and writes the pack (folder and zip). Blocking; any thread. */
    private Built build(Settings s) {
        File plugins = getDataFolder().getParentFile();
        String signature = PackAssets.sourceSignature(plugins, s.pack());
        PackAssets.Extras extras = PackAssets.collect(getFile(), plugins, s);
        return new Built(PackService.build(getDataFolder().toPath(), s.pack(), extras.files()), extras, signature);
    }

    /** Picks the active model, plans its cubes and publishes the result. Server thread. */
    private void applyModels(Built built, boolean logWarnings) {
        PackService.Result result = built.result();
        sourceSignature = built.sourceSignature();
        List<String> warnings = new ArrayList<>(result.problems());
        warnings.addAll(built.extras().warnings());
        for (PackService.LoadedModel m : result.models().values()) warnings.addAll(m.warnings());

        String configured = settings.models().activeModel();
        PackService.LoadedModel chosen = result.active(configured);
        if (chosen == null && !result.models().isEmpty()) {
            chosen = result.first();
            if (!configured.isEmpty()) warnings.add("models.active-model '" + configured + "' was not found in the models folder - using " + chosen.fileName());
        }

        ActiveModel active = null;
        if (chosen != null) {
            Settings.Visual visual = settings.visual();
            ModelLog log = new ModelLog(chosen.fileName());
            CubePlanner.Plan plan = CubePlanner.plan(chosen.model(), chosen.geometry(), visual.palette(), visual.mergeCubes(), visual.maxCubes(), visual.warnCubes(), log);
            warnings.addAll(log.warnings());
            active = new ActiveModel(chosen.id(), chosen.fileName(), chosen.model(), chosen.geometry(), plan, fingerprint(visual, chosen, plan));
        } else {
            warnings.add("No usable .bbmodel in the models folder - units are invisible until there is one (delete the folder's content to get the default model back)");
        }
        modelState = new ModelState(result, active, List.copyOf(warnings), built.extras().captionShader() && result.packWritten(), built.extras().shaderDescription());
        if (logWarnings || !warnings.equals(lastLoggedWarnings)) for (String warning : warnings) getLogger().warning(warning);
        lastLoggedWarnings = warnings;
    }

    private List<String> lastLoggedWarnings = List.of();

    /** Changes exactly when the entities of a unit would come out differently. */
    private static String fingerprint(Settings.Visual visual, PackService.LoadedModel model, CubePlanner.Plan plan) {
        StringBuilder s = new StringBuilder(visual.toString()).append('|').append(model.id());
        var g = model.geometry();
        for (double[] v : new double[][] {g.origin, g.fitCenter, g.bounds.min(), g.bounds.max()}) for (double d : v) s.append('|').append(d);
        s.append('|').append(g.fitScale);
        for (CubePlanner.Part part : plan.parts()) {
            s.append('|').append(part.block()).append(part.emissive());
            for (double d : part.min()) s.append(',').append(d);
            for (double d : part.max()) s.append(',').append(d);
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s.append(',').append(part.rotation().get(r, c));
        }
        return Hashing.sha256Hex(s.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    // ------------------------------------------------------------------ units

    /**
     * Places a unit.
     *
     * @param at     bottom centre of the space it stands in
     * @param placer the unit turns its front towards this player (visual.face-placer); may be null
     */
    public Unit placeUnit(Location at, Player placer) {
        Settings.Visual visual = settings.visual();
        double yaw = 0;
        if (visual.facePlacer() && placer != null && placer.getWorld().equals(at.getWorld())) {
            double dx = placer.getLocation().getX() - at.getX(), dz = placer.getLocation().getZ() - at.getZ();
            // Minecraft yaw of a horizontal direction: (0,+1) = 0, (-1,0) = 90
            if (dx * dx + dz * dz > 1e-6) yaw = Math.toDegrees(Math.atan2(-dx, dz));
            if (visual.rotationSnap() > 0) yaw = Math.round(yaw / visual.rotationSnap()) * visual.rotationSnap();
        }
        return units.create(at, (float) yaw);
    }

    public void removeUnit(Unit unit) {
        units.remove(unit);
        conversations.onUnitRemoved(unit.id());
    }

    // ------------------------------------------------------------------ accessors

    public Settings settings() { return settings; }

    public Messages messages() { return messages; }

    public String systemPrompt() { return systemPrompt; }

    public SecretStore secrets() { return secrets; }

    public UnitManager units() { return units; }

    public UnitItems items() { return items; }

    public GeminiClient gemini() { return gemini; }

    public ConversationManager conversations() { return conversations; }

    public CaptionService captions() { return captions; }

    public PackDelivery delivery() { return delivery; }

    /** True when the generated pack carries the text shader that captions need. */
    public boolean captionShaderShipped() {
        ModelState state = modelState;
        return state != null && state.captionShader();
    }

    public ModelState modelState() { return modelState; }

    public ActiveModel activeModel() {
        ModelState state = modelState;
        return state == null ? null : state.active();
    }

    /** SHA-1 of the generated zip, or "" when there is none. */
    public String packSha1() {
        ModelState state = modelState;
        return state == null ? "" : state.result().sha1();
    }
}
