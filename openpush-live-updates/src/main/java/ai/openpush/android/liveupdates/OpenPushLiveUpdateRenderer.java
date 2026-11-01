package ai.openpush.android.liveupdates;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

/** Builds one allow-listed progress notification without accepting remote Android components. */
final class OpenPushLiveUpdateRenderer {
    static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    private final Context context;
    private final NotificationManager manager;

    OpenPushLiveUpdateRenderer(Context context, NotificationManager manager) {
        this.context = context;
        this.manager = manager;
    }

    Notification build(
            OpenPushLiveUpdatePayload payload,
            int notificationId,
            int requestedSmallIcon,
            PendingIntent contentIntent,
            long timeoutMillis) {
        if (!"progress".equals(payload.getKey())) {
            return null;
        }
        ensureChannel();

        JSONObject attributes = payload.getEventAttributes();
        JSONObject updates = payload.getEventUpdates();
        String title = boundedText(attributes.optString("title", appLabel()), appLabel(), 200);
        String body = boundedText(
                updates.optString("body", attributes.optString("body", updates.optString("status", ""))),
                "",
                500);
        int maximum = boundedInteger(updates.opt("max_progress"), 100, 1, 1_000_000);
        int current = boundedInteger(updates.opt("current_progress"), 0, 0, maximum);
        String shortText = boundedText(
                updates.optString("short_text", attributes.optString("short_text", "")),
                "",
                7);

        PendingIntent dismissIntent = dismissalIntent(payload.getInstanceId(), notificationId);
        PendingIntent safeContentIntent = contentIntent != null
                ? contentIntent
                : defaultContentIntent(notificationId);

        Notification.Builder builder = newBuilder()
                .setSmallIcon(resolveSmallIcon(requestedSmallIcon))
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(dismissIntent)
                .setContentIntent(safeContentIntent)
                .addAction(
                        R.drawable.openpush_ic_unpin,
                        context.getString(R.string.openpush_live_update_unpin),
                        dismissIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(timeoutMillis);
        }

        if (Build.VERSION.SDK_INT >= 36) {
            Api36.applyProgressStyle(builder, maximum, current, shortText);
            Bundle promotion = new Bundle();
            promotion.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
            builder.addExtras(promotion);
        } else {
            builder.setProgress(maximum, current, false)
                    .setStyle(new Notification.BigTextStyle().bigText(body));
        }
        return builder.build();
    }

    /** Below API 26 there are no channels: the builder carries the quiet, low priority instead. */
    @SuppressWarnings("deprecation")
    private Notification.Builder newBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, OpenPushLiveUpdates.CHANNEL_ID);
        }
        return new Notification.Builder(context).setPriority(Notification.PRIORITY_LOW);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        createChannelIfMissing();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createChannelIfMissing() {
        NotificationChannel existing = manager.getNotificationChannel(OpenPushLiveUpdates.CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                OpenPushLiveUpdates.CHANNEL_ID,
                "Live updates",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ongoing, time-sensitive progress updates");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private PendingIntent dismissalIntent(String instanceId, int notificationId) {
        Intent intent = new Intent(context, OpenPushLiveUpdateDismissReceiver.class)
                .setAction(OpenPushLiveUpdateDismissReceiver.ACTION_DISMISS)
                .putExtra(OpenPushLiveUpdateDismissReceiver.EXTRA_INSTANCE_ID, instanceId)
                .putExtra(OpenPushLiveUpdateDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId);
        return PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent defaultContentIntent(int notificationId) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) {
            launch = new Intent();
        }
        launch.setPackage(context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                context,
                notificationId,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private int resolveSmallIcon(int requested) {
        if (requested != 0) {
            return requested;
        }
        int applicationIcon = context.getApplicationInfo().icon;
        return applicationIcon != 0 ? applicationIcon : android.R.drawable.ic_dialog_info;
    }

    private String appLabel() {
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        return label == null || label.length() == 0 ? context.getPackageName() : label.toString();
    }

    private static String boundedText(String value, String fallback, int maximumLength) {
        if (value == null || value.length() == 0) {
            return fallback;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static int boundedInteger(Object value, int fallback, int minimum, int maximum) {
        if (!(value instanceof Number)) {
            return fallback;
        }
        double numeric = ((Number) value).doubleValue();
        if (!Double.isFinite(numeric)) {
            return fallback;
        }
        return (int) Math.max(minimum, Math.min(maximum, Math.round(numeric)));
    }

    /** Keeps API-36 types and calls out of verifier paths on older devices. */
    @TargetApi(36)
    private static final class Api36 {
        private Api36() {}

        static void applyProgressStyle(
                Notification.Builder builder, int maximum, int current, String shortText) {
            Notification.ProgressStyle style = new Notification.ProgressStyle()
                    .addProgressSegment(new Notification.ProgressStyle.Segment(maximum))
                    .setProgress(current)
                    .setStyledByProgress(true);
            builder.setStyle(style);
            if (!shortText.isEmpty()) {
                builder.setShortCriticalText(shortText);
            }
        }
    }
}
