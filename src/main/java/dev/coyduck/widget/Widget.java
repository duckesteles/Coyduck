package dev.coyduck.widget;

import dev.coyduck.feature.Settings;
import dev.coyduck.render.Panel;

import java.util.function.Supplier;

public abstract class Widget {
    public record Info(String name, String description, Supplier<Widget> factory) {}

    public interface Frame {
        Frame DETACHED = new Frame() {};

        default void size(double width, double height) {}

        default int x() {
            return 0;
        }

        default int y() {
            return 0;
        }

        default int width() {
            return 0;
        }

        default boolean rightSide() {
            return false;
        }

        default boolean inEditor() {
            return false;
        }
    }

    public final Info info;
    public final Settings settings = new Settings();
    private Frame frame = Frame.DETACHED;

    protected Widget(Info info) {
        this.info = info;
    }

    public abstract void render(Panel panel);

    public final void bind(Frame target) {
        frame = target;
    }

    protected final void size(double width, double height) {
        frame.size(width, height);
    }

    protected final int x() {
        return frame.x();
    }

    protected final int y() {
        return frame.y();
    }

    protected final int width() {
        return frame.width();
    }

    protected final boolean rightSide() {
        return frame.rightSide();
    }

    protected final boolean inEditor() {
        return frame.inEditor();
    }
}
