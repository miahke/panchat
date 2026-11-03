#!/usr/bin/env python3
"""Interactive, self-hosted Debian preparation. GPL-3.0-only; no forwarding enablement."""
from __future__ import annotations
import argparse
import base64
from dataclasses import dataclass
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import socket
import subprocess
import sys
import tempfile
import threading

REQUIRED_FILES={"deploy/activate-qwdtt.sh","deploy/remote-qwdtt.sh","deploy/ssh-common.sh","deploy/remote-admission.sh","deploy/wizard.py","deploy/install-admission.sh","deploy/stage-core.sh","deploy/panchan-admission.service","server-binaries/linux-amd64/panchan-admission","server-binaries/linux-amd64/qwdtt-server"}

class DeployError(Exception):
    pass

@dataclass(frozen=True)
class Target:
    hostname: str
    port: int
    username: str="root"
    @property
    def ssh_target(self): return self.username+'@'+self.hostname
    def __repr__(self): return 'Target([redacted])'

@dataclass(frozen=True)
class Step:
    kind: str
    title: str
    command: tuple[str,...]
    def __repr__(self): return 'DeployStep('+self.kind+')'

def parse_target(value: str,port: int,username: str|None=None) -> Target:
    value=value.strip()
    if '@' in value:
        if value.count('@')!=1:raise DeployError('Неверный SSH-адрес.')
        account,value=value.split('@',1)
        if username and username!=account:raise DeployError('Укажите один SSH-логин.')
        username=account
    username=username or 'root'
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]{0,63}',username):raise DeployError('Неверный SSH-логин.')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',value) or '..' in value or not 1<=port<=65535:
        raise DeployError('Введите IPv4 или DNS-имя VPS и корректный SSH-порт.')
    return Target(value,port,username)

class PasswordBroker:
    """Private local socket: the SSH password is never written into a file or argv."""
    def __init__(self,password: str):
        if not password or len(password)>4096 or any(c in password for c in '\x00\r\n'):
            raise DeployError('Неверная длина пароля SSH.')
        self.password=password
    def __enter__(self):
        self.temp=tempfile.TemporaryDirectory(prefix='panchan-auth-')
        self.directory=Path(self.temp.name)
        os.chmod(self.directory,0o700)
        self.server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        self.server.bind(str(self.directory/'password.sock'));self.server.listen(4);self.server.settimeout(.25)
        os.chmod(self.directory/'password.sock',0o600)
        self.stop=threading.Event()
        def serve():
            while not self.stop.is_set():
                try: connection,_=self.server.accept()
                except socket.timeout: continue
                except OSError: break
                with connection:
                    try: connection.sendall(self.password.encode('utf-8')+b'\n')
                    except OSError: pass
        self.thread=threading.Thread(target=serve,daemon=True);self.thread.start()
        self.helper=self.directory/'askpass'
        self.helper.write_text('#!/usr/bin/env python3\nimport socket,sys\ns=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)\ns.connect('+repr(str(self.directory/'password.sock'))+')\nwhile True:\n b=s.recv(8192)\n if not b:break\n sys.stdout.buffer.write(b)\n')
        os.chmod(self.helper,0o700)
        return self
    def environment(self): return {'SSH_ASKPASS':str(self.helper),'SSH_ASKPASS_REQUIRE':'force','DISPLAY':'panchan-deploy'}
    def __exit__(self,*_):
        self.stop.set();self.server.close();self.thread.join(timeout=1);self.password='';self.temp.cleanup()

class Runner:
    def __init__(self,environment: dict[str,str]|None=None): self.environment={**os.environ,**(environment or {})}
    def run(self,args):
        try:
            return subprocess.run(list(args),stdin=subprocess.DEVNULL,capture_output=True,text=True,env=self.environment,timeout=180)
        except (OSError,subprocess.TimeoutExpired) as error:
            raise DeployError('Команда не завершилась. Проверьте наличие OpenSSH, связь с VPS и повторите попытку.') from error

def normalize_host_key(text: str,target: Target):
    lines=[line.split() for line in text.splitlines() if line and not line.startswith('#')]
    values=[fields for fields in lines if len(fields)>=3 and fields[1]=='ssh-ed25519']
    if len(values)!=1: raise DeployError('Не удалось получить однозначный ключ SSH-сервера.')
    encoded=values[0][2]
    try: raw=base64.b64decode(encoded,validate=True)
    except ValueError as error: raise DeployError('Сервер вернул неверный SSH-ключ.') from error
    if len(raw)!=51 or raw[:19]!=b'\0\0\0\x0bssh-ed25519\0\0\0\x20':
        raise DeployError('Сервер вернул неверный ключ Ed25519.')
    host=target.hostname if target.port==22 else '['+target.hostname+']:'+str(target.port)
    pin='SHA256:'+base64.b64encode(hashlib.sha256(raw).digest()).decode().rstrip('=')
    return host+' ssh-ed25519 '+encoded+'\n',pin

def verify_manifest(root: Path):
    manifest=root/'deployer-SHA256SUMS'
    if not manifest.is_file():raise DeployError('Нет контрольных сумм комплекта. Экспортируйте комплект из приложения или используйте --build из исходников.')
    lines=manifest.read_text().splitlines()
    if not lines or len(lines)>20000:raise DeployError('Неверный манифест комплекта.')
    seen=set()
    for line in lines:
        match=re.fullmatch(r'([0-9a-f]{64})  ([^\r\n]+)',line)
        if not match:raise DeployError('Неверный манифест комплекта.')
        digest,name=match.groups();relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts or name in seen:raise DeployError('Недопустимый путь в комплекте.')
        seen.add(name);path=root/relative
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):raise DeployError('Файл комплекта отсутствует или заменён ссылкой.')
        with path.open('rb') as source:actual=hashlib.file_digest(source,'sha256').hexdigest()
        if actual!=digest:raise DeployError('Комплект повреждён. Экспортируйте его заново.')
    return seen

INVENTORY=r'''set -eu
. /etc/os-release
printf 'OS=%s\n' "$ID"
printf 'ARCH=%s\n' "$(uname -m)"
printf 'UID=%s\n' "$(id -u)"
printf 'FREE_KB=%s\n' "$(df -Pk / | awk 'NR==2{print $4}')"
if test -c /dev/net/tun; then printf 'TUN=yes\n'; else printf 'TUN=no\n'; fi
printf 'BUSY_PORTS='
for port in 56001 56002 56003 56004; do
 if ss -H -lntu | awk '{print $5}' | grep -Eq ":${port}$"; then printf '%s,' "$port"; fi
done
printf '\n'
'''

def parse_inventory(text: str):
    allowed={'OS','ARCH','UID','FREE_KB','TUN','BUSY_PORTS'};result={}
    for line in text.splitlines():
        key,separator,value=line.partition('=')
        if not separator or key not in allowed:continue
        if key in result or len(value)>256:raise DeployError('Неоднозначный ответ VPS.')
        result[key]=value
    if set(result)!=allowed:raise DeployError('Не удалось проверить параметры VPS.')
    return result

def validate_inventory(inventory):
    try:
        if inventory['OS']!='debian':raise DeployError('Этот комплект предназначен для Debian.')
        if inventory['ARCH']!='x86_64':raise DeployError('Нужен VPS x86_64. Для другой архитектуры потребуется отдельная сборка.')
        if inventory['UID']!='0':raise DeployError('Для подготовки сервера нужен вход root.')
        if int(inventory['FREE_KB'])<262144:raise DeployError('На VPS требуется хотя бы 256 МБ свободного места.')
        if inventory['TUN'] not in ('yes','no'):raise ValueError()
        busy=inventory['BUSY_PORTS'].strip(',')
        if busy and not re.fullmatch(r'[0-9,]+',busy):raise ValueError()
    except (ValueError,KeyError) as error:raise DeployError('Неверные параметры VPS.') from error
    warnings=[]
    if busy:warnings.append('Для будущего запуска транспортов уже заняты порты: '+busy+'. Подготовка службы допуска возможна.')
    if inventory['TUN']=='no':warnings.append('TUN пока недоступен. Перед запуском WG/Raw его нужно включить в панели VPS.')
    return warnings

def ssh_command(target: Target,known_hosts: Path,command: str,identity: Path|None=None,auth: str|None=None):
    options=()
    if identity is not None:options+=('-o','IdentitiesOnly=yes','-i',str(identity))
    if auth:
        if auth not in ('password','publickey'):raise DeployError('Неверный способ SSH-входа.')
        options+=('-o','PreferredAuthentications='+auth)
    if target.username!='root':
        quoted=shlex.quote(command)
        command='if [ \"$(id -u)\" = 0 ]; then exec sh -c '+quoted+'; else exec sudo -n -- sh -c '+quoted+'; fi'
    return ('ssh','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(known_hosts),'-o','ConnectTimeout=15','-o','Port='+str(target.port),*options,'--',target.ssh_target,command)

def make_plan(root: Path,target: Target):
    binaries=root/'server-binaries/linux-amd64'
    return [
        Step('stage','Разместить ядра qWDTT в закрытом каталоге сервера.',('bash',str(root/'deploy/stage-core.sh'),target.ssh_target,str(binaries/'qwdtt-server'))),
        Step('install','Установить локальную службу управления доступом с проверкой готовности и откатом.',('bash',str(root/'deploy/install-admission.sh'),target.ssh_target,str(binaries/'panchan-admission'))),
        Step('activate','Установить и запустить qWDTT WG/Raw с закрытой панелью управления.',('bash',str(root/'deploy/activate-qwdtt.sh'),target.ssh_target,str(binaries/'qwdtt-server'))),
        Step('health','Проверить готовность службы допуска.',('ssh','-o','StrictHostKeyChecking=yes','-o','Port='+str(target.port),'--',target.ssh_target,'/opt/panchan/admission/current/panchan-admission status')),
    ]

def execute_plan(plan,runner,report):
    for step in plan:
        report(step.title)
        result=runner.run(step.command)
        if result.returncode!=0:
            raise DeployError('Шаг не выполнен: '+step.title+' Проверьте SSH и повторите запуск. Установщик службы сохраняет предыдущую рабочую версию при ошибке обновления.')
        if step.kind=='health':
            try: state=json.loads(result.stdout)
            except ValueError as error:raise DeployError('Служба не вернула корректное состояние.') from error
            if state.get('version')!=1:raise DeployError('Служба допуска пока не готова.')


def build_cores(root: Path,report):
    go=shutil.which('go')
    if not go:raise DeployError('Для сборки установите Go1.27.3.')
    env={**os.environ,'CGO_ENABLED':'0','GOOS':'linux','GOARCH':'amd64','GOMAXPROCS':'2','GOGC':'50'}
    destination=root/'server-binaries/linux-amd64';destination.mkdir(parents=True,exist_ok=True)
    for module,package,name in [('server/admission','./cmd/panchan-admission','panchan-admission'),('server/qwdtt','./server','qwdtt-server')]:
        report('Сборка: '+name)
        result=subprocess.run([go,'build','-p=2','-mod=readonly','-trimpath','-buildvcs=false','-ldflags=-s -w -checklinkname=0','-o',str(destination/name),package],cwd=root/module,env=env,stdin=subprocess.DEVNULL,capture_output=True,text=True)
        if result.returncode:raise DeployError('Не удалось собрать '+name+'. Проверьте версию Go и доступ к зависимостям.')
    files=[root/name for name in sorted(REQUIRED_FILES)]
    with (root/'deployer-SHA256SUMS').open('w') as manifest:
        for file in files:
            with file.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
            manifest.write(digest+'  '+file.relative_to(root).as_posix()+'\n')

def main(argv=None):
    parser=argparse.ArgumentParser(description='Мастер подготовки собственного Debian VPS для Panchan.')
    parser.add_argument('--host');parser.add_argument('--user',help='SSH-логин, по умолчанию root');parser.add_argument('--ssh-port',type=int,default=22)
    parser.add_argument('--known-hosts',type=Path);parser.add_argument('--host-key',help='Ожидаемый SHA256 SSH-отпечаток')
    parser.add_argument('--identity',type=Path,help='Приватный SSH-ключ; не копируется в комплект')
    parser.add_argument('--key-passphrase',action='store_true',help='Скрыто запросить пароль выбранного ключа')
    parser.add_argument('--password',action='store_true',help='Запросить пароль SSH скрытым вводом')
    parser.add_argument('--yes',action='store_true',help='Подтвердить показанный план; нужен заранее закреплённый SSH-ключ')
    parser.add_argument('--check',action='store_true',help='Только проверить VPS')
    parser.add_argument('--build',action='store_true',help='Собрать серверные бинарные файлы из исходников')
    args=parser.parse_args(argv)
    if args.password and args.identity:raise DeployError('Выберите пароль SSH либо ключ.')
    if args.key_passphrase and not args.identity:raise DeployError('Для passphrase выберите --identity.')
    if args.identity:
        args.identity=args.identity.expanduser().resolve()
        if not args.identity.is_file():raise DeployError('Файл SSH-ключа не найден.')
    root=Path(__file__).resolve().parent.parent
    print('Panchan · подготовка сервера\nЭтап подготовки: размещение ядер и локальная служба допуска. qWDTT ещё не запускаются.')
    if args.build:build_cores(root,print)
    if not args.check:
        if not REQUIRED_FILES.issubset(verify_manifest(root)):raise DeployError('В комплекте отсутствуют проверенные файлы. Экспортируйте его заново.')
    target=parse_target(args.host or input('IPv4 или DNS-имя вашего VPS: '),args.ssh_port,args.user)
    broker=None
    try:
        with tempfile.TemporaryDirectory(prefix='panchan-deploy-') as temporary:
            known=Path(temporary)/'known_hosts'
            provided=args.known_hosts or (Path(os.environ['PANCHAN_KNOWN_HOSTS']) if os.environ.get('PANCHAN_KNOWN_HOSTS') else None)
            if provided:
                if not provided.is_file() or provided.stat().st_size>1048576:raise DeployError('Неверный файл закреплённых ключей SSH.')
                shutil.copyfile(provided,known)
            else:
                scan=Runner().run(('ssh-keyscan','-T','10','-p',str(target.port),'-t','ed25519',target.hostname))
                line,pin=normalize_host_key(scan.stdout,target)
                print('Отпечаток SSH-сервера: '+pin)
                if args.host_key:
                    if args.host_key!=pin:raise DeployError('Ключ SSH не совпал. Подключение остановлено.')
                elif args.yes:raise DeployError('Для автоматического запуска укажите --known-hosts или --host-key.')
                elif input('Сверьте отпечаток в панели VPS. Он совпадает? [да/нет]: ').strip().lower() not in ('да','д','yes','y'):raise DeployError('Ключ сервера не подтверждён.')
                known.write_text(line)
            os.chmod(known,0o600)
            env={'PANCHAN_KNOWN_HOSTS':str(known),'PANCHAN_SSH_PORT':str(target.port)}
            auth='publickey' if args.identity else ('password' if args.password else None)
            if args.identity:env['PANCHAN_SSH_IDENTITY']=str(args.identity)
            if auth:env['PANCHAN_SSH_AUTH']=auth
            if args.key_passphrase:
                broker=PasswordBroker(getpass.getpass('Passphrase ключа SSH (не сохраняется): '));broker.__enter__();env.update(broker.environment())
            if args.password:
                broker=PasswordBroker(getpass.getpass('Пароль SSH (не сохраняется): '));broker.__enter__();env.update(broker.environment())
            elif not args.identity and not args.yes and not os.environ.get('SSH_ASKPASS'):
                if input('Использовать пароль SSH? [да/нет, по умолчанию ключ SSH]: ').strip().lower() in ('да','д','yes','y'):
                    broker=PasswordBroker(getpass.getpass('Пароль SSH (не сохраняется): '));broker.__enter__();env.update(broker.environment())
                    auth='password';env['PANCHAN_SSH_AUTH']=auth
            runner=Runner(env)
            response=runner.run(ssh_command(target,known,INVENTORY,args.identity,auth))
            if response.returncode:raise DeployError('Не удалось проверить VPS. Проверьте адрес, SSH-ключ и реквизиты; нужны root либо sudo без пароля.')
            inventory=parse_inventory(response.stdout)
            for warning in validate_inventory(inventory):print(warning)
            print('Debian x86_64 проверен. Доступ root и свободное место подтверждены.')
            if args.check:return 0
            plan=make_plan(root,target)
            print('\nПлан:')
            for index,step in enumerate(plan,1):print(str(index)+'. '+step.title)
            if not args.yes and input('Выполнить этот план? [да/нет]: ').strip().lower() not in ('да','д','yes','y'):
                print('Отменено до изменений сервера.');return 0
            # Re-check local bytes after the review/prompt before uploading anything.
            verify_manifest(root)
            adjusted=[]
            for step in plan:
                command=step.command
                if step.kind=='health':command=ssh_command(target,known,command[-1],args.identity,auth)
                adjusted.append(Step(step.kind,step.title,command))
            execute_plan(adjusted,runner,print)
            print('\nqWDTT запущен. Панель подписок доступна через SSH в приложении. Аппаратная привязка допуска ещё не подключена к передаче трафика.')
            return 0
    finally:
        if broker:broker.__exit__(None,None,None)

if __name__=='__main__':
    if sys.version_info<(3,11):print('Требуется Python3.11 или новее.',file=sys.stderr);raise SystemExit(2)
    try:raise SystemExit(main())
    except DeployError as error:print('Ошибка: '+str(error),file=sys.stderr);raise SystemExit(1)
    except KeyboardInterrupt:print('\nДействие отменено. Для проверки состояния запустите мастер с --check.',file=sys.stderr);raise SystemExit(130)
