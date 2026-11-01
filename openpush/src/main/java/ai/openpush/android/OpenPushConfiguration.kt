package ai.openpush.android

class OpenPushConfiguration(
    val appID: String,
    val sdkKey: String,
    serverURL: String = DEFAULT_SERVER_URL,
    val platform: String = "android",
    val tokenKey: String = "openpush.deviceToken",
) {
    val serverURL = serverURL.trimEnd('/')
    val server get() = serverURL
    fun url(path: String) = serverURL + path

    override fun equals(other: Any?): Boolean = other is OpenPushConfiguration &&
        appID == other.appID &&
        sdkKey == other.sdkKey &&
        serverURL == other.serverURL &&
        platform == other.platform &&
        tokenKey == other.tokenKey

    override fun hashCode(): Int = listOf(appID, sdkKey, serverURL, platform, tokenKey).hashCode()

    override fun toString(): String = "OpenPushConfiguration(" +
        "appID=$appID, server=$serverURL, platform=$platform, tokenKey=$tokenKey" +
        ")"

    companion object {
        /** Server used when no server URL is supplied. */
        const val DEFAULT_SERVER_URL = "https://app.openpush.ai"
    }
}
