package com.alpha.assistant.tools

import android.os.Bundle
import com.alpha.assistant.diagnostics.UiFailureDiagnostics
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.CloudTestActivity
import com.alpha.assistant.automations.*
import com.alpha.assistant.capabilities.*
import com.alpha.assistant.cloud.*
import com.alpha.assistant.data.*
import com.alpha.assistant.sandbox.SandboxClient
import com.alpha.assistant.surfaces.*
import com.alpha.assistant.ui.ShellViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Failure cases: wrong search, stale accessibility trees, lost state/history, stale writes, pending effects, active rules, stale widget actions, recovery auto-enables rules. */
class LibraryManagementWorkflowTest {
    private val diagnostics = UiFailureDiagnostics("library")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as android.app.Application
    private val offline = object : ChatProvider {
        override suspend fun respond(key: String, request: CloudRequest, session: CloudSession, emit: suspend (CloudEvent) -> Unit) { error("Library must not call the cloud") }
    }
    private fun counter(id: String, title: String) = ToolSpec.parse("""{"formatVersion":3,"title":"$title","description":"Fixture","capabilities":["notification.post"],"stateSchema":{"count":"number"},"initialState":{"count":0},"ui":{"type":"column","children":[{"type":"text","text":"Счёт","bind":"count"},{"type":"button","text":"Добавить","action":"add"}]},"source":"if(input.event.type==='action') input.state.count++; return {state:input.state};"}""").let {
        ToolRecord(id,1,1,it.json,"""{"count":7}""",it.ui.toString(),null,true,createdAt=1000)
    }
    private fun rule(tools: ToolRecord) = JSONObject("""{"name":"Зависимый сценарий","trigger":{"type":"manual"},"conditions":{},"actions":[{"id":"notice","capability":"notification.post","args":{"title":"Fixture","text":"Fixture"}},{"id":"set","capability":"tool.state.set","args":{"toolId":"${tools.id}","revision":${tools.revision},"field":"count","value":9}}]}""")

    @Test fun archiveRestartDependencyGuardsAndRecoveryPreserveData() = runBlocking {
        val name="library-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        val widget=1700000000
        try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
            val initial=counter("counter","Мой счётчик")
            tools.commitTool(initial,null,true)
            val pending=CapabilityRequest("notice","notification.post",JSONObject().put("title","Fixture").put("text","Fixture"))
            val live=initial.copy(revision=2,stateVersion=2)
            tools.commitTool(live,initial,true,effects=listOf(pending))
            val draft=ToolDraft("draft",live.id,live.revision,live.stateVersion,"Правка",null,null,null,null)
            tools.saveDraft(draft)
            tools.grant(live.id,"notification.post",true)
            val dependent=rules.save(null,rule(live)); rules.approve(dependent.id,dependent.revision)
            ToolWidgets.bind(app,widget,live.id,WidgetPreferences())
            val stale=ToolWidgets.actionIntent(app,widget,live,"add",null)
            AutomationRuntime.archiveTool(tools,rules,live)
            val archived=checkNotNull(tools.tool(live.id))
            assertTrue(archived.archived); assertEquals(live.state,archived.state)
            assertEquals(live.createdAt,archived.createdAt); assertEquals(2,tools.revisions(live.id).size)
            assertEquals(draft,tools.draft(draft.id)); assertFalse(rules.rule(dependent.id)!!.enabled)
            assertEquals("cancelled",tools.effects(live.id).single().result.status)
            assertNull(tools.claimEffect(tools.effects(live.id).single().key,true))
            var nativeEffects=0
            assertEquals("cancelled",CapabilityBroker(app,tools).execute(tools.effects(live.id).single().key,true) {nativeEffects++;CapabilityResult("succeeded","Fixture")}.status)
            assertEquals(0,nativeEffects)
            assertTrue(runCatching {tools.commitTool(live.copy(stateVersion=3),live,false)}.isFailure)
            val engine=ToolEngine(tools,ToolGenerationService(offline,SandboxClient(app)))
            assertFalse(ToolWidgets.perform(app,stale,tools,engine,false))
            assertTrue(runCatching {engine.createDraft("Правка",live.id)}.isFailure)
            assertTrue(runCatching {engine.accept(draft.id)}.isFailure)
            assertTrue(runCatching {engine.interact(archived.id,archived.revision,archived.stateVersion,"add",null)}.isFailure)
            instrumentation.runOnMainSync {
                val views=ToolWidgets.render(app,widget,archived,WidgetPreferences(),false,false).apply(app,android.widget.FrameLayout(app))
                assertEquals("Инструмент в архиве · восстанови в Alpha",views.findViewById<android.widget.TextView>(com.alpha.assistant.R.id.widget_subtitle).text.toString())
                assertEquals(0,views.findViewById<android.widget.LinearLayout>(com.alpha.assistant.R.id.widget_content).childCount)
            }
            // Even a stale approval cannot authorize another effect while any target is archived.
            rules.approve(dependent.id,dependent.revision)
            var effects=0
            val executor=AutomationExecutor(rules,{null},{ owner,_ -> AutomationRuntime.dependencyFailure(tools,owner) }) {_,_,_->effects++; CapabilityResult("succeeded","Fixture")}
            assertEquals("revoked",executor.run(dependent.id,dependent.revision,AutomationEvent("manual","archived"))?.status)
            assertEquals(0,effects)
            ToolStore(app,name).use { reopened ->
                assertEquals(archived,reopened.tool(live.id)); assertEquals(draft,reopened.draft(draft.id))
                reopened.setArchived(archived,false)
            }
            val recovered=checkNotNull(tools.tool(live.id))
            assertFalse(recovered.archived); assertEquals(live.state,recovered.state)
            assertEquals(live.createdAt,recovered.createdAt); assertEquals(2,tools.revisions(live.id).size)
            assertFalse(rules.rule(dependent.id)!!.enabled); assertNull(AutomationRuntime.dependencyFailure(tools,rules.rule(dependent.id)!!))
            assertFalse(ToolWidgets.perform(app,stale,tools,engine,false))
            assertTrue(ToolWidgets.perform(app,ToolWidgets.actionIntent(app,widget,recovered,"add",null),tools,engine,false))
            assertEquals(8,JSONObject(tools.tool(live.id)!!.state).getInt("count"))
            assertEquals(live.id,ToolWidgets.toolId(app,widget))
        } } } finally {ToolWidgets.remove(app,widget);app.deleteDatabase(name);app.deleteDatabase(rulesName)}
    }

    @Test fun approvalValidatedBeforeArchiveCannotResumeAfterRecovery() = runBlocking {
        val name="library-approval-${UUID.randomUUID()}.db"
        val rulesName="$name-automations.db"
        try { ToolStore(app,name).use { tools -> AutomationStore(app,rulesName).use { rules ->
            val target=counter("approval-target","Счётчик")
            tools.commitTool(target,null,true)
            val dependent=rules.save(null,rule(target))
            rules.approve(dependent.id,dependent.revision); rules.pause(dependent.id)
            val before=checkNotNull(rules.rule(dependent.id))
            val pending=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            // Reproduce validation -> archive -> recover -> resume approval, without timing or a device service.
            assertNull(AutomationRuntime.dependencyFailure(tools,pending.rule))
            AutomationRuntime.archiveTool(tools,rules,target)
            tools.setArchived(checkNotNull(tools.tool(target.id)),false)
            assertNull(AutomationRuntime.dependencyFailure(tools,pending.rule))
            assertTrue(runCatching { AutomationRuntime.approve(rules,pending) { current ->
                check(AutomationRuntime.dependencyFailure(tools,current) == null)
            } }.isFailure)
            val paused=checkNotNull(rules.rule(dependent.id))
            assertFalse(paused.enabled); assertEquals(before.revision,paused.revision)
            assertEquals(before.approvedRevision,paused.approvedRevision)
            assertTrue(paused.attention.contains("архив"))
            // A new explicit approval after recovery retains the exact requested revision.
            val fresh=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            AutomationRuntime.approve(rules,fresh) { current -> check(AutomationRuntime.dependencyFailure(tools,current) == null) }
            assertTrue(rules.active(dependent.id,dependent.revision))
            val stale=AutomationRuntime.prepareApproval(rules,dependent.id,dependent.revision)
            val changed=rules.save(dependent.id,rule(target).put("name","Правка"),dependent.revision)
            assertTrue(runCatching { AutomationRuntime.approve(rules,stale) {} }.isFailure)
            assertFalse(rules.active(changed.id,changed.revision))
        } } } finally {app.deleteDatabase(name);app.deleteDatabase(rulesName)}
    }

    @Test fun searchOpenArchiveCancelConfirmAndRestoreThroughNativeUi() {
        val name="library-ui-${UUID.randomUUID()}.db"
        val shell=ShellStore(app,name)
        val toolName="$name-tools.db"
        val rulesName="$toolName-automations.db"
        val keys=SecureKeyStore(app,name,name)
        lateinit var model: ShellViewModel
        try {
            ToolStore(app,toolName).use { tools ->
                val first=counter("first","Мой счётчик");tools.commitTool(first,null,true)
                tools.commitTool(counter("second","Покупки"),null,true)
                AutomationStore(app,rulesName).use { rules -> val dependency=rules.save(null,rule(first));rules.approve(dependency.id,dependency.revision) }
            }
            CloudTestActivity.factory={ application -> ShellViewModel(application,offline,shell,keys).also {
                model=it
                diagnostics.state={"loading=${model.loading} busy=${model.tools.busy} selected=${model.tools.selectedId} tools=${model.tools.tools.map {item -> item.id to item.archived}}"}
            } }
            ActivityScenario.launch(CloudTestActivity::class.java).use { scenario ->
                waitUntil {!model.loading && !model.tools.busy}
                click("Создать")
                edit("Search mini-apps","  СЧЁТ  ")
                waitFor("Мой счётчик"); waitUntil { find(diagnostics.freshRoot(),"Покупки") == null }
                click("Мой счётчик"); waitFor("Счёт: 7")
                click("Mini-app options");click("Archive");waitFor("Зависимый сценарий");click("Отмена")
                assertFalse(model.tools.tools.first {it.id=="first"}.archived)
                click("Mini-app options");click("Archive");click("Архивировать")
                waitFor("Archived · data and versions are preserved. Restore this mini-app to use its controls.")
                scenario.recreate(); waitFor("Archived · data and versions are preserved. Restore this mini-app to use its controls.")
            }
            ActivityScenario.launch(CloudTestActivity::class.java).use {
                waitUntil {!model.loading && !model.tools.busy}
                click("Создать");click("Archive")
                waitFor("Мой счётчик");click("Мой счётчик")
                click("Restore from archive");waitFor("Счёт: 7")
                waitUntil {!model.tools.busy}
                AutomationStore(app,rulesName).use {assertFalse(it.rules().single().enabled)}
                click("Mini-app library");edit("Search mini-apps","не найдено");waitFor("No matches");diagnostics.capture("empty-search-result")
            }
        } finally {
            CloudTestActivity.factory=null;shell.close();keys.clear()
            app.deleteDatabase(name);app.deleteDatabase(toolName);app.deleteDatabase(rulesName);app.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(name)}
        }
    }
    private fun edit(label: String,value: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable)return node
            repeat(node.childCount) {node.getChild(it)?.let {child -> editable(child)?.let {return it}}};return null
        }
        var node: AccessibilityNodeInfo?=waitFor(label)
        node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        while(node!=null) {val input=editable(node);if(input!=null) {assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}));waitUntil { input.refresh() && input.text?.toString() == value };return};node=node.parent}
        error("No input for $label")
    }
    private fun click(label: String) {
        waitUntil {
            var node: AccessibilityNodeInfo? = waitFor(label)
            while(node != null && !node.isClickable) node = node.parent
            node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.uiAutomation.waitForIdle(100,2000)
    }
    private fun scroll(node: AccessibilityNodeInfo?, action: Int): Boolean {
        if(node == null) return false
        if(node.actionList.any { it.id == action } && node.performAction(action)) return true
        repeat(node.childCount) { if(scroll(node.getChild(it),action)) return true }
        return false
    }
    private fun waitFor(label: String): AccessibilityNodeInfo {
        diagnostics.target(label)
        var found: AccessibilityNodeInfo? = null
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        try { waitUntil {
            val root = diagnostics.freshRoot()
            found = find(root,label)
            if(found == null) {
                if(!scroll(root,direction)) direction = if(direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                instrumentation.uiAutomation.waitForIdle(100,2000)
            }
            found != null
        } } catch(failure: Throwable) {
            fun dump(node: AccessibilityNodeInfo?): String = if(node == null) "" else
                "[text=${node.text}, description=${node.contentDescription}, scrollable=${node.isScrollable}, actions=${node.actionList.map { it.id }}]" +
                    (0 until node.childCount).joinToString("") { dump(node.getChild(it)) }
            val cachedTree=dump(instrumentation.uiAutomation.rootInActiveWindow)
            diagnostics.capture("wait-timeout")
            throw AssertionError("Missing $label; tree=$cachedTree",failure)
        }
        return found!!
    }
    private fun find(node: AccessibilityNodeInfo?,label: String): AccessibilityNodeInfo? {if(node==null)return null;if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node;repeat(node.childCount) {find(node.getChild(it),label)?.let {return it}};return null}
    private fun waitUntil(condition: () -> Boolean) {val deadline=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<deadline) {if(condition())return;SystemClock.sleep(50)};fail("Library workflow timed out")}
}
