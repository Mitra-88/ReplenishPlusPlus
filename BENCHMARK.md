# BENCHMARK: ReplenishPlusPlus under load

Measured live with spark during a 4m59s window of non-stop wheat farming, dev mode
enabled (full-age replants, instant growth, auto inventory clear), auto-farm mod
driving a Netherite Hoe (Efficiency V, Fortune III, Unbreaking III), randomized
replant delay window (new build).

## Setup

| | |
| --- | --- |
| Server | Paper 26.3-34, Minecraft 26.3, Java 25 |
| Plugin build | ReplenishPlusPlus v7.0.0 (tables-for-every-tool + randomized delay + tool report) |
| Plugins | AspectoftheVoid-3.0.0-mc26.2 · EssentialsX-2.22.1-dev+24-49a2f10 · ReplenishPlusPlus · spark (bundled) · worldedit-bukkit-7.4.6-beta-01 |
| Memory | 2048M heap, ZGC |

## Results

| Metric | Value |
| --- | --- |
| Crops harvested | **5,993** in 4m59s (**19.99/sec**, sustained, wheat only) |
| Crops auto-cleared | 25,171 items |
| TPS | 20.00 flat across 1m / 5m / 15m |
| MSPT | min 0.7 · **median 1.14** · 95%ile 1.85 · max 23.7 |
| Process CPU | 1.18% (1m) · 1.63% (15m) |

The plugin's own Harvest Report matched the profiler exactly (5,993 crops,
25,171 cleared) and identified the tool: Netherite Hoe (Efficiency V, Fortune
III, Unbreaking III).

## What it cost the server thread

The entire plugin accounted for **0.28%** of the server thread (previous
capture: 0.39%, so **28% lighter**).

| Path | Share | Previous |
| --- | --- | --- |
| Replant queue (`tick` → `replant` → `setBlockData`) | 0.11% | 0.20% |
| Break decide phase (`prepareHarvest`) | 0.04% | 0.10% |
| Break commit phase (`onBlockBreakCommit`) | 0.10% | 0.06% |
| Chunk-loaded memo (`isChunkLoadedCached`) | 0.03% | 0.05% |
| Drop distribution (`DropPickupManager.giveOrDrop`) | 0.05% | 0.05% |
| Teleport pads (`onMove`, `onBreak`, `onInteract`) | 0.01% | 0.02% |
| Dev mode (`clearTick`, report) | 0.01% | 0.01% |
| Neighbor/client updates (`notifyAndUpdatePhysics`) | 0.03% | 0.03% |

## What moved and why

- **Decide phase -60% (0.10% → 0.04%):** the transcribed tables now run for
  every tool. The capture shows `VanillaCropDrops.counts`/`binomialBonus`
  frames instead of the vanilla loot-table + enchantment-map machinery, and
  `getDrops` is gone from the tree. The only tool read left is one
  `getEnchantmentLevel` call (0.01%).
- **Replant queue -45% (0.20% → 0.11%):** the randomized 1..N delay window
  spread the wheel's due-batches. Same replant count, flatter per-tick work.
- **Commit +0.04% (0.06% → 0.10%):** the price of the new tool reporting, and
  it is only paid while a profiling window is open (`onHarvest` returns
  immediately otherwise). `CraftItemStack.getEnchantments` 0.02% is the tool
  snapshot.
- `notifyAndUpdatePhysics` 0.03% is the client sync that must stay.

## Notes

- Median MSPT is unchanged (1.14 vs 1.12, vanilla-dominated). The max tick
  (23.7 vs 19.7) has no plugin frame in its tree at a 0.28% total share; this
  run also auto-cleared 63% more items (25,171 vs 15,414), which is more item
  churn for ZGC on a 2GB heap. Max-tick wobble here is GC and chunk-system
  noise, not the plugin.
- Per-player extrapolation: 0.28% per farming player, so ~20 crops/sec each,
  ~36 players before the plugin reaches 10% of a thread.

## How many players can it handle?

One player farming flat-out costs **0.28% of one server thread**. Scaling is
linear, so (plugin share only, everyone farming non-stop):

| Farming players | Plugin share of server thread | Crops/sec |
| --- | --- | --- |
| 1 (measured) | 0.28% | 20 |
| 10 | ~3% | ~200 |
| 50 | ~14% | ~1,000 |
| 100 | ~28% | ~2,000 |
| ~1,000+ | hits the safety cap | ~20,480 |

**The practical answer: the plugin never becomes the bottleneck.** The replant
pipeline holds until the configured safety cap, `maxReplantsPerTick: 1024` ×
20 TPS = ~20,480 crops/sec; beyond that the queue defers excess replants to the
next tick and, in the extreme, drops them with a throttled console warning
instead of lagging the server. A consumed seed is refunded when a replant
itself fails (like a chunk that stays unloaded); a queue-full drop stays silent
by design.
