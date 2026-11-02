package com.alpha.assistant.surfaces

import android.app.Application
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
    override val viewModelStore = ViewModelStore()
    fun model(application: Application) = ViewModelProvider(this,ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ShellViewModel::class.java]
}

data class AssistCapture(val id: String, val text: String? = null, val screenshot: Bitmap? = null,
    val status: String = "Screen context unavailable. Android can omit protected or disabled screen data.")
object AssistantCapture {
    var current by mutableStateOf<AssistCapture?>(null); private set
    fun begin(): String = UUID.randomUUID().toString().also { current = AssistCapture(it) }
    fun update(id: String, text: String? = current?.text, screenshot: Bitmap? = current?.screenshot) {
        val capture = current?.takeIf { it.id == id } ?: return
        current = capture.copy(text = text,screenshot = screenshot)
    }
    fun clear(id: String) { if(current?.id == id) current = null }
}

class AlphaVoiceInteractionService : VoiceInteractionService()
class AlphaVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AlphaVoiceSession(this)
}

class AlphaVoiceSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private var invocation: String? = null
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args,showFlags)
        setUiEnabled(false)
        invocation?.let(AssistantCapture::clear)
        invocation = AssistantCapture.begin()
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args,showFlags)
        if(ToolWidgets.isLocked(appContext)) { invocation?.let(AssistantCapture::clear) }
        startAssistantActivity(Intent(appContext,AssistantActivity::class.java).putExtra("assist_invocation",invocation))
    }
    override fun onHandleAssist(state: AssistState) {
        val id = invocation ?: return
        if(ToolWidgets.isLocked(appContext) || !state.isFocused) return
        val structure = state.assistStructure ?: return
        val text = runCatching { visibleText(structure) }.getOrNull()
        AssistantCapture.update(id,text = text)
    }
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val id = invocation ?: return
        if(ToolWidgets.isLocked(appContext)) return
        AssistantCapture.update(id,screenshot = screenshot)
    }
    override fun onLockscreenShown() { invocation?.let(AssistantCapture::clear); hide(); super.onLockscreenShown() }
    override fun onDestroy() { invocation?.let(AssistantCapture::clear); super.onDestroy() }
    private fun visibleText(structure: AssistStructure): String? {
        val text = StringBuilder()
        var count = 0
        fun visit(node: AssistStructure.ViewNode, depth: Int) {
            if(++count > 2000 || depth > 20 || text.length >= 8000 || node.isAssistBlocked || node.visibility != View.VISIBLE) return
            val kind = node.inputType and InputType.TYPE_MASK_CLASS
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val password = (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            if(!password) node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                if(text.isNotEmpty()) text.append('\n')
                text.append(it.take(8000-text.length))
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
