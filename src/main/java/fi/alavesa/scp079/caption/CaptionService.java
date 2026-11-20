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
        List<List<String>> pages = List.of();
        int page, pageLength;
        /** Characters of the current page shown so far (fractional: characters-per-tick need not be whole). */
        double revealed;
        int holdLeft;
        boolean thinking;
        int thinkingTicks;
        Sound sound;
        double x, y, z;
    }

    private final Scp079Plugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;

    public CaptionService(Scp079Plugin plugin) { this.plugin = plugin; }

    /** Shows cycling dots until {@link #say} or {@link #clear} is called. */
    public void thinking(Player player) {
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        if (!state.pages.isEmpty()) return; // still saying something: let it finish
        state.thinking = true;
        state.thinkingTicks = 0;
        show(player, state, CaptionText.page(List.of("..."), 1, CaptionText.PALETTE_DIM));
        ensureTask();
    }

    /**
     * Types {@code text} out, page by page.
     *
     * @param sound played at the start of every page at the given position; may be null
     */
    public void say(Player player, String text, Sound sound, double x, double y, double z) {
        List<List<String>> pages = CaptionText.pages(text);
        if (pages.isEmpty()) return;
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        state.thinking = false;
        state.pages = pages;
        state.sound = sound;
        state.x = x;
        state.y = y;
        state.z = z;
        startPage(player, state, 0);
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

    private void startPage(Player player, State state, int page) {
        state.page = page;
        state.revealed = 0;
        state.pageLength = 0;
        for (String line : state.pages.get(page)) state.pageLength += line.length();
        Settings.Captions cfg = plugin.settings().captions();
        state.holdLeft = (int) Math.min(cfg.holdMaxTicks(), cfg.holdBaseTicks() + state.pageLength * cfg.holdTicksPerChar());
        if (state.sound != null) player.playSound(state.sound, state.x, state.y, state.z);
        show(player, state, CaptionText.page(state.pages.get(page), 0, CaptionText.PALETTE_TEXT));
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
            if (state.pages.isEmpty()) {
                if (state.bar != null) player.hideBossBar(state.bar);
                it.remove();
                continue;
            }
            if (state.revealed < state.pageLength) {
                state.revealed = Math.min(state.pageLength, state.revealed + speed);
                show(player, state, CaptionText.page(state.pages.get(state.page), (int) state.revealed, CaptionText.PALETTE_TEXT));
            } else if (--state.holdLeft <= 0) {
                if (state.page + 1 < state.pages.size()) {
                    startPage(player, state, state.page + 1);
                } else {
                    if (state.bar != null) player.hideBossBar(state.bar);
                    it.remove();
                }
            }
        }
        if (states.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }
}
