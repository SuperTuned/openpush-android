package ai.openpush.android.liveupdates;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.util.Map;

/** Dependency-free entry point for Android Live Updates. */
public final class OpenPushLiveUpdates {
    public static final String CHANNEL_ID = "openpush_live_updates";
    public static final long DEFAULT_TIMEOUT_MILLIS = 8L * 60L * 60L * 1000L + 15L * 60L * 1000L;

    public enum Capability {
        FALLBACK_ONGOING,
        ELIGIBLE,
        PROMOTION_DISABLED
    }

    interface TransactionHook {
        void afterAdmission(OpenPushLiveUpdatePayload payload);
    }

    static volatile TransactionHook transactionHook;

    private OpenPushLiveUpdates() {}

    /** Handles a raw FCM data map using safe host defaults. */
    public static OpenPushLiveUpdateResult handle(
            Context context, Map<String, String> data, String collapseKey) {
        return handle(context, data, collapseKey, 0, null);
    }

    /**
     * Handles a raw FCM data map with host-owned visual/click configuration. Neither value can
     * be supplied remotely: the icon is a local resource and the intent is created by the host.
     */
    public static OpenPushLiveUpdateResult handle(
            Context context,
            Map<String, String> data,
            String collapseKey,
            int smallIconResource,
            PendingIntent contentIntent) {
        if (context == null || data == null || !data.containsKey(OpenPushLiveUpdatePayload.DATA_KEY)) {
            return OpenPushLiveUpdateResult.NOT_LIVE_UPDATE;
        }

        final OpenPushLiveUpdatePayload payload;
        try {
            payload = OpenPushLiveUpdatePayload.parse(data, collapseKey);
        } catch (OpenPushLiveUpdatePayload.InvalidPayloadException invalid) {
            return OpenPushLiveUpdateResult.INVALID;
        }

        // Admission, notification side effects and durable commit are one process transaction.
        // Releasing the state monitor between prepare and recordDisplayed let two FCM threads both
        // pass against the same sequence, then commit in reverse order; it also let an admitted
        // update recreate an instance after a concurrent end removed it.
        synchronized (OpenPushLiveUpdateState.transactionLock()) {
            Context application = context.getApplicationContext();
            OpenPushLiveUpdateState state = new OpenPushLiveUpdateState(application);
            long now = System.currentTimeMillis();
            state.pruneExpired(now);
            OpenPushLiveUpdateState.Record existing = state.get(payload.getInstanceId());

            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.UPDATE && existing == null) {
                return OpenPushLiveUpdateResult.INVALID;
            }

            OpenPushLiveUpdateState.Decision decision = state.prepare(payload, now);
            if (decision == OpenPushLiveUpdateState.Decision.STALE) {
                return OpenPushLiveUpdateResult.STALE;
            }
            if (decision != OpenPushLiveUpdateState.Decision.PROCEED) {
                return OpenPushLiveUpdateResult.DISMISSED;
            }
            TransactionHook hook = transactionHook;
            if (hook != null) {
                hook.afterAdmission(payload);
            }

            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null) {
                return OpenPushLiveUpdateResult.INVALID;
            }

            int notificationId = existing == null
                    ? state.notificationId(payload.getInstanceId())
                    : existing.getNotificationId();
            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.END) {
                manager.cancel(notificationId);
                state.remove(payload.getInstanceId());
                return OpenPushLiveUpdateResult.ENDED;
            }

            OpenPushLiveUpdateRenderer renderer = new OpenPushLiveUpdateRenderer(application, manager);
            Notification notification = renderer.build(
                    payload,
                    notificationId,
                    smallIconResource,
                    contentIntent,
                    DEFAULT_TIMEOUT_MILLIS);
            if (notification == null) {
                removeNewReservation(state, existing, payload);
                return OpenPushLiveUpdateResult.INVALID;
            }

            try {
                manager.notify(notificationId, notification);
                state.recordDisplayed(payload, notificationId, now + DEFAULT_TIMEOUT_MILLIS);
                return OpenPushLiveUpdateResult.DISPLAYED;
            } catch (RuntimeException postingFailure) {
                removeNewReservation(state, existing, payload);
                return OpenPushLiveUpdateResult.INVALID;
            }
        }
    }

    private static void removeNewReservation(
            OpenPushLiveUpdateState state,
            OpenPushLiveUpdateState.Record existing,
            OpenPushLiveUpdatePayload payload) {
        if (existing == null) {
            state.remove(payload.getInstanceId());
        }
    }

    public static Capability capability(Context context) {
        if (Build.VERSION.SDK_INT < 36) {
            return Capability.FALLBACK_ONGOING;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && Api36.canPostPromotedNotifications(manager)
                ? Capability.ELIGIBLE
                : Capability.PROMOTION_DISABLED;
    }

    public static boolean hasPromotableCharacteristics(Notification notification) {
        return notification != null
                && Build.VERSION.SDK_INT >= 36
                && Api36.hasPromotableCharacteristics(notification);
    }

    /** Opens the app's promotion setting when the device exposes a matching settings screen. */
    public static boolean openPromotionSettings(Context context) {
        if (Build.VERSION.SDK_INT < 36) {
            return false;
        }
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager packageManager = context.getPackageManager();
        if (intent.resolveActivity(packageManager) == null) {
            return false;
        }
        context.startActivity(intent);
        return true;
    }

    /** Keeps API-36 references out of verifier paths on older devices. */
    @TargetApi(36)
    private static final class Api36 {
        private Api36() {}

        static boolean canPostPromotedNotifications(NotificationManager manager) {
            return manager.canPostPromotedNotifications();
        }

        static boolean hasPromotableCharacteristics(Notification notification) {
            return notification.hasPromotableCharacteristics();
        }
    }
}
