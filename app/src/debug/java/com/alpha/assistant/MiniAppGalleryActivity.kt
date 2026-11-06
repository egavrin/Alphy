package com.alpha.assistant

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alpha.assistant.tools.*
import com.alpha.assistant.ui.AlphaDarkColors
import com.alpha.assistant.ui.AlphaLightColors
import com.alpha.assistant.ui.AlphaTheme
import com.alpha.assistant.ui.AlphyActivity
import com.alpha.assistant.ui.alphaColorScheme
import org.json.JSONArray
import org.json.JSONObject

/** Debug fixtures render locally. The activity has no store, provider, grants, or external entry point. */
class MiniAppGalleryActivity : AlphyActivity() {
    internal var page by mutableStateOf("roles")
    internal var section by mutableStateOf<String?>(null)

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = intent
        page = savedInstanceState?.getString("galleryPage") ?: options.getStringExtra("page") ?: "roles"
        section = savedInstanceState?.getString("gallerySection")
        val expanded = options.getBooleanExtra("expanded", false)
        if (expanded) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent {
            var theme by remember { mutableStateOf(options.getStringExtra("scheme") ?: "device") }
            var dark by remember { mutableStateOf(options.getBooleanExtra("dark", false)) }
            var large by remember { mutableStateOf(options.getBooleanExtra("large", false)) }
            val colors = when (theme) {
                "fixed" -> if (dark) AlphaDarkColors else AlphaLightColors
                "blue", "green" -> galleryWallpaperColors(theme, dark)
                else -> alphaColorScheme(this, dark)
            }
            val fontScale = if (large) 2f else 1f
            val density = LocalDensity.current.density
            val fontContext = remember(fontScale, density) {
                createConfigurationContext(Configuration().apply { this.fontScale = fontScale })
            }
            val galleryDensity = if (options.getBooleanExtra("systemFont", false)) LocalDensity.current else Density(fontContext)
            CompositionLocalProvider(LocalDensity provides galleryDensity) {
                AlphaTheme {
                    MaterialTheme(colorScheme = colors) {
                        Surface(Modifier.fillMaxSize(), color = colors.surface) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                                key(page, section) {
                                    Column(Modifier.widthIn(max = if (expanded) 640.dp else 320.dp).fillMaxWidth()
                                        .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (page == "canvas") GalleryCanvas(if (options.getBooleanExtra("matrix", false)) 96.dp else 260.dp)
                                        else GalleryTool(GallerySamples.getValue(page), section)
                                        if (!options.hasExtra("scheme")) {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                for ((id, label) in listOf("device" to "Система", "fixed" to "API 29", "blue" to "Синий", "green" to "Зелёный"))
                                                    FilterChip(theme == id, { theme = id }, { Text(label) }, Modifier.heightIn(min = 48.dp))
                                                FilterChip(dark, { dark = !dark }, { Text("Тёмная тема") }, Modifier.heightIn(min = 48.dp))
                                                FilterChip(large, { large = !large }, { Text("Текст 200%") }, Modifier.heightIn(min = 48.dp))
                                                for ((id, label) in GallerySamples.mapValues { it.value.title } + ("canvas" to "Холст"))
                                                    FilterChip(page == id, { page = id }, { Text(label) }, Modifier.heightIn(min = 48.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("galleryPage", page)
        outState.putString("gallerySection", section)
        super.onSaveInstanceState(outState)
    }
}

internal fun gallerySpec(ui: String, state: String, schema: String, title: String,
    capabilities: List<String> = emptyList(), canvas: Boolean = false): ToolSpec {
    val definition = JSONObject().put("formatVersion", 3).put("title", title).put("description", "Галерея мини-приложений")
        .put("capabilities", JSONArray(capabilities)).put("stateSchema", JSONObject(schema)).put("initialState", JSONObject(state))
        .put("ui", JSONObject(ui)).put("source", "return {state:input.state};")
    if (canvas) definition.put("surfaces", JSONArray("""[{"id":"main","width":100,"height":100}]"""))
    return try { ToolSpec.parse(definition.toString()) }
    catch (failure: ToolValidationFailure) {
        throw IllegalArgumentException("Invalid gallery fixture $title at ${failure.uiDiagnostic}", failure)
    }
}

@Composable private fun GalleryTool(sample: GallerySample, section: String?) {
    val spec = remember(sample, section) { sample.spec(section) }
    var state by remember(sample, section) { mutableStateOf(spec.initialState) }
    ToolContent(spec.ui.toString(), state, true) { id, value ->
        state = JSONObject(state).apply {
            when (id) {
                "active", "animate" -> { put("active", !getBoolean("active")); put("tick", getInt("tick") + 1) }
                "show", "hide" -> put("dialog", id == "show")
                "showSheet", "hideSheet" -> put("sheet", id == "showSheet")
                "back" -> put("returned", true)
                "routePush" -> getJSONArray("routePath").put("detail")
                "routeBack" -> getJSONArray("routePath").let { it.remove(it.length() - 1) }
                else -> id.substringBefore('_').takeIf { value != null && has(it) }?.let { put(it, value) }
            }
        }.toString()
    }
}

@Composable private fun GalleryCanvas(maxHeight: Dp) {
    val spec = remember { gallerySpec("""{"type":"text","text":"Графика"}""", """{"score":0}""", """{"score":"number"}""", "Проверка холста", canvas = true) }
    val tool = remember(spec) { ToolRecord("gallery", 1, 1, spec.json, spec.initialState, spec.ui.toString(), null, false) }
    val host = remember(tool) {
        val frame = GraphicsContract.frame("""{"commands":[
            {"type":"rect","x":0,"y":0,"width":25,"height":40,"color":"accent"},
            {"type":"rect","x":25,"y":0,"width":25,"height":40,"color":"#123456"},
            {"type":"rect","x":50,"y":0,"width":50,"height":40,"gradient":{"type":"linear","x":70,"y":0,"x2":90,"y2":0,"stops":[{"offset":0,"color":"error"},{"offset":1,"color":"default"}]}},
            {"type":"text","x":5,"y":70,"size":14,"text":"Графика","color":"default"}
        ]}""", "render")
        val snapshot = ArtifactSnapshot(tool, 1, surfaces = JSONObject().put("main", frame), playback = "ready", frameOwner = false)
        object : ArtifactUiHost {
            override suspend fun open() = snapshot
            override suspend fun startSession() = snapshot
            override suspend fun snapshot() = snapshot
            override suspend fun flush() = snapshot
            override suspend fun event(event: JSONObject) = snapshot
            override suspend fun close() {}
        }
    }
    NativeToolSurface(tool, spec, true, maxHeight, host)
}
