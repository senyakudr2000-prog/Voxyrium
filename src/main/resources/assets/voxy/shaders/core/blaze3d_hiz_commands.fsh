#version 330
uniform sampler2D Sampler0;
uniform usamplerBuffer VoxyOcclusionCounts;
in vec2 texCoord;
out uvec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 slot = ivec2(pixel.x / 3, pixel.y);
    int index = slot.y * 256 + slot.x;
    uvec2 count = texelFetch(VoxyOcclusionCounts, index).rg;
    if (texelFetch(Sampler0, slot, 0).r < 0.5) count = uvec2(0u);
    // Two five-word indexed commands in three RGBA32_UINT texels (48-byte slot).
    // Opaque: 6, opaqueCount, 0, 0, 0. Water: 6, waterCount, 0, 0, 0.
    int word = pixel.x % 3;
    fragColor = word == 0 ? uvec4(6u, count.x, 0u, 0u)
            : word == 1 ? uvec4(0u, 6u, count.y, 0u) : uvec4(0u);
}
