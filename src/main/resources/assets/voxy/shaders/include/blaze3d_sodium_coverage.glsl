layout(std140) uniform VoxyCoverage {
    ivec4 CoverageOriginSize; // column X/Z origin and dimensions
    vec4 CoverageParameters; // maximum edge overlap in blocks
};
uniform usampler2D VoxySodiumMask;

uint sodiumColumnFlags(ivec2 column) {
    ivec2 cell = column - CoverageOriginSize.xy;
    if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, CoverageOriginSize.zw))) return 0u;
    return texelFetch(VoxySodiumMask, cell, 0).r;
}

bool sodiumInteriorQuad(vec2 minimum, vec2 maximum) {
    ivec2 first = ivec2(floor((minimum - 0.001) / 16.0));
    ivec2 last = ivec2(floor((maximum + 0.001) / 16.0));
    // Four corners prove full coverage only for a footprint spanning at most two columns per axis.
    if (any(greaterThan(last - first, ivec2(1)))) return false;
    return sodiumColumnFlags(first) == 1u && sodiumColumnFlags(last) == 1u
            && sodiumColumnFlags(ivec2(first.x, last.y)) == 1u
            && sodiumColumnFlags(ivec2(last.x, first.y)) == 1u;
}

bool sodiumDiscards(vec2 worldXZ, vec2 pixel) {
    ivec2 column = ivec2(floor(worldXZ / 16.0));
    uint flags = sodiumColumnFlags(column);
    if ((flags & 1u) == 0u) return false;
    float overlap = CoverageParameters.x;
    if (flags == 1u || overlap <= 0.0) return true;
    vec2 local = worldXZ - vec2(column) * 16.0;
    float edge = 16.0;
    if ((flags & 2u) != 0u) edge = min(edge, local.x);
    if ((flags & 4u) != 0u) edge = min(edge, 16.0 - local.x);
    if ((flags & 8u) != 0u) edge = min(edge, local.y);
    if ((flags & 16u) != 0u) edge = min(edge, 16.0 - local.y);
    ivec2 p = ivec2(pixel) & 3;
    int bayer = ((p.x & 1) << 1) | (p.y & 1);
    bayer = (bayer << 2) | (((p.x >> 1) & 1) << 1) | ((p.y >> 1) & 1);
    float threshold = (float(bayer) + 0.5) / 16.0;
    return 1.0 - smoothstep(0.0, overlap, edge) <= threshold;
}
