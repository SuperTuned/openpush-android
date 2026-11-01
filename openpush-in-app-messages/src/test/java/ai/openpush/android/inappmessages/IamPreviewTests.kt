package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The developer preview shows a real surface and tells the host about it, and records nothing at
 * all. Everything asserted here is a thing that must *not* happen: no receipt on the wire, and no
 * entry in the state the eligibility rules read.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamPreviewTests : IamTestCase() {
    private val document = "<html><body>preview</body></html>"

    @Test fun aPreviewRendersWithoutPostingAReceipt() {
        val phases = mutableListOf<String>()
        OpenPushInAppMessages.addLifecycleListener { phases += it.phase }

        OpenPushInAppMessages.engine.previewDocument(activity, document)
        assertEquals(listOf(document), presenter.previews)
        idle()
        assertEquals(listOf("willDisplay"), phases)

        presenter.emit(PresenterEvent.Rendered("center", 260, false))
        idle()

        assertEquals(listOf("willDisplay", "didDisplay"), phases)
        // A real message's `Rendered` posts `iam_impression`. A preview's posts nothing.
        assertTrue("a preview posted a receipt: ${ingestRequests()}", ingestRequests().isEmpty())
        assertTrue(OpenPushInAppMessages.state.impressions.isEmpty())
        assertTrue(OpenPushInAppMessages.state.redisplay.isEmpty())
        assertTrue(OpenPushInAppMessages.state.displayedGeneration.isEmpty())
    }

    @Test fun listenersArePostedToTheMainThreadInTheOrderRaised() {
        val seen = mutableListOf<String>()
        val onMain = { Looper.myLooper() == Looper.getMainLooper() }
        OpenPushInAppMessages.addLifecycleListener { seen += "${it.phase}:${onMain()}" }
        OpenPushInAppMessages.addClickListener { seen += "click:${onMain()}" }

        OpenPushInAppMessages.engine.previewDocument(activity, document)
        presenter.emit(PresenterEvent.Rendered("center", 260, false))
        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c", actionId = "a", closes = true)))
        assertTrue("a listener ran inside the engine's own step", seen.isEmpty())

        idle()

        assertEquals(
            listOf("willDisplay:true", "didDisplay:true", "click:true", "willDismiss:true", "didDismiss:true"),
            seen,
        )
    }

    @Test fun aListenerRemovedBeforeThePostRunsIsNotCalled() {
        val phases = mutableListOf<String>()
        val id = OpenPushInAppMessages.addLifecycleListener { phases += it.phase }

        OpenPushInAppMessages.engine.previewDocument(activity, document)
        OpenPushInAppMessages.removeLifecycleListener(id)
        idle()

        assertTrue(phases.isEmpty())
    }

    @Test fun aPreviewClickReachesTheHostAndNotTheServer() {
        val clicks = mutableListOf<InAppMessageClickEvent>()
        OpenPushInAppMessages.addClickListener(clicks::add)

        OpenPushInAppMessages.engine.previewDocument(activity, document)
        presenter.emit(PresenterEvent.Rendered("center", 260, false))
        presenter.emit(
            PresenterEvent.Action(IamAction(clickId = "demo-click", actionId = "demo", closes = true)),
        )
        idle()

        assertEquals(1, clicks.size)
        assertEquals(IAM_PREVIEW_ID, clicks.single().messageId)
        assertEquals("demo", clicks.single().actionId)
        assertTrue(clicks.single().firstClick)
        assertTrue("a preview click posted a receipt: ${ingestRequests()}", ingestRequests().isEmpty())
        assertTrue(OpenPushInAppMessages.state.clicked.isEmpty())
        assertTrue(OpenPushInAppMessages.state.dismissed.isEmpty())
        assertTrue(OpenPushInAppMessages.state.redisplay.isEmpty())
    }

    @Test fun aPreviewDismissalLeavesTheRealQueueAlone() {
        fetchFixture(oneMessage("a"))
        val shownBefore = presenter.shows.size

        OpenPushInAppMessages.engine.previewDocument(activity, document)
        presenter.emit(PresenterEvent.Rendered("center", 260, false))
        presenter.emit(PresenterEvent.Dismissed)
        idle()

        // The message the engine is actually displaying is untouched: still current, still not
        // dismissed, and no second surface was asked for on its behalf.
        assertEquals("a", OpenPushInAppMessages.engine.current?.id)
        assertEquals(shownBefore, presenter.shows.size)
        assertTrue(OpenPushInAppMessages.state.dismissed.isEmpty())
    }
}
