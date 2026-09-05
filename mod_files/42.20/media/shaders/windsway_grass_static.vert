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
// object frame in this part's uv: content top v, bottom v, left u; w: barrier bits, page slot pair
layout (location = 8) in vec4 aFrame;
// leaf flutter of this part: amplitude x, y (sprite px at the wind), cell px, rate factor
layout (location = 9) in vec4 aClass;
// object class: x bend exponent factor, y blade spread factor, z tip physics factor, w sheen factor
layout (location = 10) in vec4 aClass2;
// dry factors: x damping, y flutter; z: position across the lean axis (tiles), w: steady share
layout (location = 11) in vec4 aClass3;
// body: x stem-and-block profile share, y swing factor, z lean inertia factor, w lobe amplitude of this part (px)
layout (location = 12) in vec4 aClass4;
// leaf look: x flicker amplitude (brightness), y mask share
layout (location = 13) in vec4 aClass5;

uniform mat4 ModelViewProjection;
uniform vec4 uWind;    // x: w (plants channel), y: amplitude at w, z: lean direction sign, w: unused
uniform vec4 uClock;   // x: real seconds, y: swing clock (s, mod 2048), z: unused, w: standing lean per unit of w
uniform vec4 uTurb;    // x, y: steady ramp (w lo, hi), z: per-object rate (1/s), w: contrast
uniform vec4 uMix;     // x, y, z: slow, short, per-object mix weights, w: per-object clock (cycles at rate spread 1)
uniform vec4 uResp;    // x: sensitivity spread, y: dead band max, z: response curve calm, w: curve at w 1
uniform vec4 uRing;    // x: swing gain, y: unit change rate (1/s), z: knee, w: wind excitation
uniform vec4 uRing2;   // x: rest share, y: fast share at w 1, z: upwind cap, w: unused
uniform vec4 uPlant2;  // x: soft cap R of the lean amplitude toward a fence or wall (sprite px)
uniform vec4 uLeafP;   // x: leaf clock integer cycles mod 64, y: gate density at calm, z: amplitude share below the local wind's low, w: per-object rate spread
uniform vec4 uModel;   // x: 1 = ring model, y: bend exponent added at w 1, z: damping ratio, w: ring gain
uniform vec4 uFlut;    // x: flutter amplitude px, y: onset on w * (0.5 + 0.5 g), z: rate multiple, w: column phase spread
uniform vec4 uHon;     // x: honami octave length (tiles) at the reference period, y: its advection factor, z: mix weight, w: plant period spread
uniform vec4 uHon2;    // x: reference period (s); the octave length grows with period / reference, y: ring gate rate (1/s), z: ring share removed at w 1
uniform vec4 uPTurb;   // x, y: the plants' slow and short octave lengths (tiles); z: swing gain factor, w: swing energy rate factor
uniform vec4 uLean;    // x: lean inertia lag (s), y: share of the lagged wind in the lean, z: the plants' front travel (tiles, signed), w: their front speed (tiles/s)
uniform vec4 uCross;   // crosswind octave: x length (tiles), y mix weight, z drift rate (1/s); w: growth of the per-object rate with w
uniform vec4 uCamA;    // world lane camera: x, y: offX/offY with the jiggly pixel term folded in; z, w: jiggly square offset
uniform vec4 uCamB;    // world lane camera: x, y: camera chunk centre, z: 32 * tileScale, w: 1 = world-space vertices

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
out vec4 vLeafP;
out vec4 vClass2;
out vec4 vGust;
out vec4 vBody;
out vec4 vLeafM;

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

// Local wind g and dg/dt at lag seconds ago: the two advected octaves and
// the per-object term as in TreeSway.turbulence, plus the fine octaves
// (hon > 0, ring model only; weights renormalised so the mean stays 0.5).
// The advection carries the lag: the fronts were that much further back.
// z: dg/dt of the slow part alone (front energy for the ring gate), w: g of
// the slow part alone (the lean follows it; the fine octaves only ring).
vec4 windAt(float s, float sc, float rate, float off, float lclk, float dirSign, float speed, float lag, float hon, float hlen)
{
	float adv = uLean.z - dirSign * speed * lag;
	float t = uClock.x - lag;
	vec2 n1 = noise1((s - adv) / uPTurb.x, 11.0);
	vec2 n2 = noise1((s - adv) / uPTurb.y + 7.7, 23.0);
	// Decorrelate through the salt, not the coordinate: a 4096 offset
	// costs twelve mantissa bits in float32 (the tree path does this in
	// double). noise1 hashes floor(x) + salt, so this is exact. The clock
	// is integrated over the wind-dependent rate (TreeSway.plantLocalClock):
	// rate times the absolute time turned every wind drift into a phase run.
	vec2 n3 = noise1(lclk - lag * rate, 37.0 + floor(off));
	float v = uMix.x * n1.x + uMix.y * n2.x + uMix.z * n3.x;
	float d = -uMix.x * n1.y * dirSign * speed / uPTurb.x
	        - uMix.y * n2.y * dirSign * speed / uPTurb.y
	        + uMix.z * n3.y * rate;
	float mc = uCross.y;
	if (mc > 0.0)
	{
		// Crosswind octave: noise across the lean axis that drifts in time,
		// so neighbours across the wind differ (the fronts are 1D).
		vec2 n6 = noise1(sc / uCross.x + t * uCross.z + 2.9, 53.0);
		v = (v + mc * n6.x) / (uMix.x + uMix.y + uMix.z + mc);
		d = (d + mc * n6.y * uCross.z) / (uMix.x + uMix.y + uMix.z + mc);
	}
	float dSlow = d;
	float vSlow = v;
	if (hon > 0.0)
	{
		// Two incommensurate fine octaves: one alone is a metronome that
		// the tuft beats against.
		float advH = adv * uHon.y;
		vec2 n4 = noise1((s - advH) / hlen + 3.3, 41.0);
		float hlen2 = hlen * 0.618;
		vec2 n5 = noise1((s - advH) / hlen2 + 5.1, 43.0);
		v += hon * (0.6 * n4.x + 0.4 * n5.x);
		d += -hon * dirSign * speed * uHon.y * (0.6 * n4.y / hlen + 0.4 * n5.y / hlen2);
		float norm = 1.0 / (1.0 + hon);
		v *= norm;
		d *= norm;
		dSlow *= norm;
	}
	float c = uTurb.w;
	float u = clamp((v - 0.5) * c + 0.5, 0.0, 1.0);
	float g = u * u * (3.0 - 2.0 * u);
	float slope = c * 6.0 * u * (1.0 - u);
	float us = clamp((vSlow - 0.5) * c + 0.5, 0.0, 1.0);
	float gSlow = us * us * (3.0 - 2.0 * us);
	return vec4(g, d * slope, dSlow * c * 6.0 * us * (1.0 - us), gSlow);
}

// FBORenderLevels.calculateMinLevel: Java int division truncates, the
// negative branch floors an absolute value, so floor covers both.
float minLevelOf(float level)
{
	if (level < 0.0)
	{
		float m = floor(abs(level + 1.0) * 0.5);
		return -(m + 1.0) * 2.0;
	}
	return floor(level * 0.5) * 2.0;
}

// IsoDepthHelper.getSquareDepthData(camX, camY, x, y, z).depthStart,
// replicated op for op (getChunkDepthData + calculateDepth) so the value
// matches the capture's floats; the camera chunk centre rides in uCamB.
float depthStartAt(float x, float y, float z)
{
	float wx = floor(x * 0.125);
	float wy = floor(y * 0.125);
	float ix = (10.0 + uCamB.x - wx) * 8.0;
	float iy = (10.0 + uCamB.y - wy) * 8.0;
	float level = floor(z);
	float ds = (ix + iy) / 8.0;
	ds = ds / 40.0;
	ds = ds * 0.46187335;
	ds = ds - minLevelOf(level) * 0.0028867084;
	float xMod = 8.0 - (x - floor(x * 0.125) * 8.0);
	float yMod = 8.0 - (y - floor(y * 0.125) * 8.0);
	float zOffset = (2.0 - (z - minLevelOf(level))) * 0.0028867084;
	ds = ds + ((xMod + yMod) / 16.0 * 0.023093667 + zOffset);
	return ds;
}

void main (void)
{
	vColor = aColor;
	vUV1 = aUV1;
	vUV2 = aUV2;
	vec2 scenePos = aPosition.xy;
	float depthNear = aPosition.z;
	float depthFar = aDepthFar;
	if (uCamB.w > 0.5)
	{
		// World lane: aPosition carries the square, aClass5.zw the corner's
		// pixel offset from the square's screen anchor (an exact float split
		// at capture, so anchor + offset rebuilds the screen corner bit for
		// bit), aDepthFar the render-y depth shift. The anchor recomputation
		// matches the capture op for op.
		float k1 = uCamB.z;
		float xts = aPosition.x * k1 - aPosition.y * k1;
		float k2 = k1 * 0.5;
		float yts = aPosition.y * k2 + aPosition.x * k2 + (0.0 - aPosition.z) * (k1 * 3.0);
		scenePos = vec2((xts - uCamA.x) + aClass5.z, (yts - uCamA.y) + aClass5.w);
		float jx = aPosition.x + uCamA.z;
		float jy = aPosition.y + uCamA.w;
		depthFar = depthStartAt(jx, jy, aPosition.z) - aDepthFar;
		depthNear = depthStartAt(jx + 1.0, jy + 1.0, aPosition.z + 1.0) - aDepthFar;
	}
	vDepthNear = depthNear;
	vDepthFar = depthFar;
	vRect = aRect;
	vTexel = aTexel;
	vFrame = aFrame;
	vWind = vec4(0.0);
	vLeafP = vec4(0.0, 0.0, aClass.z, 0.0);
	vClass2 = vec4(aClass2.x, aClass2.y, 0.0, 0.0);
	vGust = vec4(0.0);
	vBody = vec4(0.0);
	vLeafM = vec4(0.0);
	if (aWind.z > 0.0)
	{
		float w = uWind.x;
		// Steady share of the class: the constant floors scale with it, so a
		// low class moves on the fronts alone. Toward storm the floors
		// return, and the lean holds between the gusts.
		float cm = aClass3.w;
		cm += (1.0 - cm) * smoothstep(uTurb.x, uTurb.y, w);
		float seed = aWind.y * 61.0;
		float hSens = hash1(seed + 1.3);
		float hThr = hash1(seed + 2.7);
		float hRate = hash1(seed + 3.1);
		float hPhase = hash1(seed + 4.9);
		float hRing = hash1(seed + 5.3);
		float hFray = hash1(seed + 6.1);
		float hPeriod = hash1(seed + 7.7);
		float dirSign = uWind.z < 0.0 ? -1.0 : 1.0;
		float s = aWind.x + 0.6 * uPTurb.y * (hFray - 0.5);
		float speed = uLean.w;
		float rate = uTurb.z * (1.0 + uCross.w * w) * (0.7 + 0.6 * hRate);
		float sc = aClass3.z;
		float off = hPhase * 4096.0;
		float lclk = uMix.w * (0.7 + 0.6 * hRate) + fract(off);
		bool ring = uModel.x > 0.5;
		float hon = ring ? uHon.z : 0.0;
		float period = aWind.w * (1.0 - uHon.w + 2.0 * uHon.w * hPeriod);
		// Period 2048 / (11 m): the clock's 2048 s wrap is 11 m whole cycles.
		period = 2048.0 / (11.0 * max(1.0, floor(2048.0 / (11.0 * period) + 0.5)));
		// Honami wavelength ~ plant height (2-9 heights measured): a bush sees
		// a longer, slower wave, which also keeps it under the ring's T/8
		// sampling rate.
		float hlen = uHon.x * max(1.0, period / uHon2.x);
		vec4 gg = windAt(s, sc, rate, off, lclk, dirSign, speed, 0.0, hon, hlen);
		// The lean, the leaf gust and the flutter gate follow the slow wind;
		// the fine octaves reach only the ring.
		float g = ring ? gg.w : gg.x;
		float gd = gg.y;
		// Lean inertia: the crown of a tree mixes the wind now with the wind
		// a lag ago (TreeSway.leanSmooth); same here, the swing carries the
		// fast part.
		float gL = g;
		// Change energy over a window (now, half the lag, the lag): an
		// envelope that follows the instant derivative jumps mid-swing, and
		// a jump in the envelope is a jolt.
		float gd2 = gd * gd;
		if (uLean.x > 0.0)
		{
			vec4 gl = windAt(s, sc, rate, off, lclk, dirSign, speed, uLean.x, hon, hlen);
			vec4 gh = windAt(s, sc, rate, off, lclk, dirSign, speed, 0.5 * uLean.x, hon, hlen);
			// A woody body follows the lagged wind more (class inertia factor).
			gL = mix(g, ring ? gl.w : gl.x, min(1.0, uLean.y * aClass4.z));
			gd2 = (gd2 + gh.y * gh.y + gl.y * gl.y) / 3.0;
		}
		float sens = 1.0 - uResp.x + 2.0 * uResp.x * hSens;
		float thr = uResp.y * hThr * (1.0 - w);
		float e = max((gL - thr) / (1.0 - thr), 0.0);
		float curve = uResp.z + (uResp.w - uResp.z) * w;
		e = min(pow(e + 0.00001, curve) * sens, 1.0);
		float ph = uClock.y / period + hRing;
		// The fragment takes sin of ph and of ph / 0.55 (model 0) or of a
		// whole multiple (flutter); 11 is a period of all of them, so the
		// wrap is exact and the sine argument stays small (past ~48k rad
		// the SFU sine loses accuracy, reached after two hours of play).
		ph = mod(ph, 11.0);
		float swing;
		float ringLag = 0.0;
		if (ring)
		{
			// Damped oscillator at the object's own period driven by the
			// wind's change: x(t) = sum h(k dl) g'(t - k dl) dl with
			// h = exp(-zeta w t) sin(w t), dl = T / 8, twelve samples (1.5 T),
			// so a gust pushes, the blade overshoots and rings down at its
			// own rate; neighbours ring in the order the front reaches them,
			// which is the honami wave at f0. ringLag is the same sum one
			// sample back, for the tip lead and the return-swing snap.
			float zeta = uModel.z * aClass3.x;
			float dl = period * 0.125;
			float now = 0.0;
			float lag = 0.0;
			float front = gg.z * gg.z;
			for (int k = 1; k <= 13; ++k)
			{
				float fk = float(k);
				vec4 wk = windAt(s, sc, rate, off, lclk, dirSign, speed, fk * dl, hon, hlen);
				float gdk = wk.y;
				front += wk.z * wk.z;
				if (k <= 12)
				{
					now += exp(-zeta * 0.7853982 * fk) * sin(0.7853982 * fk) * gdk;
				}
				if (k >= 2)
				{
					lag += exp(-zeta * 0.7853982 * (fk - 1.0)) * sin(0.7853982 * (fk - 1.0)) * gdk;
				}
			}
			// Front gate: mean slow-octave change energy over the window.
			float gate = (1.0 - exp(-front / (14.0 * uHon2.y * uHon2.y))) * (1.0 - uHon2.z * w);
			swing = uModel.w * sens * gate * now * dl;
			ringLag = uModel.w * sens * gate * lag * dl;
		}
		else
		{
			float rateE = uRing.y * uPTurb.w;
			float x = gd2 / (rateE * rateE);
			float xk = x * x / (x + uRing.z);
			float energy = 1.0 - exp(-(xk + uRing.w * w * g * cm));
			float rest = (uRing2.x + (1.0 - uRing2.x) * w) * cm;
			// A storm presses the grass over, it does not rock it: the swing
			// fades toward w 1, the pushes come from the lean following the field.
			swing = uRing.x * uPTurb.z * energy * sens * (rest + (1.0 - rest) * e) * (1.0 - uHon2.z * w);
		}
		// A crown rocks less than a blade for the same push.
		swing *= aClass4.y;
		ringLag *= aClass4.y;
		// Body: profile share, lobe amplitude breathing with the local wind
		// (the leaf gust share), per-object lobe phase.
		float fl = uLeafP.z * cm;
		vBody = vec4(aClass4.x, aClass4.w * (fl + (1.0 - fl) * g), hash1(seed + 9.7), 0.0);
		// vWind: x amplitude (px, signed), y lean share, z swing share (model
		// 0) or ring displacement share (model 1), w phase. The standing
		// lean takes its room from the gust lean, not from the cap.
		float m = uClock.w * w * cm;
		vWind = vec4(uWind.z * aWind.z * uWind.y, m + (1.0 - m) * e, swing, ph);
		// Sheen from the advected octaves alone: neighbours share it, so it
		// reads as a wave over the meadow; the per-object term would flash
		// single tiles. Signed around the mean, the wind field's contrast.
		float adv = uLean.z;
		float sheenRaw = (uMix.x * noise1((s - adv) / uPTurb.x, 11.0).x + uMix.y * noise1((s - adv) / uPTurb.y + 7.7, 23.0).x)
		               / max(uMix.x + uMix.y, 0.001);
		// Flutter gate: the galloping mode sets in above an onset and
		// saturates (measured: ~30 % above onset); a blade thing, so the
		// class tip factor scales it (a crown at full amplitude trembles).
		float gate = smoothstep(uFlut.y, uFlut.y + 0.2, w * (0.5 + 0.5 * g));
		vGust = vec4(clamp((sheenRaw - 0.5) * 2.0 * uTurb.w, -1.0, 1.0) * aClass2.w, aClass2.z, ringLag,
		             ring ? uFlut.x * gate * aClass3.y * aClass2.z : 0.0);
		// Leaf flutter: the amplitude breathes with the local wind, the
		// rate is spread per object (the fragment quantises it per cell),
		// the object's phase offset rides in vClass2.w.
		float hLeaf = hash1(seed + 8.3);
		float gustF = fl + (1.0 - fl) * g;
		float lrate = aClass.w * (1.0 - uLeafP.w + 2.0 * uLeafP.w * hLeaf);
		vLeafP = vec4(aClass.x * gustF, aClass.y * gustF, aClass.z, lrate);
		vLeafM = vec4(aClass5.y, aClass5.x, aWind.y, 0.0);
		vClass2.z = g;
		vClass2.w = hLeaf;
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
	gl_Position = ModelViewProjection * vec4(scenePos, 0.0, 1.0);
}
