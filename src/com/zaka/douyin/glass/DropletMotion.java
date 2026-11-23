package com.zaka.douyin.glass;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;

/** Java port of KernelSU Manager's DampedDragAnimation. */
final class DropletMotion {
    static final float HELD_SCALE = 78f / 56f;

    private final Channel position = new Channel(0f, Math.sqrt(1000.0), 1.0, 0.001f);
    private final Channel pressure = new Channel(0f, Math.sqrt(1000.0), 1.0, 0.001f);
    private final Channel width = new Channel(1f, Math.sqrt(250.0), 0.6, 0.001f);
    private final Channel height = new Channel(1f, Math.sqrt(250.0), 0.7, 0.001f);
    private final Channel velocity = new Channel(0f, Math.sqrt(300.0), 0.5, 5f);
    private final Channel dragOffset = new Channel(0f, Math.sqrt(300.0), 1.0, 0.5f);

    private final VelocityTracker velocityTracker = VelocityTracker.obtain();
    private float span = 1f;
    private boolean releasePending;
    private boolean releaseArmed;
    private boolean trackVelocity = true;
    private long downTime;
    private long lastVelocityEventTime;

    float x() { return position.value; }
    float targetX() { return position.target; }
    float pressProgress() { return clamp(pressure.value, 0f, 1f); }
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
                || dragOffset.moving();
    }

    void setSpan(float value, float ignoredColumnWidth) {
        span = Math.max(1e-6f, value);
        position.threshold = 0.001f;
    }

    void moveTo(float x, boolean trackVelocity) {
        position.target = x;
        this.trackVelocity = trackVelocity;
        if (!trackVelocity) velocity.target = 0f;
    }

    void dragBy(float delta) {
        dragOffset.snapTo(dragOffset.value + delta);
    }

    void touchDown() { }

    void touchUp() {
        dragOffset.target = 0f;
    }

    void press() {
        releasePending = false;
        releaseArmed = false;
        pressure.target = 1f;
        width.target = HELD_SCALE;
        height.target = HELD_SCALE;
        velocityTracker.clear();
        downTime = SystemClock.uptimeMillis();
        lastVelocityEventTime = downTime;
    }

    void animateToValue(float value) {
        press();
        moveTo(value, true);
        release();
    }

    void release() {
        releasePending = true;
        releaseArmed = false;
    }

    void dispose() {
        velocityTracker.recycle();
    }

    void snapTo(float x) {
        position.snapTo(x);
        pressure.snapTo(0f);
        width.snapTo(1f);
        height.snapTo(1f);
        velocity.snapTo(0f);
        dragOffset.snapTo(0f);
        releasePending = false;
        releaseArmed = false;
        velocityTracker.clear();
    }

    boolean advance(float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0f) return isAnimating();

        position.advance(seconds);
        if (releasePending) {
            if (!releaseArmed) {
                releaseArmed = true;
            } else if (Math.abs(position.value - position.target) < span * 0.025f) {
                releasePending = false;
                pressure.target = 0f;
                width.target = 1f;
                height.target = 1f;
            }
        }
        pressure.advance(seconds);
        width.advance(seconds);
        height.advance(seconds);
        dragOffset.advance(seconds);

        if (trackVelocity && position.moving()) {
            long eventTime = SystemClock.uptimeMillis();
            if (eventTime <= lastVelocityEventTime) eventTime = lastVelocityEventTime + 1L;
            MotionEvent sample = MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE, position.value, 0f, 0);
            try {
                velocityTracker.addMovement(sample);
            } finally {
                sample.recycle();
            }
            lastVelocityEventTime = eventTime;
            velocityTracker.computeCurrentVelocity(1000);
            velocity.snapTo(velocityTracker.getXVelocity() / span);
        } else if (!trackVelocity) {
            velocity.advance(seconds);
        }
        return isAnimating();
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

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
