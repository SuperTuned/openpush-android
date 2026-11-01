package ai.openpush.android.liveupdates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

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
public class OpenPushLiveUpdateRendererTests {
    private Context context;
    private OpenPushLiveUpdateRenderer renderer;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        renderer = new OpenPushLiveUpdateRenderer(context, manager);
    }

    @Test
    public void theUnpinActionIsLabelledFromResourcesAndCarriesAnIcon() throws Exception {
        Notification notification = renderer.build(
                payload("progress-1"), 17, 0, null, OpenPushLiveUpdates.DEFAULT_TIMEOUT_MILLIS);

        assertNotNull(notification);
        assertNotNull(notification.actions);
        assertEquals(1, notification.actions.length);

        Notification.Action action = notification.actions[0];
        String expected = context.getString(R.string.openpush_live_update_unpin);
        assertEquals(expected, action.title.toString());
        // The label must come from resources, never from a hard-coded literal.
        assertEquals(context.getResources().getString(R.string.openpush_live_update_unpin),
                action.title.toString());
        assertNotEquals(0, action.icon);
        assertNotNull(action.getIcon());
        assertNotNull(action.actionIntent);
    }

    @Test
    public void theUnpinDrawableResolvesToADeclaredResource() {
        assertNotEquals(0, R.drawable.openpush_ic_unpin);
        assertNotNull(context.getResources().getDrawable(R.drawable.openpush_ic_unpin, null));
        assertTrue(context.getString(R.string.openpush_live_update_unpin).length() > 0);
    }

    private static OpenPushLiveUpdatePayload payload(String instanceId) throws Exception {
        Map<String, String> data = new HashMap<>();
        data.put(OpenPushLiveUpdatePayload.DATA_KEY,
                "{\"key\":\"progress\",\"instance_id\":\"" + instanceId + "\","
                        + "\"event\":\"start\",\"sequence\":1,"
                        + "\"event_attributes\":{\"title\":\"Delivery\",\"body\":\"On the way\"},"
                        + "\"event_updates\":{\"current_progress\":10,\"max_progress\":100}}");
        return OpenPushLiveUpdatePayload.parse(data, null);
    }
}
