import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class QwdttActivationTest(unittest.TestCase):
    def test_bad_hash_is_rejected_before_any_changes(self):
        result = subprocess.run(['bash', str(ROOT/'deploy/remote-qwdtt.sh'), 'invalid'], capture_output=True)
        self.assertEqual(2, result.returncode)

    def exercise(self, fail):
        with tempfile.TemporaryDirectory() as name:
            tmp = Path(name)
            opt = tmp/'opt/panchan'; (opt/'staging').mkdir(parents=True)
            (opt/'qwdtt').mkdir()
            binary = b'synthetic-new-core'
            (opt/'staging/qwdtt-server').write_bytes(binary)
            (opt/'qwdtt/server').write_text('synthetic-old-core')
            etc = tmp/'etc'; (etc/'systemd/system').mkdir(parents=True)
            (etc/'os-release').write_text('ID=debian\n')
            unit = etc/'systemd/system/panchan-qwdtt.service'; unit.write_text('synthetic-old-unit')
            bins = tmp/'bin'; bins.mkdir()
            commands = {
                'id': 'echo 0', 'uname': 'echo x86_64', 'ip': 'exit 1', 'iptables': 'exit 0',
                'systemctl': 'exit 0',
                'ss': 'printf "UNCONN 0 0 0.0.0.0:56000 *:*\\nUNCONN 0 0 0.0.0.0:56002 *:*\\nUNCONN 0 0 0.0.0.0:56003 *:*\\n"',
                'python3': 'exit '+('1' if fail else '0'),
            }
            for command, body in commands.items():
                file = bins/command; file.write_text('#!/bin/bash\n'+body+'\n'); file.chmod(0o700)
            script = (ROOT/'deploy/remote-qwdtt.sh').read_text().replace('/opt/panchan',str(opt)).replace('/etc/',str(etc)+'/').replace('/var/lib/',str(tmp/'var/lib')+'/').replace('/dev/net/tun','/dev/null')
            result = subprocess.run(['bash','-c',script,'activation',hashlib.sha256(binary).hexdigest()],env={**os.environ,'PATH':str(bins)+os.pathsep+os.environ['PATH']},capture_output=True)
            if fail:
                self.assertNotEqual(0,result.returncode)
                self.assertEqual('synthetic-old-core',(opt/'qwdtt/server').read_text())
                self.assertEqual('synthetic-old-unit',unit.read_text())
            else:
                self.assertEqual(0,result.returncode,result.stderr.decode())
                self.assertEqual(binary,(opt/'qwdtt/server').read_bytes())
                self.assertIn('-admin-listen 127.0.0.1:56443',unit.read_text())
                self.assertEqual('QWDTT_READY',result.stdout.decode().strip())
            self.assertEqual([],list((opt/'qwdtt').glob('rollback.*')))
            self.assertEqual(0o600,(etc/'panchan/qwdtt/owner-password').stat().st_mode & 0o777)

    def test_failed_readiness_restores_previous_binary_and_service(self): self.exercise(True)
    def test_ready_service_keeps_admin_on_loopback(self): self.exercise(False)

if __name__=='__main__': unittest.main()
