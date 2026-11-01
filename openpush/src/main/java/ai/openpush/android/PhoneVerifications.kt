package ai.openpush.android

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The phone-code requests behind [OpenPush.Verify]. Each call is one request on the SDK's own
 * transport, with the same base URL, SDK key and SDK headers as registration. Nothing is queued or
 * retried: a person is waiting for the code, so a failure is reported at once and the app decides
 * whether to ask again.
 */
internal object PhoneVerifications {
    private val scope = sdkScope("OpenPush phone codes")

    suspend fun start(phone: String, channel: OpenPushVerifyChannel, locale: String?): OpenPushVerification {
        val number = PhoneNumberFormat.require(phone)
        val body = JSONObject().put("to", number).put("channel", channel.wireValue)
        locale?.trim()?.takeIf(String::isNotEmpty)?.let { body.put("locale", it) }
        return send("", body, number, channel)
    }

    suspend fun check(verification: OpenPushVerification, code: String): OpenPushVerification {
        val body = JSONObject().put("to", verification.phoneNumber).put("code", code.trim())
        return send("/${Uri.encode(verification.id)}/check", body, verification.phoneNumber, verification.channel)
    }

    suspend fun resend(verification: OpenPushVerification, channel: OpenPushVerifyChannel): OpenPushVerification {
        val body = JSONObject().put("to", verification.phoneNumber).put("channel", channel.wireValue)
        return send("/${Uri.encode(verification.id)}/resend", body, verification.phoneNumber, channel)
    }

    /** Runs [call] off the caller's thread and reports its outcome to [callback] on the main thread. */
    fun deliver(callback: (OpenPushVerifyResult) -> Unit, call: suspend () -> OpenPushVerification) {
        scope.launch {
            val result = try {
                OpenPushVerifyResult.Success(call())
            } catch (error: OpenPushVerifyError) {
                OpenPushVerifyResult.Failure(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OpenPushVerifyResult.Failure(
                    OpenPushVerifyError(OpenPushVerifyError.Code.NETWORK, error.message ?: "network error"),
                )
            }
            OpenPush.deliverOnMain("verify callback") { callback(result) }
        }
    }

    private suspend fun send(
        suffix: String,
        body: JSONObject,
        number: String,
        channel: OpenPushVerifyChannel,
    ): OpenPushVerification {
        val appId = OpenPush.bridgeAppId() ?: throw OpenPushVerifyError(
            OpenPushVerifyError.Code.REFUSED,
            "OpenPush is not initialized — call OpenPush.initialize first",
        )
        val path = "/v1/apps/$appId/verify$suffix"
        OpenPush.onLog("→ POST $path · ${PhoneNumberFormat.masked(number)}")
        val response = OpenPush.bridgeRequest("POST", path, body.toString())
        if (response.status !in 200..299) {
            val error = VerifyResponses.error(response)
            OpenPush.onLog("✗ verify ${error.code.name.lowercase()} (${response.status}) · ${error.message}")
            throw error
        }
        return VerifyResponses.verification(response.body, number, channel).also {
            OpenPush.onLog("✓ verification ${it.id} · ${it.status.name.lowercase()}")
        }
    }
}
