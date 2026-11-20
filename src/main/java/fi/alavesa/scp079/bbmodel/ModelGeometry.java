package fi.alavesa.scp079.bbmodel;

import fi.alavesa.scp079.util.Affine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link BbModel} with its group hierarchy flattened: every cube carries its final (world) transform.
 * Shared by both render modes - LIVE_CUBES turns each {@link Element#world} into a block display
 * transformation, the pack generator writes each element into the item model JSON.
 * Everything is in Blockbench pixels and Blockbench axes (16 px = 1 block, front = north = -Z).
 *
 * <h2>Flattening groups</h2>
 * A group rotates its content about its pivot: {@code G = T(origin) * R(rotation) * T(-origin)}, and groups
 * nest, so a cube's world transform is {@code M = G_root * ... * G_parent * C} with the cube's own
 * {@code C = T(origin) * R(rotation) * T(-origin)}. {@code R} is Blockbench's Euler order
 * {@code Rz * Ry * Rx} (see {@link Affine#rotationZYX}).
 * <p>
 * A Java model element can only express "an axis-aligned box, rotated about one pivot". Any rigid transform
 * M can be written that way: take the cube's own origin {@code o}, let {@code p = M * o} be where it ends
 * up, slide the unrotated box by {@code p - o} and rotate it about {@code p} by the rotation part of M:
 * <pre>   R(x + (p - o) - p) + p  =  R(x - o) + p  =  M * x </pre>
 *
 * <h2>The fit (pack model only)</h2>
 * Element coordinates must stay within -16..32 (a 48 px cube centred on 8,8,8). The unrotated boxes are
 * recentred on {@link #fitCenter} and, if still too big, shrunk by {@link #fitScale}; the placed
 * item_display undoes both with its own (unlimited) translation and scale.
 */
public final class ModelGeometry {

    /** Half the allowed coordinate range of a model element: -16..32 is 8 +- 24. */
    public static final double ELEMENT_HALF_RANGE = 24;

    /** An axis-aligned box, {@code min}/{@code max} as {@code [x, y, z]}. */
    public record Box(double[] min, double[] max) {
        public double size(int axis) { return max[axis] - min[axis]; }
        public double center(int axis) { return (min[axis] + max[axis]) / 2; }
    }

    /**
     * One cube, flattened.
     *
     * @param world    the cube's full rigid transform: a point {@code x} of the unrotated box
     *                 ({@code cube.from - inflate .. cube.to + inflate}) ends up at {@code world * x}
     * @param from     corner of the box BEFORE rotation, already slid to its final place (item-model form)
     * @param to       opposite corner
     * @param pivot    the point the slid box is rotated about
     * @param rotation world rotation as Blockbench-order Euler angles {@code [x, y, z]}, degrees
     */
    public record Element(BbModel.Cube cube, Affine world, double[] from, double[] to, double[] pivot, double[] rotation) { }

    public final List<Element> elements = new ArrayList<>();
    /** Bounds of the visible, rotated geometry. */
    public Box bounds;
    /** The point of the model that is put on the unit's position (bottom centre of the block it stands on). */
    public double[] origin;
    public double[] fitCenter;
    /** 1 when the model fits a Java item model as it is, below 1 when it had to be shrunk. */
    public double fitScale = 1;

    private ModelGeometry() { }

    public static ModelGeometry of(BbModel model) throws ModelException {
        ModelGeometry g = new ModelGeometry();
        Map<BbModel.Group, Affine> groupTransforms = new HashMap<>();

        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        double[] preLo = lo.clone(), preHi = hi.clone();
        for (BbModel.Cube cube : model.cubes) {
            if (!cube.visible) continue;
            Affine world = world(cube.group, groupTransforms).mul(Affine.rotationAbout(cube.origin, cube.rotation));
            double[] pivot = world.apply(cube.origin);
            double[] from = new double[3], to = new double[3];
            for (int i = 0; i < 3; i++) {
                double slide = pivot[i] - cube.origin[i];
                from[i] = cube.from[i] - cube.inflate + slide;
                to[i] = cube.to[i] + cube.inflate + slide;
            }
            double[] rotation = world.isRotationIdentity(1e-9) ? new double[] {0, 0, 0} : world.eulerZYX();
            g.elements.add(new Element(cube, world, from, to, pivot, rotation));
            grow(preLo, preHi, from);
            grow(preLo, preHi, to);
            for (int corner = 0; corner < 8; corner++) grow(lo, hi, world.apply(corner(cube.from, cube.to, cube.inflate, corner)));
        }
        if (g.elements.isEmpty()) throw new ModelException("the model has no visible cube elements");
        g.bounds = new Box(lo, hi);

        // Java block/item projects are built inside the 0..16 block, whose floor centre is (8, 0, 8);
        // every other Blockbench format has its ground centre at the world origin.
        g.origin = model.format.toLowerCase(Locale.ROOT).startsWith("java") ? new double[] {8, 0, 8} : new double[] {0, 0, 0};

        g.fitCenter = new double[] {(preLo[0] + preHi[0]) / 2, (preLo[1] + preHi[1]) / 2, (preLo[2] + preHi[2]) / 2};
        double halfExtent = 0;
        for (int i = 0; i < 3; i++) halfExtent = Math.max(halfExtent, (preHi[i] - preLo[i]) / 2);
        g.fitScale = halfExtent > ELEMENT_HALF_RANGE ? ELEMENT_HALF_RANGE / halfExtent : 1;
        return g;
    }

    /** Model pixels -> the coordinate written into the item model JSON (recentred on 8,8,8 and shrunk to fit). */
    public double[] toElementSpace(double[] px) {
        return new double[] {8 + fitScale * (px[0] - fitCenter[0]), 8 + fitScale * (px[1] - fitCenter[1]), 8 + fitScale * (px[2] - fitCenter[2])};
    }

    private static Affine world(BbModel.Group group, Map<BbModel.Group, Affine> cache) {
        if (group == null) return Affine.IDENTITY;
        Affine cached = cache.get(group);
        if (cached != null) return cached;
        Affine world = world(group.parent, cache).mul(Affine.rotationAbout(group.origin, group.rotation));
        cache.put(group, world);
        return world;
    }

    private static double[] corner(double[] from, double[] to, double inflate, int corner) {
        return new double[] {
            (corner & 1) == 0 ? from[0] - inflate : to[0] + inflate,
            (corner & 2) == 0 ? from[1] - inflate : to[1] + inflate,
            (corner & 4) == 0 ? from[2] - inflate : to[2] + inflate};
    }

    private static void grow(double[] lo, double[] hi, double[] p) {
        for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p[i]); hi[i] = Math.max(hi[i], p[i]); }
    }
}
