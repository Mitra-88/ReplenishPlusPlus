# /rpp dev

Op-only test sandbox for farming. Turn it on, break crops at a stupid speed,
watch the numbers, turn it off.

It's invisible on purpose: no menu entry, nothing in `/rpp status`, nothing in
`/rpp reload`, no `dev:` section in `config.yml`, no README row, no permission
listing. This file is the only doc, and it doesn't leave your machine.

![The testing world](preview/testing-world-preview.png)

## How it behaves

- `/rpp dev` toggles it, just for you. Permission `replenishplusplus.dev`, so op.
- Lives in memory only. Relog and it's off. Nothing is saved anywhere.
- Nobody in dev mode = the plugin does no dev work at all, just a couple of
  emptiness checks per harvest and one boolean per ice event.
- All dev text is hardcoded. It never touches `en_us.yml`.

## What turns on

| Key                | Default | What it does                                                                                                                                                                                                                                                                                                                                                                        |
| ------------------ | ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `fast-water`       | on      | Water acts like dry land. A transient +1.0 water movement attribute (roughly Depth Strider III) applied on enable, removed on disable. Dying keeps it working now, respawn re-applies it automatically. A relog drops it, since the whole state is memory-only.                                                                                                                     |
| `no-ice`           | on      | No ice forms anywhere while anyone has dev on, Frost Walker included. World-wide by nature, so it affects everyone, not just you.                                                                                                                                                                                                                                                   |
| `inventory-clear`  | on      | Every second: if you have 4 or more free slots it does nothing, otherwise it wipes the 36 storage slots except hoes/axes and up to 64 seeds of whatever you farmed last. Harvested crops get deleted too, so only replant material survives. Armor and offhand are never touched. Deletes quietly, never drops items. Runs even while you're dead or spectating, that's on purpose. |
| `full-age-replant` | on      | Crops you break replant fully grown, young ones included, so a half-grown farm normalizes the moment you harvest it. Seed rules don't change: immature crops still drop nothing and eat no seed.                                                                                                                                                                                    |
| `harvest-counter`  | on      | Spark profiler windows get counted and reported. Workflow below.                                                                                                                                                                                                                                                                                                                    |
| `fast-growth`      | on      | Crops placed by anyone pop in fully grown, and enabling dev fully grows the 25×25×5 area around you once. No growth task runs after that. Crops grown young by villagers stay vanilla until you re-toggle.                                                                                                                                                                          |

All six default to on. To turn one off, add a `dev:` section to config.yml by
hand (it never ships there), then `/rpp reload`:

```yaml
dev:
  inventory-clear: false
```

## The harvest counter

1. `/rpp dev`
2. `/spark profiler start`, or `start 60` to auto-stop after 60 seconds.
   You get a "Harvest tracking started" line with your pre-window total.
3. Farm.
4. `/spark profiler stop`, or let the timeout run out. The Harvest Report goes
   to you, the console, and anyone else in dev mode: window length, crops and
   crops/sec, a per-crop breakdown, the tool you harvested with and its
   enchants, items the auto-clear deleted, and session totals.

Things worth knowing:

- The report follows whoever ran the profiler. Turn dev off before stopping and
  you still get it.
- Bare `/spark profiler` and flag forms like `--memory true` count as toggles.
  Works from the console too.
- Timeouts cap at 24 hours. Re-running `start 60` mid-window pushes the
  auto-stop back without resetting the numbers.
- Any player typing spark profiler commands moves the window. There's no
  permission check on the counting itself, and it only ever shows aggregate
  numbers, but it's worth knowing on a server with strangers.
- Stopping requires the counter still enabled. If you set
  `harvest-counter: false` and reload mid-window, a manual stop won't print a
  report until you turn it back on. A timed window still closes on its own.

## The testing world

The farm above lives in a void world built for these tests. If you want the
exact setup, it's packaged: [Server Testing.7z](devmode/Server%20Testing.7z)
contains the world, `server.properties`, and the plugin config folder. Drop
them into a fresh Paper 26.3 server, add the plugin jar, start it, and the
numbers in BENCHMARK.md are reproducible.

The farm sits at `-75 5 -91`. After loading in, teleport to it with
`/minecraft:tp @e -75 5 -91`. The plugin pins the wool pad displays, so they
ignore teleports and stay on their pads even when `@e` tries to drag them along.

## Performance

- Nobody in dev mode: no dev code runs. Two emptiness checks per harvest and
  one boolean per ice event, that's the whole cost.
- Someone in dev mode: the clear task scans 36 slots once a second,
  allocation-free. The latest benchmark had dev fully active during a 5-minute
  profile, and it measured 0.01% of the server thread. Numbers in BENCHMARK.md.

It's sandbox code. Speed is the point, don't productionize it.
