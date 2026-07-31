package me.mykindos.betterpvp.balancesim.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Renders the audit's verdicts as a YAML file a human reviews and then pastes into config.
 *
 * <p>YAML with comments rather than a table or a log dump, because the artifact has two readers and
 * has to serve both: the exclusion lists are valid config as they stand, and every line carries the
 * evidence behind it as a trailing comment so a reviewer can disagree with any individual verdict
 * without re-running anything.
 *
 * <p>What it deliberately does <em>not</em> do is write to config. Generate, review, commit is the
 * workflow, and the file says so at the top -- an audit applied automatically would silently exclude
 * a skill that a later balance change made relevant again.
 */
public final class SkillAuditReport {

    private SkillAuditReport() {
    }

    /**
     * Writes the artifact and returns where it went.
     *
     * @param directory   where audits are kept; created if absent
     * @param runId       the {@code sim_run} the verdicts came from
     * @param scope       the tier that was swept, which bounds what the verdicts can mean
     * @param scenario    one-way or mutual; a one-way sweep cannot see defensive value at all
     * @param configHash  the run's config digest, so a stale list is detectable later
     * @param thresholds  what counted as a real change
     * @param verdicts    every skill the audit reached a conclusion about
     * @param skippedMultiSkill builds dropped because they carried more than one skill
     */
    public static Path write(Path directory,
                             long runId,
                             String scope,
                             String scenario,
                             String configHash,
                             AuditThresholds thresholds,
                             List<SkillVerdict> verdicts,
                             int skippedMultiSkill) throws IOException {
        Files.createDirectories(directory);
        final Path file = directory.resolve("skill-audit-run-" + runId + ".yml");
        Files.writeString(file,
                render(runId, scope, scenario, configHash, thresholds, verdicts, skippedMultiSkill),
                StandardCharsets.UTF_8);
        return file;
    }

    static String render(long runId,
                         String scope,
                         String scenario,
                         String configHash,
                         AuditThresholds thresholds,
                         List<SkillVerdict> verdicts,
                         int skippedMultiSkill) {
        final List<SkillVerdict> undrivable = filter(verdicts, SkillRelevanceBucket.UNDRIVABLE);
        final List<SkillVerdict> inert = filter(verdicts, SkillRelevanceBucket.INERT);
        final List<SkillVerdict> relevant = filter(verdicts, SkillRelevanceBucket.RELEVANT);

        final StringBuilder out = new StringBuilder();
        out.append("# Skill relevance audit -- generated, NOT applied.\n");
        out.append("#\n");
        out.append("# Review every line before copying any of it into config. These verdicts are\n");
        out.append("# measurements of one sweep under one configuration, not permanent facts about\n");
        out.append("# the skills. See docs/balance-simulation/DASHBOARD-PARITY-NOTES.md.\n");
        out.append("#\n");
        out.append(comment("run", String.valueOf(runId)));
        out.append(comment("generated", Instant.now().toString()));
        out.append(comment("scope", scope));
        out.append(comment("scenario", scenario));
        out.append(comment("config_hash", configHash));
        out.append(comment("thresholds", thresholds.describe()));
        out.append(comment("counts", String.format(Locale.ROOT,
                "%d relevant, %d inert, %d undrivable", relevant.size(), inert.size(), undrivable.size())));
        if (skippedMultiSkill > 0) {
            out.append(comment("skipped", skippedMultiSkill
                    + " multi-skill builds (a build carrying several skills cannot be attributed"
                    + " to one of them)"));
        }
        out.append("#\n");
        out.append("# STALENESS: these verdicts are only true for config_hash above. When the live\n");
        out.append("# digest differs, re-run the audit rather than trusting this file -- a skill\n");
        out.append("# buffed from zero stays excluded forever otherwise.\n");
        out.append("#\n");
        out.append("# SCOPE LIMIT: each skill was measured ALONE against a bare baseline. An energy\n");
        out.append("# skill has nothing to enable in a build with no other skills, so 'inert' here\n");
        out.append("# means 'cannot matter alone', not 'cannot ever matter'.\n");
        if ("ONE_WAY".equalsIgnoreCase(scenario)) {
            out.append("#\n");
            out.append("# ONE_WAY sweep: the defender never swings, so nothing here says anything\n");
            out.append("# about defensive value. The defensive list needs a defender skill axis.\n");
        }
        out.append("\n");

        out.append(section("UNDRIVABLE -- harness gaps, NOT balance findings",
                "The engine could not make these fire. Excluding them would bake a rotation-policy\n"
                        + "# bug into config. Fix the rotation, or confirm the skill is a known\n"
                        + "# unsupported archetype, before doing anything else with this list.",
                "undrivableSkills", undrivable));

        out.append(section("INERT -- fired, moved nothing measurable",
                "Candidates for the offensive exclusion list. A skill whose value is crowd control,\n"
                        + "# repositioning or survivability lands here correctly and is still worth\n"
                        + "# carrying, so this is a proposal rather than an answer.",
                "excludedOffensive", inert));

        out.append(section("RELEVANT -- kept in the sweep",
                "Listed for review, not for config. Check for two disagreements: a skill relevant\n"
                        + "# here with no Damage/Energy marker is a champions-side labelling bug, and\n"
                        + "# an [ENERGY ONLY] skill is one a damage-only audit would have missed.",
                "relevantSkills", relevant));

        return out.toString();
    }

    private static String section(String title, String note, String key, List<SkillVerdict> verdicts) {
        final StringBuilder out = new StringBuilder();
        out.append("# ").append("-".repeat(72)).append('\n');
        out.append("# ").append(title).append('\n');
        out.append("# ").append(note).append('\n');
        out.append("# ").append("-".repeat(72)).append('\n');
        out.append(key).append(':');
        if (verdicts.isEmpty()) {
            out.append(" []\n\n");
            return out.toString();
        }
        out.append('\n');
        for (SkillVerdict verdict : verdicts) {
            out.append("  - \"").append(verdict.skillName().replace("\"", "\\\"")).append('"');
            out.append("  # ").append(verdict.role()).append(", ").append(verdict.markers());
            out.append(" | ").append(verdict.evidence()).append('\n');
        }
        out.append('\n');
        return out.toString();
    }

    private static List<SkillVerdict> filter(List<SkillVerdict> verdicts, SkillRelevanceBucket bucket) {
        return verdicts.stream().filter(verdict -> verdict.bucket() == bucket).toList();
    }

    private static String comment(String key, String value) {
        return "# " + key + ": " + value + '\n';
    }

    /** A short summary for the command and the log, where the whole artifact would not fit. */
    public static String summarise(List<SkillVerdict> verdicts) {
        final StringJoiner joiner = new StringJoiner(", ");
        for (SkillRelevanceBucket bucket : SkillRelevanceBucket.values()) {
            joiner.add(filter(verdicts, bucket).size() + " " + bucket.name().toLowerCase(Locale.ROOT));
        }
        return joiner.toString();
    }
}
