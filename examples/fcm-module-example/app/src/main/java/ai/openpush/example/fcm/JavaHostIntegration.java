package ai.openpush.example.fcm;

import ai.openpush.android.OpenPush;
import android.app.Application;
import android.content.Context;
import java.util.Collections;

/**
 * Java-host forms of the Kotlin object API. The app initializes through this class so the sample
 * build proves callers use ordinary static methods, with no Kotlin {@code INSTANCE} field.
 */
final class JavaHostIntegration {
    private JavaHostIntegration() {}

    static void initialize(Application application, String appId, String sdkKey, String serverUrl) {
        OpenPush.initialize(application, appId, sdkKey, serverUrl);
    }

    static String sdkVersion() {
        return OpenPush.getSdkVersion();
    }

    /** Compile-checked wrapper-SDK recipe; a direct Android host leaves this unset. */
    static void setWrapperIdentity(String wrapper) {
        OpenPush.setSdkWrapper(wrapper);
    }

    /** Compile-checked nested-object recipe matching the Java section of API.md. */
    static void addTag(String key, String value) {
        OpenPush.User.addTag(key, value);
    }

    /** Other initializer and lifecycle forms, kept as compile-checked host recipes. */
    static void initializeFromManifest(Context context, String appId) {
        OpenPush.initialize(context);
        OpenPush.initialize(context, appId);
        OpenPush.initWithContext(context, appId);
        OpenPush.observeLifecycle((Application) context.getApplicationContext());
    }

    /**
     * Java overloads generated for Kotlin defaults. These calls are not run by the sample. The
     * two-argument register is deprecated and kept here as a compile check for existing Java callers.
     */
    @SuppressWarnings("deprecation")
    static void defaultArgumentOverloads(String externalId) {
        OpenPush.register("provider-token");
        OpenPush.register("provider-token", externalId);
        OpenPush.trackEvent("java_sample");
        OpenPush.trackEvent("java_sample", Collections.singletonMap("source", "java"));
        OpenPush.trackEvent(
                "java_sample", Collections.singletonMap("source", "java"), "java-sample-idempotency");
        OpenPush.receipt("received", "message-id");
        OpenPush.receipt("received", "message-id", null);
        OpenPush.login(externalId);
        OpenPush.login(externalId, "server-provided-auth-hash", null);
        OpenPush.logout();
        OpenPush.logout(null);
        OpenPush.Notifications.requestPermission(false);
        OpenPush.Notifications.requestPermission(false, granted -> kotlin.Unit.INSTANCE);
    }

    /** Manifest keys are constants too, for hosts that generate manifest entries. */
    static String[] manifestKeys() {
        return new String[] {OpenPush.META_APP_ID, OpenPush.META_SDK_KEY};
    }
}
