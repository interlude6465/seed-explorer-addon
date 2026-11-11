package me.seedexplorer.seedcracking;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft Java versions covered by the dependency-free baseline.
 *
 * <p>The audited range is 26.1 through 26.3. Patch releases such as 26.1.1 and
 * 26.1.2 map to the same minor version ({@link #V26_1}); the 26.1.2 reference
 * jar is used for that profile. The placement, pillar, slime, stronghold, and
 * random-source bytecode checked for the baseline is the same in 26.1.2, 26.2,
 * and 26.3.</p>
 *
 * <p>Parsing is intentionally strict about the major/minor pair. A future
 * 26.4 release is <em>not</em> silently treated as 26.3: add and audit a new
 * constant instead. This prevents a newer world from being cracked with stale
 * structure-set data.</p>
 */
public enum MinecraftVersion {
    V26_1(26, 1),
    V26_2(26, 2),
    V26_3(26, 3);

    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^[vV]?(\\d+)\\.(\\d+)(?:[.\\-+].*)?$");

    private final int major;
    private final int minor;

    MinecraftVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    /** Newest audited version. */
    public static MinecraftVersion latest() {
        MinecraftVersion[] versions = values();
        return versions[versions.length - 1];
    }

    /** Parses {@code 26.1}, {@code 26.1.2}, {@code v26.3}, etc. */
    public static Optional<MinecraftVersion> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int major;
        int minor;
        try {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
        for (MinecraftVersion version : values()) {
            if (version.major == major && version.minor == minor) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    public static MinecraftVersion parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException(
            "Unsupported Minecraft version '" + text + "'. Audited versions: "
                + V26_1.id() + " through " + latest().id()));
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    /** The major/minor pair, for example {@code 26.1}. */
    public String id() {
        return major + "." + minor;
    }

    public boolean isAtLeast(MinecraftVersion other) {
        return ordinal() >= other.ordinal();
    }

    /** True for versions whose vanilla structure-set data includes abandoned camps. */
    public boolean supportsAbandonedCamps() {
        return isAtLeast(V26_3);
    }

    @Override
    public String toString() {
        return id();
    }
}
