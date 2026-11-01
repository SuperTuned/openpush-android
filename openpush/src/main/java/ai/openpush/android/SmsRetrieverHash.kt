package ai.openpush.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The 11-character app hash the SMS Retriever API matches an incoming code message against. It is
 * derived from the package name and the signing certificate alone, so computing it needs no Google
 * Play services library: only an app that goes on to call `SmsRetriever` itself depends on one.
 */
internal object SmsRetrieverHash {
    private const val HASH_BYTES = 9
    private const val HASH_LENGTH = 11

    /** The hash for this app as currently signed, or null when no signing certificate can be read. */
    fun forApp(context: Context): String? {
        val signature = runCatching { currentSignature(context) }.getOrNull() ?: return null
        return compute(context.packageName, signature)
    }

    /** The hash for [packageName] signed with [signature] (the certificate's hex `toCharsString()`). */
    fun compute(packageName: String, signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName $signature".toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(digest.copyOf(HASH_BYTES), Base64.NO_PADDING or Base64.NO_WRAP)
            .take(HASH_LENGTH)
    }

    private fun currentSignature(context: Context): String? {
        val manager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toCharsString()
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toCharsString()
        }
    }
}
