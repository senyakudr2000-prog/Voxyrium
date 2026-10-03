#version 330
uniform sampler2D Sampler0;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    float depth = 1.0;
    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
        ivec2 p = base + ivec2(x, y);
        // Padding and uncovered source pixels cannot prove occlusion.
        depth = min(depth, any(greaterThanEqual(p, size)) ? 0.0 : texelFetch(Sampler0, p, 0).r);
    }
    fragColor = vec4(depth);
}
