package me.mykindos.betterpvp.balancesim.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control law, driven with synthetic tick periods.
 *
 * <p>Worth testing precisely because its failures are quiet. A governor that never grows looks like
 * a slow machine, one that never cuts looks like a governor that is switched off, and one that
 * oscillates looks like ordinary run-to-run variance -- none of the three would be noticed from a
 * sweep's output, and all three would silently change what the sweep measured.
 */
class ConcurrencyGovernorTest {

    /** Ticks to advance so at least one adjustment boundary is crossed. */
    private static final int ENOUGH_TICKS = 250;

    /** A governor reading a fixed median tick time, standing in for the server's tick ring. */
    private static ConcurrencyGovernor governor(int floor, int ceiling, double target, int start,
                                                double mspt) {
        return new ConcurrencyGovernor(floor, ceiling, target, start, () -> mspt);
    }

    /** Advances {@code count} ticks. The tick time comes from the governor's own supplier. */
    private static void feed(ConcurrencyGovernor governor, int count) {
        for (int i = 0; i < count; i++) {
            governor.observe();
        }
    }

    @Test
    @DisplayName("a healthy server's 50ms tick PERIOD must not be mistaken for being at budget")
    void headroomIsVisibleAtAHealthyTickRate() {
        // The bug this pins. A server holding 20 TPS sleeps out the rest of its budget, so the gap
        // between ticks is exactly 50ms however idle it is -- feed the controller that and it can
        // never see headroom, and against any target under 50 it reads 50 > target and cuts on a
        // perfectly healthy server until it reaches the floor. The signal has to be tick processing
        // time, which on the same healthy server is the 12ms below.
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, 12.0);

        feed(governor, ENOUGH_TICKS);

        assertTrue(governor.getLimit() > 300,
                "12ms of work against a 45ms target is headroom and must grow, got " + governor.getLimit());
    }

    @Test
    @DisplayName("grows toward the ceiling while there is tick headroom")
    void growsWhenUnderTarget() {
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, 30.0);

        feed(governor, ENOUGH_TICKS);

        assertTrue(governor.getLimit() > 300,
                "30ms under a 45ms target must raise the limit, got " + governor.getLimit());
        assertTrue(governor.getLimit() <= 600, "the ceiling is the warmed-up arena count and is absolute");
    }

    @Test
    @DisplayName("cuts when the tick budget is being missed")
    void cutsWhenOverTarget() {
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, 90.0);

        feed(governor, ENOUGH_TICKS);

        assertTrue(governor.getLimit() < 300,
                "a 90ms median against a 45ms target must cut, got " + governor.getLimit());
    }

    @Test
    @DisplayName("never goes below the floor, however bad the tick rate")
    void respectsFloor() {
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, 500.0);

        // Far past the point repeated multiplicative decay would otherwise reach zero.
        feed(governor, 10_000);

        assertEquals(16, governor.getLimit(),
                "a sweep governed to nothing would look exactly like a wedged one");
    }

    @Test
    @DisplayName("never exceeds the ceiling, however much headroom there is")
    void respectsCeiling() {
        final ConcurrencyGovernor governor = governor(16, 320, 45.0, 300, 5.0);

        feed(governor, 10_000);

        assertEquals(320, governor.getLimit(),
                "the ceiling is the number of arenas warmed up; exceeding it would need one built mid-run");
    }

    @Test
    @DisplayName("settles instead of hunting when sitting just under target")
    void deadbandStopsOscillation() {
        // Inside the growth headroom: under target, but not by enough to be worth another step.
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, 43.0);

        feed(governor, 5000);

        assertEquals(300, governor.getLimit(),
                "a median just under target must neither grow nor cut, or the run spends its life"
                        + " oscillating and every duel is measured at a different concurrency");
    }

    @Test
    @DisplayName("holds still while the tick history is not yet readable")
    void waitsForASignal() {
        // What the server reports before its tick ring has filled. Acting on it would mean growing
        // to the ceiling on no evidence, since an unpopulated ring reads as no work at all.
        final ConcurrencyGovernor governor = governor(16, 600, 45.0, 300, -1.0);

        feed(governor, ENOUGH_TICKS);

        assertEquals(300, governor.getLimit(), "no signal means no adjustment");
    }

    @Test
    @DisplayName("a fixed governor is inert whatever the tick rate does")
    void fixedNeverMoves() {
        final ConcurrencyGovernor governor = ConcurrencyGovernor.fixed(128);

        feed(governor, ENOUGH_TICKS);

        assertFalse(governor.isAdaptive());
        assertEquals(128, governor.getLimit(),
                "with the knob off a run must behave exactly as it did before the governor existed");
    }
}
