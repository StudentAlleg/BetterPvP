package me.mykindos.betterpvp.balancesim.repository;

import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.engine.SimCombatant;
import me.mykindos.betterpvp.balancesim.engine.SimEnergyLedger;
import me.mykindos.betterpvp.balancesim.engine.SimRecorder;
import me.mykindos.betterpvp.balancesim.engine.SimSkillLedger;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One duel's diagnostic row, and the reason a duel is worth keeping whole.
 *
 * <p>Every figure on {@code sim_result} is a mean over a matchup's iterations, which is the right
 * shape for a measurement and the wrong shape for an investigation. Run 164 produced a matchup where
 * the skill-less baseline killed in 10 of 10 duels and the same build carrying one pure passive --
 * no button, no damage, nothing the rotation even presses -- died in 10 of 10, with per-hit damage
 * deterministic to zero variance across the entire sweep. Something outside the build decided that
 * fight, and the mean it was reduced into cannot say what.
 *
 * <p>So this row is per duel, and it carries the state a duel starts from as well as the events it
 * produced. The typed columns are the ones a hypothesis is tested against directly in SQL; the rest
 * rides in {@code detail} for the same reason {@code sim_result.extras} exists -- a column per field
 * would be a migration every time a new suspicion needs checking.
 *
 * <h2>What each block is for</h2>
 * <ul>
 *   <li><b>setup</b> -- attributes, energy, held item, effects and effective skill levels, read off
 *       the live entity once everything is equipped. Two builds differing by one inert passive must
 *       be identical here. If they are not, the duel was decided before it started.</li>
 *   <li><b>residency</b> -- how many duels this entity has fought, how often it has been revived and
 *       whether it has ever died. Baselines are enumerated first, so they fight younger residents
 *       than the builds measured against them; that is a confound between sweep order and result
 *       which no {@code sim_result} column can express.</li>
 *   <li><b>residue</b> -- the entity's combat state as it arrived, before setup touched it. A
 *       resident is supposed to be indistinguishable from a fresh entity here.</li>
 *   <li><b>funnel</b> -- swings issued against each side and how far down the damage pipeline they
 *       got. Separates "swung and missed" from "never swung" from "swung and was refused".</li>
 *   <li><b>timeline</b> -- every landed hit in both directions with its tick. Under MUTUAL the
 *       defender acts and swings before the attacker on every tick, and a 29 HP target dies in five
 *       hits, so first-hit tick is very likely the whole story.</li>
 * </ul>
 */
public record SimDuelDiagnosticRow(long buildId,
                                   SimTargetSpec target,
                                   int iteration,
                                   int arenaIndex,
                                   Outcome outcome,
                                   @Nullable Integer resolvedTick,
                                   Side attacker,
                                   Side defender,
                                   double maxSeparation,
                                   List<SimRecorder.HitRecord> hits) {

    /**
     * How the duel ended.
     *
     * <p>{@code BARREN} is separated from {@code TIMEOUT} because they are different failures: a
     * timeout is a fight neither side could finish, and a barren duel is one where the attacker never
     * landed anything at all, which is a harness problem wearing a measurement's clothes.
     */
    public enum Outcome {
        DEFENDER_KILLED,
        ATTACKER_KILLED,
        TIMEOUT,
        BARREN
    }

    /**
     * One combatant's side of the duel.
     *
     * @param uuid       identity, used only to attribute hits in the timeline
     * @param snapshot   start-of-duel state
     * @param endHealth  health when the duel resolved, so a loss by one hit is visible as such
     * @param funnel     the pipeline funnel line for this side, from {@code SimPlayer}
     * @param energy     energy accounting, or null when none was observed
     * @param activations what the rotation pressed and what the real chain did with it
     */
    public record Side(UUID uuid,
                       SimCombatant.SetupSnapshot snapshot,
                       @Nullable Double endHealth,
                       String funnel,
                       @Nullable SimEnergyLedger.EnergyUse energy,
                       Map<String, SimSkillLedger.SkillActivation> activations) {
    }

    /** Hits this side landed on the other. */
    public int hitsBy(Side side) {
        int count = 0;
        for (SimRecorder.HitRecord hit : hits) {
            if (hit.damager().equals(side.uuid())) {
                count++;
            }
        }
        return count;
    }

    /** Total post-modifier damage this side dealt. */
    public double damageBy(Side side) {
        double total = 0;
        for (SimRecorder.HitRecord hit : hits) {
            if (hit.damager().equals(side.uuid())) {
                total += hit.finalDamage();
            }
        }
        return total;
    }

    /**
     * The tick this side first landed a hit, or null if it never did.
     *
     * <p>The single most likely explanation for run 164's flipped matchups, and the one figure
     * {@code sim_result} throws away: TTK is anchored to the first hit precisely so that setup cost
     * cancels out, which also means a build that started swinging late and a build that swung on time
     * are indistinguishable once the row is written.
     */
    @Nullable
    public Integer firstHitTick(Side side) {
        for (SimRecorder.HitRecord hit : hits) {
            if (hit.damager().equals(side.uuid())) {
                return hit.elapsedTicks();
            }
        }
        return null;
    }

    /** The {@code detail} column: everything not worth a typed column of its own. */
    public String detailJson() {
        final Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("attacker", sideJson(attacker));
        detail.put("defender", sideJson(defender));
        // -1 means the duel resolved before a sample was taken. Kept as the sentinel rather than
        // written as null, because "never measured" and "never moved apart" are opposite readings.
        detail.put("max_separation", maxSeparation);
        detail.put("timeline", timelineJson());
        return toJson(detail);
    }

    private Map<String, Object> sideJson(Side side) {
        final SimCombatant.SetupSnapshot snapshot = side.snapshot();
        final Map<String, Object> json = new LinkedHashMap<>();

        final Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("role_before_equip", snapshot.roleBeforeEquip());
        setup.put("role_primed", snapshot.rolePrimed());
        setup.put("health", snapshot.health());
        setup.put("max_health", snapshot.maxHealth());
        setup.put("armor", snapshot.armor());
        setup.put("armor_toughness", snapshot.armorToughness());
        setup.put("attack_damage", snapshot.attackDamage());
        setup.put("attack_speed", snapshot.attackSpeed());
        setup.put("knockback_resistance", snapshot.knockbackResistance());
        setup.put("movement_speed", snapshot.movementSpeed());
        setup.put("held_item", snapshot.heldItem());
        setup.put("held_slot", snapshot.heldSlot());
        setup.put("effects", snapshot.effects());
        setup.put("skills", skillsJson(snapshot.measuredSkills()));
        json.put("setup", setup);

        final Map<String, Object> residency = new LinkedHashMap<>();
        residency.put("duels_fought", snapshot.duelsFought());
        residency.put("revives", snapshot.revives());
        residency.put("ever_died", snapshot.everDied());
        json.put("residency", residency);

        json.put("residue_before_setup", snapshot.residueBeforeSetup());
        json.put("funnel", side.funnel());
        json.put("end_health", side.endHealth());

        if (side.energy() != null) {
            final SimEnergyLedger.EnergyUse energy = side.energy();
            final Map<String, Object> energyJson = new LinkedHashMap<>();
            energyJson.put("spent_on_skills", energy.spentOnSkills());
            energyJson.put("drained_custom", energy.drainedCustom());
            energyJson.put("regen_custom", energy.regenCustom());
            energyJson.put("min_energy", energy.minEnergy());
            energyJson.put("max_energy", energy.maxEnergy());
            json.put("energy", energyJson);
        }

        if (!side.activations().isEmpty()) {
            final Map<String, Object> presses = new LinkedHashMap<>();
            side.activations().forEach((skill, counts) -> {
                final Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("attempts", counts.attempts());
                entry.put("successes", counts.successes());
                entry.put("cooldown_refusals", counts.cooldownRefusals());
                entry.put("energy_refusals", counts.energyRefusals());
                entry.put("declined", counts.declined());
                presses.put(skill, entry);
            });
            json.put("presses", presses);
        }
        return json;
    }

    private static List<Map<String, Object>> skillsJson(List<SimSkillAllocation> skills) {
        return skills.stream().map(skill -> {
            final Map<String, Object> json = new LinkedHashMap<>();
            json.put("skill", skill.skillName());
            json.put("slot", skill.slot());
            json.put("allocated_level", skill.allocatedLevel());
            json.put("effective_level", skill.effectiveLevel());
            return json;
        }).toList();
    }

    /**
     * Every landed hit in arrival order, both directions.
     *
     * <p>Sides are named rather than given by UUID: a resident keeps one UUID across every duel it
     * fights, so the raw identity says nothing a reader of one row can use, and which side is which
     * is the only thing the timeline is read for.
     */
    private List<Map<String, Object>> timelineJson() {
        return hits.stream().map(hit -> {
            final Map<String, Object> json = new LinkedHashMap<>();
            json.put("tick", hit.elapsedTicks());
            json.put("by", hit.damager().equals(attacker.uuid()) ? "attacker" : "defender");
            json.put("raw", hit.rawDamage());
            json.put("final", hit.finalDamage());
            json.put("reasons", hit.reasons());
            return json;
        }).toList();
    }

    // -------------------------------------------------------------------------
    // Hand-rolled JSON, for the reason SimMeasurement documents: every value here is a primitive, a
    // null, a list or a map of those, and the column is read with Postgres' own operators rather
    // than deserialised into a type.
    // -------------------------------------------------------------------------

    private static String toJson(Map<String, Object> values) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder json, @Nullable Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Map<?, ?> map) {
            json.append(toJson((Map<String, Object>) map));
        } else if (value instanceof List<?> list) {
            json.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                appendValue(json, list.get(i));
            }
            json.append(']');
        } else if (value instanceof Double number && !Double.isFinite(number)) {
            // NaN is what an absent attribute reads as, and it is not valid JSON -- Postgres would
            // reject the whole document rather than the field, losing a duel's diagnostics to one
            // attribute the entity happened not to carry.
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            json.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
