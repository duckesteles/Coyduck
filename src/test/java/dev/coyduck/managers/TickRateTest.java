package dev.coyduck.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickRateTest {
    private static final float EPSILON = 1.0E-3f;

    @Test
    void fullRateRightAfterJoin() {
        TickRate rate = new TickRate();
        rate.join(10_000);
        rate.sample(13_000);
        assertEquals(20.0f, rate.rate(13_999), EPSILON);
    }

    @Test
    void averagesTimeUpdateIntervals() {
        TickRate rate = new TickRate();
        rate.join(0);
        long now = 0;
        for (int i = 0; i < 10; i++) rate.sample(now += 1000);
        for (int i = 0; i < 10; i++) rate.sample(now += 2000);
        assertEquals(15.0f, rate.rate(now), EPSILON);
    }

    @Test
    void keepsTheLastTwentySamples() {
        TickRate rate = new TickRate();
        rate.join(0);
        long now = 0;
        for (int i = 0; i < 20; i++) rate.sample(now += 4000);
        for (int i = 0; i < 20; i++) rate.sample(now += 1000);
        assertEquals(20.0f, rate.rate(now), EPSILON);
    }

    @Test
    void fastUpdatesClampToTwenty() {
        TickRate rate = new TickRate();
        rate.join(0);
        rate.sample(5_000);
        rate.sample(5_100);
        assertEquals(12.0f, rate.rate(5_100), EPSILON);
    }

    @Test
    void noSamplesMeansFullRate() {
        TickRate rate = new TickRate();
        rate.join(0);
        assertEquals(20.0f, rate.rate(60_000), EPSILON);
    }

    @Test
    void joinForgetsTheOldServer() {
        TickRate rate = new TickRate();
        rate.join(0);
        long now = 0;
        for (int i = 0; i < 20; i++) rate.sample(now += 4000);
        rate.join(now);
        rate.sample(now + 5000);
        assertEquals(4.0f, rate.rate(now + 5000), EPSILON);
    }

    @Test
    void tickLengthNeverDropsBelowFiftyMillis() {
        assertEquals(50.0f, TickRate.length(20.0f), EPSILON);
        assertEquals(50.0f, TickRate.length(25.0f), EPSILON);
        assertEquals(100.0f, TickRate.length(10.0f), EPSILON);
        assertEquals(50.0f, TickRate.length(0.0f), EPSILON);
        assertEquals(50.0f, TickRate.length(Float.NaN), EPSILON);
    }
}
