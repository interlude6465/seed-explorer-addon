package me.seedexplorer.addon.render;

import org.joml.Matrix4f;

/** First-person spectator camera with smooth, accelerated flight controls. */
public final class FreeCamera3D {
    private static final double MAX_HORIZONTAL_SPEED = 24.0;
    private static final double MAX_VERTICAL_SPEED = 18.0;
    private static final double ACCELERATION_SECONDS = 2.0;
    private static final double DECELERATION_SECONDS = 0.45;

    // Kept as targetX/Y/Z for source compatibility with the map renderer. These
    // values are now the camera's physical position, not an orbit anchor.
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
    private double forwardVelocity;
    private double strafeVelocity;
    private double verticalVelocity;

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
        position(x, y, z);
    }

    public void position(double x, double y, double z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void distance(double d) {
        // Keep the camera numerically stable while allowing the full world to be
        // inspected. The previous 500 block cap caused scroll-out to snap back.
        this.distance = clamp(d, 2, 1_000_000_000D);
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
        animating = false;
        pitch = clamp(pitch - Math.toRadians(deltaY), Math.toRadians(1), Math.toRadians(179));
        yaw -= Math.toRadians(deltaX);
    }

    /**
     * Adjusts the camera distance by the given scroll amount.
     * Positive zooms in, negative zooms out.
     */
    public void scroll(double amount) {
        if (!Double.isFinite(amount) || amount == 0.0) return;
        // Exponential scaling is stable for high-resolution touchpads and never
        // crosses zero when a large wheel delta is delivered.
        distance = clamp(distance * Math.exp(-amount * 0.12), 2, 1_000_000_000D);
    }

    public double eyeX() { return targetX; }
    public double eyeY() { return targetY; }
    public double eyeZ() { return targetZ; }

    public double forwardX() { return -Math.sin(yaw) * Math.sin(pitch); }
    public double forwardY() { return -Math.cos(pitch); }
    public double forwardZ() { return -Math.cos(yaw) * Math.sin(pitch); }
    public double rightX() { return Math.cos(yaw); }
    public double rightZ() { return -Math.sin(yaw); }

    /**
     * Returns the view matrix for the current camera position and look direction.
     * Uses a right-handed coordinate system with Y-up.
     */
    public Matrix4f getViewMatrix() {
        Matrix4f m = new Matrix4f();
        double lookX = eyeX() + forwardX();
        double lookY = eyeY() + forwardY();
        double lookZ = eyeZ() + forwardZ();
        m.lookAt(
            (float) eyeX(), (float) eyeY(), (float) eyeZ(),
            (float) lookX, (float) lookY, (float) lookZ,
            0.0f, 1.0f, 0.0f
        );
        return m;
    }

    /**
     * Advances held-key flight. Inputs normally range from -1..1, but may be
     * larger for movement speed tiers. Diagonal movement is normalized to the
     * largest requested tier so a 3x diagonal still has a 3x total speed.
     * Forward follows the complete look vector, including pitch; strafe remains
     * level and vertical is world Y.
     */
    public boolean updateFlight(double forwardInput, double strafeInput,
                                double verticalInput, double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) return false;
        double length = Math.sqrt(forwardInput * forwardInput
            + strafeInput * strafeInput + verticalInput * verticalInput);
        double maximumInput = Math.max(1.0, Math.max(Math.abs(forwardInput),
            Math.max(Math.abs(strafeInput), Math.abs(verticalInput))));
        if (length > maximumInput) {
            double scale = maximumInput / length;
            forwardInput *= scale;
            strafeInput *= scale;
            verticalInput *= scale;
        }

        double forwardTarget = forwardInput * MAX_HORIZONTAL_SPEED;
        double strafeTarget = strafeInput * MAX_HORIZONTAL_SPEED;
        double verticalTarget = verticalInput * MAX_VERTICAL_SPEED;
        forwardVelocity = approachVelocity(forwardVelocity,
            forwardTarget, Math.max(MAX_HORIZONTAL_SPEED, Math.abs(forwardTarget)), deltaSeconds);
        strafeVelocity = approachVelocity(strafeVelocity,
            strafeTarget, Math.max(MAX_HORIZONTAL_SPEED, Math.abs(strafeTarget)), deltaSeconds);
        verticalVelocity = approachVelocity(verticalVelocity,
            verticalTarget, Math.max(MAX_VERTICAL_SPEED, Math.abs(verticalTarget)), deltaSeconds);

        boolean moving = Math.abs(forwardVelocity) > 1.0e-4
            || Math.abs(strafeVelocity) > 1.0e-4 || Math.abs(verticalVelocity) > 1.0e-4;
        if (!moving) return false;

        animating = false;
        targetX += (forwardX() * forwardVelocity + rightX() * strafeVelocity) * deltaSeconds;
        targetY += (forwardY() * forwardVelocity + verticalVelocity) * deltaSeconds;
        targetZ += (forwardZ() * forwardVelocity + rightZ() * strafeVelocity) * deltaSeconds;
        return true;
    }

    public void stopFlight() {
        forwardVelocity = 0.0;
        strafeVelocity = 0.0;
        verticalVelocity = 0.0;
    }

    public void cancelAnimation() {
        animating = false;
        stopFlight();
    }

    /** Places the camera once at a useful vantage point looking at a world position. */
    public void viewOf(double focusX, double focusY, double focusZ,
                       double distance, double pitchDeg, double yawDeg) {
        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad = Math.toRadians(yawDeg);
        position(
            focusX + distance * Math.sin(yawRad) * Math.sin(pitchRad),
            focusY + distance * Math.cos(pitchRad),
            focusZ + distance * Math.cos(yawRad) * Math.sin(pitchRad));
        distance(distance);
        pitch(pitchRad);
        yaw(yawRad);
        animating = false;
        stopFlight();
    }

    public void animateViewOf(double focusX, double focusY, double focusZ,
                              double distance, double pitchDeg, double yawDeg,
                              int durationTicks) {
        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad = Math.toRadians(yawDeg);
        animateTo(
            focusX + distance * Math.sin(yawRad) * Math.sin(pitchRad),
            focusY + distance * Math.cos(pitchRad),
            focusZ + distance * Math.cos(yawRad) * Math.sin(pitchRad),
            distance, pitchDeg, yawDeg, durationTicks);
    }

    /**
     * Returns the perspective projection matrix.
     */
    public Matrix4f getProjectionMatrix() {
        Matrix4f m = new Matrix4f();
        // The far plane follows the orbit distance so distant terrain remains
        // visible after zooming out. Clamp to float range used by JOML.
        double dynamicFar = Math.max(farZ, distance * 4.0 + 4096.0);
        m.perspective((float) fov, (float) aspectRatio, (float) nearZ,
            (float) Math.min(dynamicFar, 3.0e38));
        return m;
    }

    /**
     * Starts a smooth animation toward the given camera state over durationTicks.
     */
    public void animateTo(double targetX, double targetY, double targetZ,
                          double distance, double pitchDeg, double yawDeg,
                          int durationTicks) {
        stopFlight();
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

    private static double approachVelocity(double current, double target,
                                           double maximumSpeed, double deltaSeconds) {
        double seconds = target == 0.0 ? DECELERATION_SECONDS
            : current != 0.0 && Math.signum(current) != Math.signum(target)
                ? DECELERATION_SECONDS : ACCELERATION_SECONDS;
        double amount = maximumSpeed / seconds * deltaSeconds;
        if (current < target) return Math.min(target, current + amount);
        if (current > target) return Math.max(target, current - amount);
        return target;
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
