plugins {
    id("org.flywaydb.flyway")
    id("io.papermc.paperweight.userdev")
}

version = "1.0.0"
group = "me.mykindos.betterpvp.balancesim"
description = "Balance simulation engine for BetterPvP (dev servers only)"

dependencies {
    compileOnly(libs.bundles.paper)
    paperweight.paperDevBundle(libs.versions.paper)
    implementation(libs.reflections)

    compileOnly(project(":core"))
    compileOnly(project(":champions"))

    // The `jooqdynamic` convention plugin is intentionally not applied: the sim_* repository
    // uses string-based DSL.table(...) so it builds without a live Postgres for codegen.
    compileOnly(libs.jooq)

    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}
