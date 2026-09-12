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

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.common.log.AndroidLog;

/**
 * 液态玻璃药丸的「玻璃层」。
 *
 * KernelSU 那边这一层是 miuix-blur 的 {@code drawBackdrop} + {@code lens} +
 * {@code vibrancy} + {@code Highlight}：compose 专有的 GPU 管线，View 世界拿不到。
 * 这里用 AGSL（API 33+，本机 API 37）自己写：
 *
 *  - 圆角矩形 SDF 推出边缘一圈「镜片带」；
 *  - 双峰高光（主光源 + 对侧次光源）对应 KernelSU 的 BloomStroke dualPeak；
 *  - 色散环：由拖动速度驱动，模仿 {@code lens(chromaticAberration)}；
 *  - 触摸高光：对应 {@code InteractiveHighlight} 的径向白光；
 *  - 亮色下的内阴影：对应 {@code innerShadow}，按下时加重。
 *
 * 药丸的模糊填充仍然由 HyperOS 的 HyperMaterial 负责（那一层是系统原生模糊），
 * 这层只叠加镜片边缘的光学效果，所以中间区域几乎是透明的，不会遮住底下的模糊。
 */
public class LiquidGlassOverlay extends View {

    private static final String TAG = "LiquidGlassOverlay";

    /** 镜片带的宽度（px），超过它就不再算边缘。 */
    private static final float BAND_PX = 34f;

    /**
     * 说明：这份 AGSL 刻意写得很保守——不用 pow()、不用向量单目负号、不用
     * 科学计数法字面量、不在分支里 return，全部换成乘法/减法/mix。
     * 之前那版更"漂亮"的写法在设备上直接编译报错。
     */
    private static final String SHADER_SRC =
        "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float2 uLight;\n"
            + "uniform float2 uTouch;\n"
            + "uniform float uTouchAlpha;\n"
            + "uniform float uPress;\n"
            + "uniform float uDispersion;\n"
            + "uniform float uMode;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - r);\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 c = coord - hs;\n"
            + "    float sd = sdRoundRect(c, hs, uRadius);\n"
            + "    float inside = 1.0 - smoothstep(0.0 - 1.0, 1.0, sd);\n"
            + "    float depth = max(0.0 - sd, 0.0);\n"
            + "    float2 nn = normalize(c + float2(0.0001, 0.0001));\n"
            + "    float band = (1.0 - smoothstep(0.0, 34.0, depth)) * inside;\n"
            + "\n"
            + "    float lamp = normalize(uLight);\n"
            + "    float d1 = dot(nn, lamp);\n"
            + "    float d2 = 0.0 - d1;\n"
            + "    float p1 = max(d1, 0.0);\n"
            + "    float p2 = max(d2, 0.0);\n"
            + "    float s1 = p1 * p1 * p1 * p1 * p1;\n"
            + "    float s2 = p2 * p2 * p2 * p2 * p2;\n"
            + "    float spec = (s1 + s2 * 0.45) * band;\n"
            + "\n"
            + "    float touchDist = distance(coord, uTouch);\n"
            + "    float touch = uTouchAlpha * (1.0 - smoothstep(0.0, uSize.y * 0.8, touchDist));\n"
            + "    float disp = uDispersion * band;\n"
            + "    float highlight = spec * 0.55 + touch * 0.30 + uPress * 0.06 * inside + disp * 0.22;\n"
            + "    float shadow = band * band * (0.08 + uPress * 0.30);\n"
            + "\n"
            + "    float3 white = float3(1.0, 1.0, 1.0);\n"
            + "    float3 black = float3(0.0, 0.0, 0.0);\n"
            + "    float3 fringe = float3(0.5, 0.5, 0.5) + 0.5 * float3(\n"
            + "        cos(disp * 12.566),\n"
            + "        cos(disp * 12.566 + 2.094),\n"
            + "        cos(disp * 12.566 + 4.188));\n"
            + "    float3 col = mix(white, fringe, clamp(disp * 0.9, 0.0, 1.0));\n"
            + "    float a = mix(highlight, shadow, uMode);\n"
            + "    float3 outColor = mix(col, black, uMode);\n"
            + "    return half4(outColor, clamp(a, 0.0, 1.0));\n"
            + "}\n";

    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RuntimeShader mShader;
    private boolean mShaderFailed;

    private float mLightAngle = (float) (-Math.PI / 2.0);
    private float mTouchX = -1f;
    private float mTouchY = -1f;
    private float mTouchAlpha = 0f;
    private float mPress = 0f;
    private float mDispersion = 0f;
    private float mRadius = 0f;
    private boolean mNight = true;

    public LiquidGlassOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        mHighlightPaint.setBlendMode(BlendMode.PLUS);
    }

    /** 当前设备/系统能不能用 AGSL（API 33+）。 */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    public void update(float lightAngle, float touchX, float touchY, float touchAlpha,
                       float press, float dispersion, float radius, boolean night) {
        mLightAngle = lightAngle;
        mTouchX = touchX;
        mTouchY = touchY;
        mTouchAlpha = touchAlpha;
        mPress = press;
        mDispersion = dispersion;
        mRadius = radius;
        mNight = night;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!isSupported() || mShaderFailed || getWidth() == 0 || getHeight() == 0) return;

        if (mShader == null) {
            try {
                mShader = new RuntimeShader(SHADER_SRC);
                mHighlightPaint.setShader(mShader);
                mShadowPaint.setShader(mShader);
            } catch (Throwable t) {
                // 编译不过就退化成纯材质，不能把整个 app 拖崩
                mShaderFailed = true;
                AndroidLog.w(TAG, "AGSL shader failed to compile, falling back to material only", t);
                return;
            }
        }

        float width = getWidth();
        float height = getHeight();
        float radius = Math.min(mRadius, Math.min(width, height) * 0.5f);
        float lightX = (float) Math.cos(mLightAngle);
        float lightY = (float) Math.sin(mLightAngle);

        mShader.setFloatUniform("uSize", width, height);
        mShader.setFloatUniform("uRadius", radius);
        mShader.setFloatUniform("uLight", lightX, lightY);
        mShader.setFloatUniform("uTouch", mTouchX, mTouchY);
        mShader.setFloatUniform("uTouchAlpha", Math.max(0f, mTouchAlpha));
        mShader.setFloatUniform("uPress", Math.max(0f, mPress));
        mShader.setFloatUniform("uDispersion", Math.max(0f, mDispersion));

        mShader.setFloatUniform("uMode", 0f);
        canvas.drawRect(0f, 0f, width, height, mHighlightPaint);

        if (!mNight) {
            mShader.setFloatUniform("uMode", 1f);
            canvas.drawRect(0f, 0f, width, height, mShadowPaint);
        }
    }
}
