#version 330
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
uniform sampler2D Sampler0;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    float depth = texture(Sampler0, texCoord).r;
    if (depth <= 0.0) discard;
    float ndcDepth = ModelOffset.x > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 view = ModelViewMat * vec4(texCoord * 2.0 - 1.0, ndcDepth, 1.0);
    vec4 clip = TextureMat * view;
    float lodDepth = clip.z / clip.w;
    if (ModelOffset.x <= 0.5) lodDepth = lodDepth * 0.5 + 0.5;
    // Sodium surfaces closer than Voxy's near plane still occlude distant geometry.
    gl_FragDepth = clamp(lodDepth, 0.0, 1.0);
    fragColor = vec4(0.0);
}
