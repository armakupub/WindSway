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
// aPix: field pixel coords, clock integer cycles (leaf + 64 * branch, each mod 64), branch clock fraction
layout (location = 3) in vec4 aPix;
// aRect: atlas u range, atlas v range
layout (location = 4) in vec4 aRect;
// aBend: lean u, bend start, exponent, branch amp u
layout (location = 5) in vec4 aBend;
// aLeaf: flutter amp u, amp v, band top v, band bottom v
layout (location = 6) in vec4 aLeaf;
// aMisc: fade alpha, bend end, lobe amp v, leaf clock fraction
layout (location = 7) in vec4 aMisc;
// aLeaf2: cells in half px (leaf + 64 * flicker + 4096 * cluster), snap, local wind, flicker amplitude
layout (location = 8) in vec4 aLeaf2;
// aWood: leaf-mask cell in the atlas (u0, v0, u1, v1), x < 0 = none
layout (location = 9) in vec4 aWood;

uniform mat4 ModelViewProjection;
uniform vec4 uParams;    // z: leaf cell px
uniform vec4 uLobe;      // x: lobe reference cell px, y: rate exponent
uniform vec4 uLeaf;      // y: leaf cell growth exponent
uniform vec4 uFlick2;    // w: cluster rate exponent over leaf cell / cluster cell

out vec4 vColor;
out vec4 vUVH;
out vec4 vPixTexel;
out vec4 vRect;
out vec4 vBend;
out vec4 vLeaf;
out vec4 vMisc;
out vec4 vLeaf2;
out vec2 vPage;
out vec4 vWood;
out float vWind;

void main (void)
{
	vColor = aColor;
	float cellRatio = aUVH.w / uLobe.x;
	vUVH = vec4(aUVH.xyz, 1.0 / aUVH.w);
	// Cells in half pixels; the leaf cell falls back to the uniform when
	// the tree sends none, the cluster cell to the leaf cell.
	float pc = aLeaf2.x;
	float leafHalf = mod(pc, 64.0);
	float flickHalf = mod(floor(pc / 64.0), 64.0);
	float clusterHalf = floor(pc / 4096.0);
	float leafCell = leafHalf > 0.5 ? leafHalf * 0.5 : uParams.z * pow(cellRatio, uLeaf.y);
	float clusterCell = clusterHalf > 0.5 ? clusterHalf * 0.5 : leafCell;
	// The leaf offset runs on the cluster cell, slower on a bigger cell
	// like the lobes; the rate factor is quantised to 1/64 in the fragment.
	vPixTexel = vec4(aPix.xy, aPix.w, 1.0 / clusterCell);
	vLeaf2 = vec4(flickHalf > 0.5 ? 2.0 / flickHalf : 0.0, aLeaf2.w, aLeaf2.y,
	              pow(leafCell / clusterCell, uFlick2.w));
	vPage = vec2(mod(aPix.z, 64.0), floor(aPix.z / 64.0));
	vWind = aLeaf2.z;
	vRect = aRect;
	vBend = aBend;
	vLeaf = aLeaf;
	vMisc = aMisc;
	vWood = aWood;

	vec4 o = ModelViewProjection * vec4(aPosDepth.xyz, 1);

	float clip = ((o.z+1.0) / 2.0);
	clip += aPosDepth.w;
	o.z = (clip*2)-1;

	gl_Position = o;
}
