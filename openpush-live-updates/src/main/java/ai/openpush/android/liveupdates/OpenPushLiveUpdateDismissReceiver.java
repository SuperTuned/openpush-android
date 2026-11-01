package ai.openpush.android.liveupdates;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Records a user dismissal/Unpin so later FCM updates cannot resurrect the instance. */
public final class OpenPushLiveUpdateDismissReceiver extends BroadcastReceiver {
    public static final String ACTION_DISMISS =
            "ai.openpush.android.liveupdates.action.DISMISS";
    public static final String EXTRA_INSTANCE_ID =
            "ai.openpush.android.liveupdates.extra.INSTANCE_ID";
    public static final String EXTRA_NOTIFICATION_ID =
            "ai.openpush.android.liveupdates.extra.NOTIFICATION_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_DISMISS.equals(intent.getAction())) {
            return;
        }
        String instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID);
        if (instanceId == null || instanceId.length() == 0) {
            return;
        }
        OpenPushLiveUpdateState state = new OpenPushLiveUpdateState(context);
        OpenPushLiveUpdateState.Record record = state.get(instanceId);
        if (record == null) {
            return;
        }
        state.markDismissed(instanceId);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(record.getNotificationId());
        }
    }
}
