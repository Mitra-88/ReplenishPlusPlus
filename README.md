# Replenish++ 🌾

Auto-replant plugin for Paper/Purpur. You break a crop, it goes back in the ground. That's basically it. Stole the idea from Hypixel's Replenish because I got tired of replanting by hand.

![Preview](assets/output.webp)

## What it does

- Replants Wheat, Carrots, Potatoes, Nether Wart, Cocoa, and Beetroots automatically
- Immature crops stay at whatever growth stage they were at
- Eats a seed from your inventory on mature harvest (can turn this off)
- Can pipe drops straight into your inventory instead of the ground (also toggleable)
- You actually need the right tool in hand. No hoe, no replant. Cocoa needs an axe.
- Sneaking while you break bypasses auto-replant entirely (toggleable via `sneakToBypass`)
- Cocoa gets replanted facing the right direction, which was annoying to get right
- Fortune works like normal
- Sounds are fully configurable, or you can just mute them all
- Built to not choke on big farms. There's a per-tick replant cap you can tune.

## Install

1. JAR goes in `plugins/`
2. Start the server
3. Poke at `plugins/ReplenishPlusPlus/config.yml` if you want
4. You're done

## What happens by default (no config changes)

**Mature crop:**
- Replants as a newly planted crop (age 0 / just planted)
- Takes 1 seed from your inventory/off-hand (if `requirePlayerSeed: true`)
- Drops go to you directly (if `directPickup: true`)

**Immature crop:**
- Replants at the same age it was. No seed needed.
- No drops either — otherwise you could farm infinite seeds by re-breaking young crops.

---

## Commands & permissions

| Command                            | What it does                                 |
|------------------------------------|----------------------------------------------|
| `/replenishplusplus`               | Main menu                                    |
| `/replenishplusplus help`          | Detailed guide                               |
| `/replenishplusplus status`        | Current settings                             |
| `/replenishplusplus version`       | Plugin version & update status               |
| `/replenishplusplus toggle`        | On/off switch for yourself                   |
| `/replenishplusplus toggle global` | On/off switch for everyone                   |
| `/replenishplusplus reload`        | Reload config.yml                            |
| `/replenishplusplus debug queue`   | View queue debug stats & performance metrics |

Aliases: `/rpp` and `/replenish` work for everything above.

Permissions:

- `replenishplusplus.use` - everyone
- `replenishplusplus.status` - everyone
- `replenishplusplus.version` - everyone
- `replenishplusplus.toggle` - everyone
- `replenishplusplus.toggle.global` - op
- `replenishplusplus.reload` - op
- `replenishplusplus.debug` - op
- `replenishplusplus.update` - op
- `replenishplusplus.*` - op (grants all of the above)

## Config

The stuff you'll probably touch:

```yaml
enabled: true
requirePlayerSeed: true
directPickup: true
sneakToBypass: true
replantDelayTicks: 1
maxReplantsPerTick: 1024
maxReplantsQueued: 4096
checkUpdates: true
```

<details>
<summary>Full default config.yml</summary>

```yaml
# ==============================================================================
# ReplenishPlusPlus Configuration
# ==============================================================================

# Global master switch. If false, no crops will auto-replant anywhere.
# (Players can still toggle their own personal auto-replant with /rpp toggle)
enabled: true

# If true, players must have the correct seed in their inventory to replant.
# If false, crops will replant themselves magically without consuming seeds.
requirePlayerSeed: true

# If true, harvested crop drops go directly into the player's inventory.
# If false, drops fall on the ground like vanilla Minecraft.
directPickup: true

# If true, sneaking while breaking a crop will bypass auto-replant entirely.
# The crop will break normally and drop on the ground.
sneakToBypass: true

# How player-facing notifications (inventory full, need seed, wrong tool) are displayed.
# CHAT       = Sends a normal chat message.
# ACTION_BAR = Sends a less spammy message above the hotbar.
# NONE       = Silences all player-facing text notifications (sounds still play).
messageStyle: CHAT

# The delay (in server ticks) before a broken crop is replanted. 20 ticks = 1 second.
replantDelayTicks: 1

# Maximum number of crops that can be replanted in a single server tick.
# Prevents lag if a massive farm is harvested all at once. (Minimum 256)
maxReplantsPerTick: 1024

# Maximum number of pending replants that can be held in the queue at once.
# If the queue is full, replants will be dropped to prevent server lag. (Minimum 256)
maxReplantsQueued: 4096

# Should the plugin check for updates on startup and notify admins?
checkUpdates: true

# ------------------------------------------------------------------------------
# Crop Settings
# ------------------------------------------------------------------------------
crops:
  wheat:       true
  carrots:     true
  potatoes:    true
  nether_wart: true
  cocoa:       true
  beetroots:   true

# ------------------------------------------------------------------------------
# Messages
# ------------------------------------------------------------------------------
# Docs: https://docs.advntr.dev/minimessage/format.html

messages:
  inventory-full: "<dark_gray>[<yellow>ReplenishPlusPlus<dark_gray>] <dark_gray>» <gray>Your inventory was full, so some items dropped on the ground instead."
  requires-tool:  "<dark_gray>[<yellow>ReplenishPlusPlus<dark_gray>] <dark_gray>» <gray>You need a <yellow>{tool} <gray>to harvest <yellow>{crop}<gray>."
  need-seed:      "<dark_gray>[<yellow>ReplenishPlusPlus<dark_gray>] <dark_gray>» <gray>You need <yellow>{count}x {seed} <gray>in your inventory to replant this."

# ------------------------------------------------------------------------------
# Sounds
# ------------------------------------------------------------------------------
# Docs: https://jd.papermc.io/paper/org/bukkit/Sound.html
#         Invalid names log a warning and fall back to the default.
# volume: 0.0 (silent) to 1.0 (loudest)
# pitch:  0.5 (low) to 2.0 (high); 1.0 = normal
#
# To disable a sound entirely, set enabled: false.

sounds:
  # Played when crop drops are successfully added to the player's inventory.
  pickup:
    enabled: true
    sound: ENTITY_ITEM_PICKUP
    volume: 1.0
    pitch: 1.0

  # Played when the player's inventory is full and items drop on the ground.
  inventory-full:
    enabled: true
    sound: BLOCK_NOTE_BLOCK_BASS
    volume: 1.0
    pitch: 0.5

  # Played when a player tries to harvest a crop with the wrong tool.
  denied-tool:
    enabled: true
    sound: ENTITY_VILLAGER_NO
    volume: 1.0
    pitch: 0.5

  # Played when a player tries to harvest a crop but lacks the required seed.
  denied-seed:
    enabled: true
    sound: ENTITY_VILLAGER_NO
    volume: 1.0
    pitch: 0.5

  # Played when a replant FAILS (e.g., chunk unloaded, block occupied, farmland trampled).
  # Alerts the player that their seed was refunded/dropped instead of planted.
  replant-failed:
    enabled: true
    sound: ENTITY_ITEM_BREAK
    volume: 0.5
    pitch: 1.0
```

</details>

## Contributions

PRs are disabled on this repo, not because I don't want your help but because managing them gets overwhelming, and I'd rather not ghost people. Open an issue instead, feature ideas, bugs, optimizations, whatever. We'll talk it out there.

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
