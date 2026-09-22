#!/usr/bin/env python3
"""Sync loot_tables/ for ReplenishPlusPlus from the vanilla Minecraft server jar."""

import argparse
import concurrent.futures
import hashlib
import json
import os
import platform
import re
import requests
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import zipfile
from pathlib import Path
from rich.console import Console
from rich.markup import escape
from rich.panel import Panel
from rich.progress import BarColumn, DownloadColumn, Progress, SpinnerColumn, TaskProgressColumn, TextColumn, TimeElapsedColumn, TimeRemainingColumn, TransferSpeedColumn

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FILL_BASE = "https://fill.papermc.io/v3"
CROPS = ("wheat", "carrots", "potatoes", "beetroots", "nether_wart", "cocoa")
TABLE_DIR = "data/minecraft/loot_table/blocks"
CHANNELS = ("STABLE", "DEFAULT", "EXPERIMENTAL")
DEFAULT_UA = "ReplenishPlusPlus-loot-sync/1.0 (+https://github.com/Mitra-88/ReplenishPlusPlus)"
CHUNK = 1 << 20
DOWNLOAD_THREADS = 8
PART_RETRIES = 3
BACKOFF_SECONDS = 0.5
CONNECT_TIMEOUT = 10
READ_TIMEOUT = 30
PARALLEL_MIN_BYTES = 4 << 20
STEPS = 5


class ToolError(Exception):
    pass


class _NotRangeable(Exception):
    pass


class Ui:
    def __init__(self, console=None):
        self.console = console or Console()

    def green(self, text):
        return f"[green]{text}[/green]"

    def yellow(self, text):
        return f"[yellow]{text}[/yellow]"

    def red(self, text):
        return f"[red]{text}[/red]"

    def cyan(self, text):
        return f"[cyan]{text}[/cyan]"

    def bold(self, text):
        return f"[bold]{text}[/bold]"

    def line(self, text=""):
        self.console.print(text)

    def banner(self, repo):
        system = escape(platform.system() or "unknown")
        release = escape(platform.release() or "")
        machine = escape(platform.machine() or "unknown")
        body = f"[bold]python {escape(platform.python_version())}[/bold] on {system} {release} {machine}\n[dim]{escape(str(repo))}[/dim]"
        self.console.print(Panel(body, title=":zap: ReplenishPlusPlus loot table sync", border_style="gold3", title_align="left"))

    def step(self, number, text):
        self.console.print(f"[cyan]{escape(f'[{number}/{STEPS}]')}[/cyan] [bold]{text}[/bold]")

    def ok(self, text):
        self.console.print(f"  [green]✓[/green] {escape(text)}")

    def warn(self, text):
        self.console.print(f"  [yellow]⚠ {escape(text)}[/yellow]")

    def info(self, text):
        self.console.print(f"  [dim]{escape(text)}[/dim]")

    def spin(self, text):
        return self.console.status(text, spinner="aesthetic")

    def progress_bar(self):
        return Progress(
            SpinnerColumn(spinner_name="dots", style="bold cyan", finished_text="[green]✓[/green]"),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(bar_width=30, complete_style="cyan", finished_style="green"),
            TaskProgressColumn(),
            DownloadColumn(binary_units=True),
            TransferSpeedColumn(),
            TimeElapsedColumn(),
            TimeRemainingColumn(compact=True),
            console=self.console,
            transient=True,
        )


def http_session(user_agent):
    session = requests.Session()
    session.headers.update({"User-Agent": user_agent})
    adapter = requests.adapters.HTTPAdapter(pool_connections=16, pool_maxsize=16)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


def http_error_text(url, error):
    response = getattr(error, "response", None)
    if response is not None:
        return f"HTTP {response.status_code} from {url}"
    return f"cannot reach {url}: {error}"


def http_json(url, session):
    try:
        response = session.get(url, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT))
        response.raise_for_status()
        return response.json()
    except requests.exceptions.JSONDecodeError as error:
        raise ToolError(f"{url} returned malformed JSON") from error
    except requests.exceptions.RequestException as error:
        raise ToolError(http_error_text(url, error)) from error


def digest_algorithm(expected):
    if len(expected) == 40:
        return "sha1"
    if len(expected) == 64:
        return "sha256"
    raise ToolError(f"expected digest has {len(expected)} hex chars, want 40 (sha1) or 64 (sha256)")


def verify_digest(path, expected_sha, ui):
    algorithm = digest_algorithm(expected_sha)
    with open(path, "rb") as source:
        actual = hashlib.file_digest(source, algorithm).hexdigest()
    if actual != expected_sha.lower():
        raise ToolError(
            f"integrity check failed: expected {algorithm} {expected_sha}, got {actual}, the file changed upstream or was corrupted in transit"
        )
    ui.ok(f"{algorithm} verified: {actual}")


def _probe_download(session, url):
    with session.get(url, stream=True, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT)) as response:
        response.raise_for_status()
        total = int(response.headers.get("Content-Length") or 0)
        rangeable = response.headers.get("Accept-Ranges", "") == "bytes"
    return total, rangeable


def _fetch_part(session, url, start, end, file, lock, progress, task, part_index):
    headers = {"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"}
    for attempt in range(PART_RETRIES):
        try:
            with session.get(url, stream=True, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), headers=headers) as response:
                if response.status_code != 206:
                    raise _NotRangeable()
                response.raise_for_status()
                position = start
                for chunk in response.iter_content(chunk_size=CHUNK):
                    with lock:
                        file.seek(position)
                        file.write(chunk)
                    position += len(chunk)
                    progress.update(task, advance=len(chunk))
                if position != end + 1:
                    raise IOError(f"short read: got {position - start} of {end + 1 - start} bytes")
                return
        except _NotRangeable:
            raise
        except (requests.exceptions.RequestException, IOError) as error:
            if attempt == PART_RETRIES - 1:
                raise ToolError(f"part {part_index} failed after {PART_RETRIES} attempts: {error}") from error
            time.sleep(BACKOFF_SECONDS * 2 ** attempt)


def _parallel_download(session, url, total, destination, ui, progress, label):
    part_size = -(-total // DOWNLOAD_THREADS)
    task = progress.add_task(label, total=total)
    lock = threading.Lock()
    range_unsupported = False
    failure = None
    with open(destination, "wb") as file:
        file.truncate(total)
        with concurrent.futures.ThreadPoolExecutor(max_workers=DOWNLOAD_THREADS, thread_name_prefix="dl") as pool:
            futures = [
                pool.submit(_fetch_part, session, url, part * part_size, min(part * part_size + part_size, total) - 1,
                            file, lock, progress, task, part)
                for part in range(DOWNLOAD_THREADS)
            ]
            for future in futures:
                try:
                    future.result()
                except _NotRangeable:
                    range_unsupported = True
                    for pending in futures:
                        pending.cancel()
                except concurrent.futures.CancelledError:
                    pass
                except ToolError as error:
                    failure = failure or error
    progress.remove_task(task)
    if range_unsupported:
        return False
    if failure:
        raise failure
    return True


def _stream_download(session, url, destination, ui, progress, label, total):
    with session.get(url, stream=True, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), headers={"Accept-Encoding": "identity"}) as response:
        response.raise_for_status()
        task = progress.add_task(label, total=total or None)
        done = 0
        with open(destination, "wb") as target:
            for chunk in response.iter_content(chunk_size=CHUNK):
                target.write(chunk)
                done += len(chunk)
                progress.update(task, completed=done)
    progress.remove_task(task)


def download_all(session, downloads, scratch, ui):
    paths = []
    with ui.progress_bar() as progress:
        for index, (url, sha, label) in enumerate(downloads):
            destination = Path(scratch) / f"jar{index}.jar"
            try:
                total, rangeable = _probe_download(session, url)
            except requests.exceptions.RequestException as error:
                raise ToolError(http_error_text(url, error)) from error
            if rangeable and total >= PARALLEL_MIN_BYTES:
                ui.info(f"{label} via {DOWNLOAD_THREADS} parallel connections")
                if not _parallel_download(session, url, total, destination, ui, progress, label):
                    ui.info("server ignored range requests, falling back to a single stream")
                    _stream_download(session, url, destination, ui, progress, label, total)
            else:
                _stream_download(session, url, destination, ui, progress, label, total)
            verify_digest(destination, sha, ui)
            paths.append(destination)
    return paths


def pom_version(repo):
    pom = repo / "pom.xml"
    if not pom.is_file():
        raise ToolError(f"{pom} not found, pass --mc or --repo")
    match = re.search(r"<mc\.version>([^<]+)</mc\.version>", pom.read_text(encoding="utf-8"))
    if not match:
        raise ToolError(f"no <mc.version> in {pom}, pass --mc")
    return match.group(1).strip()


def resolve_vanilla_manifest(mc, session, ui):
    with ui.spin("querying Mojang version manifest"):
        manifest = http_json(MANIFEST_URL, session)
    entry = next((v for v in manifest.get("versions", []) if v.get("id") == mc), None)
    if entry is None:
        latest = ", ".join(v["id"] for v in manifest.get("versions", [])[:5])
        raise ToolError(f"Minecraft {mc} is not in the version manifest, newest releases are: {latest}")
    with ui.spin(f"resolving the server jar for Minecraft {mc}"):
        details = http_json(entry["url"], session)
    try:
        server = details["downloads"]["server"]
        return server["url"], server["sha1"]
    except KeyError as error:
        raise ToolError(f"the manifest entry for {mc} has no server jar download") from error


def resolve_vanilla(args, mc, session, ui):
    if args.url:
        sha = args.sha or _sha_from_url(args.url)
        if not sha:
            raise ToolError("no sha1/sha256 hash found in the URL path, pass the expected digest with --sha")
        digest_algorithm(sha)
        ui.info(f"direct download: {args.url}")
        return args.url, sha.lower()
    return resolve_vanilla_manifest(mc, session, ui)


def _sha_from_url(url):
    return next(
        (segment for segment in re.split(r"[/?]", url)
         if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", segment)),
        None,
    )


def _version_slugs(project):
    raw = project.get("versions") or {}
    if isinstance(raw, dict):
        return [slug for family in raw.values() for slug in family]
    return [slug for family in raw for slug in (family if isinstance(family, list) else [family])]


def _select_build(builds):
    by_channel = {}
    for build in builds:
        by_channel.setdefault(str(build.get("channel") or "").upper(), []).append(build)
    channel = next((c for c in CHANNELS if c in by_channel), None)
    if channel is None:
        if not by_channel:
            return None, None
        channel = sorted(by_channel)[0]
    pool = by_channel[channel]
    newest = max(pool, key=lambda b: (b.get("build") or b.get("id") or 0, str(b.get("time") or "")))
    return channel, newest


def resolve_paper(args, session, ui):
    with ui.spin("querying the PaperMC Fill API"):
        project = http_json(f"{FILL_BASE}/projects/paper", session)
    slugs = _version_slugs(project)
    if args.mc:
        if args.mc not in slugs:
            shown = ", ".join(str(v) for v in slugs[:6])
            raise ToolError(f"PaperMC has no Minecraft {args.mc} build, available: {shown}")
        version = args.mc
    else:
        if not slugs:
            raise ToolError("the PaperMC Fill API returned no versions")
        version = slugs[0]
    with ui.spin(f"listing Paper builds for {version}"):
        payload = http_json(f"{FILL_BASE}/projects/paper/versions/{version}/builds", session)
    builds = payload if isinstance(payload, list) else (payload.get("builds") or [])
    ui.ok(f"{len(builds)} Paper builds for {version}")
    if not builds:
        raise ToolError(f"PaperMC has zero builds for {version} in any channel")
    chosen_channel, chosen = _select_build(builds)
    if chosen_channel != "STABLE":
        ui.warn(f"newest {version} build is channel {chosen_channel}, not production recommended")
    server = (chosen.get("downloads") or {}).get("server:default")
    if not server:
        raise ToolError(f"Paper build {chosen.get('id')} has no server:default download")
    sha = (server.get("checksums") or {}).get("sha256")
    if not sha:
        raise ToolError(f"Paper build {chosen.get('id')} ships no sha256 checksum")
    ui.ok(f"selected build {chosen.get('id')} ({chosen_channel}): {server.get('name')}")
    return version, server["url"], sha


def resolve_downloads(args, session, ui):
    if args.source == "vanilla":
        mc = args.mc or pom_version(args.repo.resolve())
        ui.step(1, f"source: {ui.cyan('vanilla')}, target Minecraft {ui.cyan(mc)}")
        url, sha = resolve_vanilla(args, mc, session, ui)
        return mc, [(url, sha, f"Minecraft {mc} server jar")]
    ui.step(1, f"source: {ui.cyan('PaperMC Fill API')}")
    version, paper_url, paper_sha = resolve_paper(args, session, ui)
    mc = args.mc or version
    pom = pom_version(args.repo.resolve()) if (args.repo.resolve() / "pom.xml").is_file() else None
    if pom and pom != version:
        ui.warn(f"tables now describe MC {version} but the pom targets {pom}, consider --mc {pom}")
    ui.info("the Paper jar is a patcher and carries no game data, the loot tables come from the matching vanilla jar")
    vanilla_url, vanilla_sha = resolve_vanilla_manifest(mc, session, ui)
    return mc, [
        (paper_url, paper_sha, f"Paper {version} build jar (integrity check)"),
        (vanilla_url, vanilla_sha, f"Minecraft {mc} vanilla server jar (data source)"),
    ]


def extract_tables(jar_path, mc, staging, ui):
    with zipfile.ZipFile(jar_path) as bundler:
        bundler_names = {name.replace("\\", "/"): name for name in bundler.namelist()}
        listing = bundler_names.get("META-INF/versions.list")
        if not listing:
            raise ToolError("the downloaded jar has no META-INF/versions.list, it is not a vanilla server bundler")
        lines = [line for line in bundler.read(listing).decode("utf-8").splitlines() if line.strip()]
        if not lines:
            raise ToolError("META-INF/versions.list in the bundler is empty")
        nested_path = lines[-1].split("\t")[-1].strip()
        entry = bundler_names.get(f"META-INF/versions/{nested_path}") or bundler_names.get(nested_path)
        if not entry:
            raise ToolError(f"the bundler lists nested jar {nested_path!r} but it is not in the archive")
        ui.ok(f"nested vanilla jar: {nested_path}")
        with bundler.open(entry) as raw, zipfile.ZipFile(raw) as vanilla:
            inner = {name.replace("\\", "/"): name for name in vanilla.namelist()}
            version_info = json.loads(vanilla.read(inner["version.json"]))
            jar_version = version_info.get("name") or version_info.get("id")
            if jar_version != mc:
                raise ToolError(
                    f"the server jar is Minecraft {jar_version} but the target is {mc}, bump the pom or pass --mc"
                )
            ui.ok(f"version check: {jar_version} == {mc}")
            staging.mkdir(parents=True, exist_ok=True)
            for crop in CROPS:
                key = f"{TABLE_DIR}/{crop}.json"
                actual = inner.get(key)
                if not actual:
                    raise ToolError(f"the vanilla jar is missing {key}, the data layout changed upstream")
                data = vanilla.read(actual)
                if not data:
                    raise ToolError(f"{key} in the vanilla jar is empty")
                (staging / f"{crop}.json").write_bytes(data)
                ui.ok(f"{crop}.json ({len(data)} bytes)")
            version_data = vanilla.read(inner["version.json"])
            (staging / "version.json").write_bytes(version_data)
            ui.ok(f"version.json ({len(version_data)} bytes)")
    wheat = (staging / "wheat.json").read_text(encoding="utf-8")
    if "apply_bonus" not in wheat:
        raise ToolError("wheat.json lost its apply_bonus modifier, the loot table format changed upstream, a human must review the transcription")


def report(repo, ui):
    git = shutil.which("git")
    if git is None:
        ui.warn("git not found on PATH, inspect loot_tables/ manually")
        return
    result = subprocess.run(
        [git, "-C", str(repo), "diff", "--stat", "--", "loot_tables/"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        ui.warn("not a git repository, skipping the diff verdict")
        return
    if result.stdout.strip():
        ui.line()
        ui.line(ui.bold("loot_tables/ changed against the transcription of record:"))
        ui.line(result.stdout.rstrip())
        ui.line()
        ui.line(ui.yellow("NEXT STEPS, all in one change:"))
        ui.line(ui.yellow("  1. re-read the changed JSONs in loot_tables/"))
        ui.line(ui.yellow("  2. update VanillaCropDrops AND VanillaCropDropsTest (bounds + expectations)"))
        ui.line(ui.yellow("  3. mvn package, then commit code, tests, and loot_tables/ together"))
    else:
        ui.line()
        ui.ok("loot_tables/ already matches the downloaded jar, nothing to do")


def parse_args(argv):
    kwargs = dict(
        prog="sync_loot_tables.py",
        description="Re-extract loot_tables/ for ReplenishPlusPlus from Mojang's vanilla server jar, or from PaperMC via the Fill API. Requires rich and requests (tools/requirements.txt).",
        epilog="examples:\n"
               "  python tools/sync_loot_tables.py\n"
               "  python tools/sync_loot_tables.py --mc 26.4\n"
               "  python tools/sync_loot_tables.py --source paper --mc 26.3\n"
               "  python tools/sync_loot_tables.py --url https://piston-data.mojang.com/v1/objects/<sha1>/server.jar\n"
               "Vanilla downloads verify against the sha1 from Mojang's manifest (or the URL tail with --url), Paper against the Fill API sha256.\n"
               "Loot tables are vanilla data: with --source paper the Paper build is still downloaded and verified, but the tables are extracted from the matching vanilla jar.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    try:
        parser = argparse.ArgumentParser(**kwargs, suggest_on_error=True)
    except TypeError:
        parser = argparse.ArgumentParser(**kwargs)
    parser.add_argument("--mc", help="target Minecraft version (default: pom.xml mc.version for vanilla, newest for paper)")
    parser.add_argument("--source", choices=("vanilla", "paper"), default="vanilla", help="jar source (default: vanilla)")
    parser.add_argument("--url", help="direct vanilla server.jar URL, skips the manifest")
    parser.add_argument("--sha", help="expected digest for --url, sha1 (40 hex) or sha256 (64 hex), defaults to the URL tail")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent, help="repository root (default: auto-detected)")
    parser.add_argument("--user-agent", default=os.environ.get("RPP_SYNC_USER_AGENT") or DEFAULT_UA,
                        help="User-Agent for API calls, must identify your software with a contact, env RPP_SYNC_USER_AGENT (PaperMC rejects generic agents)")
    parser.add_argument("--debug", action="store_true", help="print a traceback on failure")
    return parser.parse_args(argv)


def main(argv):
    ui = Ui()
    args = parse_args(argv)
    repo = args.repo.resolve()
    user_agent = args.user_agent.strip() or DEFAULT_UA
    session = http_session(user_agent)

    try:
        ui.banner(repo)
        mc, downloads = resolve_downloads(args, session, ui)

        ui.step(2, "downloading and verifying")
        with tempfile.TemporaryDirectory(prefix="rpp-loot-") as scratch:
            paths = download_all(session, downloads, Path(scratch), ui)
            vanilla_path = paths[-1]

            ui.step(3, "extracting the loot tables")
            staging = Path(scratch) / "loot_tables"
            with ui.spin("reading the nested vanilla jar"):
                extract_tables(vanilla_path, mc, staging, ui)

            ui.step(4, "sanity checks")
            for crop in CROPS:
                path = staging / f"{crop}.json"
                if not path.is_file() or path.stat().st_size == 0:
                    raise ToolError(f"{path} is missing or empty after extraction")
            target = repo / "loot_tables"
            if target.exists():
                shutil.rmtree(target)
            shutil.move(str(staging), str(target))
            ui.ok(f"loot_tables/ holds {len(CROPS)} tables + version.json")

        ui.step(5, "verifying against the repository")
        report(repo, ui)
        ui.line()
        ui.line(ui.green(ui.bold("done.")))
        return 0
    except ToolError as error:
        ui.line()
        ui.line(ui.red(escape(f"error: {error}")))
        return 1
    except KeyboardInterrupt:
        ui.line()
        ui.line(ui.yellow("interrupted, nothing half-written"))
        return 130
    except Exception as error:
        ui.line()
        if args.debug:
            traceback.print_exc()
        else:
            ui.line(ui.red(escape(f"error: {type(error).__name__}: {error} (run with --debug for a traceback)")))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
