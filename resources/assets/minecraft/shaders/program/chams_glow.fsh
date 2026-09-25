#version 120

uniform sampler2D DiffuseSampler;
uniform vec2 BlurDir;
uniform vec2 InSize;
uniform float Spread;
varying vec2 texCoord;
varying vec2 oneTexel;

void main() {
    // Gaussian convolution of the filled silhouette: a rounded halo, without square max-filter corners.
    // Alpha-weighted color prevents the transparent background from darkening the entity's color.
    vec4 sum = vec4(0.0);
    float total = 0.0;
    float scale = clamp(InSize.y / 720.0, 0.75, 2.0) * Spread;
    for (int i = -8; i <= 8; ++i) {
        float offset = float(i);
        float weight = exp(-offset * offset / 18.0);
        vec2 uv = clamp(texCoord + oneTexel * BlurDir * offset * scale,
                        oneTexel * 0.5, vec2(1.0) - oneTexel * 0.5);
        vec4 sample = texture2D(DiffuseSampler, uv);
        sum += vec4(sample.rgb * sample.a, sample.a) * weight;
        total += weight;
    }
    gl_FragColor = vec4(sum.rgb / max(sum.a, 0.0001), sum.a / total);
}
