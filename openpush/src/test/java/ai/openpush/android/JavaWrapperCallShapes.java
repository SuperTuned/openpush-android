package ai.openpush.android;

import android.app.Activity;
import android.content.pm.PackageManager;
import java.util.Collections;
import java.util.Map;
import kotlin.Unit;

/**
 * Every core call a Java wrapper SDK makes, in the exact shape the Unity package's Android bridge
 * makes it. Never run: compiling this file is the check that each member stays reachable from Java
 * with the same signature. A change that breaks one breaks the wrapper's build too.
 */
@SuppressWarnings({"deprecation", "unused"})
final class JavaWrapperCallShapes {
    private JavaWrapperCallShapes() {}

    static void hooks() {
        OpenPush.setOnLog(message -> Unit.INSTANCE);
        OpenPush.setOnRegistered(() -> Unit.INSTANCE);
        OpenPush.setOnRegistrationFailed(message -> Unit.INSTANCE);
        OpenPush.setOnIdentityVerificationRequired(externalId -> Unit.INSTANCE);
        OpenPush.PushSubscription.addChangeObserver(change -> {
            PushSubscriptionState previous = change.getPrevious();
            PushSubscriptionState current = change.getCurrent();
            String id = current.getId();
            String token = current.getToken();
            boolean optedIn = current.getOptedIn();
            return Unit.INSTANCE;
        });
        OpenPush.Notifications.addPermissionObserver(granted -> Unit.INSTANCE);
        OpenPush.Notifications.addForegroundLifecycleListener(event -> {
            // The foreground decision: the wrapper waits for its game's answer, then may suppress.
            event.preventDefault();
            String messageId = event.getMessageId();
            Map<String, String> data = event.getData();
            event.getNotification().toJson();
            return Unit.INSTANCE;
        });
        OpenPush.Notifications.addBackgroundMessageListener(event -> {
            event.getNotification().toJson();
            return Unit.INSTANCE;
        });
        OpenPush.Notifications.addClickListener(click -> {
            String actionId = click.getActionId();
            String launchUrl = click.getNotification().getLaunchUrl();
            String actionUrl = click.getActionUrl();
            boolean opened = click.getLaunchUrlOpened();
            return Unit.INSTANCE;
        });
    }

    static void core(Activity host, String appId, String sdkKey, String serverUrl, String credential) {
        OpenPush.setSdkWrapper("unity/1.2.2");
        OpenPush.initialize(host, appId, sdkKey, serverUrl, () -> credential);
        boolean initialized = OpenPush.isInitialized();
        String version = OpenPush.getSdkVersion();
        String token = OpenPush.getCurrentToken();
        // Kept, and deprecated: the second argument is logged in through login().
        OpenPush.register("provider-token", "player-1");
        OpenPush.register("provider-token", null);
        OpenPush.receipt("received", "message-id", () -> Unit.INSTANCE);
        OpenPush.trackEvent("level_up", Collections.<String, Object>emptyMap(), null);
        OpenPush.login("player-1", "auth-hash", result -> {
            boolean success = result instanceof IdentityResult.Success;
            String note = result instanceof IdentityResult.IdentityRejected
                ? ((IdentityResult.IdentityRejected) result).getNote() : null;
            String error = result instanceof IdentityResult.Failed ? ((IdentityResult.Failed) result).getMessage() : null;
            return Unit.INSTANCE;
        });
        OpenPush.login("player-1", null, null);
        OpenPush.logout(result -> Unit.INSTANCE);
        OpenPush.onAppForegrounded();
        OpenPush.clearToken();
    }

    static void user(Map<String, String> tags) {
        OpenPush.User.addTags(tags);
        OpenPush.User.removeTags(Collections.singletonList("key"));
        Map<String, String> current = OpenPush.User.getTags();
        OpenPush.User.addAliases(tags);
        OpenPush.User.removeAliases(Collections.singletonList("label"));
        String externalId = OpenPush.User.getExternalId();
        OpenPush.User.addSms("+15555550100");
        OpenPush.User.removeSms("+15555550100");
        OpenPush.User.addWhatsApp("+15555550100");
        OpenPush.User.removeWhatsApp("+15555550100");
        OpenPush.User.addEmail("player@example.com");
        OpenPush.User.removeEmail("player@example.com");
        PushSubscriptionState snapshot = OpenPush.LivePushSubscription.snapshot();
        OpenPush.PushSubscription.optIn();
        OpenPush.PushSubscription.optOut();
    }

    static void notifications(Map<String, String> data) {
        boolean permission = OpenPush.Notifications.getPermission();
        boolean canRequest = OpenPush.Notifications.getCanRequestPermission();
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE, new int[] {PackageManager.PERMISSION_GRANTED});
        OpenPush.Notifications.clearAll();
        OpenPush.Notifications.remove("message-id".hashCode());
        OpenPush.Notifications.processClick(data);
        OpenPush.Notifications.processForegroundMessage(data);
        boolean claimed = OpenPush.Notifications.beginReceived(data);
        OpenPush.Notifications.finishReceived(data, true);
        OpenPush.Notifications.processDisplayed(data);
    }

    static void phoneCodes() {
        OpenPush.Verify.start("+15555550100", OpenPushVerifyChannel.AUTO, null, result -> {
            if (result instanceof OpenPushVerifyResult.Success) {
                OpenPushVerification verification = ((OpenPushVerifyResult.Success) result).getVerification();
                OpenPush.Verify.check(verification, "123456", next -> Unit.INSTANCE);
                OpenPush.Verify.resend(verification, verification.getChannel(), next -> Unit.INSTANCE);
                String id = verification.getId();
                String masked = verification.getMaskedNumber();
                String status = verification.getStatus().name();
                int attempts = verification.getAttempts();
                java.util.Date expires = verification.getExpiresAt();
            } else if (result instanceof OpenPushVerifyResult.Failure) {
                OpenPushVerifyError error = ((OpenPushVerifyResult.Failure) result).getError();
                String code = error.getCode().name();
                String message = error.getMessage();
            }
            return Unit.INSTANCE;
        });
    }
}
