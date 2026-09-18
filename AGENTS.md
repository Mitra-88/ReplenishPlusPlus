# AGENTS.md — ReplenishPlusPlus

## What this is

A **server-side Paper plugin** (plugin name `ReplenishPlusPlus`, package `dev.replenishplusplus`) for **Minecraft 26.3** (`api-version: "26.3"`), Java **25**, built with **Maven** (no Gradle). Auto-replant for crops, modeled on Hypixel's Replenish: a player breaks a mature crop, the plugin replants it. Only dependency is `paper-api` (provided scope, pinned to `26.3.build.16-alpha` — see gotchas for why there is no version range). License: **AGPL-3.0**. Repo: `github.com/Mitra-88/ReplenishPlusPlus`.

**Design contract:** the plugin only acts for players who opted in (`/rpp toggle`, default on) and otherwise behaves exactly like vanilla — sneak bypass, wrong tool, missing seed, disabled crop, or global-off all fall through to a normal break. Drops always come from vanilla `block.getDrops()` (Fortune applies); nothing is invented. A consumed seed is **always refunded** (dropped at the block, or at the player when its chunk is unloaded) when a queued replant fails — preserve that refund invariant in any queue change, and keep the lag-protection caps (per-tick limit, queue limit, drop instead of throw).

## Build & run

```bash
mvn package   # compile + shade; this is what CI runs, on Zulu 25
```

- No test sourceset exists; `mvn package` is the only verification gate — run it before finishing any change.
- Jar output: `target/ReplenishPlusPlus-<version>.jar` (shade plugin, `minimizeJar` on).
- The version lives only in `pom.xml` `<version>`; `paper-plugin.yml` picks it up via Maven resource filtering (`${project.version}`) — all of `src/main/resources` is filtered.
- CI (`.github/workflows/build.yml`) builds on push to **`7.x.x`** only and uploads the jar as an artifact. Current dev branch: `7.x.x`; `6.1.x` is the old main line.
- Targets Paper/Purpur on MC 26.3. **No Folia support** — no `folia-supported: true` in `paper-plugin.yml`, no region-scheduler code.

## How a harvest works

1. `ReplenishPlusPlusListener` is two-phase over `BlockBreakEvent`: the **HIGHEST** handler (`ignoreCancelled = true`) decides — it bails out for non-survival game modes, suppressed drops (`!event.isDropItems()`, i.e. another drop plugin owns the break), global-off, player-toggle-off, sneaking (if `sneakToBypass`), unknown/disabled crops, wrong tool, missing anchor, or a non-`Ageable` block — then suppresses vanilla drops and stashes a `HarvestPlan`. The **MONITOR** handler commits — consumes the seed, enqueues the replant, distributes drops — only for a break that was not cancelled, so a late-cancelled break pays out nothing (that's the dupe protection; keep the split). Both handlers are try/catch-wrapped — keep that.
2. Mature: drops computed via `block.getDrops(tool, player)`; if `requirePlayerSeed`, the decide phase peeks `SeedIndex.hasSeed` (abort with a "need seed" message if absent) and the commit phase consumes through `SeedIndex.consume`; `event.setDropItems(false)` — vanilla drop logic is fully bypassed; drops go straight to the inventory (`DropPickupManager.giveOrDrop`) or drop naturally. Immature: replanted at the same age, no seed, no drops.
3. The replant is **scheduled, not immediate**: `plugin.enqueueReplant(...)` → `ReplantQueue` (a timing wheel); N ticks later (default 1) the main-thread tick task replants with precomputed `BlockData` from `AgeMetaRegistry`. Failures play the `replant-failed` sound and refund the seed.
4. Seed-cache invalidation: inventory click/drag, hand swap, item pickup/drop, and seed right-clicks invalidate `SeedIndex` (50 ms per-player cooldown); player notifications have a 2 s cooldown.

Cocoa is special-cased everywhere (`instanceof CocoaCropInfo`): anchor = adjacent jungle log, facing packed into the queue entry, resolved original-facing → player-facing → any adjacent jungle. Nether wart anchors to soul sand; all other crops to farmland.

## Codebase tour

All code under `src/main/java/dev/replenishplusplus/`, flat two-level packages:

**Root** — `ReplenishPlusPlus` (`JavaPlugin` main). Owns the `AtomicReference<ConfigCache>`, `AgeMetaRegistry`, `ReplantQueue`, `PlayerToggleManager`, `UpdateChecker`. `reloadLocalConfig()` is the single reload path: builds a fresh `ConfigCache` via `ConfigCache.from(getConfig(), issues)`, logs every issue as a `[Config]` warning, swaps the cache in, rebuilds the queue and flushes the old one, and returns the issue list (the `/rpp reload` output shows it to admins). `onDisable()` flushes the queue — `flush()` is the shutdown (cancels the task, drains every pending entry, then returns the count replanted).

**`listener/`** — `ReplenishPlusPlusListener`: the two-phase harvest flow (HIGHEST decide → MONITOR commit, see "How a harvest works"). `SeedCacheInvalidationListener`: every inventory-movement listener that invalidates `SeedIndex` (50 ms throttle, quit eviction) — new inventory-mutation invalidations go here, not into the harvest listener.

**`queue/`** — performance-critical (recent perf work lives here); don't restructure casually.
- `ReplantQueue` — 8192-slot timing wheel (`1 << 13`) over a primitive-array pool (parallel `World[]/int[]/Material[]/UUID[]` arrays + free list, primed at 1024, grown up to `maxReplantsQueued`). Per-entry metadata is bit-packed into one int: age (8 bits) / cocoa facing (2) / retry count (8) / seed-consumed flag (1). All public methods `synchronized`; `flush()` is the shutdown path. Delays above 8191 ticks are truncated with a warning; unloaded chunks retry up to 20 times, then refund. `replant(index)` is self-guarding — its own catch refunds the seed, callers never wrap it — and a shared chunk-loaded memo (`isChunkLoadedCached`) serves both `tick()` and `flush()`.
- `QueueStats` — record for `/rpp debug queue`.

**`crop/`** — `CropType` enum is the single source of truth for supported crops (material, seed, required tool, display name, plus the `fromMaterial`/`fromName` lookups; no crop behavior lives on it). Adding a crop = new enum constant + a `crops.<lowercase-name>` entry in `config.yml`/README (the key derives from the enum name — `ConfigCache.readCrops` reads it automatically, lenient on case and spaces); everything else derives automatically. `AgeMetaRegistry` precomputes every age (cocoa: age × facing) `BlockData` at enable-time so replanting never mutates shared state. `CropInfo` is a sealed interface over `SimpleCropInfo` (farmland anchor, or soul sand for nether wart) and `CocoaCropInfo`, and it owns attachment logic: `plantsOn(Material)` + `validNeighborFaces()` are the single source of where a crop can grow — the listener's anchor check and the queue's replant checks both go through them, so never duplicate that knowledge. `CocoaCropInfo` is the single cocoa authority: `FACES` + `face()`/`faceOrdinal()` (the packed facing order the queue's bit layout depends on — never re-create a parallel copy) plus the jungle-log anchoring. `HarvestTool` matches via vanilla item tags (`Tag.ITEMS_HOES` / `Tag.ITEMS_AXES`).

**`config/`**
- `ConfigCache` — immutable record snapshot of all settings (incl. `checkUpdates`); swap via `AtomicReference`, never mutate (`withEnabled` for the global toggle). Crop toggles are an unmodifiable `Set<CropType>` of *disabled* crops (not in the set = enabled). `from(FileConfiguration, List<String> issues)` reads every key through `ConfigReader`: absent keys fall back silently, but present-yet-invalid values (non-bool, non-number, unknown message style / crop key / sound name, out-of-range volume/pitch, below-minimum caps, delay past `ReplantQueue.MAX_DELAY_TICKS`) are appended to `issues` and fall back too — never silently default a broken value.
- `ConfigReader` — static permissive readers (`boolValue`/`intValue`/`floatValue`): they parse the string form of any scalar, fall back to the default when the key is absent, and report present-yet-invalid values into the issue list.
- `Messages` — shared `MINI_MESSAGE` instance (every class deserializes through it), MiniMessage constants (`PREFIX`, `ARROW`, `DOT`, `LINE`), `prefixed(message)` for the standard chat-line shape, and default message templates.
- `MessageStyle` — CHAT / ACTION_BAR / NONE enum; `send(player, component)` is the single notification-routing point (used by the listener and `DropPickupManager`); `parse(String)` returns null for unknown values so the config layer can report and default.
- `SoundEffect` — record clamping volume 0–1 and pitch 0.5–2.0 (range constants are public for config validation); `play()` is the only sound path.
- `SoundRegistryMapper` — lenient user sound names ("entity.item.pickup", "ENTITY_ITEM_PICKUP", …) → `Optional<Sound> lookup(String)` (empty on null/blank/unknown so callers can report the miss); separator/case-insensitive canonicalization via `Registry.SOUND_EVENT`; lazily loaded once. `keyName(Sound)` renders a sound's registry key for display — the only supported key accessor (see gotchas).
- `PlayerToggleManager` — per-player toggle in the player `PersistentDataContainer` (`replenishplusplus:enabled`), one-time migration from legacy `players.yml` (still mirrored, saved async).

**`util/`** — `SeedIndex` (static per-player seed-slot cache: 36 storage slots, offhand = -2, none = -1; plain `HashMap` — all access is main-thread; `hasSeed` is the non-mutating peek for the harvest's decide phase), `DropPickupManager` (`giveOrDrop(player, block, drops, ConfigCache)`: inventory-first, leftovers drop at the crop center — the center `Location` is computed lazily on first drop), `WarningThrottle` (3 s per-category log suppression with suppressed-count reporting; messages are `Supplier`s materialized only when actually logged, so suppressed warnings build no strings; the `Throwable` overload logs the stack trace on the first occurrence), `LocationUtil` (`describe`; centered drop points use Paper's `Location#toCenterLocation()`), `TextUtil` (enum-name prettifier).

**`update/`** — `UpdateChecker` (async GitHub releases check against the single `REPO` constant exposing `RELEASES_URL`, volatile state, numeric version compare, one-shot `onCheckCompleted` callbacks fired on the main thread on success only), `UpdateNotificationListener` (ops get a notice on join if the check already finished, otherwise the moment it completes).

**`command/`** — `ReplenishPlusPlusCommand`: Paper Brigadier registered via `LifecycleEvents.COMMANDS`; root `/replenishplusplus` with `/rpp` and `/replenish` redirects; every subcommand goes through the `execute(source, permission, action)` gate.

**Resources** — `paper-plugin.yml` (name, `api-version: "26.3"`, all permissions declared here), `config.yml` (heavily commented default config). No lang files; all strings are hardcoded English.

## Rules that matter for edits

- **Threading:** everything on the main thread except `UpdateChecker`'s HTTP call and the async `players.yml` save. Keep `ReplantQueue` `synchronized` (reload/flush can race enqueue). Never touch blocks or inventories off-thread. `SeedIndex`, the listeners' cooldown maps, and `PlayerToggleManager`'s map are plain `HashMap` because all their access is main-thread — if you ever call them async, restore concurrency first.
- **Config is immutable:** build a new `ConfigCache` and swap it in. New config keys must land in: `config.yml` (with its comment), `ConfigCache` (field + a `from()` read through `ConfigReader` with issue reporting), the README config section, and the `/rpp reload` output. Present-yet-invalid values must be reported via the issues list — never silently defaulted.
- **Player-facing text is MiniMessage** with the house palette: `<dark_gray>` structure/punctuation, `<yellow>` accents, `<gray>` body; prefix with `Messages.PREFIX`. Config message templates use legacy `{tag}` placeholders that `normalizeTemplate` (listener) converts to MiniMessage `<tag>` placeholders — keep that translation when adding placeholders.
- **Sounds** via `SoundEffect.play()` only — it no longer checks online-ness, so callers must ensure an online player; user-supplied names resolve via `SoundRegistryMapper.lookup` (fallback applied, unknown names reported as config issues). Sound key strings for messages/debug output come from `SoundRegistryMapper.keyName` — never `sound.key()`/`sound.getKey()` (deprecated for removal).
- **Logs** via `plugin.getLogger()`; anything that can fire per-crop/per-tick must go through `WarningThrottle.log(...)` (add a `Category` if needed).
- **Java 25 idioms are in use:** records, sealed interfaces, pattern switches, text blocks, unnamed `_` variables. Exhaustive switches (`GameMode`, `MessageStyle`) intentionally have no `default` — new enum constants must update them.
- **Permissions** are declared in `paper-plugin.yml` (including the `replenishplusplus.*` parent with its children) and checked in `execute()`; the README permission table must stay in sync.
- **Keep this file current:** any change that alters behavior, architecture, config keys, invariants, or file responsibilities must update the relevant `AGENTS.md` section in the same change.

## Gotchas & quirks

- **Registry keys come from the registry, not the instance.** `Sound#key()`/`Sound#getKey()` — and the same pair on `Art`, `Dialog`, `PatternType`, `Structure`, `TrimMaterial`/`TrimPattern`, `MusicInstrument` — are `@Deprecated(since = "1.20.5", forRemoval = true)`. Take keys via `Registry.SOUND_EVENT.getKey(sound)` (nullable — sounds can exist without a key) or `getKeyOrThrow`, and render them through `SoundRegistryMapper.keyName`. The generic `Keyed` accessors are not deprecated; only these per-type redeclarations are.
- **paper-api is pinned, not a range.** A range like `[26.3.build,)` silently resolved to the pre-release `26.3-pre-2.build.0-alpha` (Maven sorts `pre-2` above the `build.N` line), whose transitional API mismatches the final 26.3 docs. `Registry.SOUNDS` was also renamed `SOUND_EVENT` on the final line. Bump the pin by hand together with the `mc26.3` token in the CI artifact name (`build.yml`) and the vendored javadoc folder (see below); the plugin version in the artifact name is derived from the pom at build time, so version bumps need no edit there.
- `maxReplantsPerTick`/`maxReplantsQueued` clamp to ≥ 256 and `replantDelayTicks` to ≥ 1 — below-minimum values are reported as config issues at load and clamped; delays above `ReplantQueue.MAX_DELAY_TICKS` (8191) are capped by the wheel (`DELAY_TRUNCATION` warning).
- Queue-full (backpressure) drops replants with a throttled warning by design (lag protection) — and it is the one case where a consumed seed is NOT refunded, deliberately: refunding would spawn thousands of item entities in exactly the lag scenario the cap exists for. Don't make it throw or grow unbounded.
- Mature harvests always replant at age 0 and consume a seed; immature harvests replant at the same age, never consume one, and suppress vanilla drops — restoring the crop while also paying its seed drop would let players mint seeds by re-breaking. The seed logic depends on that split.
- The two-phase listener is the dupe protection: a break that another plugin cancels after our HIGHEST handler (legal in Bukkit) must pay out nothing, and breaks where another plugin already set `dropItems = false` are left alone entirely. Never move seed consumption, drop payout, or replant scheduling back into the decide phase.
- `SeedIndex` caches slot numbers — any new inventory-mutation path must trigger `invalidateWithCooldown`, or the cache goes stale.
- The update check uses a browser User-Agent against `api.github.com` (rate-limit workaround) — leave the header alone.

## Where to look things up

Lookup order for API questions: the vendored docs below first, then the live docs, and the local Maven repo jars only as a fallback when neither covers something.

**Dependency → reference map**

| Dependency (pom) | API reference / docs | Source / javadoc jars |
| --- | --- | --- |
| `io.papermc.paper:paper-api` `26.3.build.16-alpha` (provided, pinned) | **Vendored javadocs**: `docs-for-agents/paper-api 26.3.build.16-alpha-API/jd.papermc.io/paper/26.3/` (matches the pin; `deprecated-list.html` has every deprecation + its replacement). Topic guides: `docs-for-agents/docs.papermc.io_*.md`. Live: https://jd.papermc.io/paper/ | `.m2` `io/papermc/paper/paper-api/26.3.build.16-alpha/paper-api-*-sources.jar` / `-javadoc.jar` |
| `com.mojang:brigadier` `1.3.10` (command API) | Vendored guide: `docs-for-agents/docs.papermc.io_paper_dev_api_command-api.md`; live: https://docs.papermc.io/paper/dev/command-api/ | `.m2` `com/mojang/brigadier/1.3.10/brigadier-1.3.10-sources.jar` |
| `net.kyori:adventure-*` `5.2.0` (MiniMessage, keys, text) | MiniMessage format: https://docs.advntr.dev/minimessage/format.html (also linked from `config.yml`) | `.m2` `net/kyori/adventure-<module>/5.2.0/adventure-<module>-5.2.0-sources.jar` / `-javadoc.jar` |
| `net.md-5:bungeecord-chat` (transitive of paper-api) | none — never use it directly | `.m2` `net/md-5/bungeecord-chat/…` |

- **The vendored javadocs are HTML — convert before reading** with **`mdka`** (HTML → Markdown CLI): `"/c/Program Files/mdka-Windows-x64-2.2.3/mdka.exe" --drop-shell -o /tmp/jd <page.html>` writes `<Basename>.md` into `/tmp/jd`; read that instead of grepping raw HTML. `--drop-shell` strips the nav/header/footer/aside chrome; bulk conversion (`mdka -o <dir> *.html`) requires `-o`. Like `rtk`, it is on the persisted user PATH but a not-yet-restarted session may not see it — use the absolute path.
- The local Maven repo is `C:\Users\Mitra\.m2\repository`, laid out as `<group path>/<artifact>/<version>/<artifact>-<version>-sources.jar` (jars sit directly in the version folder, no hash subfolder) — fallback only.
- `README.md` — user-facing behavior, commands, permissions, full default config; keep it in sync with `config.yml` and `paper-plugin.yml`.
- The `config.yml` comments are the authoritative semantics for every setting.

## RTK

RTK (`rtk`) is installed and available on PATH. Use RTK commands whenever an equivalent exists to reduce unnecessary CLI output and context usage.

### Rules

- Prefer `rtk` over the normal command when RTK provides an equivalent.
- Use the normal command when RTK does not provide an appropriate equivalent.
- Do not use RTK if the full/raw output is required for the task.
- Do not run both RTK and the normal command just to compare their output.
- RTK only filters/condenses output; it does not change the underlying command's intended behavior.
- If RTK hides information needed to continue, use `rtk recall` when applicable or run the normal command.

### Common replacements

- `ls` → `rtk ls`
- `tree` → `rtk tree`
- `cat` / file reading → `rtk read`
- `find` → `rtk find`
- `grep` → `rtk grep`
- `rg` → `rtk rg`
- `git ...` → `rtk git ...`
- `gh ...` → `rtk gh ...`
- `curl ...` → `rtk curl ...`
- `wget ...` → `rtk wget ...`

Maven has no RTK equivalent — run `mvn` normally.

### Useful specialized commands

- Use `rtk test` when only test failures/results are needed.
- Use `rtk err` when only errors and warnings are relevant.
- Use `rtk diff` for a compact diff when the full diff is unnecessary.
- Use `rtk json` when inspecting JSON output.
- Use `rtk summary` or `rtk smart` when a concise command summary is useful.

Do not blindly replace every command with RTK; if RTK's filtering could hide information needed to continue, run the normal command.

### On this machine

- ZCode's shell is **Git Bash** (win32), not PowerShell — invoke `rtk` normally, never `.\rtk.exe`.
- Installed at `C:\Program Files\rtk-x86_64-pc-windows-msvc\rtk.exe` and on the persisted user PATH.
- Shell env vars don't persist between Bash calls, so `export PATH=...` won't stick. If plain `rtk` isn't found (e.g. ZCode was launched before the PATH entry was added — inherited env is stale until ZCode restarts), call it by absolute path `"/c/Program Files/rtk-x86_64-pc-windows-msvc/rtk.exe"` or fall back to the normal command.
