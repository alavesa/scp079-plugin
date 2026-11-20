package fi.alavesa.scp079.render;

import fi.alavesa.scp079.bbmodel.BbModel;
import fi.alavesa.scp079.bbmodel.ModelGeometry;

/**
 * The model units show right now, ready to spawn. Immutable; swapped as a whole on reload.
 *
 * @param plan        the LIVE_CUBES block displays (computed in both modes, for /scp079 pack info)
 * @param fingerprint changes whenever anything that affects the spawned entities changes - entities carry
 *                    it, so stale visuals are recognised and replaced
 */
public record ActiveModel(String id, String fileName, BbModel model, ModelGeometry geometry, CubePlanner.Plan plan, String fingerprint) { }
