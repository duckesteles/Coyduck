package dev.coyduck.logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class LogFile {
    private static final String END = "\u0000";

    private final Path path;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread thread;

    public LogFile(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        thread = new Thread(() -> drain(writer), "Coyduck Logger writer");
        thread.setDaemon(true);
        thread.start();
    }

    public Path path() {
        return path;
    }

    public void line(String text) {
        queue.add(text);
    }

    public void close() {
        queue.add(END);
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain(BufferedWriter writer) {
        try (writer) {
            while (true) {
                String text = queue.poll(1, TimeUnit.SECONDS);
                if (text == null) {
                    writer.flush();
                    continue;
                }
                if (text.equals(END)) return;
                writer.write(text);
                writer.newLine();
                if (queue.isEmpty()) writer.flush();
            }
        } catch (IOException e) {
            CoyduckLogger.LOG.error("Could not write {}", path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
