package ai.openpush.android.liveupdates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OpenPushLiveUpdateStateTests {
    private Context context;
    private OpenPushLiveUpdateState state;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(OpenPushLiveUpdateState.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        state = new OpenPushLiveUpdateState(context);
    }

    @Test
    public void persistsAcrossStateInstancesAndGatesDismissedUpdates() throws Exception {
        OpenPushLiveUpdatePayload start = payload("start", "activity-1", 1);
        assertEquals(OpenPushLiveUpdateState.Decision.PROCEED, state.prepare(start, 100));
        int id = state.notificationId(start.getInstanceId());
        state.recordDisplayed(start, id, 10_000);
        state.markDismissed(start.getInstanceId());

        OpenPushLiveUpdateState restarted = new OpenPushLiveUpdateState(context);
        OpenPushLiveUpdateState.Record record = restarted.get(start.getInstanceId());
        assertNotNull(record);
        assertTrue(record.isDismissed());
        assertEquals(OpenPushLiveUpdateState.Decision.DISMISSED,
                restarted.prepare(payload("update", "activity-1", 2), 200));

        assertEquals(OpenPushLiveUpdateState.Decision.PROCEED,
                restarted.prepare(payload("start", "activity-1", 3), 300));
        // A replacement start is transactional: prior dismissal survives until display succeeds.
        assertTrue(restarted.get("activity-1").isDismissed());
        OpenPushLiveUpdatePayload replacement = payload("start", "activity-1", 3);
        restarted.recordDisplayed(replacement, record.getNotificationId(), 10_000);
        assertFalse(restarted.get("activity-1").isDismissed());
        assertEquals(Long.valueOf(3), restarted.get("activity-1").getLastSequence());

        OpenPushLiveUpdatePayload unsequencedRestart = payloadWithoutSequence(
                "start", "activity-1");
        restarted.recordDisplayed(unsequencedRestart, record.getNotificationId(), 10_000);
        assertNull(restarted.get("activity-1").getLastSequence());
    }

    @Test
    public void rejectsDuplicateAndOutOfOrderSequences() throws Exception {
        OpenPushLiveUpdatePayload start = payload("start", "activity-2", 8);
        state.prepare(start, 0);
        int id = state.notificationId(start.getInstanceId());
        state.recordDisplayed(start, id, 1_000);

        assertEquals(OpenPushLiveUpdateState.Decision.STALE,
                state.prepare(payload("update", "activity-2", 8), 100));
        assertEquals(OpenPushLiveUpdateState.Decision.STALE,
                state.prepare(payload("update", "activity-2", 7), 100));
        assertEquals(OpenPushLiveUpdateState.Decision.PROCEED,
                state.prepare(payload("update", "activity-2", 9), 100));
    }

    @Test
    public void distinctInstancesReceiveDistinctStableIds() {
        int first = state.notificationId("first");
        int second = state.notificationId("second");
        assertNotEquals(first, second);
        assertEquals(first, new OpenPushLiveUpdateState(context).notificationId("first"));
        assertEquals(first, OpenPushLiveUpdateState.stableNotificationId(
                context.getPackageName(), "first"));
    }

    @Test
    public void probesWhenActiveIdsCollide() {
        SharedPreferences preferences = context.getSharedPreferences(
                OpenPushLiveUpdateState.PREFERENCES_NAME, Context.MODE_PRIVATE);
        OpenPushLiveUpdateState colliding = new OpenPushLiveUpdateState(
                context.getPackageName(), preferences, (packageName, instanceId) -> 42);

        assertEquals(42, colliding.notificationId("first"));
        assertEquals(43, colliding.notificationId("second"));
        assertEquals(42, colliding.notificationId("first"));
    }

    @Test
    public void prunesTimeoutsAndRemovesEndedState() throws Exception {
        OpenPushLiveUpdatePayload start = payload("start", "expiring", 1);
        state.prepare(start, 0);
        state.recordDisplayed(start, state.notificationId("expiring"), 500);

        assertEquals(0, state.pruneExpired(499));
        assertNotNull(state.get("expiring"));
        assertEquals(1, state.pruneExpired(500));
        assertNull(state.get("expiring"));

        OpenPushLiveUpdatePayload another = payload("start", "ended", 1);
        state.prepare(another, 0);
        state.recordDisplayed(another, state.notificationId("ended"), 1_000);
        state.remove("ended");
        assertNull(state.get("ended"));
    }

    @Test
    public void removesCorruptNonStringStateInsteadOfThrowing() {
        SharedPreferences preferences = context.getSharedPreferences(
                OpenPushLiveUpdateState.PREFERENCES_NAME, Context.MODE_PRIVATE);
        state.notificationId("corrupt");
        String recordKey = preferences.getAll().keySet().iterator().next();
        preferences.edit().putInt(recordKey, 7).commit();

        assertNull(state.get("corrupt"));
        assertFalse(preferences.contains(recordKey));
    }

    private static OpenPushLiveUpdatePayload payload(String event, String instance, long sequence)
            throws Exception {
        String updates = "update".equals(event)
                ? ",\"event_updates\":{\"current_progress\":50}"
                : "";
        Map<String, String> data = new HashMap<>();
        data.put(OpenPushLiveUpdatePayload.DATA_KEY,
                "{\"key\":\"progress\",\"instance_id\":\"" + instance + "\","
                        + "\"event\":\"" + event + "\",\"sequence\":" + sequence + updates + "}");
        return OpenPushLiveUpdatePayload.parse(data, null);
    }

    private static OpenPushLiveUpdatePayload payloadWithoutSequence(String event, String instance)
            throws Exception {
        Map<String, String> data = new HashMap<>();
        data.put(OpenPushLiveUpdatePayload.DATA_KEY,
                "{\"key\":\"progress\",\"instance_id\":\"" + instance + "\","
                        + "\"event\":\"" + event + "\"}");
        return OpenPushLiveUpdatePayload.parse(data, null);
    }
}
