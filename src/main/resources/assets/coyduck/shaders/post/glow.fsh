#version 330 core

in vec2 v_TexCoord;
in vec2 v_OneTexel;

uniform sampler2D u_Texture;

layout (std140) uniform OutlineData {
    int width;
    float fillOpacity;
    int shapeMode;
    float glowMultiplier;
} u_Outline;

out vec4 color;

void main() {
    vec4 center = texture(u_Texture, v_TexCoord);

    if (center.a != 0.0) {
        color = vec4(center.rgb, center.a * u_Outline.fillOpacity);
        return;
    }

    int radius = u_Outline.width * 3;
    int stride = max(1, radius / 6);
    float nearest = float(radius + 1);
    vec3 tint = vec3(0.0);

    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec4 probe = texture(u_Texture, v_TexCoord + v_OneTexel * vec2(x, y));
            float dist = length(vec2(x, y));

            if (probe.a != 0.0 && dist < nearest) {
                nearest = dist;
                tint = probe.rgb;
            }
        }
    }

    if (nearest > 1.5) {
        for (int x = -radius; x <= radius; x += stride) {
            for (int y = -radius; y <= radius; y += stride) {
                float dist = length(vec2(x, y));
                if (dist >= nearest) continue;

                vec4 probe = texture(u_Texture, v_TexCoord + v_OneTexel * vec2(x, y));

                if (probe.a != 0.0) {
                    nearest = dist;
                    tint = probe.rgb;
                }
            }
        }
    }

    if (nearest > float(radius)) discard;

    float line = 1.0 - smoothstep(1.0, 2.0, nearest);
    float halo = 1.0 - nearest / float(radius + 1);
    float alpha = max(line, halo * halo * u_Outline.glowMultiplier * 0.7);

    color = vec4(mix(tint, vec3(1.0), line * 0.35), clamp(alpha, 0.0, 1.0));
}
