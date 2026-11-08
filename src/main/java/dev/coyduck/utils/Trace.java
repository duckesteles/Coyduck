package dev.coyduck.utils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class Trace {
    public interface Sink {
        void line(String line);

        String pos(BlockPos pos);

        String vec(Vec3d vec);
    }

    private static volatile Sink sink;

    private Trace() {}

    public static void sink(Sink value) {
        sink = value;
    }

    public static boolean on() {
        return sink != null;
    }

    public static void log(String template, Object... args) {
        Sink out = sink;
        if (out == null) return;

        StringBuilder line = new StringBuilder();
        int arg = 0;
        int from = 0;
        int at;
        while ((at = template.indexOf("{}", from)) != -1) {
            line.append(template, from, at);
            line.append(arg < args.length ? format(out, args[arg++]) : "{}");
            from = at + 2;
        }
        line.append(template, from, template.length());
        out.line(line.toString());
    }

    private static String format(Sink out, Object value) {
        return switch (value) {
            case null -> "-";
            case BlockPos pos -> out.pos(pos);
            case Vec3d vec -> out.vec(vec);
            case Float number -> String.format(Locale.ROOT, "%.1f", number);
            case Double number -> String.format(Locale.ROOT, "%.1f", number);
            case Class<?> type -> type.getSimpleName();
            default -> String.valueOf(value);
        };
    }
}
