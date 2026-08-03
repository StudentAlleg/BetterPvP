package me.mykindos.betterpvp.balancesim.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimWeaponProfile;
import me.mykindos.betterpvp.balancesim.database.jooq.tables.records.SimDuelDiagnosticRecord;
import me.mykindos.betterpvp.balancesim.database.jooq.tables.records.SimResultRecord;
import me.mykindos.betterpvp.balancesim.database.jooq.tables.records.SimTraceRecord;
import me.mykindos.betterpvp.balancesim.engine.SimulationTrigger;
import me.mykindos.betterpvp.core.database.Database;
import org.jetbrains.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static me.mykindos.betterpvp.balancesim.database.jooq.Tables.SIM_BUILD;
import static me.mykindos.betterpvp.balancesim.database.jooq.Tables.SIM_RUN;

/**
 * Persists simulation output, mirroring {@code GrafanaSnapshotRepository}: async jOOQ inside a
 * single transaction, realm-scoped.
 *
 * <p>Tables and columns come from the generated classes, so the schema in
 * {@code balancesim-migrations} and the code that writes it are checked against each other at
 * compile time. Regenerate with {@code ./gradlew :balance-simulation:generateJooq} after changing
 * a migration; that task needs the dev Postgres up, since it reads the live schema.
 */
@Singleton
@CustomLog
public class SimResultRepository {

    /**
     * How many build rows go up per statement. Postgres binds every value of a multi-row insert as
     * a parameter and caps a statement at 65535 of them; at sixteen columns per row this still leaves
     * most of an order of magnitude of headroom while cutting a thousand-build catalog to a handful
     * of round trips. Worth re-checking against the cap if the column count doubles again.
     */
    private static final int BUILD_INSERT_CHUNK = 500;

    private final Database database;

    @Inject
    public SimResultRepository(Database database) {
        this.database = database;
    }

    /**
     * Opens a {@code sim_run} row in {@code RUNNING} state and returns its id.
     *
     * <p>{@code configHash} is what makes two runs comparable, so it must cover every config
     * value that fed the run -- a "patch preview" is just two runs diffed in SQL.
     */
    public CompletableFuture<Long> openRun(int realm,
                                           SimulationTrigger trigger,
                                           String engineVersion,
                                           String configHash,
                                           String scenarioJson) {
        return database.getAsyncDslContext().executeAsync(ctx -> ctx
                .insertInto(SIM_RUN)
                .set(SIM_RUN.REALM, realm)
                .set(SIM_RUN.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(SIM_RUN.TRIGGER, trigger.name())
                .set(SIM_RUN.ENGINE_VERSION, engineVersion)
                .set(SIM_RUN.CONFIG_HASH, configHash)
                .set(SIM_RUN.SCENARIO, jsonb(scenarioJson))
                .set(SIM_RUN.STATUS, "RUNNING")
                .returning(SIM_RUN.ID)
                .fetchOne(SIM_RUN.ID));
    }

    /**
     * Stamps {@code finished_at} and the terminal status on a run.
     *
     * @param status {@code COMPLETED}, {@code FAILED} or {@code CANCELLED}
     */
    public CompletableFuture<Void> closeRun(long runId, String status) {
        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx
                .update(SIM_RUN)
                .set(SIM_RUN.FINISHED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(SIM_RUN.STATUS, status)
                .where(SIM_RUN.ID.eq(runId))
                .execute());
    }

    /**
     * Inserts every build of a run in chunked multi-row statements and returns fingerprint to id.
     *
     * <p>{@code sim_result.build_id} is a foreign key, so all of these must exist before the first
     * duel resolves, and the sweep needs their generated ids to write results at all. A per-build
     * round trip was fine for phase 1's six rows and is not for a catalog in the thousands, so the
     * rows go up in chunks with {@code RETURNING id, fingerprint} -- one statement per chunk, and
     * the mapping comes back from the database rather than being assumed from insertion order.
     *
     * @param builds must be unique by fingerprint; {@code idx_sim_build_run_fingerprint} is unique
     */
    public CompletableFuture<Map<String, Long>> insertBuilds(long runId, List<SimBuildSpec> builds) {
        if (builds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return database.getAsyncDslContext().executeAsync(ctx -> ctx.transactionResult(configuration -> {
            final DSLContext trx = DSL.using(configuration);
            final Map<String, Long> ids = new HashMap<>();

            for (int start = 0; start < builds.size(); start += BUILD_INSERT_CHUNK) {
                final List<SimBuildSpec> chunk =
                        builds.subList(start, Math.min(builds.size(), start + BUILD_INSERT_CHUNK));

                var insert = trx.insertInto(SIM_BUILD,
                        SIM_BUILD.RUN_ID, SIM_BUILD.ROLE, SIM_BUILD.WEAPON, SIM_BUILD.RUNES,
                        SIM_BUILD.SKILLS, SIM_BUILD.POINTS_SPENT, SIM_BUILD.BOOSTER, SIM_BUILD.FINGERPRINT,
                        SIM_BUILD.WEAPON_DAMAGE_BASE, SIM_BUILD.WEAPON_DAMAGE_MIN, SIM_BUILD.WEAPON_DAMAGE_MAX,
                        SIM_BUILD.WEAPON_ATTACK_SPEED_BASE, SIM_BUILD.WEAPON_ATTACK_SPEED_MIN,
                        SIM_BUILD.WEAPON_ATTACK_SPEED_MAX, SIM_BUILD.WEAPON_SLOT, SIM_BUILD.WEAPON_ALIASES);
                for (SimBuildSpec build : chunk) {
                    final SimWeaponProfile weapon = build.weapon();
                    insert = insert.values(runId,
                            build.role(),
                            build.weaponKey(),
                            jsonb(stringsToJson(build.runeKeys()), "[]"),
                            // Allocated levels only at insert time. The effective level depends on
                            // the equipped weapon and can only be read off a live combatant, so it
                            // is written back by updateBuildSkills once the build has been spawned.
                            jsonb(skillsToJson(build.skills()), "[]"),
                            build.pointsSpent(),
                            build.booster(),
                            build.fingerprint(),
                            // The weapon's configured figures, denormalised so a row stays readable
                            // after the item config it was measured under has moved on. Only the base
                            // is exercised -- MeleeDamageStatHandler applies stat.getValue() and
                            // ItemFactory.create rolls nothing -- so the min/max pair describes what
                            // the item could roll, not what this build swung for.
                            BigDecimal.valueOf(weapon.damageBase()),
                            BigDecimal.valueOf(weapon.damageMin()),
                            BigDecimal.valueOf(weapon.damageMax()),
                            BigDecimal.valueOf(weapon.attackSpeedBase()),
                            BigDecimal.valueOf(weapon.attackSpeedMin()),
                            BigDecimal.valueOf(weapon.attackSpeedMax()),
                            weapon.skillSlot(),
                            // Every weapon key this row's measurement covers. A dashboard resolving a
                            // weapon through this list is the difference between "never swept" and
                            // "swept under an equivalent key", which are opposite conclusions.
                            jsonb(stringsToJson(build.weaponAliases()), "[]"));
                }

                insert.returning(SIM_BUILD.ID, SIM_BUILD.FINGERPRINT)
                        .fetch()
                        .forEach(record -> ids.put(record.get(SIM_BUILD.FINGERPRINT), record.get(SIM_BUILD.ID)));
            }
            return ids;
        }));
    }

    /**
     * Overwrites a build's skill allocation with one carrying observed effective levels.
     *
     * <p>Effective level is a property of the equipped combatant, not of the catalog entry: a
     * booster weapon pushes a skill past {@code maxLevel} and the simulator is forbidden from
     * deriving that itself. So the build row is written with allocated levels up front to satisfy
     * the foreign key, and corrected once the first duel for it has spawned a player the real
     * accessor can be asked about.
     */
    public CompletableFuture<Void> updateBuildSkills(long buildId, String skillsJson) {
        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx
                .update(SIM_BUILD)
                .set(SIM_BUILD.SKILLS, jsonb(skillsJson, "[]"))
                .where(SIM_BUILD.ID.eq(buildId))
                .execute());
    }

    /**
     * Batch-inserts measured matchups for a run in one transaction.
     *
     * <p>A sweep produces these in the thousands, so they are accumulated by the orchestrator
     * and flushed in chunks rather than inserted per duel.
     */
    public CompletableFuture<Void> insertResults(long runId, List<SimResultRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx.transaction(configuration -> {
            DSLContext trx = DSL.using(configuration);
            final List<SimResultRecord> records = new ArrayList<>(rows.size());

            for (SimResultRow row : rows) {
                final SimResultRecord record = new SimResultRecord();
                record.setRunId(runId);
                record.setBuildId(row.buildId());
                record.setTargetRole(row.target().role());
                record.setTargetArmor(row.target().armorSetId());
                record.setTargetHp(BigDecimal.valueOf(row.target().hp()));
                // The defender's build affects the outcome via its DefensiveSkill passives and
                // resistance effects, so it is stored alongside the measurement.
                record.setTargetSkills(jsonb(skillsToJson(row.target().skills()), "[]"));
                record.setTargetPoints(row.target().pointsSpent());
                // The roles this one measurement stands for. Written on every row, including the
                // un-reduced tiers where it is just the target's own role -- an empty array would be
                // indistinguishable from "collapsed onto nothing" for a dashboard unpacking it.
                record.setTargetRoleAliases(jsonb(stringsToJson(row.target().roleAliases()), "[]"));
                // Nullable: a matchup that timed out has no TTK, and the figures derived from it
                // are null with it.
                record.setDmgPerHit(decimal(row.dmgPerHit()));
                record.setDpsSustained(decimal(row.dpsSustained()));
                record.setDpsBurst(decimal(row.dpsBurst()));
                record.setTtkS(decimal(row.ttkSeconds()));
                record.setHitsToKill(decimal(row.hitsToKill()));
                record.setEnergyLimited(row.energyLimited());
                record.setExtras(jsonb(row.extrasJson()));
                records.add(record);
            }

            // Every record sets the same columns and leaves the identity unset, so this is one
            // JDBC batch of a single insert statement rather than one statement per row.
            trx.batchInsert(records).execute();
        })).exceptionally(ex -> {
            log.error("Failed to insert {} sim results for run {}", rows.size(), runId, ex).submit();
            return null;
        });
    }

    /**
     * Batch-inserts per-duel diagnostics.
     *
     * <p>Separated from {@link #insertResults} rather than folded into it because the two have
     * different lifetimes and different failure consequences: a sweep is worthless without its
     * results and merely uninformative without its diagnostics, so a diagnostic flush that fails is
     * logged and swallowed exactly as the result flush is, but must never be able to take a result
     * flush down with it.
     */
    public CompletableFuture<Void> insertDuelDiagnostics(long runId, List<SimDuelDiagnosticRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx.transaction(configuration -> {
            final DSLContext trx = DSL.using(configuration);
            final List<SimDuelDiagnosticRecord> records = new ArrayList<>(rows.size());

            for (SimDuelDiagnosticRow row : rows) {
                final SimDuelDiagnosticRow.Side attacker = row.attacker();
                final SimDuelDiagnosticRow.Side defender = row.defender();
                final SimDuelDiagnosticRecord record = new SimDuelDiagnosticRecord();
                record.setRunId(runId);
                record.setBuildId(row.buildId());
                record.setTargetRole(row.target().role());
                record.setTargetArmor(row.target().armorSetId());
                record.setIteration(row.iteration());
                record.setArenaIndex(row.arenaIndex());

                record.setAttackerDuelsFought(attacker.snapshot().duelsFought());
                record.setDefenderDuelsFought(defender.snapshot().duelsFought());
                record.setAttackerRevives(attacker.snapshot().revives());
                record.setDefenderRevives(defender.snapshot().revives());
                record.setAttackerEverDied(attacker.snapshot().everDied());
                record.setDefenderEverDied(defender.snapshot().everDied());

                record.setOutcome(row.outcome().name());
                record.setResolvedTick(row.resolvedTick());
                record.setAttackerFirstHitTick(row.firstHitTick(attacker));
                record.setDefenderFirstHitTick(row.firstHitTick(defender));
                record.setAttackerHits(row.hitsBy(attacker));
                record.setDefenderHits(row.hitsBy(defender));
                record.setAttackerDamage(BigDecimal.valueOf(row.damageBy(attacker)));
                record.setDefenderDamage(BigDecimal.valueOf(row.damageBy(defender)));

                record.setAttackerStartHealth(finite(attacker.snapshot().health()));
                record.setAttackerMaxHealth(finite(attacker.snapshot().maxHealth()));
                record.setDefenderStartHealth(finite(defender.snapshot().health()));
                record.setDefenderMaxHealth(finite(defender.snapshot().maxHealth()));
                record.setAttackerEndHealth(decimal(attacker.endHealth()));
                record.setDefenderEndHealth(decimal(defender.endHealth()));

                record.setDetail(jsonb(row.detailJson()));
                records.add(record);
            }

            trx.batchInsert(records).execute();
        })).exceptionally(ex -> {
            log.error("Failed to insert {} duel diagnostics for run {}", rows.size(), runId, ex).submit();
            return null;
        });
    }

    /**
     * Batch-inserts per-hit traces.
     *
     * <p>Swallows its failures for the same reason {@link #insertDuelDiagnostics} does, and more
     * urgently: this table writes roughly one row per landed hit rather than one per duel, so it is
     * the flush most likely to be the one that struggles, and a sweep must not be lost to the table
     * that was only ever watching it.
     */
    public CompletableFuture<Void> insertTraces(long runId, List<SimTraceRow> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx.transaction(configuration -> {
            final DSLContext trx = DSL.using(configuration);
            final List<SimTraceRecord> records = new ArrayList<>(rows.size());

            for (SimTraceRow row : rows) {
                final SimTraceRecord record = new SimTraceRecord();
                record.setRunId(runId);
                record.setBuildId(row.buildId());
                record.setTargetRole(row.targetRole());
                record.setTargetArmor(row.targetArmor());
                record.setIteration(row.iteration());
                record.setArenaIndex(row.arenaIndex());
                record.setTick(row.tick());
                record.setSeq(row.seq());
                record.setTMs(row.tMs());
                record.setActor(row.actor());
                record.setEvent(row.event());
                record.setRawAmount(decimal(row.rawAmount()));
                record.setAmount(decimal(row.amount()));
                records.add(record);
            }

            trx.batchInsert(records).execute();
        })).exceptionally(ex -> {
            log.error("Failed to insert {} sim traces for run {}", rows.size(), runId, ex).submit();
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * A measured attribute as a column value, or null when the entity did not carry it.
     *
     * <p>An absent attribute reads as {@code NaN}, which {@code BigDecimal.valueOf} throws on. That
     * would abort the whole flush over a field that is merely unknown, so it becomes a SQL null --
     * which is what "this entity has no such attribute" means anyway.
     */
    @Nullable
    private static BigDecimal finite(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private static JSONB jsonb(String json) {
        return jsonb(json, "{}");
    }

    /**
     * @param fallback what an absent value means for this column -- {@code "{}"} for the object
     *                 columns, {@code "[]"} for the list ones. The columns are {@code NOT NULL}
     *                 with matching defaults, so an empty run must still write valid JSON of the
     *                 shape the dashboards expect to unpack.
     */
    private static JSONB jsonb(String json, String fallback) {
        return JSONB.valueOf(json == null || json.isBlank() ? fallback : json);
    }

    /**
     * Converts a measured figure to the column's {@code NUMERIC} type, preserving null.
     *
     * <p>{@code BigDecimal.valueOf(double)} goes through {@code Double.toString}, so the value
     * that lands in Postgres is the shortest decimal that round-trips to the same double rather
     * than the exact binary expansion.
     */
    @Nullable
    private static BigDecimal decimal(@Nullable Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * Serialises a skill allocation list to the same shape the {@code sim_build.skills} column
     * uses, so an attacker build and a defender build are queryable identically. Written by hand
     * rather than pulling in a JSON binder because the values are all primitives and short
     * identifiers.
     *
     * <p>Public because the orchestrator writes the same shape back over a build row once the
     * effective levels have been read off a live combatant; both callers must produce identical
     * JSON or a dashboard would have to handle two shapes for one column.
     */
    public static String skillsToJson(List<SimSkillAllocation> skills) {
        if (skills == null || skills.isEmpty()) {
            return "[]";
        }
        final StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < skills.size(); i++) {
            final SimSkillAllocation skill = skills.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"skill\":\"").append(escape(skill.skillName()))
                    .append("\",\"slot\":\"").append(escape(skill.slot()))
                    .append("\",\"allocated_level\":").append(skill.allocatedLevel())
                    .append(",\"effective_level\":").append(skill.effectiveLevel())
                    .append('}');
        }
        return json.append(']').toString();
    }

    /**
     * A JSON string array, the shape every list-of-identifiers column on these tables uses.
     *
     * <p>Shared by the rune list and by both alias lists rather than written per column, so the three
     * cannot drift into shapes a dashboard has to special-case. Written by hand for the reason
     * {@link #skillsToJson} is: the values are short identifiers and a JSON binder would be a
     * dependency carried for two loops.
     */
    private static String stringsToJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        final StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
