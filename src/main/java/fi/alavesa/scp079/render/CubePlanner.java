package fi.alavesa.scp079.render;

import fi.alavesa.scp079.bbmodel.BbModel;
import fi.alavesa.scp079.bbmodel.ModelGeometry;
import fi.alavesa.scp079.bbmodel.ModelLog;
import fi.alavesa.scp079.util.Affine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BBModel -> the list of block displays LIVE_CUBES mode spawns. Pure (no Bukkit), so it runs off the main
 * thread and in unit tests.
 *
 * <h2>Frames</h2>
 * A cube's world transform is rigid: {@code M x = R x + t}. Rewriting it as {@code M x = R (x + R^T t)}
 * shows that the cube is an axis-aligned box in the <i>frame</i> {@code R}, shifted by {@code R^T t}.
 * Every part is stored that way - a rotation and a box in that rotation's frame - because two cubes with
 * the same {@code R} are then plain axis-aligned boxes relative to each other and can be merged when their
 * union is again a box.
 */
public final class CubePlanner {

    /** Boxes thinner than this (px) are thickened: a zero-size block display z-fights with itself. */
    private static final double MIN_THICKNESS = 0.02;
    private static final double EPS = 1e-4;

    /**
     * One block display.
     *
     * @param name     the cube it came from (the first one, after merging)
     * @param rotation the frame: rotation only, model space
     * @param min      box corner in the frame, px; the model-space point is {@code rotation * q}
     * @param max      opposite corner
     * @param block    lower-case vanilla block name
     * @param emissive drawn at full brightness
     */
    public record Part(String name, Affine rotation, double[] min, double[] max, String block, boolean emissive) {
        double volume() { return (max[0] - min[0]) * (max[1] - min[1]) * (max[2] - min[2]); }
    }

    /**
     * @param parts       what to spawn, largest first
     * @param sourceCubes visible cubes in the model
     * @param dropped     parts cut off by the maximum
     */
    public record Plan(List<Part> parts, int sourceCubes, int dropped) { }

    private CubePlanner() { }

    public static Plan plan(BbModel model, ModelGeometry geometry, Palette palette, boolean merge, int maxCubes, int warnCubes, ModelLog log) {
        TextureSampler sampler = new TextureSampler(model, log);
        List<Part> parts = new ArrayList<>();
        for (ModelGeometry.Element element : geometry.elements) {
            BbModel.Cube cube = element.cube();
            TextureSampler.Sample sample = sampler.sample(cube);
            boolean emissive = isEmissive(cube, palette);
            String block = chooseBlock(model, cube, sample, emissive, palette, log);
            if (block == null) continue; // an invisible cube

            Affine rotation = element.world().rotationOnly();
            double[] shift = rotation.applyInverseDirection(element.world().translation());
            double[] min = new double[3], max = new double[3];
            for (int i = 0; i < 3; i++) {
                min[i] = cube.from[i] - cube.inflate + shift[i];
                max[i] = cube.to[i] + cube.inflate + shift[i];
                if (max[i] - min[i] < MIN_THICKNESS) {
                    double mid = (min[i] + max[i]) / 2;
                    min[i] = mid - MIN_THICKNESS / 2;
                    max[i] = mid + MIN_THICKNESS / 2;
                }
            }
            parts.add(new Part(cube.name, rotation, min, max, block, emissive));
        }
        int source = parts.size();
        if (merge) parts = merge(parts);
        parts.sort(Comparator.comparingDouble(Part::volume).reversed());

        int dropped = 0;
        if (maxCubes > 0 && parts.size() > maxCubes) {
            dropped = parts.size() - maxCubes;
            parts = new ArrayList<>(parts.subList(0, maxCubes));
            log.warn("", "LIVE_CUBES: the model needs " + (maxCubes + dropped) + " block displays, more than visual.max-cubes (" + maxCubes
                + ") - the " + dropped + " smallest cubes are not shown. Simplify the model, raise the limit, or use PACK_MODEL");
        } else if (warnCubes > 0 && parts.size() > warnCubes) {
            log.warn("", "LIVE_CUBES: every unit of this model is " + parts.size() + " block displays (visual.warn-cubes is " + warnCubes
                + ") - many units of a model this detailed cost client frame rate");
        }
        return new Plan(List.copyOf(parts), source, dropped);
    }

    // ------------------------------------------------------------------ material

    private static boolean isEmissive(BbModel.Cube cube, Palette palette) {
        if (cube.lightEmission > 0) return true;
        for (String keyword : palette.emissiveKeywords()) {
            if (keyword.isEmpty()) continue;
            if (cube.name.toLowerCase(Locale.ROOT).contains(keyword)) return true;
            if (cube.group != null && cube.group.isOrIsUnder(keyword)) return true;
        }
        return false;
    }

    /** @return the block for a cube, or null when the cube is invisible (fully transparent texture) */
    private static String chooseBlock(BbModel model, BbModel.Cube cube, TextureSampler.Sample sample, boolean emissive, Palette palette, ModelLog log) {
        String override = palette.overrides().get(cube.name.toLowerCase(Locale.ROOT));
        for (BbModel.Group g = cube.group; override == null && g != null; g = g.parent) override = palette.overrides().get(g.name.toLowerCase(Locale.ROOT));
        if (override == null) {
            for (BbModel.Face face : cube.faces.values()) {
                override = palette.overrides().get(model.textures.get(face.texture()).key());
                if (override != null) break;
            }
        }
        if (override != null) return override;

        if (!sample.sampled()) {
            log.warn("cube '" + cube.name + "'", "has no readable texture - shown as " + palette.defaultBlock());
            return palette.defaultBlock();
        }
        if (sample.coverage() < palette.invisibleBelow()) return null;
        String block = null;
        if (sample.coverage() < palette.glassBelow()) block = Palette.nearest(sample.rgb(), palette.glass());
        if (block == null && emissive) {
            Map<String, Integer> candidates = new LinkedHashMap<>(palette.solid());
            candidates.putAll(palette.emissive());
            block = Palette.nearest(sample.rgb(), candidates);
        }
        if (block == null) block = Palette.nearest(sample.rgb(), palette.solid());
        return block != null ? block : palette.defaultBlock();
    }

    // ------------------------------------------------------------------ merging

    /**
     * Joins parts of the same block and the same frame whose union is exactly a box (they agree on two axes
     * and touch or overlap on the third), and drops parts that lie completely inside another one. Repeats
     * until nothing changes, so a row of three cubes collapses into one.
     */
    public static List<Part> merge(List<Part> input) {
        Map<String, List<Part>> buckets = new LinkedHashMap<>();
        for (Part part : input) buckets.computeIfAbsent(bucketKey(part), k -> new ArrayList<>()).add(part);
        List<Part> out = new ArrayList<>();
        for (List<Part> bucket : buckets.values()) {
            boolean changed = true;
            while (changed) {
                changed = false;
                outer:
                for (int i = 0; i < bucket.size(); i++) {
                    for (int j = i + 1; j < bucket.size(); j++) {
                        Part joined = join(bucket.get(i), bucket.get(j));
                        if (joined == null) continue;
                        bucket.set(i, joined);
                        bucket.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
            out.addAll(bucket);
        }
        return out;
    }

    private static String bucketKey(Part part) {
        StringBuilder key = new StringBuilder(part.block()).append(part.emissive() ? "|e" : "|n");
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) key.append('|').append(Math.round(part.rotation().get(r, c) * 10000));
        return key.toString();
    }

    private static Part join(Part a, Part b) {
        if (contains(a, b)) return a;
        if (contains(b, a)) return b;
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            boolean sameProfile = near(a.min()[u], b.min()[u]) && near(a.max()[u], b.max()[u]) && near(a.min()[v], b.min()[v]) && near(a.max()[v], b.max()[v]);
            boolean touching = b.min()[axis] <= a.max()[axis] + EPS && a.min()[axis] <= b.max()[axis] + EPS;
            if (!sameProfile || !touching) continue;
            double[] min = a.min().clone(), max = a.max().clone();
            min[axis] = Math.min(a.min()[axis], b.min()[axis]);
            max[axis] = Math.max(a.max()[axis], b.max()[axis]);
            return new Part(a.name(), a.rotation(), min, max, a.block(), a.emissive());
        }
        return null;
    }

    private static boolean contains(Part outer, Part inner) {
        for (int i = 0; i < 3; i++) if (inner.min()[i] < outer.min()[i] - EPS || inner.max()[i] > outer.max()[i] + EPS) return false;
        return true;
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < EPS; }

    /** How many parts use each block - for /scp079 pack info. */
    public static Map<String, Integer> blockCounts(List<Part> parts) {
        Map<String, Integer> counts = new HashMap<>();
        for (Part part : parts) counts.merge(part.block(), 1, Integer::sum);
        return counts;
    }
}
