package fi.alavesa.scp079.caption;

import fi.alavesa.scp079.Scp079Plugin;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.voice.Pronouncer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * SCP-079's captions: a box of text just above the hotbar, typed out letter by letter as if it were speaking.
 * A reply longer than the box scrolls up line by line while it is typed - one caption, without pauses.
 * <p>
 * A caption is a boss bar per player. Its name is the whole caption, laid out by {@link CaptionText}; the
 * pack's text shader moves it from the top of the screen to above the hotbar and the pack blanks the blue bar
 * graphic. Every player is sent captions, also those whose client did not load the pack
 * (see {@link fi.alavesa.scp079.pack.PackDelivery#captionsReady}). Without the pack's font and shader the
 * name is drawn as placeholder boxes at the top of the screen, so those players also get the chat line.
 * <p>
 * Main thread only. The ticking task runs only while somebody has a caption.
 */
public final class CaptionService {

    /** While SCP-079 is "thinking" (the request is on its way): dots cycle at this pace. */
    private static final int THINKING_STEP_TICKS = 6;
    private static final List<String> THINKING = List.of(".", "..", "...");

    private static final class State {
        BossBar bar;
        /** The whole reply, wrapped; empty = nothing is being said. */
        List<String> lines = List.of();
        int length;
        /** Characters of the reply shown so far (fractional: characters-per-tick need not be whole). */
        double revealed;
        int holdLeft;
        boolean thinking;
        int thinkingTicks;
        /** Where SCP-079 speaks from: the caption (and voice) stop when the player is no longer near it. */
        UUID world;
        double x, y, z;
        /** The voice still to be played, and ticks until the next step. */
        final Deque<Pronouncer.Step> voice = new ArrayDeque<>();
        int voiceWait;
        float wordPitch = 1f;
    }

    /** A player this much further away than the talk radius stops seeing (and hearing) the caption. */
    private static final double RANGE_MARGIN = 1.0;
    private final Random random = new Random();

    private final Scp079Plugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;

    public CaptionService(Scp079Plugin plugin) { this.plugin = plugin; }

    /** Shows cycling dots until {@link #say} or {@link #clear} is called. */
    public void thinking(Player player) {
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        if (!state.lines.isEmpty()) return; // still saying something: let it finish
        state.thinking = true;
        state.thinkingTicks = 0;
        show(player, state, CaptionText.page(List.of("..."), 1, CaptionText.PALETTE_DIM));
        ensureTask();
    }

    /**
     * Types {@code text} out.
     *
     * @param sound played when it starts, at the given position; may be null
     */
    public void say(Player player, String text, Sound sound, double x, double y, double z) {
        List<String> lines = CaptionText.wrap(text);
        if (lines.isEmpty()) return;
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        state.thinking = false;
        state.world = player.getWorld().getUID();
        state.x = x;
        state.y = y;
        state.z = z;
        state.voice.clear();
        state.voiceWait = 0;
        Settings.Voice voice = plugin.settings().voice();
        Pronouncer pronouncer = plugin.pronouncer();
        if (voice.enabled() && pronouncer != null) state.voice.addAll(pronouncer.steps(text));
        state.lines = lines;
        state.revealed = 0;
        state.length = 0;
        for (String line : lines) state.length += line.length();
        Settings.Captions cfg = plugin.settings().captions();
        // the hold starts when everything has been typed; it is measured on what is then in the box
        int visible = 0;
        for (int i = Math.max(0, lines.size() - CaptionText.MAX_LINES); i < lines.size(); i++) visible += lines.get(i).length();
        state.holdLeft = (int) Math.min(cfg.holdMaxTicks(), cfg.holdBaseTicks() + visible * cfg.holdTicksPerChar());
        if (sound != null) player.playSound(sound, x, y, z);
        show(player, state, CaptionText.scrolling(lines, 0, CaptionText.PALETTE_TEXT));
        ensureTask();
    }

    /** Removes the player's caption (and stops the voice) at once. */
    public void clear(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null && state.bar != null) player.hideBossBar(state.bar);
    }

    public void clearAll() {
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && entry.getValue().bar != null) player.hideBossBar(entry.getValue().bar);
        }
        states.clear();
        if (task != null) task.cancel();
        task = null;
    }

    private void show(Player player, State state, Component name) {
        if (state.bar == null) {
            // BLUE: the bar graphic the pack blanks (no vanilla mob has a blue bar). Progress 0: nothing to fill.
            state.bar = BossBar.bossBar(name, 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            player.showBossBar(state.bar);
        } else if (!state.bar.name().equals(name)) {
            state.bar.name(name);
        }
    }

    private void ensureTask() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private boolean outOfRange(Player player, State state) {
        if (!player.getWorld().getUID().equals(state.world)) return true;
        double range = plugin.settings().proximity().talkRadius() + RANGE_MARGIN;
        Location at = player.getLocation();
        double dx = at.getX() - state.x, dy = at.getY() - state.y, dz = at.getZ() - state.z;
        return dx * dx + dy * dy + dz * dz > range * range;
    }

    /** Plays the next phoneme of the voice when its turn has come. Only this player hears it, from the unit. */
    private void speakStep(Player player, State state) {
        if (state.voice.isEmpty()) return;
        if (state.voiceWait > 0) {
            state.voiceWait--;
            return;
        }
        Settings.Voice cfg = plugin.settings().voice();
        Pronouncer.Step step = state.voice.poll();
        state.voiceWait = step.ticks() - 1;
        if (step.phoneme() == null) {
            // a pause: the next word gets its own slightly different pitch
            state.wordPitch = 1f + (random.nextFloat() * 2 - 1) * cfg.pitchVariation();
            return;
        }
        float pitch = cfg.pitch() * state.wordPitch;
        if (random.nextDouble() < cfg.glitchChance()) pitch *= random.nextBoolean() ? 0.8f : 1.25f;
        player.playSound(Sound.sound(Key.key(Settings.NAMESPACE, "voice." + step.phoneme()), Sound.Source.VOICE, cfg.volume(),
            Math.max(0.5f, Math.min(2f, pitch))), state.x, state.y, state.z);
    }

    private void tick() {
        double speed = plugin.settings().captions().charsPerTick();
        for (Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, State> entry = it.next();
            State state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) { it.remove(); continue; }

            if (state.world != null && outOfRange(player, state)) {
                // walked away: SCP-079's words are only for the people near it
                if (state.bar != null) player.hideBossBar(state.bar);
                it.remove();
                continue;
            }
            if (state.thinking) {
                state.thinkingTicks++;
                String dots = THINKING.get((state.thinkingTicks / THINKING_STEP_TICKS) % THINKING.size());
                // centred on three dots, so the dots appear one after another without moving
                show(player, state, CaptionText.page(List.of("..."), dots.length(), CaptionText.PALETTE_DIM));
                continue;
            }
            if (state.lines.isEmpty()) {
                if (state.bar != null) player.hideBossBar(state.bar);
                it.remove();
                continue;
            }
            speakStep(player, state);
            if (state.revealed < state.length) {
                state.revealed = Math.min(state.length, state.revealed + speed);
                show(player, state, CaptionText.scrolling(state.lines, (int) state.revealed, CaptionText.PALETTE_TEXT));
            } else if (--state.holdLeft <= 0 && state.voice.isEmpty()) {
                // the text stays up until the voice has finished as well
                if (state.bar != null) player.hideBossBar(state.bar);
                it.remove();
            }
        }
        if (states.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }
}
