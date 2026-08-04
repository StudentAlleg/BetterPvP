package me.mykindos.betterpvp.champions.champions.skills.types;

import me.mykindos.betterpvp.core.components.champions.IChampionsSkill;

/**
 * A skill activated by right-clicking <em>a target</em> rather than the air.
 *
 * <p>Distinct from {@link InteractSkill}, which is driven by {@code PlayerInteractEvent} and resolved
 * by {@code SkillListener}. A skill of this shape handles {@code PlayerInteractEntityEvent} itself and
 * takes the clicked entity as the target, so a right-click on air or a block reaches it with no target
 * and lands on its failure branch.
 *
 * <p>Purely a marker: these skills already own their listeners, and moving that plumbing here would
 * change behaviour rather than describe it. What the marker buys is that the archetype becomes
 * <em>legible</em>. Without it a skill of this shape implements no activation interface at all, so
 * anything reading the type hierarchy to decide how to drive a skill concludes it is a passive that
 * needs no input -- which is how the balance simulation came to report {@code Sever} and
 * {@code Hilt Smash} as passives that fired 0 times and were therefore inert, when in truth nothing
 * had ever pressed the one button they answer to.
 *
 * <p>Implement this on any skill whose activation path is {@code PlayerInteractEntityEvent}. The two
 * that exist are the two that were mislabelled; a third written without it would be mislabelled the
 * same way.
 */
public interface InteractEntitySkill extends IChampionsSkill {
}
