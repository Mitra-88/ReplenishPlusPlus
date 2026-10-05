# BENCHMARK: ReplenishPlusPlus under load

One player farming flat-out for 5 minutes with dev mode on (full-age
replants, instant growth, auto inventory clear), Netherite Hoe (Efficiency V,
Fortune III, Unbreaking III), measured with spark.

Offline profile of this exact run:
[benchmark/default-jvm.sparkprofile](benchmark/default-jvm.sparkprofile)

## Setup

|              |                                                                               |
| ------------ | ----------------------------------------------------------------------------- |
| Server       | Paper 26.3-151, Minecraft 26.3                                                |
| Java         | 25.0.4.1 (Zulu25.36+205-CA, OpenJDK 64-Bit Server VM)                         |
| Plugin build | `ReplenishPlusPlus-7.0.1+build.301-d5a73ce-mc26.3-papermc`                    |
| Plugins      | EssentialsX · spark 1.10.189 · ReplenishPlusPlus                              |
| Machine      | AMD Ryzen 5 5600 (6 cores / 12 threads) · 32 GiB DDR4 · NVMe · Windows 11 Pro |
| JVM flags    | `-Xms2048M -Xmx2048M` (default G1, what most servers run)                     |

## Results

| Metric             | Value                                                     |
| ------------------ | --------------------------------------------------------- |
| Crops harvested    | **5,999** in 4m59s (**20.00/sec**, sustained)             |
| Crops auto-cleared | 25,181 items                                              |
| TPS                | 20.00 · 20.00 · 19.99 (1m / 5m / 15m)                     |
| MSPT               | **median 0.92ms** · 95%ile 1.67 · max 19.0                |

The plugin's own Harvest Report matched the profiler exactly and identified
the tool.

## What it cost

**About 2% of one server thread** for one player farming flat-out, measured
1.98% on the default JVM above. Quote this number: it is what real servers
see, and it is the worst case.

| Where the 2% goes                                                              | Share  |
| ------------------------------------------------------------------------------ | ------ |
| Paper writing the replanted block (heightmap, palette, client block sync, POI) | 1.28%  |
| Replant queue chunk-loaded checks                                              | 0.38%  |
| Dev mode (inventory clear, harvest counter)                                    | 0.18%  |
| The plugin's own code (breaks, drops, seeds, pads)                             | ~0.15% |

The biggest slice is Paper's code, not the plugin's: each replant is one
`setBlockData` call, and the cost is vanilla block writing plus client sync.
The plugin's own code never measured above ~0.2% in any capture, under any
JVM.

On a heavily JIT-tuned JVM the same workload reads as low as **0.16%**, but
that is a per-boot best case, not a number users will see (see below).

## How many players?

Thread shares scale linearly with flat-out farmers:

| Farmers | Thread share | Crops/sec |
| ------- | ------------ | --------- |
| 10      | ~20%         | ~200      |
| 25      | ~50%         | ~500      |
| 50      | ~100%        | ~1,000    |

Treat these as ceilings: nobody sustains 20 crops/sec, players walk, idle,
and wait for growth. TPS stayed at 20.00 and median MSPT under 1ms with one
flat-out farmer. The plugin's own safety valve caps replants at 1,024 per
tick (about 20,480 crops/sec) and drops beyond that with loud throttled
warnings instead of lagging the server. A consumed seed is refunded whenever
a replant itself fails; a queue-full drop stays silent by design.

## Capture conditions

The launch command changes the result, so only compare captures taken under
the same command. Paper's block-write code contains methods the default JVM
never fully JIT-compiles, which is why default setups read about 2%, every
boot, deterministically. A tuned C2/ZGC command can push the read down to
0.16%, but it is a per-boot race between the compiler finishing and the
profiler running: tuned numbers land anywhere between 0.16% and 2%. To
reproduce the fast state, farm for a few minutes before starting the
profiler window, and drop `NmethodSweepActivity=1` from the tuned command to
stop compiled code being flushed after GC cycles.

<details>
<summary>The tuned command</summary>

```
java -Xms2048M -Xmx2048M --add-modules jdk.incubator.vector -Dpaper.preferSparkPlugin=true -XX:+EnableDynamicAgentLoading -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions -XX:+AlwaysActAsServerClassMachine -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseNUMA -XX:NmethodSweepActivity=1 -XX:ReservedCodeCacheSize=400M -XX:NonMethodCodeHeapSize=12M -XX:ProfiledCodeHeapSize=194M -XX:-DontCompileHugeMethods -XX:MaxNodeLimit=240000 -XX:NodeLimitFudgeFactor=8000 -XX:+UseVectorCmov -XX:+PerfDisableSharedMem -XX:+UseFastUnorderedTimeStamps -XX:+UseCriticalJavaThreadPriority -XX:ThreadPriorityPolicy=1 -XX:AllocatePrefetchStyle=3 -XX:+UseZGC -XX:AllocatePrefetchStyle=1 -XX:-ZProactive -jar server.jar --nogui
```

</details>
