package ai.openpush.android

/** What a callback form of an [OpenPush.Verify] call reports. */
sealed class OpenPushVerifyResult {
    /** The server answered with [verification]; read its status for whether the code matched. */
    data class Success(val verification: OpenPushVerification) : OpenPushVerifyResult()

    /** Nothing usable came back; [error] says why. */
    data class Failure(val error: OpenPushVerifyError) : OpenPushVerifyResult()
}
