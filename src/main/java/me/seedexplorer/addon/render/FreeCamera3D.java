package me.seedexplorer.addon.render;

import org.joml.Matrix4f;

/**
 * A reusable 3D orbit camera that revolves around a target point.
 * State is stored in doubles for smooth interpolation.
 */
public final class FreeCamera3D {
    private double targetX;
    private double targetY;
    private double targetZ;
    private double distance;
    private double pitch;
    private double yaw;

    private double fov;
    private double aspectRatio;
    private double nearZ;
    private double farZ;

    private boolean animating;
    private int animTicks;
    private int animDuration;
    private double animStartTargetX, animStartTargetY, animStartTargetZ;
    private double animStartDistance, animStartPitch, animStartYaw;
    private double animEndTargetX, animEndTargetY, animEndTargetZ;
    private double animEndDistance, animEndPitch, animEndYaw;

    public FreeCamera3D() {
        this.targetX = 0;
        this.targetY = 0;
        this.targetZ = 0;
        this.distance = 200;
        this.pitch = Math.toRadians(90);
        this.yaw = 0;
        this.fov = Math.toRadians(60);
        this.aspectRatio = 16.0 / 9.0;
        this.nearZ = 0.1;
        this.farZ = 10000;
    }

    public void target(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void distance(double d) {
        this.distance = clamp(d, 5, 500);
    }

    public void pitch(double p) {
        this.pitch = clamp(p, Math.toRadians(1), Math.toRadians(179));
    }

    public void yaw(double y) {
        this.yaw = y;
    }

    public double targetX() { return targetX; }
    public double targetY() { return targetY; }
    public double targetZ() { return targetZ; }
    public double distance() { return distance; }
    public double pitch() { return pitch; }
    public double yaw() { return yaw; }

    public void fov(double fovDegrees) {
        this.fov = Math.toRadians(clamp(fovDegrees, 1, 179));
    }

    public void aspectRatio(double ratio) {
        this.aspectRatio = Math.max(ratio, 0.01);
    }

    public void zNear(double z) { this.nearZ = z; }
    public void zFar(double z) { this.farZ = z; }

    /**
     * Applies a mouse drag delta to pitch and yaw.
     * Positive deltaX turns right, positive deltaY tilts down.
     */
    public void mouseDrag(double deltaX, double deltaY) {
        pitch = clamp(pitch - Math.toRadians(deltaY), Math.toRadians(1), Math.toRadians(179));
        yaw -= Math.toRadians(deltaX);
    }

    /**
     * Adjusts the camera distance by the given scroll amount.
     * Positive zooms in, negative zooms out.
     */
    public void scroll(double amount) {
        distance = clamp(distance * (1.0 + amount * 0.05), 5, 500);
    }

    /** Computes the eye position from spherical coordinates around the target. */
    private double eyeX() {
        return targetX + distance * Math.sin(yaw) * Math.sin(pitch);
    }

    private double eyeY() {
        return targetY + distance * Math.cos(pitch);
    }

    private double eyeZ() {
        return targetZ + distance * Math.cos(yaw) * Math.sin(pitch);
    }

    /**
     * Returns the view matrix (camera-to-world) for the current camera state.
     * Uses a right-handed coordinate system with Y-up.
     */
    public Matrix4f getViewMatrix() {
        Matrix4f m = new Matrix4f();
        m.lookAt(
            (float) eyeX(), (float) eyeY(), (float) eyeZ(),
            (float) targetX, (float) targetY, (float) targetZ,
            0.0f, 1.0f, 0.0f
        );
        return m;
    }

    /**
     * Returns the perspective projection matrix.
     */
    public Matrix4f getProjectionMatrix() {
        Matrix4f m = new Matrix4f();
        m.perspective((float) fov, (float) aspectRatio, (float) nearZ, (float) farZ);
        return m;
    }

    /**
     * Starts a smooth animation toward the given camera state over durationTicks.
     */
    public void animateTo(double targetX, double targetY, double targetZ,
                          double distance, double pitchDeg, double yawDeg,
                          int durationTicks) {
        this.animStartTargetX = this.targetX;
        this.animStartTargetY = this.targetY;
        this.animStartTargetZ = this.targetZ;
        this.animStartDistance = this.distance;
        this.animStartPitch = this.pitch;
        this.animStartYaw = this.yaw;

        this.animEndTargetX = targetX;
        this.animEndTargetY = targetY;
        this.animEndTargetZ = targetZ;
        this.animEndDistance = distance;
        this.animEndPitch = Math.toRadians(pitchDeg);
        this.animEndYaw = Math.toRadians(yawDeg);

        this.animTicks = 0;
        this.animDuration = Math.max(durationTicks, 1);
        this.animating = true;
    }

    /**
     * Advances the animation by one tick.
     * @return true if still animating, false when complete.
     */
    public boolean tick() {
        if (!animating) return false;
        animTicks++;
        if (animTicks >= animDuration) {
            targetX = animEndTargetX;
            targetY = animEndTargetY;
            targetZ = animEndTargetZ;
            distance = animEndDistance;
            pitch = animEndPitch;
            yaw = animEndYaw;
            animating = false;
            return false;
        }
        double t = (double) animTicks / animDuration;
        t = smoothstep(t);
        targetX = lerp(animStartTargetX, animEndTargetX, t);
        targetY = lerp(animStartTargetY, animEndTargetY, t);
        targetZ = lerp(animStartTargetZ, animEndTargetZ, t);
        distance = lerp(animStartDistance, animEndDistance, t);
        pitch = lerpAngle(animStartPitch, animEndPitch, t);
        yaw = lerpAngle(animStartYaw, animEndYaw, t);
        return true;
    }

    public boolean isAnimating() {
        return animating;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : v > max ? max : v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double lerpAngle(double a, double b, double t) {
        double diff = b - a;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return a + diff * t;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }
}
