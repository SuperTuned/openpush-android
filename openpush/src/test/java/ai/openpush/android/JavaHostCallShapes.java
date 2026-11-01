package ai.openpush.android;

import android.app.Activity;
import android.content.Context;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * Every Java call shape of the overloaded entry points, as Java hosts and the examples write them.
 * Never run: compiling this file is the check that each shape keeps its meaning and signature.
 */
@SuppressWarnings({"deprecation", "unused"})
final class JavaHostCallShapes {
    private JavaHostCallShapes() {}

    static void initializeForms(Context context, String appId, String sdkKey, String serverUrl) {
        OpenPush.initialize(context);
        OpenPush.initialize(context, appId);
        OpenPush.initWithContext(context, appId);
        OpenPush.initialize(context, appId, sdkKey);
        OpenPush.initialize(context, appId, sdkKey, serverUrl);
        OpenPush.initialize(context, appId, sdkKey, serverUrl, () -> "credential");
        OpenPush.initialize(context, appId, sdkKey, serverUrl, () -> null, null);
    }

    static void permissionForms(Activity activity, Continuation<? super Boolean> continuation) {
        OpenPush.Notifications.requestPermission();
        OpenPush.Notifications.requestPermission(false);
        OpenPush.Notifications.requestPermission(false, granted -> Unit.INSTANCE);
        OpenPush.Notifications.requestPermission(activity);
        OpenPush.Notifications.requestPermission(activity, true);
        OpenPush.Notifications.requestPermission(activity, true, granted -> Unit.INSTANCE);
        OpenPush.Notifications.requestPermission(continuation);
        OpenPush.Notifications.requestPermission(false, continuation);
    }

    static void registerForms(String externalId) {
        OpenPush.register("provider-token");
        OpenPush.register("provider-token", externalId);
        OpenPush.login(externalId);
        OpenPush.login(externalId, "auth-hash");
        OpenPush.login(externalId, "auth-hash", null);
    }
}
