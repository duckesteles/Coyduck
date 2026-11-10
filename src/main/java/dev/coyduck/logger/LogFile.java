package dev.coyduck.logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LogFile {
    private static final String END = "\u0000";
    private static final int CAPACITY = 200_000;

    private final Path path;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private volatile boolean dead;

    private LogFile(Path path, BufferedWriter writer) {
        this.path = path;
        thread = new Thread(() -> drain(writer), "Coyduck Logger writer");
        thread.setDaemon(true);
        thread.start();
    }

    public static LogFile open(Path directory, String stem) throws IOException {
        Files.createDirectories(directory);
        for (int i = 0; i < 100; i++) {
            Path path = directory.resolve(i == 0 ? stem + ".log" : stem + "-" + i + ".log");
            try {
                BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new LogFile(path, writer);
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        throw new IOException("no free log file name for " + stem);
    }

    public Path path() {
        return path;
    }

    public long dropped() {
        return dropped.get();
    }

    public void line(String text) {
        if (dead || !queue.offer(text)) dropped.incrementAndGet();
    }

    public void close() {
        if (!queue.offer(END)) {
            queue.clear();
            queue.offer(END);
        }
    }

    public void await() {
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
            CoyduckLogger.LOG.error("Could not write {}, logging stops for this session", path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            dead = true;
            queue.clear();
        }
    }
}
