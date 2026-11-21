#!/usr/bin/env bash
# Manual CLI sandbox manager. Requires Bash, Python 3 and Java; Maven for build.
set -euo pipefail
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 - "$REPO_DIR" "$@" <<'PY'
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time

repo = Path(sys.argv[1])
args = sys.argv[2:]
jar = repo / 'mdbms-app/target/mdbms.jar'
state_dir = Path(tempfile.gettempdir()) / (
    'shayladb-cli-manager-' + str(os.getuid()) + '-' + hashlib.sha256(str(repo).encode()).hexdigest()[:12])
state_dir.mkdir(mode=0o700, exist_ok=True)
if state_dir.is_symlink() or state_dir.stat().st_uid != os.getuid():
    sys.exit('Unsafe manager state directory: ' + str(state_dir))
lock = (state_dir / 'lock').open('a')
fcntl.flock(lock, fcntl.LOCK_EX)
state_file = state_dir / 'current.json'
state = json.loads(state_file.read_text()) if state_file.exists() else {}

HELP = '''Usage: ./scripts/cli-test.sh COMMAND

  build                 Package the current code, skipping automated tests
  new [NAME]            Stop managed server; create, bootstrap, start and seed a fresh case
  new [NAME] --no-seed  Bootstrap and start, with empty accounts/readings tables
  new [NAME] --no-start Bootstrap only (for offline/bootstrap failure cases)
  new [NAME] --empty    Start an empty directory; Main loads its sample database
  start                 Start the current case without seeding
  stop                  Graceful shutdown; preserve the database and logs
  restart               Stop and start the same database; never reseed
  crash                 SIGKILL the managed server for crash-recovery cases
  seed                  Insert Alice/100 and Bob/200 (only into an unseeded fixture)
  connect               Open a client (telnet, or nc fallback); repeat in another terminal
  logs                  Print the latest server log (path is also shown)
  status                Show current case, process status and log path
  path                  Print the current sandbox directory
  help                  Show this help

Run from any directory using this script's absolute path. No exports needed.
Main uses ports 2323/7654. Only this helper's verified server PID is signalled.
Old sandboxes are preserved. Requirements: Linux /proc, Python 3, Java 17+.
'''
SCHEMA = '''CREATE TABLE accounts (
  id INTEGER PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  balance INTEGER NOT NULL
);
CREATE TABLE readings (
  id INTEGER PRIMARY KEY,
  value FLOAT NOT NULL,
  label CHAR(8) NOT NULL
);
CREATE INDEX idx_accounts_name ON accounts(name);
'''


def save():
    temp = state_file.with_suffix('.tmp')
    temp.write_text(json.dumps(state, indent=2) + '\n')
    temp.replace(state_file)


def directory():
    if not state.get('directory'):
        raise RuntimeError('No current case. Run: ./scripts/cli-test.sh new')
    return Path(state['directory'])


def identity(pid):
    try:
        # comm can contain spaces/parentheses; starttime is field 22.
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        if fields[0] == 'Z':
            return None
        return fields[19], str(Path(f'/proc/{pid}/cwd').resolve(strict=True))
    except (OSError, IndexError):
        return None


def alive():
    pid = state.get('pid')
    return bool(pid and identity(pid) == (state.get('starttime'), state.get('directory')))


def report():
    print('Case:', state.get('name', '(none)'))
    print('Directory:', state.get('directory', '(none)'))
    print('Server:', ('running (PID ' + str(state['pid']) + ')') if alive() else 'stopped')
    print('Log:', state.get('log', '(none)'))
    if alive():
        print('Client: ./scripts/cli-test.sh connect  (or nc 127.0.0.1 2323)')


def stop(force=False):
    if not alive():
        print('Managed server is already stopped.')
        return
    pid = state['pid']
    os.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
    deadline = time.monotonic() + (5 if force else 20)
    while alive() and time.monotonic() < deadline:
        time.sleep(.1)
    if alive():
        raise RuntimeError('Shutdown exceeded 20 seconds; inspect logs. Use crash explicitly if needed.')
    print(('Crashed' if force else 'Stopped') + f' managed server {pid}; data preserved.')
    # SIGTERM's shell/process status alone does not establish successful cleanup.
    log = Path(state['log']).read_text(errors='replace')
    if not force and any(x in log for x in ('Shutdown failed', 'Shutdown timed out', 'Server cleanup failed')):
        raise RuntimeError('Server exited with a shutdown failure; inspect ' + state['log'])


def require_jar():
    if not jar.is_file():
        raise RuntimeError('JAR missing. Run: ./scripts/cli-test.sh build')


def start():
    require_jar()
    folder = directory()
    if alive():
        print('Managed server is already running.')
        return
    # Refuse to attach to, seed, or stop an unrelated process on these ports.
    for port in (2323, 7654):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
    stamp = time.time_ns()
    log_path = folder / f'server-{stamp}.log'
    with log_path.open('wb') as log:
        proc = subprocess.Popen(['java', '-jar', str(jar)], cwd=folder,
                                stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                start_new_session=True)
    ident = identity(proc.pid)
    if ident is None:
        raise RuntimeError('Server exited immediately. Inspect ' + str(log_path))
    state.update(pid=proc.pid, starttime=ident[0], log=str(log_path))
    save()
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError('Server exited before readiness.')
            log_text = log_path.read_text(errors='replace')
            if all(f'successfully bind to port {p}' in log_text for p in (2323, 7654)):
                print('Both listeners ready: CLI 2323, JSON 7654.')
                return
            time.sleep(.1)
        raise RuntimeError('Server did not become ready within 15 seconds.')
    except BaseException:
        stop()
        print(log_path.read_text(errors='replace'), file=sys.stderr)
        raise


def receive_exact(sock, size):
    result = b''
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise RuntimeError('Connection closed during seed response.')
        result += chunk
    return result


def seed():
    if not alive():
        raise RuntimeError('Start the managed server before seeding.')
    if state.get('sample'):
        raise RuntimeError('This is a sample-database case, not an accounts fixture.')
    if state.get('seeded'):
        raise RuntimeError('Already seeded. Restart does not require reseeding; use new for a fresh case.')
    # Use the production framed protocol to validate each acknowledged insert.
    with socket.create_connection(('127.0.0.1', 7654), timeout=5) as sock:
        for sql in ("INSERT INTO accounts VALUES (1, 'Alice', 100);",
                    "INSERT INTO accounts VALUES (2, 'Bob', 200);"):
            body = json.dumps({'sql': sql}).encode()
            sock.sendall(struct.pack('!I', len(body)) + body)
            size = struct.unpack('!I', receive_exact(sock, 4))[0]
            if size > 1048576:
                raise RuntimeError('Invalid response size during seeding.')
            response = json.loads(receive_exact(sock, size))
            if response.get('status') != 'WRITE' or response.get('affectedRows') != 1:
                raise RuntimeError('Seed failed: ' + str(response) + '; create a fresh case before retrying.')
    state['seeded'] = True
    save()
    print('Seeded accounts: Alice/100 and Bob/200. Do not seed again in the client.')


def run():
    command = args[0] if args else 'help'
    rest = args[1:]
    if command in ('help', '-h', '--help'):
        print(HELP)
        return
    if command != 'new' and rest:
        raise RuntimeError('Unexpected arguments. Run help for usage.')
    if command == 'build':
        if alive():
            raise RuntimeError('Stop the managed server before rebuilding its JAR.')
        subprocess.run(['mvn', '-B', '-pl', 'mdbms-app', '-am', 'package', '-DskipTests'], cwd=repo, check=True)
    elif command == 'new':
        flags = [x for x in rest if x.startswith('--')]
        names = [x for x in rest if not x.startswith('--')]
        if len(names) > 1 or len(flags) > 1 or any(x not in ('--empty', '--no-start', '--no-seed') for x in flags):
            raise RuntimeError('Use new [NAME] with at most one of --empty, --no-start, --no-seed.')
        require_jar()
        stop()
        folder = Path(tempfile.mkdtemp(prefix='shayladb-cli-'))
        state.clear()
        state.update(directory=str(folder), name=names[0] if names else folder.name,
                     sample='--empty' in flags, seeded=False)
        save()
        if not state['sample']:
            (folder / 'schema.sql').write_text(SCHEMA)
            subprocess.run(['java', '-jar', str(jar), 'bootstrap', 'schema.sql', '--data-dir', 'data'],
                           cwd=folder, check=True)
        if '--no-start' not in flags:
            start()
            if not flags:
                try:
                    seed()
                except BaseException:
                    stop()
                    raise
        report()
    elif command == 'start':
        start(); report()
    elif command == 'restart':
        stop(); start(); report()
    elif command in ('stop', 'crash'):
        stop(force=command == 'crash')
    elif command == 'seed':
        seed()
    elif command == 'status':
        report()
    elif command == 'path':
        print(directory())
    elif command == 'logs':
        log = state.get('log')
        if not log:
            raise RuntimeError('No server log yet.')
        print('Log:', log)
        print(Path(log).read_text(errors='replace'))
    elif command == 'connect':
        if not alive():
            raise RuntimeError('Managed server is not running.')
        client = shutil.which('telnet') or shutil.which('nc')
        if not client:
            raise RuntimeError('Install telnet or nc to open an interactive client.')
        # Release manager lock before an interactive session; shutdown must remain usable.
        fcntl.flock(lock, fcntl.LOCK_UN)
        tty = os.open('/dev/tty', os.O_RDONLY)
        os.dup2(tty, 0)
        os.close(tty)
        os.execv(client, [client, '127.0.0.1', '2323'])
    else:
        raise RuntimeError('Unknown command: ' + command + '\n' + HELP)


try:
    run()
except (OSError, RuntimeError, subprocess.CalledProcessError) as failure:
    sys.exit('ERROR: ' + str(failure))
PY
