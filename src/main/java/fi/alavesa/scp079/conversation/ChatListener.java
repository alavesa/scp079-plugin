package fi.alavesa.scp079.conversation;

import fi.alavesa.scp079.Scp079Plugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Lets SCP-079 hear the chat of players who are in a conversation with it.
 * <p>
 * The message itself is left exactly as it is: it is ordinary chat, sent to everyone and formatted by
 * Minecraft or whatever chat plugin the server runs. This listener only reads it (MONITOR, never cancels or
 * changes anything). The chat event runs off the server thread; the real work is scheduled onto it.
 */
public final class ChatListener implements Listener {

    private final Scp079Plugin plugin;
    private final ConversationManager conversations;

    public ChatListener(Scp079Plugin plugin, ConversationManager conversations) {
        this.plugin = plugin;
        this.conversations = conversations;
    }

    // MONITOR + ignoreCancelled: a message that a mute or chat-filter plugin stopped is not heard by SCP-079 either
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!conversations.isTalking(player.getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> conversations.onChat(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        conversations.onQuit(event.getPlayer());
    }
}
