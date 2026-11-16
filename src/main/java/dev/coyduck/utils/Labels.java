package dev.coyduck.utils;

import dev.coyduck.render.Argb;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

public final class Labels {
    private static final Color LABEL = new Color();
    private static final Vector3d POS = new Vector3d();

    private Labels() {}

    public static void damage(Vec3d at, String text, int argb, double alpha) {
        if (at == null || alpha <= 0.01) return;
        POS.set(at.x, at.y, at.z);
        if (!NametagUtils.to2D(POS, 1.0)) return;
        LABEL.r = Argb.r(argb);
        LABEL.g = Argb.g(argb);
        LABEL.b = Argb.b(argb);
        LABEL.a = (int) Math.clamp(Argb.a(argb) * alpha, 0, 255);
        TextRenderer renderer = TextRenderer.get();
        NametagUtils.begin(POS);
        renderer.beginBig();
        renderer.render(text, -renderer.getWidth(text) / 2, -renderer.getHeight() / 2, LABEL, true);
        renderer.end();
        NametagUtils.end();
    }
}
