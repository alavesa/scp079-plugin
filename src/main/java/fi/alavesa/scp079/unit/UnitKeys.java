package fi.alavesa.scp079.unit;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** The persistent-data keys that mark the plugin's entities and its item. */
public final class UnitKeys {

    /** INTEGER on every entity of a unit (displays and the hitbox): the unit id. */
    public final NamespacedKey unit;
    /** STRING on every entity: fingerprint of the model + settings it was spawned from. */
    public final NamespacedKey revision;
    /** BYTE on the placeable item. */
    public final NamespacedKey item;

    public UnitKeys(Plugin plugin) {
        unit = new NamespacedKey(plugin, "unit");
        revision = new NamespacedKey(plugin, "revision");
        item = new NamespacedKey(plugin, "item");
    }

    /** @return the id of the unit this entity belongs to, or null if it is not ours */
    public Integer unitId(Entity entity) {
        return entity.getPersistentDataContainer().get(unit, PersistentDataType.INTEGER);
    }

    public void tag(Entity entity, int unitId, String revisionValue) {
        entity.getPersistentDataContainer().set(unit, PersistentDataType.INTEGER, unitId);
        entity.getPersistentDataContainer().set(revision, PersistentDataType.STRING, revisionValue);
    }

    public boolean isUnitItem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(item, PersistentDataType.BYTE);
    }
}
