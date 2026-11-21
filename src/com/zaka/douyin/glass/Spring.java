package com.zaka.douyin.glass;

/**
 * 单值阻尼弹簧（跟 Kyant 的 DampedDragAnimation 一个路子）。
 * 半隐式欧拉积分，用来做松手回弹、按压进度。
 */
final class Spring {

    float value;
    float target;
    float velocity;

    float stiffness = 460f;
    float damping = 34f;

    Spring(float initial) {
        value = initial;
        target = initial;
    }

    void set(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    void snapTo(float v) {
        value = v;
    }

    void to(float t) {
        target = t;
    }

    void to(float t, float stiff, float damp) {
        target = t;
        stiffness = stiff;
        damping = damp;
    }

    /** 推进一帧；返回 true 表示还在动 */
    boolean step(long dtMs) {
        float dt = Math.min(0.033f, Math.max(0.001f, dtMs / 1000f));
        float dx = value - target;
        float accel = -stiffness * dx - damping * velocity;
        velocity += accel * dt;
        value += velocity * dt;

        if (Math.abs(velocity) < 0.5f && Math.abs(value - target) < 0.0015f) {
            value = target;
            velocity = 0f;
            return false;
        }
        return true;
    }
}
