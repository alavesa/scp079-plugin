package fi.alavesa.scp079.unit;

import fi.alavesa.scp079.config.Messages;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.render.ActiveModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The placeable SCP-079 item. It is recognised only by a marker in its persistent data - never by name or
 * material - so nothing renamed in an anvil can pass for it.
 */
public final class UnitItems {

    private final UnitKeys keys;
    private final Supplier<Settings> settings;
    private final Supplier<ActiveModel> activeModel;

    public UnitItems(UnitKeys keys, Supplier<Settings> settings, Supplier<ActiveModel> activeModel) {
        this.keys = keys;
        this.settings = settings;
        this.activeModel = activeModel;
    }

    public ItemStack create() {
        Settings.Units cfg = settings.get().units();
        ItemStack stack = new ItemStack(cfg.itemMaterial());
        ItemMeta meta = stack.getItemMeta();
        meta.customName(plain(Messages.parse(cfg.itemName())));
        if (!cfg.itemLore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : cfg.itemLore()) lore.add(plain(Messages.parse(line)));
            meta.lore(lore);
        }
        ActiveModel active = activeModel.get();
        if (cfg.itemUsesPackModel() && active != null) meta.setItemModel(new NamespacedKey(Settings.NAMESPACE, active.id()));
        meta.getPersistentDataContainer().set(keys.item, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean isUnitItem(ItemStack stack) { return keys.isUnitItem(stack); }

    /** Item names and lore are italic by default; switch that off unless the text asks for it. */
    private static Component plain(Component c) { return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE); }
}
