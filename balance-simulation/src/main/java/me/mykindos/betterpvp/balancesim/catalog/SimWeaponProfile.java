package me.mykindos.betterpvp.balancesim.catalog;

import java.util.List;
import java.util.Locale;

/**
 * Everything about a weapon that can change a measured result.
 *
 * <p>The point of this record is equality. Two registered weapons carrying the same melee stats, the
 * same booster status, the same skill slot and the same set of applicable runes are, to a duel,
 * <em>the same weapon</em> -- they differ only in name and model. Sweeping both measures the same
 * fight twice, and at the {@code FULL} tier the weapon axis multiplies everything else, so the
 * duplicate is paid once per build rather than once.
 *
 * <p>So the profile is the dedupe key: {@link SimEquipment#distinctMeleeWeapons()} keeps one
 * representative per profile and {@link SimEquipment#weaponAliases(String)} names the ones it stands
 * for, which are written onto the build row. A dashboard asking "what does this weapon do" resolves
 * through the alias list rather than finding no row at all -- the reduction has to stay visible,
 * because a missing weapon and a weapon that was folded into another are different facts.
 *
 * <h2>What is deliberately in the key</h2>
 * <ul>
 *   <li><b>Damage and attack speed, all three figures each.</b> Only {@link #damageBase()} is
 *       exercised today -- {@code MeleeDamageStatHandler} calls {@code event.setDamage(stat.getValue())}
 *       and {@code ItemFactory.create} rolls nothing -- but the range is part of what the item
 *       <em>is</em>, and two weapons with equal base and different envelopes must not be collapsed:
 *       they diverge the moment a reforge or a rolled instance enters the sweep.</li>
 *   <li><b>The skill slot.</b> A skill's activation path tests
 *       {@code SkillWeapons.isHolding(player, getType())}, so a sword and an axe with identical
 *       numbers drive different builds.</li>
 *   <li><b>Booster.</b> The {@code +1} is a property of the material, not of the stats.</li>
 *   <li><b>The applicable rune keys.</b> Two weapons with equal stats that accept different runes
 *       have different rune axes, and folding them would silently drop rune combinations from the
 *       sweep.</li>
 * </ul>
 *
 * <h2>What is deliberately not</h2>
 * The item key, display name, material, rarity and durability. A material that changed a measured
 * outcome would have to do so through one of the fields above -- vanilla attack damage is overwritten
 * by the stat handler, and durability never binds inside a duel that lasts seconds. Including the
 * material would make the key unique per item and reduce nothing.
 *
 * @param damageBase       {@code damage.base}, the figure the pipeline actually applies
 * @param damageMin        {@code damage.min}, the bottom of the roll envelope
 * @param damageMax        {@code damage.max}, the top of the roll envelope
 * @param attackSpeedBase  {@code attack_speed.base}
 * @param attackSpeedMin   {@code attack_speed.min}
 * @param attackSpeedMax   {@code attack_speed.max}
 * @param booster          whether holding it raises a SWORD/AXE/BOW skill's effective level by one
 * @param skillSlot        the {@code SkillType} the weapon serves when held, or {@code "none"}
 * @param runeKeys         every rune the weapon accepts, sorted, as {@link SimEquipment#weaponRunes}
 *                         returns them
 */
public record SimWeaponProfile(double damageBase,
                               double damageMin,
                               double damageMax,
                               double attackSpeedBase,
                               double attackSpeedMin,
                               double attackSpeedMax,
                               boolean booster,
                               String skillSlot,
                               List<String> runeKeys) {

    /** The profile of a weapon whose stats could not be read, so an unreadable item never dedupes. */
    public static final String UNKNOWN_SLOT = "none";

    public SimWeaponProfile {
        runeKeys = List.copyOf(runeKeys);
    }

    /**
     * This profile with the two fields a skill-less build cannot observe removed.
     *
     * <p>Both of the fields dropped here are in the key only because of a skill. The slot is in it
     * because a skill's activation path tests {@code SkillWeapons.isHolding(player, getType())}, and
     * {@code booster} is in it because {@code Skill.getLevel} adds its {@code +1} inside
     * {@code if (SkillWeapons.isHolding(player, getType()) && SkillWeapons.hasBooster(player))} --
     * both of which need a skill to be reading them. A build that allocates none has nothing that
     * consults either, so a sword and an axe carrying identical numbers are, to that duel, one weapon:
     * the fake player swings, {@code MeleeDamageStatHandler} reads {@code MELEE_DAMAGE} off the item,
     * and nothing anywhere asks what the item is called or what slot it serves. That is exactly the
     * case in the shipped config -- {@code standard_sword}, {@code standard_axe},
     * {@code booster_sword} and {@code booster_axe} all carry damage 6.0 [5.0-7.0] at speed
     * 0.0 [-0.25-0.25] -- so the full key measures one weapon four times.
     *
     * <p><b>"Skill-less" means no skills at all, not "no sword/axe/bow skills".</b> The narrower rule
     * is the one that looks right and is wrong: several skills typed {@code PASSIVE_A}/{@code
     * PASSIVE_B} test the held weapon themselves rather than through their own slot --
     * {@code Swordsmanship}, {@code GlacialBlade} and {@code MagmaBlade} call
     * {@code SkillWeapons.isHolding(player, SkillType.SWORD)}, and {@code Cleave},
     * {@code CripplingBlow} and {@code RootingAxe} call it for {@code AXE} -- so a passive-only build
     * can absolutely tell a sword from an axe. There is no field on a {@code Skill} that says whether
     * it does, only the body of its handler, so the sound condition is the empty allocation.
     *
     * @see SimEquipment#slotAgnosticMeleeWeapons()
     */
    public SimWeaponProfile slotAgnostic() {
        return new SimWeaponProfile(damageBase, damageMin, damageMax,
                attackSpeedBase, attackSpeedMin, attackSpeedMax,
                false, UNKNOWN_SLOT, runeKeys);
    }

    /**
     * A one-line rendering for logs, so a run states which weapons it folded and on what grounds.
     *
     * <p>Without this the enumeration log says only that the axis shrank, and the difference between
     * "these two weapons are genuinely identical" and "the profile is missing a field that matters"
     * is not recoverable from a build count.
     */
    public String describe() {
        return String.format(Locale.ROOT,
                "dmg %.2f [%.2f-%.2f], speed %.2f [%.2f-%.2f], slot %s%s, %d runes",
                damageBase, damageMin, damageMax,
                attackSpeedBase, attackSpeedMin, attackSpeedMax,
                skillSlot, booster ? " (booster)" : "", runeKeys.size());
    }
}
