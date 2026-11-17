import unittest

from run_cases import step_timing


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


if __name__ == "__main__":
    unittest.main()
