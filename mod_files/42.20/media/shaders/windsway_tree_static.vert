#version 330

// Batched tree quads (TreeRenderer): per-tree and per-sprite values are
// vertex attributes, one ModelViewProjection for the whole list. Depth
// is applied exactly like vboRenderer_PositionColorUV.vert's userDepth,
// per vertex instead of per run.
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
// aPixTexel: field pixel coords, uv per texel
layout (location = 3) in vec4 aPixTexel;
// aRect: atlas u range, atlas v range
layout (location = 4) in vec4 aRect;
// aBend: lean u, bend start, exponent, branch amp u
layout (location = 5) in vec4 aBend;
// aLeaf: flutter amp u, amp v, band top v, band bottom v
layout (location = 6) in vec4 aLeaf;
// aMisc: fade alpha, bend end, lobe amp v, leaf clock rate
layout (location = 7) in vec4 aMisc;

uniform mat4 ModelViewProjection;

out vec4 vColor;
out vec4 vUVH;
out vec4 vPixTexel;
out vec4 vRect;
out vec4 vBend;
out vec4 vLeaf;
out vec4 vMisc;

void main (void)
{
	vColor = aColor;
	vUVH = aUVH;
	vPixTexel = aPixTexel;
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
