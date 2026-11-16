package dev.coyduck.render;

public interface Canvas {
    void quad(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4, int argb);

    void quadHorizontal(double x1, double y, double z1, double x2, double z2, int argb);

    void gradientQuadVertical(double x1, double y1, double z1, double x2, double y2, double z2, int top, int bottom);

    void boxLines(double x1, double y1, double z1, double x2, double y2, double z2, int argb);

    void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb);

    void line(double x1, double y1, double z1, double x2, double y2, double z2, int from, int to);
}
