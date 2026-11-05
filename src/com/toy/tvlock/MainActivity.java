package com.toy.tvlock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111111"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("电视锁");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#AAAAAA"));
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 40);
        root.addView(status);

        root.addView(makeBtn("立即锁定屏幕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServiceWith("LOCK");
            }
        }));

        root.addView(makeBtn("解除锁定", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServiceWith("UNLOCK");
            }
        }));

        root.addView(makeBtn("开启悬浮窗权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(i);
                    } catch (Throwable t) {
                        // 固件没有这个页面
                    }
                }
            }
        }));

        setContentView(root);
    }

    private Button makeBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(20);
        b.setOnClickListener(l);
        return b;
    }

    private void startServiceWith(String action) {
        Intent s = new Intent(this, LockService.class);
        s.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(s);
        } else {
            startService(s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences(LockService.PREFS, MODE_PRIVATE);
        String pin = sp.getString(LockService.KEY_PIN, LockService.DEFAULT_PIN);
        boolean canOverlay = true;
        if (Build.VERSION.SDK_INT >= 23) {
            canOverlay = Settings.canDrawOverlays(this);
        }
        status.setText("当前密码：" + pin
                + "\n悬浮窗权限：" + (canOverlay ? "已授权" : "未授权（必须授权否则锁不住）"));
    }
}
