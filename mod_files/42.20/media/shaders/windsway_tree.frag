#version 120

// Tree quad is drawn undeformed and widened by the sway reach; the crown
// bend, the secondary crown sway and the leaf flutter are sample offsets,
// so the trunk stays put and the deformation is a curve instead of a shear.
// Outline mode reproduces vboRenderer_Tree.frag on the same warped lookup.
// Everything per tree or per sprite arrives as varyings (constant across
// the quad); only frame-wide values are uniforms.

uniform sampler2D DIFFUSE;

uniform vec4 uParams;    // x: lobe clock, y: leaf clock (cycles, integrated on the game thread), z: leaf cell px, w: blend sharpening (1 = bilinear, large = nearest)
uniform vec2 uMode;      // x: 1 = outline pass, y: 1 = alpha capped at the fade alpha
uniform vec2 stepSize;
uniform vec4 outlineColor;

varying vec4 vColor;
varying vec4 vUVH;       // xy: uv, z: height fraction (0 base, 1 top), w: branch cell px
varying vec4 vPixTexel;  // xy: field pixel coords (with per-tree offset), zw: uv extent of one texel
varying vec4 vRect;      // xy: atlas u range, zw: atlas v range (whole sprite, not the depth segment)
varying vec4 vBend;      // x: top offset in u units, y: height fraction where bending starts, z: bend exponent, w: lobe amplitude u
varying vec4 vLeaf;      // x: flutter amplitude u, y: amplitude v, z: v at leaf band top, w: v at leaf band bottom
varying vec4 vMisc;      // x: fade alpha, y: height fraction where bending ends (1 = tip; below 1 the crown above moves as a block), z: lobe amplitude v, w: leaf clock rate

float hash(vec2 p)
{
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Every lattice cell carries its own oscillator (random phase, frequency
// within +-20 % of the clock rate); the field interpolates them, so motion
// is smooth in space and periodic in time without scrolling.
float osc(vec2 i, float c)
{
	float ph = hash(i);
	float fr = 0.8 + 0.4 * hash(i + vec2(7.3, 3.1));
	return sin(6.2831853 * (fr * c + ph));
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

// Sub-texel offsets under NEAREST tear the crown into shifted rows;
// bilinear on premultiplied colour keeps the motion smooth without dark
// fringes. Samples outside the sprite's atlas rect count as transparent.
// The result stays premultiplied all the way to the blend (ONE,
// ONE_MINUS_SRC_ALPHA): the silhouette is fractional alpha at every edge,
// and straight colour there adds (1 - a) * rgb on top of the background,
// a bright rim that changes with the sub-texel offset as the camera moves.
vec4 fetch(vec2 uv)
{
	if (uv.x < vRect.x || uv.x > vRect.y || uv.y < vRect.z || uv.y > vRect.w)
	{
		return vec4(0.0);
	}
	vec4 c = texture2D(DIFFUSE, uv);
	c.rgb *= c.a;
	return c;
}

vec4 sampleTree(vec2 uv)
{
	vec2 texel = vPixTexel.zw;
	vec2 t = uv / texel - 0.5;
	vec2 i = floor(t);
	vec2 f = clamp((t - i - 0.5) * uParams.w + 0.5, 0.0, 1.0);
	vec2 c00 = (i + 0.5) * texel;
	return mix(mix(fetch(c00), fetch(c00 + vec2(texel.x, 0.0)), f.x),
	           mix(fetch(c00 + vec2(0.0, texel.y)), fetch(c00 + texel), f.x), f.y);
}

vec4 outline(vec2 uv)
{
	float alpha = 4.0 * texture2D(DIFFUSE, uv).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(stepSize.x, 0.0)).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(-stepSize.x, 0.0)).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(0.0, stepSize.y)).a;
	alpha -= texture2D(DIFFUSE, uv + vec2(0.0, -stepSize.y)).a;
	alpha = clamp(alpha * outlineColor.a, 0.0, 1.0);
	return vec4(outlineColor.rgb * alpha, alpha);
}

void main()
{
	float h = clamp(vUVH.z, 0.0, 1.0);
	float b = clamp((h - vBend.y) / (vMisc.y - vBend.y), 0.0, 1.0);
	float bend = pow(max(b, 0.0001), vBend.z);
	float soft = 6.0 * vPixTexel.w;
	float crown = smoothstep(vLeaf.z - soft, vLeaf.z + soft, vUVH.y)
	            * smoothstep(vLeaf.w + soft, vLeaf.w - soft, vUVH.y);

	vec2 pix = vPixTexel.xy;
	float cb = uParams.x;
	float cl = uParams.y * vMisc.w;
	vec2 pb = pix / vUVH.w;
	float bx = field(pb, cb) + 0.5 * field(pb * 2.1 + 5.3, cb * 1.7);
	float by = field(pb + vec2(4.7, 2.3), cb * 1.15) + 0.5 * field(pb * 2.1 + 9.1, cb * 1.9);
	vec2 pl = pix / uParams.z;
	float lx = field(pl, cl);
	float ly = field(pl + 3.9, cl * 1.1);
	vec2 duv = vec2(bx * vBend.w, by * vMisc.z) * bend + vec2(lx * vLeaf.x, ly * vLeaf.y) * crown;

	vec2 uv = vec2(vUVH.x - vBend.x * bend - duv.x, vUVH.y - duv.y);
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
	gl_FragColor = vec4(col.rgb * col.a * texel.rgb, col.a * texel.a);
}
