#version 120

// Tree quad drawn undeformed and widened by the sway reach; crown bend,
// lobes and leaf flutter are sample offsets. Outline mode reproduces
// vboRenderer_Tree.frag on the same warped lookup. Per-tree values are
// varyings, frame-wide values uniforms.

uniform sampler2D DIFFUSE;

uniform vec4 uParams;    // z: leaf cell px, w: blend sharpening (1 = bilinear, large = nearest); the clocks travel per tree (vPage.xy, vMisc.w, vPixTexel.z)
uniform vec4 uMode;      // x: 1 = outline pass, y: 1 = alpha capped at the fade alpha, z: evergreen tier bounce at the trunk, w: its exponent
uniform vec2 stepSize;
uniform vec4 outlineColor;
uniform vec4 uMask;      // x: mask clock (cells), y: strength (0 = every cell flutters), z: 1 / mask cell px, w: density outside the patches
uniform vec4 uLobe;      // x: lobe reference cell px, y: rate exponent, z: per-cell rate spread, w: 1 / evergreen tier aspect
uniform vec4 uTwig;      // second lobe octave (twigs): x: lattice scale, y: rate factor, z: amplitude share, w: y-field rate factor
uniform vec4 uCrown;     // x: broadleaf tail above the knee, y: shortening, z: tilt, w: evergreen lobe ramp above the bend start
uniform vec4 uTrunk;     // x: share of the lean carried by a straight trunk pivoting at the foot (broadleaf), y: foot fade (height fraction)
uniform vec4 uFlick;     // leaf flicker: x: clock factor on the leaf clock, y: lit density at the local wind's low, z: at a full gust, w: oscillator threshold
uniform vec4 uFlick2;    // x, y: gust ramp on the local wind, z: density share outside the mask patches, w: cluster rate exponent (vertex)
uniform vec4 uLeaf;      // x: shade swing (0 = off), y: leaf cell growth exponent, z: gust share of the cells outside the patches, w: shade rate (fraction of the leaf clock)
uniform vec4 uQual;      // perf A/B, 1 = computed, 0 = skipped: x: lobes, y: second lobe octave, z: leaf flutter, w: leaf mask
uniform vec4 uQual2;     // x: leaf shade
uniform sampler2D MASK;  // leaf-material atlas, quarter sprite px, 1 = leaf, 0 = painted wood
uniform vec4 uWood;      // x, y: sprite texel to mask-atlas uv (0.25 / atlas size), z: mask strength
uniform vec4 uDebug;     // x: diagnosis view, 0 = off, 7 = depth-punch (each drawn fragment adds 20% red)
uniform vec2 uPage;      // uv per texel of the run's page
varying vec2 vPage;      // x: leaf clock integer cycles mod 64, y: branch clock integer cycles mod 64
varying float vWind;     // local wind 0..1
vec4 gTexel;             // xy: uv per texel of the page, zw: texels per uv (page size)

varying vec4 vColor;
varying vec4 vUVH;       // xy: uv, z: height fraction (0 base, 1 top), w: 1 / branch cell px
varying vec4 vPixTexel;  // xy: field pixel coords (with per-tree offset), z: branch clock fraction, w: 1 / cluster cell px
varying vec4 vRect;      // xy: atlas u range, zw: atlas v range (whole sprite, not the depth segment)
varying vec4 vBend;      // x: top offset (u), y: bend start (height fraction), z: rod exponent (0 = broadleaf bow), w: lobe amplitude u
varying vec4 vLeaf;      // x: flutter amplitude u, y: amplitude v, z: v at leaf band top, w: v at leaf band bottom
varying vec4 vMisc;      // x: fade alpha, y: bend end (1 = tip, below it a block above), z: lobe amplitude v, w: leaf clock fraction
varying vec4 vLeaf2;     // x: 1 / flicker cell px (0 = no flicker), y: flicker amplitude, z: snap of the leaf offset to whole texels, w: leaf clock factor of the cluster cell
varying vec4 vWood;      // leaf-mask cell in the atlas (u0, v0, u1, v1), x < 0 = none

float hash(vec2 p)
{
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// One oscillator per lattice corner, phase and frequency from folds of
// one hash (the hash is a sin, the expensive part). The clock arrives as
// integer cycles mod 64 (nm) plus a fraction (q), the cell's rate is a
// multiple of 1/64 (b / 64): b * nm is an exact integer, so the phase is
// exact, never drifts with the session and has no seam at the clock wrap.
// A rate that shared one float with other data lost 1e-4 of itself to
// rounding whenever the packed value stepped, times thousands of cycles.
float osc(vec2 i, float nm, float q, float mul)
{
	float h = hash(i);
	float fr = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 61.7);
	float b = floor(fr * mul * 64.0 + 0.5);
	return sin(6.2831853 * (fract(b * nm * (1.0 / 64.0)) + b * (1.0 / 64.0) * q + h));
}

float field(vec2 p, float nm, float q, float mul)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	float a = osc(i, nm, q, mul);
	float b = osc(i + vec2(1.0, 0.0), nm, q, mul);
	float cc = osc(i + vec2(0.0, 1.0), nm, q, mul);
	float d = osc(i + vec2(1.0, 1.0), nm, q, mul);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

// Two oscillators per corner from one hash.
vec2 osc2(vec2 i, float nm, float q, float mul1, float mul2)
{
	float h = hash(i);
	float h2 = fract(h * 37.3);
	float fr1 = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 61.7);
	float fr2 = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 23.9);
	float b1 = floor(fr1 * mul1 * 64.0 + 0.5);
	float b2 = floor(fr2 * mul2 * 64.0 + 0.5);
	return sin(6.2831853 * vec2(fract(b1 * nm * (1.0 / 64.0)) + b1 * (1.0 / 64.0) * q + h,
	                            fract(b2 * nm * (1.0 / 64.0)) + b2 * (1.0 / 64.0) * q + h2));
}

vec2 field2(vec2 p, float nm, float q, float mul1, float mul2)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	vec2 a = osc2(i, nm, q, mul1, mul2);
	vec2 b = osc2(i + vec2(1.0, 0.0), nm, q, mul1, mul2);
	vec2 cc = osc2(i + vec2(0.0, 1.0), nm, q, mul1, mul2);
	vec2 d = osc2(i + vec2(1.0, 1.0), nm, q, mul1, mul2);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

float vnoise(vec2 p)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
	           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Lattice periodic in 64 x 24 cells: the mask clock wraps at 64 with a
// drift of 3/8 across, so the field is seamless there and the clock stays
// exact as a float.
float vnoiseMask(vec2 p)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	vec2 i0 = mod(i, vec2(64.0, 24.0));
	vec2 i1 = mod(i + 1.0, vec2(64.0, 24.0));
	return mix(mix(hash(i0), hash(vec2(i1.x, i0.y)), f.x),
	           mix(hash(vec2(i0.x, i1.y)), hash(i1), f.x), f.y);
}

// A leaf cell flutters at full amplitude or not at all: a fraction of a
// pixel everywhere reads as heat haze.
float gate(vec2 i, float dens)
{
	return 1.0 - smoothstep(dens - 0.08, dens + 0.08, hash(i + vec2(2.7, 9.4)));
}

float gateField(vec2 p, float dens)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	float a = gate(i, dens);
	float b = gate(i + vec2(1.0, 0.0), dens);
	float cc = gate(i + vec2(0.0, 1.0), dens);
	float d = gate(i + vec2(1.0, 1.0), dens);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

// Bilinear (NEAREST tears the crown into shifted rows). Pages are STRAIGHT
// alpha (transparent texels even carry white rgb), so premultiply per texel:
// interpolation stays clean of the hidden colours, and the premultiplied
// output under (ONE, ONE_MINUS_SRC_ALPHA) blends like vanilla's straight
// texels under (770, 771).
vec4 fetch(vec2 uv)
{
	if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
	{
		return vec4(0.0);
	}
	// Base level pinned: the page keeps whatever filter the engine bound it
	// with last (six of the Tiles2x tree pages are shared with sprites drawn
	// through the ring buffer), and a mip of a packed page bleeds neighbours.
	vec4 t = texture2D(DIFFUSE, uv, -1.0);
	t.rgb *= t.a;
	return t;
}

vec4 sampleTree(vec2 uv)
{
	vec2 texel = gTexel.xy;
	vec2 t = uv * gTexel.zw - 0.5;
	vec2 i = floor(t);
	vec2 f = clamp((t - i - 0.5) * uParams.w + 0.5, 0.0, 1.0);
	vec2 c00 = (i + 0.5) * texel;
	return mix(mix(fetch(c00), fetch(c00 + vec2(texel.x, 0.0)), f.x),
	           mix(fetch(c00 + vec2(0.0, texel.y)), fetch(c00 + texel), f.x), f.y);
}

vec4 outline(vec2 uv)
{
	float alpha = 4.0 * texture2D(DIFFUSE, uv, -1.0).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(stepSize.x, 0.0), -1.0).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(-stepSize.x, 0.0), -1.0).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(0.0, stepSize.y), -1.0).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(0.0, -stepSize.y), -1.0).a;
	alpha = clamp(alpha * outlineColor.a, 0.0, 1.0);
	return vec4(outlineColor.rgb * alpha, alpha);
}

void main()
{
	// Bend over the height: rod with the exponent (evergreens, bare crowns)
	// or the broadleaf bow, mirrored in TreeRenderer.profileRaw.
	gTexel = vec4(uPage, 1.0 / uPage);
	float h = clamp(vUVH.z, 0.0, 1.0);
	float hc = vBend.y;
	float kn = vMisc.y;
	bool conifer = kn > 0.999;
	float bend;
	// Broadleaf: part of the lean is the whole trunk pivoting at the foot,
	// a straight line from the ground (no curvature, so no rubber); the
	// rest is the bow or rod above. The line is 1 where the bow is 1.
	float hRef = 1.0;
	if (conifer || vBend.z > 0.0)
	{
		float x = clamp((h - hc) / (1.0 - hc), 0.0, 1.0);
		bend = pow(max(x, 0.0001), vBend.z);
	}
	else
	{
		float sigma = kn - hc;
		float l = 1.0 - kn;
		float tail = uCrown.x;
		float yc = 0.5 * l;
		float dCentre = 0.5 * sigma + yc + tail * yc * yc / (2.0 * l);
		float d;
		if (h <= hc)
		{
			d = 0.0;
		}
		else if (h <= kn)
		{
			float u = (h - hc) / sigma;
			float u2 = u * u;
			d = sigma * (u2 * u - 0.5 * u2 * u2);
		}
		else
		{
			float y = h - kn;
			d = 0.5 * sigma + y + tail * y * y / (2.0 * l);
		}
		bend = d / dCentre;
		hRef = kn + yc;
	}
	// Bent branches shorten (uCrown.y * dx^2 / W) and the crown tilts about
	// the trunk (uCrown.z * dx / W); evergreen tiers come in above hc, not
	// with the rod.
	float lobeBend = (conifer && uCrown.w > 0.0)
	               ? smoothstep(hc, hc + uCrown.w, h) : min(bend, 1.0);
	// Lobes follow the bow only: the trunk pivots but never joins the branches.
	if (!conifer && uTrunk.x > 0.0)
	{
		float line = h / hRef * smoothstep(0.0, max(uTrunk.y, 0.001), h);
		bend = mix(bend, line, uTrunk.x);
	}
	float ux = vUVH.x - vBend.x * bend;
	float wPx = (vRect.y - vRect.x) * gTexel.z;
	float dxPx = vBend.x * bend * gTexel.z;
	float xrel = (ux - 0.5 * (vRect.x + vRect.y)) * gTexel.z;
	float dyPx = (uCrown.y * dxPx * dxPx + uCrown.z * dxPx * xrel) / wPx;
	float uy = vUVH.y - dyPx * gTexel.y;
	// Pixels that no offset can bring onto the sprite skip the fields.
	float reachX = (1.0 + uTwig.z) * vBend.w * lobeBend + vLeaf.x;
	float reachY = (1.0 + uTwig.z) * vMisc.z * lobeBend + vLeaf.y;
	if (ux < vRect.x - reachX || ux > vRect.y + reachX
	    || uy < vRect.z - reachY || uy > vRect.w + reachY)
	{
		discard;
	}
	float soft = 6.0 * gTexel.y;
	float crown = smoothstep(vLeaf.z - soft, vLeaf.z + soft, uy)
	            * smoothstep(vLeaf.w + soft, vLeaf.w - soft, uy);

	vec2 pix = vPixTexel.xy;
	float nmB = vPage.y;
	float qB = vPixTexel.z;
	float nmL = vPage.x;
	float qL = vMisc.w;
	float gTree = vWind;
	vec2 pb = pix * vUVH.w;
	float bx = 0.0;
	float by = 0.0;
	if (uQual.x > 0.5 && lobeBend > 0.0 && (vBend.w != 0.0 || vMisc.z != 0.0))
	{
		if (conifer)
		{
			vec2 pby = pix * vec2(vUVH.w * uLobe.w, vUVH.w);
			bx = field(pb, nmB, qB, 1.0);
			by = field(pby + vec2(4.7, 2.3), nmB, qB, 1.125);
			if (uQual.y > 0.5)
			{
				bx += uTwig.z * field(pb * uTwig.x + 5.3, nmB, qB, uTwig.y);
				by += uTwig.z * field(pby * uTwig.x + 9.1, nmB, qB, uTwig.y * uTwig.w);
			}
		}
		else
		{
			vec2 bxy = field2(pb, nmB, qB, 1.0, 1.125);
			if (uQual.y > 0.5)
			{
				bxy += uTwig.z * field2(pb * uTwig.x + 5.3, nmB, qB, uTwig.y, uTwig.y * uTwig.w);
			}
			bx = bxy.x;
			by = bxy.y;
		}
	}
	vec2 pl = pix * vPixTexel.w;
	float lx = 0.0;
	float ly = 0.0;
	float flick = 0.0;
	bool inBand = crown > 0.0;
	float band = crown;
	if (uQual.z > 0.5 && inBand)
	{
		// Painted wood inside the crown band (trunks, branches between the
		// leaves) shimmered like leaves: one tap of the offline leaf mask
		// scales flutter and flicker. Lobes stay full, branches may sway.
		if (uWood.z > 0.0 && vWood.x >= 0.0)
		{
			vec2 wUV = vWood.xy + (vec2(ux, uy) - vec2(vRect.x, vRect.z)) * gTexel.zw * uWood.xy;
			wUV = clamp(wUV, vWood.xy + 2.0 * uWood.xy, vWood.zw - 2.0 * uWood.xy);
			float lm = mix(1.0, texture2D(MASK, wUV).r, uWood.z);
			crown *= lm;
			band *= lm;
		}
		float patch = 1.0;
		if (uMask.y > 0.0 && uQual.w > 0.5)
		{
			float m = vnoiseMask(pix * uMask.z + vec2(uMask.x, 0.375 * uMask.x));
			float fl = uMask.w + (1.0 - uMask.w) * uLeaf.z * gTree;
			float inPatch = smoothstep(0.35, 0.65, m);
			float dens = mix(1.0, fl + (1.0 - fl) * inPatch, uMask.y);
			crown *= gateField(pl, dens);
			patch = mix(1.0, uFlick2.z + (1.0 - uFlick2.z) * inPatch, uMask.y);
		}
		if (vLeaf.x != 0.0 || vLeaf.y != 0.0)
		{
			vec2 lxy = field2(pl, nmL, qL, vLeaf2.w, vLeaf2.w * 1.125);
			lx = lxy.x;
			ly = lxy.y;
		}
		// Leaves turning show their underside: a brightness flicker on
		// leaf-sized cells, each lit for part of its own cycle, never a
		// sample offset (fine paint shifted by a fraction of a texel is
		// heat haze).
		if (vLeaf2.y > 0.0)
		{
			vec2 pf = pix * vLeaf2.x;
			float lit = smoothstep(uFlick.w - 0.12, uFlick.w + 0.12, field(pf + vec2(17.3, 5.9), nmL, qL, uFlick.x));
			float densF = mix(uFlick.y, uFlick.z, smoothstep(uFlick2.x, uFlick2.y, gTree)) * patch;
			flick = lit * gateField(pf + vec2(3.1, 7.7), densF) * vLeaf2.y * band;
		}
	}
	// Tiers pivot at the trunk: a whole crown bobbing reads as heat haze.
	float tierG = 1.0;
	if (conifer)
	{
		float r = clamp(abs(xrel) / (0.5 * wPx), 0.0, 1.0);
		tierG = mix(uMode.z, 1.0, pow(max(r, 0.0001), uMode.w));
	}
	vec2 dl = vec2(lx * vLeaf.x, ly * vLeaf.y) * crown;
	if (vLeaf2.z > 0.0)
	{
		vec2 dq = floor(dl * gTexel.zw + 0.5) * gTexel.xy;
		dl = mix(dl, dq, vLeaf2.z);
	}
	vec2 duv = vec2(bx * vBend.w, by * vMisc.z * tierG) * lobeBend + dl;

	vec2 uv = vec2(ux - duv.x, uy - duv.y);
	if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
	{
		discard;
	}

	if (uMode.x > 0.5)
	{
		vec4 o = outline(uv);
		if (o.a < 0.01)
		{
			discard;
		}
		gl_FragColor = o;
		return;
	}
	vec4 texel = sampleTree(uv);
	if (texel.a < 0.01)
	{
		discard;
	}
	// Depth-punch view: every drawn fragment adds 20% red; a quad culled
	// behind the tree shows as a bake-coloured hole under the veil. Alpha
	// above the pass's GL_ALPHA_TEST (GREATER 0.0): a zero-alpha fragment
	// is rejected whole, depth write included.
	if (uDebug.x == 7.0)
	{
		gl_FragColor = vec4(0.2, 0.0, 0.0, 0.004);
		return;
	}
	vec4 col = vColor;
	if (uMode.y > 0.5)
	{
		col.a = min(col.a, vMisc.x);
	}
	// Shade on its own slow field: at the flutter rate it strobes.
	float shade = 1.0;
	if (uQual2.x > 0.5 && inBand && uLeaf.x != 0.0)
	{
		shade += uLeaf.x * field(pl * 0.5 + 11.3, nmL, qL, uLeaf.w) * crown;
	}
	shade += flick;
	gl_FragColor = vec4(col.rgb * col.a * texel.rgb * shade, col.a * texel.a);
}
