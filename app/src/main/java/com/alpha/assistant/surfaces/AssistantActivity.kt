package com.alpha.assistant.surfaces

import android.app.KeyguardManager
import android.content.*
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alpha.assistant.data.Attachment
import com.alpha.assistant.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** System session activity uses the same chat owner as MainActivity, including active requests. */
class AssistantActivity : AlphyActivity() {
    private var invocation by mutableStateOf<String?>(null)
    private var unlocked by mutableStateOf(false)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unlocked = false
            invocation?.let(AssistantCapture::clear)
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        invocation = intent.getStringExtra("assist_invocation")
        unlocked = !ToolWidgets.isLocked(this)
        registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        setContent {
            AlphaTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable { finish() })
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Surface(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.78f),shape = MaterialTheme.shapes.extraLarge) {
                            if(!unlocked) Column(Modifier.padding(24.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Разблокируйте устройство")
                                Text("История и контекст экрана скрыты, пока устройство заблокировано.")
                                Button(onClick = {
                                    invocation?.let(AssistantCapture::clear)
                                    getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this@AssistantActivity,object : KeyguardManager.KeyguardDismissCallback() {
                                        override fun onDismissSucceeded() { unlocked = !ToolWidgets.isLocked(this@AssistantActivity) }
                                    })
                                }) { Text("Разблокировать") }
                                TextButton(onClick = { finish() }) { Text("Отмена") }
                            } else {
                                val model = remember { AssistantChat.model(application) }
                                CompactAssistant(model,invocation,{ startActivity(SurfaceEntry.chatIntent(this@AssistantActivity).putExtra("tool_id",model.tools.selectedId)); finish() },{ finish() })
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); invocation = intent.getStringExtra("assist_invocation"); unlocked = !ToolWidgets.isLocked(this) }
    override fun onDestroy() {
        unregisterReceiver(screenOff)
        if(isFinishing) invocation?.let(AssistantCapture::clear)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CompactAssistant(model: ShellViewModel, invocation: String?, full: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = AssistantCapture.current?.takeIf { it.id == invocation }
    var review by rememberSaveable(invocation) { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewOrigin by rememberSaveable { mutableStateOf("") }
    var historySearch by rememberSaveable { mutableStateOf("") }
    var attachmentBusy by remember { mutableStateOf(false) }
    var contextError by remember { mutableStateOf<String?>(null) }
    VoiceVisibility(model.voice)
    WeatherLocationAccess(model)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            ShellHeader(false, {}, { sheet = "menu" }, { sheet = "history" })
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = full) { Text("Продолжить в приложении") }
                TextButton(onClick = dismiss) { Text("Закрыть") }
            }
            Column(Modifier.padding(horizontal = 16.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if(capture?.text != null || capture?.screenshot != null) {
                    Text("Текущий экран · на устройстве, не прикреплён",style = MaterialTheme.typography.labelLarge)
                    FlowRow {
                        TextButton(onClick = { reviewOrigin = model.contextDraftKey(); review = true }) { Text("Проверить контекст экрана") }
                        TextButton(onClick = { invocation?.let(AssistantCapture::clear) }) { Text("Убрать") }
                    }
                } else Text(capture?.status ?: "Контекст экрана недоступен для этого вызова.",style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    TextButton(onClick = { sheet = "context" }) { Text("Добавить контекст") }
                }
                contextError?.let { Text(it,color = MaterialTheme.colorScheme.error) }
            }
        }
        if(model.loading) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if(model.loadFailed) Column(Modifier.padding(24.dp)) { Text("Не удалось открыть чат. Сохранённые данные не удалены."); Button(onClick = model::load) { Text("Повторить") } }
        else Box(Modifier.weight(1f)) { ChatPane(model,model.snapshot.selected,full,{ sheet = "context" }) { full() } }
    }
    model.requestError?.let { error ->
        AlertDialog(onDismissRequest = model::clearRequestError,title = { Text("Вложение или запрос недоступны") },text = { Text(error) },
            confirmButton = { TextButton(onClick = model::clearRequestError) { Text("Закрыть") } })
    }
    if(model.saveFailed) AlertDialog(onDismissRequest = {},title = { Text("Не удалось сохранить") },text = { Text("Текущий сеанс открыт. Повторите сохранение перед закрытием приложения.") },
        confirmButton = { TextButton(onClick = model::retrySave) { Text("Повторить сохранение") } })
    if(review && capture != null) AlertDialog(onDismissRequest = { review = false },title = { Text("Проверить текущий экран") },text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Текст экрана — внешний контекст. Данные отправятся только после добавления и обычной кнопки «Отправить». Защищённый или отсутствующий контент недоступен.")
            capture.text?.let { text ->
                androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
                TextButton(onClick = { if(model.addReviewedText(text, "Текст экрана", reviewOrigin)) AssistantCapture.update(capture.id,text = null) },enabled = !model.loading && !model.loadFailed) { Text("Прикрепить проверенный текст") }
            }
            capture.screenshot?.let { bitmap ->
                Image(bitmap.asImageBitmap(),"Снимок экрана текущего вызова",Modifier.fillMaxWidth().heightIn(max = 260.dp))
                TextButton(onClick = {
                    attachmentBusy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { AssistContext.screenshot(context,bitmap) } }
                            .onSuccess { model.addAttachment(it, reviewOrigin) { accepted -> if (accepted) AssistantCapture.update(capture.id,screenshot = null) } }
                            .onFailure { contextError = "Не удалось прикрепить снимок. Данные экрана не отправлены." }
                        attachmentBusy = false
                    }
                },enabled = !attachmentBusy && !model.loading && !model.loadFailed) { Text("Прикрепить проверенный снимок") }
            }
        }
    },confirmButton = { TextButton(onClick = { review = false }) { Text("Готово") } },dismissButton = {
        TextButton(onClick = { AssistantCapture.clear(capture.id); review = false }) { Text("Убрать контекст экрана") }
    })
    if (sheet == "context") ReviewedContextDialog(model) { sheet = null }
    if (sheet != null && sheet != "context") ModalBottomSheet(onDismissRequest = { sheet = null }) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sheet) {
                "voice" -> VoiceControls(model)
                "permissions" -> com.alpha.assistant.capabilities.AndroidAccessOverview()
                "history" -> {
                    Text("История", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(historySearch, { historySearch = it }, label = { Text("Поиск в истории") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { model.select(null); sheet = null }) { Text("Новый чат") }
                    model.snapshot.conversations.filter { historySearch.isBlank() || it.title.contains(historySearch, ignoreCase = true) }.forEach { chat ->
                        TextButton(onClick = { model.select(chat.id); sheet = null }) { Text(chat.title) }
                    }
                }
                else -> {
                    Text("Меню Alphy", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { model.select(null); sheet = null }) { Text("Новый чат") }
                    TextButton(onClick = { sheet = "history" }) { Text("История") }
                    TextButton(onClick = { sheet = "context" }) { Text("Добавить контекст") }
                    TextButton(onClick = { sheet = "voice" }) { Text("Непрерывный голосовой сеанс") }
                    TextButton(onClick = { sheet = "permissions" }) { Text("Разрешения Android") }
                    TextButton(onClick = full) { Text("Все функции в приложении") }
                }
            }
        }
    }
}
