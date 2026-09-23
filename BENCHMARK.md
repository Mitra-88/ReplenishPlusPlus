# BENCHMARK: ReplenishPlusPlus under load

Measured live with spark during a 5min window of non-stop wheat farming, dev mode
enabled (full-age replants, instant growth, auto inventory clear)
using a Hoe (Efficiency V, Fortune III, Unbreaking III).

## Setup

|              |                                                                                                                                               |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Server       | Paper 26.3-35-main@dd9d103 (2026-09-22T19:19:23Z), Minecraft 26.3, Java 25                                                                    |
| Plugin build | `ReplenishPlusPlus-7.0.0+build.240-de522f8-mc26.3-papermc`                                                                                    |
| Plugins      | AspectoftheVoid-3.0.0-mc26.2 · EssentialsX-2.22.1-dev+24-49a2f10 · ReplenishPlusPlus · spark-1.10.187-bukkit · worldedit-bukkit-7.4.6-beta-01 |
| Memory       | 2048M heap, ZGC                                                                                                                               |

<details>
<summary>Full startup command</summary>

```
java -Xms2048M -Xmx2048M --add-modules jdk.incubator.vector -Dpaper.preferSparkPlugin=true -XX:+EnableDynamicAgentLoading -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysActAsServerClassMachine -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseNUMA -XX:NmethodSweepActivity=1 -XX:ReservedCodeCacheSize=400M -XX:NonMethodCodeHeapSize=12M -XX:ProfiledCodeHeapSize=194M -XX:-DontCompileHugeMethods -XX:MaxNodeLimit=240000 -XX:NodeLimitFudgeFactor=8000 -XX:+UseVectorCmov -XX:+PerfDisableSharedMem -XX:+UseFastUnorderedTimeStamps -XX:+UseCriticalJavaThreadPriority -XX:ThreadPriorityPolicy=1 -XX:AllocatePrefetchStyle=3 -XX:+UseZGC -XX:AllocatePrefetchStyle=1 -XX:-ZProactive -jar server.jar --nogui
```

</details>

## Results

| Metric             | Value                                                     |
| ------------------ | --------------------------------------------------------- |
| Crops harvested    | **5,993** in 4m59s (**19.99/sec**, sustained, wheat only) |
| Crops auto-cleared | 25,079 items                                              |
| TPS                | 20.00 (1m) · 20.00 (5m) · 19.99 (15m)                     |
| MSPT               | min 8.48 · **median 9.62** · 95%ile 11.4 · max 26.8       |
| Process CPU        | 2.53% (1m) · 2.67% (15m)                                  |

The plugin's own Harvest Report matched the profiler exactly (5,993 crops,
25,079 cleared) and identified the tool: Netherite Hoe (Efficiency V, Fortune
III, Unbreaking III).

## What it cost the server thread

The entire plugin accounted for **0.16%** of the server thread.

| Path                                                | Share |
| --------------------------------------------------- | ----- |
| Break commit phase (`onBlockBreakCommit`)           | 0.07% |
| Replant queue (`tick` → `replant` → `setBlockData`) | 0.05% |
| Break decide phase (`prepareHarvest`)               | 0.02% |
| Neighbor/client updates (`notifyAndUpdatePhysics`)  | 0.01% |
| Chunk-loaded memo (`isChunkLoadedCached`)           | 0.01% |
| Teleport pads (`onMove`)                            | 0.01% |
| Dev mode (`clearTick`, report)                      | 0.01% |
| Seed path (`hasSeed`, `consume`)                    | 0.00% |

Inside the commit frame, the drop handoff is most of it (`addItem` 0.04%) and
the pickup sound packet is another 0.01%. The decide phase is where the
transcribed tables show up: `VanillaCropDrops.counts` and `binomialBonus` are
in the tree at 0.00%, the vanilla loot-table + enchantment-map machinery is
gone, and the only tool read left is `getEnchantmentLevel` at 0.00%.

## How many players can it handle?

One player farming flat-out costs **0.16% of one server thread**, about
20 crops/sec. Scaling is linear while everyone farms non-stop, which never
happens on a real server, so treat these as ceilings:

| Concurrent farmers | Plugin share of one thread | Crops/sec | Zone      |
| ------------------ | -------------------------- | --------- | --------- |
| ~30                | ~5%                        | ~600      | Safe      |
| ~75                | ~12%                       | ~1,500    | Moderate  |
| ~150               | ~24%                       | ~3,000    | Caution   |
| ~1,000+            | safety cap engaged         | ~20,480   | The valve |

Notes on the zones:

- **Safe** means the plugin is a rounding error even if every player farms
  flat-out with zero breaks.
- **Moderate** means worth a spark look on your hardware, still far from a
  problem at 20 TPS.
- **Caution** is where other server costs (chunk ticks, entities, network)
  will almost certainly dominate before the plugin does.
- The hard ceiling is the plugin's own safety valve: `maxReplantsPerTick`
  1024 × 20 TPS ≈ 20,480 crops/sec. Past it, replants are deferred and
  eventually dropped with loud throttled warnings instead of lagging the
  server. A consumed seed is refunded whenever a replant itself fails; a
  queue-full drop stays silent by design.
