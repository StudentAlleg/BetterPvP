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
import java.util.List;
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
     * Inserts one {@code sim_build} row and returns its id.
     *
     * <p>{@code skillsJson} carries allocated <em>and</em> effective level per slot; storing
     * only the allocation would make a booster run indistinguishable from a non-booster one at
     * the same points spend, despite producing different damage.
     */
    public CompletableFuture<Long> insertBuild(long runId, SimBuildSpec build, String runesJson, String skillsJson) {
        return database.getAsyncDslContext().executeAsync(ctx -> ctx
                .insertInto(SIM_BUILD)
                .set(SIM_BUILD.RUN_ID, runId)
                .set(SIM_BUILD.ROLE, build.role())
                .set(SIM_BUILD.WEAPON, build.weaponKey())
                .set(SIM_BUILD.RUNES, jsonb(runesJson, "[]"))
                .set(SIM_BUILD.SKILLS, jsonb(skillsJson))
                .set(SIM_BUILD.POINTS_SPENT, build.pointsSpent())
                .set(SIM_BUILD.BOOSTER, build.booster())
                .set(SIM_BUILD.FINGERPRINT, build.fingerprint())
                .returning(SIM_BUILD.ID)
                .fetchOne(SIM_BUILD.ID));
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
     */
    private static String skillsToJson(List<SimSkillAllocation> skills) {
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

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
