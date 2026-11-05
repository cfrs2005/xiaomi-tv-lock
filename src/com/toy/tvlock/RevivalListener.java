package com.toy.tvlock;

import android.service.notification.NotificationListenerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The revival anchor, and the only long-lived component in the app.
 *
 * <p>Xiaomi's firmware force-stops every third-party app when the TV enters standby. Accessibility
 * services are dropped from the enabled list for good, alarms are cleared with the stopped state,
 * and a plain foreground service is simply killed. Notification listeners are the exception: the
 * approval survives and the system binds the listener again shortly after the process dies, because
 * the binding is held by system_server rather than by the app.
 *
 * <p>So this service exists to be rebound. It reads no notification: no {@code onNotificationPosted}
 * override, no access to content. Everything it does is hand control to {@link LockCoordinator},
 * which owns the state machine and the lock window.
 *
 * <p>{@code directBootAware} matters: the binding can arrive before the user is unlocked, and the
 * lock must already be armed at that point.
 */
public class RevivalListener extends NotificationListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.init(this);
        DiagLog.i("listener created");
        LockCoordinator.install(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        DiagLog.i("listener connected (revived or first bind)");
        LockCoordinator.onHostConnected(this);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // Unbound on purpose or about to be killed: fail closed rather than hope.
        DiagLog.i("listener disconnected");
        LockCoordinator.onHostDisconnected(this);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        LockCoordinator c = LockCoordinator.peek();
        if (c == null) {
            writer.println("tvlock: coordinator not started in this process");
            writer.println("state=" + LockStateManager.label(LockStateManager.get(this))
                    + " reason=" + LockStateManager.reason(this));
            writer.print(DiagLog.dump());
            return;
        }
        writer.print(c.report());
    }
}
