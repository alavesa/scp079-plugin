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
 * Routes the chat of players who are in a conversation to SCP-079.
 * <p>
 * The chat event runs off the server thread. Nothing here touches the world: it reads one volatile flag,
 * optionally cancels the event, and schedules the real work on the server thread.
 */
public final class ChatListener implements Listener {

    private final Scp079Plugin plugin;
    private final ConversationManager conversations;

    public ChatListener(Scp079Plugin plugin, ConversationManager conversations) {
        this.plugin = plugin;
        this.conversations = conversations;
    }

    // HIGH + ignoreCancelled: a mute or chat-filter plugin that cancelled the message keeps the last word
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!conversations.isTalking(player.getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plugin.settings().chat().isPrivate()) event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> conversations.onChat(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        conversations.onQuit(event.getPlayer());
    }
}
