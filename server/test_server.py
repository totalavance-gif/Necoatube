#!/usr/bin/env python3
"""Pruebas del backend NexosPlay. Corre `python3 server/test_server.py` desde la raíz."""
import os
import shutil
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib import request as urlrequest
from urllib import error as urlerror
from urllib.parse import quote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server

SAMPLE = {
    "title": "Video de prueba",
    "uploader": "Canal X",
    "duration": 596,
    "formats": [
        {"format_id": "18", "url": "https://x/18.mp4", "ext": "mp4",
         "vcodec": "avc1", "acodec": "mp4a", "height": 360, "tbr": 500,
         "protocol": "https"},
        {"format_id": "22", "url": "https://x/22.mp4", "ext": "mp4",
         "vcodec": "avc1", "acodec": "mp4a", "height": 720, "tbr": 2000,
         "protocol": "https"},
        {"format_id": "133", "url": "https://x/133.mp4", "ext": "mp4",
         "vcodec": "avc1", "acodec": "none", "height": 240,
         "protocol": "https"},
        {"format_id": "140", "url": "https://x/140.m4a", "ext": "m4a",
         "vcodec": "none", "acodec": "mp4a", "abr": 128,
         "protocol": "https"},
        {"format_id": "hls-audio", "url": "https://x/a.m3u8", "ext": "m4a",
         "vcodec": "none", "acodec": "mp4a", "abr": 256,
         "protocol": "m3u8_native"},
    ],
}

LIVE_URL = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"


class PickTest(unittest.TestCase):
    def test_video_prefiere_720p_mp4(self):
        v = server.pick_video(SAMPLE)
        self.assertEqual(v["url"], "https://x/22.mp4")
        self.assertEqual(v["label"], "720p")
        self.assertEqual(v["ext"], "mp4")

    def test_video_ignora_solo_video(self):
        only = {"formats": [SAMPLE["formats"][2]]}
        self.assertIsNone(server.pick_video(only))

    def test_audio_prefiere_m4a_progresivo(self):
        a = server.pick_audio(SAMPLE)
        self.assertEqual(a["url"], "https://x/140.m4a")
        self.assertEqual(a["label"], "128k")

    def test_audio_ignora_hls(self):
        only = {"formats": [SAMPLE["formats"][4]]}
        self.assertIsNone(server.pick_audio(only))

    def test_archivo_directo_codecs_desconocidos(self):
        direct = {"formats": [
            {"format_id": "mp4", "url": "https://x/a.mp4", "ext": "mp4",
             "vcodec": None, "acodec": None, "height": None,
             "protocol": "https"},
        ]}
        v = server.pick_video(direct)
        self.assertEqual(v["url"], "https://x/a.mp4")
        a = server.pick_audio(direct)
        self.assertEqual(a["url"], "https://x/a.mp4")

    def test_curated_shape(self):
        c = server.curated(SAMPLE)
        self.assertEqual(c["title"], "Video de prueba")
        self.assertEqual(c["uploader"], "Canal X")
        self.assertEqual(c["duration"], 596)
        self.assertIn("url", c["video"])
        self.assertIn("url", c["audio"])

    def test_valid_url(self):
        self.assertTrue(server.valid_url("https://www.youtube.com/watch?v=abc"))
        self.assertTrue(server.valid_url("http://192.168.1.5:8765/x"))
        self.assertIsNone(server.valid_url(""))
        self.assertIsNone(server.valid_url("ftp://x/y"))
        self.assertIsNone(server.valid_url("javascript:alert(1)"))
        self.assertIsNone(server.valid_url("x" * 3000))


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._run = server.run_ytdlp_json
        cls._ver = server.ytdlp_version
        server.run_ytdlp_json = lambda url: SAMPLE
        server.ytdlp_version = lambda: "2099.01.01-test"
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        server.run_ytdlp_json = cls._run
        server.ytdlp_version = cls._ver

    def get(self, path):
        with urlrequest.urlopen(f"http://127.0.0.1:{self.port}{path}",
                                timeout=10) as r:
            return r.status, r.read().decode("utf-8")

    def test_health(self):
        code, body = self.get("/health")
        self.assertEqual(code, 200)
        self.assertIn('"ok": true', body)

    def test_info_ok(self):
        code, body = self.get("/info?url=" + quote("https://www.youtube.com/watch?v=abc", safe=""))
        self.assertEqual(code, 200)
        self.assertIn("Video de prueba", body)
        self.assertIn("https://x/22.mp4", body)

    def test_info_sin_url_400(self):
        with self.assertRaises(urlerror.HTTPError) as ctx:
            self.get("/info")
        self.assertEqual(ctx.exception.code, 400)
        ctx.exception.close()

    def test_ruta_desconocida_404(self):
        with self.assertRaises(urlerror.HTTPError) as ctx:
            self.get("/nada")
        self.assertEqual(ctx.exception.code, 404)
        ctx.exception.close()


@unittest.skipUnless(os.environ.get("NEXOSPLAY_LIVE") == "1", "requiere NEXOSPLAY_LIVE=1")
@unittest.skipIf(shutil.which("yt-dlp") is None, "requiere yt-dlp instalado")
class LiveTest(unittest.TestCase):
    def test_info_real(self):
        meta = server.run_ytdlp_json(LIVE_URL)
        c = server.curated(meta)
        self.assertTrue(c["title"])
        self.assertTrue(c["video"] or c["audio"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
