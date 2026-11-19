package com.alpha.assistant.tools.appfile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.portability.MiniAppArchive
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.tools.ToolRecord
import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolStore
import com.alpha.assistant.tools.ToolValidationCategory
import com.alpha.assistant.tools.ToolValidationFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** The host field appFile: no model definition sets it, the store, its revisions, and the archive keep it, the model never sees it, and the state rule of a revision between two app files. */
@RunWith(AndroidJUnit4::class)
class AppFileStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    /** calc.plan compiled by the kit: a definition with no host field, as a model definition is. */
    private fun calc()=runBlocking {AppFileCompiler(context,SandboxClient(context)).compile(plan("calc"))}.also {assertEquals(it.faults.toString(),"ok",it.status)}
    /** calc as the plan step keeps it: the compiled definition with its language and its plan. */
    private fun planned(): ToolSpec=calc().let {ToolSpec.parse(checkNotNull(it.definition)).withAppLanguage(it.language).withAppFile(plan("calc"))}
    private fun record(spec: ToolSpec)=ToolRecord("appfile-storage",1,1,spec.json,spec.initialState,spec.ui.toString(),null,saved=true)
    private fun withStore(block: (ToolStore)->Unit) {
        val name="appfile-storage-${UUID.randomUUID()}.db"
        val store=ToolStore(context,name)
        try {block(store)} finally {store.close();context.deleteDatabase(name);context.deleteDatabase("$name-automations.db")}
    }

    @Test fun parseRefusesAppFileInAModelDefinition() {
        val text=plan("calc")
        val raw=JSONObject(checkNotNull(calc().definition)).put("appFile",text).toString()
        // Each parse of a definition that a model wrote refuses the host field.
        for(parse in listOf<(String)->ToolSpec>({ToolSpec.parse(it)},{ToolSpec.parse(it,{})})) {
            val failure=runCatching {parse(raw)}.exceptionOrNull()
            assertTrue(failure.toString(),failure is ToolValidationFailure && failure.category == ToolValidationCategory.FIELDS)
        }
        assertNull(ToolSpec.available(raw))
        // The parse of stored definitions takes the same text, so the refusal comes from the boundary and not from the JSON.
        assertEquals(text,ToolSpec.parsePersisted(raw).appFile)
    }

    @Test fun persistedAppFileSurvivesParseSaveAndRevision() {
        val text=plan("calc")
        val spec=planned()
        assertEquals(text,ToolSpec.parsePersisted(spec.json).appFile)
        withStore {store ->
            val first=record(spec)
            store.commitTool(first,old=null,revision=true)
            store.commitTool(first.copy(revision=2,stateVersion=2),old=first,revision=true)
            assertEquals(text,ToolSpec.parsePersisted(checkNotNull(store.tool(first.id)).spec).appFile)
            assertEquals(listOf(2 to text,1 to text),store.revisions(first.id).map {it.revision to ToolSpec.parsePersisted(it.spec).appFile})
        }
        // Null removes the field. The field holds a text of 1 to APP_FILE_MAX_CHARS characters.
        val removed=spec.withAppFile(null)
        assertNull(removed.appFile)
        assertFalse(JSONObject(removed.json).has("appFile"))
        assertEquals(APP_FILE_MAX_CHARS,spec.withAppFile("x".repeat(APP_FILE_MAX_CHARS)).appFile?.length)
        for(bad in listOf("","x".repeat(APP_FILE_MAX_CHARS+1),12)) {
            if(bad is String) assertTrue(runCatching {spec.withAppFile(bad)}.exceptionOrNull() is IllegalArgumentException)
            val failure=runCatching {ToolSpec.parsePersisted(JSONObject(removed.json).put("appFile",bad).toString())}.exceptionOrNull()
            assertTrue(failure.toString(),failure is ToolValidationFailure)
        }
    }

    @Test fun exportAndImportKeepAppFile() {
        val archive=ByteArrayOutputStream().also {MiniAppArchive.write(record(planned()),true,it)}.toByteArray()
        assertEquals(plan("calc"),MiniAppArchive.read(ByteArrayInputStream(archive),context.cacheDir.toPath()) {}.spec.appFile)
    }

    @Test fun providerDefinitionHasNoAppFile() {
        val definition=checkNotNull(calc().definition)
        // appFile is the only host field of this spec, so the early return of providerDefinition must see it too.
        val spec=ToolSpec.parse(definition).withAppFile(plan("calc"))
        assertEquals(plan("calc"),spec.appFile)
        assertEquals(ToolSpec.parse(definition).json,spec.providerDefinition())
    }

    /** A counter of the fields of schema with their start values, as a model definition, with appFile when it is not null. */
    private fun counter(schema: String,initial: String,appFile: String?)=ToolSpec.parse("""{"formatVersion":3,"title":"Count","description":"","capabilities":[],
        "stateSchema":$schema,"initialState":$initial,"ui":{"type":"button","text":"Add","action":"add"},"source":"return {state:input.state};"}""").withAppFile(appFile)
    private val previous get()=counter("""{"count":"number","note":"string"}""","""{"count":0,"note":""}""","A")
    private val raw="""{"count":5,"note":"hi"}"""
    /** previous with count retyped and the field extra added. */
    private fun retyped(appFile: String?)=counter("""{"count":"string","note":"string","extra":"boolean"}""","""{"count":"0","note":"","extra":false}""",appFile)
    private val migrated="""{"count":"5","note":"hi","extra":true}"""
    private fun refused(revision: ()->String)=runCatching {revision()}.exceptionOrNull()

    @Test fun stateForRevisionOfAnAppFileAppTakesTheMigratedState() {
        assertEquals(JSONObject(migrated).toString(),ToolSpec.stateForRevision(previous,retyped("B"),raw,migrated))
        // The migrated state must fit the schema of the next spec.
        assertTrue(refused {ToolSpec.stateForRevision(previous,retyped("B"),raw,"""{"count":5,"note":"hi","extra":true}""")} is IllegalArgumentException)
        // An app of today's form on either side keeps today's rule: it refuses the retype, also with a migrated state.
        for((from,to) in listOf(previous.withAppFile(null) to retyped("B"),previous to retyped(null),previous.withAppFile(null) to retyped(null))) {
            assertTrue(refused {ToolSpec.stateForRevision(from,to,raw,migrated)} is IllegalArgumentException)
        }
        // Today's rule ignores a migrated state: an added field takes its start value, and each stored value stays.
        val added=counter("""{"count":"number","note":"string","extra":"boolean"}""","""{"count":0,"note":"","extra":false}""",null)
        assertEquals(JSONObject("""{"count":5,"note":"hi","extra":false}""").toString(),
            ToolSpec.stateForRevision(previous,added,raw,"""{"count":7,"note":"x","extra":true}"""))
    }

    @Test fun stateForRevisionOfAnAppFileAppRefusesAMissingField() {
        val dropped=counter("""{"count":"string","extra":"boolean"}""","""{"count":"0","extra":false}""","B")
        val failure=refused {ToolSpec.stateForRevision(previous,dropped,raw,"""{"count":"5","extra":true}""")}
        assertTrue(failure.toString(),failure is IllegalArgumentException && "note" in failure.message.orEmpty())
        // The same change with the field kept passes: the rule refuses only the missing field.
        assertEquals(JSONObject(migrated).toString(),ToolSpec.stateForRevision(previous,retyped("B"),raw,migrated))
    }

    @Test fun stateForRevisionOfAnAppFileAppNeedsAMigratedState() {
        // Two app files with the same schema: today's rule would take this pair with no migrated state.
        assertTrue(refused {ToolSpec.stateForRevision(previous,previous.withAppFile("B"),raw)} is IllegalArgumentException)
        assertEquals(JSONObject(raw).toString(),ToolSpec.stateForRevision(previous,previous.withAppFile(null),raw))
    }
}
