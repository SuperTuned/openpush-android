package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * The engine side of in-app layouts: what a page change is worth, what a permission prompt does to the
 * queue, and which ways of taking a message down count as the user having dismissed it.
 *
 * The distinction the last group is about: a display and a dismissal are not the same fact. An
 * auto-close and a `close` action both *display* the message — the redisplay record is written — but
 * neither is the user saying "no". Only a dismissal the user made blocks a one-shot forever.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamEngineLayoutTests : IamTestCase() {

    @Test
    fun eachPageOfADocumentIsWorthOneImpression() {
        fetchFixture(oneMessage("iam_1"))
        presenter.emit(PresenterEvent.Rendered("full", 0, false))
        idle()

        presenter.emit(PresenterEvent.PageChanged("page_a", 0))
        idle()
        presenter.emit(PresenterEvent.PageChanged("page_a", 0))
        idle()
        presenter.emit(PresenterEvent.PageChanged("page_b", 1))
        idle()

        assertEquals(listOf("page_a", "page_b"), pageImpressions().map(::pageIdOf))
        assertTrue("iam_1|page_a" in OpenPushInAppMessages.state.pageViews)
        assertTrue("iam_1|page_b" in OpenPushInAppMessages.state.pageViews)
    }

    @Test
    fun aPageImpressionTheServerRefusedIsRetried() {
        ingestStatus = 500
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.PageChanged("page_a", 0))
        idle()

        assertEquals(1, pageImpressions().size)
        assertFalse("iam_1|page_a" in OpenPushInAppMessages.state.pageViews)

        ingestStatus = 200
        presenter.emit(PresenterEvent.PageChanged("page_a", 0))
        idle()

        assertEquals(2, pageImpressions().size)
        assertTrue("iam_1|page_a" in OpenPushInAppMessages.state.pageViews)
    }

    @Test
    fun aPushPromptHoldsTheQueueUntilTheResultArrives() {
        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(
            PresenterEvent.Action(IamAction(clickId = "c1", closes = true, prompts = listOf("push"))),
        )
        idle()

        assertNull(OpenPushInAppMessages.engine.current)
        assertEquals(listOf("a"), presenter.shows.map { it.message.id })

        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        idle()

        assertEquals(listOf("a", "b"), presenter.shows.map { it.message.id })
    }

    @Test
    fun aPushPromptHoldExpiresOnItsOwn() {
        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(
            PresenterEvent.Action(IamAction(clickId = "c1", closes = true, prompts = listOf("push"))),
        )
        idle()

        assertEquals(listOf("a"), presenter.shows.map { it.message.id })

        scheduler.advance(60_000)
        idle()

        assertEquals(listOf("a", "b"), presenter.shows.map { it.message.id })
    }

    @Test
    fun aMessageAsksForThePermissionAtMostOnce() {
        fetchFixture(oneMessage("iam_1"))
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c1", prompts = listOf("push"))))
        idle()
        assertEquals(1, scheduler.pending)

        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        idle()
        assertEquals(0, scheduler.pending)

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c2", prompts = listOf("push"))))
        idle()

        assertEquals(0, scheduler.pending)
    }

    /**
     * A `push` prompt only sometimes puts a dialog on screen. When the permission is already
     * granted, or permanently denied, Android shows nothing and the request answers on the spot.
     * The hold has to end with that answer; the
     * sixty-second timer is the backstop for a host that never forwards a result, not the normal
     * way out, and leaning on it would leave the queue stopped with nothing on screen.
     */
    @Test
    fun aPromptThatCannotPromptReleasesTheHoldImmediately() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(
            PresenterEvent.Action(IamAction(clickId = "c1", closes = true, prompts = listOf("push"))),
        )
        idle()

        assertEquals(listOf("a", "b"), presenter.shows.map { it.message.id })
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun aCloseActionCountsADisplayButNotADismissal() {
        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c1", closes = true)))
        idle()

        assertFalse("a" in OpenPushInAppMessages.state.dismissed)
        assertEquals(1, OpenPushInAppMessages.state.redisplay["a"]?.quantity)
        assertEquals(1, presenter.hides)
        assertEquals(listOf("a", "b"), presenter.shows.map { it.message.id })
    }

    @Test
    fun anActionUrlLeavesForTheBrowser() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(
            PresenterEvent.Action(
                IamAction(clickId = "c1", url = "https://example.invalid/offer", urlTarget = "webview"),
            ),
        )
        idle()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.invalid/offer", started.data.toString())
    }

    @Test
    fun anActionUrlThatIsNotAWebLinkIsIgnored() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(
            PresenterEvent.Action(IamAction(clickId = "c1", url = "javascript:alert(1)")),
        )
        idle()

        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun pageImpressions(): List<Recorded> = ingestRequests().filter { typeOf(it) == "iam_page_impression" }

    private fun typeOf(request: Recorded): String = eventOf(request).getString("type")

    private fun pageIdOf(request: Recorded): String = eventOf(request).getString("page_id")

    private fun eventOf(request: Recorded): JSONObject =
        JSONObject(request.body!!).getJSONArray("events").getJSONObject(0)

    private fun twoMessages(): String = """{"in_app_messages":[""" +
        """{"id":"a","html_path":"/v1/apps/demo/iams/a/html","layout":"center","triggers":[]},""" +
        """{"id":"b","html_path":"/v1/apps/demo/iams/b/html","layout":"center","triggers":[]}]}"""
}
