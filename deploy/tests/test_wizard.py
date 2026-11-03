import hashlib
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

SPEC=importlib.util.spec_from_file_location('panchan_wizard',Path(__file__).parents[1]/'wizard.py')
wizard=importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name]=wizard
SPEC.loader.exec_module(wizard)

class WizardTests(unittest.TestCase):
    def test_explicit_identity_is_forwarded_without_password_fallback(self):
        target=wizard.parse_target('192.0.2.1',22)
        args=wizard.ssh_command(target,Path('/synthetic/known'), 'id -u',Path('/synthetic/key'), 'publickey')
        self.assertIn('/synthetic/key',args)
        self.assertIn('IdentitiesOnly=yes',args)
        self.assertIn('PreferredAuthentications=publickey',args)
        self.assertIn('sudo -n',wizard.ssh_command(wizard.parse_target('operator@server.example.invalid',22),Path('/synthetic/known'),'id -u')[-1])

    def test_target_validation_blocks_command_injection_and_invalid_ports(self):
        self.assertEqual(wizard.parse_target('192.0.2.1',22).ssh_target,'root@192.0.2.1')
        self.assertEqual(wizard.parse_target('root@server.example.invalid',2222).port,2222)
        self.assertEqual(wizard.parse_target('operator@server.example.invalid',22).ssh_target,'operator@server.example.invalid')
        for host,port in [('root@server;touch-bad',22),('root@-bad',22),('root@server.example.invalid',0),('https://server.example.invalid',22)]:
            with self.assertRaises(wizard.DeployError):wizard.parse_target(host,port)
    def test_inventory_requires_expected_platform_and_capacity(self):
        valid='OS=debian\nARCH=x86_64\nUID=0\nFREE_KB=1048576\nTUN=yes\nBUSY_PORTS=56001,\n'
        inventory=wizard.parse_inventory(valid)
        warnings=wizard.validate_inventory(inventory)
        self.assertTrue(any('56001' in text for text in warnings))
        for bad in [valid.replace('debian','unknown'),valid.replace('UID=0','UID=1000'),valid.replace('1048576','1'),valid+'OS=debian\n']:
            with self.assertRaises(wizard.DeployError):wizard.validate_inventory(wizard.parse_inventory(bad))
    def test_manifest_detects_corruption_traversal_and_symlinks(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);file=root/'safe.txt';file.write_text('synthetic')
            digest=hashlib.sha256(file.read_bytes()).hexdigest()
            manifest=root/'deployer-SHA256SUMS';manifest.write_text(digest+'  safe.txt\n')
            wizard.verify_manifest(root)
            file.write_text('modified')
            with self.assertRaises(wizard.DeployError):wizard.verify_manifest(root)
            manifest.write_text(digest+'  ../outside\n')
            with self.assertRaises(wizard.DeployError):wizard.verify_manifest(root)
            file.unlink();file.symlink_to('/dev/null');manifest.write_text(digest+'  safe.txt\n')
            with self.assertRaises(wizard.DeployError):wizard.verify_manifest(root)
    def test_password_is_in_memory_and_never_in_helper_file_or_arguments(self):
        password='synthetic-ssh-password'
        with wizard.PasswordBroker(password) as broker:
            helper=Path(broker.environment()['SSH_ASKPASS'])
            directory=helper.parent
            self.assertEqual(directory.stat().st_mode&0o777,0o700)
            self.assertNotIn(password,helper.read_text())
            result=subprocess.run([str(helper)],env={**os.environ,**broker.environment()},capture_output=True,text=True,check=True)
            self.assertEqual(result.stdout.strip(),password)
            for file in directory.iterdir():
                if file.is_file():self.assertNotIn(password.encode(),file.read_bytes())
        self.assertFalse(directory.exists())
    def test_keyscan_is_normalized_and_requires_explicit_matching_fingerprint(self):
        import base64,struct
        raw=struct.pack('>I',11)+b'ssh-ed25519'+struct.pack('>I',32)+bytes(32)
        encoded=base64.b64encode(raw).decode()
        key='arbitrary-host ssh-ed25519 '+encoded
        target=wizard.parse_target('192.0.2.1',2222)
        line,pin=wizard.normalize_host_key(key,target)
        self.assertTrue(line.startswith('[192.0.2.1]:2222 ssh-ed25519 '))
        self.assertTrue(pin.startswith('SHA256:'))
        with self.assertRaises(wizard.DeployError):wizard.normalize_host_key('bad key data',target)
    def test_plan_has_reviewable_staging_activation_and_health_steps(self):
        target=wizard.parse_target('192.0.2.1',22)
        plan=wizard.make_plan(Path('/synthetic'),target)
        self.assertEqual([s.kind for s in plan],['stage','install','activate','health'])
        self.assertNotIn('synthetic-ssh-password',repr(plan))
        self.assertNotIn('systemctl start qwdtt',' '.join(' '.join(s.command) for s in plan))
    def test_failed_step_stops_the_remaining_deployment(self):
        plan=wizard.make_plan(Path('/synthetic'),wizard.parse_target('192.0.2.1',22))
        calls=[]
        class Fake:
            def run(self,args):
                calls.append(args)
                return subprocess.CompletedProcess(args,1,'','synthetic failure')
        with self.assertRaises(wizard.DeployError):wizard.execute_plan(plan,Fake(),lambda text:None)
        self.assertEqual(len(calls),1)

if __name__=='__main__':unittest.main()
