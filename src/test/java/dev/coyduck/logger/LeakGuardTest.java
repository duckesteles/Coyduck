package dev.coyduck.logger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeakGuardTest {
    private final LeakGuard guard = new LeakGuard();

    @Test
    void scrubsLargeBareNumbers() {
        assertEquals("pos=?,64.00,?", guard.clean("pos=-3062785.50,64.00,13978673.25"));
        assertEquals("x=? z=?", guard.clean("x=11054 z=-61700"));
        assertEquals("Experience Orb ?, x=143.70, z=?", guard.clean("Experience Orb 362533, x=143.70, z=-18411.29"));
        assertEquals(6, guard.scrubbed());
    }

    @Test
    void keepsRelativePositionsAndStats() {
        String line = "move pos=+12.50,-3.00,+95.25 look=-179.9,45.0 hp=19.5 dmg=12.0";
        assertEquals(line, guard.clean(line));
    }

    @Test
    void keepsIdsSequencesAndTimes() {
        String line = "attack crystal#1268726 seq=48213 rev=51200 tp id=1782953895 gone after 1450ms ping=210";
        assertEquals(line, guard.clean(line));
    }

    @Test
    void keepsNamesWithDigits() {
        String line = "pop ronnie291013 by momazos1234 near alt6.6b6t.org class_1303";
        assertEquals(line, guard.clean(line));
    }

    @Test
    void scrubsSafeLookingKeysThatAreNotListed() {
        assertEquals("target-x=? target-z=?", guard.clean("target-x=1000000 target-z=-1000000"));
    }

    @Test
    void keepsOutcomeLines() {
        String[] lines = {
            "< SETBACK since-last=1450ms last-1s places=3 attacks=1 uses=0 inside-block ground",
            "totem refill ms: count 1.2k, min 150ms, median 1200ms, p90 2400ms, max 2950ms",
            "moves sent: 12.3k, blocks refused: 4",
            "< boom at +1.00,-2.00,+3.00 result me -4.5, ronnie291013 POP",
            "< place REFUSED 2,1,-3 obsidianx38 seq=48213 after 254ms",
            "> stop -1,0,2 did not break within 1200ms, block=obsidian",
            "E jump Gamerguy11 +0.50,+0.00,-7.25 (7.27 blocks) to +3.00,-1.00,-6.00"
        };
        for (String line : lines) assertEquals(line, guard.clean(line));
    }
}
