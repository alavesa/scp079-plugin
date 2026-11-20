package fi.alavesa.scp079.unit;

import fi.alavesa.scp079.Scp079Plugin;
import fi.alavesa.scp079.config.Messages;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Placing the item, punching a unit, and units coming back with their chunks. Right-clicking a unit does nothing. */
public final class UnitListener implements Listener {

    private final Scp079Plugin plugin;
    /** Tick of each player's last handled click, so main hand and off hand cannot both act on one click. */
    private final Map<UUID, Integer> lastClick = new HashMap<>();

    public UnitListener(Scp079Plugin plugin) { this.plugin = plugin; }

    private boolean firstClickThisTick(Player player) {
        Integer last = lastClick.put(player.getUniqueId(), Bukkit.getCurrentTick());
        return last == null || last != Bukkit.getCurrentTick();
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) return;
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();
        if (clicked == null || !plugin.items().isUnitItem(item)) return;
        if (event.useItemInHand() == Event.Result.DENY) return; // another plugin already said no

        // from here on the click is ours: the base item (a block, by default) must not be placed
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (!firstClickThisTick(player)) return;
        if (!player.hasPermission(Scp079Plugin.PERMISSION_ADMIN)) {
            plugin.messages().send(player, "place.no-permission");
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        Location at = target.getLocation().add(0.5, 0, 0.5);
        Unit unit = plugin.placeUnit(at, player);
        if (player.getGameMode() != GameMode.CREATIVE) item.subtract();
        plugin.messages().send(player, "place.done", Messages.text("id", unit.id()));
    }

    /** Belt and braces: whatever happens to the interact event, the item's base block never ends up in the world. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.items().isUnitItem(event.getItemInHand())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ clicking

    /** Fired for a left-click on any entity before damage is worked out - the only attack signal an Interaction gives. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLeftClick(PrePlayerAttackEntityEvent event) {
        Entity attacked = event.getAttacked();
        if (!plugin.units().isOurs(attacked)) return;
        event.setCancelled(true);
        Unit unit = plugin.units().unitOf(attacked);
        if (unit == null) { attacked.remove(); return; }
        Player player = event.getPlayer();
        if (!plugin.settings().units().punchToRemove() || !player.hasPermission(Scp079Plugin.PERMISSION_ADMIN)) return;
        if (!firstClickThisTick(player)) return;
        plugin.removeUnit(unit);
        if (plugin.settings().units().dropItem() && player.getGameMode() != GameMode.CREATIVE) {
            player.getWorld().dropItemNaturally(unit.location(player.getWorld()).add(0, 0.5, 0), plugin.items().create());
        }
        plugin.messages().send(player, "remove.done", Messages.text("id", unit.id()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.units().isOurs(event.getEntity())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Entities are loaded separately from blocks, so this (not ChunkLoadEvent) is when a unit comes back. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        plugin.units().onEntitiesLoaded(event.getChunk(), event.getEntities());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }
}
