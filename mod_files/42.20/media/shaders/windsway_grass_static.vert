#version 330

// tileWithDepth.vert with the per-draw depth uniforms moved into vertex
// attributes so one batch covers many tiles: aPosition.z carries zDepthBlendZ
// (near), aDepthFar carries zDepthBlendToZ. gl_FragDepth does the depth work.

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec4 aColor;
layout (location = 2) in vec2 aUV1;
layout (location = 3) in vec2 aUV2;
layout (location = 4) in float aDepthFar;

uniform mat4 ModelViewProjection;

out vec4 vColor;
out vec2 vUV1;
out vec2 vUV2;
out float vDepthNear;
out float vDepthFar;

void main (void)
{
	vColor = aColor;
	vUV1 = aUV1;
	vUV2 = aUV2;
	vDepthNear = aPosition.z;
	vDepthFar = aDepthFar;
	gl_Position = ModelViewProjection * vec4(aPosition.xy, 0.0, 1.0);
}
