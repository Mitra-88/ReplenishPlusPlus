# DEV_MODE: the /rpp dev testing sandbox

Dev mode is the op-only testing mode. It's deliberately invisible: no menu entry,
no help screen, nothing in `/rpp status`, nothing in the `/rpp reload` output, no
section in `config.yml`, nothing in the README, nothing in the permission list.
This file is the only documentation, it's not shipped to anyone.

## The basics

- `/rpp dev` toggles it just for you (permission `replenishplusplus.dev`, op only).
- Per-player and session-only: it lives in memory, so relogging turns it off.
- With nobody in dev mode, the plugin's entire dev cost is two `isEmpty()` checks
  per harvest and one boolean per ice event. It's kept outside the harvest
  pipeline on purpose.
- All dev messages are hardcoded (console + dev players), they're not in
  `en_us.yml` because dev mode isn't player-facing.

## The six features

| Config key | Default | What it does |
| --- | --- | --- |
| `fast-water` | on | Water behaves like dry land. Implemented as a transient +1.0 `WATER_MOVEMENT_EFFICIENCY` attribute (≈ Depth Strider III) applied on enable, removed on disable/shutdown; a relog kills it by itself. |
| `no-ice` | on | Ice can't form anywhere on the server while anyone has dev mode on (also blocks Frost Walker, same event family, covered for free). This one is world-level by nature, so it affects everyone. |
| `inventory-clear` | on | Stops your inventory from ever filling while farming. Every second: if you have 4+ free slots, nothing happens; otherwise everything in the 36 storage slots is deleted except hoes/axes and 64 units *total* of the current crop's seed (excess seed stacks get trimmed). The harvested crops themselves are deleted too, only replant material survives. Armor and offhand untouched. Deletes quietly, never drops. |
| `full-age-replant` | on | Crops you break replant fully grown instead of fresh, mature and immature alike, so a farm that was replanted young before you enabled dev mode normalizes instantly. Seed rules untouched: immature harvests still give no drops and eat no seed. |
| `harvest-counter` | on | `/spark profiler` windows count your harvests and print a report when the profiler stops (workflow below). |
| `fast-growth` | on | Crops planted by anyone grow instantly, and enabling dev mode fully grows the 25×25×5 area around you once. There is no periodic growth work at all, replants are already full-age and planted crops are instant, so nothing ever waits to grow. Crops grown young by other means (farmer villagers) stay vanilla until you re-toggle dev mode. |

## Configuring it

No `dev:` section ships in `config.yml` on purpose, admins aren't meant to see
it. The keys still work if you hand-add them:

```yaml
dev:
  inventory-clear: false
```

then run `/rpp reload`. Every key defaults to true; only keys you actually list
are read.

## Spark counter workflow

1. `/rpp dev`, you get the ENABLED message.
2. `/spark profiler start`, the plugin snapshots your session totals and tells
   you "Harvest tracking started, you had N crops before this window".
3. Farm.
4. `/spark profiler stop`, gradient report to the console and every dev player:
   window duration, crops before, harvested during the window, blocks per second,
   and crops the auto-clear deleted in that window.

Bare `/spark profiler` and flag forms like `/spark profiler --memory true` count
as toggles; anything else spark-related is ignored. Works from the console too.

## Performance notes

- Built to be invisible in spark: the benchmark in `BENCHMARK.MD` had every
  feature running *during* the profiling window and the dev frames rounded to
  0.00%.
- The only periodic work is the inventory-clear task, which exists only while a
  dev player is online and is an allocation-free 36-slot scan.
- The code is sandbox by design, speed is the acceptance bar, don't
  "productionize" it.
