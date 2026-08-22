#version 330

// Batched tree quads (TreeRenderer): per-tree values as attributes, one
// ModelViewProjection per list, depth as vboRenderer_PositionColorUV.vert's
// userDepth per vertex.
//
// Attribute comments must stay off the layout lines: on GL 2.1 the engine
// rewrites "layout (location = N) in ..." to "attribute ..." with a regex
// that requires the line to end at the ';'. A trailing comment blocks the
// rewrite, GLSL 1.20 rejects "layout", and the whole program fails to link
// (Mac fell back to vanilla trees, no sway). See README-Shaders.txt.

// aPosDepth: world xyz, depth offset
layout (location = 0) in vec4 aPosDepth;
// aColor
layout (location = 1) in vec4 aColor;
// aUVH: u, v, height fraction, branch cell px
layout (location = 2) in vec4 aUVH;
// aPix: field pixel coords, uv per texel of the page
layout (location = 3) in vec4 aPix;
// aRect: atlas u range, atlas v range
layout (location = 4) in vec4 aRect;
// aBend: lean u, bend start, exponent, branch amp u
layout (location = 5) in vec4 aBend;
// aLeaf: flutter amp u, amp v, band top v, band bottom v
layout (location = 6) in vec4 aLeaf;
// aMisc: fade alpha, bend end, lobe amp v, leaf clock rate
layout (location = 7) in vec4 aMisc;

uniform mat4 ModelViewProjection;
uniform vec4 uParams;    // z: leaf cell px
uniform vec4 uLobe;      // x: lobe reference cell px, y: rate exponent
uniform vec4 uLeaf;      // y: leaf cell growth exponent

out vec4 vColor;
out vec4 vUVH;
out vec4 vPixTexel;
out vec4 vRect;
out vec4 vBend;
out vec4 vLeaf;
out vec4 vMisc;
out vec2 vPage;

void main (void)
{
	vColor = aColor;
	vPage = aPix.zw;
	float cellRatio = aUVH.w / uLobe.x;
	vUVH = vec4(aUVH.xyz, 1.0 / aUVH.w);
	vPixTexel = vec4(aPix.xy, pow(cellRatio, -uLobe.y), 1.0 / (uParams.z * pow(cellRatio, uLeaf.y)));
	vRect = aRect;
	vBend = aBend;
	vLeaf = aLeaf;
	vMisc = aMisc;

	vec4 o = ModelViewProjection * vec4(aPosDepth.xyz, 1);

	float clip = ((o.z+1.0) / 2.0);
	clip += aPosDepth.w;
	o.z = (clip*2)-1;

	gl_Position = o;
}
