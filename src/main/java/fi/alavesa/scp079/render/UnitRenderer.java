package fi.alavesa.scp079.render;

import fi.alavesa.scp079.bbmodel.ModelGeometry;
import fi.alavesa.scp079.config.Settings;
import fi.alavesa.scp079.unit.Unit;
import fi.alavesa.scp079.util.Affine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spawns the entities of one unit: the model (block displays or one item display) and the Interaction that
 * is its hitbox. Main thread only.
 *
 * <h2>From model space to the world</h2>
 * Model space is Blockbench's: pixels, +x east, +y up, +z south, the model's FRONT towards north (-z).
 * A unit facing yaw {@code f} must show that front along the yaw's direction {@code (-sin f, 0, cos f)}.
 * {@code Ry(a)} turns (0,0,-1) into {@code (-sin a, 0, -cos a)}, so {@code a = 180 - f}, and a model point
 * {@code p} lands at
 * <pre>   world = unit + Ry(180 - f) * (p - origin) * scale / 16 </pre>
 * All entities sit exactly on the unit's position with no entity rotation; everything above goes into each
 * display's Transformation, which the client applies as {@code T(translation) * R(left) * S(scale) * R(right)}.
 *
 * <h2>LIVE_CUBES</h2>
 * A block display draws its block in the unit cube 0..1. A {@link CubePlanner.Part} is the box
 * {@code min..max} in the frame {@code R}: its points are {@code R * (min + size * u)}, {@code u} in 0..1.
 * Substituting gives, with {@code Y = Ry(180 - f)} and {@code k = scale / 16}:
 * <pre>   translation = Y * (R * min - origin) * k      left = Y * R      scale = size * k      right = identity </pre>
 * The left rotation is a free quaternion, so any combination of group and cube rotations is exact.
 *
 * <h2>PACK_MODEL</h2>
 * The item model's elements are {@code g = 8 + fit * (p - c)} (see {@link ModelGeometry}); with the display
 * context NONE the client draws them at {@code (g - 8) / 16} and - for item displays only - turned by 180
 * degrees about y. So {@code left = Ry(-f)} (the client's half turn completes it to Y),
 * {@code scale = scale / fit} and {@code translation = Y * (c - origin) * k}.
 */
public final class UnitRenderer {

    private final Map<String, BlockData> blockData = new HashMap<>();

    /**
     * @param tagger marks every spawned entity as part of the unit; called before the entity enters the world
     * @return number of entities spawned
     */
    public int spawn(World world, Unit unit, Settings.Visual visual, ActiveModel active, Consumer<Entity> tagger) {
        Location at = unit.location(world);
        int count = 1;
        if (active != null) {
            float yaw = unit.yaw() + (float) visual.rotationOffset();
            count += visual.renderMode() == Settings.RenderMode.PACK_MODEL
                ? spawnPackModel(world, at, yaw, visual, active, tagger)
                : spawnCubes(world, at, yaw, visual, active, tagger);
        }
        spawnHitbox(world, at, visual, active, tagger);
        return count;
    }

    /** How many entities {@link #spawn} creates for this model and mode - what a healthy unit consists of. */
    public static int expectedEntities(Settings.Visual visual, ActiveModel active) {
        if (active == null) return 1;
        return 1 + (visual.renderMode() == Settings.RenderMode.PACK_MODEL ? 1 : active.plan().parts().size());
    }

    private int spawnCubes(World world, Location at, float yaw, Settings.Visual visual, ActiveModel active, Consumer<Entity> tagger) {
        Affine turn = Affine.rotationY(180 - yaw);
        double k = visual.scale() / 16;
        double[] origin = active.geometry().origin;
        for (CubePlanner.Part part : active.plan().parts()) {
            double[] corner = part.rotation().apply(part.min());
            double[] t = turn.applyDirection(new double[] {corner[0] - origin[0], corner[1] - origin[1], corner[2] - origin[2]});
            Transformation transformation = new Transformation(
                new Vector3f((float) (t[0] * k), (float) (t[1] * k), (float) (t[2] * k)),
                quaternion(turn.mul(part.rotation())),
                new Vector3f((float) ((part.max()[0] - part.min()[0]) * k), (float) ((part.max()[1] - part.min()[1]) * k), (float) ((part.max()[2] - part.min()[2]) * k)),
                new Quaternionf());
            BlockData data = blockData.computeIfAbsent(part.block(), UnitRenderer::createBlockData);
            world.spawn(at, BlockDisplay.class, display -> {
                tagger.accept(display);
                display.setBlock(data);
                display.setTransformation(transformation);
                applyCommon(display, visual, part.emissive());
            });
        }
        return active.plan().parts().size();
    }

    private int spawnPackModel(World world, Location at, float yaw, Settings.Visual visual, ActiveModel active, Consumer<Entity> tagger) {
        ModelGeometry g = active.geometry();
        double k = visual.scale() / 16;
        double[] t = Affine.rotationY(180 - yaw).applyDirection(new double[] {g.fitCenter[0] - g.origin[0], g.fitCenter[1] - g.origin[1], g.fitCenter[2] - g.origin[2]});
        Transformation transformation = new Transformation(
            new Vector3f((float) (t[0] * k), (float) (t[1] * k), (float) (t[2] * k)),
            new Quaternionf().rotationY((float) Math.toRadians(-yaw)),
            new Vector3f((float) (visual.scale() / g.fitScale)),
            new Quaternionf());
        world.spawn(at, ItemDisplay.class, display -> {
            tagger.accept(display);
            display.setItemStack(packModelStack(active.id()));
            // NONE: the model's own display transforms (meant for hands and inventories) are not applied
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setTransformation(transformation);
            applyCommon(display, visual, false);
        });
        return 1;
    }

    private static void applyCommon(Display display, Settings.Visual visual, boolean emissive) {
        display.setPersistent(true);
        display.setInvulnerable(true);
        display.setViewRange(visual.viewRange());
        display.setShadowRadius(0);
        display.setShadowStrength(0);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        int light = emissive ? visual.emissiveBrightness() : visual.brightness();
        if (light >= 0) display.setBrightness(new Display.Brightness(light, light));
    }

    private void spawnHitbox(World world, Location at, Settings.Visual visual, ActiveModel active, Consumer<Entity> tagger) {
        float width = 1, height = 1;
        if (active != null) {
            ModelGeometry g = active.geometry();
            double k = visual.scale() / 16;
            // An Interaction is an upright square prism centred on the entity; take the larger horizontal
            // half-extent so that it covers the model whichever way the unit faces.
            double reach = 0;
            for (int axis = 0; axis <= 2; axis += 2) {
                reach = Math.max(reach, Math.abs(g.bounds.min()[axis] - g.origin[axis]));
                reach = Math.max(reach, Math.abs(g.bounds.max()[axis] - g.origin[axis]));
            }
            width = (float) Math.max(0.4, Math.min(8, reach * 2 * k));
            height = (float) Math.max(0.4, Math.min(8, (g.bounds.max()[1] - g.origin[1]) * k));
        }
        float w = width, h = height;
        world.spawn(at, Interaction.class, hitbox -> {
            tagger.accept(hitbox);
            hitbox.setInteractionWidth(w);
            hitbox.setInteractionHeight(h);
            hitbox.setResponsive(true); // the client swings its arm on click, like on a block
            hitbox.setPersistent(true);
            hitbox.setInvulnerable(true);
        });
    }

    /** The stack an item display (or the placeable item) uses to show {@code scp079:<model id>}. */
    public static ItemStack packModelStack(String modelId) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey(Settings.NAMESPACE, modelId));
        stack.setItemMeta(meta);
        return stack;
    }

    private static BlockData createBlockData(String block) {
        Material material = Material.matchMaterial(block);
        // palette entries are validated when the config is read; this only guards a hand-made Palette
        return Bukkit.createBlockData(material != null && material.isBlock() ? material : Material.GRAY_CONCRETE);
    }

    /** Rotation matrix (double, row/column) -> JOML quaternion. JOML's {@code mCR} is column C, row R. */
    static Quaternionf quaternion(Affine r) {
        Matrix3f m = new Matrix3f(
            (float) r.get(0, 0), (float) r.get(1, 0), (float) r.get(2, 0),
            (float) r.get(0, 1), (float) r.get(1, 1), (float) r.get(2, 1),
            (float) r.get(0, 2), (float) r.get(1, 2), (float) r.get(2, 2));
        return new Quaternionf().setFromNormalized(m);
    }
}
