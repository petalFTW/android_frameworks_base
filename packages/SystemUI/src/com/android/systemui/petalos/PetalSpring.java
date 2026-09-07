/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.petalos;

/**
 * Mass-1 underdamped spring integrator matching the petalOS web prototype exactly.
 *
 * <p>The integrator is equivalent to Android's {@code SpringForce} (stiffness + damping ratio),
 * but is stepped manually so the spring value can be baked into custom geometry every frame.
 * Sub-steps at ~4 ms for numeric parity with the prototype.
 */
public final class PetalSpring {

    public float value;
    public float target;
    public float velocity;

    public PetalSpring(float initialValue) {
        value = initialValue;
        target = initialValue;
        velocity = 0f;
    }

    /** Set the target the spring settles toward. */
    public void set(float targetValue) {
        target = targetValue;
    }

    /** Jump instantly to {@code v} (no animation). */
    public void snap(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    /** Advance the spring by {@code dt} seconds. */
    public void step(float dt, float stiffness, float dampingRatio) {
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        for (int i = 0; i < steps; i++) {
            float a = -stiffness * (value - target) - c * velocity;
            velocity += a * h;
            value += velocity * h;
        }
    }

    /** True when the spring is effectively settled. */
    public boolean resting() {
        return Math.abs(velocity) < 0.0015f && Math.abs(value - target) < 0.0015f;
    }
}
