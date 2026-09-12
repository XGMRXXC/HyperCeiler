/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.home.widget.liquid;

import android.view.Choreographer;

/**
 * 极简弹簧积分器。
 *
 * 参数语义和 Compose 的 {@code spring(dampingRatio, stiffness)} 一致，
 * 这样 KernelSU {@code DampedDragAnimation} 里那几条弹簧参数可以照搬：
 * value = spring(1f, 1000f)、velocity = spring(0.5f, 300f)、
 * press = spring(1f, 1000f)、scale = spring(0.6~0.7f, 250f)。
 *
 * 用半隐式欧拉 + 定步长子步进，避免高刚度下单帧步长过大而发散。
 */
public final class SpringValue implements Choreographer.FrameCallback {

    /** 子步进的最大步长，1/240s，保证 omega*dt 远小于 2。 */
    private static final float MAX_STEP_SECONDS = 1f / 240f;
    /** 单帧最多推进的时间，防止掉帧后一次跳太远。 */
    private static final float MAX_FRAME_SECONDS = 0.05f;

    private final float mStiffness;
    private final float mDampingRatio;
    private final float mRestThreshold;
    private final float mOmega;

    private float mValue;
    private float mTarget;
    private float mVelocity;
    private boolean mRunning;
    private long mLastFrameNanos;
    private Runnable mOnUpdate;

    public SpringValue(float stiffness, float dampingRatio, float restThreshold, float initialValue) {
        mStiffness = stiffness;
        mDampingRatio = dampingRatio;
        mRestThreshold = restThreshold;
        mOmega = (float) Math.sqrt(stiffness);
        mValue = initialValue;
        mTarget = initialValue;
    }

    public void setOnUpdate(Runnable onUpdate) {
        mOnUpdate = onUpdate;
    }

    public float get() {
        return mValue;
    }

    public float getTarget() {
        return mTarget;
    }

    public float getVelocity() {
        return mVelocity;
    }

    public boolean isSettled() {
        return !mRunning;
    }

    /** 直接把值放到目标上，不播动画。 */
    public void snapTo(float value) {
        stop();
        mValue = value;
        mTarget = value;
        mVelocity = 0f;
        notifyUpdate();
    }

    /** 以弹簧方式移动到目标。 */
    public void animateTo(float target) {
        mTarget = target;
        if (mRunning) return;
        mRunning = true;
        mLastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        if (mRunning) {
            Choreographer.getInstance().removeFrameCallback(this);
            mRunning = false;
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (mLastFrameNanos == 0L) {
            mLastFrameNanos = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        float dt = (frameTimeNanos - mLastFrameNanos) / 1_000_000_000f;
        mLastFrameNanos = frameTimeNanos;
        dt = Math.min(dt, MAX_FRAME_SECONDS);

        int steps = Math.max(1, (int) Math.ceil(dt / MAX_STEP_SECONDS));
        float step = dt / steps;
        float damping = 2f * mDampingRatio * mOmega;
        for (int i = 0; i < steps; i++) {
            float displacement = mValue - mTarget;
            float acceleration = -mStiffness * displacement - damping * mVelocity;
            mVelocity += acceleration * step;
            mValue += mVelocity * step;
        }

        boolean settled = Math.abs(mValue - mTarget) < mRestThreshold
            && Math.abs(mVelocity) < mRestThreshold * 10f;
        if (settled) {
            mValue = mTarget;
            mVelocity = 0f;
            mRunning = false;
            notifyUpdate();
            return;
        }

        notifyUpdate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void notifyUpdate() {
        if (mOnUpdate != null) mOnUpdate.run();
    }
}
