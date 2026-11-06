package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import com.alpha.assistant.R
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The compact and full activities share chat, drafts, and active requests. */
class AssistantActivity : AlphyActivity() {
    private var invocation by mutableStateOf<String?>(null)
    private var lockedLaunch by mutableStateOf(false)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        receiveInvocation(intent)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            AlphaTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)).clickable { finish() })
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                        val density = LocalDensity.current
                        val keyboard = WindowInsets.ime.getBottom(density) > 0
                        val compactHeight = minOf(maxHeight, maxOf(310.dp, maxHeight * 0.56f))
                        val panel = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("assistant-sheet")
                        if (lockedLaunch) {
                            Surface(panel.height(compactHeight), shape = MaterialTheme.shapes.extraLarge) {
                                UnlockAssistant(unlock = ::requestUnlock, dismiss = ::finish)
                            }
                        } else {
                            val model = remember { AssistantChat.model(application) }
                            val chat = model.snapshot.selected
                            val latest = chat?.messages?.lastOrNull { it.role == "assistant" }
                            val answer = latest?.let { model.activeAnswer(it.id) ?: it.text }.orEmpty()
                            val longAnswer = answer.length >= (520 / density.fontScale).toInt() || answer.count { it == '\n' } >= 9
                            val expanded = longAnswer || keyboard || maxHeight < 420.dp
                            Surface(panel.height(if (expanded) maxHeight else compactHeight), shape = MaterialTheme.shapes.extraLarge) {
                                CompactAssistant(
                                    model = model,
                                    invocation = invocation,
                                    canUseCapture = ::canUseCapture,
                                    compact = maxHeight < 360.dp,
                                    full = { openApp() },
                                    openTool = {
                                        val id = model.tools.selectedId
                                        if (id == null) {
                                            model.openCreationSurface(ShellDestination.MiniApps, "")
                                            openApp(preserveDestination = true)
                                        } else {
                                            openApp(toolId = id)
                                        }
                                    },
                                    providerSettings = { openApp(providerSettings = true) },
                                    openAutomation = { id ->
                                        model.openAutomationSurface(id)
                                        openApp(preserveDestination = true)
                                    },
                                    dismiss = ::finish,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveInvocation(intent)
    }

    private fun receiveInvocation(intent: Intent) {
        val incoming = intent.getStringExtra(ASSIST_INVOCATION)
        invocation?.takeIf { it != incoming }?.let(AssistantCapture::close)
        invocation = incoming
        lockedLaunch = assistantUnavailable()
        if (lockedLaunch) closeCapture()
        setShowWhenLocked(lockedLaunch)
    }

    private fun requestUnlock() {
        val launchIntent = intent
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                if (isFinishing || intent !== launchIntent || assistantUnavailable()) return
                setShowWhenLocked(false)
                lockedLaunch = false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!lockedLaunch && assistantUnavailable()) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!lockedLaunch && assistantUnavailable()) finish()
    }

    override fun onStop() {
        if (!isChangingConfigurations) invocation?.let(AssistantCapture::clear)
        super.onStop()
    }

    private fun canUseCapture(id: String): Boolean =
        invocation == id && AssistantCapture.current?.id == id && !isFinishing && !lockedLaunch && !assistantUnavailable()

    private fun openApp(toolId: String? = null, providerSettings: Boolean = false, preserveDestination: Boolean = false) {
        if (!assistantUnavailable()) {
            val entry = SurfaceEntry.chatIntent(this)
            if (preserveDestination) entry.action = null
            if (toolId != null) entry.putExtra("tool_id", toolId)
            if (providerSettings) entry.putExtra("settings", true)
            startActivity(entry)
        }
        finish()
    }

    private fun closeCapture() {
        val id = invocation
        invocation = null
        id?.let(AssistantCapture::close)
    }

    override fun finish() {
        closeCapture()
        super.finish()
    }

    override fun onDestroy() {
        if (isFinishing) closeCapture()
        unregisterReceiver(screenOff)
        super.onDestroy()
    }
}

@Composable
private fun UnlockAssistant(unlock: () -> Unit, dismiss: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.assistant_unlock_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = unlock) { Text(stringResource(R.string.assistant_unlock)) }
        TextButton(onClick = dismiss) { Text(stringResource(R.string.assistant_locked_dismiss)) }
    }
}

private enum class AssistantMenu { Main, Context, Voice, Permissions, History }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAssistant(
    model: ShellViewModel,
    invocation: String?,
    canUseCapture: (String) -> Boolean,
    compact: Boolean,
    full: () -> Unit,
    openTool: () -> Unit,
    providerSettings: () -> Unit,
    openAutomation: (String) -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val capture = AssistantCapture.current?.takeIf { it.id == invocation && it.hasContent }
    var reviewOrigin by rememberSaveable(invocation) { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf<AssistantMenu?>(null) }
    var historySearch by rememberSaveable { mutableStateOf("") }
    var settingsFailed by remember { mutableStateOf(false) }

    VoiceVisibility(model.voice)
    WeatherLocationAccess(model)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val menuLabel = stringResource(R.string.menu)
            IconButton(onClick = { menu = AssistantMenu.Main }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
                AlphaIcon(Glyph.Menu)
            }
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            TextButton(onClick = full, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.assistant_open_app), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val closeLabel = stringResource(R.string.assistant_close)
            IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                AlphaIcon(Glyph.Close)
            }
        }
        if (!model.loading && !model.loadFailed && capture != null) {
            ScreenSuggestion(capture) { reviewOrigin = model.contextDraftKey() }
        } else if (invocation != null && !compact) {
            Text(stringResource(R.string.assistant_screen_unavailable), style = MaterialTheme.typography.labelSmall)
        }
        when {
            model.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            model.loadFailed -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(stringResource(R.string.assistant_load_error))
                Button(onClick = model::load) { Text(stringResource(R.string.assistant_retry)) }
            }
            else -> {
                ChatPane(
                    model, model.snapshot.selected, tools = openTool,
                    settings = providerSettings, modifier = Modifier.weight(1f), openAutomation = openAutomation,
                )
                Composer(
                    model, context = { menu = AssistantMenu.Context }, modifier = Modifier.widthIn(max = 620.dp),
                    compact = compact, placeholder = R.string.assistant_ask,
                )
            }
        }
    }

    com.alpha.assistant.ui.ChatSetupDialogs(model)
    val secure = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    model.requestError?.let { error ->
        AlertDialog(
            onDismissRequest = model::clearRequestError, title = { Text(stringResource(R.string.assistant_request_error)) },
            text = { Text(error) }, properties = secure,
            confirmButton = { TextButton(onClick = model::clearRequestError) { Text(stringResource(R.string.tool_dialog_close)) } },
        )
    }
    if (model.saveFailed) AlertDialog(
        onDismissRequest = {}, title = { Text(stringResource(R.string.assistant_save_error)) },
        text = { Text(stringResource(R.string.assistant_save_detail)) }, properties = secure,
        confirmButton = { TextButton(onClick = model::retrySave) { Text(stringResource(R.string.assistant_save_retry)) } },
    )

    val origin = reviewOrigin
    if (origin != null && capture != null) key(capture.id) {
        ReviewCapture(
            capture = capture, model = model, origin = origin,
            canAttach = { reviewOrigin == origin && canUseCapture(capture.id) },
            done = { reviewOrigin = null },
            discard = {
                AssistantCapture.clear(capture.id)
                reviewOrigin = null
            },
        )
    }

    if (menu == AssistantMenu.Context && !model.loading && !model.loadFailed) {
        ReviewedContextDialog(model) { menu = null }
    }
    if (menu != null && menu != AssistantMenu.Context) {
        ModalBottomSheet(onDismissRequest = { menu = null }, properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (menu) {
                    AssistantMenu.Voice -> VoiceControls(model)
                    AssistantMenu.Permissions -> {
                        Text(stringResource(R.string.access), style = MaterialTheme.typography.headlineSmall)
                        com.alpha.assistant.capabilities.AndroidAccessOverview()
                    }
                    AssistantMenu.History -> {
                        Text(stringResource(R.string.assistant_history), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(historySearch, { historySearch = it }, label = { Text(stringResource(R.string.assistant_history_search)) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { model.select(null); menu = null }) { Text(stringResource(R.string.new_chat)) }
                        model.snapshot.conversations.filter { it.title.contains(historySearch, ignoreCase = true) }.forEach { chat ->
                            TextButton(onClick = { model.select(chat.id); menu = null }) { Text(chat.title) }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.menu), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { model.select(null); menu = null }) { Text(stringResource(R.string.new_chat)) }
                        TextButton(onClick = { menu = AssistantMenu.History }) { Text(stringResource(R.string.assistant_history)) }
                        TextButton(onClick = { menu = AssistantMenu.Context }) { Text(stringResource(R.string.composer_add_context)) }
                        TextButton(onClick = { menu = AssistantMenu.Voice }) { Text(stringResource(R.string.assistant_continuous_voice)) }
                        TextButton(onClick = { menu = AssistantMenu.Permissions }) { Text(stringResource(R.string.access)) }
                        TextButton(onClick = providerSettings) { Text(stringResource(R.string.assistant_provider_settings)) }
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                                dismiss()
                            } catch (_: ActivityNotFoundException) {
                                settingsFailed = true
                            }
                        }) { Text(stringResource(R.string.assistant_open)) }
                        Text(stringResource(R.string.assistant_pick_hint), style = MaterialTheme.typography.bodySmall)
                        if (settingsFailed) StatusLine(Glyph.Close, MaterialTheme.colorScheme.error, stringResource(R.string.assistant_open_failed), PoliteLiveRegion)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenSuggestion(capture: AssistCapture, review: () -> Unit) {
    val preview = capture.text?.lineSequence()?.firstOrNull()?.take(48)
    val label = if (preview.isNullOrBlank()) stringResource(R.string.assistant_screen_suggestion)
        else stringResource(R.string.assistant_screen_suggestion_text, preview)
    val outline = MaterialTheme.colorScheme.outline
    Text(
        label,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .drawWithCache {
                val stroke = 1.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                onDrawBehind {
                    drawRoundRect(outline, topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke), cornerRadius = CornerRadius(16.dp.toPx()),
                        style = Stroke(stroke, pathEffect = dash))
                }
            }
            .clickable(role = Role.Button, onClick = review).heightIn(min = 48.dp).wrapContentHeight()
            .padding(horizontal = 12.dp),
        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ReviewCapture(
    capture: AssistCapture,
    model: ShellViewModel,
    origin: String,
    canAttach: () -> Boolean,
    done: () -> Unit,
    discard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf(false) }
    val enabled = !savingImage && !model.attachmentBusy
    AlertDialog(
        onDismissRequest = done, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.assistant_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assistant_review_detail))
                capture.text?.let { text ->
                    SelectionContainer {
                        Text(text, Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()))
                    }
                    val source = stringResource(R.string.assistant_screen_text)
                    TextButton(enabled = enabled, onClick = {
                        if (canAttach() && model.addReviewedText(text, source, origin)) AssistantCapture.update(capture.id, text = null)
                    }) { Text(stringResource(R.string.assistant_attach_text)) }
                }
                capture.screenshot?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), stringResource(R.string.assistant_screen_image), Modifier.fillMaxWidth().heightIn(max = 128.dp))
                    TextButton(enabled = enabled, onClick = {
                        if (!canAttach()) return@TextButton
                        savingImage = true
                        imageError = false
                        scope.launch {
                            var image: Attachment? = null
                            var attached = false
                            try {
                                withContext(Dispatchers.IO) { image = AssistContext.screenshot(context, bitmap) }
                                if (canAttach()) {
                                    attached = model.attachImage(checkNotNull(image), origin, canAttach = canAttach)
                                    if (attached) AssistantCapture.update(capture.id, screenshot = null)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (canAttach()) imageError = true
                            } finally {
                                val rejected = image?.takeUnless { attached }
                                if (rejected != null) withContext(NonCancellable + Dispatchers.IO) {
                                    AssistContext.mediaFile(context, Uri.parse(rejected.uri)).delete()
                                }
                                savingImage = false
                            }
                        }
                    }) { Text(stringResource(R.string.assistant_attach_image)) }
                }
                if (imageError) Text(stringResource(R.string.assistant_image_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = done) { Text(stringResource(R.string.assistant_review_done)) } },
        dismissButton = { TextButton(onClick = discard) { Text(stringResource(R.string.assistant_discard_capture)) } },
    )
}
