package me.mykindos.betterpvp.balancesim.repository;

import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;

/**
 * One measured matchup, ready to insert into {@code sim_result}.
 *
 * <p>All figures are the mean across the run's Monte-Carlo iterations; the distribution
 * (percentiles, iteration count, per-modifier breakdown) rides along in {@code extrasJson}
 * rather than widening the table.
 *
 * @param buildId       the {@code sim_build} row this was measured for
 * @param target        the defender configuration
 * @param dmgPerHit     mean final damage of a single melee hit
 * @param dpsSustained  damage per second over the whole fight
 * @param dpsBurst      damage per second over the best short window
 * @param ttkSeconds    time to kill, or {@code null} if the timeout hit first
 * @param hitsToKill    mean hits required, {@code null} alongside a null TTK
 * @param energyLimited true when the rotation stalled on energy rather than cooldowns
 * @param extrasJson    JSON object of percentiles and modifier breakdown
 * @param configScopeHash digest of exactly the config this matchup depended on -- the build's scope,
 *                      the target's scope, and the measurement terms that decide what a duel means.
 *                      What a later {@code --changed} sweep compares against to decide whether this
 *                      row is still true; see {@code SimConfigDigest}. Empty when unavailable, which
 *                      compares equal to nothing and so is never carried forward
 */
public record SimResultRow(long buildId,
                           SimTargetSpec target,
                           Double dmgPerHit,
                           Double dpsSustained,
                           Double dpsBurst,
                           Double ttkSeconds,
                           Double hitsToKill,
                           boolean energyLimited,
                           String extrasJson,
                           String configScopeHash) {
}
