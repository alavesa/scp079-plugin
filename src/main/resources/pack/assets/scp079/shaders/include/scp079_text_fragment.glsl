#ifndef SCP079_TEXT_FRAGMENT_GLSL
#define SCP079_TEXT_FRAGMENT_GLSL

// =====================================================================================================================
// SCP079: fragment part of the core/text override: colours SCP-079's captions.
//
// Acts only on GUI text (IS_GUI) whose colour is the marker #FB79xx (see scp079_text_vertex.glsl); everything else
// gets back exactly the colour computed before this call.
//   box (blue bits 0-1 = 3): semi-transparent black
//   text: palette (blue bits 4-6): 0 caption colour | 1 white | 2 dimmed caption colour
// =====================================================================================================================

#ifdef IS_GUI
#include <scp079:scp079_caption_config.glsl>
#endif

vec4 scp079_text_fragment(vec4 color, vec4 texColor, vec4 vertColor, vec4 colorModulator) {
#ifdef IS_GUI
    ivec3 c = ivec3(round(vertColor.rgb * 255.0));
    if (c.r != 251 || c.g != 121) return color;
    if ((c.b & 3) == 3) return vec4(0.0, 0.0, 0.0, SCP079_CAPTION_BG_ALPHA * vertColor.a * colorModulator.a);
    int palette = (c.b >> 4) & 7;
    vec3 rgb = palette == 1 ? vec3(0.95, 0.97, 0.95) : palette == 2 ? SCP079_CAPTION_COLOR * 0.55 : SCP079_CAPTION_COLOR;
    return vec4(rgb, texColor.a * vertColor.a) * colorModulator;
#else
    return color;
#endif
}

#endif
