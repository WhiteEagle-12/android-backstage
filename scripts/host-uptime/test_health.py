import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import tempfile
import fcntl

spec = importlib.util.spec_from_file_location('health', Path(__file__).with_name('health.py'))
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)

class RecoveryTests(unittest.TestCase):
    def test_healthy_resets_failure_count(self):
        row, action = health.decide({'failures': 2}, True, 'active', 1000, 2000)
        self.assertEqual(row['failures'], 0)
        self.assertIsNone(action)

    def test_no_restart_after_single_failure(self):
        self.assertIsNone(health.decide({}, False, 'active', 1000, 2000)[1])

    def test_third_failure_restarts_stuck_process(self):
        row, action = health.decide({'failures': 2}, False, 'active', 1000, 2000)
        self.assertEqual(action, 'restart')
        self.assertEqual(row['lastRecovery'], 2000)

    def test_inactive_is_started(self):
        self.assertEqual(health.decide({'failures': 2}, False, 'inactive', None, 2000)[1], 'start')

    def test_cooldown_prevents_restart_loop(self):
        self.assertIsNone(health.decide({'failures': 10, 'lastRecovery': 1950}, False, 'active', 1000, 2000)[1])
        self.assertEqual(health.decide({'failures': 10, 'lastRecovery': 1600}, False, 'active', 1000, 2000)[1], 'restart')

    def test_startup_grace(self):
        self.assertIsNone(health.decide({'failures': 10}, False, 'active', 30, 2000)[1])

    def test_respects_in_progress_start_and_stop(self):
        for state in ['activating', 'deactivating']:
            self.assertIsNone(health.decide({'failures': 10}, False, state, 1000, 2000)[1])

    def test_probe_rejects_wrong_service_and_malformed_responses(self):
        for body in ['[]', 'null', 'true', 'invalid', '{"ok":true,"service":"other"}']:
            with patch.object(health, 'run', return_value=SimpleNamespace(returncode=0, stdout=body)):
                self.assertFalse(health.probe('http://localhost', 'backstage-work'))

    def test_probe_accepts_only_ready_tunnel(self):
        for body, expected in [('{"status":200,"readyConnections":4}', True), ('{"status":200,"readyConnections":"4"}', False), ('{"status":200,"readyConnections":0}', False)]:
            with patch.object(health, 'run', return_value=SimpleNamespace(returncode=0, stdout=body)):
                self.assertEqual(health.probe('http://localhost', 'tunnel'), expected)

    def test_maintenance_lock_prevents_probes_and_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            with (base / 'maintenance.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                with patch.object(health, 'BASE', base), patch.object(health, 'probe', side_effect=AssertionError('must not probe')), patch('builtins.print'):
                    health.main()
            self.assertFalse((base / 'health.json').exists())

if __name__ == '__main__':
    unittest.main()
