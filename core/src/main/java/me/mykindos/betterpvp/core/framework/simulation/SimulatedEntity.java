package me.mykindos.betterpvp.core.framework.simulation;

import org.bukkit.entity.Entity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Marks an entity as belonging to the balance simulator, and asks whether one is.
 *
 * <p>The simulator fights fake players through the <em>real</em> combat pipeline, so their duels
 * reach every listener a genuine fight does -- including the ones that persist kills, combat
 * stats and leaderboard ratings. Those must never see a simulated death. The flag lives here in
 * {@code core} rather than in the simulation plugin because the guards belong at the persistence
 * chokepoints, and {@code core} cannot depend on a plugin that is deliberately absent from
 * production builds.
 *
 * <p>Metadata rather than a registry of UUIDs: it lives and dies with the entity, so a combatant
 * that is removed without a clean teardown cannot leave a stale entry behind that later
 * suppresses a real player's stats.
 */
public final class SimulatedEntity {

    /**
     * Metadata key set on every fake combatant. Reading metadata needs no plugin instance, so
     * {@code core} can check the flag without knowing who set it.
     */
    public static final String METADATA_KEY = "bpvp_simulated";

    private SimulatedEntity() {
    }

    /**
     * Flags an entity as simulated. Called by the simulation plugin as it spawns a combatant,
     * before the combatant is given a role, a build or a weapon -- anything that can produce a
     * persistable side effect must happen after this point.
     */
    public static void mark(@NotNull Entity entity, @NotNull Plugin plugin) {
        entity.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
    }

    /**
     * Whether this entity is a simulation combatant. Persistence and leaderboard paths gate on
     * this; null-tolerant so callers can pass a nullable damager straight in.
     */
    public static boolean isSimulated(@Nullable Entity entity) {
        return entity != null && entity.hasMetadata(METADATA_KEY);
    }
}
