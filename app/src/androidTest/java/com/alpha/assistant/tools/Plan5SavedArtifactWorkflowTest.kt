package com.alpha.assistant.tools

import com.alpha.assistant.R
import com.alpha.assistant.ui.ShellFixture
import com.alpha.assistant.ui.ShellRobot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Plan5SavedArtifactWorkflowTest {
    @Test fun capturedGridDistinguishesGraphicsRestartFromGeneratedRestart() {
        replay("grid-with-progress-surface") { checks, reopen, saved ->
            checks.grid(reopen, verifyHostRestart = true)
            assertEquals(1, JSONObject(saved().state).getInt("score"))
        }
    }

    @Test fun capturedLedgerSelectsTheTeamControlWhenItsLabelMatchesTheBalance() {
        replay("ledger-with-duplicate-team-labels") { checks, reopen, _ -> checks.ledger(reopen) }
    }

    @Test fun capturedLedgerReadsPrefixedBalancesAndPreservesHistory() {
        replay("ledger-with-prefixed-balances") { checks, reopen, _ -> checks.ledger(reopen) }
    }

    /** Failure case: each cell was a list item "Клетка N" with an action, and the check found no cell controls. */
    @Test fun capturedGridPressesListItemCells() {
        replay("grid-with-list-item-cells") { checks, reopen, _ -> checks.grid(reopen) }
    }

    /** Failure case: the balance was the text "0 очк.", and the check read only a number. */
    @Test fun capturedLedgerReadsTextBalances() {
        replay("ledger-with-text-balances") { checks, reopen, _ -> checks.ledger(reopen) }
    }

    /** Failure case: an ingredient showed "300 g → 450 g", and the check found two numbers. */
    @Test fun capturedRecipeReadsTheResultOfAnArrowAmount() {
        replay("recipe-with-arrow-amount") { checks, reopen, _ -> checks.run("fresh:cooking-1", reopen) {} }
    }

    /** Failure case: an ingredient row showed "Ingredient: Flour" beside "Scaled: 450 g". */
    @Test fun capturedRecipeReadsAScaledAmountBesideItsIngredient() {
        replay("recipe-with-scaled-row-text") { checks, reopen, _ -> checks.run("fresh:cooking-1", reopen) {} }
    }

    private fun replay(asset: String, workflow: (Plan5WorkflowChecks, () -> Unit, () -> ToolRecord) -> Unit) {
        val fixture = ShellFixture("plan5-saved-artifact")
        val robot = ShellRobot(fixture.name)
        val captured = robot.instrumentation.context.assets.open("behavior-replay/$asset.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val spec = ToolSpec.parsePersisted(captured.getJSONObject("spec").toString())
        val id = captured.getString("artifact_id")
        val database = "${fixture.name}.db-tools.db"
        val proof = JSONObject()
        try {
            ToolStore(fixture.context, database).use { store ->
                store.saveTool(ToolRecord(id, captured.getInt("artifact_revision"), 1, spec.json,
                    spec.initialState, spec.ui.toString(), null, true), revision = true)
                fixture.install()
                fixture.launch().use {
                    val model = checkNotNull(fixture.model)
                    robot.waitUntil { !model.loading && !model.tools.busy }
                    fun open() {
                        robot.instrumentation.runOnMainSync { model.openSurface(id) }
                        robot.waitFor(robot.text(R.string.tool_refine))
                    }
                    open()
                    try {
                        val saved = { checkNotNull(store.tool(id)) }
                        fun reopen() {
                            Plan5WorkflowChecks.reopenSaved(robot, spec.title)
                        }
                        val checks = Plan5WorkflowChecks(robot, proof, saved, {
                            val shot = robot.automation.takeScreenshot()
                            val valid = shot != null
                            shot?.recycle()
                            valid
                        }, database)
                        workflow(checks, ::reopen, saved)
                        assertTrue(proof.getJSONArray("checks").length() > 0)
                    } finally {
                        println("Saved artifact workflow $asset: $proof")
                    }
                    assertEquals(0, fixture.provider.calls)
                }
            }
        } finally {
            fixture.removeWithModel()
        }
    }
}
