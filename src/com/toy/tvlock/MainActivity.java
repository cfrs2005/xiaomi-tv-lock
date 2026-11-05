package com.toy.tvlock;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings and self-check. Reachable from the app list, so it must give nothing away.
 *
 * <p>Hard rules this screen follows, after the previous version leaked both:
 * <ul>
 *   <li>The PIN is never displayed and never accepted here — the keypad overlay owns that.</li>
 *   <li>There is no unlock button. Unlocking requires entering the PIN on the lock screen.</li>
 *   <li>All actions are read-only or lock-only while the TV is locked.</li>
 * </ul>
 *
 * <p>While locked, this activity sits behind the overlay anyway; the content is still restricted so
 * that a guard defect cannot turn it into a way in.
 */
public class MainActivity extends Activity {

    private TextView stateLine;
    private TextView detailLine;
    private LinearLayout actions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111111);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(48), dp(36), dp(48), dp(48));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("电视锁");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        stateLine = new TextView(this);
        stateLine.setTextColor(0xFFCCCCCC);
        stateLine.setTextSize(20);
        stateLine.setGravity(Gravity.CENTER);
        stateLine.setPadding(0, dp(20), 0, dp(8));
        root.addView(stateLine);

        detailLine = new TextView(this);
        detailLine.setTextColor(0xFF888888);
        detailLine.setTextSize(15);
        detailLine.setPadding(0, 0, 0, dp(20));
        root.addView(detailLine);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(actions);

        actions.addView(makeButton("修改密码", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LockCoordinator.beginChangePin(MainActivity.this);
                // Get out of the way so the keypad overlay is what the remote talks to.
                finish();
            }
        }));

        actions.addView(makeButton("立即锁定", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LockCoordinator.lockNow(MainActivity.this, "manual");
                finish();
            }
        }));

        actions.addView(makeButton("悬浮窗权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT < 23) return;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    DiagLog.e("overlay settings page unavailable", t);
                }
            }
        }));

        actions.addView(makeButton("通知监听授权", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (ActivityNotFoundException e) {
                    detailLine.setText("此固件没有通知监听设置页，请用 ADB 授权：\n"
                            + listenerCommand());
                } catch (Throwable t) {
                    DiagLog.e("listener settings page failed", t);
                }
            }
        }));

        actions.addView(makeButton("刷新状态", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LockCoordinator.install(this);
        refresh();
    }

    private void refresh() {
        int state = LockStateManager.get(this);
        boolean unlocked = state == LockStateManager.UNLOCKED;
        boolean pinSet = PinManager.hasPin(this);
        boolean overlayOk = LockCoordinator.hasOverlayPermission(this);
        boolean listenerOk = LockCoordinator.isListenerEnabled(this);

        String label;
        int color;
        switch (state) {
            case LockStateManager.UNLOCKED:
                label = "当前状态：已解锁";
                color = 0xFF4CAF50;
                break;
            case LockStateManager.RECOVERY_REQUIRED:
                label = "当前状态：防护失效";
                color = 0xFFFF6B6B;
                break;
            case LockStateManager.LOCKED:
            default:
                label = "当前状态：已锁定";
                color = 0xFFFFC107;
                break;
        }
        stateLine.setText(label);
        stateLine.setTextColor(color);

        StringBuilder sb = new StringBuilder();
        sb.append("密码：").append(pinSet ? "已设置" : "未设置（首次解锁时会要求设置）").append('\n');
        sb.append("悬浮窗权限：").append(overlayOk ? "已授权" : "未授权 — 锁无法显示").append('\n');
        sb.append("通知监听：").append(listenerOk ? "已授权" : "未授权 — 待机后无法复活").append('\n');
        if (!listenerOk) {
            sb.append('\n').append("请用 ADB 执行一次：\n").append(listenerCommand()).append('\n');
        }
        long wait = PinManager.millisUntilNextAttempt(this);
        if (wait > 0) {
            sb.append('\n').append("密码错误次数过多，还需等待 ")
                    .append((wait + 999) / 1000).append(" 秒");
        }
        sb.append('\n').append("锁定原因：").append(LockStateManager.reason(this));
        detailLine.setText(sb.toString());

        // No action here may change access without the PIN; while locked, show none at all.
        actions.setVisibility(unlocked ? View.VISIBLE : View.GONE);
    }

    private String listenerCommand() {
        return "adb shell cmd notification allow_listener "
                + getPackageName() + "/" + RevivalListener.class.getName();
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(18);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
