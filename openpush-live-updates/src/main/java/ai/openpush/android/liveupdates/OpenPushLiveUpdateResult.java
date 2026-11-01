package ai.openpush.android.liveupdates;

/** Result returned after inspecting an OpenPush Android Live Update payload. */
public enum OpenPushLiveUpdateResult {
    NOT_LIVE_UPDATE(false),
    DISPLAYED(true),
    ENDED(true),
    DISMISSED(true),
    /** The frame repeated or preceded the sequence already applied, so it was dropped. */
    STALE(true),
    INVALID(true);

    private final boolean handled;

    OpenPushLiveUpdateResult(boolean handled) {
        this.handled = handled;
    }

    /** Whether the payload was consumed and must not become an ordinary notification. */
    public boolean isHandled() {
        return handled;
    }
}
