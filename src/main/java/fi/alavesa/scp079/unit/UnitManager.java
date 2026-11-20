package fi.alavesa.scp079.unit;

import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.render.ActiveModel;
import fi.alavesa.scp079.render.UnitRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The placed units and their entities. Main thread only.
 * <p>
 * The data file is the truth; entities are its picture. Every entity of a unit carries the unit id and the
 * fingerprint of what it was spawned from, and is persistent, so a healthy unit simply comes back with its
 * chunk. Whenever a unit's chunk has its entities loaded (server start, chunk load, reload) the unit is
 * <i>validated</i>: if exactly the expected entities with the current fingerprint are there, nothing
 * happens; otherwise all of its entities are removed and spawned afresh. An entity whose unit no longer
 * exists is removed on sight. That covers duplicates, leftovers of removed units, model edits and crashes.
 */
public final class UnitManager {

    private final Plugin plugin;
    private final UnitKeys keys;
    private final UnitStore store;
    private final UnitRenderer renderer = new UnitRenderer();
    private final Supplier<Settings> settings;
    private final Supplier<ActiveModel> activeModel;
    private final Map<Integer, Unit> units = new ConcurrentHashMap<>();

    public UnitManager(Plugin plugin, UnitKeys keys, UnitStore store, Supplier<Settings> settings, Supplier<ActiveModel> activeModel) {
        this.plugin = plugin;
        this.keys = keys;
        this.store = store;
        this.settings = settings;
        this.activeModel = activeModel;
    }

    public void load() {
        units.clear();
        for (Unit unit : store.load()) units.put(unit.id(), unit);
    }

    public void save() { store.save(sorted()); }

    public Collection<Unit> all() { return units.values(); }

    public List<Unit> sorted() {
        List<Unit> list = new ArrayList<>(units.values());
        list.sort(Comparator.comparingInt(Unit::id));
        return list;
    }

    public Unit get(int id) { return units.get(id); }

    public int count() { return units.size(); }

    // ------------------------------------------------------------------ create / remove

    /** @param at the unit's position: bottom centre of the space it stands in */
    public Unit create(Location at, float yaw) {
        World world = at.getWorld();
        Unit unit = new Unit(store.takeId(), world.getUID(), world.getName(), at.getX(), at.getY(), at.getZ(), yaw);
        units.put(unit.id(), unit);
        save();
        validate(unit);
        return unit;
    }

    /** Removes the unit and, as far as they are loaded, its entities (the rest go when their chunk loads). */
    public void remove(Unit unit) {
        units.remove(unit.id());
        save();
        World world = unit.world();
        if (world != null && entitiesLoaded(world, unit)) for (Entity e : parts(world, unit)) e.remove();
    }

    // ------------------------------------------------------------------ lookup

    /** @return the unit an entity belongs to, or null (also for an entity of a unit that no longer exists) */
    public Unit unitOf(Entity entity) {
        Integer id = keys.unitId(entity);
        return id == null ? null : units.get(id);
    }

    public boolean isOurs(Entity entity) { return keys.unitId(entity) != null; }

    /** @return the closest unit within {@code radius} blocks of {@code at}, or null */
    public Unit nearest(Location at, double radius) {
        Unit best = null;
        double bestDistance = radius * radius;
        for (Unit unit : units.values()) {
            if (!unit.worldId().equals(at.getWorld().getUID())) continue;
            double d = unit.distanceSquared(at);
            if (d <= bestDistance) { bestDistance = d; best = unit; }
        }
        return best;
    }

    // ------------------------------------------------------------------ entities

    private static boolean entitiesLoaded(World world, Unit unit) {
        return world.isChunkLoaded(unit.chunkX(), unit.chunkZ()) && world.getChunkAt(unit.chunkX(), unit.chunkZ()).isEntitiesLoaded();
    }

    /** All loaded entities of a unit. They are spawned on the unit's exact position, so one chunk holds them all. */
    private List<Entity> parts(World world, Unit unit) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.getChunkAt(unit.chunkX(), unit.chunkZ()).getEntities()) {
            Integer id = keys.unitId(e);
            if (id != null && id == unit.id() && e.isValid()) out.add(e);
        }
        return out;
    }

    /** Makes the unit's entities match the current model and settings; a no-op while its chunk is not loaded. */
    public void validate(Unit unit) {
        World world = unit.world();
        if (world == null || !entitiesLoaded(world, unit)) return;
        Settings.Visual visual = settings.get().visual();
        ActiveModel active = activeModel.get();
        String revision = active == null ? "none" : active.fingerprint();

        List<Entity> parts = parts(world, unit);
        boolean healthy = parts.size() == UnitRenderer.expectedEntities(visual, active);
        for (Entity e : parts) {
            if (!healthy) break;
            healthy = revision.equals(e.getPersistentDataContainer().get(keys.revision, PersistentDataType.STRING));
        }
        if (healthy) return;
        for (Entity e : parts) e.remove();
        renderer.spawn(world, unit, visual, active, entity -> keys.tag(entity, unit.id(), revision));
    }

    /** Validates every unit whose chunk is loaded - after a reload, a model edit or a settings change. */
    public void validateAll() {
        for (Unit unit : units.values()) validate(unit);
    }

    /** Removes every loaded entity that belongs to no unit (anymore). */
    public void removeOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClasses(Display.class, Interaction.class)) removeIfOrphan(e);
        }
    }

    private boolean removeIfOrphan(Entity e) {
        Integer id = keys.unitId(e);
        if (id == null || units.containsKey(id)) return false;
        e.remove();
        return true;
    }

    /**
     * A chunk's entities have just been loaded: drop orphans now, and validate the units standing in that
     * chunk on the next tick (entities must not be added to a chunk while it is still being loaded).
     */
    public void onEntitiesLoaded(Chunk chunk, Collection<Entity> entities) {
        for (Entity e : entities) removeIfOrphan(e);
        List<Unit> here = new ArrayList<>();
        for (Unit unit : units.values()) {
            if (unit.worldId().equals(chunk.getWorld().getUID()) && unit.chunkX() == chunk.getX() && unit.chunkZ() == chunk.getZ()) here.add(unit);
        }
        if (here.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Unit unit : here) if (units.containsKey(unit.id())) validate(unit);
        });
    }
}
