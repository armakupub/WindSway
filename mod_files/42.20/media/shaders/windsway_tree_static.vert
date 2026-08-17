#version 330

// Batched tree quads (TreeRenderer): per-tree and per-sprite values are
// vertex attributes, one ModelViewProjection for the whole list. Depth
// is applied exactly like vboRenderer_PositionColorUV.vert's userDepth,
// per vertex instead of per run.

layout (location = 0) in vec4 aPosDepth;   // world xyz, depth offset
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec4 aUVH;        // u, v, height fraction, branch cell px
layout (location = 3) in vec4 aPixTexel;   // field pixel coords, uv per texel
layout (location = 4) in vec4 aRect;       // atlas u range, segment v range
layout (location = 5) in vec4 aBend;       // lean u, bend start, exponent, branch amp u
layout (location = 6) in vec4 aLeaf;       // flutter amp u, amp v, band top v, band bottom v
layout (location = 7) in vec4 aMisc;       // fade alpha, bend end, lobe amp v, leaf clock rate

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
