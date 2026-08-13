#version 120

// tileWithDepth.frag: no alpha discard — coverage comes from the depth map
// (0 = discard), color blends premultiplied. Depth writes are off in the
// translucent pass; gl_FragDepth only feeds the LEQUAL test.

uniform sampler2D DIFFUSE;
uniform sampler2D DEPTH;

varying vec4 vColor;
varying vec2 vUV1;
varying vec2 vUV2;
varying float vDepthNear;
varying float vDepthFar;

void main()
{
	vec4 c = texture2D(DIFFUSE, vUV1);
	float d = texture2D(DEPTH, vUV2).r;
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
