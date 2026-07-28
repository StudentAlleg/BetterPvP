package me.mykindos.betterpvp.balancesim.catalog;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.item.ItemRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the permutation space from the <em>live registries</em>, never from YAML.
 *
 * <p>Weapons come from {@link ItemRegistry} filtered to {@code WeaponItem} (reading
 * {@code StatContainerComponent} after {@code reload()}), roles from the {@code Role} enum,
 * skills from the Guice {@code Skill} singletons with their slot/class constraints, and
 * runes/gems from their handler singletons. Validity rules -- class and slot exclusivity,
 * weapon-type guards such as ComboAttack's -- are read from the same code the game uses rather
 * than re-encoded here.
 *
 * <h2>Level vectors</h2>
 * Every skill in a build varies independently over its legal range, bounded by two rules that
 * both come from real code:
 * <ol>
 *   <li>allocated level is {@code 1..Skill.getMaxLevel()} ({@code maxlevel}, config-driven,
 *       default 5); {@code BuildRepository} clamps to it on load, so higher allocations are
 *       not representable.</li>
 *   <li>total allocated levels across the build must be {@code <= RoleBuild.points} (12).</li>
 * </ol>
 * The second is the dominant prune -- it cuts the naive 5^5 = 3125 vectors per role to a few
 * hundred, and means "everything at max" is not a reachable build at all. Generate
 * budget-feasible vectors directly; enumerating then filtering gives the same answer but
 * wastes the orchestrator's time.
 *
 * <p><b>Scaffolding.</b> Nothing is enumerated yet. See {@code docs/balance-simulation/DESIGN.md}
 * section 3.1, and open question 6 -- the space should be counted after applying both prunes
 * before the orchestrator is built, since that count sets the concurrency and iteration targets
 * and decides whether a scheduled sweep needs a reduced tier.
 */
@Singleton
@CustomLog
public class BalanceCatalog {

    /**
     * Phase 1 equips whatever {@code RoleManager.equipWeapons} hands out rather than picking from
     * {@link ItemRegistry}, so the weapon axis is a single fixed value. Recorded on the build row
     * so a phase 1 run is not silently comparable with a phase 2 run that varied the weapon.
     */
    private static final String PHASE_ONE_WEAPON = "role_default";

    private final ItemRegistry itemRegistry;

    @Inject
    public BalanceCatalog(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    /**
     * Enumerates every attacker build in scope.
     *
     * <p><b>Phase 1 scope.</b> One skill-less build per role with the role's default weapons, which
     * is what the phase 1 exit criterion measures: fake players landing plain melee hits through
     * the real pipeline, with rows in {@code sim_result} to prove the ETL end to end. No skills, no
     * rune sets and no weapon axis yet, so {@code pointsSpent} is 0 and no allocation is recorded.
     *
     * <p>TODO(phase 2): roles x one skill per slot x budget-feasible level vectors x weapons
     * (including boosters) x rune sets. {@code effectiveLevel} on each
     * {@link SimSkillAllocation} is left at its allocated value until the combatant is
     * equipped, at which point the orchestrator overwrites it from the real accessor.
     */
    public List<SimBuildSpec> enumerateBuilds() {
        final List<SimBuildSpec> builds = new ArrayList<>();
        for (Role role : Role.values()) {
            builds.add(new SimBuildSpec(role.name(),
                    PHASE_ONE_WEAPON,
                    List.of(),
                    List.of(),
                    0,
                    false,
                    fingerprint(role.name(), PHASE_ONE_WEAPON)));
        }
        return List.copyOf(builds);
    }

    /**
     * Stable hash over the configuration a build is identified by, so the same build can be found
     * across runs and joined to live per-build player data. Phase 1 only varies role and weapon;
     * the skill allocation joins this input as the catalog grows, which is why it is hashed from a
     * delimited string rather than from a record's identity.
     */
    private static String fingerprint(String... parts) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint a build", e);
        }
    }

    /**
     * Enumerates every defender configuration in scope.
     *
     * <p><b>Phase 1 scope.</b> One bare target per role: no armour set, so {@code hp} is the role's
     * base health from {@code Role.getHealth()} with nothing summed on top, and no skills, so
     * nothing mitigates. Because armour is an effective-HP stat rather than a reduction multiplier,
     * adding sets in phase 2 changes {@code hp} and nothing else about how a result is read.
     *
     * <p>TODO(phase 2): roles x armour sets x budget-feasible defender builds, with {@code hp}
     * summed from the armour items' {@code StatTypes.HEALTH} contributions rather than assumed.
     * The defender carries a skill allocation like an attacker does, so its {@code DefensiveSkill}
     * passives and resistance effects fire in the real pipeline; {@code effectiveLevel} is left at
     * its allocated value until the combatant is equipped and read back through the real accessor.
     */
    public List<SimTargetSpec> enumerateTargets() {
        final List<SimTargetSpec> targets = new ArrayList<>();
        for (Role role : Role.values()) {
            targets.add(new SimTargetSpec(role.name(), "none", role.getHealth(), List.of(), 0));
        }
        return List.copyOf(targets);
    }
}
