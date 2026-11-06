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

// goo maths for stuff oozing out of the bezel. volume hud + power menu share it
final class PetalLiquid {

    // opening is bouncy, reach leads and the height lags behind it
    static final float OPEN_REACH_K = 420f;
    static final float OPEN_REACH_Z = 0.46f;
    static final float OPEN_SPREAD_K = 240f;
    static final float OPEN_SPREAD_Z = 0.65f;
    // draining flips it, height goes first and reach never overshoots so no pop at zero
    static final float DRAIN_REACH_K = 230f;
    static final float DRAIN_REACH_Z = 0.96f;
    static final float DRAIN_SPREAD_K = 420f;
    static final float DRAIN_SPREAD_Z = 0.92f;

    // how hard the front edge wobbles off the spring speed
    private static final float BULGE_GAIN = 0.1f;
    private static final float BULGE_MAX = 0.4f;
    // jelly squash once its nearly out
    private static final float SQUASH = 0.72f;

    // exit is a timeline now, springs were too polite. swell, slam, smear, gone
    static final long EXIT_MS = 500L;
    private long mExitStartNanos = -1L;
    // killed mid open? splat from where it actually was, not from full size
    private float mExitFrom = 1f;

    // outputs, read after solve()
    float reach;     // 0..~1.15 of full width
    float spread;    // 0..~1.05 of full height
    float bulge;     // px, +out -in, feed straight to jointPath
    float joint;     // 0..1 of full joint depth
    float opacity;

    // open/spread are the two springs, w/h are the full size in px
    void solve(PetalSpring open, PetalSpring spread, boolean draining, float w, float h) {
        float r = PetalUtils.clamp(open.value, 0f, 1.15f);
        float c = PetalUtils.clamp(spread.value, 0f, 1.1f);
        reach = r;

        // starts life as a round blob about as tall as it is wide
        float bead = h > 0f ? PetalUtils.clamp(r * w * 1.25f / h, 0f, 1f) : 1f;
        float s = PetalUtils.lerp(bead, 1f, Math.min(c, 1f)) + Math.max(0f, c - 1f);
        // overshoot goes wide -> goes thin, undershoot fattens. thats the jelly
        float settled = smoothstep(0.7f, 1f, c);
        s *= 1f - SQUASH * (r - 1f) * settled;
        this.spread = Math.max(0f, s);

        // speed bends the front, fast out = fat nose, sucked back = dented in
        float b = open.velocity * BULGE_GAIN * w;
        float cap = BULGE_MAX * w * Math.max(r, 0.2f);
        bulge = PetalUtils.clamp(b, -cap, cap);

        if (draining) {
            // meniscus stays fat while it drains, looks like it's getting slurped
            joint = (float) Math.sqrt(Math.max(r, 0f)) * Math.max(c, 0.55f);
            opacity = PetalUtils.clamp(r * 5f, 0f, 1f);
        } else {
            joint = c;
            opacity = PetalUtils.clamp(r * 2.4f, 0f, 1f);
        }
    }

    void beginExit(long nowNanos, float fromReach) {
        mExitStartNanos = nowNanos;
        mExitFrom = PetalUtils.clamp(fromReach, 0f, 1.15f);
    }

    void cancelExit() {
        mExitStartNanos = -1L;
    }

    boolean exiting() {
        return mExitStartNanos >= 0L;
    }

    float exitT(long nowNanos) {
        if (mExitStartNanos < 0L) return 0f;
        return PetalUtils.clamp((nowNanos - mExitStartNanos) / 1_000_000f / EXIT_MS, 0f, 1f);
    }

    // splat into the bezel. maxSpread = how far it may smear along the edge, x full height
    void solveExit(float t, float w, float maxSpread) {
        // tiny wind up so the slam reads as a slam
        float wind = (float) Math.sin(Math.PI * PetalUtils.clamp(t / 0.12f, 0f, 1f)) * 0.09f;
        float a = PetalUtils.clamp((t - 0.1f) / 0.3f, 0f, 1f);
        float a3 = a * a * a;
        float sp = PetalUtils.clamp((t - 0.38f) / 0.36f, 0f, 1f);
        float ab = PetalUtils.clamp((t - 0.72f) / 0.28f, 0f, 1f);

        float r = 1f + wind * (1f - a) - 0.86f * a3;
        r -= 0.06f * easeOut(sp);
        r *= (1f - ab) * (1f - ab) * mExitFrom;
        reach = Math.max(0f, r);

        // hits the frame, splodges up and down it, wobbles, keeps creeping as it soaks in
        float ripple = 0.08f * (float) Math.sin(sp * Math.PI * 3.0) * (1f - sp);
        float smear = (maxSpread - 1.1f) * backOut(sp);
        spread = 1f + 0.1f * a3 + smear + ripple + 0.06f * ab;

        joint = (1f + (0.45f - 1f) * sp) * (1f - ab);
        // nose gets sucked in on the way down
        bulge = -0.3f * w * reach * a * (1f - sp);
        opacity = 1f - smoothstep(0.8f, 1f, t);
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float backOut(float t) {
        float c = 1.9f;
        float u = t - 1f;
        return 1f + (c + 1f) * u * u * u + c * u * u;
    }

    static void stepOpen(PetalSpring open, PetalSpring spread, float dt) {
        open.step(dt, OPEN_REACH_K, OPEN_REACH_Z);
        spread.step(dt, OPEN_SPREAD_K, OPEN_SPREAD_Z);
    }

    static void stepDrain(PetalSpring open, PetalSpring spread, float dt) {
        open.step(dt, DRAIN_REACH_K, DRAIN_REACH_Z);
        spread.step(dt, DRAIN_SPREAD_K, DRAIN_SPREAD_Z);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = PetalUtils.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
