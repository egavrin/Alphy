package com.alpha.assistant.surfaces

import android.app.Application
import android.app.assist.AssistStructure
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
import android.text.InputType
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.alpha.assistant.ui.ShellViewModel
import java.util.UUID

/** One process owns each in-flight request across compact and full activities. */
object AssistantChat : ViewModelStoreOwner {
    interface ModelSource {
        fun assistantModel(application: Application): ShellViewModel?
    }

    override val viewModelStore = ViewModelStore()

    fun model(application: Application): ShellViewModel =
        (application as? ModelSource)?.assistantModel(application)
            ?: ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ShellViewModel::class.java]
}

internal const val ASSIST_INVOCATION = "assist_invocation"

internal fun Context.assistantUnavailable(): Boolean =
    ToolWidgets.isLocked(this) || !getSystemService(PowerManager::class.java).isInteractive

data class AssistCapture(val id: String, val text: String? = null, val screenshot: Bitmap? = null) {
    val hasContent: Boolean get() = text != null || screenshot != null
}

object AssistantCapture {
    var current by mutableStateOf<AssistCapture?>(null)
        private set
    private var owner: Pair<String, () -> Unit>? = null

    fun begin(onClose: () -> Unit = {}): String = UUID.randomUUID().toString().also { id ->
        owner = id to onClose
        current = AssistCapture(id)
    }

    fun update(id: String, text: String? = current?.text, screenshot: Bitmap? = current?.screenshot) {
        val capture = current?.takeIf { it.id == id } ?: return
        current = capture.copy(text = text, screenshot = screenshot)
    }

    fun clear(id: String) {
        if (current?.id == id) current = null
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
        val text = state.assistStructure?.let { runCatching { visibleText(it) }.getOrNull() }
        AssistantCapture.update(id, text = text)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val id = invocation ?: return
        if (!receiveScreenshot || appContext.assistantUnavailable() || AssistantCapture.current?.id != id) return
        receiveScreenshot = false
        AssistantCapture.update(id, screenshot = screenshot)
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

    private fun visibleText(structure: AssistStructure): String? {
        val text = StringBuilder()
        var count = 0
        fun visit(node: AssistStructure.ViewNode, depth: Int) {
            if(++count > 2000 || depth > 20 || text.length >= 8000 || node.isAssistBlocked || node.visibility != View.VISIBLE) return
            val kind = node.inputType and InputType.TYPE_MASK_CLASS
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val password = (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            if (password) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                if (text.isNotEmpty()) text.append('\n')
                val part = it.take(8000 - text.length)
                text.append(if (part.lastOrNull()?.isHighSurrogate() == true) part.dropLast(1) else part)
            }
            repeat(minOf(node.childCount,2000-count)) { visit(node.getChildAt(it),depth+1) }
        }
        repeat(minOf(structure.windowNodeCount,20)) { visit(structure.getWindowNodeAt(it).rootViewNode,0) }
        return text.toString().takeIf { it.isNotBlank() }
    }
}

/** System metadata requires a recognition component. App voice remains an explicit in-app action. */
class AlphaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onStopListening(listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback) = Unit
}
