package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import kotlinx.coroutines.launch

/**
 * Everything the engine reports back to the server about a message on screen, and the local record
 * that keeps it from reporting the same fact twice.
 *
 * The pattern is the same for all three receipts and is the reason they live together: the record
 * is written *first*, so a burst of repeats collapses before any request is made, and rolled back
 * when the server refuses, so nothing is lost to a transient failure. The store is the dedupe, the
 * network is not.
 */
@OptIn(OpenPushInternalApi::class)
internal fun IamEngine.impression(message: IamMessage) {
    val state = store ?: return
    if (!state.withLock { impressions.add(message.id) }) return
    state.save()
    receipt(message, "iam_impression", emptyMap()) { accepted ->
        if (!accepted) {
            state.withLock { impressions.remove(message.id) }
            state.save()
        }
    }
}

/** One impression per page of a multi-page document, keyed `"<messageId>|<pageId>"`. */
@OptIn(OpenPushInternalApi::class)
internal fun IamEngine.pageImpression(message: IamMessage, pageId: String) {
    val state = store ?: return
    if (pageId.isEmpty()) return
    val record = "${message.id}|$pageId"
    if (!state.withLock { pageViews.add(record) }) return
    state.save()
    receipt(message, "iam_page_impression", mapOf("page_id" to pageId)) { accepted ->
        if (!accepted) {
            state.withLock { pageViews.remove(record) }
            state.save()
        }
    }
}

/**
 * One tap: at most one receipt per click id, and exactly one callback per tap.
 *
 * The two are deliberately out of step. The server counts a click id once, so a repeat of a tap it
 * has already seen sends nothing. The host's listener is a different contract — a second tap is a
 * second thing the user did, and a host routing on it would silently lose the repeat if the engine
 * folded the two together — so it fires every time, with `firstClick` false from the second click
 * id onwards.
 *
 * `firstClick` is read *before* the record is inserted, and asks about the message rather than the
 * button: it is true only while nothing has ever been clicked on this message.
 */
@OptIn(OpenPushInternalApi::class)
internal fun IamEngine.recordClick(message: IamMessage, action: IamAction) {
    val state = store ?: return
    val clickId = action.clickId ?: return
    val record = "${message.id}#$clickId"
    var firstClick = false
    val unseen = state.withLock {
        firstClick = clicked.none { it.startsWith("${message.id}#") }
        clicked.add(record)
    }
    if (unseen) {
        state.save()
        val extra = buildMap<String, Any?> {
            put("click_id", clickId)
            action.blockKey?.let { put("block_key", it) }
            put("first_click", firstClick)
        }
        receipt(message, "iam_click", extra) { accepted ->
            if (!accepted) {
                state.withLock { clicked.remove(record) }
                state.save()
            }
        }
    }
    notifyClick(
        InAppMessageClickEvent(
            messageId = message.id,
            actionId = action.actionId,
            url = action.url,
            urlTarget = action.urlTarget,
            closingMessage = action.closes,
            firstClick = firstClick,
            pageId = currentPageId,
        ),
    )
}

/** Posts one `/v1/ingest` event and reports whether the server took it. */
@OptIn(OpenPushInternalApi::class)
internal fun IamEngine.receipt(
    message: IamMessage,
    type: String,
    extra: Map<String, Any?>,
    onOutcome: (Boolean) -> Unit,
) {
    val app = OpenPushBridge.appId ?: run {
        OpenPush.onLog("in-app receipt $type for ${message.id} not sent — app id is unavailable")
        return
    }
    val token = OpenPushBridge.token ?: run {
        OpenPush.onLog("in-app receipt $type for ${message.id} not sent — provider token is unavailable")
        return
    }
    scope.launch {
        val response = OpenPushBridge.request(
            "POST",
            "/v1/ingest",
            OpenPushBridge.receiptBody(listOf(type), message.id, token, app, extra),
        )
        val accepted = response.status in 200..299
        if (!accepted) {
            OpenPush.onLog("✗ in-app receipt $type for ${message.id} failed (${response.status})")
        }
        onMain { onOutcome(accepted) }
    }
}
