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
uniform vec4 uPlant2;  // x: barrier cap R (sprite px), y: 1 = bend (A/B), z: 1 / blade cell px

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

vec4 diffuseAt(float s, vec2 uv)
{
	if (s < 4.0)
	{
		if (s < 2.0)
		{
			return s < 1.0 ? texture2D(DIFFUSE0, uv, -1.0) : texture2D(DIFFUSE1, uv, -1.0);
		}
		return s < 3.0 ? texture2D(DIFFUSE2, uv, -1.0) : texture2D(DIFFUSE3, uv, -1.0);
	}
	if (s < 6.0)
	{
		return s < 5.0 ? texture2D(DIFFUSE4, uv, -1.0) : texture2D(DIFFUSE5, uv, -1.0);
	}
	return s < 7.0 ? texture2D(DIFFUSE6, uv, -1.0) : texture2D(DIFFUSE7, uv, -1.0);
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
	float slot = floor(floor(vFrame.w) * 0.125);
	float depthSlot = floor(slot * 0.125);
	float diffuseSlot = slot - 8.0 * depthSlot;
	if (vWind.x != 0.0 && uPlant2.y > 0.5)
	{
		float seed = fract(vFrame.w);
		// Height and blade field in the object's frame: all parts bend as one.
		float h = clamp((vFrame.y - vUV1.y) * vInv.y, 0.0, 1.0);
		float bend = pow(h + 0.0001, uPlant.x);
		float fast = uRing2.y * uWind.x * sin(6.2831853 * (vWind.w / 0.55 + 0.37));
		float total0 = vWind.y + vWind.z * (sin(6.2831853 * vWind.w) + fast);
		if (total0 > 1.0)
		{
			float o = total0 - 1.0;
			total0 = 1.0 + o / (1.0 + o / 0.3);
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
		float var = min(uPlant.w, safe);
		float px = (vUV1.x - vFrame.z) * vInv.x;
		float bn = noise1(px * uPlant2.z + seed * 977.0, 3.0);
		float osc = sin(6.2831853 * (vWind.w + 0.15 * var * (bn - 0.5))) + fast;
		float total = vWind.y + vWind.z * osc;
		if (total > 1.0)
		{
			float o = total - 1.0;
			total = 1.0 + o / (1.0 + o / 0.3);
		}
		else if (total < 0.0)
		{
			total = uRing2.z > 0.0 ? total / (1.0 - total / uRing2.z) : 0.0;
		}
		float blade = 1.0 + var * (bn - 0.5);
		float dx = vWind.x * total * bend * blade * k;
		float dy = uPlant.y * dx * dx * vInv.z;
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
	vec4 c = sampleDiffuse(diffuseSlot, uv);
	float d = depthAt(depthSlot, uv2);
	c *= vColor;
	c.rgb *= vColor.a;
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
