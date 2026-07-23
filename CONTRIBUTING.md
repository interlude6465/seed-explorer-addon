# Contributing

## Code Style

- **Indentation:** 4 spaces. No tabs.
- **Braces:** Same-line (K&R) for classes, methods, and control flow.
- **Imports:** No wildcard imports. Group by: `net.minecraft.*`, `com.google.*`,
  `java.*`, then project imports.
- **Null handling:** Use `Optional` for container types; avoid `@Nullable`
  on return values. Check with `== null` / `!= null`.
- **Records over classes:** Use `record` for value types (coordinates, loot
  seeds, chest positions).
- **Access modifiers:** Prefer `private`; package-private only for proxy
  invocations and test collaborators.
- **No comments in product code.** This file and the dev log are the
  appropriate places for rationale.
- **Method length:** Keep methods under 80 lines. Extract helpers for
  repeated patterns (block-state checks, RNG index lookups).
- **Naming:** `camelCase` for fields/variables, `PascalCase` for types,
  `UPPER_SNAKE` for constants. Minecraft identifiers use `snake_case`.

## How to Verify Changes Don't Break Existing Oracles

1. **Run the regression suite:**
   ```powershell
   .\gradlew predictionRegressionSuite --console=plain
   ```
   This checks every accepted oracle SHA-256 pin, replays every chest
   simulation, and verifies terrain height hash, critical carved height,
   and carver-replaceable tag count. Exit code 0 = pass.

2. **Run individual oracle validation** for debugging:
   ```powershell
   .\gradlew validateLootProbeOracle -Pseed=42 -PchunkX=-86 -PchunkZ=-110 -Poracle=validation/oracles/lootprobe-seed42-validated.json
   ```

3. **If you added a new structure**, run:
   ```powershell
   .\gradlew offlineLootSimulation -Pseed=<seed> -PchunkX=<cx> -PchunkZ=<cz>
   ```

4. **Stronghold work:**
   ```powershell
   .\gradlew simulateStronghold -Pseed=0
   .\gradlew strongholdSavedStartSimulation
   ```

5. **After any change** to loot-table loading, RNG simulation, or decoration
   index logic, re-run `predictionRegressionSuite` before opening a PR.

## Accuracy Policy

### Fail closed, no unvalidated predictions

- No structure or loot table enters the product until it passes a strict
  zero-difference comparison against an independently captured Paper oracle.
- Simulator output that matches simulator output is circular evidence and
  is **not** accepted. Every oracle must trace to a fresh Paper server launch.
- If an oracle file is missing, replaced, or has a modified SHA-256,
  `PredictionRegressionSuite` must exit 2 (fail closed). No silent fallback.
- The `VALIDATED_STRUCTURES` set in `ChestLootPredictor` defines the
  product boundary. Everything else returns `List.of()` — no prediction,
  no warning, no fallback.
- Research branches (stronghold parity, dungeon simulation) must stay
  behind `VALIDATED_STRUCTURES` until independently validated.

### Changing an oracle

1. Recapture from a fresh Paper server with LootProbe.
2. Place the new JSON in `validation/oracles/` with a descriptive filename.
3. Update the SHA-256 pins in `PredictionRegressionSuite`.
4. Run the full suite — zero differences expected.
5. Document the recapture in `PREDICTION_DEVELOPMENT_LOG.md`.

### What constitutes a validated prediction

- Exact block coordinates for every chest.
- Exact loot-table resource ID.
- Exact 64-bit loot seed yielded by `RandomizableContainer.setLootTableSeed()`.
- Exact aggregate item counts (item ID → total count) from the full loot
  table simulation.
- All checks must pass for all captured oracle chests.
