#!/usr/bin/env python3
"""Build an offline, source-complete server preparation kit for app export."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--rebuild',action='store_true')
args=parser.parse_args()
spec=importlib.util.spec_from_file_location('panchan_wizard',root/'deploy/wizard.py')
wizard=importlib.util.module_from_spec(spec);sys.modules[spec.name]=wizard;spec.loader.exec_module(wizard)
if args.rebuild:wizard.build_cores(root,lambda message:print(message,flush=True))
if not wizard.REQUIRED_FILES.issubset(wizard.verify_manifest(root)):raise RuntimeError('Required files are not verified')

files={}
def add(relative,data,executable=False):
    name=Path(relative).as_posix()
    if name.startswith('/') or '..' in Path(name).parts:raise RuntimeError('Unsafe kit path')
    if str(Path.home()).encode() in data:raise RuntimeError('Private build path found in kit source')
    files[name]=(data,executable)

for prefix in ['server/admission','server/qwdtt','third_party/network']:
    for file in (root/prefix).rglob('*'):
        if not file.is_file() or file.is_symlink() or any(part in ('.git','__pycache__','build','target') for part in file.relative_to(root).parts):continue
        add(file.relative_to(root),file.read_bytes(),bool(file.stat().st_mode&0o111))
for relative in sorted(wizard.REQUIRED_FILES|{'LICENSE.md','docs/network/server-admission.md','docs/network/server-setup.md'}):
    file=root/relative
    add(relative,file.read_bytes(),bool(file.stat().st_mode&0o111))

# Include redistribution notices for statically linked Go dependencies.
modules={}
environment={**os.environ,'CGO_ENABLED':'0','GOOS':'linux','GOARCH':'amd64','GOMAXPROCS':'2'}
for directory,package in [('server/admission','./cmd/panchan-admission'),('server/qwdtt','./server')]:
    listing=subprocess.check_output(['go','list','-mod=readonly','-deps','-f','{{with .Module}}{{.Path}}|{{.Version}}|{{.Dir}}{{end}}',package],cwd=root/directory,env=environment,text=True)
    for line in listing.splitlines():
        if not line.strip():continue
        name,version,directory=line.split('|',2)
        if directory:modules[(name,version)]=Path(directory)
notices=[]
for (name,version),directory in sorted(modules.items()):
    if directory.is_relative_to(root):continue
    copied=[]
    for file in directory.iterdir():
        if file.is_file() and file.name.lower().startswith(('license','copying','notice')) and file.stat().st_size<=1048576:
            relative='licenses/go-modules/'+name+'@'+version+'/'+file.name
            add(relative,file.read_bytes());copied.append(relative)
    notices.append({'module':name,'version':version,'notices':copied})
add('licenses/go-modules.json',(json.dumps(notices,indent=2)+'\n').encode())
readme='''Panchan — подготовка собственного VPS

Требуется Linux, macOS или WSL, Python3.11+ и OpenSSH. Комплект для VPS Debian x86_64.
Go нужен только при самостоятельной пересборке (--build); готовые бинарные файлы входят в ZIP.

1. Откройте терминал в этой папке.
2. Запустите: python3 deploy/wizard.py
3. Укажите свой сервер и сверьте SSH-отпечаток с панелью VPS.
4. Мастер покажет план; подтвердите его перед изменениями.

Проверка без изменений: python3 deploy/wizard.py --host root@server.example.invalid --check
Пароль вводится скрыто и не записывается в файлы или аргументы команд.

Мастер запускает qWDTT WG/Raw и устанавливает локальную службу управления доступом.
API подписок слушает только loopback и доступен через SSH в приложении.
Аппаратная привязка допуска ещё не подключена к передаче; лимит qWDTT её не заменяет.
Файлы конфигурации с реальными ключами или реквизитами сервера в комплект не включены.

В ZIP входят соответствующие исходники, установщики, GPL3 и уведомления зависимостей.
SHA-256 проверяет целостность файлов; доверяйте источнику получения самого комплекта.
'''
add('README.txt',readme.encode())
manifest=''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,(data,_) in sorted(files.items()))
add('deployer-SHA256SUMS',manifest.encode())
assets=root/'app/src/main/assets/network';assets.mkdir(parents=True,exist_ok=True)
output=assets/'panchan-deployer.zip'
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,(data,executable) in sorted(files.items()):
        info=zipfile.ZipInfo('panchan-deployer/'+name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=(0o100755 if executable else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(info,data)
with output.open('rb') as file:digest=hashlib.file_digest(file,'sha256').hexdigest()
(assets/'panchan-deployer.sha256').write_text(digest+'\n')
print('server_kit_ready',len(files),'files',output.stat().st_size,'bytes')
