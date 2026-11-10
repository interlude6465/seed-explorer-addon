import java.util.zip.ZipFile

// Simple Java project that compiles the addon against the meteor-client jar and Minecraft dependencies
plugins {
    java
}

base {
    archivesName = "meteor-seed-explorer"
    group = "me.seedexplorer"
    version = "1.0.0"
}

repositories {
    mavenLocal()
    mavenCentral()
}

val generatedResourcesDir = layout.buildDirectory.dir("generated/seedExplorerResources")

val coreModel by sourceSets.creating
val standaloneFabric by sourceSets.creating

sourceSets {
    main {
        resources.srcDir(generatedResourcesDir)
        compileClasspath += coreModel.output
        runtimeClasspath += coreModel.output
    }
    named("standaloneFabric") {
        java.srcDir("src/main/java")
        java.include("me/seedexplorer/standalone/**/*.java")
        java.include("me/seedexplorer/addon/enchant/EnchantingTablePredictor.java")
        java.include("me/seedexplorer/addon/enchant/EnchantingTargetPlanner.java")
        java.include("me/seedexplorer/addon/worldgen/BiomeColorPalette.java")
        java.include("me/seedexplorer/addon/worldgen/OfflineWorldgenResources.java")
        java.include("me/seedexplorer/addon/worldgen/WorldgenEngine.java")
        java.include("me/seedexplorer/addon/worldgen/VanillaStructurePredictor.java")
        java.include("me/seedexplorer/addon/worldgen/GeneratedTerrainHeightmap.java")
        java.include("me/seedexplorer/addon/worldgen/HorizonTerrainSampler.java")
        java.include("me/seedexplorer/addon/live/LiveChunkSnapshot.java")
        java.include("me/seedexplorer/addon/ore/OrePatch.java")
        java.include("me/seedexplorer/addon/ore/OrePredictor.java")
        java.include("me/seedexplorer/addon/ore/OreType.java")
        java.include("me/seedexplorer/addon/preview/StructurePreviewModel.java")
        java.include("me/seedexplorer/addon/preview/StructurePreviewSimulator.java")
        java.include("me/seedexplorer/addon/waypoints/SeedWaypoint.java")
        java.include("me/seedexplorer/addon/render/BlockTextureAtlas.java")
        java.include("me/seedexplorer/addon/render/FreeCamera3D.java")
        java.include("me/seedexplorer/addon/render/GpuTextureResource.java")
        java.include("me/seedexplorer/addon/render/MapLayer.java")
        java.include("me/seedexplorer/addon/render/MapRenderContext.java")
        java.include("me/seedexplorer/addon/render/MapViewport.java")
        java.include("me/seedexplorer/addon/render/MeshUniformBuffer.java")
        java.include("me/seedexplorer/addon/render/RgbaColor.java")
        java.include("me/seedexplorer/addon/render/SeedExplorerRenderPipelines.java")
        java.include("me/seedexplorer/addon/render/StructureColors.java")
        java.include("me/seedexplorer/addon/render/StructureIcons.java")
        java.include("me/seedexplorer/addon/render/Terrain3DRenderer.java")
        java.include("me/seedexplorer/addon/render/TerrainDiskCache.java")
        java.include("me/seedexplorer/addon/render/TerrainFocusTarget.java")
        java.include("me/seedexplorer/addon/render/TerrainGenerationMode.java")
        java.include("me/seedexplorer/addon/render/TerrainLodPolicy.java")
        java.include("me/seedexplorer/addon/render/TerrainMaterialPolicy.java")
        java.include("me/seedexplorer/addon/render/TerrainOverlayRenderer.java")
        java.include("me/seedexplorer/addon/render/TerrainOverlayRenderers.java")
        java.include("me/seedexplorer/addon/render/TerrainPresentationFrontier.java")
        java.include("me/seedexplorer/addon/render/TerrainStreamingCursor.java")
        java.include("me/seedexplorer/addon/render/TerrainStreamStateStore.java")
        java.include("me/seedexplorer/addon/render/TerrainWorldDataSource.java")
        java.include("me/seedexplorer/addon/render/TextureRegion.java")
        java.include("me/seedexplorer/addon/loot/BundledLootTableLoader.java")
        java.include("me/seedexplorer/addon/loot/ChestLootOutput.java")
        java.include("me/seedexplorer/addon/loot/ItemLoot.java")
        java.include("me/seedexplorer/addon/loot/ItemLootRequest.java")
        java.include("me/seedexplorer/addon/loot/LootSearchParser.java")
        java.include("me/seedexplorer/addon/loot/LootSearchTerm.java")
        java.include("me/seedexplorer/addon/loot/LootPool.java")
        java.include("me/seedexplorer/addon/loot/LootRandom.java")
        java.include("me/seedexplorer/addon/loot/LootTableDef.java")
        java.include("me/seedexplorer/addon/loot/LootTableSimulator.java")
        java.include("me/seedexplorer/addon/loot/StandaloneStructureLootPredictor.java")
        java.include("me/seedexplorer/addon/loot/VanillaLootStructureSimulator.java")
        java.include("me/seedexplorer/addon/loot/VanillaLootTables.java")
        compileClasspath += coreModel.output
        runtimeClasspath += coreModel.output
    }
}

// Find all jars from the main project's classpath
val classpathJars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    fun cachedJar(group: String, artifact: String, version: String): File {
        val artifactDir = gradle.gradleUserHomeDir
            .resolve("caches/modules-2/files-2.1")
            .resolve(group)
            .resolve(artifact)
            .resolve(version)

        return artifactDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "$artifact-$version.jar" }
            ?: artifactDir.resolve("$artifact-$version.jar")
    }

    val meteorClientClasses = file("../build/classes/java/main")
    val meteorClientResources = file("../build/resources/main")
    val minecraftMergedJar = file("../.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-b374768f74/26.3/minecraft-merged-b374768f74-26.3.jar")
    val brigadierJar = cachedJar("com.mojang", "brigadier", "1.3.11")
    val orbitJar = cachedJar("meteordevelopment", "orbit", "0.2.4")
    val fabricLoaderJar = cachedJar("net.fabricmc", "fabric-loader", "0.19.5")
    val mixinJar = cachedJar("net.fabricmc", "sponge-mixin", "0.17.4+mixin.0.8.7")
    val jspecifyJar = cachedJar("org.jspecify", "jspecify", "1.0.0")
    val fastutilJar = cachedJar("it.unimi.dsi", "fastutil", "8.5.18")
    val gsonJar = cachedJar("com.google.code.gson", "gson", "2.14.0")
    val guavaJar = cachedJar("com.google.guava", "guava", "33.6.0-jre")
    val failureAccessJar = cachedJar("com.google.guava", "failureaccess", "1.0.3")
    val log4jApiJar = cachedJar("org.apache.logging.log4j", "log4j-api", "2.26.0")
    val log4jCoreJar = cachedJar("org.apache.logging.log4j", "log4j-core", "2.26.0")
    val slf4jJar = cachedJar("org.slf4j", "slf4j-api", "2.0.17")
    val datafixerupperJar = cachedJar("com.mojang", "datafixerupper", "10.0.21")
    val jomlJar = cachedJar("org.joml", "joml", "1.10.9")
    val icu4jJar = cachedJar("com.ibm.icu", "icu4j", "78.3")
    val authlibJar = cachedJar("com.mojang", "authlib", "10.0.77")
    val mojangLoggingJar = cachedJar("com.mojang", "logging", "1.7.12")
    val jtracyJar = cachedJar("com.mojang", "jtracy", "1.14.38")
    val lz4Jar = cachedJar("at.yawk.lz4", "lz4-java", "1.10.1")
    val nettyCommonJar = cachedJar("io.netty", "netty-common", "4.2.16.Final")
    val nettyBufferJar = cachedJar("io.netty", "netty-buffer", "4.2.16.Final")
    val nettyTransportJar = cachedJar("io.netty", "netty-transport", "4.2.16.Final")
    val nettyResolverJar = cachedJar("io.netty", "netty-resolver", "4.2.16.Final")
    val nettyCodecBaseJar = cachedJar("io.netty", "netty-codec-base", "4.2.16.Final")
    val nettyHandlerJar = cachedJar("io.netty", "netty-handler", "4.2.16.Final")
    val commonsLang3Jar = cachedJar("org.apache.commons", "commons-lang3", "3.20.0")
    val fabricApiBaseJar = cachedJar("net.fabricmc.fabric-api", "fabric-api-base", "2.0.6+fcdff87f5d")
    val fabricResourceLoaderJar = cachedJar("net.fabricmc.fabric-api", "fabric-resource-loader-v1", "3.0.4+ba0d6c0d5d")
    val fabricKeyMappingJar = cachedJar("net.fabricmc.fabric-api", "fabric-key-mapping-api-v1", "2.0.8+3434d6d95d")
    val fabricLifecycleJar = cachedJar("net.fabricmc.fabric-api", "fabric-lifecycle-events-v1", "4.1.9+ffef5f675d")
    val fabricRenderingJar = cachedJar("net.fabricmc.fabric-api", "fabric-rendering-v1", "27.2.0+eb9fe70b5d")
    // LWJGL: needed at compile time to stitch block textures into an atlas via STBImage
    // (BufferUtils/MemoryUtil/MemoryStack in core, STBImage in stb). Already on the
    // runtime classpath transitively via Minecraft; these make them compile-visible.
    val lwjglJar = cachedJar("org.lwjgl", "lwjgl", "3.4.3")
    val lwjglStbJar = cachedJar("org.lwjgl", "lwjgl-stb", "3.4.3")

    compileOnly(files(
        meteorClientClasses, meteorClientResources, minecraftMergedJar, brigadierJar, orbitJar, fabricLoaderJar,
        mixinJar, jspecifyJar, fastutilJar, gsonJar, guavaJar, failureAccessJar,
        log4jApiJar, log4jCoreJar, slf4jJar, datafixerupperJar, jomlJar,
        icu4jJar, authlibJar, mojangLoggingJar, jtracyJar, lz4Jar,
        nettyCommonJar, nettyBufferJar, nettyTransportJar, nettyResolverJar, nettyCodecBaseJar, nettyHandlerJar,
        commonsLang3Jar, fabricApiBaseJar, fabricResourceLoaderJar, lwjglJar, lwjglStbJar
    ))

    add(standaloneFabric.compileOnlyConfigurationName, files(
        minecraftMergedJar, brigadierJar, fabricLoaderJar, mixinJar, jspecifyJar, fastutilJar,
        gsonJar, guavaJar, failureAccessJar, log4jApiJar, log4jCoreJar, slf4jJar,
        datafixerupperJar, jomlJar, icu4jJar, authlibJar, mojangLoggingJar, jtracyJar,
        lz4Jar, nettyCommonJar, nettyBufferJar, nettyTransportJar, nettyResolverJar,
        nettyCodecBaseJar, nettyHandlerJar, commonsLang3Jar,
        fabricApiBaseJar, fabricResourceLoaderJar, fabricKeyMappingJar, fabricLifecycleJar,
        fabricRenderingJar,
        lwjglJar, lwjglStbJar
    ))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    val verifyCoreModelIsolation by registering {
        group = "verification"
        description = "Ensures the standalone core model has no Minecraft, Fabric, or Meteor dependencies."
        inputs.files(coreModel.allJava)

        doLast {
            val forbidden = listOf("meteordevelopment.", "net.minecraft.", "net.fabricmc.")
            val violations = coreModel.allJava.files.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    val dependency = forbidden.firstOrNull(line::contains) ?: return@mapIndexedNotNull null
                    "${source.relativeTo(projectDir)}:${index + 1}: $dependency"
                }
            }
            check(violations.isEmpty()) {
                "Standalone core model contains forbidden dependencies:\n${violations.joinToString("\n")}"
            }
        }
    }

    named(coreModel.classesTaskName) {
        dependsOn(verifyCoreModelIsolation)
    }

    compileJava {
        dependsOn(named(coreModel.classesTaskName))
    }

    register<Jar>("coreModelJar") {
        group = "build"
        description = "Builds the dependency-free Seed Explorer prediction model."
        archiveClassifier.set("core-model")
        from(coreModel.output)
    }

    val verifyStandaloneFabricIsolation by registering {
        group = "verification"
        description = "Ensures the standalone Fabric frontend has no Meteor dependencies."
        inputs.files(standaloneFabric.allJava)

        doLast {
            val violations = standaloneFabric.allJava.files.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (!line.contains("meteordevelopment.")) return@mapIndexedNotNull null
                    "${source.relativeTo(projectDir)}:${index + 1}"
                }
            }
            check(violations.isEmpty()) {
                "Standalone Fabric frontend contains Meteor dependencies:\n${violations.joinToString("\n")}"
            }

            val parentBuildClasses = projectDir.parentFile.resolve("build/classes").toPath()
                .toAbsolutePath().normalize()
            val parentBuildResources = projectDir.parentFile.resolve("build/resources").toPath()
                .toAbsolutePath().normalize()
            val forbiddenClasspath = standaloneFabric.compileClasspath.files.filter { entry ->
                val path = entry.toPath().toAbsolutePath().normalize()
                path.startsWith(parentBuildClasses)
                    || path.startsWith(parentBuildResources)
                    || entry.name.contains("meteor-client", ignoreCase = true)
                    || entry.path.contains("meteordevelopment", ignoreCase = true)
            }
            check(forbiddenClasspath.isEmpty()) {
                "Standalone compile classpath contains Meteor build outputs:\n" +
                    forbiddenClasspath.joinToString("\n")
            }
        }
    }

    named(standaloneFabric.classesTaskName) {
        dependsOn(verifyStandaloneFabricIsolation, named(coreModel.classesTaskName))
    }

    named<ProcessResources>(standaloneFabric.processResourcesTaskName) {
        val propertyMap = mapOf(
            "version" to project.version,
            "jdk_version" to "25",
            "minecraft_version" to "26.3",
            "loader_version" to "0.19.5"
        )
        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    val standaloneFabricJar by registering(Jar::class) {
        group = "build"
        description = "Builds the Meteor-independent Fabric client frontend."
        dependsOn(named(standaloneFabric.classesTaskName), "generateBlockTextureIndex")
        archiveBaseName.set("seed-explorer")
        archiveClassifier.set("standalone-fabric")
        from(coreModel.output)
        from(standaloneFabric.output)
        from("src/main/resources/data/meteor-seed-explorer/versioned_loot") {
            into("data/seed-explorer/versioned_loot")
            include("26.1.2/**", "26.2/**", "26.3/**", "provenance.tsv")
        }
        from("src/main/resources") {
            include("assets/meteor-seed-explorer/shaders/**")
            include("assets/meteor-seed-explorer/textures/blocks/**")
            include("assets/meteor-seed-explorer/textures/structures/**")
        }
        from(generatedResourcesDir) {
            include("assets/meteor-seed-explorer/textures/blocks/index.txt")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    val verifyStandaloneFabricJar by registering {
        group = "verification"
        description = "Rejects Meteor symbols and compatibility resources in the standalone jar."
        dependsOn(standaloneFabricJar)

        doLast {
            val artifact = standaloneFabricJar.get().archiveFile.get().asFile
            ZipFile(artifact).use { zip ->
                val forbiddenEntries = zip.entries().asSequence()
                    .map { it.name }
                    .filter {
                        it.startsWith("meteordevelopment/")
                            || it.startsWith("assets/meteor-client/")
                    }
                    .toList()
                check(forbiddenEntries.isEmpty()) {
                    "Standalone jar contains Meteor compatibility entries:\n${forbiddenEntries.joinToString("\n")}"
                }

                val forbiddenSymbols = listOf(
                    "meteordevelopment/",
                    "meteordevelopment.",
                    "me/seedexplorer/addon/seed/",
                    "me.seedexplorer.addon.seed.",
                    "me/seedexplorer/addon/gui/",
                    "me.seedexplorer.addon.gui.",
                    "me/seedexplorer/addon/modules/",
                    "me.seedexplorer.addon.modules.",
                    "meteor-client"
                )
                val forbiddenClasses = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .filter { entry ->
                        zip.getInputStream(entry).use { stream ->
                            val bytecode = String(stream.readBytes(), Charsets.ISO_8859_1)
                            forbiddenSymbols.any(bytecode::contains)
                        }
                    }
                    .map { it.name }
                    .toList()
                check(forbiddenClasses.isEmpty()) {
                    "Standalone jar contains Meteor bytecode references:\n${forbiddenClasses.joinToString("\n")}"
                }

                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .toSet()
                val internalReference = Regex("me/seedexplorer/[A-Za-z0-9_$/]+")
                val dottedInternalReference = Regex("me\\.seedexplorer\\.[A-Za-z0-9_$.]+")
                val missingInternalClasses = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .flatMap { entry ->
                        val bytecode = zip.getInputStream(entry).use { stream ->
                            String(stream.readBytes(), Charsets.ISO_8859_1)
                        }
                        val references = internalReference.findAll(bytecode)
                            .mapTo(mutableListOf()) { match -> match.value + ".class" }
                        dottedInternalReference.findAll(bytecode)
                            .mapTo(references) { match ->
                                match.value.replace('.', '/') + ".class"
                            }
                        references.asSequence()
                    }
                    .filter { it !in entries }
                    .distinct()
                    .sorted()
                    .toList()
                check(missingInternalClasses.isEmpty()) {
                    "Standalone jar has missing Seed Explorer class references:\n" +
                        missingInternalClasses.joinToString("\n")
                }

                val metadataEntry = zip.getEntry("fabric.mod.json")
                    ?: error("Standalone jar is missing fabric.mod.json")
                val metadata = zip.getInputStream(metadataEntry).bufferedReader().use { it.readText() }
                check(!metadata.contains("${'$'}{")) {
                    "Standalone fabric.mod.json contains unexpanded placeholders"
                }
                check(!metadata.contains("meteor", ignoreCase = true)) {
                    "Standalone fabric.mod.json contains Meteor metadata"
                }
                val entrypoint = "me.seedexplorer.standalone.SeedExplorerFabricClient"
                check(metadata.contains(entrypoint)) {
                    "Standalone fabric.mod.json does not declare $entrypoint"
                }
                check("me/seedexplorer/standalone/SeedExplorerFabricClient.class" in entries) {
                    "Standalone jar is missing its declared client entrypoint"
                }
            }
        }
    }
    standaloneFabricJar.configure {
        finalizedBy(verifyStandaloneFabricJar)
    }

    register<JavaExec>("standaloneEngineProbe") {
        group = "verification"
        description = "Loads biome and structure prediction from the packaged standalone artifact."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneEngineProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    register<JavaExec>("standaloneRendererProbe") {
        group = "verification"
        description = "Links and initializes the packaged 3D renderer without Meteor."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneRendererProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    register<JavaExec>("standaloneOreProbe") {
        group = "verification"
        description = "Runs deterministic ore prediction from the packaged standalone artifact."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneOreProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    register<JavaExec>("standaloneLootProbe") {
        group = "verification"
        description = "Replays exact 26.3 structure loot from the packaged standalone artifact."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneLootProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
    }

    register<JavaExec>("standaloneMinimapProbe") {
        group = "verification"
        description = "Checks the standalone minimap's fixed frame-time and memory bounds."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneMinimapProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    register<JavaExec>("standaloneWaypointProbe") {
        group = "verification"
        description = "Verifies standalone waypoint persistence and config isolation."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneWaypointProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    register<JavaExec>("standaloneSettingsProbe") {
        group = "verification"
        description = "Validates the complete native settings contract and reset behavior."
        dependsOn(standaloneFabricJar)
        mainClass.set("me.seedexplorer.standalone.StandaloneSettingsProbe")
        classpath = files(standaloneFabricJar.flatMap { it.archiveFile })
            .plus(standaloneFabric.compileClasspath)
    }

    val generateBlockTextureIndex by registering {
        val textureDir = layout.projectDirectory.dir("src/main/resources/assets/meteor-seed-explorer/textures/blocks")
        val indexFile = generatedResourcesDir.map {
            it.file("assets/meteor-seed-explorer/textures/blocks/index.txt")
        }
        inputs.dir(textureDir)
        outputs.file(indexFile)

        doLast {
            val files = textureDir.asFile.listFiles { file -> file.isFile && file.name.endsWith(".png") }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
            val out = indexFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText(files.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    processResources {
        dependsOn(generateBlockTextureIndex)
        from("src/main/resources/data/meteor-seed-explorer/versioned_loot") {
            into("data/seed-explorer/versioned_loot")
        }
        val propertyMap = mapOf(
            "version" to project.version,
            "jdk_version" to "25",
            "minecraft_version" to "26.3",
            "loader_version" to "0.19.5"
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        from(coreModel.output)
        from("src/main/resources")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }

    register<JavaExec>("offlineLootSimulation") {
        group = "verification"
        description = "Runs the vanilla-backed desert pyramid chest/loot simulator."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LootSimulationReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(providers.gradleProperty("seed").orElse("4717879387438598985").get())
        if (providers.gradleProperty("chunkX").isPresent && providers.gradleProperty("chunkZ").isPresent) {
            args(
                providers.gradleProperty("chunkX").get(),
                providers.gradleProperty("chunkZ").get()
            )
        }
    }

    register<JavaExec>("simulateStronghold") {
        group = "verification"
        description = "Simulates a stronghold and prints chests. Omits chunk args for auto-discover. Pass -Pmode=mineshaft|shipwreck for other structures."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.SimulateStronghold")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        val seed = providers.gradleProperty("seed").orElse("0").get()
        val chunkX = providers.gradleProperty("chunkX").orElse("").get()
        val chunkZ = providers.gradleProperty("chunkZ").orElse("").get()
        val mode = providers.gradleProperty("mode").orElse("").get()
        if (chunkX.isNotEmpty() && chunkZ.isNotEmpty()) {
            args(seed, chunkX, chunkZ, mode)
        } else {
            args(seed)
        }
        systemProperty("seedexplorer.proxyDebug",
            providers.gradleProperty("proxyDebug").orElse("false").get())
    }

    register<JavaExec>("fortressPredictionProbe") {
        group = "verification"
        description = "Runs an offline Nether Fortress chest/loot prediction probe."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.FortressPredictionProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("-1124201383388859604").get(),
            providers.gradleProperty("centerChunkX").orElse("0").get(),
            providers.gradleProperty("centerChunkZ").orElse("0").get(),
            providers.gradleProperty("radiusChunks").orElse("256").get(),
            providers.gradleProperty("limit").orElse("8").get(),
            providers.gradleProperty("targetX").orElse("207").get(),
            providers.gradleProperty("targetY").orElse("57").get(),
            providers.gradleProperty("targetZ").orElse("307").get(),
            providers.gradleProperty("directChunkX").orElse("").get(),
            providers.gradleProperty("directChunkZ").orElse("").get(),
            providers.gradleProperty("bruteForceIndices").orElse("false").get()
        )
    }

    register<JavaExec>("strongholdDecorationIndexSearch") {
        group = "verification"
        description = "Searches the stronghold structure-decoration RNG index against a Paper chest seed."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.StrongholdDecorationIndexSearch")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("0").get(),
            providers.gradleProperty("chunkX").orElse("125").get(),
            providers.gradleProperty("chunkZ").orElse("57").get(),
            providers.gradleProperty("chestX").orElse("1964").get(),
            providers.gradleProperty("chestY").orElse("28").get(),
            providers.gradleProperty("chestZ").orElse("894").get(),
            providers.gradleProperty("lootSeed").orElse("-393332197303699418").get(),
            providers.gradleProperty("minIndex").orElse("0").get(),
            providers.gradleProperty("maxIndex").orElse("64").get()
        )
    }

    register<JavaExec>("strongholdRandomSequenceSearch") {
        group = "verification"
        description = "Locates a Paper stronghold loot seed in candidate per-chunk RNG streams."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.StrongholdRandomSequenceSearch")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("0").get(),
            providers.gradleProperty("placementChunkX").orElse("122").get(),
            providers.gradleProperty("placementChunkZ").orElse("55").get(),
            providers.gradleProperty("minStep").orElse("0").get(),
            providers.gradleProperty("maxStep").orElse("10").get(),
            providers.gradleProperty("lootSeed").orElse("-393332197303699418").get(),
            providers.gradleProperty("maxIndex").orElse("128").get(),
            providers.gradleProperty("maxBitCalls").orElse("20000").get()
        )
    }

    register<JavaExec>("strongholdSavedStartSimulation") {
        group = "verification"
        description = "Replay one chunk from a Paper-saved structure start"
        dependsOn(classes)
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        mainClass.set("me.seedexplorer.addon.tools.StrongholdSavedStartSimulation")
        args(
            providers.gradleProperty("regionDir").getOrElse(""),
            providers.gradleProperty("startChunkX").getOrElse("125"),
            providers.gradleProperty("startChunkZ").getOrElse("57"),
            providers.gradleProperty("placementChunkX").getOrElse("122"),
            providers.gradleProperty("placementChunkZ").getOrElse("55"),
            providers.gradleProperty("seed").getOrElse("0"),
            providers.gradleProperty("index").getOrElse("19"),
            providers.gradleProperty("expectedSeed").getOrElse("-393332197303699418"),
            providers.gradleProperty("structureId").getOrElse("minecraft:stronghold"),
            providers.gradleProperty("dimension").getOrElse("minecraft:overworld")
        )
    }

    register<JavaExec>("crossVersionTest") {
        group = "verification"
        description = "Loads all version profiles and reports loot table parse failures per version."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.CrossVersionTestReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("versionedLootProfileAudit") {
        group = "verification"
        description = "Audits modern version loot profiles through table parsing, container simulation, and deterministic item prediction."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.VersionedLootProfileAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("validateJungle") {
        group = "verification"
        description = "Validates jungle temple predictions against captured oracle data."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.JungleTempleValidate")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        val seed = providers.gradleProperty("seed").orElse("-2843430517209339837").get()
        val cx = providers.gradleProperty("chunkX").orElse("-241").get()
        val cz = providers.gradleProperty("chunkZ").orElse("16").get()
        val index = providers.gradleProperty("index").orElse("4").get()
        val oracle = providers.gradleProperty("oracle").orElse("validation/oracles/lootprobe-jungle-temple-seed-2843430517209339837.json").get()
        args(seed, cx, cz, index, oracle)
    }

    register<JavaExec>("dungeonStepTest") {
        group = "verification"
        description = "Tests dungeon feature simulation for a given seed/chunk/step."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.DungeonStepTest")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        val seed = providers.gradleProperty("seed").orElse("4717879387438598985").get()
        val cx = providers.gradleProperty("chunkX").orElse("-21").get()
        val cz = providers.gradleProperty("chunkZ").orElse("-370").get()
        val step = providers.gradleProperty("step").orElse("3").get()
        args(seed, cx, cz, step)
    }

    register<JavaExec>("dungeonSavedOracleReport") {
        group = "verification"
        description = "Scans saved Anvil chunks for dungeon spawners and simple-dungeon loot chests."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.DungeonSavedOracleReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("regionDir").getOrElse(""),
            providers.gradleProperty("limit").getOrElse("64")
        )
    }

    register<JavaExec>("dungeonPredictorProbe") {
        group = "verification"
        description = "Validates the cached parallel dungeon scanner against saved-world oracle coordinates and loot seeds."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.DungeonPredictorProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").getOrElse("-5330673357612662400"),
            providers.gradleProperty("chunkX").getOrElse("-38"),
            providers.gradleProperty("chunkZ").getOrElse("-18")
        )
    }

    register<JavaExec>("lootTableAccuracyTest") {
        group = "verification"
        description = "Tests loot table parsing accuracy vs oracle data."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LootTableAccuracyTest")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("oracle").orElse("").get()
        )
    }

    register<JavaExec>("terrainInteractionReport") {
        group = "verification"
        description = "Reports overlapping structure starts and their terrain adaptation."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.TerrainInteractionReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("123456789").get(),
            providers.gradleProperty("pyramidChunkX").orElse("-157").get(),
            providers.gradleProperty("pyramidChunkZ").orElse("-125").get(),
            providers.gradleProperty("trialChunkX").orElse("-154").get(),
            providers.gradleProperty("trialChunkZ").orElse("-125").get()
        )
    }

    register<JavaExec>("placedFeatureInventoryReport") {
        group = "verification"
        description = "Lists vanilla placed features selected for a chunk rectangle without executing them."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.PlacedFeatureInventoryReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("123456789").get(),
            providers.gradleProperty("minChunkX").orElse("-158").get(),
            providers.gradleProperty("minChunkZ").orElse("-126").get(),
            providers.gradleProperty("maxChunkX").orElse("-155").get(),
            providers.gradleProperty("maxChunkZ").orElse("-123").get()
        )
    }

    register<JavaExec>("naturalDecorationProbe") {
        group = "verification"
        description = "Checks deterministic vanilla natural-feature placement for the 3D map."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.NaturalDecorationProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("123456789").get(),
            providers.gradleProperty("dimension").orElse("0").get(),
            providers.gradleProperty("chunkX").orElse("0").get(),
            providers.gradleProperty("chunkZ").orElse("0").get()
        )
    }

    register<JavaExec>("predictionRegressionSuite") {
        group = "verification"
        description = "Runs all accepted strict loot oracles and the pinned vanilla carver terrain regression."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.PredictionRegressionSuite")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(projectDir.absolutePath)
    }



    register<JavaExec>("surfaceLakePyramidSearch") {
        group = "verification"
        description = "Searches seeds for a surface lava lake that changes a desert-pyramid footprint."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.SurfaceLakePyramidSearchReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("firstSeed").orElse("1").get(),
            providers.gradleProperty("seedCount").orElse("500").get()
        )
    }

    register<JavaExec>("savedChunkNbtReport") {
        group = "verification"
        description = "Reads structure metadata from an oracle Anvil chunk without modifying it."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.SavedChunkNbtReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("regionDir").getOrElse(""),
            providers.gradleProperty("chunkX").getOrElse("0"),
            providers.gradleProperty("chunkZ").getOrElse("0"),
            providers.gradleProperty("structureId").getOrElse("minecraft:stronghold"),
            providers.gradleProperty("dimension").getOrElse("minecraft:overworld")
        )
        if (listOf("minX", "minZ", "maxX", "maxZ").all {
                providers.gradleProperty(it).isPresent
            }) {
            args(
                providers.gradleProperty("minX").get(),
                providers.gradleProperty("minZ").get(),
                providers.gradleProperty("maxX").get(),
                providers.gradleProperty("maxZ").get()
            )
            if (providers.gradleProperty("comparisonSeed").isPresent) {
                args(providers.gradleProperty("comparisonSeed").get())
            }
        }
    }

    register<JavaExec>("terrainBlockParityReport") {
        group = "verification"
        description = "Compares a structure-disabled Paper chunk's blocks with offline generated terrain."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.TerrainBlockParityReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("regionDir").getOrElse(""),
            providers.gradleProperty("chunkX").getOrElse("0"),
            providers.gradleProperty("chunkZ").getOrElse("0"),
            providers.gradleProperty("seed").getOrElse("0"),
            providers.gradleProperty("minX").getOrElse("0"),
            providers.gradleProperty("minY").getOrElse("0"),
            providers.gradleProperty("minZ").getOrElse("0"),
            providers.gradleProperty("maxX").getOrElse("0"),
            providers.gradleProperty("maxY").getOrElse("0"),
            providers.gradleProperty("maxZ").getOrElse("0")
        )
    }

    register<JavaExec>("validateLootProbeOracle") {
        group = "verification"
        description = "Diffs a desert-pyramid prediction against a LootProbe real-server JSON capture."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LootProbeOracleValidator")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("4717879387438598985").get(),
            providers.gradleProperty("chunkX").orElse("0").get(),
            providers.gradleProperty("chunkZ").orElse("0").get(),
            providers.gradleProperty("oracle").orElse("").get()
        )
    }

    register<JavaExec>("performanceBenchmark") {
        group = "verification"
        description = "Runs performance benchmarks for each prediction component and prints a summary table."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.PerformanceBenchmark")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("ancientCityValidation") {
        group = "verification"
        description = "Locates ancient city candidates for a seed and attempts offline simulation (jigsaw limitation documented)."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.AncientCityValidation")
        classpath = sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(providers.gradleProperty("seed").orElse("-3791862030646821359").get())
    }

    register<JavaExec>("bastionOracleResearchReport") {
        group = "verification"
        description = "Research-only comparison against the captured Nether bastion oracle; does not validate product support."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BastionOracleResearchReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(projectDir.absolutePath)
    }

    register<JavaExec>("mineshaftOracleAudit") {
        group = "verification"
        description = "Audits the existing mineshaft oracle for actual abandoned-mineshaft minecart chest evidence."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.MineshaftOracleAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(projectDir.absolutePath)
    }

    register<JavaExec>("freshSeedPredictionSmoke") {
        group = "verification"
        description = "Runs non-oracle fresh-seed smoke checks across map prediction and validated loot simulation paths."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.FreshSeedPredictionSmoke")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get().split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    register<JavaExec>("mineshaftMinecartProbe") {
        group = "verification"
        description = "Probes whether the offline simulator captures mineshaft minecart chests."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.MineshaftMinecartProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        systemProperty("seedexplorer.proxyDebug", "true")
        if (providers.gradleProperty("probeLog").isPresent) {
            systemProperty("seedexplorer.probeLog", providers.gradleProperty("probeLog").get())
        }
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        if (providers.gradleProperty("seed").isPresent) {
            args(providers.gradleProperty("seed").get())
        }
    }

    register<JavaExec>("realLootTableProbe") {
        group = "verification"
        description = "A/B tests the real vanilla LootTable.getRandomItems against the hand-rolled simulator on the desert oracle."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.RealLootTableProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        if (providers.gradleProperty("probeLog").isPresent) {
            systemProperty("seedexplorer.probeLog", providers.gradleProperty("probeLog").get())
        }
        val probeArgs = mutableListOf<String>()
        if (providers.gradleProperty("table").isPresent) probeArgs.add(providers.gradleProperty("table").get())
        if (providers.gradleProperty("seed").isPresent) probeArgs.add(providers.gradleProperty("seed").get())
        args(probeArgs)
    }

    register<JavaExec>("enchantHelperProbe") {
        group = "verification"
        description = "De-risk: can the real EnchantmentHelper.enchantItem run offline? -Pitem=... -Plevel=30 -Pseed=..."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.EnchantHelperProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        val probeArgs = mutableListOf<String>()
        if (providers.gradleProperty("item").isPresent) probeArgs.add(providers.gradleProperty("item").get())
        if (providers.gradleProperty("level").isPresent) probeArgs.add(providers.gradleProperty("level").get())
        if (providers.gradleProperty("seed").isPresent) probeArgs.add(providers.gradleProperty("seed").get())
        args(probeArgs)
    }

    register<JavaExec>("enchantingTablePredictionProbe") {
        group = "verification"
        description = "Checks deterministic enchanting-table offers through the vanilla helper path."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.EnchantingTablePredictionProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        if (providers.gradleProperty("seed").isPresent) {
            args(providers.gradleProperty("seed").get())
        }
    }

    register<JavaExec>("enchantingSeedCrackerProbe") {
        group = "verification"
        description = "Checks ClientCommands-compatible XP-seed candidate filtering offline."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.EnchantingSeedCrackerProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        if (providers.gradleProperty("seed").isPresent) {
            args(providers.gradleProperty("seed").get())
        }
    }

    register<JavaExec>("lootSearchQueryProbe") {
        group = "verification"
        description = "Checks natural item, enchanted-book, and quantity search parsing."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LootSearchQueryProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        standardOutput = System.out
        errorOutput = System.err
    }

    register<JavaExec>("structureLootProbe") {
        group = "verification"
        description = "Probes simulate()+predictForStructure() loot for any structure type. -Ptype=TRIAL_CHAMBER -Pseed=... -Plimit=4"
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.StructureLootProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        val probeArgs = mutableListOf<String>()
        if (providers.gradleProperty("type").isPresent) probeArgs.add(providers.gradleProperty("type").get())
        if (providers.gradleProperty("seed").isPresent) probeArgs.add(providers.gradleProperty("seed").get())
        if (providers.gradleProperty("limit").isPresent) probeArgs.add(providers.gradleProperty("limit").get())
        if (providers.gradleProperty("radius").isPresent) probeArgs.add(providers.gradleProperty("radius").get())
        if (providers.gradleProperty("chunk").isPresent) probeArgs.add("chunk=" + providers.gradleProperty("chunk").get())
        args(probeArgs)
        if (providers.gradleProperty("probeLog").isPresent) {
            systemProperty("seedexplorer.probeLog", providers.gradleProperty("probeLog").get())
        }
    }

    register<JavaExec>("structureLootIndexSearch") {
        group = "verification"
        description = "Searches structure decoration indices against observed loot counts."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.StructureLootIndexSearch")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        val probeArgs = mutableListOf<String>()
        listOf("type", "seed", "dim", "chunkX", "chunkZ", "chestX", "chestY", "chestZ", "expected", "maxIndex").forEach {
            if (providers.gradleProperty(it).isPresent) probeArgs.add(providers.gradleProperty(it).get())
        }
        args(probeArgs)
    }

    register<JavaExec>("locateAndIndexSweep") {
        group = "verification"
        description = "Locates nearest structure of a given type then sweeps decoration indices against observed chest loot."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LocateAndIndexSweep")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        jvmArgs("--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED")
        val probeArgs = mutableListOf<String>()
        listOf("type", "seed", "dim", "chestX", "chestY", "chestZ", "expected", "maxIndex", "searchRadius").forEach {
            if (providers.gradleProperty(it).isPresent) probeArgs.add(providers.gradleProperty(it).get())
        }
        args(probeArgs)
    }

    register("dumpProbeClasspath") {
        group = "verification"
        description = "Writes the probe runtime classpath to build/probe-classpath.txt for direct java invocation."
        dependsOn(classes)
        doLast {
            val cp = (sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath)
                .files.joinToString(File.pathSeparator) { it.absolutePath }
            val out = layout.buildDirectory.file("probe-classpath.txt").get().asFile
            out.writeText(cp)
            println("wrote classpath (${cp.length} chars) to ${out.absolutePath}")
        }
    }

    register<JavaExec>("liveChunkSnapshotRoundTrip") {
        group = "verification"
        description = "Verifies observed chunk snapshot compression and restore behavior."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.LiveChunkSnapshotRoundTrip")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("terrainRendererPolicyProbe") {
        group = "verification"
        description = "Checks terrain LOD, material filtering, and persistent cache policy."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.render.TerrainRendererPolicyProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("terrainGenerationStrategyProbe") {
        group = "verification"
        description = "Compares renderer-fast and diagnostic vanilla terrain generation for exact parity."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.TerrainGenerationStrategyProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("horizonTerrainSamplerProbe") {
        group = "verification"
        description = "Benchmarks experimental Horizon-style density sampling against base columns."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.HorizonTerrainSamplerProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("42").get(),
            providers.gradleProperty("chunkX").orElse("-2").get(),
            providers.gradleProperty("chunkZ").orElse("-2").get(),
            providers.gradleProperty("chunksPerSide").orElse("4").get(),
            providers.gradleProperty("step").orElse("8").get()
        )
    }

    register<JavaExec>("terrainThroughputProbe") {
        group = "verification"
        description = "Measures sustained parallel exact and interactive terrain throughput."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.TerrainThroughputProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("42").get(),
            providers.gradleProperty("chunks").orElse("96").get(),
            providers.gradleProperty("threads").orElse("16").get()
        )
    }

    register<JavaExec>("abandonedCampPolicyProbe") {
        group = "verification"
        description = "Checks the 26.3 abandoned-camp placement, variants, and bundled loot tables."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.AbandonedCampPolicyProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("predictionVersionPolicyProbe") {
        group = "verification"
        description = "Checks selected-profile introduction gates without invoking worldgen."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.PredictionVersionPolicyProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("naturalFeatureSearchProbe") {
        group = "verification"
        description = "Searches one seed region for a circular ridge around a river-biome center."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.NaturalFeatureSearchProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("0").get(),
            providers.gradleProperty("originX").orElse("0").get(),
            providers.gradleProperty("originZ").orElse("0").get(),
            providers.gradleProperty("radiusBlocks").orElse("512").get(),
            providers.gradleProperty("lastSeed").orElse("").get(),
            providers.gradleProperty("requireDeepCave").orElse("false").get(),
            providers.gradleProperty("minimumCaveDepth").orElse("80").get()
        )
    }

    register<JavaExec>("endCityShipPredictionSmoke") {
        group = "verification"
        description = "Checks End City predictions distinguish ship and no-ship variants."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.EndCityShipPredictionSmoke")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("exactStructureQueryParityProbe") {
        group = "verification"
        description = "Checks exact-key queries preserve vanilla mixed-set weighted selection."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.ExactStructureQueryParityProbe")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("structurePreviewSmoke") {
        group = "verification"
        description = "Checks vanilla structure preview extraction returns non-empty block captures."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.StructurePreviewSmoke")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    }

    register<JavaExec>("broadStructureAudit") {
        group = "verification"
        description = "Audits fresh-seed structure starts, bastion variants, end-city ships, previews and validated loot paths."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BroadStructureAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(providers.gradleProperty("phase").orElse("all").get())
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get())
        }
    }

    register<JavaExec>("broadStructureStartsAudit") {
        group = "verification"
        description = "Audits fresh-seed predicted structure starts."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BroadStructureAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args("starts")
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get())
        }
    }

    register<JavaExec>("broadStructureVariantsAudit") {
        group = "verification"
        description = "Audits bastion variants and End City ship/no-ship predictions."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BroadStructureAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args("variants")
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get())
        }
    }

    register<JavaExec>("broadStructureLootAudit") {
        group = "verification"
        description = "Audits deterministic container locations, loot seeds and loot item simulation for validated structures."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BroadStructureAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args("loot")
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get())
        }
    }

    register<JavaExec>("broadStructurePreviewAudit") {
        group = "verification"
        description = "Audits preview models contain validated structure chest positions."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.BroadStructureAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args("preview")
        if (providers.gradleProperty("seeds").isPresent) {
            args(providers.gradleProperty("seeds").get())
        }
    }

    register<JavaExec>("oracleCorpusAudit") {
        group = "verification"
        description = "Audits independent Paper oracle captures for complete containers, loot seeds, and enchantment data."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.OracleCorpusAudit")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(projectDir.resolve("validation").absolutePath)
    }

    register<JavaExec>("compareOracleCase") {
        group = "verification"
        description = "Compares one Paper oracle JSON with offline structure, container, seed, and loot prediction."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.OracleCaseComparator")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("oracle").orElse("").get(),
            providers.gradleProperty("structure").orElse("").get()
        )
        if (providers.gradleProperty("decorationIndex").isPresent) {
            args(providers.gradleProperty("decorationIndex").get())
        }
    }

    register<JavaExec>("compareOracleMatrix") {
        group = "verification"
        description = "Compares every configured fresh Paper oracle case in one JVM."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.OracleMatrixComparator")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("cases").orElse("oracle-cases.example.json").get(),
            providers.gradleProperty("oracleRoot").orElse("validation/generated-oracles").get()
        )
        if (providers.gradleProperty("mode").isPresent) {
            args(providers.gradleProperty("mode").get())
        } else if (providers.gradleProperty("maxCases").isPresent) {
            args(providers.gradleProperty("maxCases").get())
        }
    }

    register<JavaExec>("mineshaftSavedOracleReport") {
        group = "verification"
        description = "Scans saved entity regions for abandoned-mineshaft chest-minecart oracle data."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.MineshaftSavedOracleReport")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("entityDir").orElse("").get(),
            providers.gradleProperty("limit").orElse("64").get()
        )
    }

    register<JavaExec>("mineshaftSavedOracleCompare") {
        group = "verification"
        description = "Compares mineshaft minecart coordinates, loot seeds, and contents with a saved-world oracle."
        dependsOn(classes)
        mainClass.set("me.seedexplorer.addon.tools.MineshaftSavedOracleComparator")
        classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
        args(
            providers.gradleProperty("seed").orElse("0").get(),
            providers.gradleProperty("entityDir").orElse("").get(),
            providers.gradleProperty("limit").orElse("12").get()
        )
        if (providers.gradleProperty("targetX").isPresent
            && providers.gradleProperty("targetZ").isPresent) {
            args(
                providers.gradleProperty("targetX").get(),
                providers.gradleProperty("targetZ").get()
            )
        }
    }
}
