package fi.alavesa.scp079;

import fi.alavesa.scp079.bbmodel.BbModel;
import fi.alavesa.scp079.bbmodel.BbModelParser;
import fi.alavesa.scp079.bbmodel.DefaultModel;
import fi.alavesa.scp079.bbmodel.ModelGeometry;
import fi.alavesa.scp079.bbmodel.ModelLog;
import fi.alavesa.scp079.render.CubePlanner;
import fi.alavesa.scp079.render.Palette;
import fi.alavesa.scp079.util.Affine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPipelineTest {

    private static CubePlanner.Part part(String block, double... box) {
        return new CubePlanner.Part("p", Affine.IDENTITY, new double[] {box[0], box[1], box[2]}, new double[] {box[3], box[4], box[5]}, block, false);
    }

    @Test
    void defaultModelParsesAndPlans() throws Exception {
        ModelLog log = new ModelLog("scp079.bbmodel");
        BbModel model = BbModelParser.parse("scp079", DefaultModel.json(), null, log);
        ModelGeometry geometry = ModelGeometry.of(model);
        assertEquals(8, geometry.elements.size());
        assertEquals(1.0, geometry.fitScale);

        CubePlanner.Plan plan = CubePlanner.plan(model, geometry, Palette.defaults(), true, 256, 96, log);
        assertTrue(log.warnings().isEmpty(), log.warnings().toString());
        assertFalse(plan.parts().isEmpty());
        CubePlanner.Part screen = plan.parts().stream().filter(p -> p.name().equals("screen")).findFirst().orElseThrow();
        assertTrue(screen.emissive());
        // the screen's FRONT (north, the side that faces the player) shows the screen region of the atlas, 0,16 - 16,28
        BbModel.Cube cube = model.cubes.stream().filter(c -> c.name.equals("screen")).findFirst().orElseThrow();
        assertArrayEquals(new double[] {0, 16, 16, 28}, cube.faces.get("north").uv(), 1e-9);
    }

    @Test
    void partReproducesTheCubeTransform() throws Exception {
        BbModel model = BbModelParser.parse("scp079", DefaultModel.json(), null, new ModelLog("t"));
        ModelGeometry geometry = ModelGeometry.of(model);
        CubePlanner.Plan plan = CubePlanner.plan(model, geometry, Palette.defaults(), false, 256, 96, new ModelLog("t"));
        // the keyboard is in a group tilted by -8 degrees about x through (0, 0, -12)
        ModelGeometry.Element element = geometry.elements.stream().filter(e -> e.cube().name.equals("keyboard")).findFirst().orElseThrow();
        CubePlanner.Part part = plan.parts().stream().filter(p -> p.name().equals("keyboard")).findFirst().orElseThrow();
        double[] viaWorld = element.world().apply(element.cube().to);
        double[] viaPart = part.rotation().apply(part.max());
        assertArrayEquals(viaWorld, viaPart, 1e-9);
        // its back edge is lifted: 4.5 px behind the pivot, sin(8 deg) * 4.5 above the cube's own height
        double[] back = element.world().apply(new double[] {0, 0, -7.5});
        assertEquals(Math.sin(Math.toRadians(8)) * 4.5, back[1], 1e-9);
    }

    @Test
    void mergesBoxesWhoseUnionIsABox() {
        List<CubePlanner.Part> parts = new ArrayList<>(List.of(
            part("stone", 0, 0, 0, 1, 1, 1),
            part("stone", 1, 0, 0, 2, 1, 1),      // touches the first along x: joins
            part("stone", 2, 0, 0, 3, 1, 1),      // and so does this, once the first two are one
            part("stone", 0, 1, 0, 1, 2, 2),      // different profile: stays
            part("dirt", 3, 0, 0, 4, 1, 1),       // different block: stays
            part("stone", 0.2, 0.2, 0.2, 0.8, 0.8, 0.8))); // inside the first: dropped
        List<CubePlanner.Part> merged = CubePlanner.merge(parts);
        assertEquals(3, merged.size());
        CubePlanner.Part row = merged.get(0);
        assertArrayEquals(new double[] {0, 0, 0}, row.min(), 1e-9);
        assertArrayEquals(new double[] {3, 1, 1}, row.max(), 1e-9);
    }
}
