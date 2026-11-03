#!/usr/bin/env python3
"""Preserve dependency notices for the independently built Go executables."""
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
output = root / 'third_party/network/go-modules'
modules = {}
env = {**os.environ, 'CGO_ENABLED': '0', 'GOOS': 'linux', 'GOARCH': 'amd64', 'GOMAXPROCS': '2'}
for relative, package in [('native/pt', './cmd/lyrebird'), ('native/qwdtt', '.'), ('server/qwdtt', './server'), ('server/admission', './cmd/panchan-admission')]:
    lines = subprocess.check_output(['go', 'list', '-mod=readonly', '-deps', '-f', '{{with .Module}}{{.Path}}|{{.Version}}|{{.Dir}}{{end}}', package], cwd=root/relative, env=env, text=True)
    for line in lines.splitlines():
        if not line: continue
        name, version, directory = line.split('|', 2)
        if directory and not Path(directory).is_relative_to(root): modules[(name, version)] = Path(directory)
notices = []
for (name, version), directory in sorted(modules.items()):
    names = []
    for source in sorted(directory.iterdir()):
        if source.is_file() and source.name.lower().startswith(('license', 'copying', 'notice')) and source.stat().st_size <= 1048576:
            target = output/(name+'@'+version)/source.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
            names.append(target.relative_to(root).as_posix())
    # fhttp's module archive omits LICENSE although its net/http-derived source
    # retains the Go Authors BSD header. Preserve the original Go license.
    if name == 'github.com/bogdanfinn/fhttp' and not names:
        goroot = Path(subprocess.check_output(['go', 'env', 'GOROOT'], text=True).strip())
        target = output/(name+'@'+version)/'LICENSE-Go'
        target.parent.mkdir(parents=True, exist_ok=True)
        license_source = goroot/'LICENSE'
        if not license_source.is_file(): license_source = root/'tools/network-native/licenses/Go-BSD-LICENSE'
        target.write_bytes(license_source.read_bytes())
        names.append(target.relative_to(root).as_posix())
    notices.append({'module': name, 'version': version, 'notices': names})
output.mkdir(parents=True, exist_ok=True)
(output/'modules.json').write_text(json.dumps(notices, indent=2)+'\n')
print('Go dependency notice entries:', len(notices))
