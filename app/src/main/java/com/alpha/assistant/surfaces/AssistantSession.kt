package com.alpha.assistant.surfaces

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.alpha.assistant.data.ScreenContext
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** One process owns each in-flight request across compact and full activities. */
object AssistantChat : ViewModelStoreOwner {
    interface ModelSource {
        fun assistantModel(application: Application): ShellViewModel?
    }

    override val viewModelStore = ViewModelStore()

    fun model(application: Application): ShellViewModel =
        (application as? ModelSource)?.assistantModel(application)
            ?: ViewModelProvider(this, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ShellViewModel(application, offerOnboarding = true) as T
            })[ShellViewModel::class.java]
}

internal const val ASSIST_INVOCATION = "assist_invocation"

internal fun Context.assistantUnavailable(): Boolean =
    ToolWidgets.isLocked(this) || !getSystemService(PowerManager::class.java).isInteractive

enum class AssistPageStatus { NONE, LOADING, AVAILABLE, UNAVAILABLE }

data class AssistCapture(
    val id: String,
    val screen: ScreenContext? = null,
    val screenshot: Bitmap? = null,
    val draft: String? = null,
    val pageStatus: AssistPageStatus = AssistPageStatus.NONE,
    val screenDiscarded: Boolean = false,
    val pageDiscarded: Boolean = false,
    val screenshotDiscarded: Boolean = false,
) {
    fun forDraft(draftId: String): AssistCapture? = takeIf { draft == draftId }
}

object AssistantCapture {
    var current by mutableStateOf<AssistCapture?>(null)
        private set
    private var owner: Pair<String, () -> Unit>? = null
    private var pageRead: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    fun begin(onClose: () -> Unit = {}): String = UUID.randomUUID().toString().also { id ->
        cancelPage()
        owner = id to onClose
        current = AssistCapture(id)
    }

    fun updateScreen(id: String, screen: ScreenContext) {
        val capture = current?.takeIf { it.id == id } ?: return
        val source = if (capture.screenDiscarded) screen.onlyPage() else screen
        val accepted = if (capture.pageDiscarded) source.withoutPage() else source
        current = capture.copy(screen = accepted.nonempty())
    }

    fun updateScreenshot(id: String, screenshot: Bitmap?) {
        val capture = current?.takeIf { it.id == id && !it.screenshotDiscarded } ?: return
        current = capture.copy(screenshot = screenshot)
    }

    internal fun readPage(id: String, context: Context, reference: android.net.Uri) {
        val capture = current?.takeIf { it.id == id && !it.pageDiscarded } ?: return
        current = capture.copy(pageStatus = AssistPageStatus.LOADING)
        pageRead = scope.launch {
            val result = ScreenAssist.readChrome(context, reference)
            val latest = current?.takeIf { it.id == id && !it.pageDiscarded } ?: return@launch
            if (context.assistantUnavailable()) {
                clear(id)
                return@launch
            }
            current = latest.copy(
                screen = (latest.screen ?: ScreenContext()).copy(
                    pageText = result?.text, pageTruncated = result?.truncated == true,
                ).nonempty(),
                pageStatus = if (result == null) AssistPageStatus.UNAVAILABLE else AssistPageStatus.AVAILABLE,
            )
        }
    }

    fun bind(id: String, draft: String) {
        val capture = current?.takeIf { it.id == id } ?: return
        if (capture.draft == null) current = capture.copy(draft = draft)
        else if (capture.draft != draft) discardAll(id)
    }

    fun discardScreen(id: String) {
        val capture = current?.takeIf { it.id == id } ?: return
        current = capture.copy(screen = capture.screen?.onlyPage()?.nonempty(), screenDiscarded = true)
    }

    fun discardPage(id: String) {
        val capture = current?.takeIf { it.id == id } ?: return
        cancelPage()
        current = capture.copy(screen = capture.screen?.withoutPage()?.nonempty(),
            pageStatus = AssistPageStatus.NONE, pageDiscarded = true)
    }

    fun discardScreenshot(id: String) {
        val capture = current?.takeIf { it.id == id } ?: return
        current = capture.copy(screenshot = null, screenshotDiscarded = true)
    }

    fun discardAll(id: String) {
        val capture = current?.takeIf { it.id == id } ?: return
        cancelPage()
        current = AssistCapture(id, draft = capture.draft,
            screenDiscarded = true, pageDiscarded = true, screenshotDiscarded = true)
    }

    fun clear(id: String) {
        if (current?.id != id) return
        cancelPage()
        current = null
    }

    fun close(id: String) {
        val closing = owner?.takeIf { it.first == id } ?: return
        owner = null
        clear(id)
        closing.second()
    }

    internal fun release(id: String) {
        if (owner?.first == id) owner = null
        clear(id)
    }

    private fun cancelPage() {
        pageRead?.cancel()
        pageRead = null
    }
}

class AlphaVoiceInteractionService : VoiceInteractionService()

class AlphaVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AlphaVoiceSession(this)
}

class AlphaVoiceSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private var invocation: String? = null
    private var shown = false
    private var receiveText = false
    private var receiveScreenshot = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
        invocation?.let(AssistantCapture::release)
        // The platform callbacks carry no show ID. A reused session cannot safely attribute late data.
        receiveText = !shown && showFlags and SHOW_WITH_ASSIST != 0
        receiveScreenshot = !shown && showFlags and SHOW_WITH_SCREENSHOT != 0
        shown = true
        invocation = if (appContext.assistantUnavailable()) null else AssistantCapture.begin { finish() }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val id = invocation
        if (id == null || appContext.assistantUnavailable()) {
            endSession()
            return
        }
        try {
            startAssistantActivity(Intent(appContext, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ASSIST_INVOCATION, id))
        } catch (_: android.content.ActivityNotFoundException) {
            endSession()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        val id = invocation ?: return
        if (!receiveText || !state.isFocused || appContext.assistantUnavailable() || AssistantCapture.current?.id != id) return
        receiveText = false
        val captured = runCatching {
            ScreenAssist.capture(appContext, state.assistStructure, state.assistContent)
        }.getOrNull() ?: return
        AssistantCapture.updateScreen(id, captured.screen)
        captured.pageReference?.let { AssistantCapture.readPage(id, appContext, it) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val id = invocation ?: return
        if (!receiveScreenshot || appContext.assistantUnavailable() || AssistantCapture.current?.id != id) return
        receiveScreenshot = false
        AssistantCapture.updateScreenshot(id, screenshot)
    }

    override fun onHide() {
        super.onHide()
        if (invocation != null) endSession()
    }

    override fun onLockscreenShown() {
        endSession()
    }

    override fun onDestroy() {
        invocation?.let(AssistantCapture::release)
        invocation = null
        super.onDestroy()
    }

    private fun endSession() {
        invocation?.let(AssistantCapture::release)
        invocation = null
        finish()
    }

}

/** System metadata requires a recognition component. App voice remains an explicit in-app action. */
class AlphaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback) = Unit
}
