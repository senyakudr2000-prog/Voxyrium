#version 330
#moj_import <voxy:blaze3d_fragment_discard.glsl>
#moj_import <voxy:blaze3d_sodium_coverage.glsl>

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
flat in vec4 tintColor;
flat in vec4 vertexLighting;
flat in int modelId;
flat in int quadFlags;
in float sphericalDistance;
in vec2 worldXZ;

out vec4 fragColor;

float linearFogValue(float fogDistance, float start, float end) {
    if (fogDistance <= start) {
        return 0.0;
    }
    if (fogDistance >= end) {
        return 1.0;
    }
    return (fogDistance - start) / (end - start);
}

void main() {
    VOXY_INIT_FRAGMENT();
    int face = (quadFlags >> 8) & 7;
    vec2 modelBase = vec2(modelId & 0xFF, (modelId >> 8) & 0xFF) / 256.0;
    vec2 faceBase = vec2(face >> 1, face & 1) / (vec2(3.0, 2.0) * 256.0);
    vec2 tile;
    vec2 repeatedUv = modf(texCoord0, tile);
    // Geometry expands slightly to close seams. Edge UVs must sample this face,
    // rather than a neighbouring face/model tile, at every uploaded mip level.
    repeatedUv = clamp(repeatedUv, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
    vec2 atlasScale = vec2(1.0) / (vec2(3.0, 2.0) * 256.0);
    vec2 atlasUv = modelBase + faceBase + repeatedUv * atlasScale;
    // All lanes compute derivatives before any Sodium/alpha rejection.
    vec2 dx = dFdx(texCoord0 * atlasScale);
    vec2 dy = dFdy(texCoord0 * atlasScale);
    if (sodiumDiscards(worldXZ, gl_FragCoord.xy)) VOXY_DISCARD_FRAGMENT();
    // Match Native: the seam expansion must not create a new repeated tile at
    // the outside of a merged/cropped quad, even when that face is fully solid.
    vec2 lastTile = vec2((quadFlags >> 15) & 15, (quadFlags >> 19) & 15);
    if (any(notEqual(tile, clamp(tile, vec2(0.0), lastTile)))) VOXY_DISCARD_FRAGMENT();
    vec4 color = textureGrad(Sampler0, atlasUv, dx, dy);
    bool translucent = ((quadFlags >> 14) & 1) != 0;
    bool useCutout = ((quadFlags >> 13) & 1) != 0;
    int tintState = (quadFlags >> 11) & 3;
    // Full opaque faces need only the filtered sample. Cutout/fluid/tint classification
    // shares one mip-zero fetch instead of fetching it again for tint detection.
    vec4 mipZero = vec4(1.0);
    if (translucent || useCutout || tintState == 1) mipZero = textureLod(Sampler0, atlasUv, 0.0);
    float mipZeroAlpha = mipZero.a;
    if ((translucent && mipZeroAlpha == 0.0) || (!translucent && useCutout && mipZeroAlpha <= 0.1)) {
        VOXY_DISCARD_FRAGMENT();
    }
    if (!translucent) {
        color.a = 1.0;
    }

    bool applyTint = tintState == 2;
    if (tintState == 1) {
        applyTint = abs(mipZero.r - mipZero.g) < 0.02 && abs(mipZero.g - mipZero.b) < 0.02;
    }
    if (applyTint) {
        color *= tintColor;
    }
    color *= vertexLighting;
    // Render-distance fog is deliberately disabled for both Sodium and Voxy and applied once
    // from their combined depth. Environmental fog stays per geometry, matching Sodium.
    float fogValue = linearFogValue(
            sphericalDistance, FogEnvironmentalStart, FogEnvironmentalEnd);
    color.rgb = mix(color.rgb, FogColor.rgb, fogValue * FogColor.a);
    fragColor = color * ColorModulator;
}
