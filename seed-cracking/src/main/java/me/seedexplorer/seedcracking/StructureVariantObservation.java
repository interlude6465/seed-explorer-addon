package me.seedexplorer.seedcracking;

import java.util.Objects;

/** An observed variant label at a known structure placement chunk. */
public record StructureVariantObservation(
    String structureId,
    int chunkX,
    int chunkZ,
    String variantId
) {
    public StructureVariantObservation {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(variantId, "variantId");
        if (structureId.isBlank()) {
            throw new IllegalArgumentException("structureId must not be blank");
        }
        if (variantId.isBlank()) {
            throw new IllegalArgumentException("variantId must not be blank");
        }
    }

    public String describe() {
        return structureId + " variant=" + variantId + " at chunk (" + chunkX + ", " + chunkZ + ")";
    }
}
