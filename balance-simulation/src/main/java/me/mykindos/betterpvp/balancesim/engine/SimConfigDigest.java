package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.config.ExtendedYamlConfiguration;
import me.mykindos.betterpvp.core.framework.BPvPPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fingerprints the game configuration a run's numbers actually came from -- whole, and per permutation.
 *
 * <p>This is design open question 10. {@code sim_run.config_hash} exists so that "two runs are only
 * comparable when this differs for the reason you think it does" -- and until phase 3 it hashed the
 * simulation knobs only: scope, iterations, timeout, concurrency, engine version. None of those is
 * where damage comes from. Skill damage, cooldowns and energy costs are read live out of champions'
 * {@code skills/skills.yml} during a duel, and weapon and armour stats out of the {@code items/*.yml}
 * trees. So two sweeps taken either side of a balance change shared a hash, which is exactly the case
 * the column was added to distinguish, and a patch diff had to be pinned by run timestamp instead.
 *
 * <h2>What is hashed</h2>
 * The same config files {@code GrafanaConfigSyncService} already mirrors into {@code grafana_config},
 * read the same way -- every leaf key of the live {@code ExtendedYamlConfiguration}, which is the
 * loaded-and-defaulted view rather than the file on disk, so a value that only exists as a code default
 * is included and a stale comment is not. Reading them here rather than querying
 * {@code grafana_config} keeps the digest independent of whether that sync has run, and keeps it
 * synchronous: it has to be in hand before the {@code sim_run} row is inserted.
 *
 * <p>Role base health is folded in too. It is not in any YAML -- it comes from the {@link Role} enum --
 * but it is the largest single term in a target's durability, so a change to it moves every TTK on
 * every row.
 *
 * <h2>What is deliberately not hashed</h2>
 * Config unrelated to combat arithmetic: the recipe trees (crafting, not stats) and each plugin's
 * top-level {@code config.yml}, which holds operational settings -- database, world names, feature
 * toggles -- that a balance diff should not be sensitive to. Including them would make the hash change
 * on a dev server restart with a different world name and make two genuinely comparable runs look
 * incomparable, which is the opposite failure but the same kind of mistake.
 *
 * <h2>Scoped digests</h2>
 * {@link #digest()} answers "did anything move", which is the right question for deciding whether two
 * <em>runs</em> are comparable and the wrong one for deciding whether a <em>permutation</em> needs
 * re-measuring. One skill's cooldown changing moves the whole-config digest, and under that digest
 * alone a delta sweep would have to re-run every build in the catalog -- which is the same sweep it
 * was trying to avoid.
 *
 * <p>So the leaf map is kept rather than hashed and dropped, and {@link #itemDigest(String)},
 * {@link #skillDigest(Role, String)} and {@link #roleDigest(Role)} hash only the subtree a single
 * item, skill or role owns. A build's scope hash is then composed from the components it actually
 * carries, and a build is stale exactly when something it carries moved.
 *
 * <p>Component digests are memoised and composed rather than recomputed per build, which is not an
 * optimisation so much as a feasibility requirement: an {@code EQUIPMENT} catalog is hundreds of
 * thousands of builds and there are only ~27 weapons, ~20 runes and ~150 skills behind them. Scanning
 * seven thousand leaves per build would cost more than the duels.
 */
@Singleton
@CustomLog
public class SimConfigDigest {

    /**
     * Config trees whose leaves determine damage, per plugin.
     *
     * <p>{@code items/recipes} is absent on purpose: it decides how an item is obtained, not what it
     * does. The rest are the item stat trees plus champions' skill tree, which together are every
     * number the pipeline reads during a duel.
     */
    private static final List<String> ITEM_CONFIGS = List.of(
            "items/armor", "items/block", "items/consumable",
            "items/material", "items/misc", "items/tool", "items/weapon");

    private static final String SKILL_CONFIG = "skills/skills";

    /** Namespace for the role base health leaves, which come from the enum rather than from YAML. */
    private static final String ROLE_PREFIX = "roles|";

    /**
     * The character a prefix range is closed with. Every leaf key is built from config paths and
     * identifiers, none of which contain it, so {@code [prefix, prefix + this)} is exactly the set
     * of keys under {@code prefix}.
     */
    private static final char RANGE_END = '￿';

    /**
     * Every hashed config leaf, sorted, cached for the length of a run and cleared by
     * {@link #invalidate()}.
     *
     * <p>Sorted twice over: the digest must depend on the values and not on the order a YAML parser
     * happened to yield its keys, and a {@link NavigableMap} is what makes a scoped digest a subrange
     * lookup rather than a scan.
     *
     * <p>Cleared per run rather than never, because {@code /champions reload} can change every value
     * in it between runs, and a digest that could not notice that would be worse than no digest at all.
     */
    @Nullable
    private NavigableMap<String, String> leaves;

    @Nullable
    private String cached;

    /**
     * Memoised component digests, keyed by the scope prefix they were taken over.
     *
     * <p>Cleared with the leaf map, so a reload cannot leave a stale component digest behind while
     * the whole-config digest correctly moves -- which would be the worst of both, a delta sweep
     * carrying forward rows whose skill had in fact changed.
     */
    private final Map<String, String> components = new ConcurrentHashMap<>();

    /** Drops the cached digest so the next run re-reads the live configs. Called as a run starts. */
    public void invalidate() {
        cached = null;
        leaves = null;
        components.clear();
    }

    /**
     * A short hex digest of every config value that determines damage.
     *
     * <p>Must be called on the main thread: it reads the plugins' loaded configurations.
     *
     * @return the digest, or {@code "unavailable"} if the configs could not be read. A sentinel rather
     *         than a throw, because a run whose balance fingerprint is unknown is still a run worth
     *         having -- and a sentinel that is visibly not a hash is impossible to mistake for one,
     *         where a zero or an empty string would quietly group unrelated runs together.
     */
    public String digest() {
        if (cached != null) {
            return cached;
        }
        try {
            final NavigableMap<String, String> all = leaves();
            cached = hash(all);
            log.info("Balance config digest {} over {} config values", cached, all.size()).submit();
        } catch (Exception e) {
            log.warn("Could not digest the balance configuration; sim_run.config_hash will not"
                    + " distinguish runs taken either side of a balance change", e).submit();
            cached = "unavailable";
        }
        return cached;
    }

    /**
     * The digest of one registered item's config subtree, by {@code ItemRegistry} key.
     *
     * <p>The key's namespace picks the plugin and its name picks the subtree, but <em>which</em> of the
     * item trees the entry lives in is not knowable from the key -- a rune is in {@code items/misc} or
     * {@code items/material} depending on what it is, and a weapon that gained a tool component could
     * move. So every item tree is searched for the name, and the digest covers whatever is found.
     *
     * <p>An item with no config at all digests to the empty-scope value rather than throwing. That is
     * correct: an item whose stats are entirely code defaults has nothing that can drift, so nothing
     * it carries can ever make a build stale.
     */
    public String itemDigest(String registryKey) {
        return components.computeIfAbsent("item:" + registryKey, ignored -> {
            final int colon = registryKey.indexOf(':');
            final String namespace = colon < 0 ? "" : registryKey.substring(0, colon);
            final String name = colon < 0 ? registryKey : registryKey.substring(colon + 1);
            final String plugin = pluginNameFor(namespace);
            final List<String> prefixes = new ArrayList<>(ITEM_CONFIGS.size());
            for (String tree : ITEM_CONFIGS) {
                prefixes.add(plugin + '/' + tree + '|' + name + '.');
            }
            return scopeDigest(prefixes);
        });
    }

    /**
     * The digest of one skill's config subtree.
     *
     * <p>The path is {@code Skill.getPath}'s, reproduced here because that method is private and
     * because reproducing it is what makes the scope exact: {@code skills.<role>.<name lowercased,
     * spaces removed>}, or {@code skills.global.<name>} for a skill with no class type.
     *
     * <p>Both branches are hashed rather than the one the caller names. A wrong prefix here fails
     * silently and in the worst direction: it selects an empty subtree, which digests to a constant,
     * so the skill would look unchanged through every balance edit it ever received and a delta sweep
     * would carry its rows forward forever. Callers hold an allocation rather than a {@code Skill} and
     * so cannot always know whether a skill is role-scoped or global; hashing both costs a subrange
     * lookup that finds nothing and removes the failure mode entirely. The only price is that a global
     * and a role skill sharing a name invalidate together, which over-measures.
     *
     * @param role the skill's class type, or null if it is not known to the caller
     */
    public String skillDigest(@Nullable Role role, String skillName) {
        final String slug = skillName.toLowerCase(Locale.ROOT).replace(" ", "");
        final String branch = role == null ? "global" : role.name().toLowerCase(Locale.ROOT);
        return components.computeIfAbsent("skill:" + branch + '.' + slug, ignored -> {
            final String base = champions().getName() + '/' + SKILL_CONFIG + "|skills.";
            return scopeDigest(List.of(base + branch + '.' + slug + '.', base + "global." + slug + '.'));
        });
    }

    /** The digest of a role's own contribution to a duel: its base health. */
    public String roleDigest(Role role) {
        return components.computeIfAbsent("role:" + role.name(), ignored ->
                scopeDigest(List.of(ROLE_PREFIX + role.name().toLowerCase(Locale.ROOT) + '.')));
    }

    /**
     * A digest over every leaf under any of {@code prefixes}.
     *
     * <p>Taken as subranges of the sorted leaf map rather than by filtering it, so the cost is
     * proportional to what the scope actually contains rather than to the size of the config.
     */
    public String scopeDigest(List<String> prefixes) {
        final NavigableMap<String, String> all;
        try {
            all = leaves();
        } catch (Exception e) {
            // Same sentinel as digest(), and the same reasoning: a scope whose fingerprint is unknown
            // must be visibly unknown. It also fails safe for the delta sweep, because "unavailable"
            // is not equal to any stored hash, so an unreadable config re-measures rather than
            // carrying a row forward on a fingerprint nobody could compute.
            return "unavailable";
        }
        final TreeMap<String, String> scope = new TreeMap<>();
        for (String prefix : prefixes) {
            final SortedMap<String, String> range = all.subMap(prefix, true, prefix + RANGE_END, false);
            scope.putAll(range);
        }
        return hash(scope);
    }

    /**
     * Composes a digest over already-computed component digests.
     *
     * <p>Sorted before hashing, so a build whose runes or skills were enumerated in a different order
     * -- which the catalog's own sorting already prevents, but which a caller could reintroduce --
     * still fingerprints identically. Two builds that are the same build must never disagree here:
     * that is the whole basis on which a delta sweep decides not to re-measure one.
     */
    public static String compose(List<String> parts) {
        final List<String> sorted = new ArrayList<>(parts);
        sorted.sort(null);
        final StringBuilder canonical = new StringBuilder();
        for (String part : sorted) {
            canonical.append(part).append('\n');
        }
        return hex(digester().digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------

    private NavigableMap<String, String> leaves() {
        if (leaves != null) {
            return leaves;
        }
        final Champions champions = champions();
        final Core core = JavaPlugin.getPlugin(Core.class);

        final TreeMap<String, String> collected = new TreeMap<>();
        for (String config : ITEM_CONFIGS) {
            collect(collected, core, config);
            collect(collected, champions, config);
        }
        collect(collected, champions, SKILL_CONFIG);
        for (Role role : Role.values()) {
            collected.put(ROLE_PREFIX + role.name().toLowerCase(Locale.ROOT) + ".base_health",
                    String.valueOf(role.getHealth()));
        }
        leaves = collected;
        return collected;
    }

    private static Champions champions() {
        return JavaPlugin.getPlugin(Champions.class);
    }

    /**
     * The plugin whose config tree an item namespace's entries live in.
     *
     * <p>Item keys are namespaced by the plugin that registered them, and both plugins name their
     * config trees identically, so the namespace is the only thing that separates
     * {@code core:standard_sword} from a champions weapon of the same name.
     */
    private static String pluginNameFor(String namespace) {
        return "champions".equalsIgnoreCase(namespace)
                ? champions().getName()
                : JavaPlugin.getPlugin(Core.class).getName();
    }

    private static String hash(Map<String, String> leaves) {
        final MessageDigest sha256 = digester();
        leaves.forEach((key, value) -> {
            sha256.update(key.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '=');
            sha256.update(value.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
        });
        return hex(sha256.digest());
    }

    private static String hex(byte[] hash) {
        final StringBuilder hex = new StringBuilder(16);
        // First eight bytes only. This is an identity for grouping runs, not a security claim, and a
        // 16-character value is short enough to read in a log line and compare by eye.
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16))
                    .append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return hex.toString();
    }

    private static MessageDigest digester() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to digest the balance configuration", e);
        }
    }

    /**
     * Adds every leaf of one plugin's config tree, namespaced so two plugins' identically named keys
     * cannot collide or cancel out.
     *
     * <p>A config file that is absent or empty contributes nothing rather than failing: not every plugin
     * ships every tree, and the digest's job is to change when values change, which an absent file
     * satisfies by staying absent.
     */
    private static void collect(TreeMap<String, String> leaves, BPvPPlugin plugin, String configPath) {
        final ExtendedYamlConfiguration config = plugin.getConfig(configPath);
        if (config == null) {
            return;
        }
        final String prefix = plugin.getName() + '/' + configPath + '|';
        final List<String> keys = new ArrayList<>(config.getKeys(true));
        for (String key : keys) {
            if (config.isConfigurationSection(key)) {
                continue;
            }
            final Object value = config.get(key);
            if (value == null) {
                continue;
            }
            leaves.put(prefix + key, String.valueOf(value));
        }
    }
}
