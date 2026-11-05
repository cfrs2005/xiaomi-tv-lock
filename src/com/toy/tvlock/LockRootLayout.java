package com.toy.tvlock;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * Root view of the lock screen, existing only to catch the Back key.
 *
 * <p>A WindowManager window has no activity behind it, so an unhandled Back key normally goes
 * nowhere — which is the behaviour we want while locked. The change-PIN flow is the exception: it
 * runs while the TV is unlocked, and without an exit the viewer would be trapped under an overlay
 * with no way out. Back cancels that flow and nothing else.
 *
 * <p>Key events reach this view because {@code ViewGroup.dispatchKeyEvent} falls back to the
 * group's own handling when the focused child does not consume the event.
 */
public class LockRootLayout extends FrameLayout {

    interface BackHandler {
        /** @return true when the key was consumed. */
        boolean onBackPressed();
    }

    private BackHandler backHandler;

    public LockRootLayout(Context context) {
        super(context);
    }

    public LockRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LockRootLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setBackHandler(BackHandler handler) {
        this.backHandler = handler;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && backHandler != null) {
                return backHandler.onBackPressed();
            }
            // Swallow both edges even when nothing handles it: never let Back leak downward.
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
