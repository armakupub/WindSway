#version 330

// tileWithDepth.vert with the per-draw depth uniforms moved into vertex
// attributes so one batch covers many tiles: aPosition.z carries zDepthBlendZ
// (near), aDepthFar carries zDepthBlendToZ. gl_FragDepth does the depth work.
// Wind flora: local wind per vertex (the trees' turbulence field), the bend
// in the fragment shader. No trailing comments on the layout lines: the
// engine's GL 2.1 rewrite matches only bare "layout (...) in type name;".

// xy: scene pixels, z: depth near
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
// diffuse uv
layout (location = 2) in vec2 aUV1;
// depth map uv
layout (location = 3) in vec2 aUV2;
layout (location = 4) in float aDepthFar;
// x: lean-axis position (tiles), y: seed, z: lean amplitude (sprite px per
// unit lean, 0 = no wind), w: period (s)
layout (location = 5) in vec4 aWind;
// content rect in diffuse uv: u min, u max, v top, v bottom
layout (location = 6) in vec4 aRect;
// diffuse uv per sprite pixel (x signed by flip), depth uv per sprite pixel
layout (location = 7) in vec4 aTexel;
// object frame in this part's uv: content top v, bottom v, left u; w: seed
layout (location = 8) in vec4 aFrame;

uniform mat4 ModelViewProjection;
uniform vec4 uWind;    // x: w (plants channel), y: amplitude at w, z: lean direction sign, w: front travel (tiles, signed)
uniform vec4 uClock;   // x: real seconds, y: swing clock (s), z: front speed (tiles/s), w: standing lean per unit of w
uniform vec4 uTurb;    // x: slow octave length (tiles), y: short octave length, z: per-object rate (1/s), w: contrast
uniform vec4 uMix;     // x, y, z: slow, short, per-object mix weights
uniform vec4 uResp;    // x: sensitivity spread, y: dead band max, z: response curve calm, w: curve at w 1
uniform vec4 uRing;    // x: swing gain, y: unit change rate (1/s), z: knee, w: wind excitation
uniform vec4 uRing2;   // x: rest share, y: fast share at w 1, z: upwind cap, w: period spread
uniform vec4 uPlant2;  // x: soft cap R of the lean amplitude toward a fence or wall (sprite px)

out vec4 vColor;
out vec2 vUV1;
out vec2 vUV2;
out float vDepthNear;
out float vDepthFar;
out vec4 vWind;
out vec4 vRect;
out vec4 vTexel;
out vec4 vFrame;
out vec4 vInv;

float hash1(float p)
{
	p = fract(p * 0.1031);
	p *= p + 33.33;
	p *= p + p;
	return fract(p);
}

// 1D value noise, value and d/dx.
vec2 noise1(float x, float salt)
{
	float i = floor(x);
	float f = x - i;
	float a = hash1(i + salt);
	float b = hash1(i + 1.0 + salt);
	return vec2(a + (b - a) * f * f * (3.0 - 2.0 * f), (b - a) * 6.0 * f * (1.0 - f));
}

void main (void)
{
	vColor = aColor;
	vUV1 = aUV1;
	vUV2 = aUV2;
	vDepthNear = aPosition.z;
	vDepthFar = aDepthFar;
	vRect = aRect;
	vTexel = aTexel;
	vFrame = aFrame;
	vWind = vec4(0.0);
	if (aWind.z > 0.0)
	{
		float w = uWind.x;
		float seed = aWind.y * 61.0;
		float hSens = hash1(seed + 1.3);
		float hThr = hash1(seed + 2.7);
		float hRate = hash1(seed + 3.1);
		float hPhase = hash1(seed + 4.9);
		float hRing = hash1(seed + 5.3);
		float hFray = hash1(seed + 6.1);
		float hPeriod = hash1(seed + 7.7);
		// Local wind, the same field as TreeSway's (derivative analytic).
		float dirSign = uWind.z < 0.0 ? -1.0 : 1.0;
		float s = aWind.x + 0.6 * uTurb.y * (hFray - 0.5);
		float adv = uWind.w;
		float speed = uClock.z;
		vec2 n1 = noise1((s - adv) / uTurb.x, 11.0);
		vec2 n2 = noise1((s - adv) / uTurb.y + 7.7, 23.0);
		float rate = uTurb.z * (0.7 + 0.6 * hRate);
		// Decorrelate through the salt, not the coordinate: a 4096 offset
		// costs twelve mantissa bits in float32 (the tree path does this in
		// double). noise1 hashes floor(x) + salt, so this is exact.
		float off = hPhase * 4096.0;
		vec2 n3 = noise1(uClock.x * rate + fract(off), 37.0 + floor(off));
		float v = uMix.x * n1.x + uMix.y * n2.x + uMix.z * n3.x;
		float d = -uMix.x * n1.y * dirSign * speed / uTurb.x
		        - uMix.y * n2.y * dirSign * speed / uTurb.y
		        + uMix.z * n3.y * rate;
		float c = uTurb.w;
		float u = clamp((v - 0.5) * c + 0.5, 0.0, 1.0);
		float g = u * u * (3.0 - 2.0 * u);
		float gd = d * c * 6.0 * u * (1.0 - u);
		float x = gd * gd / (uRing.y * uRing.y);
		float xk = x * x / (x + uRing.z);
		float energy = 1.0 - exp(-(xk + uRing.w * w * g));
		float sens = 1.0 - uResp.x + 2.0 * uResp.x * hSens;
		float thr = uResp.y * hThr * (1.0 - w);
		float e = max((g - thr) / (1.0 - thr), 0.0);
		float curve = uResp.z + (uResp.w - uResp.z) * w;
		e = min(pow(e + 0.00001, curve) * sens, 1.0);
		float rest = uRing2.x + (1.0 - uRing2.x) * w;
		float swing = uRing.x * energy * sens * (rest + (1.0 - rest) * e);
		float period = aWind.w * (1.0 - uRing2.w + 2.0 * uRing2.w * hPeriod);
		float ph = uClock.y / period + hRing;
		// The fragment takes sin of ph and of ph / 0.55; 11 is a period of
		// both, so the wrap is exact and the sine argument stays small
		// (past ~48k rad the SFU sine loses accuracy, reached after two
		// hours of play).
		ph = mod(ph, 11.0);
		// vWind: x amplitude (px, signed), y lean share, z swing share, w phase.
		vWind = vec4(uWind.z * aWind.z * uWind.y, uClock.w * w + e, swing, ph);
	}
	// Barrier bits 2/4 of aFrame.w: amplitude A compressed to A * R / (R + A)
	// toward the line, same pattern, smaller.
	float code = floor(aFrame.w);
	// The page slot pair sits above the barrier bits.
	code -= 8.0 * floor(code * 0.125);
	float blockR = step(4.0, code);
	float blockL = step(2.0, code - 4.0 * blockR);
	float k = 1.0;
	if ((vWind.x > 0.0 && blockR > 0.5) || (vWind.x < 0.0 && blockL > 0.5))
	{
		k = uPlant2.x / max(uPlant2.x + abs(vWind.x), 0.01);
	}
	float wPx = (aRect.y - aRect.x) / max(abs(aTexel.x), 1e-8);
	vInv = vec4(1.0 / (aTexel.x == 0.0 ? 1.0 : aTexel.x),
	            1.0 / max(aFrame.y - aFrame.x, 1e-6),
	            1.0 / max(wPx, 1.0),
	            k);
	gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
