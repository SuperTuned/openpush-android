package ai.openpush.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceCredentialStore internal constructor(
    ctx: Context,
    private val service: String,
    private val keyProvider: () -> SecretKey?,
) {
    constructor(ctx: Context, service: String) : this(ctx, service, { androidKey(service) })

    private val prefs = ctx.applicationContext.getSharedPreferences("openpush.secure", Context.MODE_PRIVATE)
    private val dataKey = "$service.data"
    private val ivKey = "$service.iv"

    fun save(value: String): Boolean {
        val key = keyProvider() ?: return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            prefs.edit()
                .putString(dataKey, Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
                .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            true
        }.getOrDefault(false)
    }

    fun load(): String? {
        val data = prefs.getString(dataKey, null) ?: return null
        val iv = prefs.getString(ivKey, null) ?: return null
        val key = keyProvider() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun wipe() {
        prefs.edit().remove(dataKey).remove(ivKey).apply()
    }

    private companion object {
        fun androidKey(service: String): SecretKey? = runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(service, null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                            service,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                    generateKey()
                }
        }.getOrNull()
    }
}
