import java.util.zip.ZipFile

plugins {
    id("net.fabricmc.fabric-loom") version "1.18.4"
}

group = "me.seedexplorer"
version = "1.0.0"
base { archivesName = "seed-explorer" }
repositories { mavenCentral() }
dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.162.0+26.3")
}
java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "seed-explorer-client-test"
        enableGameTests = false
        enableClientGameTests = true
    }
}

sourceSets.named("gametest") {
    java.setSrcDirs(listOf("src/gametest/java"))
    resources.setSrcDirs(listOf("src/gametest/resources"))
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

tasks.named<JavaCompile>("compileGametestJava") {
    options.release.set(25)
}

tasks.named<ProcessResources>("processGametestResources") {
    val rendererVisualAudit = providers.gradleProperty("rendererVisualAudit").orNull == "true"
    val spectatorVisualAudit = providers.gradleProperty("spectatorVisualAudit").orNull == "true"
    val terrainMovingAudit = providers.gradleProperty("terrainMovingAudit").orNull == "true"
    inputs.property("spectatorVisualAudit", spectatorVisualAudit)
    inputs.property("rendererVisualAudit", rendererVisualAudit)
    inputs.property("terrainMovingAudit", terrainMovingAudit)
    inputs.properties(mapOf("version" to project.version, "jdk_version" to "25",
        "minecraft_version" to "26.3", "loader_version" to "0.19.5"))
    filesMatching("fabric.mod.json") { expand(inputs.properties) }
    doLast {
        if (rendererVisualAudit || spectatorVisualAudit || terrainMovingAudit) {
            val descriptor = destinationDir.resolve("fabric.mod.json")
            descriptor.writeText(descriptor.readText().replace(
                Regex("\"fabric-client-gametest\"\\s*:\\s*\\[[\\s\\S]*?\\]"),
                "\"fabric-client-gametest\": [\"me.seedexplorer.standalone.test.StandaloneTerrainVisualGameTest\"]"))
            if (spectatorVisualAudit) descriptor.writeText(descriptor.readText().replace(
                "StandaloneTerrainVisualGameTest", "StandaloneSpectatorVisualGameTest"))
        }
    }
}

loom.runs.named("clientGameTest") {
    vmArgs("-Doshi.util.wmi.timeout=1000")
    if (providers.gradleProperty("terrainMovingAudit").orNull?.toBoolean() == true) {
        val auditSeconds = providers.gradleProperty("terrainMovingAuditSeconds").getOrElse("120")
        vmArgs("-Dseedexplorer.terrainMovingAudit=true", "-Dseedexplorer.terrainMovingAuditSeconds=$auditSeconds", "-Xmx6G")
    }
    if (providers.gradleProperty("searchWorkflowAudit").orNull?.toBoolean() == true) {
        vmArgs("-Dseedexplorer.searchWorkflowAudit=true", "-Xmx3G")
    }
    if (providers.gradleProperty("mapFollowupAudit").orNull?.toBoolean() == true) {
        vmArgs("-Dseedexplorer.mapFollowupAudit=true", "-Xmx3G")
    }
    runDir = "run/clientGameTest"
    if (providers.gradleProperty("seedCrackingAudit").orNull?.toBoolean() == true) {
        vmArgs("-Dseedexplorer.runSeedCrackingAudit=true", "-Xmx6G")
    }
    if (providers.gradleProperty("predictionOracle").orNull?.toBoolean() == true) {
        val oracleHeap = providers.gradleProperty("predictionOracleHeap").getOrElse("6G")
        vmArgs("-Xmx$oracleHeap")
        vmArgs("-Dseedexplorer.runPredictionOracle=true")
        providers.gradleProperty("predictionOracleSeeds").orNull?.let { seeds ->
            vmArgs("-Dseedexplorer.oracleSeeds=$seeds")
        }
        providers.gradleProperty("predictionOracleTypes").orNull?.let { types ->
            vmArgs("-Dseedexplorer.oracleTypes=$types")
        }
        providers.gradleProperty("predictionOracleOmitTypes").orNull?.let { types ->
            vmArgs("-Dseedexplorer.oracleOmitTypes=$types")
        }
        mapOf(
            "predictionOracleLocateRadius" to "seedexplorer.oracleLocateRadius",
            "predictionOracleWindowRadius" to "seedexplorer.oracleWindowRadius",
            "predictionOracleQueryAttempts" to "seedexplorer.oracleQueryAttempts",
            "predictionOraclePredictedSamples" to "seedexplorer.oraclePredictedSamples",
            "predictionOracleDungeonRadius" to "seedexplorer.oracleDungeonRadius",
            "predictionOracleCaseTimeoutTicks" to "seedexplorer.oracleCaseTimeoutTicks",
            "predictionOracleDimensions" to "seedexplorer.oracleDimensions",
            "predictionOracleStarts" to "seedexplorer.oracleStarts",
            "predictionOracleFirstChunk" to "seedexplorer.oracleFirstChunk",
            "predictionOracleSkipLoot" to "seedexplorer.oracleSkipLoot"
        ).forEach { (gradleProperty, systemProperty) ->
            providers.gradleProperty(gradleProperty).orNull?.let { value ->
                vmArgs("-D$systemProperty=$value")
            }
        }
        if (providers.gradleProperty("campPlacementTrace").orNull?.toBoolean() == true) {
            vmArgs("-Dseedexplorer.campPlacementTrace=true")
            providers.gradleProperty("campPlacementTracePath").orNull?.let { path ->
                vmArgs("-Dseedexplorer.campPlacementTracePath=$path")
            }
        }
        if (providers.gradleProperty("villageRngTrace").orNull?.toBoolean() == true) {
            vmArgs("-Dseedexplorer.villageRngTrace=true")
            providers.gradleProperty("villageRngTracePath").orNull?.let { path ->
                vmArgs("-Dseedexplorer.villageRngTraceFile=$path")
            }
        }
        if (providers.gradleProperty("mineshaftTrace").orNull?.toBoolean() == true) {
            vmArgs("-Dseedexplorer.mineshaftTrace=true")
        }
        if (providers.gradleProperty("predictionOracleDebugScans").orNull?.toBoolean() == true) {
            vmArgs("-Dseedexplorer.oracleDebugScans=true")
        }
    }
}

// Optional interactive smoke session: normal client lifecycle, no test-driven shutdown.
providers.gradleProperty("manualTestWorld").orNull?.let { world ->
    loom.runs.named("client") {
        runDir = providers.gradleProperty("manualTestRunDir").getOrElse("run/manualTest")
        vmArgs("-Doshi.util.wmi.timeout=1000")
        programArgs("--quickPlaySingleplayer", world)
    }
}

// Use the same explicit shared-source boundary as the compatibility harness.
// Reading this source list does not evaluate the old build or resolve Meteor.
val sharedIncludes = Regex("java\\.include\\(\"([^\"]+)\"\\)")
    .findAll(file("../build.gradle.kts").readText())
    .map { it.groupValues[1] }.toList()
sourceSets.main {
    java.setSrcDirs(listOf("../src/standaloneFabric/java", "../src/main/java", "../src/coreModel/java", "../seed-cracking/src/main/java"))
    java.setIncludes(sharedIncludes + listOf(
        "me/seedexplorer/addon/structures/GeneratedStructure.java",
        "me/seedexplorer/addon/structures/StructureType.java",
        "me/seedexplorer/addon/worldgen/PredictedBiome.java",
        "me/seedexplorer/addon/worldgen/PredictionContext.java",
        "me/seedexplorer/addon/worldgen/PredictionVersionPolicy.java"))
    resources.setSrcDirs(listOf("../src/standaloneFabric/resources"))
}

tasks.processResources {
    inputs.properties(mapOf("version" to project.version, "jdk_version" to "25",
        "minecraft_version" to "26.3", "loader_version" to "0.19.5"))
    filesMatching("fabric.mod.json") { expand(inputs.properties) }
    from("../src/main/resources") {
        include("assets/meteor-seed-explorer/shaders/**",
            "assets/meteor-seed-explorer/textures/blocks/**",
            "assets/meteor-seed-explorer/textures/structures/**")
    }
    from("../src/main/resources/data/meteor-seed-explorer/versioned_loot") {
        into("data/seed-explorer/versioned_loot")
    }
    doLast {
        val textures = file("../src/main/resources/assets/meteor-seed-explorer/textures/blocks")
            .walkTopDown().filter { it.isFile && it.extension == "png" }
            .map { it.name }.sorted().toList()
        val index = destinationDir.resolve("assets/meteor-seed-explorer/textures/blocks/index.txt")
        index.parentFile.mkdirs()
        index.writeText(textures.joinToString("\n", postfix = "\n"))
    }
}
tasks.jar { from("../LICENSE") }

val verifyStandalone by tasks.registering {
    dependsOn(tasks.jar)
    doLast {
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            check(entries.none { it.startsWith("meteordevelopment/") || it.startsWith("assets/meteor-client/") })
            val bad = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
                .filter { entry ->
                    val bytes = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    bytes.contains("meteordevelopment/") || bytes.contains("meteordevelopment.")
                }.map { it.name }.toList()
            check(bad.isEmpty()) { "Meteor dependency in standalone artifact: $bad" }
            check(zip.getEntry("fabric.mod.json") != null)
            val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).bufferedReader().use { it.readText() }
            check(!metadata.contains("\${") && !metadata.contains("meteor", ignoreCase = true))
            val internalReference = Regex("me/seedexplorer/[A-Za-z0-9_$/]+")
            val missing = entries.filter { it.endsWith(".class") }.flatMap { name ->
                val bytecode = zip.getInputStream(zip.getEntry(name)).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                internalReference.findAll(bytecode).map { it.value + ".class" }.toList()
            }.filter { it !in entries }.distinct()
            check(missing.isEmpty()) { "Missing bundled classes: $missing" }
            val mixins = zip.getInputStream(zip.getEntry("seed-explorer-standalone.mixins.json"))
                .bufferedReader().use { it.readText() }
            Regex("\"(Standalone[A-Za-z0-9]+Mixin)\"").findAll(mixins).forEach {
                check("me/seedexplorer/standalone/mixin/${it.groupValues[1]}.class" in entries)
            }
            val textureRoot = "assets/meteor-seed-explorer/textures/blocks/"
            val textures = zip.getInputStream(zip.getEntry(textureRoot + "index.txt"))
                .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            check(textures.isNotEmpty() && textures.all { textureRoot + it in entries })
            check(textures.none { it == "pale_oak_leaves.png" })
            val provider = zip.getInputStream(zip.getEntry("META-INF/services/me.seedexplorer.addon.render.TerrainOverlayRenderer"))
                .bufferedReader().use { it.readText().trim() }
            check(provider.replace('.', '/') + ".class" in entries)
        }
    }
}
tasks.check { dependsOn(verifyStandalone) }

val probeNames = listOf("Engine", "Renderer", "Loot", "Waypoint", "Minimap", "Ore", "Settings", "Ancillary", "Search", "SeedCracking")
probeNames.forEach { probe ->
    val verification = tasks.register<JavaExec>("standalone${probe}Probe") {
        group = "verification"
        dependsOn(tasks.jar)
        mainClass.set("me.seedexplorer.standalone.Standalone${probe}Probe")
        classpath = files(tasks.jar.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()
        maxHeapSize = "2G"
    }
    tasks.check { dependsOn(verification) }
}

// Research replay is deliberately separate from independent generated-world validation.
tasks.register<JavaExec>("standaloneSearchOracleProbe") {
    group = "verification"
    dependsOn(tasks.jar)
    mainClass.set("me.seedexplorer.standalone.StandaloneSearchProbe")
    classpath = files(tasks.jar.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()
    maxHeapSize = "2G"
    args("oracle")
}

val versionedLootIsolationProbe = tasks.register<JavaExec>("standaloneVersionedLootIsolationProbe") {
    group = "verification"
    dependsOn(tasks.jar)
    mainClass.set("me.seedexplorer.addon.tools.VersionedLootIsolationProbe")
    classpath = files(tasks.jar.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()
    maxHeapSize = "2G"
}
tasks.check { dependsOn(versionedLootIsolationProbe) }

tasks.register<JavaExec>("standalonePlacementHistoryProbe") {
    group = "verification"
    dependsOn(tasks.jar)
    mainClass.set("me.seedexplorer.standalone.StandalonePlacementHistoryProbe")
    classpath = files(tasks.jar.flatMap { it.archiveFile }) + configurations.runtimeClasspath.get()
    maxHeapSize = "2G"
    args(providers.gradleProperty("historySeed").getOrElse("1"),
        providers.gradleProperty("historyStructure").getOrElse("minecraft:desert_pyramid"),
        providers.gradleProperty("historyChunkX").getOrElse("-700"),
        providers.gradleProperty("historyChunkZ").getOrElse("-126"))
    providers.gradleProperty("historyRegion").orNull?.let { args(file(it).absolutePath) }
    providers.gradleProperty("historyTrace").orNull?.let {
        systemProperty("seedexplorer.historyTrace", file(it).absolutePath)
    }
}
