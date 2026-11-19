from pathlib import Path
import sys
import unittest

# The acceptance command runs this file from the repository root, where run_cases is not on the path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_cases import plan_step_summary, report_summary, run_all, step_timing  # noqa: E402


def phase(name, at_ms, detail=None, ms=None, operation="op-1", attempt=1):
    """One phase event as traceEvent of ComparisonChatCaptureTest writes it."""
    return {"operation_id": operation, "attempt": attempt, "at_ns": at_ms * 1_000_000, "kind": "phase",
            "name": name, "detail": detail, "ms": ms}


def loop_start(operation="op-1"):
    """The draft, the capability guidance, and the first agent call of today's loop."""
    return [phase("draft_start", 1000, operation=operation, attempt=0),
            phase("draft_end", 1040, operation=operation, attempt=0),
            phase("capability_guidance_start", 1100, operation=operation),
            phase("capability_guidance_end", 4600, operation=operation),
            phase("agent_call_start", 4610, operation=operation)]


class StepTimingTest(unittest.TestCase):
    def test_splits_a_step_into_wait_writing_and_host_time(self):
        event = {"tool": "write_app", "duration_ms": 45000, "model_calls": [{"first_text_ms": 38000, "end_ms": 44000}]}
        self.assertEqual({"tool": "write_app", "ms": 45000, "calls": 1, "wait_ms": 38000, "write_ms": 6000,
                          "host_ms": 1000}, step_timing(event))

    def test_a_call_without_text_has_no_writing_time(self):
        event = {"tool": "reply", "duration_ms": 3500, "model_calls": [{"first_text_ms": None, "end_ms": 3000}]}
        self.assertEqual((None, None, 500), tuple(step_timing(event)[key] for key in ("wait_ms", "write_ms", "host_ms")))

    def test_a_report_without_model_calls_keeps_the_step_time(self):
        self.assertEqual({"tool": "finish", "ms": 3000, "calls": 0}, step_timing({"tool": "finish", "duration_ms": 3000}))


class PlanStepSummaryTest(unittest.TestCase):
    def test_plan_step_summary_of_an_accepted_plan(self):
        events = [phase("draft_start", 1000, attempt=0), phase("draft_end", 1050, attempt=0),
                  phase("plan_call_start", 1060), phase("plan_first_text", 2760, ms=1700),
                  phase("plan_reply_end", 4960, ms=3900), phase("compile", 4980, "faults", 18),
                  phase("repair_call_start", 4990), phase("compile", 7010, "ok", 15),
                  phase("plan_outcome", 7100, "accepted"),
                  {"operation_id": "op-1", "attempt": 1, "at_ns": 7_200_000_000, "kind": "finished",
                   "outcome": "VALIDATED", "failure_code": "none"}]
        self.assertEqual({"outcome": "accepted", "detail": None, "plan_ms": 3900, "first_text_ms": 1700,
                          "compile_ms": [18, 15], "repairs": 1, "guidance_ms": None, "draft_ms": 50},
                         plan_step_summary(events))

    def test_plan_step_summary_of_an_outside_reply(self):
        events = [phase("draft_start", 1000, attempt=0), phase("draft_end", 1040, attempt=0),
                  phase("plan_call_start", 1050), phase("plan_first_text", 2250, ms=1200),
                  phase("plan_reply_end", 2350, ms=1300), phase("plan_outcome", 2360, "outside:3d"),
                  phase("capability_guidance_start", 2370), phase("capability_guidance_end", 6870),
                  phase("agent_call_start", 6880),
                  {"operation_id": "op-1", "attempt": 1, "at_ns": 9_000_000_000, "kind": "agent-step",
                   "tool": "write_app", "duration_ms": 2000}]
        self.assertEqual({"outcome": "outside", "detail": "3d", "plan_ms": 1300, "first_text_ms": 1200,
                          "compile_ms": [], "repairs": 0, "guidance_ms": 4500, "draft_ms": 40},
                         plan_step_summary(events))

    def test_plan_step_summary_of_the_off_arm_holds_the_guidance_time(self):
        self.assertEqual({"outcome": None, "detail": None, "plan_ms": None, "first_text_ms": None,
                          "compile_ms": [], "repairs": 0, "guidance_ms": 3500, "draft_ms": 40},
                         plan_step_summary(loop_start()))

    def test_plan_step_summary_without_phases_is_none(self):
        self.assertIsNone(plan_step_summary([]))
        self.assertIsNone(plan_step_summary([{"operation_id": "op-1", "attempt": 1, "at_ns": 1, "kind": "agent-step",
                                              "tool": "finish", "duration_ms": 3000}]))


def report(operation, first_ui_ms, calls, decision="create"):
    return {"chat_outcome": "artifact-committed", "chat_decision": decision, "assistant_text": "Ready.",
            "measurements": {"first_ui_ms": first_ui_ms, "completed_ui_ms": first_ui_ms + 100,
                             "terminal_chat_ms": first_ui_ms + 50, "provider_http_calls": calls},
            "operation_trace": {"generation": loop_start(operation)}}


class RepeatedCaseTest(unittest.TestCase):
    def test_a_repeated_case_summarizes_both_requests(self):
        cold = report("op-1", 41000, 3)
        cold["phase"] = "complete"
        cold["second"] = report("op-2", 23000, 4, decision="refine")
        summary = report_summary(cold)
        self.assertEqual((41000, 41100, 41050, 3, "complete"), tuple(summary[key] for key in (
            "first_ui_ms", "completed_ui_ms", "chat_ms", "provider_http_calls", "phase")))
        second = summary["second"]
        self.assertEqual((23000, 23100, 23050, 4, "refine", "artifact-committed"), tuple(second[key] for key in (
            "first_ui_ms", "completed_ui_ms", "chat_ms", "provider_http_calls", "decision", "outcome")))
        self.assertEqual(3500, summary["plan_step"]["guidance_ms"])
        self.assertEqual(3500, second["plan_step"]["guidance_ms"])

    def test_a_single_case_has_no_second_request(self):
        self.assertNotIn("second", report_summary(report("op-1", 41000, 3)))


class CallBudgetTest(unittest.TestCase):
    def run_with(self, calls, budget):
        cases = [{"id": case} for case in calls]
        ran, written = [], []

        def run(case):
            ran.append(case["id"])
            return dict(calls[case["id"]], case=case["id"])

        run_all(cases, run, written.append, budget)
        return ran, written

    def test_call_budget_stops_before_the_case_that_passes_it(self):
        calls = {"a": {"provider_http_calls": 4}, "b": {"provider_http_calls": 3, "second": {"provider_http_calls": 3}},
                 "c": {"provider_http_calls": 1}, "d": {"provider_http_calls": 1}}
        ran, written = self.run_with(calls, 10)
        self.assertEqual(["a", "b"], ran)
        self.assertEqual(["a", "b"], [summary["case"] for summary in written])
        self.assertNotIn("stopped_at_call_budget", written[0])
        self.assertEqual({"call_budget": 10, "provider_http_calls": 10, "not_run": ["c", "d"]},
                         written[1]["stopped_at_call_budget"])

    def test_a_case_without_a_report_counts_no_calls(self):
        ran, _ = self.run_with({"a": {"outcome": "no-report"}, "b": {"provider_http_calls": 2}}, 1)
        self.assertEqual(["a", "b"], ran)

    def test_without_a_budget_every_case_runs(self):
        ran, written = self.run_with({"a": {"provider_http_calls": 400}, "b": {"provider_http_calls": 400}}, None)
        self.assertEqual(["a", "b"], ran)
        self.assertTrue(all("stopped_at_call_budget" not in summary for summary in written))


if __name__ == "__main__":
    unittest.main()
