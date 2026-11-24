#!/usr/bin/env python3
"""NexosPlay backend: expone yt-dlp por HTTP para la app Android.

Endpoints:
  GET /health      -> {"ok": true, "ytdlp": "<version>"}
  GET /info?url=.. -> {"title", "uploader", "duration",
                       "video": {"url", "label", "ext"},
                       "audio": {"url", "label", "ext"}}
Las URL son directas y expiran: la app las usa pronto para descargar.
Solo stdlib; yt-dlp se llama como subproceso.
"""
import json
import os
import shutil
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

YTDLP_TIMEOUT = int(os.environ.get("NEXOSPLAY_YTDLP_TIMEOUT", "60"))
MAX_URL_LEN = 2048


def run_ytdlp_json(page_url):
    out = subprocess.run(
        ["yt-dlp", "--dump-json", "--no-playlist", "--no-warnings",
         "--socket-timeout", "20", page_url],
        capture_output=True, text=True, timeout=YTDLP_TIMEOUT, check=False)
    if out.returncode != 0:
        err = (out.stderr or out.stdout or "").strip().splitlines()
        raise RuntimeError(err[-1][:300] if err else f"yt-dlp salió con {out.returncode}")
    try:
        return json.loads(out.stdout)
    except json.JSONDecodeError:
        raise RuntimeError("yt-dlp devolvió JSON inválido")


def ytdlp_version():
    if shutil.which("yt-dlp") is None:
        return None
    try:
        out = subprocess.run(["yt-dlp", "--version"], capture_output=True,
                             text=True, timeout=15, check=False)
        return out.stdout.strip().splitlines()[0] if out.returncode == 0 else "desconocida"
    except (OSError, subprocess.SubprocessError, IndexError):
        return "desconocida"


def pick_video(meta):
    """Mejor mp4 progresivo (video+audio juntos)."""
    best = None
    for f in meta.get("formats", []) or []:
        if not f.get("url"):
            continue
        # None = códec desconocido (archivo directo): se acepta; "none" = ausente.
        if f.get("vcodec") == "none" or f.get("acodec") == "none":
            continue
        if (f.get("protocol") or "").startswith("m3u8"):
            continue
        key = ((f.get("ext") == "mp4"), f.get("height") or 0, f.get("tbr") or 0)
        if best is None or key > best[0]:
            best = (key, f)
    if best is None:
        return None
    f = best[1]
    h = f.get("height")
    return {"url": f["url"], "label": f"{h}p" if h else (f.get("format_id") or "video"),
            "ext": f.get("ext") or "mp4"}


def pick_audio(meta):
    """Mejor audio progresivo por bitrate."""
    best = None
    for f in meta.get("formats", []) or []:
        if not f.get("url"):
            continue
        if f.get("acodec") == "none":
            continue
        if (f.get("protocol") or "").startswith("m3u8"):
            continue
        pure = f.get("vcodec") in (None, "none")
        key = (pure, f.get("ext") in ("m4a", "mp3", "opus", "webm", "mp4"),
               f.get("abr") or f.get("tbr") or 0)
        if best is None or key > best[0]:
            best = (key, f)
    if best is None:
        return None
    f = best[1]
    abr = f.get("abr") or f.get("tbr")
    return {"url": f["url"], "label": f"{int(abr)}k" if abr else (f.get("format_id") or "audio"),
            "ext": f.get("ext") or "m4a"}


def curated(meta):
    return {
        "title": meta.get("title") or "",
        "uploader": meta.get("uploader") or meta.get("channel") or "",
        "duration": meta.get("duration") or 0,
        "video": pick_video(meta),
        "audio": pick_audio(meta),
    }


def valid_url(value):
    if not value or len(value) > MAX_URL_LEN:
        return None
    try:
        parts = urlparse(value)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https") or not parts.netloc:
        return None
    return value


class Handler(BaseHTTPRequestHandler):
    server_version = "NexosPlayBackend/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("backend: " + fmt % args + "\n")

    def send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            ver = ytdlp_version()
            self.send_json(200 if ver else 503, {"ok": bool(ver), "ytdlp": ver or "missing"})
            return
        if parsed.path == "/info":
            url = valid_url((parse_qs(parsed.query).get("url") or [""])[0])
            if not url:
                self.send_json(400, {"error": "parámetro url inválido (http/https, máx 2048)"})
                return
            try:
                self.send_json(200, curated(run_ytdlp_json(url)))
            except subprocess.TimeoutExpired:
                self.send_json(504, {"error": "yt-dlp tardó demasiado"})
            except RuntimeError as e:
                self.send_json(502, {"error": str(e)})
            except OSError as e:
                self.send_json(500, {"error": f"no se pudo ejecutar yt-dlp: {e}"})
            return
        self.send_json(404, {"error": "ruta desconocida (usa /health o /info)"})


def main(argv):
    port = int(argv[1]) if len(argv) > 1 else int(os.environ.get("PORT", "8765"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"NexosPlay backend en http://0.0.0.0:{port} (yt-dlp: {ytdlp_version() or 'FALTA'})",
          flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main(sys.argv)
