package com.zaka.douyin.glass;

/*
 * Copyright 2026, compose-miuix-ui contributors.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Native Java adaptation of ForbidAd4TieBa DropletMotion.kt, which adapts
 * miuix DampedDragAnimation / Kyant0 AndroidLiquidGlass.
 * The independent position, pressure, width, height, velocity, glow and
 * drag-offset channels are intentionally kept separate.
 * Source: https://github.com/aikavvak12una/ForbidAd4TieBa
 * Reference revision: 3f1272cc54dafa991d7253f6423af261eb86853b
 * Local changes: Kotlin/Compose state and callbacks translated to Java.
 */
final class DropletMotion {
    static final float HELD_SCALE = 78f / 56f;

    private final Channel position = new Channel(0f, Math.sqrt(1000.0), 1.0, 0.1f);
    private final Channel pressure = new Channel(0f, Math.sqrt(1000.0), 1.0, 0.001f);
    private final Channel width = new Channel(1f, Math.sqrt(250.0), 0.6, 0.001f);
    private final Channel height = new Channel(1f, Math.sqrt(250.0), 0.7, 0.001f);
    private final Channel velocity = new Channel(0f, Math.sqrt(300.0), 0.5, 0.01f);
    private final Channel glow = new Channel(0f, Math.sqrt(300.0), 0.5, 0.001f);
    private final Channel dragOffset = new Channel(0f, Math.sqrt(300.0), 1.0, 0.5f);

    private float span = 1f;
    private boolean releasePending;
    private boolean trackVelocity = true;

    float x() { return position.value; }
    float targetX() { return position.target; }
    float pressProgress() { return clamp(pressure.value, 0f, 1f); }
    float glowProgress() { return Math.max(0f, glow.value); }
    float accumulatedDrag() { return dragOffset.value; }

    float scaleX() {
        return width.value / (1f - clamp(velocity.value * 0.075f, -0.2f, 0.2f));
    }

    float scaleY() {
        return height.value * (1f - clamp(velocity.value * 0.025f, -0.2f, 0.2f));
    }

    boolean isAnimating() {
        return releasePending || position.moving() || pressure.moving()
                || width.moving() || height.moving() || velocity.moving()
                || glow.moving() || dragOffset.moving();
    }

    void setSpan(float value, float columnWidth) {
        span = Math.max(1f, value);
        position.threshold = Math.max(1f, columnWidth) * 0.001f;
    }

    void moveTo(float x, boolean trackVelocity) {
        position.target = x;
        this.trackVelocity = trackVelocity;
        if (!trackVelocity) velocity.target = 0f;
    }

    void dragBy(float delta) {
        dragOffset.snapTo(dragOffset.value + delta);
    }

    void touchDown() {
        glow.target = 1f;
    }

    void touchUp() {
        glow.target = 0f;
        dragOffset.target = 0f;
    }

    void press() {
        releasePending = false;
        pressure.target = 1f;
        width.target = HELD_SCALE;
        height.target = HELD_SCALE;
    }

    /** Keep the lens expanded until its position spring reaches the target. */
    void release() {
        releasePending = true;
        releaseIfArrived();
    }

    void snapTo(float x) {
        position.snapTo(x);
        pressure.snapTo(0f);
        width.snapTo(1f);
        height.snapTo(1f);
        velocity.snapTo(0f);
        glow.snapTo(0f);
        dragOffset.snapTo(0f);
        releasePending = false;
    }

    boolean advance(float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0f) return isAnimating();

        position.advance(seconds);
        releaseIfArrived();
        pressure.advance(seconds);
        width.advance(seconds);
        height.advance(seconds);
        glow.advance(seconds);
        dragOffset.advance(seconds);

        if (!position.moving() && trackVelocity) {
            velocity.snapTo(0f);
        } else if (trackVelocity) {
            // The source uses a VelocityTracker over the animated position.
            // Its analytical spring derivative is the native equivalent.
            velocity.snapTo(position.velocity / span);
        } else {
            velocity.advance(seconds);
        }
        return isAnimating();
    }

    private void releaseIfArrived() {
        if (!releasePending || Math.abs(position.value - position.target) > span * 0.025f) return;
        releasePending = false;
        pressure.target = 0f;
        width.target = 1f;
        height.target = 1f;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    /** Exact analytical damped-oscillator step used by the Tieba native port. */
    private static final class Channel {
        float value;
        float target;
        float velocity;
        final double frequency;
        final double damping;
        float threshold;

        Channel(float initial, double frequency, double damping, float threshold) {
            value = initial;
            target = initial;
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

            if (damping == 1.0) {
                double slope = speed + frequency * offset;
                value = (float) (target + decay * (offset + slope * seconds));
                velocity = (float) (decay * (speed - frequency * slope * seconds));
            } else {
                double angular = frequency * Math.sqrt(Math.max(0.0, 1.0 - damping * damping));
                double sine = Math.sin(angular * seconds);
                double cosine = Math.cos(angular * seconds);
                double slope = (speed + damping * frequency * offset) / angular;
                double wave = offset * cosine + slope * sine;
                value = (float) (target + decay * wave);
                velocity = (float) (decay * (angular * (slope * cosine - offset * sine)
                        - damping * frequency * wave));
            }

            if (!moving()) snapTo(target);
        }
    }
}
