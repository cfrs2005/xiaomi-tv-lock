package com.toy.tvlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * The lock screen window itself: a full-screen overlay with a 3x4 keypad.
 *
 * <p>Split out of {@link LockCoordinator} so the coordinator can own the state machine and retry
 * policy while this class owns nothing but pixels and digit input.
 *
 * <p>Notes carried over from the on-device experiments and worth not re-learning:
 *
 * <ul>
 *   <li>No {@code FLAG_NOT_FOCUSABLE}: without focus the remote's D-pad does nothing.</li>
 *   <li>{@code FLAG_ALT_FOCUSABLE_IM} plus a hidden soft keyboard, otherwise an IME window can
 *       cover the lock.</li>
 *   <li>{@code FLAG_KEEP_SCREEN_ON} is deliberately <b>not</b> used: it would stop the TV from
 *       ever entering standby while locked.</li>
 * </ul>
 */
final class LockOverlay {

    static final int MODE_UNLOCK = 0;
    /** First run: no PIN yet, ask for one twice. */
    static final int MODE_SETUP = 1;
    /** Change from the settings screen: current PIN, then the new one twice. */
    static final int MODE_CHANGE = 2;

    interface Listener {
        void onUnlocked();

        void onPinChanged();

        /** Back pressed while the change-PIN flow was running. */
        void onCancelRequested();
    }

    private static final int[] BACKGROUNDS = {
            R.drawable.lock_bg_1,
            R.drawable.lock_bg_2,
            R.drawable.lock_bg_3
    };
    private static final java.util.Random RANDOM = new java.util.Random();
    /** Static: consecutive lock sessions must not show the same picture twice. */
    private static int lastBackground = -1;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder input = new StringBuilder();
    /** Resolved once: the layout owns the palette, the code only switches between three states. */
    private final int colorHint;
    private final int colorOk;
    private final int colorErr;

    private WindowManager windowManager;
    private View root;
    private TextView title;
    private TextView hint;
    private View[] dots;
    private boolean attached;

    private int mode = MODE_UNLOCK;
    private int step;
    private String pending;

    LockOverlay(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.colorHint = context.getColor(R.color.text_muted);
        this.colorOk = context.getColor(R.color.state_ok);
        this.colorErr = context.getColor(R.color.state_err);
    }

    void setMode(int mode) {
        this.mode = mode;
        this.step = 0;
        this.pending = null;
        if (attached) {
            resetInput();
            applyModeText();
        }
    }

    boolean isAttached() {
        return attached;
    }

    /** Throws when the window cannot be added; the caller decides whether to retry. */
    void show() {
        if (attached) return;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) throw new IllegalStateException("no WindowManager");

        root = LayoutInflater.from(context).inflate(R.layout.lock_overlay, null);
        // One picture per lock session; the XML default stays as the fallback.
        if (BACKGROUNDS.length > 0) {
            root.setBackgroundResource(pickBackground());
        }
        if (root instanceof LockRootLayout) {
            ((LockRootLayout) root).setBackHandler(new LockRootLayout.BackHandler() {
                @Override
                public boolean onBackPressed() {
                    // Back means "leave the change flow". In every other mode it does nothing,
                    // which is why the lock cannot be dismissed with the remote.
                    if (mode == MODE_CHANGE) {
                        listener.onCancelRequested();
                        return true;
                    }
                    return false;
                }
            });
        }

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0;
        lp.y = 0;

        // Throws (BadTokenException / SecurityException) if the overlay permission was revoked
        // between the check and this call. Let it propagate: failing loudly is the point.
        windowManager.addView(root, lp);

        title = (TextView) root.findViewById(R.id.title);
        hint = (TextView) root.findViewById(R.id.hint);
        dots = new View[]{
                root.findViewById(R.id.dot0),
                root.findViewById(R.id.dot1),
                root.findViewById(R.id.dot2),
                root.findViewById(R.id.dot3)
        };

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

        root.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetInput();
            }
        });
        root.findViewById(R.id.btnDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.length() > 0) {
                    input.setLength(input.length() - 1);
                    refreshDots();
                }
            }
        });

        View first = root.findViewById(R.id.btn5);
        if (first != null) first.requestFocus();

        attached = true;
        resetInput();
        applyModeText();
    }

    /** A different picture each time the lock comes up, never the same one twice in a row. */
    private int pickBackground() {
        if (BACKGROUNDS.length == 1) return BACKGROUNDS[0];
        int pick;
        do {
            pick = RANDOM.nextInt(BACKGROUNDS.length);
        } while (pick == lastBackground);
        lastBackground = pick;
        DiagLog.i("background #" + (pick + 1));
        return BACKGROUNDS[pick];
    }

    void hide() {
        if (!attached) return;
        attached = false;
        handler.removeCallbacksAndMessages(null);
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable t) {
                // Already gone (window leaked out with the process, token invalid, ...).
            }
        }
        root = null;
        windowManager = null;
        dots = null;
        title = null;
        hint = null;
    }

    /**
     * Re-evaluates the texts without touching the input. Called when the coordinator re-attaches a
     * window that was kept across standby, where the visual state is already correct.
     */
    void refresh() {
        if (!attached) return;
        applyModeText();
        refreshDots();
    }

    private void bind(int id, final char digit) {
        Button b = (Button) root.findViewById(id);
        if (b == null) return;
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (input.length() >= 4) return;
                input.append(digit);
                refreshDots();
                if (input.length() == 4) submit();
            }
        });
    }

    private void submit() {
        final String entered = input.toString();
        switch (mode) {
            case MODE_SETUP:
                handleSetup(entered);
                break;
            case MODE_CHANGE:
                handleChange(entered);
                break;
            case MODE_UNLOCK:
            default:
                handleUnlock(entered);
                break;
        }
    }

    private void handleUnlock(String entered) {
        int result = PinManager.verify(context, entered);
        switch (result) {
            case PinManager.OK:
                flash(colorOk, "已解锁");
                resetInput();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        listener.onUnlocked();
                    }
                }, 250);
                break;
            case PinManager.THROTTLED:
                resetInput();
                showThrottled();
                break;
            case PinManager.NOT_SET:
                // No PIN yet (first run, or the record was wiped): set one now.
                resetInput();
                mode = MODE_SETUP;
                step = 0;
                applyModeText();
                break;
            case PinManager.ERROR:
                resetInput();
                flash(colorErr, "校验失败，请重试");
                break;
            case PinManager.WRONG:
            default:
                resetInput();
                int fails = PinManager.failedAttempts(context);
                int free = BackoffPolicy.freeAttemptsLeft(fails);
                if (free > 0) {
                    flash(colorErr, "密码错误，还可尝试 " + free + " 次");
                } else {
                    showThrottled();
                }
                break;
        }
    }

    private void handleSetup(String entered) {
        if (step == 0) {
            pending = entered;
            step = 1;
            resetInput();
            hint.setTextColor(colorHint);
            hint.setText("请再次输入同一密码");
            return;
        }
        if (entered.equals(pending)) {
            try {
                PinManager.setPin(context, entered);
            } catch (Throwable t) {
                DiagLog.e("set pin failed", t);
                pending = null;
                step = 0;
                resetInput();
                flash(colorErr, "设置失败，请重试");
                return;
            }
            pending = null;
            step = 0;
            resetInput();
            flash(colorOk, "密码已设置");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    listener.onPinChanged();
                }
            }, 250);
        } else {
            pending = null;
            step = 0;
            resetInput();
            flash(colorErr, "两次不一致，请重新设置");
        }
    }

    private void handleChange(String entered) {
        if (step == 0) {
            if (PinManager.verify(context, entered) == PinManager.OK) {
                step = 1;
                resetInput();
                hint.setTextColor(colorHint);
                hint.setText("请输入新密码");
            } else {
                resetInput();
                int fails = PinManager.failedAttempts(context);
                int free = BackoffPolicy.freeAttemptsLeft(fails);
                flash(colorErr, free > 0
                        ? "原密码错误，还可尝试 " + free + " 次"
                        : "尝试过多，请稍后再试");
            }
            return;
        }
        if (step == 1) {
            pending = entered;
            step = 2;
            resetInput();
            hint.setTextColor(colorHint);
            hint.setText("请再次输入新密码");
            return;
        }
        if (entered.equals(pending)) {
            try {
                PinManager.setPin(context, entered);
            } catch (Throwable t) {
                DiagLog.e("set pin failed", t);
                pending = null;
                step = 1;
                resetInput();
                flash(colorErr, "设置失败，请重试");
                return;
            }
            pending = null;
            step = 0;
            resetInput();
            flash(colorOk, "密码已更新");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    listener.onPinChanged();
                }
            }, 250);
        } else {
            pending = null;
            step = 1;
            resetInput();
            flash(colorErr, "两次不一致，请重新输入新密码");
        }
    }

    private void applyModeText() {
        if (!attached || title == null || hint == null) return;
        // The hero line above owns the attention; this row stays muted on purpose.
        title.setTextColor(colorHint);
        hint.setTextColor(colorHint);
        switch (mode) {
            case MODE_SETUP:
                title.setText("第一次使用");
                hint.setText("设一个 4 位密码，需要输入两次");
                break;
            case MODE_CHANGE:
                title.setText("修改密码");
                hint.setText(step == 0
                        ? "先输入当前密码（返回键取消）"
                        : "输入新密码（返回键取消）");
                break;
            case MODE_UNLOCK:
            default:
                title.setText("欢迎回家");
                long wait = PinManager.millisUntilNextAttempt(context);
                if (wait > 0) {
                    showThrottled();
                } else if (!PinManager.hasPin(context)) {
                    mode = MODE_SETUP;
                    step = 0;
                    applyModeText();
                } else {
                    hint.setText("输入 4 位密码");
                }
                break;
        }
    }

    private void showThrottled() {
        long wait = PinManager.millisUntilNextAttempt(context);
        if (wait <= 0) {
            hint.setTextColor(colorHint);
            hint.setText("请输入 4 位密码解锁");
            return;
        }
        long seconds = Math.max(1, (wait + 999) / 1000);
        hint.setTextColor(colorErr);
        hint.setText("尝试过多，请等待 " + seconds + " 秒");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (attached && mode == MODE_UNLOCK) showThrottled();
            }
        }, 1000);
    }

    private void flash(int color, String text) {
        hint.setTextColor(color);
        hint.setText(text);
    }

    private void resetInput() {
        input.setLength(0);
        refreshDots();
    }

    private void refreshDots() {
        if (dots == null) return;
        for (int i = 0; i < dots.length; i++) {
            if (dots[i] == null) continue;
            dots[i].setBackgroundResource(
                    i < input.length() ? R.drawable.dot_on : R.drawable.dot_off);
        }
    }
}
