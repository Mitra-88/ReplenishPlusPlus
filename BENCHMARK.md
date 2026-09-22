Outdated (im so tired, just wait a little plz!)

# BENCHMARK — ReplenishPlusPlus under load

Measured live with spark during a 5m14s window of non-stop wheat farming, dev
mode fully enabled (full-age replants, instant growth, auto inventory clear).

## Setup

| | |
| --- | --- |
| Server | Paper 26.3.build.32-alpha, Minecraft 26.3, Java 25 |
| Plugin build | `ReplenishPlusPlus-7.0.0+build.214-76908a0-mc26.3-papermc.jar` |
| Plugins | AspectoftheVoid-3.0.0-mc26.2 · EssentialsX-2.22.1-dev+24-49a2f10 · ReplenishPlusPlus · spark-1.10.187-bukkit · worldedit-bukkit-7.4.6-beta-01 |
| Memory | 2048M heap, ZGC |

<details>
<summary>Full startup command</summary>

```
java -Xms2048M -Xmx2048M --add-modules jdk.incubator.vector -Dpaper.preferSparkPlugin=true -XX:+EnableDynamicAgentLoading -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysActAsServerClassMachine -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseNUMA -XX:NmethodSweepActivity=1 -XX:ReservedCodeCacheSize=400M -XX:NonNMethodCodeHeapSize=12M -XX:ProfiledCodeHeapSize=194M -XX:NonProfiledCodeHeapSize=194M -XX:-DontCompileHugeMethods -XX:MaxNodeLimit=240000 -XX:NodeLimitFudgeFactor=8000 -XX:+UseVectorCmov -XX:+PerfDisableSharedMem -XX:+UseFastUnorderedTimeStamps -XX:+UseCriticalJavaThreadPriority -XX:ThreadPriorityPolicy=1 -XX:AllocatePrefetchStyle=3 -XX:+UseZGC -XX:AllocatePrefetchStyle=1 -XX:-ZProactive -jar server.jar --nogui
```

</details>

## Results

| Metric | Value |
| --- | --- |
| Crops harvested | **5,984** in 5m14s (**19.05/sec**, sustained) |
| Crops auto-cleared | 25,243 items (~80/sec) |
| TPS | 20.00 — flat across 1m / 5m / 15m |
| MSPT | min 0.52 · **median 1.29** · 95%ile 1.68 · max 20.9 |
| Process CPU | 0.62% (1m) · 1.02% (15m) |

## What it cost the server thread

The entire plugin accounted for **0.19%** of the server thread.

| Path | Share |
| --- | --- |
| Replant queue (`tick` → `replant` → `setBlockData`) | 0.08% |
| Break decide phase (`prepareHarvest`) | 0.06% |
| Break commit phase (`onBlockBreakCommit`) | 0.04% |
| Seed consumption (`SeedIndex.consume`) | 0.02% |
| Chunk-loaded check (`isChunkLoadedCached`) | 0.02% |
| Drop distribution (`DropPickupManager.giveOrDrop`) | 0.02% |
| Teleport pad walk-on (`PadListener.onMove`) | 0.01% |
| Dev mode (`clearTick`, `onHarvest`, `anyActive`) | **0.00%** |

The big frames inside those numbers — `getDrops` loot tables, chunk writes,
heightmap updates — are work the server does for *any* block break. The
plugin's own logic rides on top at fractions of a hundredth of a percent.

Dev mode, quoted straight from the capture:

```
dev.replenishplusplus.dev.DevModeManager.anyActive()   0.00%
dev.replenishplusplus.dev.DevModeManager.onHarvest()   0.00%
dev.replenishplusplus.dev.DevModeManager.clearTick()   0.00%
```

Full-age replants, instant growth, the auto-clear task, and the spark harvest
counter all running at once — and together they round to zero inside the
profiler that was running *because of* them.

## How many players can it handle?

One player farming flat-out costs **0.19% of one server thread**. Scaling is
linear, so (plugin share only, everyone farming non-stop):

| Farming players | Plugin share of server thread | Crops/sec |
| --- | --- | --- |
| 1 (measured) | 0.19% | 19 |
| 10 | ~2% | ~190 |
| 50 | ~10% | ~950 |
| 100 | ~19% | ~1,900 |
| ~1,000+ | hits the safety cap | ~20,480 |

**The practical answer: the plugin never becomes the bottleneck.** A typical
server (20–100 players online, a slice of them farming) spends under 1–5% of a
thread on it. The replant pipeline itself holds until the configured safety
cap — `maxReplantsPerTick: 1024` × 20 TPS = ~20,480 crops/sec — and when
something ever exceeds it, the queue defers excess replants to the next tick
and, in the extreme, drops them with a throttled console warning instead of
lagging the server. A consumed seed is refunded when a replant itself fails
(like a chunk that stays unloaded) — a queue-full drop stays silent by design.

**MSPT headroom:** the median tick used 1.29ms of the 50ms budget (2.6%). The
single 20.9ms max tick has no plugin frame anywhere in its tree that's GC
and chunk-system noise under ZGC on a 2GB heap, not the plugin.
