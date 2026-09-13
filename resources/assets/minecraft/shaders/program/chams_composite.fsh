#version 120

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;
uniform sampler2D HaloSampler;
uniform vec2 InSize;
varying vec2 texCoord;
varying vec2 oneTexel;

void main() {
    vec4 model = texture2D(DiffuseSampler, texCoord);
    vec4 mask = texture2D(MaskSampler, texCoord);
    vec4 halo = texture2D(HaloSampler, texCoord);
    float coverage = max(model.a, mask.a);
    float edge = 0.0;
    vec3 edgeColor = vec3(0.0);
    float scale = clamp(InSize.y / 720.0, 0.75, 2.0);
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            vec2 uv = clamp(texCoord + vec2(float(x), float(y)) * oneTexel * scale,
                            oneTexel * 0.5, vec2(1.0) - oneTexel * 0.5);
            vec4 neighbor = texture2D(MaskSampler, uv);
            if (neighbor.a > edge) {
                edge = neighbor.a;
                edgeColor = neighbor.rgb;
            }
        }
    }
    // Keep the broad glow outside the model; a bright narrow rim holds small details together.
    float rimAlpha = edge * (1.0 - coverage) * 0.9;
    float haloAlpha = smoothstep(0.0, 0.65, halo.a) * (1.0 - coverage) * 0.55;
    float outerAlpha = rimAlpha + haloAlpha * (1.0 - rimAlpha);
    vec3 outer = mix(halo.rgb, vec3(1.0), 0.08) * haloAlpha * (1.0 - rimAlpha)
               + mix(edgeColor, vec3(1.0), 0.18) * rimAlpha;
    vec3 surface = mix(model.rgb, mask.rgb, mask.a * 0.06);
    float alpha = model.a + outerAlpha * (1.0 - model.a);
    vec3 color = surface * model.a + outer * (1.0 - model.a);
    gl_FragColor = vec4(color / max(alpha, 0.0001), alpha);
}
