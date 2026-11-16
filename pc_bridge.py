#!/usr/bin/env python3
"""Мост Jarvis <-> ПК (только стандартная библиотека).
Запуск: python pc_bridge.py [--trust blender,unity,python]
Переменные: BLENDER (путь к blender), UNITY (путь к Unity.exe), UNITY_PROJECT (папка проекта).
Каждое действие, кроме ping, требует вашего «y» в этой консоли, если вид не указан в --trust."""
import json, os, pathlib, secrets, socket, subprocess, sys, tempfile, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8080
TOK = os.environ.get('JARVIS_TOKEN') or secrets.token_urlsafe(12)
TRUST = set(sys.argv[sys.argv.index('--trust') + 1].split(',')) if '--trust' in sys.argv else set()
BLENDER = os.environ.get('BLENDER', 'blender')
UNITY, UPROJ = os.environ.get('UNITY', ''), os.environ.get('UNITY_PROJECT', '')
LOCK = threading.Lock()

def run(cmd, **k):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=300, **k)
    return {'code': p.returncode, 'out': (p.stdout + p.stderr)[-6000:]}

def tmp(src, ext):
    f = tempfile.NamedTemporaryFile('w', suffix=ext, delete=False, encoding='utf-8')
    f.write(src); f.close(); return f.name

def act(a, d):
    if a == 'ping': return {'ok': 1}
    if a == 'shell': return run(d['cmd'], shell=True)
    if a == 'python': return run([sys.executable, tmp(d['code'], '.py')])
    if a == 'blender':
        return run([BLENDER, '-b', *([d['blend']] if d.get('blend') else []), '-P', tmp(d['code'], '.py')])
    if a == 'unity':  # C#-скрипт кладётся в Assets/Editor и вызывается JarvisRun.Go (проект не должен быть открыт в редакторе)
        ed = pathlib.Path(UPROJ, 'Assets', 'Editor'); ed.mkdir(parents=True, exist_ok=True)
        (ed / 'JarvisRun.cs').write_text(d['code'], encoding='utf-8')
        return run([UNITY, '-batchmode', '-quit', '-projectPath', UPROJ, '-executeMethod', 'JarvisRun.Go', '-logFile', '-'])
    if a == 'read': return {'text': pathlib.Path(d['path']).read_text(encoding='utf-8', errors='replace')[:200000]}
    if a == 'write':
        p = pathlib.Path(d['path']); p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(d['text'], encoding='utf-8'); return {'ok': 1}
    if a == 'ls': return {'items': sorted(os.listdir(d['path']))[:500]}
    raise ValueError('неизвестное действие: ' + a)

def flush():  # сбросить случайные нажатия, накопившиеся до вопроса
    try:
        import msvcrt
        while msvcrt.kbhit(): msvcrt.getwch()
    except ImportError:
        try:
            import termios; termios.tcflush(sys.stdin, termios.TCIFLUSH)
        except Exception: pass

def allowed(a, d):
    if a == 'ping' or a in TRUST: return True
    with LOCK:
        print('\n>>> Jarvis просит: %s\n%s' % (a, json.dumps(d, ensure_ascii=False)[:1500]))
        flush()
        return input('Разрешить? Введите y и нажмите Enter: ').strip().lower() in ('y', 'д')

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        for k, v in {'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*',
                     'Access-Control-Allow-Headers': 'Content-Type, X-Jarvis-Token',
                     'Content-Length': str(len(b))}.items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(b)
    def do_OPTIONS(self): self._send(204, {})
    def do_POST(self):
        try:
            if self.headers.get('X-Jarvis-Token') != TOK: return self._send(401, {'error': 'неверный токен'})
            m = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            a, d = m.get('action', ''), m.get('data', {})
            if not allowed(a, d): return self._send(403, {'error': 'владелец ПК отклонил действие'})
            self._send(200, act(a, d))
        except Exception as e:
            self._send(500, {'error': str(e)})
    def log_message(self, *a): pass

def ips():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(('192.168.43.1', 1)); return s.getsockname()[0]
    except Exception: return socket.gethostbyname(socket.gethostname())

if __name__ == '__main__':
    print('Jarvis-мост запущен.\nАдрес для приложения: %s:%d\nТокен: %s\nДоверенные виды: %s' % (ips(), PORT, TOK, ', '.join(TRUST) or 'нет'))
    ThreadingHTTPServer(('0.0.0.0', PORT), H).serve_forever()
