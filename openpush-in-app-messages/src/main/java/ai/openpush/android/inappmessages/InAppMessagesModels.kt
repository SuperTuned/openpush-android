package ai.openpush.android.inappmessages

internal data class IamCondition(
    val id: String,
    val kind: String,
    val property: String?,
    val op: String,
    val value: Any?,
)

/**
 * One in-app message as the server described it.
 *
 * [endTimeMs] is the row's `end_time`: past it, the message is still a definition but is never
 * queued. [hasDynamicTriggers] marks a message no host call can ever make eligible — its triggers
 * are satisfied by the clock alone — so the engine knows it must re-evaluate on its own.
 */
internal data class IamMessage(
    val id: String,
    val htmlPath: String,
    val layout: String,
    val triggers: List<List<IamCondition>>,
    val displayDurationMs: Long?,
    val redisplayLimit: Int,
    val redisplayDelayMs: Long,
    val endTimeMs: Long?,
    val hasDynamicTriggers: Boolean,
)

/**
 * Where a message is in its display, as a value a `when` can be exhaustive over.
 * [InAppMessageLifecycleEvent.phase] carries the same four facts as text and is kept for hosts
 * written against it.
 */
enum class InAppLifecycleStage { WILL_DISPLAY, DID_DISPLAY, WILL_DISMISS, DID_DISMISS, UNKNOWN }

/**
 * One step of a message's display. [phase] and [stage] always agree; the two-argument constructor
 * derives the stage from the phase. A phase introduced by a newer SDK maps to [InAppLifecycleStage.UNKNOWN]
 * so the two-argument constructor remains tolerant of arbitrary phase text.
 */
class InAppMessageLifecycleEvent(val messageId: String, val phase: String, val stage: InAppLifecycleStage) {
    constructor(messageId: String, phase: String) : this(messageId, phase, stageOf(phase))

    operator fun component1(): String = messageId

    operator fun component2(): String = phase

    operator fun component3(): InAppLifecycleStage = stage

    /** The two-argument copy descriptor. The typed stage is derived from the copied phase. */
    fun copy(messageId: String, phase: String): InAppMessageLifecycleEvent =
        InAppMessageLifecycleEvent(messageId, phase)

    /** The complete copy surface. */
    fun copy(
        messageId: String = this.messageId,
        phase: String = this.phase,
        stage: InAppLifecycleStage = this.stage,
    ): InAppMessageLifecycleEvent = InAppMessageLifecycleEvent(messageId, phase, stage)

    override fun equals(other: Any?): Boolean = other is InAppMessageLifecycleEvent &&
        messageId == other.messageId &&
        phase == other.phase &&
        stage == other.stage

    override fun hashCode(): Int = 31 * (31 * messageId.hashCode() + phase.hashCode()) + stage.hashCode()

    override fun toString(): String = "InAppMessageLifecycleEvent(messageId=$messageId, phase=$phase, stage=$stage)"

    companion object {
        private fun stageOf(phase: String): InAppLifecycleStage = when (phase) {
            "willDisplay" -> InAppLifecycleStage.WILL_DISPLAY
            "didDisplay" -> InAppLifecycleStage.DID_DISPLAY
            "willDismiss" -> InAppLifecycleStage.WILL_DISMISS
            "didDismiss" -> InAppLifecycleStage.DID_DISMISS
            else -> InAppLifecycleStage.UNKNOWN
        }

        /** The static `copy$default` helper of the shorter copy, for compiled Kotlin callers. */
        @JvmStatic
        @Suppress("FunctionName", "ktlint:standard:function-naming")
        fun `copy$default`(
            source: InAppMessageLifecycleEvent,
            messageId: String?,
            phase: String?,
            mask: Int,
            marker: Any?,
        ): InAppMessageLifecycleEvent {
            check(marker == null) { "Super calls with default arguments are not supported" }
            return source.copy(
                if (mask and 1 != 0) source.messageId else requireNotNull(messageId),
                if (mask and 2 != 0) source.phase else requireNotNull(phase),
            )
        }
    }
}

/**
 * One tap the host is told about. Every action carrying a click id produces one of these, including
 * a repeat of a tap already reported to the server — the receipt is deduplicated, the callback is
 * not, because a second tap is a second thing the user did.
 *
 * [firstClick] is about the message, not the button: it is true only for the very first click id
 * recorded against this message. [pageId] is the page the document last reported moving to, or
 * `null` for a single-page document.
 */
class InAppMessageClickEvent @JvmOverloads constructor(
    val messageId: String,
    val actionId: String?,
    val url: String?,
    val urlTarget: String?,
    val closingMessage: Boolean,
    val firstClick: Boolean = false,
    val pageId: String? = null,
) {
    operator fun component1(): String = messageId

    operator fun component2(): String? = actionId

    operator fun component3(): String? = url

    operator fun component4(): String? = urlTarget

    operator fun component5(): Boolean = closingMessage

    operator fun component6(): Boolean = firstClick

    operator fun component7(): String? = pageId

    /** The five-argument copy descriptor. The other fields stay attached when a caller uses it. */
    fun copy(
        messageId: String,
        actionId: String?,
        url: String?,
        urlTarget: String?,
        closingMessage: Boolean,
    ): InAppMessageClickEvent =
        InAppMessageClickEvent(messageId, actionId, url, urlTarget, closingMessage, firstClick, pageId)

    /** The complete copy surface. */
    fun copy(
        messageId: String = this.messageId,
        actionId: String? = this.actionId,
        url: String? = this.url,
        urlTarget: String? = this.urlTarget,
        closingMessage: Boolean = this.closingMessage,
        firstClick: Boolean = this.firstClick,
        pageId: String? = this.pageId,
    ): InAppMessageClickEvent =
        InAppMessageClickEvent(messageId, actionId, url, urlTarget, closingMessage, firstClick, pageId)

    override fun equals(other: Any?): Boolean = other is InAppMessageClickEvent &&
        messageId == other.messageId &&
        actionId == other.actionId &&
        url == other.url &&
        urlTarget == other.urlTarget &&
        closingMessage == other.closingMessage &&
        firstClick == other.firstClick &&
        pageId == other.pageId

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + (actionId?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (urlTarget?.hashCode() ?: 0)
        result = 31 * result + closingMessage.hashCode()
        result = 31 * result + firstClick.hashCode()
        return 31 * result + (pageId?.hashCode() ?: 0)
    }

    override fun toString(): String = "InAppMessageClickEvent(messageId=$messageId, actionId=$actionId, url=$url, " +
        "urlTarget=$urlTarget, closingMessage=$closingMessage, firstClick=$firstClick, pageId=$pageId)"

    companion object {
        /** The static `copy$default` helper of the shorter copy, for compiled Kotlin callers. */
        @JvmStatic
        @Suppress("FunctionName", "ktlint:standard:function-naming")
        fun `copy$default`(
            source: InAppMessageClickEvent,
            messageId: String?,
            actionId: String?,
            url: String?,
            urlTarget: String?,
            closingMessage: Boolean,
            mask: Int,
            marker: Any?,
        ): InAppMessageClickEvent {
            check(marker == null) { "Super calls with default arguments are not supported" }
            return source.copy(
                if (mask and 1 != 0) source.messageId else requireNotNull(messageId),
                if (mask and 2 != 0) source.actionId else actionId,
                if (mask and 4 != 0) source.url else url,
                if (mask and 8 != 0) source.urlTarget else urlTarget,
                if (mask and 16 != 0) source.closingMessage else closingMessage,
            )
        }
    }
}
