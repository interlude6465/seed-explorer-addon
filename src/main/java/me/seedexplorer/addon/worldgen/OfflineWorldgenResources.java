package me.seedexplorer.addon.worldgen;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies the full vanilla data registries and structure templates to the
 * headless prediction harness. The lightweight HolderLookup used by data
 * generation is not itself a RegistryAccess, so jigsaw generation otherwise
 * cannot resolve template pools.
 */
final class OfflineWorldgenResources {
    private static final Object LOCK = new Object();
    private static volatile RegistryAccess registryAccess;
    private static volatile StructureTemplateManager structureTemplateManager;
    private static volatile MultiPackResourceManager resourceManager;
    private static volatile LevelStorageSource.LevelStorageAccess levelStorageAccess;

    private OfflineWorldgenResources() {
    }

    static RegistryAccess registryAccess() {
        RegistryAccess current = registryAccess;
        if (current != null) return current;

        synchronized (LOCK) {
            if (registryAccess == null) registryAccess = createRegistryAccess();
            return registryAccess;
        }
    }

    static StructureTemplateManager structureTemplateManager() {
        StructureTemplateManager current = structureTemplateManager;
        if (current != null) return current;

        synchronized (LOCK) {
            if (structureTemplateManager == null) {
                try {
                    Path storageRoot = Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "seed-explorer-worldgen-" + ProcessHandle.current().pid());
                    LevelStorageSource storage = LevelStorageSource.createDefault(storageRoot);
                    levelStorageAccess = storage.createAccess("templates");
                    structureTemplateManager = new StructureTemplateManager(
                        resourceManager(),
                        levelStorageAccess,
                        DataFixers.getDataFixer(),
                        registryAccess().lookupOrThrow(Registries.BLOCK));
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to initialize vanilla structure templates", exception);
                }
            }
            return structureTemplateManager;
        }
    }

    private static RegistryAccess createRegistryAccess() {
        // Bind block tags BEFORE freezing registries via fromRegistryOfRegistries.
        if (BuiltInRegistries.BLOCK instanceof net.minecraft.core.MappedRegistry<?> m) {
            try {
                TagLoader.loadTagsForRegistry(resourceManager(), m);
            } catch (IllegalStateException ignored) {
                // Registry already frozen by Bootstrap.bootStrap()
            }
        }

        HolderLookup.Provider provider = WorldgenEngine.vanillaLookup();
        RegistryAccess builtIns = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        List<Registry<?>> registries = new ArrayList<>();

        provider.listRegistries().forEach(lookupEntry -> {
            Registry<?> builtIn = builtInRegistry(builtIns, lookupEntry.key());
            registries.add(builtIn != null ? builtIn : copyRegistry(lookupEntry));
        });
        RegistryAccess result =
            new RegistryAccess.ImmutableRegistryAccess(registries);

        TagLoader.loadTagsForExistingRegistries(resourceManager(), result)
            .forEach(Registry.PendingTags::apply);

        RegistryAccess.Frozen frozen = result.freeze();

        // Bind item data components. Bootstrap.bootStrap() populates the item registry
        // but does NOT run the data-component initializers, so every item Holder.Reference
        // has null components. Vanilla structure code that constructs an ItemStack for a
        // data marker (e.g. EndCityPieces placing the ship's elytra/brewing items) then
        // throws "Components not bound yet". Running the initializers here binds them,
        // exactly as the normal registry-load path does.
        try {
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(frozen)
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        } catch (Throwable t) {
            // Already bound (idempotent guard) or unavailable — safe to continue.
        }

        return frozen;
    }

    private static MultiPackResourceManager resourceManager() {
        MultiPackResourceManager current = resourceManager;
        if (current != null) return current;

        synchronized (LOCK) {
            if (resourceManager == null) {
                PackLocationInfo location = new PackLocationInfo(
                    "seed-explorer-vanilla",
                    Component.literal("Seed Explorer vanilla resources"),
                    PackSource.BUILT_IN,
                    Optional.empty());
                VanillaPackResources vanillaPack = new VanillaPackResourcesBuilder()
                    .pushJarResources()
                    .exposeNamespace("minecraft")
                    .build(location);
                List<PackResources> packs = new ArrayList<>(List.of(vanillaPack));

                // The auto-detection in pushJarResources may fail in an offline
                // Gradle-exec context.  Fall back to scanning the classpath for
                // the merged jar and exposing it as an explicit path pack.
                Path mergedJar = findMergedJar();
                if (mergedJar != null) {
                    try {
                        FileSystem jarFs = FileSystems.newFileSystem(mergedJar, (ClassLoader) null);
                        PackLocationInfo jarLocation = new PackLocationInfo(
                            "seed-explorer-merged-jar",
                            Component.literal("Merged jar resources"),
                            PackSource.BUILT_IN,
                            Optional.empty());
                        packs.add(new PathPackResources(jarLocation, jarFs.getPath("/")));
                    } catch (IOException ignored) {
                    }
                }

                resourceManager = new MultiPackResourceManager(
                    PackType.SERVER_DATA, packs);
            }
            return resourceManager;
        }
    }

    /** Scans the system classpath for the Minecraft merged jar. */
    private static Path findMergedJar() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isEmpty()) return null;
        String separator = System.getProperty("path.separator");
        // First pass: look for the exact minecraft-merged marker in the filename.
        for (String entry : cp.split(separator)) {
            if (entry.contains("minecraft-merged") && entry.endsWith(".jar")) {
                Path path = Path.of(entry);
                if (Files.exists(path)) return path;
            }
        }
        // Second pass: scan all remaining entries containing "minecraft" and
        // verify they really contain the structure directory before returning.
        for (String entry : cp.split(separator)) {
            if (!entry.endsWith(".jar")) continue;
            if (!entry.contains("minecraft")) continue;
            Path path = Path.of(entry);
            if (!Files.exists(path)) continue;
            try (FileSystem probe = FileSystems.newFileSystem(path, (ClassLoader) null)) {
                if (Files.isDirectory(probe.getPath("data/minecraft/structure"))) {
                    return path;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry<?> builtInRegistry(RegistryAccess builtIns,
                                                net.minecraft.resources.ResourceKey key) {
        return (Registry<?>) builtIns.lookup(key).orElse(null);
    }

    private static Registry<?> copyRegistry(HolderLookup.RegistryLookup<?> lookup) {
        return copyTypedRegistry(lookup);
    }

    private static <T> Registry<T> copyTypedRegistry(HolderLookup.RegistryLookup<T> source) {
        MappedRegistry<T> target = new MappedRegistry<>(registryKey(source), Lifecycle.stable());
        source.listElements().forEach(holder ->
            target.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));

        try {
            Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
            source.listTags().forEach(named -> {
                List<Holder<T>> members = named.stream()
                    .map(holder -> holder.unwrapKey()
                        .<Holder<T>>map(target::getOrThrow)
                        .orElseGet(() -> Holder.direct(holder.value())))
                    .toList();
                tags.put(named.key(), members);
            });
            target.bindTags(tags);
        } catch (UnsupportedOperationException ignored) {
            // VanillaRegistries' datagen lookups intentionally expose no tag view.
            // Built-in registries retain their real tags; dynamic worldgen values
            // used here carry their direct holder references in the bootstrapped data.
            target.bindAllTagsToEmpty();
        }
        return target.freeze();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ResourceKey<? extends Registry<T>> registryKey(
        HolderLookup.RegistryLookup<T> source) {
        return (ResourceKey) source.key();
    }
}
