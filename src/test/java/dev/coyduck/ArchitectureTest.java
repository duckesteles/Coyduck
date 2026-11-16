package dev.coyduck;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
    private static final Path SOURCES = Path.of(System.getProperty("coyduck.sources", "src/main/java"));
    private static final String ROOT = "dev.coyduck.";
    private static final Set<String> CORE_PACKAGES = Set.of("dev.coyduck.core", "dev.coyduck.events", "dev.coyduck.managers");
    private static final Set<String> LOGIC_PACKAGES = Set.of("dev.coyduck.logic");
    private static final Set<String> SHELL_PACKAGES = Set.of("dev.coyduck.modules", "dev.coyduck.hud", "dev.coyduck.gui", "dev.coyduck.commands", "dev.coyduck.dev");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?(dev\\.coyduck\\.[\\w.]+);", Pattern.MULTILINE);
    private static final Pattern QUALIFIED = Pattern.compile("\\bdev\\.coyduck\\.[a-z][\\w.]*?\\.[A-Z]\\w*");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z]\\w*\\b");

    private static Map<String, String> sources;
    private static Map<String, String> reachedFrom;
    private static Map<String, String> logicReaches;

    @BeforeAll
    static void scan() {
        sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> sources.put(className(file), read(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reachedFrom = closure(CORE_PACKAGES);
        logicReaches = closure(LOGIC_PACKAGES);
    }

    @Test
    void coreHasClasses() {
        assertTrue(reachedFrom.keySet().stream().anyMatch(name -> CORE_PACKAGES.contains(packageOf(name))));
    }

    @Test
    void coreDoesNotUseMeteor() {
        List<String> offenders = reachedFrom.keySet().stream()
            .filter(name -> sources.get(name).contains("import meteordevelopment.meteorclient") || sources.get(name).contains("import static meteordevelopment.meteorclient"))
            .map(name -> chain(reachedFrom, name))
            .toList();
        assertTrue(offenders.isEmpty(), "core reaches Meteor:\n" + String.join("\n", offenders));
    }

    @Test
    void coreDoesNotUseShell() {
        List<String> offenders = reachedFrom.keySet().stream()
            .filter(name -> SHELL_PACKAGES.contains(packageOf(name)) || name.equals("dev.coyduck.Coyduck") || name.equals("dev.coyduck.MeteorHost"))
            .map(name -> chain(reachedFrom, name))
            .toList();
        assertTrue(offenders.isEmpty(), "core reaches the Meteor shell:\n" + String.join("\n", offenders));
    }

    @Test
    void logicIsSelfContained() {
        assertTrue(logicReaches.keySet().stream().anyMatch(name -> LOGIC_PACKAGES.contains(packageOf(name))));
        List<String> offenders = logicReaches.keySet().stream()
            .filter(name -> !LOGIC_PACKAGES.contains(packageOf(name)) && LOGIC_PACKAGES.contains(packageOf(logicReaches.get(name))))
            .map(name -> chain(logicReaches, name))
            .toList();
        assertTrue(offenders.isEmpty(), "logic depends on stateful code:\n" + String.join("\n", offenders));
    }

    @Test
    void scannerSeesKnownLinks() {
        assertTrue(dependencies("dev.coyduck.managers.MiningManager").contains("dev.coyduck.core.Bus"));
        assertTrue(dependencies("dev.coyduck.managers.MiningManager").contains("dev.coyduck.managers.InventoryManager"));
        assertFalse(dependencies("dev.coyduck.managers.PopTracker").contains("dev.coyduck.modules.AutoTotem"));
    }

    private static Map<String, String> closure(Set<String> roots) {
        Map<String, String> parent = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String name : sources.keySet()) {
            if (!roots.contains(packageOf(name))) continue;
            parent.put(name, null);
            queue.add(name);
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : dependencies(current)) {
                if (parent.containsKey(next)) continue;
                parent.put(next, current);
                queue.add(next);
            }
        }
        return parent;
    }

    private static Set<String> dependencies(String name) {
        String text = strip(sources.get(name));
        Set<String> out = new TreeSet<>();
        Matcher imports = IMPORT.matcher(text);
        while (imports.find()) resolve(imports.group(1)).ifPresent(out::add);
        Matcher qualified = QUALIFIED.matcher(text);
        while (qualified.find()) resolve(qualified.group()).ifPresent(out::add);
        String pkg = packageOf(name);
        Matcher identifiers = IDENTIFIER.matcher(text);
        while (identifiers.find()) {
            String sibling = pkg + "." + identifiers.group();
            if (!sibling.equals(name) && sources.containsKey(sibling)) out.add(sibling);
        }
        return out;
    }

    private static Optional<String> resolve(String reference) {
        String candidate = reference;
        while (candidate.startsWith(ROOT)) {
            if (sources.containsKey(candidate)) return Optional.of(candidate);
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) break;
            candidate = candidate.substring(0, dot);
        }
        return Optional.empty();
    }

    private static String chain(Map<String, String> parents, String name) {
        List<String> path = new ArrayList<>();
        for (String at = name; at != null; at = parents.get(at)) path.add(0, at.substring(ROOT.length()));
        return "  " + String.join(" -> ", path);
    }

    private static String strip(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "").replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static String packageOf(String name) {
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String className(Path file) {
        String relative = SOURCES.relativize(file).toString();
        return relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
