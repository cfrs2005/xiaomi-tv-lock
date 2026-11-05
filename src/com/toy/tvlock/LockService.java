package com.toy.tvlock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class LockService extends Service {

    public static final String PREFS = "tvlock";
    public static final String KEY_PIN = "pin";
    public static final String DEFAULT_PIN = "2025";
    private static final String CHANNEL = "tvlock_fg";
    private static final int NOTIF_ID = 1001;

    private WindowManager wm;
    private View overlay;
    private final StringBuilder input = new StringBuilder();
    private TextView[] dots = new TextView[4];
    private TextView hint;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean screenReceiverRegistered = false;
    private PowerManager.WakeLock wakeLock;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (overlay == null) showOverlay();
                    }
                }, 600);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            // 忽略：部分固件对前台服务有限制，锁层仍然要上
        }
        registerScreenReceiver();
        scheduleNextAlarm();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlock:keeper");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            // 拿不到唤醒锁不影响锁层生效
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                showOverlay();
            }
        }, 800);
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
            screenReceiverRegistered = true;
        } catch (Throwable t) {
            // 固件限制注册时不崩溃
        }
    }

    private void scheduleNextAlarm() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 200, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + 5000;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            // 闹钟设不上不影响其他机制
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "电视锁", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("电视锁运行中")
                .setContentText("屏幕已锁定")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true);
        return b.build();
    }

    private int getPin() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        return Integer.parseInt(sp.getString(KEY_PIN, DEFAULT_PIN));
    }

    private void showOverlay() {
        if (overlay != null) return;
        input.setLength(0);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        overlay = LayoutInflater.from(this).inflate(R.layout.lock_overlay, null);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;
        lp.screenBrightness = 0.6f;

        try {
            wm.addView(overlay, lp);
        } catch (Throwable t) {
            stopSelf();
            return;
        }

        dots[0] = (TextView) overlay.findViewById(R.id.dot0);
        dots[1] = (TextView) overlay.findViewById(R.id.dot1);
        dots[2] = (TextView) overlay.findViewById(R.id.dot2);
        dots[3] = (TextView) overlay.findViewById(R.id.dot3);
        hint = (TextView) overlay.findViewById(R.id.hint);

        bind(R.id.btn0, '0');
        bind(R.id.btn1, '1');
        bind(R.id.btn2, '2');
        bind(R.id.btn3, '3');
        bind(R.id.btn4, '4');
        bind(R.id.btn5, '5');
        bind(R.id.btn6, '6');
        bind(R.id.btn7, '7');
        bind(R.id.btn8, '8');
        bind(R.id.btn9, '9');

        overlay.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setLength(0);
                refreshDots();
            }
        });
        overlay.findViewById(R.id.btnDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.length() > 0) {
                    input.setLength(input.length() - 1);
                    refreshDots();
                }
            }
        });

        View first = overlay.findViewById(R.id.btn5);
        if (first != null) first.requestFocus();
    }

    private void bind(int id, final char digit) {
        Button b = (Button) overlay.findViewById(id);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.length() >= 4) return;
                input.append(digit);
                refreshDots();
                if (input.length() == 4) {
                    checkPin();
                }
            }
        });
    }

    private void refreshDots() {
        for (int i = 0; i < 4; i++) {
            dots[i].setText(i < input.length() ? "●" : "○");
        }
    }

    private void checkPin() {
        int entered = Integer.parseInt(input.toString());
        if (entered == getPin()) {
            hint.setTextColor(Color.parseColor("#4CAF50"));
            hint.setText("已解锁");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    removeOverlay();
                }
            }, 300);
        } else {
            hint.setTextColor(Color.parseColor("#FF6B6B"));
            hint.setText("密码错误");
            input.setLength(0);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshDots();
                    hint.setText("");
                }
            }, 800);
        }
    }

    private void removeOverlay() {
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Throwable t) {
                // 已经移除
            }
            overlay = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("LOCK".equals(action)) {
            if (overlay == null) showOverlay();
        } else if ("UNLOCK".equals(action)) {
            removeOverlay();
        } else if ("TICK".equals(action)) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm != null && pm.isInteractive();
            if (screenOn && overlay == null) {
                showOverlay();
            }
        }
        scheduleNextAlarm();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable t) {
                // 已经反注册
            }
            screenReceiverRegistered = false;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable t) {
                // 已经释放
            }
        }
        super.onDestroy();
    }
}
