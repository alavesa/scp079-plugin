package fi.alavesa.scp079.api;

import fi.alavesa.scp079.gemini.ToolSpec;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The registry behind {@link Scp079Api}. Server thread only. */
public final class ApiImpl implements Scp079Api {

    private record Owned<T>(Plugin owner, T value) { }
    private record Note(Plugin owner, String text, long at) { }

    private final Map<String, Owned<Tool>> tools = new LinkedHashMap<>();
    private final List<Owned<Function<Player, String>>> contexts = new ArrayList<>();
    private final Map<String, Note> notes = new LinkedHashMap<>();
    private final java.util.function.IntSupplier unitCount;
    private final java.util.function.IntSupplier noteMinutes;

    public ApiImpl(java.util.function.IntSupplier unitCount, java.util.function.IntSupplier noteMinutes) {
        this.unitCount = unitCount;
        this.noteMinutes = noteMinutes;
    }

    @Override public void registerTool(Plugin owner, Tool tool) { tools.put(tool.name(), new Owned<>(owner, tool)); }

    @Override public void registerContext(Plugin owner, Function<Player, String> provider) { contexts.add(new Owned<>(owner, provider)); }

    @Override public void note(Plugin owner, String key, String text) { notes.put(key, new Note(owner, text, System.currentTimeMillis())); }

    @Override public void forgetNote(String key) { notes.remove(key); }

    @Override
    public void unregisterAll(Plugin owner) {
        tools.values().removeIf(o -> o.owner() == owner);
        contexts.removeIf(o -> o.owner() == owner);
        notes.values().removeIf(n -> n.owner() == owner);
    }

    @Override public boolean hasUnits() { return unitCount.getAsInt() > 0; }

    /** The tools offered to this player's conversation. */
    public List<ToolSpec> specsFor(Player player) {
        List<ToolSpec> out = new ArrayList<>();
        for (Owned<Tool> o : tools.values()) {
            boolean ok;
            try { ok = Boolean.TRUE.equals(o.value().available().apply(player)); } catch (RuntimeException e) { ok = false; }
            if (ok) out.add(new ToolSpec(o.value().name(), o.value().description(), o.value().parameters()));
        }
        return out;
    }

    /** Runs a tool the model asked for; never throws. */
    public String call(Player player, String name, Map<String, String> arguments) {
        Owned<Tool> tool = tools.get(name);
        if (tool == null) return "error: there is no tool called " + name;
        try {
            String result = tool.value().handler().call(player, arguments);
            return result == null ? "done" : result;
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Context lines + notes, appended to the system prompt. Empty when there is nothing. */
    public String promptFor(Player player) {
        StringBuilder out = new StringBuilder();
        for (Owned<Function<Player, String>> c : contexts) {
            String text;
            try { text = c.value().apply(player); } catch (RuntimeException e) { continue; }
            if (text != null && !text.isBlank()) out.append("\n\n").append(text.strip());
        }
        long now = System.currentTimeMillis(), max = noteMinutes.getAsInt() * 60_000L;
        notes.values().removeIf(n -> now - n.at() > max);
        if (!notes.isEmpty()) {
            out.append("\n\nWhat you currently know from your sensors and systems (the time is how long ago you learned it):");
            for (Note n : notes.values()) out.append("\n- ").append(age(now - n.at())).append(": ").append(n.text());
        }
        return out.toString();
    }

    static String age(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 5) return "just now";
        if (s < 90) return s + " seconds ago";
        return (s / 60) + " minutes ago";
    }
}
