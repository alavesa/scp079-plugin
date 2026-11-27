package fi.alavesa.scp079.conversation;

import fi.alavesa.scp079.Scp079Plugin;
import fi.alavesa.scp079.config.Messages;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.gemini.GeminiClient;
import fi.alavesa.scp079.gemini.GeminiResult;
import fi.alavesa.scp079.gemini.ReplySanitizer;
import fi.alavesa.scp079.gemini.Safety;
import fi.alavesa.scp079.gemini.ToolRunner;
import fi.alavesa.scp079.gemini.Turn;
import fi.alavesa.scp079.unit.Unit;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who is talking to which unit, what was said, and the proximity behaviour that starts and ends it: a
 * conversation exists exactly while the player stands within the talk radius of a unit.
 * <p>
 * Threads: {@link #tick} and everything that sends messages run on the server thread. {@link #isTalking} is called from the asynchronous chat event and only reads volatile state.
 * Gemini answers arrive on HTTP threads and are handed to the server thread before anything is touched.
 */
public final class ConversationManager {

    /** Leaving is measured a little further out than entering, so standing on the edge does not flicker. */
    private static final double HYSTERESIS = 0.75;
    private static final long LOG_INTERVAL_MILLIS = 30_000;

    private static final class State {
        /** Alternating user / model turns, oldest first, always starting with a user turn. Guarded by {@code this}. */
        final Deque<Turn> history = new ArrayDeque<>();
        /** Unit id of the active conversation, or -1. */
        volatile int activeUnit = -1;
        volatile long lastActivity = System.currentTimeMillis();
        volatile long lastRequest;
        /** Bumped by a memory reset: an answer that was requested before it is thrown away. */
        volatile int generation;
        final AtomicBoolean busy = new AtomicBoolean();
        final Map<Integer, Long> greetedAt = new ConcurrentHashMap<>();
    }

    private final Scp079Plugin plugin;
    private final GeminiClient gemini;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<GeminiResult.Error, Long> lastLogged = new ConcurrentHashMap<>();

    public ConversationManager(Scp079Plugin plugin, GeminiClient gemini) {
        this.plugin = plugin;
        this.gemini = gemini;
    }

    // ------------------------------------------------------------------ proximity

    /** The repeating proximity check. */
    public void tick() {
        drain();
        Settings settings = plugin.settings();
        Settings.Proximity cfg = settings.proximity();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            State state = states.get(player.getUniqueId());
            if (!player.hasPermission(Scp079Plugin.PERMISSION_TALK) || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                if (state != null && state.activeUnit != -1) end(player, state);
                continue;
            }
            Location at = player.getLocation();
            UUID worldId = at.getWorld().getUID();

            if (state != null && state.activeUnit != -1) {
                Unit unit = plugin.units().get(state.activeUnit);
                double leave = cfg.talkRadius() + HYSTERESIS;
                if (unit != null && unit.worldId().equals(worldId) && unit.distanceSquared(at) <= leave * leave) continue; // still talking
                end(player, state);
            }

            Unit nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Unit unit : plugin.units().all()) {
                if (!unit.worldId().equals(worldId)) continue;
                double d = unit.distanceSquared(at);
                if (d < nearestDistance) { nearestDistance = d; nearest = unit; }
            }
            if (nearest != null && nearestDistance <= cfg.talkRadius() * cfg.talkRadius()) start(player, nearest);
        }

        // Memory fades: the history after memory-minutes, the rest of the state once no cooldown needs it.
        long memory = settings.conversation().memoryMillis();
        long keep = Math.max(memory, cfg.greetingCooldownMillis());
        states.entrySet().removeIf(entry -> {
            State s = entry.getValue();
            if (s.activeUnit != -1 || s.busy.get()) return false;
            long idle = now - s.lastActivity;
            if (idle > memory) synchronized (s) { s.history.clear(); }
            long newestCooldown = 0;
            for (long t : s.greetedAt.values()) newestCooldown = Math.max(newestCooldown, t);
            return idle > keep && now - newestCooldown > keep;
        });
    }

    private void start(Player player, Unit unit) {
        Settings settings = plugin.settings();
        State state = states.computeIfAbsent(player.getUniqueId(), id -> new State());
        long now = System.currentTimeMillis();
        state.activeUnit = unit.id();
        state.lastActivity = now;

        Long greeted = state.greetedAt.get(unit.id());
        if (greeted == null || now - greeted >= settings.proximity().greetingCooldownMillis()) {
            state.greetedAt.put(unit.id(), now);
            request(player, state, unit, settings.conversation().greetingPrompt(), true);
        }
        // no "SCP-079 is listening" line: walking back up to the unit is silent until it speaks
    }

    private void end(Player player, State state) {
        plugin.captions().clear(player);
        state.activeUnit = -1;
        state.lastActivity = System.currentTimeMillis();
        // no "out of range" line: SCP-079 just stops talking to a player who walks away
    }

    /** Called when a player leaves the server: the conversation ends, the memory stays for memory-minutes. */
    public void onQuit(Player player) {
        State state = states.get(player.getUniqueId());
        if (state != null) {
            state.activeUnit = -1;
            state.lastActivity = System.currentTimeMillis();
        }
    }

    /** A unit was removed: nobody is talking to it anymore. */
    public void onUnitRemoved(int unitId) {
        for (State state : states.values()) {
            if (state.activeUnit == unitId) state.activeUnit = -1;
            state.greetedAt.remove(unitId);
        }
    }

    // ------------------------------------------------------------------ chat

    /** Thread-safe: is this player's chat currently meant for SCP-079? */
    public boolean isTalking(UUID player) {
        State state = states.get(player);
        return state != null && state.activeUnit != -1;
    }

    /** A chat line of a player in a conversation. Server thread. */
    public void onChat(Player player, String text) {
        Settings settings = plugin.settings();
        State state = states.get(player.getUniqueId());
        if (state == null || state.activeUnit == -1 || !player.isOnline()) return;
        Unit unit = plugin.units().get(state.activeUnit);
        if (unit == null) return;

        String message = Safety.cleanInput(text);
        if (message.isEmpty()) return;
        int max = settings.conversation().maxMessageLength();
        if (message.length() > max) message = message.substring(0, max);

        long now = System.currentTimeMillis();
        if (now - state.lastRequest < settings.gemini().playerCooldownMillis()) {
            speak(player, unit, plain(plugin.messages().component("error.cooldown")), false);
            return;
        }
        state.lastActivity = now;
        request(player, state, unit, message, false);
    }

    // ------------------------------------------------------------------ one message at a time

    /** A chat line that arrived while SCP-079 was busy with someone else. */
    private record Pending(UUID player, int unit, String text) { }

    /** At most this many lines wait their turn; more are told to wait (privately). */
    private static final int MAX_WAITING = 3;
    private final java.util.Deque<Pending> waiting = new ArrayDeque<>();
    /** Requests to Gemini in flight, over all players. Server thread only. */
    private int inFlight;
    /** Until when the line that was just shown is still being typed and read. */
    private long quietUntil;

    /**
     * SCP-079 is one speaker: while it thinks about, types or lets someone read a line, it takes no new one. Greetings of
     * players who walk up meanwhile are dropped (three players arriving get one greeting, not three), chat lines wait in
     * a short queue and are answered one after another.
     */
    private boolean unitBusy() { return inFlight > 0 || System.currentTimeMillis() < quietUntil; }

    private void drain() {
        if (waiting.isEmpty() || unitBusy()) return;
        Pending next = waiting.pollFirst();
        Player player = Bukkit.getPlayer(next.player());
        State state = states.get(next.player());
        Unit unit = plugin.units().get(next.unit());
        if (player == null || state == null || unit == null || state.activeUnit != next.unit()) return; // left in the meantime
        request(player, state, unit, next.text(), false, true);
    }

    /** How long a line occupies the unit: typing it, then the hold the captions use, and a short pause. */
    private long occupiedMillis(String line) {
        Settings.Captions cfg = plugin.settings().captions();
        double typing = line.length() / Math.max(0.1, cfg.charsPerTick());
        // the next line simply replaces this one on screen, so only the typing and a short breath count
        return (long) ((typing + 30) * 50);
    }

    private void request(Player player, State state, Unit unit, String userText, boolean greeting) {
        request(player, state, unit, userText, greeting, false);
    }

    private void request(Player player, State state, Unit unit, String userText, boolean greeting, boolean fromQueue) {
        if (!fromQueue && (unitBusy() || !waiting.isEmpty())) {
            if (greeting) return;
            if (waiting.size() >= MAX_WAITING) player.sendMessage(plugin.messages().component("error.still-thinking"));
            else waiting.addLast(new Pending(player.getUniqueId(), unit.id(), userText));
            return;
        }
        if (!state.busy.compareAndSet(false, true)) {
            if (!greeting) speak(player, unit, plain(plugin.messages().component("error.still-thinking")), false);
            return;
        }
        inFlight++;
        Settings settings = plugin.settings();
        state.lastRequest = System.currentTimeMillis();
        List<Turn> turns;
        int generation;
        synchronized (state) {
            state.history.addLast(Turn.user(userText));
            trim(state.history, settings.conversation().historyTurns());
            turns = new ArrayList<>(state.history);
            generation = state.generation;
        }
        UUID playerId = player.getUniqueId();
        int unitId = unit.id();
        if (captions()) plugin.captions().thinking(player);
        String prompt = plugin.systemPrompt() + plugin.api().promptFor(player) + "\n\n" + Safety.RULES;
        var tools = plugin.api().specsFor(player);
        ToolRunner runner = (name, args) -> {
            CompletableFuture<String> done = new CompletableFuture<>();
            if (!plugin.isEnabled()) { done.complete("error: shutting down"); return done; }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player live = Bukkit.getPlayer(playerId);
                    done.complete(live == null ? "error: the player left" : plugin.api().call(live, name, args));
                });
            } catch (RuntimeException e) {
                done.complete("error: shutting down");
            }
            return done;
        };
        gemini.generate(prompt, turns, tools, runner).thenAccept(result -> {
            // HTTP thread: only hand over. When the plugin is being disabled the scheduler refuses tasks.
            if (!plugin.isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> deliver(playerId, unitId, state, generation, result));
            } catch (RuntimeException ignored) {
                // disabled between the check and the call
            }
        });
    }

    /** Keeps the last {@code maxTurns} exchanges; the history must keep starting with a user turn. */
    static void trim(Deque<Turn> history, int maxTurns) {
        while (history.size() > maxTurns * 2) history.removeFirst();
        while (!history.isEmpty() && !history.peekFirst().role().equals(Turn.USER)) history.removeFirst();
    }

    private void deliver(UUID playerId, int unitId, State state, int generation, GeminiResult result) {
        inFlight = Math.max(0, inFlight - 1);
        state.busy.set(false);
        Settings settings = plugin.settings();
        String reply = result.ok() ? ReplySanitizer.clean(result.text(), settings.conversation().maxSentences()) : "";
        GeminiResult outcome = result.ok() && reply.isEmpty()
            ? new GeminiResult(null, GeminiResult.Error.EMPTY, result.status(), "the reply was empty after clean-up", result.millis())
            : result;
        if (outcome.ok() && plugin.getConfig().getBoolean("safety.filter-replies", true)) {
            Safety.Verdict verdict = Safety.filterReply(reply, Safety.patterns(plugin.getConfig().getStringList("safety.blocked-patterns")));
            if (!verdict.ok()) plugin.getLogger().info("safety filter: " + verdict.reason() + " (the rest of the answer is said)");
            reply = verdict.text();
            if (reply.isEmpty()) outcome = new GeminiResult(null, GeminiResult.Error.EMPTY, result.status(), "nothing was left of the reply after the safety filter", result.millis());
        }

        synchronized (state) {
            if (state.generation != generation) { // the memory was reset while the request was running
                Player waiting = Bukkit.getPlayer(playerId);
                if (waiting != null) plugin.captions().clear(waiting);
                return;
            }
            if (outcome.ok()) {
                state.history.addLast(Turn.model(reply));
            } else if (!state.history.isEmpty() && state.history.peekLast().role().equals(Turn.USER)) {
                state.history.removeLast(); // the unanswered message must not leave two user turns in a row
            }
        }
        if (!outcome.ok()) logFailure(outcome);

        Player player = Bukkit.getPlayer(playerId);
        Unit unit = plugin.units().get(unitId);
        if (player == null) return;
        if (unit == null || state.activeUnit != unitId) {
            plugin.captions().clear(player); // walked away (or the unit is gone) while SCP-079 was thinking
            return;
        }
        if (outcome.ok()) {
            speak(player, unit, reply, true);
        } else {
            speak(player, unit, plain(plugin.messages().component(outcome.error().messageKey())), false);
            if (player.hasPermission(Scp079Plugin.PERMISSION_ADMIN)) {
                plugin.messages().send(player, "admin.error-detail", Messages.text("detail", outcome.detail()));
            }
        }
    }

    /** The console gets every kind of failure, but not a line per player message while Gemini is down. */
    private void logFailure(GeminiResult result) {
        long now = System.currentTimeMillis();
        Long last = lastLogged.get(result.error());
        if (last != null && now - last < LOG_INTERVAL_MILLIS) return;
        lastLogged.put(result.error(), now);
        plugin.getLogger().warning("Gemini request failed (" + result.error() + "): " + result.detail());
    }

    // ------------------------------------------------------------------ output

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Captions go to every player, with or without the pack on their client. */
    private boolean captions() {
        return plugin.settings().captions().enabled() && plugin.delivery().captionsReady();
    }

    /**
     * SCP-079 says something. Everyone within the talk radius of the unit gets it as a caption above the hotbar
     * (and hears it, see voice), and in chat according to captions.in-chat. Nobody further away sees it, unless
     * chat.global is switched on (off by default since 1.3.0).
     *
     * @param line   plain text; empty = nothing (a message blanked out in messages.yml)
     * @param typed  for chat only: wait the typing delay first (captions type themselves out)
     */
    private void speak(Player player, Unit unit, String line, boolean typed) {
        if (line.isBlank()) {
            plugin.captions().clear(player);
            return;
        }
        quietUntil = Math.max(quietUntil, System.currentTimeMillis() + occupiedMillis(line));
        Settings settings = plugin.settings();
        Settings.Chat chat = settings.chat();
        Sound sound = chat.sound() == null ? null : Sound.sound(chat.sound(), Sound.Source.MASTER, chat.soundVolume(), chat.soundPitch());
        List<Player> audience = new ArrayList<>();
        World world = unit.world();
        if (world == null) {
            audience.add(player);
        } else {
            double radius = settings.proximity().talkRadius() + HYSTERESIS;
            // only players near the unit: one who walked away before the reply came does not get it either
            for (Player other : world.getPlayers()) if (unit.distanceSquared(other.getLocation()) <= radius * radius) audience.add(other);
        }
        Component chatLine = Messages.format(chat.format(), Placeholder.unparsed("message", line));
        int delay = typed && chat.typingDelay() ? (int) Math.min(chat.typingMaxTicks(), chat.typingBaseTicks() + line.length() * chat.typingTicksPerChar()) : 0;
        for (Player listener : audience) {
            boolean caption = captions();
            if (caption) plugin.captions().say(listener, line, sound, unit.x(), unit.y() + 0.5, unit.z());
            Settings.InChat inChat = settings.captions().inChat();
            // without the pack's font a caption is unreadable boxes: those players need the words in chat
            boolean readable = caption && plugin.delivery().packLoaded(listener);
            // with captions switched off, chat is the only way the line is heard
            if (!caption || inChat == Settings.InChat.ALWAYS || (!readable && inChat == Settings.InChat.FALLBACK)) {
                Runnable say = () -> {
                    if (!listener.isOnline()) return;
                    listener.sendMessage(chatLine);
                    if (!caption && sound != null) listener.playSound(sound, unit.x(), unit.y() + 0.5, unit.z());
                };
                if (delay <= 0) say.run();
                else Bukkit.getScheduler().runTaskLater(plugin, say, delay);
            }
        }
        if (chat.global()) {
            Runnable sayToEveryoneElse = () -> {
                for (Player other : Bukkit.getOnlinePlayers()) if (!audience.contains(other)) other.sendMessage(chatLine);
                Bukkit.getConsoleSender().sendMessage(chatLine);
            };
            if (delay <= 0) sayToEveryoneElse.run();
            else Bukkit.getScheduler().runTaskLater(plugin, sayToEveryoneElse, delay);
        }
    }

    // ------------------------------------------------------------------ memory

    /** @return false when there was nothing to forget */
    public boolean reset(UUID player) {
        State state = states.get(player);
        if (state == null) return false;
        synchronized (state) {
            state.history.clear();
            state.generation++;
        }
        state.greetedAt.clear();
        return true;
    }

    public int resetAll() {
        int count = 0;
        for (UUID id : new ArrayList<>(states.keySet())) if (reset(id)) count++;
        return count;
    }

    public void clear() {
        states.clear();
        waiting.clear();
        inFlight = 0;
        quietUntil = 0;
    }
}
