package me.mykindos.betterpvp.balancesim.catalog;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether a duel drives one side or both.
 *
 * <p>This is design open question 9. Phase 1 and 2 drove the attacker only: the defender was a full
 * combatant with real health and real armour, but it never swung. That makes every passive whose value
 * is realised <em>on being hit</em> invisible -- knight's {@code Vengeance} ramps its holder's damage as
 * that holder takes hits, and an attacker who is never hit never ramps -- and the same goes for
 * counters and for every {@code DefensiveSkill} on the attacker.
 *
 * <p>The fix is not to always drive both sides. A mutual exchange truncates the attacker's
 * time-to-kill whenever the defender wins the race, which is a meaningful measurement but a
 * <em>different</em> one, and averaging the two would make a row ambiguous with nothing on it to say
 * which had been measured. So it is an axis: named at invocation, recorded on
 * {@code sim_run.scenario}, and never mixed within a run.
 *
 * <h2>Reading a row from each</h2>
 * <ul>
 *   <li>{@link #ONE_WAY} -- {@code ttk_s} is how long this build needs to kill that target, full stop.
 *       Comparable across builds without qualification, which is why it stays the default and why the
 *       phase 1 and 2 dashboards keep meaning what they meant.</li>
 *   <li>{@link #MUTUAL} -- {@code ttk_s} is how long it needed <em>when it won</em>, and
 *       {@code extras.win_rate} is how often that was. A build that kills fast but dies faster shows up
 *       as a good TTK and a poor win rate, and neither number alone is the answer. {@code ttk_s} is
 *       still averaged over winning iterations only, for the same reason a timeout is not averaged in
 *       as a slow kill.</li>
 * </ul>
 *
 * <p>The defender is driven by the same {@code GreedyRotationPolicy} as the attacker, from the same
 * kind of build. What it does <em>not</em> yet get is a swept build of its own: the target axis is role
 * and armour, so a mutual duel is a full attacker build against a bare-role defender with the standard
 * kit. That keeps the space the same size as a one-way sweep instead of squaring it, and it is why
 * {@code sim_result.target_skills} is still empty rather than fabricated.
 */
public enum SimScenario {

    /** Only the attacker acts. The phase 1 and 2 measurement, and the default. */
    ONE_WAY,

    /** Both sides swing and both rotations run; the duel ends when either dies. */
    MUTUAL;

    /** Whether the defender is driven as well as the attacker. */
    public boolean isDefenderDriven() {
        return this == MUTUAL;
    }

    /** The value written into {@code sim_run.scenario}, lower case to match the rest of that JSON. */
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a command argument or config value, case-insensitively. Empty when it is not a scenario. */
    public static Optional<SimScenario> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
