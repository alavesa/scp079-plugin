package fi.alavesa.scp079.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.function.Function;

/**
 * What other plugins can give SCP-079: things it can DO (tools, called by Gemini through function calling),
 * things it KNOWS (notes) and things it is TOLD every time it answers (context). Fetched through Bukkit's
 * services manager:
 * <pre>{@code
 * Scp079Api api = Bukkit.getServicesManager().load(Scp079Api.class);
 * }</pre>
 * Everything runs on the server thread. Registrations belong to a plugin and disappear with
 * {@link #unregisterAll(Plugin)} (call it from onDisable).
 */
public interface Scp079Api {

    /** What a tool does. Runs on the server thread; the returned text goes back to Gemini as the tool's result. */
    @FunctionalInterface
    interface ToolHandler {
        String call(Player player, Map<String, String> arguments);
    }

    /**
     * @param name        lower_snake_case function name
     * @param description what it does and when to use it, for the model
     * @param parameters  argument name -> description; every argument is a required string
     * @param available   false = the tool is not offered to this player's conversation
     */
    record Tool(String name, String description, Map<String, String> parameters, Function<Player, Boolean> available, ToolHandler handler) {
        public Tool(String name, String description, Map<String, String> parameters, ToolHandler handler) {
            this(name, description, parameters, p -> true, handler);
        }
    }

    void registerTool(Plugin owner, Tool tool);

    /** Text added to the system prompt of every request (null or blank = nothing). Called on the server thread. */
    void registerContext(Plugin owner, Function<Player, String> provider);

    /**
     * SCP-079 learns something: {@code text} is kept under {@code key} (a newer note with the same key replaces
     * it) and shown to the model with how long ago it was written, until {@code memory.note-minutes} pass.
     */
    void note(Plugin owner, String key, String text);

    void forgetNote(String key);

    void unregisterAll(Plugin owner);

    /**
     * SCP-079 speaks on the intercom (the Intercom plugin's banner, signed by SCP-079). For tools that act on the AI's
     * behalf: the text is cleaned like any answer (no links, commands, pings...), and Intercom's announcement cooldown
     * (its {@code cooldown-seconds}) applies to SCP-079 as one sender.
     *
     * @return null when it went out, else why not (a sentence for the model)
     */
    String announce(String text);

    /** Is there at least one unit placed? Tools usually only matter when SCP-079 exists. */
    boolean hasUnits();
}
