#!/usr/bin/env python3
"""
P2P Phone Gateway — Combined HTTPS + WebSocket Server
Порт по умолчанию: 8765

- GET /         → отдаёт client.html (веб-интерфейс)
- WS  /         → WebSocket для телефона и браузера
- Записи звонков сохраняются в ./recordings/

TLS: самоподписанный сертификат генерируется автоматически при первом запуске
     (server.crt / server.key рядом с server.py).
     При первом открытии браузер покажет предупреждение — нажми «Дополнительно → Перейти».
     Без TLS браузер блокирует доступ к микрофону на HTTP (не localhost).
"""

import json
import pathlib
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import hashlib
import base64
import webbrowser
import uuid

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
SCRIPT_DIR     = pathlib.Path(__file__).parent.resolve()
CLIENT_HTML    = SCRIPT_DIR / "client.html"
RECORDINGS_DIR = SCRIPT_DIR / "recordings"
CERT_FILE      = SCRIPT_DIR / "server.crt"
KEY_FILE       = SCRIPT_DIR / "server.key"

# ── TLS ────────────────────────────────────────────────────────────────────
def ensure_tls_cert(ip: str):
    """Генерирует self-signed сертификат если его нет или IP изменился."""
    need_gen = True
    if CERT_FILE.exists() and KEY_FILE.exists():
        # Проверяем что сертификат валиден и содержит нужный SAN
        try:
            out = subprocess.check_output(
                ["openssl", "x509", "-in", str(CERT_FILE), "-noout",
                 "-checkend", "86400"],  # валиден ещё хотя бы сутки
                stderr=subprocess.DEVNULL
            )
            san_out = subprocess.check_output(
                ["openssl", "x509", "-in", str(CERT_FILE), "-noout", "-text"],
                stderr=subprocess.DEVNULL
            ).decode()
            if ip in san_out:
                need_gen = False
        except Exception:
            pass

    if not need_gen:
        log(f"TLS cert OK: {CERT_FILE.name}")
        return

    log(f"Generating self-signed TLS cert for {ip} ...")
    # SAN включает и IP и localhost чтобы работало с обоими
    san = f"subjectAltName=IP:{ip},IP:127.0.0.1,DNS:localhost"
    try:
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", str(KEY_FILE),
            "-out",    str(CERT_FILE),
            "-days",   "3650",
            "-nodes",
            "-subj",   f"/CN={ip}",
            "-addext", san,
        ], check=True, capture_output=True)
        log(f"TLS cert generated: {CERT_FILE.name}")
    except FileNotFoundError:
        log("WARNING: openssl not found — running without TLS (mic won't work on non-localhost)")
        CERT_FILE.unlink(missing_ok=True)
        KEY_FILE.unlink(missing_ok=True)
    except subprocess.CalledProcessError as e:
        log(f"WARNING: cert generation failed: {e.stderr.decode()[:200]}")
        CERT_FILE.unlink(missing_ok=True)
        KEY_FILE.unlink(missing_ok=True)

def make_ssl_context():
    """Создаёт SSL context из сгенерированного сертификата."""
    if not CERT_FILE.exists() or not KEY_FILE.exists():
        return None
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(str(CERT_FILE), str(KEY_FILE))
        return ctx
    except Exception as e:
        log(f"SSL context error: {e}")
        return None

# ── Состояние ──────────────────────────────────────────────────────────────
clients        = {}   # id -> {"sock", "role", "addr", "rec_file", "rec_path"}
phone_id       = None
lock           = threading.Lock()
client_counter = 0

def log(msg):
    print(f"{time.strftime('%H:%M:%S')}  {msg}", flush=True)

def ensure_recordings_dir():
    RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)

# ── HTTP helper ────────────────────────────────────────────────────────────
def serve_http(sock, method, path):
    """Отдаём client.html на любой GET-запрос."""
    # N-4 fix: таймаут чтобы медленные клиенты не блокировали поток
    sock.settimeout(10.0)
    try:
        if CLIENT_HTML.exists():
            body = CLIENT_HTML.read_bytes()
        else:
            body = b"<h1>client.html not found</h1><p>Put client.html next to server.py</p>"
        header = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: text/html; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Connection: close\r\n"
            "\r\n"
        ).encode()
        sock.sendall(header + body)
    except Exception as e:
        log(f"HTTP serve error: {e}")
    finally:
        try: sock.close()
        except: pass

# ── WebSocket (stdlib, RFC 6455) ───────────────────────────────────────────
MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

def ws_handshake_response(key):
    accept = base64.b64encode(
        hashlib.sha1((key + MAGIC).encode()).digest()
    ).decode()
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n"
        "\r\n"
    ).encode()

def ws_read_frame(sock):
    # C-1 fix: re-raise socket.timeout — не глотаем его, пусть caller обработает keepalive
    try:
        header = b""
        while len(header) < 2:
            chunk = sock.recv(2 - len(header))
            if not chunk: return None
            header += chunk
        b0, b1 = header[0], header[1]
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        length = b1 & 0x7F
        if length == 126:
            raw = _recv_exact(sock, 2)
            if raw is None: return None
            length = struct.unpack(">H", raw)[0]
        elif length == 127:
            raw = _recv_exact(sock, 8)
            if raw is None: return None
            length = struct.unpack(">Q", raw)[0]
        mask = b""
        if masked:
            mask = _recv_exact(sock, 4)
            if mask is None: return None
        data = _recv_exact(sock, length)
        if data is None: return None
        if masked:
            data = bytes(data[i] ^ mask[i % 4] for i in range(len(data)))
        return opcode, data
    except socket.timeout:
        raise   # C-1 fix: пробрасываем наверх для keepalive логики
    except Exception:
        return None

def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        try:
            chunk = sock.recv(min(65536, n - len(buf)))
        except socket.timeout:
            raise   # C-1 fix: пробрасываем
        except Exception:
            return None
        if not chunk: return None
        buf += chunk
    return buf

def ws_send_text(sock, text):
    ws_send_frame(sock, 0x01, text.encode("utf-8"))

def ws_send_binary(sock, data):
    ws_send_frame(sock, 0x02, data)

def ws_send_frame(sock, opcode, data):
    # Bug(v12)3 fix: НЕ глотаем исключения здесь — пусть вызывающий код ловит и помечает dead
    # Предыдущий try/except:pass предотвращал failed=True в broadcast, сокеты не очищались
    length = len(data)
    header = bytearray()
    header.append(0x80 | opcode)
    if length < 126:
        header.append(length)
    elif length < 65536:
        header.append(126)
        header += struct.pack(">H", length)
    else:
        header.append(127)
        header += struct.pack(">Q", length)
    sock.sendall(bytes(header) + data)

def ws_close(sock, data=b""):
    try: ws_send_frame(sock, 0x08, data)
    except: pass

# ── Обработчик соединения (HTTP или WS) ───────────────────────────────────
def handle_connection(sock, addr):
    global phone_id, client_counter

    # Bug#9 fix: таймаут на начальное чтение заголовков — медленные клиенты не блокируют поток
    sock.settimeout(10.0)
    try:
        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = sock.recv(4096)
            if not chunk: return
            raw += chunk
        headers_raw = raw.decode("utf-8", errors="replace")
    except Exception:
        try: sock.close()
        except: pass
        return
    finally:
        # Сбрасываем таймаут — дальше serve_http или WS сами управляют таймаутами
        try: sock.settimeout(None)
        except: pass

    lines = headers_raw.split("\r\n")
    request_line = lines[0] if lines else ""
    parts = request_line.split(" ")
    method = parts[0] if len(parts) > 0 else "GET"
    path   = parts[1] if len(parts) > 1 else "/"

    headers = {}
    for line in lines[1:]:
        if ": " in line:
            k, v = line.split(": ", 1)
            headers[k.strip().lower()] = v.strip()

    if headers.get("upgrade", "").lower() != "websocket":
        serve_http(sock, method, path)
        return

    key = headers.get("sec-websocket-key", "")
    if not key:
        sock.close()
        return

    sock.sendall(ws_handshake_response(key))

    with lock:
        client_counter += 1
        cid = str(client_counter)
        clients[cid] = {
            "sock":     sock,
            "role":     None,
            "addr":     addr,
            "rec_file": None,
            "rec_path": None,
            # Bug#10 fix: per-socket write lock предотвращает interleaving sendall из разных потоков
            "wlock":    threading.Lock(),
        }

    log(f"WS connected: {addr} id={cid}")

    role = None
    try:
        sock.settimeout(15.0)
        frame = ws_read_frame(sock)
        sock.settimeout(None)

        if not frame: return
        opcode, data = frame
        if opcode != 0x01: return

        try:
            reg = json.loads(data.decode("utf-8"))
            role = reg.get("role", "browser")
        except Exception:
            role = "browser"

        with lock:
            clients[cid]["role"] = role
            if role == "phone":
                phone_id = cid

        # Bug7 fix: получаем wlock до первой отправки — защищает все исходящие от race с broadcast
        with lock:
            my_wlock = clients[cid]["wlock"] if cid in clients else threading.Lock()

        if role == "phone":
            log(f"Phone registered: id={cid}")
            _broadcast_json({"type": "server_status", "phone_connected": True})
            with my_wlock:
                try:
                    ws_send_text(sock, json.dumps({"type": "get_state"}))
                except Exception:
                    pass
        else:
            log(f"Browser registered: id={cid}")
            with lock:
                is_phone_connected = phone_id is not None
            with my_wlock:
                try:
                    ws_send_text(sock, json.dumps({"type": "server_status",
                                                   "phone_connected": is_phone_connected}))
                except Exception:
                    pass

        # Основной цикл с keepalive
        sock.settimeout(60.0)
        while True:
            try:
                frame = ws_read_frame(sock)
            except socket.timeout:
                # C-1 fix: keepalive теперь реально работает (timeout re-raised)
                try:
                    # Bug7 fix: ping через wlock — не race-условие с broadcast
                    with my_wlock:
                        ws_send_frame(sock, 0x09, b"ping")
                    sock.settimeout(10.0)
                    pong = ws_read_frame(sock)
                    sock.settimeout(60.0)
                    if not pong: break
                    continue
                except Exception:
                    break
            if not frame: break
            opcode, data = frame

            if opcode == 0x08:
                ws_close(sock, data)
                break
            elif opcode == 0x09:
                # Bug7 fix: pong тоже через wlock
                with my_wlock:
                    ws_send_frame(sock, 0x0A, data)
                continue
            elif opcode == 0x01:
                try:
                    msg = json.loads(data.decode("utf-8"))
                    handle_message(cid, role, msg, sock)
                except Exception as e:
                    log(f"JSON error from {cid}: {e}")
            elif opcode == 0x02:
                with lock:
                    is_active_phone = (cid == phone_id)
                    client_role = clients[cid]["role"] if cid in clients else None
                if is_active_phone:
                    _broadcast_audio(data, exclude=cid)
                elif client_role == "browser":
                    if len(data) > 0 and data[0] == 0x03:
                        _append_recording(cid, data[1:])
                    else:
                        _send_binary_to_phone(data)

    except Exception as e:
        log(f"Client {cid} error: {e}")
    finally:
        _close_recording(cid)
        with lock:
            clients.pop(cid, None)
            was_phone = (role == "phone" and phone_id == cid)
            if was_phone:
                phone_id = None

        if was_phone:
            log("Phone disconnected")
            _broadcast_json({"type": "phone_disconnected"})
        elif role is not None:
            log(f"Browser {cid} disconnected")

        try: sock.close()
        except: pass

# ── Запись звонков ────────────────────────────────────────────────────────
def _open_recording(cid) -> str | None:
    """Открывает новый файл записи для клиента."""
    ts  = time.strftime("%Y-%m-%d_%H-%M-%S")
    uid = uuid.uuid4().hex[:8]   # Bug8: уникальный суффикс — нет коллизий при одновременном старте
    path = RECORDINGS_DIR / f"call_{ts}_{uid}.webm"
    try:
        # M-1 fix: открываем файл ВНЕ лока — файловый I/O не должен блокировать всех
        f = open(path, "wb")
        with lock:
            if cid not in clients:
                f.close()
                try: path.unlink()
                except: pass
                return None
            old = clients[cid].get("rec_file")
            if old:
                try: old.close()
                except: pass
            clients[cid]["rec_file"] = f
            clients[cid]["rec_path"] = str(path)
        log(f"Recording started: {path}")
        return str(path)
    except Exception as e:
        log(f"Recording open error: {e}")
        return None

def _append_recording(cid, data: bytes):
    # Bug9: НЕ держим глобальный lock во время file.write — I/O блокирует всех клиентов
    with lock:
        f = clients[cid]["rec_file"] if cid in clients else None
    if f:
        try:
            f.write(data)
        except (OSError, ValueError):
            pass  # файл закрыт между get и write — harmless

def _close_recording(cid) -> str | None:
    """Закрывает файл записи, возвращает путь."""
    # M-1 fix: возвращаем путь чтобы включить в rec_ack
    with lock:
        if cid not in clients:
            return None
        f    = clients[cid].get("rec_file")
        path = clients[cid].get("rec_path")
        clients[cid]["rec_file"] = None
        clients[cid]["rec_path"] = None
    if f:
        try:
            f.flush()
            f.close()
            log(f"Recording saved: {path}")
        except Exception as e:
            log(f"Recording close error: {e}")
    return path

# ── Хелперы WS ────────────────────────────────────────────────────────────
def _remove_client(cid):
    """Удаляем клиента из словаря и закрываем сокет (идемпотентно)."""
    global phone_id
    with lock:
        entry = clients.pop(cid, None)
        was_phone = (phone_id == cid)
        if was_phone:
            phone_id = None
    if entry:
        try: entry["sock"].close()
        except: pass
    if was_phone:
        log(f"Phone disconnected (evicted)")
        _broadcast_json({"type": "phone_disconnected"})

def _ws_send_locked(cid, data, binary=False):
    """Потокобезопасная отправка через per-socket lock с таймаутом."""
    with lock:
        entry = clients.get(cid)
    if not entry:
        return
    wlock = entry.get("wlock")
    sock  = entry.get("sock")
    if not wlock or not sock:
        return
    with wlock:
        try:
            sock.settimeout(2.0)
            if binary:
                ws_send_binary(sock, data)
            else:
                ws_send_text(sock, data if isinstance(data, str) else data.decode())
        except Exception:
            pass
        finally:
            try: sock.settimeout(None)
            except: pass

def _broadcast_json(msg, exclude=None):
    data = json.dumps(msg)
    with lock:
        targets = list(clients.keys())
    dead = []
    for cid in targets:
        with lock:
            entry = clients.get(cid)
        if not entry or entry.get("role") != "browser" or cid == exclude:
            continue
        wlock = entry.get("wlock")
        sock  = entry.get("sock")
        if not wlock or not sock:
            continue
        failed = False
        with wlock:
            try:
                sock.settimeout(2.0)
                ws_send_text(sock, data)
            except Exception:
                failed = True
            finally:
                try: sock.settimeout(None)
                except: pass
        # Bug5 fix: удаляем мёртвые сокеты сразу, не ждём 60с
        if failed:
            dead.append(cid)
    for cid in dead:
        _remove_client(cid)

def _broadcast_audio(data, exclude=None):
    with lock:
        targets = list(clients.keys())
    dead = []
    for cid in targets:
        with lock:
            entry = clients.get(cid)
        if not entry or entry.get("role") != "browser" or cid == exclude:
            continue
        wlock = entry.get("wlock")
        sock  = entry.get("sock")
        if not wlock or not sock:
            continue
        failed = False
        with wlock:
            try:
                sock.settimeout(2.0)
                ws_send_binary(sock, data)
            except Exception:
                failed = True
            finally:
                try: sock.settimeout(None)
                except: pass
        # Bug5 fix: удаляем мёртвые сокеты
        if failed:
            dead.append(cid)
    for cid in dead:
        _remove_client(cid)

def _send_to_phone(msg):
    with lock:
        if phone_id and phone_id in clients:
            entry = clients[phone_id]
        else:
            return False
    wlock = entry.get("wlock")
    sock  = entry.get("sock")
    if not wlock or not sock:
        return False
    with wlock:
        try:
            sock.settimeout(2.0)
            ws_send_text(sock, json.dumps(msg))
            return True
        except Exception:
            return False
        finally:
            try: sock.settimeout(None)
            except: pass

def _send_binary_to_phone(data):
    # Bug#8+10 fix: per-socket lock + таймаут
    with lock:
        if phone_id and phone_id in clients:
            entry = clients[phone_id]
        else:
            return
    wlock = entry.get("wlock")
    sock  = entry.get("sock")
    if not wlock or not sock:
        return
    with wlock:
        try:
            sock.settimeout(2.0)
            ws_send_binary(sock, data)
        except Exception:
            pass
        finally:
            try: sock.settimeout(None)
            except: pass

def _send_to_browser(cid, payload: dict):
    """Bug6 fix: отправка браузеру через per-socket wlock — предотвращает race с broadcast."""
    with lock:
        entry = clients.get(cid)
    if not entry:
        return
    wlock = entry.get("wlock")
    sock  = entry.get("sock")
    if not wlock or not sock:
        return
    data = json.dumps(payload)
    with wlock:
        try:
            sock.settimeout(2.0)
            ws_send_text(sock, data)
        except Exception:
            pass
        finally:
            try: sock.settimeout(None)
            except: pass

def handle_message(cid, role, msg, sock):
    t = msg.get("type", "")
    log(f"[{role}:{cid}] → {t}")

    if role == "browser":
        if t == "rec_start":
            path = _open_recording(cid)
            # Bug6 fix: используем _send_to_browser с wlock
            _send_to_browser(cid, {"type": "rec_ack", "status": "started", "path": path or ""})
        elif t == "rec_stop":
            path = _close_recording(cid)
            _send_to_browser(cid, {"type": "rec_ack", "status": "stopped", "path": path or ""})
        elif t == "ping":
            # Relay ping to phone; if offline, bounce pong back immediately
            if not _send_to_phone(msg):
                _send_to_browser(cid, {"type": "pong", "ts": msg.get("ts", 0), "offline": True})
        elif t in ("dial", "hangup", "accept_call", "get_call_history",
                   "get_contacts", "get_sms", "send_sms", "get_state",
                   "start_audio", "stop_audio"):
            if not _send_to_phone(msg):
                _send_to_browser(cid, {"type": "error", "message": "Phone not connected"})

    elif role == "phone":
        if t in ("call_state", "state", "call_history", "contacts",
                 "sms_history", "dial_result", "send_sms_result",
                 "accept_result", "hangup_result", "pong"):
            _broadcast_json(msg, exclude=cid)
        elif t == "log":
            log(f"[PHONE] {msg.get('message', '')}")

# ── Основной сервер ────────────────────────────────────────────────────────
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def main():
    ensure_recordings_dir()

    ip  = get_local_ip()

    # Генерируем / проверяем TLS сертификат
    ensure_tls_cert(ip)
    ssl_ctx = make_ssl_context()
    use_tls = ssl_ctx is not None

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", PORT))
    server.listen(20)

    scheme    = "https" if use_tls else "http"
    ws_scheme = "wss"   if use_tls else "ws"
    url = f"{scheme}://{ip}:{PORT}"

    print("=" * 60)
    print("  P2P Phone Gateway Server")
    print(f"  Web UI:      {url}")
    print(f"  WS endpoint: {ws_scheme}://{ip}:{PORT}")
    print(f"  TLS:         {'✓ HTTPS/WSS (self-signed)' if use_tls else '✗ HTTP only (mic blocked by browser)'}")
    print(f"  Recordings:  {RECORDINGS_DIR}")
    print()
    print(f"  В Android приложении введи: {ip}:{PORT}")
    if use_tls:
        print()
        print("  ⚠  Первый раз браузер покажет предупреждение о сертификате.")
        print("     Нажми «Дополнительно» → «Перейти на сайт» — это безопасно.")
    print("=" * 60)

    threading.Timer(1.5, lambda: webbrowser.open(url)).start()

    try:
        while True:
            raw_sock, addr = server.accept()
            raw_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if use_tls:
                try:
                    raw_sock.settimeout(10.0)   # handshake timeout
                    conn = ssl_ctx.wrap_socket(raw_sock, server_side=True)
                    conn.settimeout(None)         # reset to blocking
                except Exception as e:
                    # SSLError, ConnectionResetError, OSError — all expected when Android connects plain
                    log(f"SSL handshake failed from {addr}: {type(e).__name__}: {e}")
                    try: raw_sock.close()
                    except: pass
                    continue
            else:
                conn = raw_sock
            t = threading.Thread(target=handle_connection, args=(conn, addr), daemon=True)
            t.start()
    except KeyboardInterrupt:
        print("\nStopping...")
    finally:
        server.close()

if __name__ == "__main__":
    main()
