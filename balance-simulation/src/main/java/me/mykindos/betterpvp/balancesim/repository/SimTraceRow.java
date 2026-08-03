package me.mykindos.betterpvp.balancesim.repository;

/**
 * One landed hit, on the duel's own tick axis.
 *
 * <p>Exists to locate a divergence, not to measure anything. Runs 168 and 169 established that the
 * sweep is not reproducible -- identical build, identical target, four repeat iterations, different
 * damage in 2.3% of {@code MUTUAL} matchups and 5.2% of {@code ONE_WAY} ones -- and no table that
 * stores a duel as one row can say where inside the fight the two runs parted, because the totals are
 * what disagree. A trace of hits can: line two iterations up on {@code tick} and read down until a
 * row differs.
 *
 * <p>{@code tick} is measured from the duel's first landed hit, matching how TTK is anchored, so a
 * trace row and the {@code sim_result} row it explains sit on the same axis. Setup cost varies with a
 * duel's position in its batch, and anchoring anywhere earlier would fold that into every comparison.
 *
 * <p>{@code seq} exists because two hits can share a tick and a diff must be stable: without it the
 * comparison reports a divergence that is only a difference in insertion order.
 *
 * <p>Both damages are carried. If raw agrees and final does not, the swing was the same and the
 * mitigation pipeline diverged; if raw already differs, the swing itself did. Those are different
 * bugs and the trace should not need a second run to tell them apart.
 *
 * @param buildId     the attacking build this duel measured
 * @param targetRole  the target spec's role
 * @param targetArmor the target spec's armour set id
 * @param iteration   which repeat of the matchup this hit belongs to
 * @param arenaIndex  the platform the duel was fought on
 * @param tick        server ticks since the duel's first landed hit
 * @param seq         ordering within {@code tick}
 * @param actor       {@code attacker} or {@code defender}
 * @param event       what happened; {@code hit} today, so the table can carry more later
 * @param rawAmount   pre-mitigation damage, or null where the event carries none
 * @param amount      post-mitigation damage, or null where the event carries none
 */
public record SimTraceRow(long buildId,
                          String targetRole,
                          String targetArmor,
                          int iteration,
                          int arenaIndex,
                          int tick,
                          int seq,
                          String actor,
                          String event,
                          Double rawAmount,
                          Double amount) {

    /** The {@code actor} value for the build under measurement. */
    public static final String ATTACKER = "attacker";

    /** The {@code actor} value for the target it is measured against. */
    public static final String DEFENDER = "defender";

    /** The {@code event} value for a landed hit. */
    public static final String HIT = "hit";

    /**
     * Milliseconds since the first landed hit, derived from {@link #tick()}.
     *
     * <p>Derived rather than stored independently. The engine's clock is ticks; a millisecond figure
     * that was measured separately would invite comparison against a wall clock that no part of this
     * simulation runs on, and the divergence being hunted is a timing one.
     */
    public long tMs() {
        return tick * 50L;
    }
}
