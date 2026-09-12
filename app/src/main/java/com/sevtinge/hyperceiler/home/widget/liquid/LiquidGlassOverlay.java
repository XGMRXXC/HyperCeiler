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
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import com.sevtinge.hyperceiler.common.log.AndroidLog;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 液态玻璃的「背景」渲染器：自己抓底栏背后的内容快照，再用 AGSL 做
 * 模糊 + 折射 + 色散 + 饱和度提升，最后当作药丸的底色画上去。
 *
 * 为什么不直接用 HyperOS 的 HyperMaterial：
 * MIUI 的「背景模糊」模糊的是**窗口背后**的内容，而底栏背后是同一个窗口里的
 * 列表，所以系统那套材质永远不会给它做模糊——只能自己采样。
 *
 * 着色器的折射/色散数学照抄 KernelSU 的
 * {@code ui/component/liquid/Lens.kt}（源自 Kyant0/AndroidLiquidGlass，Apache-2.0），
 * 只是把 miuix-blur 的 backdrop 换成了自己抓的 Bitmap。
 */
public final class LiquidGlassOverlay {

    private static final String TAG = "LiquidGlassOverlay";

    /** 快照最小间隔，避免每帧重绘整棵视图树。 */
    private static final long CAPTURE_INTERVAL_MS = 80L;

    /** 折射带宽度（px）与折射位移（px）。 */
    private static final float REFRACT_HEIGHT = 40f;
    private static final float REFRACT_AMOUNT = 26f;

    /**
     * 快照的降采样倍率。
     *
     * 原生分辨率下（650×208）几个像素的模糊根本看不出来；miuix-blur 也是先把
     * backdrop 降采样再模糊的。降到 1/4 后，同样的 9 抽头等效覆盖 4 倍范围，
     * 才有毛玻璃的观感，而且采样更便宜。
     */
    private static final float DOWNSCALE = 4f;

    /** 在降采样后的快照上做 9 抽头模糊的半径（源像素）。 */
    private static final float BLUR_RADIUS = 8f;

    private static final String SHADER_SRC =
        "uniform shader content;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uScale;\n"
            + "uniform float uRefractHeight;\n"
            + "uniform float uRefractAmount;\n"
            + "uniform float uBlur;\n"
            + "uniform float uDispersion;\n"
            + "uniform float2 uLight;\n"
            + "uniform float2 uTouch;\n"
            + "uniform float uTouchAlpha;\n"
            + "uniform float uPress;\n"
            + "uniform float uDark;\n"
            + "\n"
            + "float sdRoundRect(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - r);\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "half4 blur9(float2 p) {\n"
            + "    float2 q = p * uScale;\n"
            + "    float d = uBlur;\n"
            + "    float e = d * 0.7071;\n"
            + "    half4 s = content.eval(q) * 0.25;\n"
            + "    s = s + content.eval(q + float2(d, 0.0)) * 0.125;\n"
            + "    s = s + content.eval(q - float2(d, 0.0)) * 0.125;\n"
            + "    s = s + content.eval(q + float2(0.0, d)) * 0.125;\n"
            + "    s = s + content.eval(q - float2(0.0, d)) * 0.125;\n"
            + "    s = s + content.eval(q + float2(e, e)) * 0.0625;\n"
            + "    s = s + content.eval(q + float2(e, 0.0 - e)) * 0.0625;\n"
            + "    s = s + content.eval(q - float2(e, e)) * 0.0625;\n"
            + "    s = s + content.eval(q - float2(e, 0.0 - e)) * 0.0625;\n"
            + "    return s;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = uSize * 0.5;\n"
            + "    float2 c = coord - hs;\n"
            + "    float sd = sdRoundRect(c, hs, uRadius);\n"
            + "    float inside = 1.0 - smoothstep(0.0 - 1.5, 1.5, sd);\n"
            + "    if (inside <= 0.0) return half4(0.0, 0.0, 0.0, 0.0);\n"
            + "\n"
            + "    float depth = max(0.0 - sd, 0.0);\n"
            + "    float2 nn = normalize(c + float2(0.0001, 0.0001));\n"
            + "    float band = 1.0 - smoothstep(0.0, uRefractHeight, depth);\n"
            + "\n"
            + "    float t = clamp(1.0 - depth / uRefractHeight, 0.0, 1.0);\n"
            + "    float lens = (1.0 - sqrt(max(1.0 - t * t, 0.0))) * uRefractAmount * band;\n"
            + "    float2 refracted = coord - nn * lens;\n"
            + "    half4 col = blur9(refracted);\n"
            + "\n"
            + "    float disp = uDispersion * band;\n"
            + "    if (disp > 0.002) {\n"
            + "        float3 shifted = col.rgb;\n"
            + "        shifted.r = blur9(refracted + nn * (disp * 6.0)).r;\n"
            + "        shifted.b = blur9(refracted - nn * (disp * 6.0)).b;\n"
            + "        col = half4(shifted, col.a);\n"
            + "    }\n"
            + "\n"
            + "    float gray = dot(col.rgb, float3(0.299, 0.587, 0.114));\n"
            + "    float3 vibrant = mix(float3(gray, gray, gray), col.rgb, 1.5);\n"
            + "    float3 tint = mix(float3(1.0, 1.0, 1.0), float3(0.13, 0.13, 0.14), uDark);\n"
            + "    float3 fill = mix(vibrant, tint, 0.3);\n"
            + "\n"
            + "    float lamp = normalize(uLight);\n"
            + "    float d1 = max(dot(nn, lamp), 0.0);\n"
            + "    float d2 = max(0.0 - dot(nn, lamp), 0.0);\n"
            + "    float p1 = d1 * d1 * d1 * d1 * d1;\n"
            + "    float p2 = d2 * d2 * d2 * d2 * d2;\n"
            + "    float spec = (p1 + p2 * 0.45) * band;\n"
            + "    float touch = uTouchAlpha\n"
            + "        * (1.0 - smoothstep(0.0, uSize.y * 0.8, distance(coord, uTouch)));\n"
            + "    float glow = spec * 0.35 + touch * 0.25 + uPress * 0.05;\n"
            + "    float edge = 1.0 - smoothstep(0.0, 2.5, depth);\n"
            + "    fill = fill + float3(glow, glow, glow) + float3(edge * 0.18, edge * 0.18, edge * 0.18);\n"
            + "\n"
            + "    return half4(clamp(fill, 0.0, 1.0), inside);\n"
            + "}\n";

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 调试：把抓到的背景快照写到外部缓存目录，方便 adb pull 出来核对。 */
    private static final boolean DEBUG_DUMP_BACKDROP = true;

    /**
     * 调试二分法第一步：把快照**原样**画进药丸（不过着色器）。
     * 如果这一步能看到背后的内容，说明"抓→画"这条路是通的，
     * 问题只出在 AGSL 采样；如果还是平色，说明压根没画上。
     */
    private static final boolean DEBUG_DRAW_RAW_BITMAP = false;

    private final Context mContext;
    private boolean mDumped;
    private boolean mLoggedDraw;

    private RuntimeShader mShader;
    private boolean mFailed;
    private Bitmap mBitmap;
    private Canvas mBitmapCanvas;
    private BitmapShader mBitmapShader;
    private long mLastCapture;
    private View mSource;
    private final int[] mAnchorLocation = new int[2];
    private final int[] mSourceLocation = new int[2];

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    public LiquidGlassOverlay(Context context) {
        mContext = context.getApplicationContext();
    }

    /** 底栏背后的那一层内容（通常是 ViewPager）。 */
    public void setSource(View source) {
        mSource = source;
    }

    /**
     * 抓一次背后的内容快照。节流到 {@link #CAPTURE_INTERVAL_MS}，
     * 布局/滚动变化时由外部触发重绘即可。
     */
    public void capture(View anchor, int width, int height) {
        if (mFailed || mSource == null || width <= 0 || height <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (mBitmap != null && now - mLastCapture < CAPTURE_INTERVAL_MS) return;
        mLastCapture = now;

        try {
            int scaledWidth = Math.max(1, Math.round(width / DOWNSCALE));
            int scaledHeight = Math.max(1, Math.round(height / DOWNSCALE));
            if (mBitmap == null || mBitmap.getWidth() != scaledWidth
                || mBitmap.getHeight() != scaledHeight) {
                releaseBitmap();
                mBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
                mBitmapCanvas = new Canvas(mBitmap);
                mBitmapShader = new BitmapShader(mBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            mBitmapCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            anchor.getLocationInWindow(mAnchorLocation);
            mSource.getLocationInWindow(mSourceLocation);
            int save = mBitmapCanvas.save();
            mBitmapCanvas.scale(1f / DOWNSCALE, 1f / DOWNSCALE);
            mBitmapCanvas.translate(
                mSourceLocation[0] - mAnchorLocation[0],
                mSourceLocation[1] - mAnchorLocation[1]);
            mSource.draw(mBitmapCanvas);
            mBitmapCanvas.restoreToCount(save);

            if (DEBUG_DUMP_BACKDROP && !mDumped) {
                mDumped = true;
                dumpBackdrop();
            }
        } catch (Throwable t) {
            mFailed = true;
            releaseBitmap();
            AndroidLog.w(TAG, "backdrop capture failed, liquid glass falls back to material", t);
        }
    }

    public void draw(Canvas canvas, float width, float height, float radius,
                     float lightAngle, float touchX, float touchY, float touchAlpha,
                     float press, float dispersion, boolean night) {
        if (!mLoggedDraw) {
            mLoggedDraw = true;
            android.util.Log.w(TAG, "draw: failed=" + mFailed + " bitmap=" + mBitmap
                + " bitmapSize=" + (mBitmap == null ? "-" : mBitmap.getWidth() + "x" + mBitmap.getHeight())
                + " scale=" + (1f / DOWNSCALE) + " blur=" + BLUR_RADIUS
                + " pill=" + width + "x" + height);
        }
        if (mFailed || mBitmap == null || mBitmapShader == null) return;
        if (width <= 0f || height <= 0f) return;

        if (DEBUG_DRAW_RAW_BITMAP) {
            // 二分法第一步：原图直接铺上去，看能不能看到背后的内容
            mPaint.setShader(null);
            mPaint.setAlpha(255);
            mPaint.setFilterBitmap(true);
            canvas.drawBitmap(mBitmap, 0f, 0f, mPaint);
            return;
        }

        if (mShader == null) {
            try {
                mShader = new RuntimeShader(SHADER_SRC);
            } catch (Throwable t) {
                mFailed = true;
                AndroidLog.w(TAG, "AGSL shader failed to compile, falling back to material", t);
                return;
            }
        }

        mShader.setInputShader("content", mBitmapShader);
        mShader.setFloatUniform("uSize", width, height);
        mShader.setFloatUniform("uRadius", Math.min(radius, Math.min(width, height) * 0.5f));
        mShader.setFloatUniform("uScale", 1f / DOWNSCALE);
        mShader.setFloatUniform("uRefractHeight", REFRACT_HEIGHT);
        mShader.setFloatUniform("uRefractAmount", REFRACT_AMOUNT);
        mShader.setFloatUniform("uBlur", BLUR_RADIUS);
        mShader.setFloatUniform("uDispersion", Math.max(0f, dispersion));
        mShader.setFloatUniform("uLight", (float) Math.cos(lightAngle), (float) Math.sin(lightAngle));
        mShader.setFloatUniform("uTouch", clamp(touchX, 0f, width), clamp(touchY, 0f, height));
        mShader.setFloatUniform("uTouchAlpha", Math.max(0f, touchAlpha));
        mShader.setFloatUniform("uPress", Math.max(0f, press));
        mShader.setFloatUniform("uDark", night ? 1f : 0f);

        mPaint.setShader(mShader);
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mPaint);
        mPaint.setShader(null);
    }

    /** 内容滚动/布局变化时调用，让下一帧重抓快照。 */
    public void invalidateBackdrop() {
        mLastCapture = 0L;
    }

    public void release() {
        releaseBitmap();
    }

    private void dumpBackdrop() {
        try {
            File dir = mContext.getExternalCacheDir();
            if (dir == null) return;
            File out = new File(dir, "backdrop.png");
            FileOutputStream os = new FileOutputStream(out);
            mBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            AndroidLog.w(TAG, "backdrop dumped to " + out.getAbsolutePath());
        } catch (Throwable t) {
            AndroidLog.w(TAG, "backdrop dump failed", t);
        }
    }

    private void releaseBitmap() {        if (mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
        mBitmapCanvas = null;
        mBitmapShader = null;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
