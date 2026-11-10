package com.alpha.assistant.portability

import com.alpha.assistant.sandbox.SandboxContract
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MiniAppExportProvider : androidx.core.content.FileProvider()

internal object MiniAppArchive {
    private const val MANIFEST = "manifest.json"
    private const val DEFINITION = "definition.json"
    private const val STATE = "state.json"
    private const val VERSION = 1
    private const val MAX_BYTES = 320 * 1024L
    private val limits = ZipLimits(MAX_BYTES + 4096, 3, ToolSpec.MAX_DEFINITION_BYTES.toLong(), MAX_BYTES)
    private val artifactId = Regex("[A-Za-z0-9_-]{1,128}")

    data class Contents(val id: String, val spec: ToolSpec, val state: String)

    fun write(tool: ToolRecord, includeState: Boolean, output: OutputStream) {
        require(artifactId.matches(tool.id))
        val spec = ToolSpec.parsePersisted(tool.spec)
        require(spec.generatedImages.isEmpty()) { "Generated images cannot be included in this archive format." }
        val manifest = JSONObject().put("version", VERSION).put("id", tool.id).toString()
        val state = if (includeState) {
            require(spec.collections.length() == 0) { "Записи из отдельных коллекций нельзя добавить в этот файл." }
            spec.validateState(tool.state).toString()
        } else null
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(MANIFEST, manifest)
            entry(DEFINITION, spec.json)
            state?.let { entry(STATE, it) }
        }
    }

    fun read(input: InputStream, directory: Path, checkCancellation: () -> Unit): Contents =
        ZipTransport.stage(input, directory, limits, checkCancellation).use { archive ->
            val entries = archive.entries.associateBy { it.name }
            val required = setOf(MANIFEST, DEFINITION)
            require(entries.keys == required || entries.keys == required + STATE)
            fun read(name: String): String {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                return InputStreamReader(entries.getValue(name).openStream(), decoder).buffered().use { it.readText() }
            }
            val manifest = SandboxContract.objectFrom(read(MANIFEST))
            require(manifest.keys().asSequence().toSet() == setOf("version", "id"))
            require(manifest.opt("version") == VERSION)
            val id = manifest.get("id") as? String
            require(id != null && artifactId.matches(id))
            // The stored-artifact parser rebuilds and validates canonical host parts.
            val spec = ToolSpec.parsePersisted(read(DEFINITION))
            require(spec.generatedImages.isEmpty() && spec.lessonImageIntent == null && com.alpha.assistant.tools.imageSlots(spec.ui).isEmpty())
            val state = if (STATE in entries) {
                require(spec.collections.length() == 0)
                spec.validateState(read(STATE)).toString()
            } else spec.initialState
            checkCancellation()
            Contents(id, spec, state)
        }
}
