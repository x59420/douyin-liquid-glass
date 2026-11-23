package com.zaka.douyin.glass;

/**
 * Apache-2.0 adapted from compose-miuix-ui DropletMotion
 * (ForbidAd4TieBa), itself adapted from miuix/Kyant0 AndroidLiquidGlass.
 * Native View host keeps the existing DockOverlay touch dispatch.
 */
final class DropletMotion {
    static final float HELD_SCALE = 78f / 56f;

    private final Channel pressure = new Channel(0f, (float)Math.sqrt(1000.0), 1.0f, 0.001f);
    private final Channel width = new Channel(1f, (float)Math.sqrt(250.0), 0.6f, 0.001f);
    private final Channel height = new Channel(1f, (float)Math.sqrt(250.0), 0.7f, 0.001f);
    private final Channel velocity = new Channel(0f, (float)Math.sqrt(300.0), 0.5f, 0.01f);
    private final Channel glow = new Channel(0f, (float)Math.sqrt(300.0), 0.5f, 0.001f);
    private final Channel dragOffset = new Channel(0f, (float)Math.sqrt(300.0), 1.0f, 0.5f);
    private boolean releasePending;

    float pressProgress() { return clamp01(pressure.value); }
    float glowProgress() { return Math.max(0f, glow.value); }
    float scaleX() { return width.value / (1f - clamp(velocity.value * 0.075f, -0.2f, 0.2f)); }
    float scaleY() { return height.value * (1f - clamp(velocity.value * 0.025f, -0.2f, 0.2f)); }
    float accumulatedDrag() { return dragOffset.value; }
    float dragStretch(float slotWidth) {
        float span = Math.max(1f, slotWidth);
        return 1f + Math.min(0.22f, Math.abs(dragOffset.value) / span * 0.10f);
    }

    void press() {
        releasePending = false;
        pressure.target = 1f;
        width.target = HELD_SCALE;
        height.target = HELD_SCALE;
    }

    void touchDown() { glow.target = 1f; }

    void dragBy(float deltaPx) {
        dragOffset.snapTo(dragOffset.value + deltaPx);
    }

    void touchUp() {
        glow.target = 0f;
        dragOffset.target = 0f;
    }

    void release() {
        releasePending = true;
    }

    void snapToRest() {
        pressure.snapTo(0f);
        width.snapTo(1f);
        height.snapTo(1f);
        velocity.snapTo(0f);
        glow.snapTo(0f);
        dragOffset.snapTo(0f);
        releasePending = false;
    }

    boolean advance(float seconds, float position, float target, float positionVelocity,
                    int slotCount) {
        if (!Float.isFinite(seconds) || seconds <= 0f) return isAnimating();
        pressure.advance(seconds);
        width.advance(seconds);
        height.advance(seconds);
        glow.advance(seconds);
        dragOffset.advance(seconds);

        float span = Math.max(1f, slotCount - 1f);
        velocity.snapTo(positionVelocity / span);

        if (releasePending && Math.abs(position - target) <= Math.max(span * 0.025f, 0.001f)) {
            releasePending = false;
            pressure.target = 0f;
            width.target = 1f;
            height.target = 1f;
        }
        return isAnimating();
    }

    boolean isAnimating() {
        return releasePending || pressure.moving() || width.moving() || height.moving()
                || velocity.moving() || glow.moving() || dragOffset.moving();
    }

    private static float clamp01(float v) { return clamp(v, 0f, 1f); }
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Exact damped-oscillator step, independent of frame rate. */
    private static final class Channel {
        float value;
        float target;
        float velocity;
        final float frequency;
        final float damping;
        final float threshold;

        Channel(float initial, float frequency, float damping, float threshold) {
            this.value = initial;
            this.target = initial;
            this.frequency = frequency;
            this.damping = damping;
            this.threshold = threshold;
        }

        boolean moving() {
            return Math.abs(value - target) > threshold
                    || Math.abs(velocity) > threshold * 62.5f;
        }

        void snapTo(float next) {
            value = next;
            target = next;
            velocity = 0f;
        }

        void advance(float seconds) {
            if (!moving()) {
                snapTo(target);
                return;
            }
            double offset = value - target;
            double speed = velocity;
            double decay = Math.exp(-damping * frequency * seconds);
            if (damping == 1f) {
                double slope = speed + frequency * offset;
                value = (float)(target + decay * (offset + slope * seconds));
                velocity = (float)(decay * (speed - frequency * slope * seconds));
            } else {
                double angular = frequency * Math.sqrt(Math.max(0.0,
                        1.0 - damping * damping));
                double sine = Math.sin(angular * seconds);
                double cosine = Math.cos(angular * seconds);
                double slope = (speed + damping * frequency * offset) / angular;
                double wave = offset * cosine + slope * sine;
                value = (float)(target + decay * wave);
                velocity = (float)(decay * (angular * (slope * cosine - offset * sine)
                        - damping * frequency * wave));
            }
            if (!moving()) snapTo(target);
        }
    }
}
