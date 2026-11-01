package ai.openpush.android.onesignal;

import android.app.Activity;
import android.content.Context;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/** Compilation coverage for the short Java forms generated from Kotlin defaults. */
public class JavaFacadeSmokeTest {
    @SuppressWarnings("deprecation")
    private static void compileShortForms(Context context, Activity activity) {
        OneSignal.initialize(context, "manifest-app");
        OneSignal.initialize(context, "explicit-app", "sdk-key");
        OneSignal.initialize(context, "explicit-app", "sdk-key", OneSignal.DEFAULT_SERVER_URL);
        OneSignal.initialize(context, "explicit-app", "sdk-key", OneSignal.DEFAULT_SERVER_URL, () -> null);
        OneSignal.initWithContext(context, "manifest-app");
        OneSignal.login("user");
        OneSignal.logout();
        OneSignal.register("provider-token");
        OneSignal.register("provider-token", "user");
        OneSignal.Notifications.requestPermission();
        OneSignal.Notifications.requestPermission(false);
        OneSignal.Notifications.requestPermission(false, granted -> Unit.INSTANCE);
        OneSignal.Notifications.requestPermission(activity);
        OneSignal.Notifications.requestPermission(
                (Continuation<? super Boolean>) null);
        OneSignal.Notifications.requestPermission(false, (Continuation<? super Boolean>) null);
        OneSignal.Notifications.clearAllNotifications();
        OneSignal.Notifications.removeNotification(7);
        OneSignal.Notifications.removeGroupedNotifications("orders");
        OneSignal.InAppMessages.addTrigger("plan", "pro");
        OneSignal.Session.addOutcome("signed_in");
    }

    @Test
    public void facadePublishesStaticJavaEntryPoints() throws Exception {
        assertNotNull(OneSignal.class.getMethod(
                "initialize", Context.class, String.class, String.class));
        assertNotNull(OneSignal.class.getMethod("login", String.class));
        assertNotNull(OneSignal.class.getMethod("logout"));
        assertNotNull(OneSignal.class.getMethod("register", String.class));
        assertNotNull(OneSignal.Notifications.class.getMethod("requestPermission", boolean.class));
        assertNotNull(OneSignal.Notifications.class.getMethod("clearAllNotifications"));
        assertNotNull(OneSignal.Notifications.class.getMethod("removeNotification", int.class));
        assertNotNull(OneSignal.Notifications.class.getMethod("removeGroupedNotifications", String.class));
        assertNotNull(OneSignal.InAppMessages.class.getMethod("addTrigger", String.class, String.class));
        assertNotNull(OneSignal.Session.class.getMethod("addOutcome", String.class));
        assertNotNull(OneSignal.User.class.getMethod("addSms", String.class));
        assertNotNull(OneSignal.User.class.getMethod("removeSms", String.class));
        assertNotNull(OneSignal.User.class.getMethod("addEmail", String.class));
        assertNotNull(OneSignal.User.class.getMethod("removeEmail", String.class));
        assertNotNull(Class.forName("ai.openpush.android.onesignal.OneSignalUnavailableKt").getMethod(
                "updateUserJwtSuspend", OneSignal.class, String.class, String.class, Continuation.class));
    }
}
