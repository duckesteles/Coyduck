package dev.coyduck.logic;

public final class YawRate {
    public static final float LIMIT = 250.0f;
    private static final float STATIONARY = 32.0f;
    private static final long BUCKET = 333;
    private static final long WINDOW = 999;
    private static final long RESET = 999;

    private static final class Track {
        float lastYaw;
        long lastTime = Long.MIN_VALUE;
        float sumYaw;

        void copy(Track other) {
            lastYaw = other.lastYaw;
            lastTime = other.lastTime;
            sumYaw = other.sumYaw;
        }

        float feed(float yaw, long now, long jitter) {
            if (lastTime == Long.MIN_VALUE || now - lastTime > RESET) {
                lastYaw = yaw;
                lastTime = now;
                sumYaw = 0;
            }
            float diff = wrap(lastYaw - yaw);
            long elapsed = Math.max(0, now - lastTime - jitter);
            lastYaw = yaw;
            lastTime = now;
            float abs = Math.abs(diff);
            if (abs < STATIONARY) {
                sumYaw += diff;
                if (Math.abs(sumYaw) < STATIONARY) return 0;
            }
            sumYaw = 0;
            return abs / (1 + elapsed);
        }
    }

    private final Track track = new Track();
    private final Track trial = new Track();
    private final long[] times = new long[128];
    private final float[] amounts = new float[128];
    private int head;
    private int size;

    public void feed(float yaw, long now, long jitter) {
        float amount = track.feed(yaw, now, jitter);
        if (amount > 0) push(now, amount);
    }

    public boolean allows(float yaw, float back, long now, long backAt, long jitter) {
        trim(now);
        trial.copy(track);
        float there = trial.feed(yaw, now, jitter);
        float home = trial.feed(back, backAt, jitter);
        float recent = sum(now - BUCKET) + there + home;
        float all = sum(now - WINDOW) + there + home;
        return Math.max(recent * 3, all) <= LIMIT;
    }

    private void push(long now, float amount) {
        trim(now);
        if (size == times.length) {
            head = (head + 1) % times.length;
            size--;
        }
        int index = (head + size) % times.length;
        times[index] = now;
        amounts[index] = amount;
        size++;
    }

    private void trim(long now) {
        while (size > 0 && now - times[head] > WINDOW) {
            head = (head + 1) % times.length;
            size--;
        }
    }

    private float sum(long since) {
        float total = 0;
        for (int i = 0; i < size; i++) {
            int index = (head + i) % times.length;
            if (times[index] > since) total += amounts[index];
        }
        return total;
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}
