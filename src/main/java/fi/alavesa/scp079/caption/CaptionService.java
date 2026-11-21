package fi.alavesa.scp079.caption;

import fi.alavesa.scp079.Scp079Plugin;
import fi.alavesa.scp079.config.Settings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCP-079's captions: a box of text just above the hotbar, typed out letter by letter as if it were speaking.
 * A reply longer than the box scrolls up line by line while it is typed - one caption, without pauses.
 * <p>
 * A caption is a boss bar per player. Its name is the whole caption, laid out by {@link CaptionText}; the
 * pack's text shader moves it from the top of the screen to above the hotbar and the pack blanks the blue bar
 * graphic. Only players whose client has the pack (see {@link fi.alavesa.scp079.pack.PackDelivery#captionsReady})
 * get captions - without the shader the name would be unreadable glyphs at the top of the screen.
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
    }

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

    /** Removes the player's caption at once. */
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

    private void tick() {
        double speed = plugin.settings().captions().charsPerTick();
        for (Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, State> entry = it.next();
            State state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) { it.remove(); continue; }

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
            if (state.revealed < state.length) {
                state.revealed = Math.min(state.length, state.revealed + speed);
                show(player, state, CaptionText.scrolling(state.lines, (int) state.revealed, CaptionText.PALETTE_TEXT));
            } else if (--state.holdLeft <= 0) {
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
