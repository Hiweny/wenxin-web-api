package com.hiweny.wenxinapi;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** 悬浮球：常驻显示服务状态，点击回到 App，长按隐藏 */
public class FloatingBubble {

    private final Context ctx;
    private WindowManager wm;
    private TextView view;
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
        view = new TextView(ctx);
        view.setText("文");
        view.setTextSize(15f);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF5B8CFF, 0xFF7A6BFF});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1.5f), 0x55FFFFFF);
        view.setBackground(bg);
        view.setElevation(dp(6));

        int size = (int) dp(52);
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) dp(12);
        lp.y = (int) dp(220);

        view.setOnTouchListener(new View.OnTouchListener() {
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
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignore) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downAt;
                        if (!moved) {
                            if (dur > 700) {
                                hide();
                                Prefs.setAttr(ctx, "bubble", "0");
                                Log.add("[bubble] 已隐藏悬浮球");
                            } else {
                                Intent i = new Intent(ctx, MainActivity.class);
                                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                ctx.startActivity(i);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(view, lp);
            shown = true;
            Log.add("[bubble] 悬浮球已显示");
        } catch (Exception e) {
            Log.add("[bubble] 显示失败: " + e);
        }
    }

    public void hide() {
        if (!shown) return;
        try { wm.removeView(view); } catch (Exception ignore) { }
        shown = false;
    }

    private float dp(float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }
}
