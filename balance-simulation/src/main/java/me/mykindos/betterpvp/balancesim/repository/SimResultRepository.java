package me.mykindos.betterpvp.balancesim.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.engine.SimulationTrigger;
import me.mykindos.betterpvp.core.database.Database;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Name;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Persists simulation output, mirroring {@code GrafanaSnapshotRepository}: async jOOQ inside a
 * single transaction, realm-scoped.
 *
 * <p>Tables and columns are referenced by name via {@link DSL#table(Name)} rather than through
 * generated classes, so this module builds without running jOOQ codegen against a live
 * Postgres. Swapping to generated classes later is mechanical.
 */
@Singleton
@CustomLog
public class SimResultRepository {

    private static final Table<?> SIM_RUN = DSL.table(DSL.name("sim_run"));
    private static final Table<?> SIM_BUILD = DSL.table(DSL.name("sim_build"));
    private static final Table<?> SIM_RESULT = DSL.table(DSL.name("sim_result"));

    private static final Field<Long> ID = DSL.field(DSL.name("id"), Long.class);

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
                .columns(field("realm", Integer.class),
                        field("started_at", OffsetDateTime.class),
                        field("trigger", String.class),
                        field("engine_version", String.class),
                        field("config_hash", String.class),
                        jsonbField("scenario"),
                        field("status", String.class))
                .values(DSL.val(realm),
                        DSL.val(OffsetDateTime.now(ZoneOffset.UTC)),
                        DSL.val(trigger.name()),
                        DSL.val(engineVersion),
                        DSL.val(configHash),
                        jsonb(scenarioJson),
                        DSL.val("RUNNING"))
                .returning(ID)
                .fetchOne(ID));
    }

    /**
     * Stamps {@code finished_at} and the terminal status on a run.
     *
     * @param status {@code COMPLETED}, {@code FAILED} or {@code CANCELLED}
     */
    public CompletableFuture<Void> closeRun(long runId, String status) {
        return database.getAsyncDslContext().executeAsyncVoid(ctx -> ctx
                .update(SIM_RUN)
                .set(field("finished_at", OffsetDateTime.class), OffsetDateTime.now(ZoneOffset.UTC))
                .set(field("status", String.class), status)
                .where(ID.eq(runId))
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
                .columns(field("run_id", Long.class),
                        field("role", String.class),
                        field("weapon", String.class),
                        jsonbField("runes"),
                        jsonbField("skills"),
                        field("points_spent", Integer.class),
                        field("booster", Boolean.class),
                        field("fingerprint", String.class))
                .values(DSL.val(runId),
                        DSL.val(build.role()),
                        DSL.val(build.weaponKey()),
                        jsonb(runesJson),
                        jsonb(skillsJson),
                        DSL.val(build.pointsSpent()),
                        DSL.val(build.booster()),
                        DSL.val(build.fingerprint()))
                .returning(ID)
                .fetchOne(ID));
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
            var insert = trx.insertInto(SIM_RESULT)
                    .columns(field("run_id", Long.class),
                            field("build_id", Long.class),
                            field("target_role", String.class),
                            field("target_armor", String.class),
                            field("target_hp", Double.class),
                            jsonbField("target_skills"),
                            field("target_points", Integer.class),
                            field("dmg_per_hit", Double.class),
                            field("dps_sustained", Double.class),
                            field("dps_burst", Double.class),
                            field("ttk_s", Double.class),
                            field("hits_to_kill", Double.class),
                            field("energy_limited", Boolean.class),
                            jsonbField("extras"));

            for (SimResultRow row : rows) {
                insert = insert.values(DSL.val(runId),
                        DSL.val(row.buildId()),
                        DSL.val(row.target().role()),
                        DSL.val(row.target().armorSetId()),
                        DSL.val(row.target().hp()),
                        // The defender's build affects the outcome via its DefensiveSkill passives
                        // and resistance effects, so it is stored alongside the measurement.
                        jsonb(skillsToJson(row.target().skills())),
                        DSL.val(row.target().pointsSpent()),
                        // Nullable: a matchup that timed out has no TTK. Bind with an explicit
                        // type so a null does not lose its SQL type.
                        DSL.val(row.dmgPerHit(), SQLDataType.DOUBLE),
                        DSL.val(row.dpsSustained(), SQLDataType.DOUBLE),
                        DSL.val(row.dpsBurst(), SQLDataType.DOUBLE),
                        DSL.val(row.ttkSeconds(), SQLDataType.DOUBLE),
                        DSL.val(row.hitsToKill(), SQLDataType.DOUBLE),
                        DSL.val(row.energyLimited()),
                        jsonb(row.extrasJson()));
            }

            insert.execute();
        })).exceptionally(ex -> {
            log.error("Failed to insert {} sim results for run {}", rows.size(), runId, ex).submit();
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static <T> Field<T> field(String name, Class<T> type) {
        return DSL.field(DSL.name(name), type);
    }

    private static Field<JSONB> jsonbField(String name) {
        return DSL.field(DSL.name(name), SQLDataType.JSONB);
    }

    private static Field<JSONB> jsonb(String json) {
        return DSL.val(JSONB.valueOf(json == null || json.isBlank() ? "{}" : json), SQLDataType.JSONB);
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
