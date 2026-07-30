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
import java.util.TreeMap;

/**
 * Fingerprints the game configuration a run's numbers actually came from.
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

    /**
     * Cached for the length of a run, cleared by {@link #invalidate()}.
     *
     * <p>Cached because it is asked for twice per run -- once for {@code config_hash} and once for the
     * scenario JSON -- and walking every leaf of eight YAML trees is not free. Cleared per run rather
     * than never, because {@code /champions reload} can change every value in it between runs, and a
     * digest that could not notice that would be worse than no digest at all.
     */
    @Nullable
    private String cached;

    /** Drops the cached digest so the next run re-reads the live configs. Called as a run starts. */
    public void invalidate() {
        cached = null;
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
            cached = compute();
        } catch (Exception e) {
            log.warn("Could not digest the balance configuration; sim_run.config_hash will not"
                    + " distinguish runs taken either side of a balance change", e).submit();
            cached = "unavailable";
        }
        return cached;
    }

    private String compute() throws NoSuchAlgorithmException {
        final Champions champions = JavaPlugin.getPlugin(Champions.class);
        final Core core = JavaPlugin.getPlugin(Core.class);

        // Sorted, so the digest depends on the values and not on the order a YAML parser happened to
        // yield its keys -- two identical configs must produce one hash.
        final TreeMap<String, String> leaves = new TreeMap<>();
        for (String config : ITEM_CONFIGS) {
            collect(leaves, core, config);
            collect(leaves, champions, config);
        }
        collect(leaves, champions, SKILL_CONFIG);
        for (Role role : Role.values()) {
            leaves.put("roles|" + role.name().toLowerCase(Locale.ROOT) + ".base_health",
                    String.valueOf(role.getHealth()));
        }

        final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        leaves.forEach((key, value) -> {
            sha256.update(key.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '=');
            sha256.update(value.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
        });

        final byte[] hash = sha256.digest();
        final StringBuilder hex = new StringBuilder(16);
        // First eight bytes only. This is an identity for grouping runs, not a security claim, and a
        // 16-character value is short enough to read in a log line and compare by eye.
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16))
                    .append(Character.forDigit(hash[i] & 0xF, 16));
        }
        log.info("Balance config digest {} over {} config values", hex, leaves.size()).submit();
        return hex.toString();
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
