#!/usr/bin/env python3

import contextlib
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
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import requests
import sync_loot_tables as tool
from rich.console import Console

WHEAT_BODY = '{"modifier":{"type":"minecraft:apply_bonus"}}'
BLOB = random.Random(263).randbytes(5 * 1024 * 1024 + 12345)


def quiet_ui():
    return tool.Ui(Console(file=io.StringIO()))


def ui_progress():
    return quiet_ui().progress_bar()


def bundler_bytes(version="26.3", separator="\\", crops=None, wheat_body=WHEAT_BODY):
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as archive:
        archive.writestr("version.json", json.dumps({"name": version, "id": version}))
        for crop in crops or tool.CROPS:
            body = wheat_body if crop == "wheat" else "{}"
            archive.writestr(f"data/minecraft/loot_table/blocks/{crop}.json", body)
    outer = io.BytesIO()
    with zipfile.ZipFile(outer, "w") as archive:
        archive.writestr(
            f"META-INF{separator}versions.list",
            f"{'0' * 64}\t26.3\t26.3/server-26.3.jar\n",
        )
        archive.writestr(
            f"META-INF{separator}versions{separator}26.3{separator}server-26.3.jar",
            inner.getvalue(),
        )
    return outer.getvalue()


@contextlib.contextmanager
def blob_server(**flags):
    server = ThreadingHTTPServer(("127.0.0.1", 0), _BlobHandler)
    server.supports_range = flags.get("supports_range", True)
    server.fail_range = flags.get("fail_range", False)
    server.flaky_stream = flags.get("flaky_stream", 0)
    server.content_encoding = flags.get("content_encoding", False)
    server.blob = BLOB
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}/server.jar"
    finally:
        server.shutdown()
        server.server_close()


# noinspection PyPep8Naming
class _BlobHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        server = self.server
        range_header = self.headers.get("Range")
        supports_range = getattr(server, "supports_range", False)
        if getattr(server, "fail_range", False) and range_header:
            self.send_response(500)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        match = (
            re.fullmatch(r"bytes=(\d+)-(\d+)", range_header or "")
            if supports_range
            else None
        )
        if match:
            start, end = int(match[1]), int(match[2])
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(server.blob)}")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            self.wfile.write(server.blob[start : end + 1])
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(server.blob)))
        if supports_range:
            self.send_header("Accept-Ranges", "bytes")
        if getattr(server, "content_encoding", False):
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        if getattr(server, "flaky_stream", 0) > 0:
            server.flaky_stream -= 1
            self.wfile.write(server.blob[: len(server.blob) // 2])
            self.wfile.close()
            return
        self.wfile.write(server.blob)

    def log_message(self, *args):
        pass


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
        self.assertEqual(
            tool._sha_from_url(url), "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c"
        )

    def test_fill_style_sha256_segment(self):
        url = (
            "https://fill-data.papermc.io/v1/objects/" + "a" * 64 + "/paper-26.3-33.jar"
        )
        self.assertEqual(tool._sha_from_url(url), "a" * 64)

    def test_survives_query_string(self):
        url = "https://x.test/objects/" + "b" * 40 + "/server.jar?download=true"
        self.assertEqual(tool._sha_from_url(url), "b" * 40)

    def test_url_without_hash_yields_none(self):
        self.assertIsNone(tool._sha_from_url("https://x.test/downloads/server.jar"))


class HttpErrorTextTest(unittest.TestCase):
    def test_includes_status_when_response_present(self):
        error = requests.exceptions.HTTPError(
            "404", response=SimpleNamespace(status_code=404)
        )
        self.assertEqual(
            tool.http_error_text("https://x.test/a", error),
            "HTTP 404 from https://x.test/a",
        )

    def test_includes_reason_when_bare(self):
        error = requests.exceptions.ConnectionError("connection refused")
        self.assertIn(
            "connection refused", tool.http_error_text("https://x.test/a", error)
        )


class PomVersionTest(unittest.TestCase):
    def test_reads_mc_version(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = pathlib.Path(scratch)
            (repo / "pom.xml").write_text(
                "<project><mc.version>26.3</mc.version></project>", encoding="utf-8"
            )
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
        self.assertEqual(
            tool._version_slugs({"versions": ["26.3", "26.2"]}), ["26.3", "26.2"]
        )

    def test_handles_string_entries(self):
        self.assertEqual(tool._version_slugs({"versions": {"26.3": "26.3"}}), ["26.3"])

    def test_empty_project_yields_empty(self):
        self.assertEqual(tool._version_slugs({}), [])


def paper_build(build_id, channel):
    return {
        "id": build_id,
        "channel": channel,
        "time": "2026-01-01T00:00:00Z",
        "downloads": {
            "server:default": {
                "name": f"paper-{build_id}.jar",
                "url": f"https://x.test/{build_id}.jar",
                "checksums": {"sha256": "a" * 64},
            }
        },
    }


class SelectBuildTest(unittest.TestCase):
    def test_stable_beats_newer_lower_channels(self):
        channel, build = tool._select_build(
            [paper_build(30, "EXPERIMENTAL"), paper_build(20, "STABLE")]
        )
        self.assertEqual((channel, build["id"]), ("STABLE", 20))

    def test_newest_build_within_channel(self):
        channel, build = tool._select_build(
            [paper_build(5, "STABLE"), paper_build(9, "STABLE")]
        )
        self.assertEqual((channel, build["id"]), ("STABLE", 9))

    def test_default_used_when_no_stable(self):
        channel, build = tool._select_build(
            [paper_build(2, "ALPHA"), paper_build(1, "DEFAULT")]
        )
        self.assertEqual((channel, build["id"]), ("DEFAULT", 1))

    def test_unknown_channel_fallback(self):
        channel, build = tool._select_build([paper_build(33, "ALPHA")])
        self.assertEqual((channel, build["id"]), ("ALPHA", 33))

    def test_no_builds(self):
        self.assertEqual(tool._select_build([]), (None, None))


class ResolvePaperErrorTest(unittest.TestCase):
    @staticmethod
    def run_resolve(side_effects, mc="26.3"):
        args = SimpleNamespace(
            mc=mc, source="paper", url=None, sha=None, repo=pathlib.Path(".")
        )
        with mock.patch.object(tool, "http_json", side_effect=side_effects):
            report = tool.resolve_paper(args, None, quiet_ui())
        return report

    def test_unknown_version_lists_available(self):
        project = {"versions": {"26.3": ["26.3"], "26.2": ["26.2"]}}
        with self.assertRaisesRegex(tool.ToolError, "26.2"):
            self.run_resolve([project], mc="99.9")

    def test_zero_builds_fails(self):
        with self.assertRaisesRegex(tool.ToolError, "zero builds"):
            self.run_resolve([{"versions": {"26.3": ["26.3"]}}, []])

    def test_missing_checksum_fails(self):
        build = paper_build(1, "STABLE")
        del build["downloads"]["server:default"]["checksums"]
        with self.assertRaisesRegex(tool.ToolError, "sha256"):
            self.run_resolve([{"versions": {"26.3": ["26.3"]}}, [build]])


class ManifestShapeTest(unittest.TestCase):
    @staticmethod
    def run_resolve(side_effects, mc="26.3"):
        with mock.patch.object(tool, "http_json", side_effect=side_effects):
            report = tool.resolve_vanilla_manifest(mc, None, quiet_ui())
        return report

    def test_non_list_versions_fails(self):
        with self.assertRaisesRegex(tool.ToolError, "unexpected shape"):
            self.run_resolve([{"versions": "garbage"}])

    def test_entry_without_url_fails(self):
        with self.assertRaisesRegex(tool.ToolError, "no server jar download"):
            self.run_resolve([{"versions": [{"id": "26.3"}]}, {}])

    def test_entry_without_server_download_fails(self):
        with self.assertRaisesRegex(tool.ToolError, "no server jar download"):
            self.run_resolve(
                [
                    {"versions": [{"id": "26.3", "url": "https://x.test"}]},
                    {"downloads": {}},
                ]
            )


class ExtractTablesTest(unittest.TestCase):
    @staticmethod
    def make_bundler(
        root, version="26.3", separator="\\", crops=None, wheat_body=WHEAT_BODY
    ):
        root.mkdir(parents=True, exist_ok=True)
        (root / "bundler.jar").write_bytes(
            bundler_bytes(version, separator, crops, wheat_body)
        )
        return root / "bundler.jar"

    def test_extracts_through_backslash_stored_entries(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            staging = root / "staging"
            tool.extract_tables(self.make_bundler(root), "26.3", staging, quiet_ui())
            names = sorted(path.name for path in staging.iterdir())
            self.assertEqual(
                names,
                sorted([f"{crop}.json" for crop in tool.CROPS] + ["version.json"]),
            )
            self.assertIn(
                "apply_bonus", (staging / "wheat.json").read_text(encoding="utf-8")
            )

    def test_extracts_through_forward_slash_stored_entries(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            staging = root / "staging"
            tool.extract_tables(
                self.make_bundler(root, separator="/"), "26.3", staging, quiet_ui()
            )
            self.assertTrue((staging / "version.json").is_file())

    def test_version_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            with self.assertRaisesRegex(tool.ToolError, "9.9"):
                tool.extract_tables(
                    self.make_bundler(root, version="9.9"),
                    "26.3",
                    root / "staging",
                    quiet_ui(),
                )

    def test_missing_table_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            crops = [crop for crop in tool.CROPS if crop != "cocoa"]
            with self.assertRaisesRegex(tool.ToolError, "cocoa"):
                tool.extract_tables(
                    self.make_bundler(root, crops=crops),
                    "26.3",
                    root / "staging",
                    quiet_ui(),
                )

    def test_missing_version_json_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, "w") as archive:
                archive.writestr("data/minecraft/loot_table/blocks/wheat.json", "{}")
            jar = root / "bundler.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr(
                    "META-INF\\versions.list",
                    f"{'0' * 64}\t26.3\t26.3/server-26.3.jar\n",
                )
                archive.writestr(
                    "META-INF\\versions\\26.3\\server-26.3.jar", inner.getvalue()
                )
            with self.assertRaisesRegex(tool.ToolError, "version.json"):
                tool.extract_tables(jar, "26.3", root / "staging", quiet_ui())

    def test_wheat_without_fortune_modifier_fails(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = pathlib.Path(scratch)
            body = '{"modifier":{"type":"minecraft:set_count"}}'
            with self.assertRaisesRegex(tool.ToolError, "apply_bonus"):
                tool.extract_tables(
                    self.make_bundler(root, wheat_body=body),
                    "26.3",
                    root / "staging",
                    quiet_ui(),
                )


class VerifyDigestTest(unittest.TestCase):
    @staticmethod
    def test_passes_on_match():
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


class DownloadTestBase(unittest.TestCase):
    session: requests.Session
    ui: tool.Ui

    # noinspection PyPep8Naming
    def setUp(self):
        self.session = tool.http_session(tool.DEFAULT_UA)
        self.ui = quiet_ui()
        patcher = mock.patch.object(tool, "BACKOFF_SECONDS", 0)
        patcher.start()
        self.addCleanup(patcher.stop)


class ParallelDownloadTest(DownloadTestBase):
    def test_assembles_exact_bytes_across_parts(self):
        with blob_server() as url:
            total, rangeable = tool._probe_download(self.session, url)
            self.assertTrue(rangeable)
            with tempfile.TemporaryDirectory() as scratch:
                destination = pathlib.Path(scratch) / "out.jar"
                with ui_progress() as progress:
                    self.assertTrue(
                        tool._parallel_download(
                            self.session, url, total, destination, progress, "test"
                        )
                    )
                self.assertEqual(destination.read_bytes(), BLOB)

    def test_download_all_verifies_and_returns_path(self):
        with blob_server() as url, tempfile.TemporaryDirectory() as scratch:
            paths = tool.download_all(
                self.session,
                [(url, hashlib.sha1(BLOB).hexdigest(), "test jar")],
                pathlib.Path(scratch),
                self.ui,
            )
            self.assertEqual(paths[0].read_bytes(), BLOB)

    def test_server_erroring_on_ranges_falls_back_cleanly(self):
        with blob_server(fail_range=True) as url:
            total, _ = tool._probe_download(self.session, url)
            with tempfile.TemporaryDirectory() as scratch:
                destination = pathlib.Path(scratch) / "out.jar"
                with ui_progress() as progress:
                    self.assertFalse(
                        tool._parallel_download(
                            self.session, url, total, destination, progress, "test"
                        )
                    )


class StreamFallbackTest(DownloadTestBase):
    def test_fallback_stream_recovers_full_body(self):
        with blob_server(supports_range=False) as url:
            total, rangeable = tool._probe_download(self.session, url)
            self.assertFalse(rangeable)
            with tempfile.TemporaryDirectory() as scratch:
                destination = pathlib.Path(scratch) / "out.jar"
                with ui_progress() as progress:
                    self.assertFalse(
                        tool._parallel_download(
                            self.session, url, total, destination, progress, "test"
                        )
                    )
                    tool._stream_download(
                        self.session, url, destination, progress, "test", total
                    )
                self.assertEqual(destination.read_bytes(), BLOB)


class ProbeGuardTest(DownloadTestBase):
    def test_gzip_content_disables_parallel(self):
        with blob_server(content_encoding=True) as url:
            total, rangeable = tool._probe_download(self.session, url)
            self.assertFalse(rangeable)
            self.assertEqual(total, len(BLOB))


class StreamRetryTest(DownloadTestBase):
    def test_truncated_bodies_are_retried(self):
        with (
            blob_server(supports_range=False, flaky_stream=2) as url,
            tempfile.TemporaryDirectory() as scratch,
        ):
            destination = pathlib.Path(scratch) / "out.jar"
            with ui_progress() as progress:
                tool._stream_download(
                    self.session, url, destination, progress, "test", len(BLOB)
                )
            self.assertEqual(destination.read_bytes(), BLOB)

    def test_permanent_failure_raises_after_retries(self):
        with (
            blob_server(supports_range=False, flaky_stream=99) as url,
            tempfile.TemporaryDirectory() as scratch,
            ui_progress() as progress,
            self.assertRaisesRegex(tool.ToolError, "after 3 attempts"),
        ):
            destination = pathlib.Path(scratch) / "out.jar"
            tool._stream_download(
                self.session, url, destination, progress, "test", len(BLOB)
            )


# noinspection PyPep8Naming
class MainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = bundler_bytes()
        cls.sha1 = hashlib.sha1(cls.bundle).hexdigest()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _BlobHandler)
        cls.server.supports_range = True
        cls.server.fail_range = False
        cls.server.flaky_stream = 0
        cls.server.blob = cls.bundle
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}/objects/{cls.sha1}/server.jar"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    @staticmethod
    def run_main(argv):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = tool.main(argv)
        return code, buffer.getvalue()

    def test_happy_path_installs_tables(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = pathlib.Path(scratch)
            code, _ = self.run_main(
                ["--url", self.url, "--mc", "26.3", "--repo", str(repo)]
            )
            self.assertEqual(code, 0)
            tables = sorted(path.name for path in (repo / "loot_tables").iterdir())
            self.assertEqual(
                tables,
                sorted([f"{crop}.json" for crop in tool.CROPS] + ["version.json"]),
            )
            self.assertIn(
                "apply_bonus",
                (repo / "loot_tables" / "wheat.json").read_text(encoding="utf-8"),
            )

    def test_blocked_swap_reports_actionable_error(self):
        with tempfile.TemporaryDirectory() as scratch:
            repo = pathlib.Path(scratch)
            (repo / "loot_tables").write_text("blocked", encoding="utf-8")
            code, out = self.run_main(
                ["--url", self.url, "--mc", "26.3", "--repo", str(repo)]
            )
            self.assertEqual(code, 1)
            self.assertIn("could not install", out)

    def test_preflight_rejects_url_with_paper(self):
        with tempfile.TemporaryDirectory() as scratch:
            code, out = self.run_main(
                ["--source", "paper", "--url", self.url, "--repo", scratch]
            )
            self.assertEqual(code, 1)
            self.assertIn("--url only applies", out)

    def test_preflight_rejects_sha_without_url(self):
        with tempfile.TemporaryDirectory() as scratch:
            code, out = self.run_main(["--sha", "a" * 40, "--repo", scratch])
            self.assertEqual(code, 1)
            self.assertIn("--sha only applies", out)

    def test_preflight_rejects_missing_repo(self):
        with tempfile.TemporaryDirectory() as scratch:
            missing = pathlib.Path(scratch) / "nope"
            code, out = self.run_main(["--repo", str(missing)])
            self.assertEqual(code, 1)
            self.assertIn("repository path does not exist", out)


if __name__ == "__main__":
    unittest.main()
