#!/usr/bin/env python3
"""Offline tests for sync_loot_tables.py: run with `python tools/test_sync_loot_tables.py`."""

import hashlib
import io
import json
import pathlib
import random
import re
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import requests
import sync_loot_tables as tool
from rich.console import Console


def quiet_ui():
    return tool.Ui(Console(file=io.StringIO()))


def ui_progress():
    return quiet_ui().progress_bar()


class DigestAlgorithmTest(unittest.TestCase):
    def test_selects_by_length(self):
        self.assertEqual(tool.digest_algorithm("a" * 40), "sha1")
        self.assertEqual(tool.digest_algorithm("b" * 64), "sha256")

    def test_rejects_other_lengths(self):
        with self.assertRaisesRegex(tool.ToolError, "hex chars"):
            tool.digest_algorithm("abc")


class ShaFromUrlTest(unittest.TestCase):
    def test_piston_style_path_segment(self):
        url = "https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar"
        self.assertEqual(tool._sha_from_url(url), "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c")

    def test_fill_style_sha256_segment(self):
        url = "https://fill-data.papermc.io/v1/objects/" + "a" * 64 + "/paper-26.3-33.jar"
        self.assertEqual(tool._sha_from_url(url), "a" * 64)

    def test_survives_query_string(self):
        url = "https://x.test/objects/" + "b" * 40 + "/server.jar?download=true"
        self.assertEqual(tool._sha_from_url(url), "b" * 40)

    def test_url_without_hash_yields_none(self):
        self.assertIsNone(tool._sha_from_url("https://x.test/downloads/server.jar"))


class HttpErrorTextTest(unittest.TestCase):
    def test_includes_status_when_response_present(self):
        error = requests.exceptions.HTTPError("404", response=SimpleNamespace(status_code=404))
        self.assertEqual(tool.http_error_text("https://x.test/a", error), "HTTP 404 from https://x.test/a")

    def test_includes_reason_when_bare(self):
        error = requests.exceptions.ConnectionError("connection refused")
        self.assertIn("connection refused", tool.http_error_text("https://x.test/a", error))


class PomVersionTest(unittest.TestCase):
    def test_reads_mc_version(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = pathlib.Path(scratch)
            (repo / "pom.xml").write_text("<project><mc.version>26.3</mc.version></project>", encoding="utf-8")
            self.assertEqual(tool.pom_version(repo), "26.3")

    def test_fails_without_pom_or_key(self):
        with tempfile.TemporaryDirectory() as scratch:
            with self.assertRaises(tool.ToolError):
                tool.pom_version(pathlib.Path(scratch))
            repo = pathlib.Path(scratch)
            (repo / "pom.xml").write_text("<project></project>", encoding="utf-8")
            with self.assertRaises(tool.ToolError):
                tool.pom_version(repo)


class VersionSlugsTest(unittest.TestCase):
    def test_flattens_the_fill_dict_shape_newest_first(self):
        project = {"versions": {"26.3": ["26.3", "26.3-rc-3"], "26.2": ["26.2"]}}
        self.assertEqual(tool._version_slugs(project), ["26.3", "26.3-rc-3", "26.2"])

    def test_accepts_plain_list_shape(self):
        self.assertEqual(tool._version_slugs({"versions": ["26.3", "26.2"]}), ["26.3", "26.2"])

    def test_empty_project_yields_empty(self):
        self.assertEqual(tool._version_slugs({}), [])


def paper_build(build_id, channel):
    return {
        "id": build_id,
        "channel": channel,
        "time": "2026-01-01T00:00:00Z",
        "downloads": {"server:default": {"name": f"paper-{build_id}.jar", "url": f"https://x.test/{build_id}.jar",
                                         "checksums": {"sha256": "a" * 64}}},
    }


class SelectBuildTest(unittest.TestCase):
    def test_stable_beats_newer_lower_channels(self):
        channel, build = tool._select_build([paper_build(30, "EXPERIMENTAL"), paper_build(20, "STABLE")])
        self.assertEqual((channel, build["id"]), ("STABLE", 20))

    def test_newest_build_within_channel(self):
        channel, build = tool._select_build([paper_build(5, "STABLE"), paper_build(9, "STABLE")])
        self.assertEqual((channel, build["id"]), ("STABLE", 9))

    def test_default_used_when_no_stable(self):
        channel, build = tool._select_build([paper_build(2, "ALPHA"), paper_build(1, "DEFAULT")])
        self.assertEqual((channel, build["id"]), ("DEFAULT", 1))

    def test_unknown_channel_fallback(self):
        channel, build = tool._select_build([paper_build(33, "ALPHA")])
        self.assertEqual((channel, build["id"]), ("ALPHA", 33))

    def test_no_builds(self):
        self.assertEqual(tool._select_build([]), (None, None))


class ExtractTablesTest(unittest.TestCase):
    WHEAT_BODY = '{"modifier":{"type":"minecraft:apply_bonus"}}'

    def make_bundler(self, root, version="26.3", separator="\\", crops=None, wheat_body=None):
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as archive:
            archive.writestr("version.json", json.dumps({"name": version, "id": version}))
            for crop in crops or tool.CROPS:
                body = (wheat_body or self.WHEAT_BODY) if crop == "wheat" else "{}"
                archive.writestr(f"data/minecraft/loot_table/blocks/{crop}.json", body)
        bundler = root / "bundler.jar"
        with zipfile.ZipFile(bundler, "w") as archive:
            archive.writestr(f"META-INF{separator}versions.list", f"{'0' * 64}\t26.3\t26.3/server-26.3.jar\n")
            archive.writestr(f"META-INF{separator}versions{separator}26.3{separator}server-26.3.jar", inner.getvalue())
        return bundler

    def test_extracts_through_backslash_stored_entries(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            staging = root / "staging"
            tool.extract_tables(self.make_bundler(root), "26.3", staging, quiet_ui())
            names = sorted(path.name for path in staging.iterdir())
            self.assertEqual(names, sorted([f"{crop}.json" for crop in tool.CROPS] + ["version.json"]))
            self.assertIn("apply_bonus", (staging / "wheat.json").read_text(encoding="utf-8"))

    def test_extracts_through_forward_slash_stored_entries(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            staging = root / "staging"
            tool.extract_tables(self.make_bundler(root, separator="/"), "26.3", staging, quiet_ui())
            self.assertTrue((staging / "version.json").is_file())

    def test_version_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            with self.assertRaisesRegex(tool.ToolError, "9.9"):
                tool.extract_tables(self.make_bundler(root, version="9.9"), "26.3", root / "staging", quiet_ui())

    def test_missing_table_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            crops = [crop for crop in tool.CROPS if crop != "cocoa"]
            with self.assertRaisesRegex(tool.ToolError, "cocoa"):
                tool.extract_tables(self.make_bundler(root, crops=crops), "26.3", root / "staging", quiet_ui())

    def test_wheat_without_fortune_modifier_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            body = '{"modifier":{"type":"minecraft:set_count"}}'
            with self.assertRaisesRegex(tool.ToolError, "apply_bonus"):
                tool.extract_tables(self.make_bundler(root, wheat_body=body), "26.3", root / "staging", quiet_ui())


class VerifyDigestTest(unittest.TestCase):
    def test_passes_on_match(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = pathlib.Path(scratch) / "f.bin"
            path.write_bytes(b"loot")
            tool.verify_digest(path, hashlib.sha256(b"loot").hexdigest(), quiet_ui())

    def test_fails_on_mismatch(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = pathlib.Path(scratch) / "f.bin"
            path.write_bytes(b"loot")
            with self.assertRaisesRegex(tool.ToolError, "integrity check failed"):
                tool.verify_digest(path, hashlib.sha1(b"other").hexdigest(), quiet_ui())


BLOB = random.Random(263).randbytes(5 * 1024 * 1024 + 12345)


class _BlobHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        range_header = self.headers.get("Range")
        supports = getattr(self.server, "supports_range", False)
        match = re.fullmatch(r"bytes=(\d+)-(\d+)", range_header or "") if supports else None
        if match:
            start, end = int(match[1]), int(match[2])
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(BLOB)}")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            self.wfile.write(BLOB[start:end + 1])
        else:
            self.send_response(200)
            self.send_header("Content-Length", str(len(BLOB)))
            if supports:
                self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            self.wfile.write(BLOB)

    def log_message(self, *args):
        pass


class DownloadServerTest(unittest.TestCase):
    supports_range = True

    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _BlobHandler)
        cls.server.supports_range = cls.supports_range
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}/server.jar"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.session = tool.http_session(tool.DEFAULT_UA)
        self.ui = quiet_ui()

    def total_and_rangeable(self):
        return tool._probe_download(self.session, self.url)


class ParallelDownloadTest(DownloadServerTest):
    def test_assembles_exact_bytes_across_parts(self):
        total, rangeable = self.total_and_rangeable()
        self.assertTrue(rangeable)
        with tempfile.TemporaryDirectory() as scratch:
            destination = pathlib.Path(scratch) / "out.jar"
            with ui_progress() as progress:
                self.assertTrue(tool._parallel_download(self.session, self.url, total, destination, self.ui, progress, "test"))
            self.assertEqual(destination.read_bytes(), BLOB)

    def test_download_all_verifies_and_returns_path(self):
        with tempfile.TemporaryDirectory() as scratch:
            paths = tool.download_all(self.session, [(self.url, hashlib.sha1(BLOB).hexdigest(), "test jar")], pathlib.Path(scratch), self.ui)
            self.assertEqual(paths[0].read_bytes(), BLOB)


class StreamFallbackTest(DownloadServerTest):
    supports_range = False

    def test_parallel_reports_unsupported_and_stream_recovers(self):
        total, rangeable = self.total_and_rangeable()
        self.assertFalse(rangeable)
        with tempfile.TemporaryDirectory() as scratch:
            destination = pathlib.Path(scratch) / "out.jar"
            with ui_progress() as progress:
                self.assertFalse(tool._parallel_download(self.session, self.url, total, destination, self.ui, progress, "test"))
                tool._stream_download(self.session, self.url, destination, self.ui, progress, "test", total)
            self.assertEqual(destination.read_bytes(), BLOB)


if __name__ == "__main__":
    unittest.main()
