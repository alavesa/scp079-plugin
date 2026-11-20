#ifndef SCP079_TEXT_VERTEX_GLSL
#define SCP079_TEXT_VERTEX_GLSL

// =====================================================================================================================
// SCP079: vertex part of the core/text override: moves SCP-079's captions to just above the hotbar.
//
// A caption is the NAME of a boss bar, written in the font scp079:caption. Vanilla draws that name centred at the top
// of the screen. Its total width is 0 by construction (CaptionText.java), so the pen origin is exactly the screen
// centre and a glyph's distance from the centre is its x inside the caption.
//
// MARKER: text colour #FB79xx (red 251, green 121). Blue: bits 0-1 = text line counted from the BOTTOM (0-2),
// 3 = the background box; text: bits 4-6 = palette; box: bits 2-3 = lines - 1, bits 4-7 = half width / 8 px - 1.
// Only GUI text is looked at (IS_GUI); world text (signs, name tags, text displays, maps) is compiled without this
// code. Does not collide with SuperbVehicles (red 253), Radio (#FEFBxx), IdCards (red 252) or CCTV (#0CC7xx).
//
// No varyings are added, so there is nothing to collide with at the varying locations the other plugins use.
// =====================================================================================================================

#ifdef IS_GUI
#include <scp079:scp079_caption_config.glsl>

const float SCP079_CELL_H = 8.0;     // glyph height
const float SCP079_LINE_H = 10.0;    // distance between two lines
const float SCP079_PAD_Y = 3.0;      // box padding above the first and below the last line
#endif

// Call at the END of main(): it replaces gl_Position for caption glyphs and must not be overwritten afterwards.
// color: the vertex colour attribute (it is declared after this file is included, so it is passed in).
void scp079_text_vertex(vec4 color) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (c.r != 251 || c.g != 121) return;
    // glyph quads are emitted top-left, bottom-left, bottom-right, top-right (26.3 BakedSheetGlyph.render)
    int corner = gl_VertexIndex % 4;
    vec2 k = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
    vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);          // GUI size in GUI pixels
    int line = c.b & 3;
    vec2 p;                                                              // relative to (screen centre, box bottom)
    if (line == 3) {
        float lines = float(((c.b >> 2) & 3) + 1);
        float halfWidth = float(((c.b >> 4) & 15) + 1) * 8.0;
        float height = (lines - 1.0) * SCP079_LINE_H + SCP079_CELL_H + 2.0 * SCP079_PAD_Y;
        p = vec2((k.x * 2.0 - 1.0) * halfWidth, (k.y - 1.0) * height);
    } else {
        // x inside the caption = distance from the screen centre (rounded: the centre is a half pixel on odd widths)
        float x = floor(gl_Position.x / gl_Position.w * 0.5 * gui.x + 0.75);
        float top = -SCP079_PAD_Y - SCP079_CELL_H - float(line) * SCP079_LINE_H;
        p = vec2(x, top + k.y * SCP079_CELL_H);
    }
    p *= SCP079_CAPTION_SCALE;
    vec4 clip = ProjMat * vec4(floor(gui.x * 0.5) + p.x, gui.y - SCP079_CAPTION_BOTTOM + p.y, 0.0, 1.0);
    gl_Position = vec4(clip.xy * gl_Position.w, gl_Position.z, gl_Position.w);
#endif
}

#endif
