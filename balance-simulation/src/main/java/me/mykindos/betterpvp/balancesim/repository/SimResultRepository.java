package me.mykindos.betterpvp.balancesim.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.database.jooq.tables.records.SimResultRecord;
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
     * a parameter and caps a statement at 65535 of them; at eight columns per row this leaves an
     * order of magnitude of headroom while still cutting a thousand-build catalog to a handful of
     * round trips.
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
                        SIM_BUILD.SKILLS, SIM_BUILD.POINTS_SPENT, SIM_BUILD.BOOSTER, SIM_BUILD.FINGERPRINT);
                for (SimBuildSpec build : chunk) {
                    insert = insert.values(runId,
                            build.role(),
                            build.weaponKey(),
                            jsonb(runesToJson(build.runeKeys()), "[]"),
                            // Allocated levels only at insert time. The effective level depends on
                            // the equipped weapon and can only be read off a live combatant, so it
                            // is written back by updateBuildSkills once the build has been spawned.
                            jsonb(skillsToJson(build.skills()), "[]"),
                            build.pointsSpent(),
                            build.booster(),
                            build.fingerprint());
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

    /** Rune keys as a JSON string array, matching the {@code sim_build.runes} column's shape. */
    private static String runesToJson(List<String> runeKeys) {
        if (runeKeys == null || runeKeys.isEmpty()) {
            return "[]";
        }
        final StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < runeKeys.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(runeKeys.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
