package ai.openpush.android.liveupdates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OpenPushLiveUpdatesTests {
    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        OpenPushLiveUpdates.transactionHook = null;
        context = ApplicationProvider.getApplicationContext();
        manager = context.getSystemService(NotificationManager.class);
        manager.cancelAll();
        context.getSharedPreferences(OpenPushLiveUpdateState.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void ordinaryAndInvalidPayloadsNeverPost() {
        assertEquals(OpenPushLiveUpdateResult.NOT_LIVE_UPDATE,
                OpenPushLiveUpdates.handle(context, Collections.emptyMap(), null));
        assertEquals(OpenPushLiveUpdateResult.INVALID,
                OpenPushLiveUpdates.handle(context, data("not-json"), null));
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void createsQuietFallbackChannelAndOngoingNotification() {
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("trip-1", 1), "trip-1"));

        NotificationChannel channel = manager.getNotificationChannel(OpenPushLiveUpdates.CHANNEL_ID);
        assertNotNull(channel);
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        assertNull(channel.getSound());
        assertFalse(channel.shouldVibrate());
        assertFalse(channel.canShowBadge());

        Notification notification = activeNotification();
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertTrue((notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertNotNull(notification.contentIntent);
        assertNotNull(notification.deleteIntent);
        assertNotNull(notification.actions);
        assertEquals(1, notification.actions.length);
        assertEquals(OpenPushLiveUpdates.DEFAULT_TIMEOUT_MILLIS, notification.getTimeoutAfter());
    }

    @Test
    public void updateReplacesStableNotificationAndEndCancelsIt() {
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("trip-2", 1), "trip-2"));
        int notificationId = manager.getActiveNotifications()[0].getId();

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, update("trip-2", 2, 70), "trip-2"));
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals(notificationId, manager.getActiveNotifications()[0].getId());
        assertEquals(70, activeNotification().extras.getInt(Notification.EXTRA_PROGRESS));

        assertEquals(OpenPushLiveUpdateResult.ENDED,
                OpenPushLiveUpdates.handle(context, end("trip-2", 3), "trip-2"));
        assertEquals(0, manager.getActiveNotifications().length);
        assertNull(new OpenPushLiveUpdateState(context).get("trip-2"));
    }

    @Test
    public void dismissalIsDurableAndNewStartClearsIt() {
        OpenPushLiveUpdates.handle(context, start("trip-3", 1), "trip-3");
        OpenPushLiveUpdateState.Record record = new OpenPushLiveUpdateState(context).get("trip-3");
        assertNotNull(record);

        new OpenPushLiveUpdateDismissReceiver().onReceive(context,
                new Intent(context, OpenPushLiveUpdateDismissReceiver.class)
                        .setAction(OpenPushLiveUpdateDismissReceiver.ACTION_DISMISS)
                        .putExtra(OpenPushLiveUpdateDismissReceiver.EXTRA_INSTANCE_ID, "trip-3")
                        .putExtra(OpenPushLiveUpdateDismissReceiver.EXTRA_NOTIFICATION_ID,
                                record.getNotificationId()));
        assertEquals(0, manager.getActiveNotifications().length);
        assertTrue(new OpenPushLiveUpdateState(context).get("trip-3").isDismissed());

        assertEquals(OpenPushLiveUpdateResult.DISMISSED,
                OpenPushLiveUpdates.handle(context, update("trip-3", 2, 50), "trip-3"));
        assertEquals(0, manager.getActiveNotifications().length);

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("trip-3", 3), "trip-3"));
        assertEquals(1, manager.getActiveNotifications().length);
        assertFalse(new OpenPushLiveUpdateState(context).get("trip-3").isDismissed());
    }

    @Test
    public void staleUpdateAndUpdateWithoutStartAreConsumedWithoutPosting() {
        assertEquals(OpenPushLiveUpdateResult.INVALID,
                OpenPushLiveUpdates.handle(context, update("missing", 1, 10), "missing"));
        OpenPushLiveUpdates.handle(context, start("trip-4", 8), "trip-4");
        assertEquals(OpenPushLiveUpdateResult.STALE,
                OpenPushLiveUpdates.handle(context, update("trip-4", 7, 10), "trip-4"));
        assertEquals(0, activeNotification().extras.getInt(Notification.EXTRA_PROGRESS));
    }

    @Test
    public void aStaleSequenceIsReportedAsStale() {
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("trip-5", 5), "trip-5"));

        OpenPushLiveUpdateResult replayed =
                OpenPushLiveUpdates.handle(context, update("trip-5", 5, 90), "trip-5");
        assertEquals(OpenPushLiveUpdateResult.STALE, replayed);
        assertTrue(replayed.isHandled());
        assertEquals(OpenPushLiveUpdateResult.STALE,
                OpenPushLiveUpdates.handle(context, update("trip-5", 4, 90), "trip-5"));

        // A stale frame is consumed without touching the posted progress.
        assertEquals(0, activeNotification().extras.getInt(Notification.EXTRA_PROGRESS));
        assertEquals(Long.valueOf(5), new OpenPushLiveUpdateState(context)
                .get("trip-5").getLastSequence());
    }

    @Test
    public void twoConcurrentStartsNeverShareANotificationId() throws Exception {
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        final AtomicReferenceArray<Integer> ids = new AtomicReferenceArray<>(2);
        final AtomicReferenceArray<OpenPushLiveUpdateResult> results =
                new AtomicReferenceArray<>(2);

        for (int index = 0; index < 2; index++) {
            final int slot = index;
            final String instanceId = "race-" + index;
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    results.set(slot, OpenPushLiveUpdates.handle(
                            context, start(instanceId, 1), instanceId));
                    OpenPushLiveUpdateState.Record record =
                            new OpenPushLiveUpdateState(context).get(instanceId);
                    ids.set(slot, record == null ? null : record.getNotificationId());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "live-update-race-" + index).start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, results.get(0));
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, results.get(1));
        assertNotNull(ids.get(0));
        assertNotNull(ids.get(1));
        assertNotEquals(ids.get(0), ids.get(1));
    }

    @Test
    public void concurrentSequencesCannotCommitInReverseOrder() throws Exception {
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("ordered", 1), "ordered"));
        CountDownLatch sequenceTwoAdmitted = new CountDownLatch(1);
        CountDownLatch releaseSequenceTwo = new CountDownLatch(1);
        CountDownLatch sequenceThreeStarted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReferenceArray<OpenPushLiveUpdateResult> results = new AtomicReferenceArray<>(2);
        OpenPushLiveUpdates.transactionHook = payload -> {
            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.UPDATE
                    && Long.valueOf(2).equals(payload.getSequence())) {
                sequenceTwoAdmitted.countDown();
                await(releaseSequenceTwo);
            }
        };

        new Thread(() -> {
            results.set(0, OpenPushLiveUpdates.handle(
                    context, update("ordered", 2, 20), "ordered"));
            done.countDown();
        }, "live-update-sequence-2").start();
        assertTrue(sequenceTwoAdmitted.await(5, TimeUnit.SECONDS));
        new Thread(() -> {
            sequenceThreeStarted.countDown();
            results.set(1, OpenPushLiveUpdates.handle(
                    context, update("ordered", 3, 30), "ordered"));
            done.countDown();
        }, "live-update-sequence-3").start();
        assertTrue(sequenceThreeStarted.await(5, TimeUnit.SECONDS));

        releaseSequenceTwo.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        OpenPushLiveUpdates.transactionHook = null;

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, results.get(0));
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, results.get(1));
        assertEquals(Long.valueOf(3),
                new OpenPushLiveUpdateState(context).get("ordered").getLastSequence());
        assertEquals(30, activeNotification().extras.getInt(Notification.EXTRA_PROGRESS));
    }

    @Test
    public void anEndCannotBeUndoneByAnAlreadyAdmittedUpdate() throws Exception {
        assertEquals(OpenPushLiveUpdateResult.DISPLAYED,
                OpenPushLiveUpdates.handle(context, start("ending", 1), "ending"));
        CountDownLatch updateAdmitted = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        CountDownLatch endStarted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReferenceArray<OpenPushLiveUpdateResult> results = new AtomicReferenceArray<>(2);
        OpenPushLiveUpdates.transactionHook = payload -> {
            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.UPDATE) {
                updateAdmitted.countDown();
                await(releaseUpdate);
            }
        };

        new Thread(() -> {
            results.set(0, OpenPushLiveUpdates.handle(
                    context, update("ending", 2, 50), "ending"));
            done.countDown();
        }, "live-update-before-end").start();
        assertTrue(updateAdmitted.await(5, TimeUnit.SECONDS));
        new Thread(() -> {
            endStarted.countDown();
            results.set(1, OpenPushLiveUpdates.handle(context, end("ending", 3), "ending"));
            done.countDown();
        }, "live-update-end").start();
        assertTrue(endStarted.await(5, TimeUnit.SECONDS));

        releaseUpdate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        OpenPushLiveUpdates.transactionHook = null;

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, results.get(0));
        assertEquals(OpenPushLiveUpdateResult.ENDED, results.get(1));
        assertNull(new OpenPushLiveUpdateState(context).get("ending"));
        assertEquals(0, manager.getActiveNotifications().length);
    }

    @Test
    public void aReservationOlderThanTenMinutesIsPruned() {
        OpenPushLiveUpdateState state = new OpenPushLiveUpdateState(context);
        long reservedAt = 1_000_000L;
        int reserved = state.notificationId("abandoned", reservedAt);
        assertNotNull(state.get("abandoned"));
        assertEquals(0, state.get("abandoned").getTimeoutAtMillis());
        assertEquals(reservedAt, state.get("abandoned").getReservedAtMillis());

        assertEquals(0, state.pruneExpired(reservedAt + 10L * 60L * 1000L - 1L));
        assertNotNull(state.get("abandoned"));

        assertEquals(1, state.pruneExpired(reservedAt + 10L * 60L * 1000L));
        assertNull(state.get("abandoned"));

        // The id stays reusable once the abandoned reservation is gone.
        assertEquals(reserved, state.notificationId("abandoned", reservedAt + 10L * 60L * 1000L));
    }

    @Test
    public void anUpdateCannotRestartAExpiredInstance() throws Exception {
        OpenPushLiveUpdatePayload startPayload = OpenPushLiveUpdatePayload.parse(
                start("expired", 1), "expired");
        OpenPushLiveUpdateState state = new OpenPushLiveUpdateState(context);
        int id = state.notificationId("expired");
        state.recordDisplayed(startPayload, id, 1);

        assertEquals(OpenPushLiveUpdateResult.INVALID,
                OpenPushLiveUpdates.handle(context, update("expired", 2, 50), "expired"));
        assertNull(state.get("expired"));
    }

    @Test
    public void preAndroid36ReportsFallbackAndCannotOpenPromotionSettings() {
        assertEquals(OpenPushLiveUpdates.Capability.FALLBACK_ONGOING,
                OpenPushLiveUpdates.capability(context));
        assertFalse(OpenPushLiveUpdates.hasPromotableCharacteristics(new Notification()));
        assertFalse(OpenPushLiveUpdates.openPromotionSettings(context));
    }

    private Notification activeNotification() {
        StatusBarNotification[] active = manager.getActiveNotifications();
        assertEquals(1, active.length);
        return active[0].getNotification();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the test transaction latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Map<String, String> start(String instanceId, long sequence) {
        return data("{\"key\":\"progress\",\"instance_id\":\"" + instanceId + "\","
                + "\"event\":\"start\",\"sequence\":" + sequence + ","
                + "\"event_attributes\":{\"title\":\"Delivery\",\"body\":\"On the way\"},"
                + "\"event_updates\":{\"current_progress\":0,\"max_progress\":100}}" );
    }

    private static Map<String, String> update(String instanceId, long sequence, int progress) {
        return data("{\"key\":\"progress\",\"instance_id\":\"" + instanceId + "\","
                + "\"event\":\"update\",\"sequence\":" + sequence + ","
                + "\"event_attributes\":{\"title\":\"Delivery\"},"
                + "\"event_updates\":{\"current_progress\":" + progress
                + ",\"max_progress\":100}}" );
    }

    private static Map<String, String> end(String instanceId, long sequence) {
        return data("{\"key\":\"progress\",\"instance_id\":\"" + instanceId + "\","
                + "\"event\":\"end\",\"sequence\":" + sequence + "}");
    }

    private static Map<String, String> data(String value) {
        Map<String, String> data = new HashMap<>();
        data.put(OpenPushLiveUpdatePayload.DATA_KEY, value);
        return data;
    }
}
