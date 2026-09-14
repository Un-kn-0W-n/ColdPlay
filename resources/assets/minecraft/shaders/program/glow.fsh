#version 120

uniform sampler2D DiffuseSampler;

varying vec2 texCoord;
varying vec2 oneTexel;

uniform vec2 InSize;

uniform vec2 BlurDir;
uniform float Radius;

void main() {
    vec4 glow = vec4(0.0);
    // Tent-weighted max, not a blur. The sobel pass hands us a 1px line, so averaging (blur.fsh's
    // divide, in either its summed or normalised form) either saturates the whole band into a hard
    // rim or crushes the peak to 1/Radius and vanishes. Taking the strongest weighted neighbour
    // instead puts alpha 1 on the silhouette and ramps it to 0 exactly Radius texels out, and is
    // idempotent for flat regions -- so the second, perpendicular pass leaves the first one's ramp
    // alone instead of amplifying it.
    for(float r = -Radius; r <= Radius; r += 1.0) {
        vec4 sample = texture2D(DiffuseSampler, texCoord + oneTexel * r * BlurDir);
        float weighted = sample.a * (1.0 - abs(r) / Radius);
        if (weighted > glow.a) {
            glow = vec4(sample.rgb, weighted);
        }
    }
    gl_FragColor = glow;
}
