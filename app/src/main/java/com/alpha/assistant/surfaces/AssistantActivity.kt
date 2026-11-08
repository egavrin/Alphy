package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alpha.assistant.R
import com.alpha.assistant.ui.*

private const val NEW_CHAT_PENDING = "new_chat_pending"

/** The compact and full activities share chat, drafts, and active requests. */
class AssistantActivity : AlphyActivity() {
    private var invocation by mutableStateOf<String?>(null)
    private var newChatPending by mutableStateOf(false)
    private var lockedLaunch by mutableStateOf(false)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        newChatPending = savedInstanceState?.getBoolean(NEW_CHAT_PENDING) == true
        receiveInvocation(intent, newEntry = savedInstanceState == null)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            AlphaTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)).clickable { finish() })
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                        val density = LocalDensity.current
                        val keyboard = WindowInsets.ime.getBottom(density) > 0
                        val compactHeight = minOf(maxHeight, maxOf(310.dp, maxHeight * 0.44f))
                        val panel = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("assistant-sheet")
                        if (lockedLaunch) {
                            Surface(panel.height(compactHeight), shape = MaterialTheme.shapes.extraLarge) {
                                UnlockAssistant(unlock = ::requestUnlock, dismiss = ::finish)
                            }
                        } else {
                            val model = remember { AssistantChat.model(application) }
                            if (newChatPending && !model.loadFailed) {
                                // Select the new chat before the composer can bind the screenshot.
                                LaunchedEffect(model.loading) {
                                    if (newChatPending && !model.loading && !lockedLaunch && !isFinishing) {
                                        model.select(null)
                                        newChatPending = false
                                    }
                                }
                                Surface(panel.height(compactHeight), shape = MaterialTheme.shapes.extraLarge) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            } else {
                                val chat = model.snapshot.selected
                                val latest = chat?.messages?.lastOrNull { it.role == "assistant" }
                                val answer = latest?.let { model.activeAnswer(it.id) ?: it.text }.orEmpty()
                                val longAnswer = answer.length >= (520 / density.fontScale).toInt() || answer.count { it == '\n' } >= 9
                                val expanded = longAnswer || keyboard || maxHeight < 420.dp ||
                                    model.snapshot.draft.attachments.isNotEmpty()
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveInvocation(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(NEW_CHAT_PENDING, newChatPending)
    }

    private fun receiveInvocation(intent: Intent, newEntry: Boolean = true) {
        val incoming = intent.getStringExtra(ASSIST_INVOCATION)
        if (newEntry && (incoming == null || incoming != invocation)) {
            newChatPending = intent.action == Intent.ACTION_ASSIST ||
                (incoming != null && incoming == AssistantCapture.current?.id)
        }
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
    val capture = AssistantCapture.current?.takeIf { it.id == invocation }
    val draft = model.snapshot.draft
    val screenshot = capture?.screenshotFor(draft.id)
    val screenText = capture?.text
    LaunchedEffect(capture?.id, capture?.screenshot, draft.id, draft.attachments.size, model.loading, model.loadFailed) {
        if (capture != null && !model.loading && !model.loadFailed && canUseCapture(capture.id)) {
            model.bindAssistantScreenshot(capture.id)
        }
    }
    var reviewOrigin by rememberSaveable(invocation) { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf<AssistantMenu?>(null) }
    var historySearch by rememberSaveable { mutableStateOf("") }
    var settingsFailed by remember { mutableStateOf(false) }

    VoiceVisibility(model.voice)
    WeatherLocationAccess(model)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 8.dp).size(38.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            val menuLabel = stringResource(R.string.menu)
            IconButton(onClick = { menu = AssistantMenu.Main }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
                AlphaIcon(Glyph.MoreVertical)
            }
            val closeLabel = stringResource(R.string.assistant_close)
            IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                AlphaIcon(Glyph.Close)
            }
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
                if (screenshot != null && capture != null) {
                    AssistantScreenshot(screenshot, compact) { AssistantCapture.discardScreenshot(capture.id) }
                } else if (screenText != null) {
                    val preview = screenText.lineSequence().first().take(48)
                    TextButton(onClick = { reviewOrigin = draft.id }) {
                        Text(stringResource(R.string.assistant_screen_suggestion_text, preview), maxLines = 1)
                    }
                }
                Composer(
                    model, context = { menu = AssistantMenu.Context }, modifier = Modifier.widthIn(max = 620.dp),
                    compact = compact, placeholder = R.string.assistant_ask,
                    assistantInvocation = capture?.id, requiresQuestion = screenshot != null,
                )
            }
        }
    }

    com.alpha.assistant.ui.ChatSetupDialogs(model)
    model.requestError?.let { error ->
        AlertDialog(
            onDismissRequest = model::clearRequestError, title = { Text(stringResource(R.string.assistant_request_error)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = model::clearRequestError) { Text(stringResource(R.string.tool_dialog_close)) } },
        )
    }
    if (model.saveFailed) AlertDialog(
        onDismissRequest = {}, title = { Text(stringResource(R.string.assistant_save_error)) },
        text = { Text(stringResource(R.string.assistant_save_detail)) },
        confirmButton = { TextButton(onClick = model::retrySave) { Text(stringResource(R.string.assistant_save_retry)) } },
    )

    val origin = reviewOrigin
    if (origin != null && capture != null && screenText != null) key(capture.id) {
        val source = stringResource(R.string.assistant_screen_text)
        ReviewScreenText(
            text = screenText, enabled = !model.attachmentBusy,
            attach = {
                if (reviewOrigin == origin && canUseCapture(capture.id) && model.addReviewedText(screenText, source, origin)) {
                    AssistantCapture.update(capture.id, text = null)
                    reviewOrigin = null
                }
            },
            done = { reviewOrigin = null },
            discard = {
                AssistantCapture.update(capture.id, text = null)
                reviewOrigin = null
            },
        )
    }

    if (menu == AssistantMenu.Context && !model.loading && !model.loadFailed) {
        ReviewedContextDialog(model) { menu = null }
    }
    if (menu != null && menu != AssistantMenu.Context) {
        ModalBottomSheet(onDismissRequest = { menu = null }) {
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
                        TextButton(onClick = full) { Text(stringResource(R.string.assistant_open_app)) }
                        TextButton(onClick = { model.select(null); menu = null }) { Text(stringResource(R.string.new_chat)) }
                        TextButton(onClick = { menu = AssistantMenu.History }) { Text(stringResource(R.string.assistant_history)) }
                        TextButton(onClick = { menu = AssistantMenu.Context }) { Text(stringResource(R.string.composer_add_context)) }
                        if (screenText != null && !model.loading && !model.loadFailed) {
                            TextButton(onClick = { menu = null; reviewOrigin = draft.id }) {
                                Text(stringResource(R.string.assistant_review_title))
                            }
                        }
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
private fun AssistantScreenshot(screenshot: Bitmap, compact: Boolean, remove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    screenshot.asImageBitmap(), stringResource(R.string.assistant_screen_image),
                    Modifier.size(if (compact) 48.dp else 64.dp).clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(12.dp))
                AlphaIcon(Glyph.Check, accent = true)
                Text(
                    stringResource(R.string.assistant_image_attached), Modifier.weight(1f).padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                val removeLabel = stringResource(R.string.assistant_image_remove)
                IconButton(onClick = remove, modifier = Modifier.semantics { contentDescription = removeLabel }) {
                    AlphaIcon(Glyph.Close)
                }
            }
        }
        if (!compact) Text(
            stringResource(R.string.assistant_image_send_hint), Modifier.padding(start = 4.dp, top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewScreenText(text: String, enabled: Boolean, attach: () -> Unit, done: () -> Unit, discard: () -> Unit) {
    AlertDialog(
        onDismissRequest = done,
        title = { Text(stringResource(R.string.assistant_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assistant_review_detail))
                SelectionContainer {
                    Text(text, Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()))
                }
                TextButton(enabled = enabled, onClick = attach) { Text(stringResource(R.string.assistant_attach_text)) }
            }
        },
        confirmButton = { TextButton(onClick = done) { Text(stringResource(R.string.assistant_review_done)) } },
        dismissButton = { TextButton(onClick = discard) { Text(stringResource(R.string.assistant_discard_capture)) } },
    )
}
