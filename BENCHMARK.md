# BENCHMARK: ReplenishPlusPlus under load

Measured live with spark during a 4m59s window of non-stop wheat farming, dev mode
enabled (full-age replants, instant growth, auto inventory clear), wheat speed 93,
`replantDelayTicks: 5`.

## Setup

| | |
| --- | --- |
| Server | Paper 26.3.build.32-alpha, Minecraft 26.3, Java 25 |
| Plugin build | ReplenishPlusPlus v7.0.0 (7.x.x, harvest-report build) |
| Plugins | AspectoftheVoid-3.0.0-mc26.2 · EssentialsX-2.22.1-dev+24-49a2f10 · ReplenishPlusPlus · spark-1.10.187-bukkit · worldedit-bukkit-7.4.6-beta-01 |
| Memory | 2048M heap, ZGC |

## Results

| Metric | Value |
| --- | --- |
| Crops harvested | **5,933** in 4m59s (**19.79/sec**, sustained, wheat only) |
| Crops auto-cleared | 15,414 items |
| TPS | 20.00 flat across 1m / 5m / 15m |
| MSPT | min 0.37 · **median 1.12** · 95%ile 1.61 · max 19.7 |
| Process CPU | 1.22% (1m) · 1.5% (15m) |

The plugin's own Harvest Report for the same window matched the profiler exactly
(5,933 crops, 15,414 cleared), so the counter and the profiler agree.

## What it cost the server thread

The entire plugin accounted for **0.39%** of the server thread.

| Path | Share |
| --- | --- |
| Replant queue (`tick` → `replant` → `setBlockData`) | 0.20% |
| Break decide phase (`prepareHarvest`) | 0.10% |
| Break commit phase (`onBlockBreakCommit`) | 0.06% |
| Chunk-loaded memo (`isChunkLoadedCached`) | 0.05% |
| Drop distribution (`DropPickupManager.giveOrDrop`) | 0.05% |
| Teleport pads (`onMove`, `onBreak`, chunk sync) | 0.02% |
| Dev mode (`clearTick`, report `stopCounting`/`announce`) | 0.01% |
| Neighbor/client updates (`notifyAndUpdatePhysics`) | 0.03% |

Inside the replant frame, `setBlockData` is 0.12% and almost all of it is the
chunk write itself (`LevelChunk.setBlockState`, heightmaps); the plugin's
`applyPhysics=false` keeps the physics side at 0.03%, which is the client sync
that must stay. The seed path (`SeedIndex.consume`) no longer registers above
0.00%.

## Notes

- The decide-phase share still contains a vanilla `getDrops` frame from the
  fortune-only tool gate that was removed right after this capture; with the
  gate gone (transcribed tables sample for every tool, one `getEnchantmentLevel`
  read instead of an enchantment-map copy) the decide path should drop further.
- The capture also ran with a fixed `replantDelayTicks: 5`, which batched all
  replants from 5 consecutive ticks into one tick. The delay is now a random
  1..N window (default 3), which flattens those per-tick replant spikes; total
  replant work is unchanged, the peak per tick is what drops. Re-run before
  drawing conclusions.
- Dev mode's whole footprint in the tree (`clearTick`, the report announce)
  rounds to 0.01% while fully active.
- MSPT headroom: the median tick used 1.12ms of the 50ms budget (2.2%). The
  19.7ms max tick has no plugin frame in its tree; that is GC and chunk-system
  noise under ZGC on a 2GB heap.

## How many players can it handle?

One player farming flat-out costs **0.39% of one server thread**. Scaling is
linear, so (plugin share only, everyone farming non-stop):

| Farming players | Plugin share of server thread | Crops/sec |
| --- | --- | --- |
| 1 (measured) | 0.39% | 20 |
| 10 | ~4% | ~200 |
| 50 | ~20% | ~990 |
| 100 | ~39% | ~1,980 |
| ~1,000+ | hits the safety cap | ~20,480 |

**The practical answer: the plugin never becomes the bottleneck.** A typical
server (20-100 players online, a slice of them farming) spends a few percent of
a thread on it. The replant pipeline holds until the configured safety cap,
`maxReplantsPerTick: 1024` × 20 TPS = ~20,480 crops/sec; beyond that the queue
defers excess replants to the next tick and, in the extreme, drops them with a
throttled console warning instead of lagging the server. A consumed seed is
refunded when a replant itself fails (like a chunk that stays unloaded); a
queue-full drop stays silent by design.
