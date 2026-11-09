package dev.coyduck.logger;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LeakGuard {
    public static final double LIMIT = 1000;

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Set<String> SAFE_KEYS = Set.of("seq", "rev", "id", "tick", "ping", "after", "gap", "count", "sync", "took", "every");

    private final AtomicLong scrubbed = new AtomicLong();

    public String clean(String text) {
        Matcher matcher = NUMBER.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (partOfWord(text, start, end) || safe(text, start, end)) continue;
            if (Math.abs(Double.parseDouble(matcher.group())) < LIMIT) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, start).append('?');
            last = end;
            scrubbed.incrementAndGet();
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    public long scrubbed() {
        return scrubbed.get();
    }

    private static boolean partOfWord(String text, int start, int end) {
        int first = text.charAt(start) == '-' ? start + 1 : start;
        if (first > 0) {
            char before = text.charAt(first - 1);
            if (Character.isLetter(before) || before == '_') return true;
        }
        if (end < text.length()) {
            char after = text.charAt(end);
            if (Character.isLetter(after) && !text.startsWith("ms", end)) return true;
            if (after == '_') return true;
        }
        return false;
    }

    private static boolean safe(String text, int start, int end) {
        if (start > 0 && text.charAt(start - 1) == '#') return true;
        if (text.startsWith("ms", end)) return true;
        if (start > 0 && text.charAt(start - 1) == '=') {
            int key = start - 1;
            while (key > 0 && (Character.isLetter(text.charAt(key - 1)) || text.charAt(key - 1) == '-')) key--;
            return SAFE_KEYS.contains(text.substring(key, start - 1));
        }
        return false;
    }
}
