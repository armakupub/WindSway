#version 120

// Tree quad drawn undeformed and widened by the sway reach; crown bend,
// lobes and leaf flutter are sample offsets. Outline mode reproduces
// vboRenderer_Tree.frag on the same warped lookup. Per-tree values are
// varyings, frame-wide values uniforms.

uniform sampler2D DIFFUSE;

uniform vec4 uParams;    // x: lobe clock, y: leaf clock (cycles, integrated on the game thread), z: leaf cell px, w: blend sharpening (1 = bilinear, large = nearest)
uniform vec4 uMode;      // x: 1 = outline pass, y: 1 = alpha capped at the fade alpha, z: evergreen tier bounce at the trunk, w: its exponent
uniform vec2 stepSize;
uniform vec4 outlineColor;
uniform vec4 uMask;      // x: mask clock (cells), y: strength (0 = every cell flutters), z: 1 / mask cell px, w: density outside the patches
uniform vec4 uLobe;      // x: lobe reference cell px, y: rate exponent, z: per-cell rate spread, w: 1 / evergreen tier aspect
uniform vec4 uCrown;     // x: broadleaf tail above the knee, y: shortening, z: tilt, w: evergreen lobe ramp above the bend start
uniform vec4 uLeaf;      // x: shade swing (0 = off), y: leaf cell growth exponent, z: gust share of the cells outside the patches, w: shade rate (fraction of the leaf clock)
uniform vec4 uQual;      // perf A/B, 1 = computed, 0 = skipped: x: lobes, y: second lobe octave, z: leaf flutter, w: leaf mask
uniform vec4 uQual2;     // x: leaf shade
varying vec2 vPage;      // uv per texel of the page (from the vertex)
vec4 gTexel;             // xy: uv per texel of the page, zw: texels per uv (page size)

varying vec4 vColor;
varying vec4 vUVH;       // xy: uv, z: height fraction (0 base, 1 top), w: 1 / branch cell px
varying vec4 vPixTexel;  // xy: field pixel coords (with per-tree offset), z: lobe clock scale, w: 1 / leaf cell px
varying vec4 vRect;      // xy: atlas u range, zw: atlas v range (whole sprite, not the depth segment)
varying vec4 vBend;      // x: top offset (u), y: bend start (height fraction), z: rod exponent (0 = broadleaf bow), w: lobe amplitude u
varying vec4 vLeaf;      // x: flutter amplitude u, y: amplitude v, z: v at leaf band top, w: v at leaf band bottom
varying vec4 vMisc;      // x: fade alpha, y: bend end (1 = tip, below it a block above), z: lobe amplitude v, w: leaf rate (0.5..1.5) + 2 * floor(local wind * 63)

float hash(vec2 p)
{
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// One oscillator per lattice corner, phase and frequency from folds of
// one hash (the hash is a sin, the expensive part).
float osc(vec2 i, float c)
{
	float h = hash(i);
	float fr = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 61.7);
	return sin(6.2831853 * (fr * c + h));
}

float field(vec2 p, float c)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	float a = osc(i, c);
	float b = osc(i + vec2(1.0, 0.0), c);
	float cc = osc(i + vec2(0.0, 1.0), c);
	float d = osc(i + vec2(1.0, 1.0), c);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

// Two oscillators per corner from one hash.
vec2 osc2(vec2 i, float c1, float c2)
{
	float h = hash(i);
	float h2 = fract(h * 37.3);
	float fr1 = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 61.7);
	float fr2 = 1.0 - uLobe.z + 2.0 * uLobe.z * fract(h * 23.9);
	return sin(6.2831853 * vec2(fr1 * c1 + h, fr2 * c2 + h2));
}

vec2 field2(vec2 p, float c1, float c2)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	vec2 a = osc2(i, c1, c2);
	vec2 b = osc2(i + vec2(1.0, 0.0), c1, c2);
	vec2 cc = osc2(i + vec2(0.0, 1.0), c1, c2);
	vec2 d = osc2(i + vec2(1.0, 1.0), c1, c2);
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

// Bilinear (NEAREST tears the crown into shifted rows). Pages are
// premultiplied (ImageData), so texels blend straight and the output
// stays premultiplied for the (ONE, ONE_MINUS_SRC_ALPHA) blend.
vec4 fetch(vec2 uv)
{
	if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
	{
		return vec4(0.0);
	}
	// Base level pinned: the page keeps whatever filter the engine bound it
	// with last (six of the Tiles2x tree pages are shared with sprites drawn
	// through the ring buffer), and a mip of a packed page bleeds neighbours.
	return texture2D(DIFFUSE, uv, -1.0);
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
	gTexel = vec4(vPage, 1.0 / vPage);
	float h = clamp(vUVH.z, 0.0, 1.0);
	float hc = vBend.y;
	float kn = vMisc.y;
	bool conifer = kn > 0.999;
	float bend;
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
	}
	// Bent branches shorten (uCrown.y * dx^2 / W) and the crown tilts about
	// the trunk (uCrown.z * dx / W); evergreen tiers come in above hc, not
	// with the rod.
	float lobeBend = (conifer && uCrown.w > 0.0)
	               ? smoothstep(hc, hc + uCrown.w, h) : min(bend, 1.0);
	float ux = vUVH.x - vBend.x * bend;
	float wPx = (vRect.y - vRect.x) * gTexel.z;
	float dxPx = vBend.x * bend * gTexel.z;
	float xrel = (ux - 0.5 * (vRect.x + vRect.y)) * gTexel.z;
	float dyPx = (uCrown.y * dxPx * dxPx + uCrown.z * dxPx * xrel) / wPx;
	float uy = vUVH.y - dyPx * gTexel.y;
	// Pixels that no offset can bring onto the sprite skip the fields.
	float reachX = 1.5 * vBend.w * lobeBend + vLeaf.x;
	float reachY = 1.5 * vMisc.z * lobeBend + vLeaf.y;
	if (ux < vRect.x - reachX || ux > vRect.y + reachX
	    || uy < vRect.z - reachY || uy > vRect.w + reachY)
	{
		discard;
	}
	float soft = 6.0 * gTexel.y;
	float crown = smoothstep(vLeaf.z - soft, vLeaf.z + soft, uy)
	            * smoothstep(vLeaf.w + soft, vLeaf.w - soft, uy);

	vec2 pix = vPixTexel.xy;
	float cb = uParams.x * vPixTexel.z;
	float gq = floor(vMisc.w * 0.5);
	float leafRate = vMisc.w - 2.0 * gq;
	float gTree = gq * (1.0 / 63.0);
	float cl = uParams.y * leafRate;
	vec2 pb = pix * vUVH.w;
	float bx = 0.0;
	float by = 0.0;
	if (uQual.x > 0.5 && lobeBend > 0.0 && (vBend.w != 0.0 || vMisc.z != 0.0))
	{
		if (conifer)
		{
			vec2 pby = pix * vec2(vUVH.w * uLobe.w, vUVH.w);
			bx = field(pb, cb);
			by = field(pby + vec2(4.7, 2.3), cb * 1.15);
			if (uQual.y > 0.5)
			{
				bx += 0.5 * field(pb * 2.1 + 5.3, cb * 1.7);
				by += 0.5 * field(pby * 2.1 + 9.1, cb * 1.9);
			}
		}
		else
		{
			vec2 bxy = field2(pb, cb, cb * 1.15);
			if (uQual.y > 0.5)
			{
				bxy += 0.5 * field2(pb * 2.1 + 5.3, cb * 1.7, cb * 1.9);
			}
			bx = bxy.x;
			by = bxy.y;
		}
	}
	vec2 pl = pix * vPixTexel.w;
	float lx = 0.0;
	float ly = 0.0;
	bool inBand = crown > 0.0;
	if (uQual.z > 0.5 && inBand)
	{
		if (uMask.y > 0.0 && uQual.w > 0.5)
		{
			float m = vnoise(pix * uMask.z + vec2(uMask.x, 0.37 * uMask.x));
			float fl = uMask.w + (1.0 - uMask.w) * uLeaf.z * gTree;
			float dens = mix(1.0, fl + (1.0 - fl) * smoothstep(0.35, 0.65, m), uMask.y);
			crown *= gateField(pl, dens);
		}
		if (vLeaf.x != 0.0 || vLeaf.y != 0.0)
		{
			vec2 lxy = field2(pl, cl, cl * 1.1);
			lx = lxy.x;
			ly = lxy.y;
		}
	}
	// Tiers pivot at the trunk: a whole crown bobbing reads as heat haze.
	float tierG = 1.0;
	if (conifer)
	{
		float r = clamp(abs(xrel) / (0.5 * wPx), 0.0, 1.0);
		tierG = mix(uMode.z, 1.0, pow(max(r, 0.0001), uMode.w));
	}
	vec2 duv = vec2(bx * vBend.w, by * vMisc.z * tierG) * lobeBend + vec2(lx * vLeaf.x, ly * vLeaf.y) * crown;

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
	vec4 col = vColor;
	if (uMode.y > 0.5)
	{
		col.a = min(col.a, vMisc.x);
	}
	// Shade on its own slow field: at the flutter rate it strobes.
	float shade = 1.0;
	if (uQual2.x > 0.5 && inBand && uLeaf.x != 0.0)
	{
		shade += uLeaf.x * field(pl * 0.5 + 11.3, cl * uLeaf.w) * crown;
	}
	gl_FragColor = vec4(col.rgb * col.a * texel.rgb * shade, col.a * texel.a);
}
