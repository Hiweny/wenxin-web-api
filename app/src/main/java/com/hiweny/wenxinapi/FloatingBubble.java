package com.hiweny.wenxinapi;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** 悬浮球：一颗会自己贴边的星星 */
public class FloatingBubble {

    private final Context ctx;
    private WindowManager wm;
    private FrameLayout container;
    private StarView star;
    private WindowManager.LayoutParams lp;
    private boolean shown = false;

    public FloatingBubble(Context ctx) { this.ctx = ctx; }

    public boolean isShown() { return shown; }

    public boolean canShow() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(ctx);
    }

    public void show() {
        if (shown || !canShow()) return;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        container = new FrameLayout(ctx);
        star = new StarView(ctx);
        container.addView(star, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int size = (int) dp(58);
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = screenWidth() - size - (int) dp(8);
        lp.y = (int) dp(240);

        container.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean moved;
            private long downAt;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        moved = false;
                        downAt = System.currentTimeMillis();
                        star.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        lp.x = clampX(startX + dx);
                        lp.y = clampY(startY + dy);
                        try { wm.updateViewLayout(container, lp); } catch (Exception ignore) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        star.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        long dur = System.currentTimeMillis() - downAt;
                        if (!moved) {
                            if (dur > 700) {
                                hide();
                                Prefs.setAttr(ctx, "bubble", "0");
                                Log.add("[bubble] 已隐藏悬浮星星");
                            } else {
                                Intent i = new Intent(ctx, MainActivity.class);
                                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                ctx.startActivity(i);
                            }
                        } else {
                            snapToEdge();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(container, lp);
            shown = true;
            Log.add("[bubble] 悬浮星星已显示（拖动后自动贴边）");
        } catch (Exception e) {
            Log.add("[bubble] 显示失败: " + e);
        }
    }

    private int clampX(int x) {
        int max = screenWidth() - lp.width - (int) dp(4);
        return Math.max((int) dp(4), Math.min(x, max));
    }

    private int clampY(int y) {
        int max = screenHeight() - lp.height - (int) dp(24);
        return Math.max(0, Math.min(y, max));
    }

    /** 松手后吸附到最近的左右边缘 */
    private void snapToEdge() {
        final int target = (lp.x + lp.width / 2 < screenWidth() / 2)
                ? (int) dp(6)
                : screenWidth() - lp.width - (int) dp(6);
        ValueAnimator anim = ValueAnimator.ofInt(lp.x, target);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                lp.x = (int) a.getAnimatedValue();
                try { wm.updateViewLayout(container, lp); } catch (Exception ignore) { }
            }
        });
        anim.start();
    }

    public void hide() {
        if (!shown) return;
        try { wm.removeView(container); } catch (Exception ignore) { }
        shown = false;
    }

    private int screenWidth() {
        return ctx.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return ctx.getResources().getDisplayMetrics().heightPixels;
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** 五角星（渐变 + 光晕） */
    static class StarView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        StarView(Context c) {
            super(c);
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(0x445B8CFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.1f);
            border.setColor(0x66FFFFFF);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            build(w, h);
        }

        private void build(int w, int h) {
            path.reset();
            float cx = w / 2f, cy = h / 2f;
            float outer = Math.min(w, h) * 0.34f;
            float inner = outer * 0.42f;
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 5;
                float r = (i % 2 == 0) ? outer : inner;
                float x = (float) (cx + Math.cos(angle) * r);
                float y = (float) (cy + Math.sin(angle) * r);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            fill.setShader(new LinearGradient(cx - outer, cy - outer, cx + outer, cy + outer,
                    new int[]{0xFFFFD98A, 0xFF5B8CFF, 0xFF7A6BFF}, null, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            if (path.isEmpty()) build(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.36f;
            canvas.drawCircle(cx, cy, r, glow);
            canvas.drawPath(path, fill);
            canvas.drawPath(path, border);
        }
    }
}
