package me.mykindos.betterpvp.balancesim.repository;

import me.mykindos.betterpvp.core.combat.modifiers.DamageModifier;

/**
 * One damage modifier as it applied to one landed hit.
 *
 * <p>The piece of evidence every damage number in this project was missing. {@code sim_result}
 * stores a duel's damage per hit and {@code sim_trace} stores a single hit's raw and final amount,
 * but neither stores <em>why</em> the number is what it is -- so a skill's contribution had to be
 * modelled from config, and modelling it from config has been measured and does not work: against
 * the eleven skills that really construct a {@code SkillDamageModifier.Multiplier}, config-key
 * inference scores precision 1/5 and recall 1/11.
 *
 * <p>Read off the {@link DamageModifier} interface rather than by calling {@code apply(event)} a
 * second time. {@code getAppliedModifiers} already invokes {@code apply} while filtering, and the
 * base {@code SkillDamageModifier.apply} is a pure result constructor, but "pure today" is not a
 * property this class should depend on for every modifier anyone writes later. The declared
 * accessors give the same operator and operand with no second invocation.
 *
 * <p>This is what makes a RAMPING skill visible. Combo Attack's operand is not a property of
 * (skill, level) at all -- it is a function of how many times the holder has already hit the same
 * target, contributing zero on the first hit and climbing to a cap. A per-duel average hides that
 * completely; a per-hit operand sequence is the ramp, read directly.
 *
 * @param source    the modifier's own name, {@code DamageModifier.getName()}
 * @param operator  {@code FLAT} or {@code MULTIPLIER}
 * @param operand   the value the operator applies -- damage added for FLAT, factor for MULTIPLIER
 * @param priority  higher runs first. Recorded for completeness, not because it changes the total:
 *                  each phase of the composition is a sum or a product, so priority only decides
 *                  what the damage log prints
 * @param type      {@code ModifierType}, e.g. ABILITY, so skill modifiers can be separated from
 *                  the pipeline's own
 * @param reductive whether the pipeline treated this as a reduction. Stored rather than derived:
 *                  {@code ModifierResult.isReductive} computes it from the operand today
 *                  (FLAT <= 0, MULTIPLIER < 1.0), and a row should keep saying what the pipeline
 *                  actually decided even if that rule later changes
 */
public record SimHitModifier(String source,
                             String operator,
                             double operand,
                             int priority,
                             String type,
                             boolean reductive) {

    /**
     * Snapshots a modifier the pipeline applied.
     *
     * <p>{@code reductive} reproduces {@code ModifierResult.isReductive} rather than calling it,
     * because obtaining a {@code ModifierResult} means invoking {@code apply} again. The rule is one
     * line and is asserted against the real one in the simulator's tests.
     */
    public static SimHitModifier of(DamageModifier modifier) {
        final double operand = modifier.getDamageOperand();
        final String operator = modifier.getDamageOperator().name();
        final boolean reductive = switch (modifier.getDamageOperator()) {
            case FLAT -> operand <= 0;
            case MULTIPLIER -> operand < 1.0;
        };
        return new SimHitModifier(modifier.getName(), operator, operand,
                modifier.getPriority(), modifier.getType().name(), reductive);
    }
}
