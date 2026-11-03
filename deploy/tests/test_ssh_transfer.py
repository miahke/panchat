import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

class SshTransferTests(unittest.TestCase):
    def test_both_installers_forward_key_and_upload_only_fixed_artifacts(self):
        root=Path(__file__).parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);record=folder/'calls'
            fake=folder/'ssh'
            fake.write_text('''#!/usr/bin/env python3
import io,json,os,sys,tarfile
raw=sys.stdin.buffer.read()
names=[]
if raw:
 try:
  with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as archive:names=archive.getnames()
 except tarfile.ReadError:pass
with open(os.environ['SYNTHETIC_SSH_RECORD'],'a') as out:out.write(json.dumps({'args':sys.argv[1:],'names':names})+'\\n')
''')
            fake.chmod(0o700)
            key=folder/'synthetic key';key.write_text('synthetic')
            binary=folder/'synthetic-binary';binary.write_bytes(b'synthetic-public-binary')
            env={**os.environ,'PATH':str(folder)+os.pathsep+os.environ['PATH'],'SYNTHETIC_SSH_RECORD':str(record),'PANCHAN_SSH_IDENTITY':str(key),'PANCHAN_SSH_AUTH':'publickey','PANCHAN_KNOWN_HOSTS':str(folder/'known')}
            for script,arguments in [('stage-core.sh',[str(binary)]),('install-admission.sh',[str(binary)])]:
                subprocess.run(['bash',str(root/'deploy'/script),'operator@server.example.invalid',*arguments],env=env,check=True,capture_output=True)
            calls=[json.loads(line) for line in record.read_text().splitlines()]
            self.assertEqual(len(calls),5)
            archives=[]
            for call in calls:
                self.assertIn(str(key),call['args'])
                self.assertIn('IdentitiesOnly=yes',call['args'])
                self.assertIn('PreferredAuthentications=publickey',call['args'])
                self.assertIn('sudo -n',call['args'][-1])
                subprocess.run(['sh','-n','-c',call['args'][-1]],check=True)
                if call['names']:archives.append(call['names'])
            self.assertEqual(set(archives[0]),{'qwdtt-server','SHA256SUMS'})
            self.assertEqual(len(archives[1]),2)
            self.assertTrue(all('/' not in name and (name.startswith('admission-') or name.startswith('unit-')) for name in archives[1]))

if __name__=='__main__':unittest.main()
