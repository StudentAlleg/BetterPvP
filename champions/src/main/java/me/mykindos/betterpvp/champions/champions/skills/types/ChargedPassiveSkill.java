package me.mykindos.betterpvp.champions.champions.skills.types;

import me.mykindos.betterpvp.core.components.champions.IChampionsSkill;
import org.bukkit.entity.Player;

/**
 * A passive that banks charges while its holder is out of combat and spends them on a hit.
 *
 * <p>{@code Deflection} and {@code Swordsmanship} are the two, and they describe the same mechanic
 * from opposite ends -- one converts banked charges into damage reduction taken, the other into
 * damage dealt. Both accrue only while {@code Gamer.hasBeenOutOfCombatFor} holds.
 *
 * <p>That gate is why they need this interface. A skill of this shape is worth exactly nothing in a
 * fight with no lull in it: charges seed at zero, combat starts, and the accrual condition is never
 * true again, so the modifier applied on hit is always a no-op. Anything measuring these skills over
 * a continuous engagement therefore measures zero however well they are tuned, and cannot tell that
 * apart from a skill that is broken.
 *
 * <p>{@link #fillCharges} is the way out: it puts the holder in the state the mechanic assumes they
 * arrive in -- walked into the fight fully banked -- so what gets measured afterwards is the skill's
 * payoff rather than its accrual. That is a legitimate starting condition and not a simulation
 * concept, which is why it lives here rather than in the harness.
 */
public interface ChargedPassiveSkill extends IChampionsSkill {

    /**
     * Banks {@code player} up to the maximum their level allows, as though they had spent long enough
     * out of combat to accrue it.
     *
     * <p>Idempotent, and a no-op for a player who does not have the skill -- the level is read from
     * the build rather than passed in, so there is no way for a caller to invent a charge count the
     * build does not support.
     */
    void fillCharges(Player player);
}
