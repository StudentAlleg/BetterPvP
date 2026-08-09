package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.core.components.champions.Role;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which slice of an already-chosen {@link SimScope} a sweep is restricted to.
 *
 * <p>{@code SimScope} answers "which axes vary"; this answers "over which values". They are separate
 * because the axes are the expensive structural choice and the values are the cheap one: an
 * {@code EQUIPMENT} sweep of one weapon is still an {@code EQUIPMENT} sweep -- it crosses rolls with
 * every rune set that weapon accepts -- and folding "one weapon" into the scope enum would mean a tier
 * per weapon.
 *
 * <p>Two things it is for. A balance change usually touches a handful of items or skills, and the
 * matching re-measurement is a sweep of those and nothing else. And when a run surfaces something
 * suspicious about one weapon, re-running that weapon's permutations is minutes rather than the day
 * the full tier costs.
 *
 * <h2>Matching</h2>
 * Selectors are matched leniently by design, because the identifiers a person has in front of them are
 * not the ones the registry uses. {@code champions:wind_blade}, {@code wind_blade}, {@code windblade}
 * and {@code Wind Blade} all name the same weapon; {@code Blood Shield}, {@code bloodshield} and
 * {@code blood-shield} all name the same skill. Everything is compared on its
 * {@linkplain #normalise(String) normalised} form -- lowercased, with the namespace and every
 * non-alphanumeric character dropped -- and a trailing {@code *} makes a selector a prefix match, so
 * {@code --weapons=reinforced*} is a family.
 *
 * <p>A weapon is also matched through its {@linkplain SimBuildSpec#weaponAliases() aliases}, so naming
 * a weapon the reduced tiers folded onto another profile selects the row that actually stands for it
 * rather than nothing at all. Those are opposite answers, and the one this gives is the useful one.
 *
 * <h2>Why an unmatched selector is fatal</h2>
 * A selector that matches nothing is refused by {@link #verify}, with the values that were available.
 * It is the same failure mode {@code SimSkillFilter.RELEVANT} guards against with its empty-list
 * check: a mistyped {@code --weapons=thornfangg} would enumerate an empty catalog, and an empty sweep
 * completes in seconds, reports {@code COMPLETED} and looks perfectly healthy. There is nothing on the
 * run or on any row that would say the sweep measured nothing because of a typo.
 *
 * @param roles      attacker roles to build permutations for; empty means every role
 * @param weapons    weapon selectors; empty means every weapon the scope's axis offers
 * @param skills     skill selectors; empty means every skill the scope's filter admits
 * @param runes      rune selectors; empty means every rune each weapon accepts
 * @param targetRoles defender roles to measure against; empty means every role. Separate from
 *                   {@code roles} because narrowing the attacker and narrowing the defender are
 *                   different questions -- a sweep of one weapon usually still wants every target
 * @param armor      armour set selectors, by set id rather than by tier number -- {@code none},
 *                   {@code reinforced}. Empty means every set the scope's armour axis offers. Set
 *                   ids rather than tiers because a tier is an ordering this class does not own:
 *                   {@code SimEquipment} numbers the ladder by durability, so registering a
 *                   flimsier set would renumber every tier above it and silently re-point a
 *                   selector that had been typed against the old numbering
 */
public record SimSelection(Set<String> roles,
                           List<String> weapons,
                           List<String> skills,
                           List<String> runes,
                           Set<String> targetRoles,
                           List<String> armor) {

    /** No restriction: every value of every axis the scope varies. */
    public static final SimSelection ALL =
            new SimSelection(Set.of(), List.of(), List.of(), List.of(), Set.of(), List.of());

    public SimSelection {
        roles = Set.copyOf(roles);
        weapons = List.copyOf(weapons);
        skills = List.copyOf(skills);
        runes = List.copyOf(runes);
        targetRoles = Set.copyOf(targetRoles);
        armor = List.copyOf(armor);
    }

    /** Whether this restricts anything at all. */
    public boolean isAll() {
        return roles.isEmpty() && weapons.isEmpty() && skills.isEmpty()
                && runes.isEmpty() && targetRoles.isEmpty() && armor.isEmpty();
    }

    /**
     * Parses one comma-separated selector list.
     *
     * <p>Blank entries are dropped rather than kept as an empty selector that matches everything,
     * which is what a trailing comma would otherwise produce.
     */
    public static List<String> parseList(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            final String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    /**
     * Parses a comma-separated role list, refusing a name that is not a role.
     *
     * @throws IllegalArgumentException naming the bad value and the valid ones. Refused rather than
     *                                  dropped for {@link #verify}'s reason: a silently ignored role
     *                                  produces a wider sweep than was asked for, which is the failure
     *                                  that costs hours rather than seconds
     */
    public static Set<String> parseRoles(@Nullable String raw, String flag) {
        final Set<String> parsed = new LinkedHashSet<>();
        for (String value : parseList(raw)) {
            boolean found = false;
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    parsed.add(role.name());
                    found = true;
                    break;
                }
            }
            if (!found) {
                final StringBuilder valid = new StringBuilder();
                for (Role role : Role.values()) {
                    valid.append(valid.isEmpty() ? "" : ", ").append(role.name());
                }
                throw new IllegalArgumentException(flag + "=" + value + " is not a role. Valid: " + valid);
            }
        }
        return Set.copyOf(parsed);
    }

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    /** Whether an attacker build for {@code role} should be enumerated. */
    public boolean admitsRole(Role role) {
        return roles.isEmpty() || roles.contains(role.name());
    }

    /** Whether a defender of {@code role} should be measured against. */
    public boolean admitsTargetRole(String role) {
        return targetRoles.isEmpty() || targetRoles.contains(role.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether a weapon is in the sweep, by its own key or by any key it stands for.
     *
     * @param aliases every key this weapon's measurement covers, which on the deduplicated tiers is
     *                more than one. Checked as well as the key, because naming a folded-away weapon
     *                must select its representative rather than nothing
     */
    public boolean admitsWeapon(String weaponKey, Collection<String> aliases) {
        if (weapons.isEmpty()) {
            return true;
        }
        if (matchesAny(weapons, weaponKey)) {
            return true;
        }
        for (String alias : aliases) {
            if (matchesAny(weapons, alias)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a skill may fill a slot in an enumerated build. */
    public boolean admitsSkill(String skillName) {
        return skills.isEmpty() || matchesAny(skills, skillName);
    }

    /** Whether a rune may be socketed into an enumerated build. */
    public boolean admitsRune(String runeKey) {
        return runes.isEmpty() || matchesAny(runes, runeKey);
    }

    /**
     * Whether a defender wearing this armour set should be measured.
     *
     * <p>Matched on the set id with no roll suffix, so {@code --armor=reinforced} takes every roll of
     * that set. Narrowing to one roll is what {@code SimScope}'s armour roll axis is for, and having
     * two flags able to express it would let them contradict each other.
     */
    public boolean admitsArmor(String setId) {
        return armor.isEmpty() || matchesAny(armor, setId);
    }

    // -------------------------------------------------------------------------

    /**
     * Refuses selectors that matched nothing during enumeration.
     *
     * <p>Called by the catalog once it has walked every axis, with what each axis actually offered, so
     * the message can say which values were available rather than only that the typed one was not.
     *
     * @param axis      the flag being reported on, for the message
     * @param selectors what was asked for
     * @param available every value the axis offered
     * @throws IllegalStateException if any selector matched none of {@code available}
     */
    public static void verify(String axis, List<String> selectors, Collection<String> available) {
        if (selectors.isEmpty()) {
            return;
        }
        final List<String> unmatched = new ArrayList<>();
        for (String selector : selectors) {
            boolean matched = false;
            for (String value : available) {
                if (matches(selector, value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unmatched.add(selector);
            }
        }
        if (unmatched.isEmpty()) {
            return;
        }
        // Sorted and de-duplicated, because this list is read by someone trying to find the name they
        // meant, and the registry's order is not helpful for that.
        final Set<String> sorted = new TreeSet<>(available);
        throw new IllegalStateException(axis + " matched nothing for " + String.join(", ", unmatched)
                + ". A sweep is refused rather than narrowed to nothing, because an empty sweep"
                + " completes in seconds and reports COMPLETED -- nothing on the run would say it"
                + " measured nothing. Available: " + String.join(", ", sorted));
    }

    /**
     * The selection as a canonical string, for {@code config_hash} and {@code sim_run.scenario}.
     *
     * <p>Hashing this is what stops a filtered run being a resume candidate for an unfiltered one at
     * the same balance config. Without it a {@code --weapons=thornfang} sitting would find the full
     * {@code EQUIPMENT} run, reopen it, and write a handful of measured matchups into a run that
     * claims to cover the whole item space -- and the missing rows would be indistinguishable from
     * ones the sweep had simply not reached yet.
     *
     * <p>Each axis is sorted, so re-ordering a selector list during review is not a change.
     */
    public String canonical() {
        if (isAll()) {
            return "";
        }
        return "roles=" + sortedJoin(roles)
                + ";weapons=" + sortedJoin(weapons)
                + ";skills=" + sortedJoin(skills)
                + ";runes=" + sortedJoin(runes)
                + ";targets=" + sortedJoin(targetRoles)
                + ";armor=" + sortedJoin(armor);
    }

    private static String sortedJoin(Collection<String> values) {
        return String.join(",", new TreeSet<>(values));
    }

    private static boolean matchesAny(List<String> selectors, String value) {
        for (String selector : selectors) {
            if (matches(selector, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one selector names one value.
     *
     * <p>Equality on the normalised forms, or prefix when the selector ends in {@code *}. The
     * normalisation is what makes the flag usable from a chat box: nobody types a namespace, and a
     * skill's display name has spaces its config key does not.
     */
    static boolean matches(String selector, String value) {
        final String pattern = selector.trim();
        if (pattern.endsWith("*")) {
            final String prefix = normalise(pattern.substring(0, pattern.length() - 1));
            return !prefix.isEmpty() && normalise(value).startsWith(prefix);
        }
        return normalise(pattern).equals(normalise(value));
    }

    /**
     * Lowercased, namespace-stripped, alphanumerics only.
     *
     * <p>Dropping the namespace is what lets {@code thornfang} name {@code champions:thornfang}, and
     * dropping punctuation is what makes {@code Blood Shield} and {@code blood_shield} the same
     * string. The cost is that two items with the same name in different namespaces are one selector,
     * which is worth it: the ambiguity is resolvable by typing the namespace, and the alternative is a
     * flag that only works if you already know which plugin registered the item.
     */
    static String normalise(String raw) {
        final int colon = raw.indexOf(':');
        final String withoutNamespace = colon < 0 ? raw : raw.substring(colon + 1);
        final StringBuilder normalised = new StringBuilder(withoutNamespace.length());
        for (int i = 0; i < withoutNamespace.length(); i++) {
            final char c = Character.toLowerCase(withoutNamespace.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                normalised.append(c);
            }
        }
        return normalised.toString();
    }
}
