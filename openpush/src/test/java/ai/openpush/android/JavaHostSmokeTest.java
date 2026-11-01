package ai.openpush.android;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import kotlin.coroutines.Continuation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class JavaHostSmokeTest {
    /** Kept out of the runtime smoke path: compiling this method is the Java ABI regression test. */
    private static void compileShortForms(Context context) {
        OpenPushConfiguration configuration = new OpenPushConfiguration(
                "demo", "sdk", OpenPush.DEFAULT_SERVER_URL, "android", "openpush.deviceToken");
        OpenPush.attach(context, configuration, () -> "credential");
        OpenPush.Notifications.requestPermission(false);
        OpenPush.Notifications.requestPermission((Continuation<? super Boolean>) null);
        OpenPush.Notifications.clearAll();
        OpenPush.Notifications.remove(7);
        OpenPush.Notifications.removeGroup("orders");
        RegistrationAck ack = new RegistrationAck(
                "sub-1", null, java.util.Collections.singletonList("reserved"));
        RegistrationAck copied = ack.copy("sub-2", null, java.util.Collections.emptyList());
        assertTrue(copied.getSubscriptionId().equals("sub-2"));
    }

    @Test
    public void theSurfaceIsCallableFromJavaWithoutINSTANCEOrKotlinDefaults() {
        Context context = ApplicationProvider.getApplicationContext();
        OpenPush.resetForTests();
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit();
        OpenPush.initialize(context, "demo", "sdk");
        OpenPush.User.addTag("plan", "pro");
        OpenPush.User.addTags(java.util.Collections.singletonMap("seats", "3"));
        OpenPush.login("user-1");
        OpenPush.trackEvent("level_up");
        ListenerHandle handle = OpenPush.Notifications.addClickListener(event -> kotlin.Unit.INSTANCE);
        OpenPush.Notifications.removeClickListener(handle);
        assertTrue(OpenPush.isInitialized());
        OpenPush.resetForTests();
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit();
    }
}
