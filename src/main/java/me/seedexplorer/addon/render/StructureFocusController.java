package me.seedexplorer.addon.render;

import me.seedexplorer.addon.loot.ChestLootOutput;
import me.seedexplorer.addon.loot.ChestLootPredictor;
import me.seedexplorer.addon.structures.GeneratedStructure;
import me.seedexplorer.addon.workers.WorkerManager;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class StructureFocusController {
    public enum State { IDLE, ZOOMING_IN, FOCUSED, ZOOMING_OUT }
    public record FocusTarget(double x, double y, double z, double distance) {
    }

    private static final double ORBIT_PITCH_DEGREES = 72.0;
    private static final double ORBIT_DEGREES_PER_TICK = 0.35;
    private static final int CHEST_PANEL_X = 10;
    private static final int CHEST_PANEL_Y = 76;
    private static final int CHEST_PANEL_WIDTH = 240;
    private static final int CHEST_ROW_HEIGHT = 20;
    private static final int CHEST_PANEL_PADDING = 10;

    private State state = State.IDLE;
    private FreeCamera3D camera;
    private GeneratedStructure target;
    private List<ChestLootOutput> chests = List.of();
    private int focusTicks;
    private boolean loadingLoot;
    private boolean autoRotate;
    private double returnTargetX, returnTargetY, returnTargetZ;
    private double returnDistance, returnPitchDeg, returnYawDeg;
    private double orbitX, orbitY, orbitZ, orbitDistance, orbitYawDeg;

    private Consumer<GeneratedStructure> onFocusStart;
    private Runnable onFocusEnd;
    private Function<GeneratedStructure, FocusTarget> focusResolver;

    private static final Color CHEST_BG = new Color(16, 14, 8, 200);
    private static final Color CHEST_BORDER = new Color(80, 70, 30, 180);

    public StructureFocusController() {
    }

    public void bindCamera(FreeCamera3D camera) {
        this.camera = camera;
    }

    public void bindFocusResolver(Function<GeneratedStructure, FocusTarget> resolver) {
        this.focusResolver = resolver;
    }

    public void startFocus(GeneratedStructure structure) {
        if (camera == null) return;
        this.target = structure;
        this.focusTicks = 0;
        this.returnTargetX = camera.targetX();
        this.returnTargetY = camera.targetY();
        this.returnTargetZ = camera.targetZ();
        this.returnDistance = camera.distance();
        this.returnPitchDeg = Math.toDegrees(camera.pitch());
        this.returnYawDeg = Math.toDegrees(camera.yaw());
        FocusTarget focus = resolveFocus(structure);
        this.orbitX = focus.x;
        this.orbitY = focus.y;
        this.orbitZ = focus.z;
        this.orbitDistance = focus.distance;
        this.orbitYawDeg = Math.toDegrees(camera.yaw());
        this.autoRotate = true;
        camera.animateViewOf(orbitX, orbitY, orbitZ, orbitDistance,
            ORBIT_PITCH_DEGREES, orbitYawDeg, 40);
        this.chests = List.of();
        this.loadingLoot = ChestLootPredictor.canPredict(structure.type);
        if (loadingLoot) {
            WorkerManager.get().submit(() -> {
                List<ChestLootOutput> loaded;
                try {
                    loaded = loadChests(structure);
                } catch (RuntimeException exception) {
                    loaded = List.of();
                }
                List<ChestLootOutput> published = loaded;
                Minecraft.getInstance().execute(() -> {
                    if (target != structure) return;
                    chests = published;
                    loadingLoot = false;
                });
            });
        }
        state = State.ZOOMING_IN;
        if (onFocusStart != null) onFocusStart.accept(structure);
    }

    public void cancelFocus() {
        if (state == State.IDLE) return;
        if (camera == null) return;
        camera.animateTo(returnTargetX, returnTargetY, returnTargetZ,
            returnDistance, returnPitchDeg, returnYawDeg, 40);
        state = State.ZOOMING_OUT;
        loadingLoot = false;
        if (onFocusEnd != null) onFocusEnd.run();
    }

    public void tick() {
        if (state == State.IDLE) return;
        if (camera == null) return;

        camera.tick();

        if (state == State.ZOOMING_IN && !camera.isAnimating()) {
            state = State.FOCUSED;
            focusTicks = 0;
        }

        if (state == State.FOCUSED) {
            focusTicks++;
            if (autoRotate) {
                FocusTarget resolved = resolveFocus(target);
                orbitX = lerp(orbitX, resolved.x, 0.18);
                orbitY = lerp(orbitY, resolved.y, 0.18);
                orbitZ = lerp(orbitZ, resolved.z, 0.18);
                orbitDistance = lerp(orbitDistance, resolved.distance, 0.18);
                orbitYawDeg += ORBIT_DEGREES_PER_TICK;
                camera.viewOf(orbitX, orbitY, orbitZ, orbitDistance,
                    ORBIT_PITCH_DEGREES, orbitYawDeg);
            }
        }

        if (state == State.ZOOMING_OUT && !camera.isAnimating()) {
            state = State.IDLE;
            target = null;
            chests = List.of();
            loadingLoot = false;
        }
    }

    public void renderOverlay(GuiGraphicsExtractor g) {
        if (state != State.FOCUSED || target == null) return;

        Font f = Minecraft.getInstance().font;
        double pulse = (Math.sin(System.nanoTime() / 400_000_000.0) * 0.5 + 0.5) * 0.35 + 0.65;

        String title = target.displayName() + " @ " + target.x + ", " + target.z;
        g.text(f, title, 10, 10, 0xFFFFD700);

        int panelX = CHEST_PANEL_X;
        int panelY = CHEST_PANEL_Y;
        int panelW = CHEST_PANEL_WIDTH;
        int rowH = CHEST_ROW_HEIGHT;
        int panelH = Math.max(1, chests.size()) * rowH + CHEST_PANEL_PADDING;

        Renderer2D r = Renderer2D.COLOR;
        r.begin();
        r.quad(panelX, panelY, panelW, panelH, CHEST_BG);
        r.boxLines(panelX, panelY, panelW, panelH, CHEST_BORDER);
        r.render();

        if (loadingLoot) {
            g.text(f, "Predicting chests...", panelX + 8, panelY + 6, 0xFFE6D38A);
            return;
        }

        if (chests.isEmpty()) {
            g.text(f, "No predictable chests", panelX + 8, panelY + 6, 0xFF9AA6B2);
            return;
        }

        for (int i = 0; i < chests.size(); i++) {
            ChestLootOutput ch = chests.get(i);
            String label = "Chest " + (i + 1) + ": " + ch.blockX() + ", " + ch.blockZ();
            int goldR = (int) (200 + pulse * 55);
            int goldG = (int) (160 + pulse * 55);
            int goldColor = 0xFF000000 | (goldR << 16) | (goldG << 8);
            g.text(f, label, panelX + 8, panelY + 5 + i * rowH, goldColor);
        }
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public void disableAutoRotate() {
        autoRotate = false;
    }

    /** Returns the focused chest row under a physical map-screen coordinate. */
    public ChestLootOutput chestAt(double mouseX, double mouseY) {
        if (state != State.FOCUSED || loadingLoot || chests.isEmpty()) return null;
        if (mouseX < CHEST_PANEL_X || mouseX > CHEST_PANEL_X + CHEST_PANEL_WIDTH
            || mouseY < CHEST_PANEL_Y || mouseY > CHEST_PANEL_Y
                + chests.size() * CHEST_ROW_HEIGHT + CHEST_PANEL_PADDING) {
            return null;
        }
        int row = (int) ((mouseY - CHEST_PANEL_Y - 2) / CHEST_ROW_HEIGHT);
        return row >= 0 && row < chests.size() ? chests.get(row) : null;
    }

    /** Hands an active focus animation directly back to first-person flight. */
    public void releaseForManualFlight() {
        if (camera != null) camera.stopFlight();
        state = State.IDLE;
        target = null;
        chests = List.of();
        loadingLoot = false;
        autoRotate = false;
        if (onFocusEnd != null) onFocusEnd.run();
    }

    private FocusTarget resolveFocus(GeneratedStructure structure) {
        if (focusResolver != null) {
            FocusTarget resolved = focusResolver.apply(structure);
            if (resolved != null) return resolved;
        }
        double y = structure.y == GeneratedStructure.UNKNOWN_Y ? 64.0 : structure.y + 2.0;
        return new FocusTarget(structure.x + 0.5, y, structure.z + 0.5, 25.0);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private List<ChestLootOutput> loadChests(GeneratedStructure structure) {
        List<ChestLootOutput> results = ChestLootPredictor.predictForStructure(structure);
        if (results == null || results.isEmpty()) return List.of();
        List<ChestLootOutput> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingInt(ChestLootOutput::blockX)
            .thenComparingInt(ChestLootOutput::blockY)
            .thenComparingInt(ChestLootOutput::blockZ));
        return sorted;
    }

    public void setOnFocusStart(Consumer<GeneratedStructure> callback) {
        this.onFocusStart = callback;
    }

    public void setOnFocusEnd(Runnable callback) {
        this.onFocusEnd = callback;
    }

    public State state() {
        return state;
    }

    public GeneratedStructure target() {
        return target;
    }

    public List<ChestLootOutput> chests() {
        return chests;
    }
}
