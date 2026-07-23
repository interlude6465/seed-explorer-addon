package me.seedexplorer.addon.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.seedexplorer.addon.loot.VanillaLootStructureSimulator;
import me.seedexplorer.addon.loot.VanillaLootTables;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class JungleTempleValidate {
    private static final String JT = "minecraft:chests/jungle_temple";
    private static final String JD = "minecraft:chests/jungle_temple_dispenser";
    private static final PrintStream O = System.out;

    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VanillaLootTables.loadAllFromBundled();
        long seed = a.length > 0 ? Long.parseLong(a[0]) : -2843430517209339837L;
        int cx = a.length > 1 ? Integer.parseInt(a[1]) : -241;
        int cz = a.length > 2 ? Integer.parseInt(a[2]) : 16;
        int idx = a.length > 3 ? Integer.parseInt(a[3]) : 4;
        var path = Path.of(a.length > 4 ? a[4] : "validation/oracles/lootprobe-jungle-temple-seed-2843430517209339837.json");
            run(seed, cx, cz, idx, path);
    }

    private static void run(long seed, int cx, int cz, int idx, Path path) throws Exception {
        var root = JsonParser.parseReader(Files.newBufferedReader(path)).getAsJsonObject();
        var scan = root.getAsJsonObject("regionScan");
        List<OC> oracle = new ArrayList<>();
        for (JsonElement se : scan.getAsJsonArray("structures")) {
            for (JsonElement ce : se.getAsJsonObject().getAsJsonArray("chests")) {
                var c = ce.getAsJsonObject();
                String t = string(c, "lootTable");
                if (!JT.equals(t) && !JD.equals(t)) continue;
                Long ls = c.has("lootTableSeed") && !c.get("lootTableSeed").isJsonNull()
                    ? c.get("lootTableSeed").getAsLong() : null;
                oracle.add(new OC(integer(c,"x"), integer(c,"y"), integer(c,"z"), t, ls));
            }
        }
        O.println("Oracle: " + oracle.size() + " chests");
        oracle.forEach(oc -> O.println("  " + oc.x+","+oc.y+","+oc.z+" "+oc.table+" "+oc.seed));

        var key = BuiltinStructures.JUNGLE_TEMPLE;
        var sim = VanillaLootStructureSimulator.simulate(seed, 0, key, cx, cz, idx);
        O.println("Index " + idx + " simulated: " + sim.size() + " chests");
        sim.forEach(s -> O.println("  "+s.x()+","+s.y()+","+s.z()+" "+s.lootTableId()+" "+s.lootSeed()));

        int match = 0;
        for (var s : sim) {
            String t = s.lootTableId();
            if (!JT.equals(t) && !JD.equals(t)) continue;
            for (var o : oracle) {
                if (s.x()==o.x && s.y()==o.y && s.z()==o.z && t.equals(o.table) && o.seed != null && s.lootSeed()==o.seed) match++;
            }
        }

        // Compare
        List<String> diffs = new ArrayList<>();
        List<OC> unmatched = new ArrayList<>(oracle);
        for (var s : sim) {
            String t = s.lootTableId();
            if (!JT.equals(t) && !JD.equals(t)) continue;
            OC act = null;
            for (int i = 0; i < unmatched.size(); i++) {
                var o = unmatched.get(i);
                if (o.x==s.x() && o.y==s.y() && o.z==s.z()) { act = unmatched.remove(i); break; }
            }
            String pos = s.x()+","+s.y()+","+s.z();
            if (act == null) { diffs.add("MISSING oracle " + pos); continue; }
            if (!s.lootTableId().equals(act.table)) diffs.add(pos + " table");
            if (act.seed != null && s.lootSeed() != act.seed) diffs.add(pos + " seed");
        }
        for (var extra : unmatched) diffs.add("UNEXPECTED oracle at " + extra.x+","+extra.y+","+extra.z);

        boolean pass = diffs.isEmpty();
        O.println("RESULT: " + (pass ? "PASS" : "FAIL") + " diffs=" + diffs.size());
        diffs.forEach(d -> O.println("  " + d));
        if (!pass) System.exit(2);
    }

    private static String string(JsonObject o, String k) { return o.has(k)&&!o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }
    private static int integer(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsInt() : 0; }
    private record OC(int x, int y, int z, String table, Long seed) {}
}
