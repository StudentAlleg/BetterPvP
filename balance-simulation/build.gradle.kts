plugins {
    id("org.flywaydb.flyway")
    id("io.papermc.paperweight.userdev")
    id("jooqdynamic")
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

    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)

    // The relevance audit is pure logic over measured aggregates, so it is the one part of this
    // plugin that can be tested without a server. Champions is on the test classpath because the
    // classifier reads skill archetypes and marker interfaces from it.
    testImplementation(libs.bundles.test)
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":core"))
    testImplementation(project(":champions"))
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}
