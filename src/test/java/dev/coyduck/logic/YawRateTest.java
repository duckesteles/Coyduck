package dev.coyduck.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YawRateTest {
    @Test
    void lookAndReturnNextTickPass() {
        YawRate rate = new YawRate();
        rate.feed(0, 1_000, 0);
        assertTrue(rate.allows(80, 0, 1_000, 1_050, 0));
    }

    @Test
    void flickAndReturnInOneBatchFreeze() {
        YawRate rate = new YawRate();
        rate.feed(0, 1_000, 0);
        assertFalse(rate.allows(90, 0, 1_000, 1_000, 0));
    }

    @Test
    void jitterBunchesTheReturn() {
        YawRate rate = new YawRate();
        rate.feed(0, 1_000, 0);
        assertTrue(rate.allows(70, 0, 1_000, 1_050, 0));
        assertFalse(rate.allows(70, 0, 1_000, 1_050, 50));
    }

    @Test
    void recentFlicksFillTheBucket() {
        YawRate rate = new YawRate();
        rate.feed(0, 1_000, 0);
        rate.feed(60, 1_000, 0);
        assertFalse(rate.allows(0, 0, 1_000, 1_050, 0));
        assertTrue(rate.allows(0, 0, 2_100, 2_150, 0));
    }

    @Test
    void smallTurnsAddUpOnlyPastStationary() {
        YawRate rate = new YawRate();
        rate.feed(0, 1_000, 0);
        for (int i = 1; i <= 10; i++) rate.feed(i * 3, 1_000 + i * 50, 0);
        assertTrue(rate.allows(30, 30, 1_600, 1_650, 0));
    }

    @Test
    void wrapsAcrossTheSeam() {
        YawRate rate = new YawRate();
        rate.feed(179, 1_000, 0);
        assertTrue(rate.allows(-179, 179, 1_000, 1_050, 0));
    }
}
