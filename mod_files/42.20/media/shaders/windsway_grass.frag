#version 120

// tileWithDepth.frag: no alpha discard, coverage comes from the depth map
// (0 = discard), colour blends premultiplied. Depth writes are off in the
// translucent pass; gl_FragDepth only feeds the LEQUAL test.
// Wind flora: the quad is widened, the bend over the height is a sample
// offset on diffuse and depth map alike. The diffuse is filtered here, on
// texel centres at the base level: the page keeps whatever filter bound it
// last (NEAREST after a chunk bake, LINEAR with mipmaps after an engine
// bind), and NEAREST under the fractional bend plus the camera's sub-pixel
// offset re-rolls every texel choice each frame while walking.

// Page slot pair from the integer part of vFrame.w (diffuse + 8 * depth,
// above the barrier bits); GLSL 1.20 cannot index samplers.
uniform sampler2D DIFFUSE0;
uniform sampler2D DIFFUSE1;
uniform sampler2D DIFFUSE2;
uniform sampler2D DIFFUSE3;
uniform sampler2D DIFFUSE4;
uniform sampler2D DIFFUSE5;
uniform sampler2D DIFFUSE6;
uniform sampler2D DIFFUSE7;
uniform sampler2D DEPTH0;
uniform sampler2D DEPTH1;
uniform sampler2D DEPTH2;
uniform sampler2D DEPTH3;
uniform sampler2D DEPTH4;
uniform sampler2D DEPTH5;
uniform sampler2D DEPTH6;
uniform sampler2D DEPTH7;
uniform vec4 uWind;    // x: w
uniform vec4 uRing2;   // y: fast swing share at w 1, z: upwind cap
uniform vec4 uPlant;   // x: bend exponent, y: shortening, z: blade cell px, w: blade spread (amplitude, phase)
uniform vec4 uPlant2;  // x: barrier cap R (sprite px), y: 1 = bend (A/B), z: 1 / blade cell px, w: leaf shade swing
uniform vec4 uLeafP;   // x: leaf clock integer cycles mod 64, y: gate density at calm
uniform vec4 uLeafQ;   // x: leaf clock fraction, y: branch clock integer cycles mod 64, z: branch clock fraction
uniform vec4 uTip;     // x: phase lead of the tip (cycles), y: its height exponent, z: extra fast-swing share at the tip, w: snap lift of the tip on the return swing (fraction of A * swing)
uniform vec4 uSheen;   // x: brightness swing of the gust sheen at the plants' wind, y: height exponent
uniform vec4 uModel;   // x: 1 = ring model, y: bend exponent added at w 1
uniform vec4 uHon2;    // w: soft cap knee of the lean (total tops out at 1 + knee)
uniform vec4 uFlut;    // z: flutter rate as a multiple of the swing rate, w: column phase spread (cycles)
uniform vec4 uBody;    // x: knee of the stem-and-block profile (height fraction), y: residual shear above it, z: 1 / lobe cell px, w: lobe vertical share
uniform vec4 uLeafM;   // mask patches: x clock (cells), y 1 / patch cell px, z strength, w floor density outside the patches
uniform vec4 uFlick;   // leaf flicker: x clock factor on the leaf clock, y duty threshold, z lit density at the local wind's low, w at a full gust
uniform vec4 uFlick2;  // x, y: gust ramp on the local wind, z: density share outside the patches, w: 1 / flicker cell px
uniform vec4 uLeafM2;  // x: share of the cells outside the patches a full gust switches on, y: lobe rate factor (branch clock)

varying vec4 vColor;
varying vec2 vUV1;
varying vec2 vUV2;
varying float vDepthNear;
varying float vDepthFar;
varying vec4 vWind;
varying vec4 vRect;
varying vec4 vTexel;
varying vec4 vFrame;
varying vec4 vInv;     // x: 1 / u per sprite px, y: 1 / frame height (v), z: 1 / content width (px), w: barrier compression
varying vec4 vLeafP;   // x, y: leaf flutter amplitude (sprite px, local wind applied), z: cell px, w: leaf rate of the object
varying vec4 vClass2;  // x: bend exponent factor, y: blade spread factor, z: local wind, w: leaf phase offset
varying vec4 vGust;    // x: gust sheen -1..1 (class factor applied), y: tip physics factor of the class, z: ring one sample back (model 1), w: flutter amplitude px (model 1)
varying vec4 vBody;    // x: stem-and-block profile share, y: lobe amplitude of this part (sprite px, local wind applied), z: lobe phase of the object
varying vec4 vLeafM;   // x: mask share of this part, y: flicker amplitude (brightness, 0 = none)

float hash1(float p)
{
	p = fract(p * 0.1031);
	p *= p + 33.33;
	p *= p + p;
	return fract(p);
}

float noise1(float x, float salt)
{
	float i = floor(x);
	float f = x - i;
	float a = hash1(i + salt);
	float b = hash1(i + 1.0 + salt);
	return a + (b - a) * f * f * (3.0 - 2.0 * f);
}

// Leaf flutter lattice as in windsway_tree.frag: one hash per corner (the
// sin is the expensive part), two oscillators folded from it, a gate so a
// cell flutters at full amplitude or not at all.
float hash2(vec2 p)
{
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Exact phase as in windsway_tree.frag: clock as (integer cycles mod 64,
// fraction), the cell's rate a multiple of 1/64, so b * nm is a whole
// number and nothing drifts with the session or jumps at the wrap.
vec2 osc2(vec2 i, float nm, float q, float mul1, float mul2, float hOff)
{
	float h = hash2(i);
	float h2 = fract(h * 37.3);
	float fr1 = 0.85 + 0.3 * fract(h * 61.7);
	float fr2 = 0.85 + 0.3 * fract(h * 23.9);
	float b1 = floor(fr1 * mul1 * 64.0 + 0.5);
	float b2 = floor(fr2 * mul2 * 64.0 + 0.5);
	return sin(6.2831853 * vec2(fract(b1 * nm * (1.0 / 64.0)) + b1 * (1.0 / 64.0) * q + h + hOff,
	                            fract(b2 * nm * (1.0 / 64.0)) + b2 * (1.0 / 64.0) * q + h2 + hOff));
}

vec2 field2(vec2 p, float nm, float q, float mul1, float mul2, float hOff)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	vec2 a = osc2(i, nm, q, mul1, mul2, hOff);
	vec2 b = osc2(i + vec2(1.0, 0.0), nm, q, mul1, mul2, hOff);
	vec2 cc = osc2(i + vec2(0.0, 1.0), nm, q, mul1, mul2, hOff);
	vec2 d = osc2(i + vec2(1.0, 1.0), nm, q, mul1, mul2, hOff);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

float gate(vec2 i, float dens)
{
	return 1.0 - smoothstep(dens - 0.08, dens + 0.08, hash2(i + vec2(2.7, 9.4)));
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

// One oscillator per corner (the flicker), exact phase as osc2.
float osc1(vec2 i, float nm, float q, float mul, float hOff)
{
	float h = hash2(i);
	float fr = 0.85 + 0.3 * fract(h * 61.7);
	float b = floor(fr * mul * 64.0 + 0.5);
	return sin(6.2831853 * (fract(b * nm * (1.0 / 64.0)) + b * (1.0 / 64.0) * q + h + hOff));
}

float field1(vec2 p, float nm, float q, float mul, float hOff)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	float a = osc1(i, nm, q, mul, hOff);
	float b = osc1(i + vec2(1.0, 0.0), nm, q, mul, hOff);
	float cc = osc1(i + vec2(0.0, 1.0), nm, q, mul, hOff);
	float d = osc1(i + vec2(1.0, 1.0), nm, q, mul, hOff);
	return mix(mix(a, b, f.x), mix(cc, d, f.x), f.y);
}

float vnoise(vec2 p)
{
	vec2 i = floor(p);
	vec2 f = p - i;
	f = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash2(i), hash2(i + vec2(1.0, 0.0)), f.x),
	           mix(hash2(i + vec2(0.0, 1.0)), hash2(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec4 diffuseAt(float s, vec2 uv)
{
	if (s < 4.0)
	{
		if (s < 2.0)
		{
			return s < 1.0 ? texture2D(DIFFUSE0, uv, -8.0) : texture2D(DIFFUSE1, uv, -8.0);
		}
		return s < 3.0 ? texture2D(DIFFUSE2, uv, -8.0) : texture2D(DIFFUSE3, uv, -8.0);
	}
	if (s < 6.0)
	{
		return s < 5.0 ? texture2D(DIFFUSE4, uv, -8.0) : texture2D(DIFFUSE5, uv, -8.0);
	}
	return s < 7.0 ? texture2D(DIFFUSE6, uv, -8.0) : texture2D(DIFFUSE7, uv, -8.0);
}

// Taps outside the content rect read transparent: the page packs the
// neighbours right next to it.
vec4 fetch(float s, vec2 uv)
{
	if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
	{
		return vec4(0.0);
	}
	return diffuseAt(s, uv);
}

vec4 sampleDiffuse(float s, vec2 uv)
{
	vec2 texel = abs(vTexel.xy);
	vec2 t = uv / texel - 0.5;
	vec2 i = floor(t);
	vec2 f = t - i;
	vec2 c00 = (i + 0.5) * texel;
	return mix(mix(fetch(s, c00), fetch(s, c00 + vec2(texel.x, 0.0)), f.x),
	           mix(fetch(s, c00 + vec2(0.0, texel.y)), fetch(s, c00 + texel), f.x), f.y);
}

// Zoomed out: box over the pixel footprint (2^lod sprite px) from four
// bilinear taps, so thin structures (fence wire) do not alias and crawl
// with the camera. The page's own mip filter cannot be used: it is
// NEAREST after a chunk bake and trilinear after an engine bind.
vec4 sampleMinified(float s, vec2 uv, float lod)
{
	vec2 o = abs(vTexel.xy) * (0.25 * exp2(lod));
	return 0.25 * (sampleDiffuse(s, uv + vec2(-o.x, -o.y)) + sampleDiffuse(s, uv + vec2(o.x, -o.y))
	             + sampleDiffuse(s, uv + vec2(-o.x, o.y)) + sampleDiffuse(s, uv + o));
}

float depthAt(float s, vec2 uv)
{
	if (s < 4.0)
	{
		if (s < 2.0)
		{
			return s < 1.0 ? texture2D(DEPTH0, uv).r : texture2D(DEPTH1, uv).r;
		}
		return s < 3.0 ? texture2D(DEPTH2, uv).r : texture2D(DEPTH3, uv).r;
	}
	if (s < 6.0)
	{
		return s < 5.0 ? texture2D(DEPTH4, uv).r : texture2D(DEPTH5, uv).r;
	}
	return s < 7.0 ? texture2D(DEPTH6, uv).r : texture2D(DEPTH7, uv).r;
}

void main()
{
	vec2 uv = vUV1;
	vec2 uv2 = vUV2;
	// Sprite px per screen px from the unbent uv, before any discard can
	// break the derivative quad.
	vec2 texel0 = abs(vTexel.xy);
	vec2 ddx = dFdx(vUV1) / texel0;
	vec2 ddy = dFdy(vUV1) / texel0;
	float lod = 0.5 * log2(max(max(dot(ddx, ddx), dot(ddy, ddy)), 1e-12));
	float slot = floor(floor(vFrame.w) * 0.125);
	float depthSlot = floor(slot * 0.125);
	float diffuseSlot = slot - 8.0 * depthSlot;
	float shade = 1.0;
	if (vWind.x != 0.0 && uPlant2.y > 0.5)
	{
		float seed = fract(vFrame.w);
		// Height and blade field in the object's frame: all parts bend as one.
		float h = clamp((vFrame.y - vUV1.y) * vInv.y, 0.0, 1.0);
		bool ringM = uModel.x > 0.5;
		// Ring model: the profile stiffens with the wind, a storm blade is
		// rigid at the base and streams at the tip.
		float bend = pow(h + 0.0001, (uPlant.x + (ringM ? uModel.y * uWind.x : 0.0)) * vClass2.x);
		// Woody body: the stems shear as straight lines up to the knee (a
		// pivot at the foot, no curvature), above it the crown rides as one
		// piece with a residual shear; a crown sheared over its whole
		// height is rubber.
		if (vBody.x > 0.0)
		{
			float kn = uBody.x;
			float blk = h < kn ? h / kn : 1.0 + uBody.y * (h - kn) / (1.0 - kn);
			bend = mix(bend, blk / (1.0 + uBody.y), vBody.x);
		}
		// The tip leads the base by uTip.x cycles (a gust deflects the tip
		// first, the base follows), and the fast companion is a tip thing:
		// an S that travels up the blade instead of one shear for all of it.
		// Both are functions of h only, so no column folds. In the ring model
		// the lead blends the ring toward its value one sample (T/8) back.
		float hl = pow(h + 0.0001, uTip.y) * vGust.y;
		float phT = vWind.w + uTip.x * hl;
		float fast = 0.0;
		float ringH = 0.0;
		float total0;
		if (ringM)
		{
			ringH = mix(vGust.z, vWind.z, clamp(uTip.x * 8.0 * hl, 0.0, 1.0));
			total0 = vWind.y + ringH;
		}
		else
		{
			fast = uRing2.y * uWind.x * (1.0 + uTip.z * hl) * sin(6.2831853 * (phT / 0.55 + 0.37));
			total0 = vWind.y + vWind.z * (sin(6.2831853 * phT) + fast);
		}
		if (total0 > 1.0)
		{
			float o = total0 - 1.0;
			total0 = 1.0 + o / (1.0 + o / uHon2.w);
		}
		else if (total0 < 0.0)
		{
			total0 = uRing2.z > 0.0 ? total0 / (1.0 - total0 / uRing2.z) : 0.0;
		}
		// Barrier compression per object, not per swing: the plant keeps moving.
		float k = vInv.w;
		float dx0 = vWind.x * total0 * bend * k;
		// The offset must stay monotonic in x (one source column per screen
		// column): |d dx / d x| <= 0.35, else columns double and turn liquid.
		float safe = 0.35 * uPlant.z / (1.5 * max(abs(dx0), 0.01));
		float var = min(uPlant.w * vClass2.y, safe);
		float px = (vUV1.x - vFrame.z) * vInv.x;
		float bn = noise1(px * uPlant2.z + seed * 977.0, 3.0);
		float total = total0;
		if (!ringM)
		{
			float osc = sin(6.2831853 * (phT + 0.15 * var * (bn - 0.5))) + fast;
			total = vWind.y + vWind.z * osc;
			if (total > 1.0)
			{
				float o = total - 1.0;
				total = 1.0 + o / (1.0 + o / uHon2.w);
			}
			else if (total < 0.0)
			{
				total = uRing2.z > 0.0 ? total / (1.0 - total / uRing2.z) : 0.0;
			}
		}
		float blade = 1.0 + var * (bn - 0.5);
		float dx = vWind.x * total * bend * blade * k;
		// Flutter (ring model): the galloping mode at a whole multiple of
		// the swing rate (exact at the clock wrap), tips only, phase per
		// blade column from the column noise; at 1-2 px the column phase
		// stays far below the fold slope.
		if (ringM && vGust.w > 0.0)
		{
			dx += vGust.w * h * h * sin(6.2831853 * (uFlut.z * vWind.w + uFlut.w * bn));
		}
		float dy = uPlant.y * dx * dx * vInv.z;
		// Snap: on the return swing the tip whips up past its arc; negative
		// dy lifts, the quad's top pad covers it.
		if (uTip.w > 0.0)
		{
			float snap;
			if (ringM)
			{
				// Returning = the ring moving against its own displacement.
				float vel = vWind.z - vGust.z;
				snap = clamp(-vel * sign(ringH) * 16.0, 0.0, 1.0);
				dy -= uTip.w * abs(vWind.x) * abs(ringH) * hl * h * snap * snap;
			}
			else
			{
				snap = max(0.0, -cos(6.2831853 * phT));
				dy -= uTip.w * abs(vWind.x) * vWind.z * hl * h * snap * snap;
			}
		}
		// Gust sheen: blades tilting into the light, the wave the front
		// paints over a meadow; the base clump keeps its shade.
		shade += uSheen.x * vGust.x * pow(h + 0.0001, uSheen.y);
		float py = (vUV1.y - vFrame.x) / vTexel.y;
		// Crown lobes: the trees' branch lattice on the branch clock, so the
		// crown's outline moves in pieces (branches) instead of as one
		// silhouette; rides on the bend like the tree lobes.
		if (vBody.y > 0.0)
		{
			vec2 pb = (vec2(px, py) + seed * 401.0) * uBody.z;
			vec2 lb = field2(pb, uLeafQ.y, uLeafQ.z, uLeafM2.y, uLeafM2.y * 1.125, vBody.z) * vBody.y * min(bend, 1.0);
			dx += lb.x;
			dy += lb.y * uBody.w;
		}
		// Leaf layer on leafy parts only: a lattice over the part's sprite
		// pixels, gated, on top of the bend, and the brightness flicker;
		// grass skips it.
		bool leafOff = vLeafP.x != 0.0 || vLeafP.y != 0.0;
		if (leafOff || vLeafM.y > 0.0)
		{
			vec2 pixL = vec2(px, py) + seed * 613.0;
			vec2 pl = pixL / vLeafP.z;
			float dens = uLeafP.y + (1.0 - uLeafP.y) * max(uWind.x, 0.6 * vClass2.z);
			float patch = 1.0;
			if (vLeafM.x > 0.0 && uLeafM.z > 0.0)
			{
				// Mask patches as on the trees: dense patches drifting over
				// the crown, single cells between them, more with the gust;
				// a whole crown fluttering at once reads as heat haze.
				float m = vnoise(pixL * uLeafM.y + vec2(uLeafM.x, 0.37 * uLeafM.x));
				float inPatch = smoothstep(0.35, 0.65, m);
				float fl = uLeafM.w + (1.0 - uLeafM.w) * uLeafM2.x * vClass2.z;
				float st = uLeafM.z * vLeafM.x;
				dens = mix(dens, fl + (1.0 - fl) * inPatch, st);
				patch = mix(1.0, uFlick2.z + (1.0 - uFlick2.z) * inPatch, st);
			}
			if (leafOff)
			{
				float gt = gateField(pl, dens);
				if (gt > 0.0)
				{
					vec2 l = field2(pl, uLeafP.x, uLeafQ.x, vLeafP.w, vLeafP.w * 1.125, vClass2.w) * gt;
					dx += l.x * vLeafP.x;
					dy += l.y * vLeafP.y;
					if (uPlant2.w > 0.0)
					{
						float bs = floor(vLeafP.w * 0.25 * 64.0 + 0.5);
						shade += uPlant2.w * gt * sin(6.2831853 * (fract(bs * uLeafP.x * (1.0 / 64.0)) + bs * (1.0 / 64.0) * uLeafQ.x
						        + hash2(floor(pl * 0.5) + vec2(11.3, 11.3)) + vClass2.w));
					}
				}
			}
			// Leaves turning show their underside: brightness on leaf-sized
			// cells, each lit for part of its own cycle, never an offset.
			if (vLeafM.y > 0.0)
			{
				vec2 pf = pixL * uFlick2.w;
				float lit = smoothstep(uFlick.y - 0.12, uFlick.y + 0.12, field1(pf + vec2(17.3, 5.9), uLeafP.x, uLeafQ.x, uFlick.x, vClass2.w));
				float densF = mix(uFlick.z, uFlick.w, smoothstep(uFlick2.x, uFlick2.y, vClass2.z)) * patch;
				shade += lit * gateField(pf + vec2(3.1, 7.7), densF) * vLeafM.y;
			}
		}
		uv = vUV1 - vec2(dx * vTexel.x, dy * vTexel.y);
		if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
		{
			discard;
		}
		// Whole texels for the coverage: the NEAREST depth map then snaps
		// with the camera as one piece, like vanilla, instead of row by row.
		vec2 dInt = floor(vec2(dx, dy) + 0.5);
		uv2 = vUV2 - vec2(dInt.x * vTexel.z, dInt.y * vTexel.w);
	}
	vec4 c = lod > 0.05 ? sampleMinified(diffuseSlot, uv, lod) : sampleDiffuse(diffuseSlot, uv);
	float d = depthAt(depthSlot, uv2);
	c *= vColor;
	c.rgb *= vColor.a * shade;
	if (d > 0.0)
	{
		gl_FragDepth = (vDepthFar - vDepthNear) * d + vDepthNear;
		gl_FragColor = c;
	}
	else
	{
		discard;
	}
}
