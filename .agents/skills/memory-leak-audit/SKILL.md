---
name: memory-leak-audit
description: 'Audit this Paper plugin for memory leaks and lifetime bugs. Use when adding or reviewing per-player state (maps keyed by UUID/Player), event listeners, scheduled or repeating tasks, static caches, decide→commit stashing, or the replant queue — and when fixing leak reports, growing heap usage, or OOMs.'
---

# Memory Leak Audit (Paper plugin)

A Minecraft server is a long-running process shared by every world and player — one unbounded
collection outlives players, worlds, and reloads. The #1 bug category for this plugin is
per-player state that outlives the player. This skill encodes the lifetime patterns the
codebase already uses; audit changes against them.

## When to Use

- Adding or reviewing any map/collection keyed by UUID or Player
- Stashing state between the harvest decide phase and the commit phase
- Scheduling tasks (`runTaskTimer`/`runTaskLater`/`runTaskAsynchronously`)
- Touching `ReplantQueue`, the reload path, or any static state
- Fixing leak reports or heap growth

## Audit Checklist

Work through each check in order. Each rule is grounded in a real file in this repo —
read the referenced file when auditing that area.

### Step 1: Per-player state

**Rule**: every map or field keyed by `UUID` or `Player` must have a `PlayerQuitEvent`
eviction path. Key by UUID, not `Player` — a stored `Player` pins the entire entity
hierarchy.

```java
// BAD — one entry per player who ever triggered it, lives until restart
private final Map<UUID, Long> cooldown = new HashMap<>();

// GOOD — evicted on quit
@EventHandler
public void onQuit(PlayerQuitEvent event) {
    lastInvalidation.remove(event.getPlayer().getUniqueId());
    SeedIndex.invalidate(event.getPlayer());
}
```

**Reference**: `SeedCacheInvalidationListener.onQuit` and `ReplenishPlusPlusListener.onQuit` —
the quit hooks that evict `lastInvalidation`, `SeedIndex`, and `messageCooldown`. Every new
per-player map gets an eviction line in one of them.

### Step 2: Decide→commit stashing

**Rule**: state stashed by the HIGHEST decide handler for the MONITOR commit handler must
not outlive the event. Use a `WeakHashMap` keyed by the event instance *and* `remove()` it
explicitly in the commit handler. A plain `HashMap` keyed by events leaks one entry per
break that never reaches commit (late-cancelled breaks are legal in Bukkit — that is the
dupe protection, see AGENTS.md).

**Reference**: `ReplenishPlusPlusListener.pendingHarvests` — `WeakHashMap<BlockBreakEvent, HarvestPlan>`,
removed first thing in `onBlockBreakCommit`.

### Step 3: Scheduled tasks

**Rule**: keep every `BukkitTask` handle and cancel it in the disable path *and* before
recreating it. `ReplantQueue.scheduledTask` is cancelled in `flush()`; both `onDisable()`
and the reload's `restartQueue()` go through `flush()` — a reload that started a second
timer without cancelling the first would double-replant *and* leak the task.

```java
// BAD — reload creates a second timer; the first keeps firing forever
plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);

// GOOD — handle kept, cancelled before restart
public synchronized int flush() {
    if (scheduledTask != null) { scheduledTask.cancel(); scheduledTask = null; }
    ...
}
```

One-shot `runTaskAsynchronously` needs no handle — short-lived by design.

### Step 4: Bounded growth

**Rule**: every structure that grows must have a cap and a defined overflow policy —
drop (with a `WarningThrottle` warning), refund, or evict. Never throw; never grow unbounded.
`ReplantQueue`'s primitive pool grows only to `maxReplantsQueued` (config-clamped ≥ 256);
a queue-full enqueue drops the replant with a throttled warning — by design, and the one
deliberate no-refund case (see AGENTS.md).

```java
// BAD
pendingReplants.add(entry);

// GOOD — capped, overflow handled by policy
if (pendingCount >= maxPoolSize) {
    WarningThrottle.log(plugin, Level.WARNING, Category.QUEUE_BACKPRESSURE, () -> "...");
    return;
}
```

### Step 5: Static state

**Rule**: static collections live for the whole process. They must be bounded by an enum
or registry, or loaded once — never keyed by anything dynamic.

Good: `WarningThrottle.STATES` (keyed by `Category`), `SoundRegistryMapper.SOUNDS`
(registry contents, lazily loaded once), `SeedIndex.cacheByPlayer` (static but evicted per
Step 1). Bad: a new `static Map` keyed by player, world, chunk, or event.

### Step 6: One-shot callbacks

**Rule**: completion-callback lists must drain after firing and fast-path "already
completed", or every late registrant accumulates forever. `UpdateChecker` is the pattern:
volatile `checkCompleted` flag, `onCheckCompleted` runs immediately when the check is done,
`fireCompletionActions` copies + clears the list, and `check()` no-ops when disabled.

### Step 7: Listener instances

**Rule**: `registerEvents` happens once in `onEnable`, never per-event or per-object.
New inventory-mutation invalidations go in `SeedCacheInvalidationListener` (per AGENTS.md) —
which already has the quit-eviction path, so new maps there inherit Step 1 for free.

## Quick Reference

| Scenario | Pattern | Anti-Pattern |
|----------|---------|-------------|
| Per-player map | UUID key + `PlayerQuitEvent` eviction | `Player` key, no quit path |
| Decide→commit stash | `WeakHashMap` by event + explicit remove | `HashMap` of events |
| Repeating tasks | keep `BukkitTask`, cancel in `flush()`/`onDisable()` | fire-and-forget `runTaskTimer` |
| Growing structures | config cap + drop/refund overflow | unbounded `add(...)` |
| Static collections | enum/registry-bounded, load-once | `static Map` for dynamic keys |
| One-shot callbacks | drain after fire + already-done fast path | append-only list |

## Verification

After fixing a leak, verify by:

1. `/rpp debug queue` — pending replants drain to 0 on idle farms; pool size is stable across repeated bursts.
2. Stress the path: N relogs, N notifications, N reloads — then re-check map sizes (temporary logging or a heap dump); counts must not grow linearly with usage.
3. `/spark heapsummary` / heap activity over a session for slow growth.
4. `mvn package` passes before finishing any change.
