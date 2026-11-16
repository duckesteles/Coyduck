package dev.coyduck.render;

import net.minecraft.client.gui.DrawContext;

public interface Overlay {
    DrawContext context();

    void flush();

    boolean project(double x, double y, double z, double scale);

    void unproject();

    double textWidth(String text, boolean shadow);

    double textHeight(boolean shadow);

    double text(String text, double x, double y, int argb, boolean shadow);

    void rect(double x, double y, double width, double height, int argb);

    boolean customFont();
}
