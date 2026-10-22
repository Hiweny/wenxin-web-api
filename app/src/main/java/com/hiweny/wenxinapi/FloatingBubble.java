package com.hiweny.wenxinapi;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 悬浮球：使用 App 同一枚图案（环形 + 四角星）的透明版本，可拖动、自动贴边。
 *
 * 交互：
 *  - 轻点 → 打开 App
 *  - 长按（>700ms）→ 隐藏悬浮球
 *  - 拖动 → 松手吸附到最近一侧
 */
public class FloatingBubble {

    private final Context ctx;
    private WindowManager wm;
    private FrameLayout container;
    private ImageView icon;
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
        icon = new ImageView(ctx);
        Drawable d = ctx.getResources().getDrawable(R.drawable.ic_bubble, null);
        icon.setImageDrawable(d);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = (int) dp(5);
        icon.setPadding(pad, pad, pad, pad);
        container.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int size = (int) dp(56);
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
                        icon.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start();
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
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        long dur = System.currentTimeMillis() - downAt;
                        if (!moved) {
                            if (dur > 700) {
                                hide();
                                Prefs.setAttr(ctx, "bubble", "0");
                                Log.add("[bubble] 已隐藏悬浮图标");
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
            Log.add("[bubble] 悬浮图标已显示（拖动后自动贴边）");
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
}
