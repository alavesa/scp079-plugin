package fi.alavesa.scp079.unit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * One placed SCP-079. The position is the model's origin: the bottom centre of the block space it stands in.
 *
 * @param id        never reused, so a leftover entity of a removed unit can always be told apart
 * @param worldName only for display and for worlds that are not loaded
 * @param yaw       the direction the model's front faces, Minecraft yaw (0 = south, 90 = west)
 */
public record Unit(int id, UUID worldId, String worldName, double x, double y, double z, float yaw) {

    /** @return the world, or null while it is not loaded */
    public World world() { return Bukkit.getWorld(worldId); }

    /** The unit's position with no rotation - where all of its entities are. */
    public Location location(World world) { return new Location(world, x, y, z, 0, 0); }

    public int chunkX() { return ((int) Math.floor(x)) >> 4; }

    public int chunkZ() { return ((int) Math.floor(z)) >> 4; }

    public double distanceSquared(Location l) {
        double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
