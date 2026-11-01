package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCredentialStoreTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptedValueRoundTripsAndWipes() {
        val key = aesKey()
        val store = DeviceCredentialStore(context, "test.${System.nanoTime()}") { key }
        assertTrue(store.save("opdev_secret"))
        assertEquals("opdev_secret", store.load())
        store.wipe()
        assertNull(store.load())
    }

    @Test fun missingKeystoreFailsClosed() {
        val service = "missing.${System.nanoTime()}"
        val store = DeviceCredentialStore(context, service) { null }
        assertFalse(store.save("opdev_secret"))
        assertNull(store.load())
        assertNull(
            context
                .getSharedPreferences("openpush.secure", Context.MODE_PRIVATE)
                .getString("$service.plain", null),
        )
    }
}
