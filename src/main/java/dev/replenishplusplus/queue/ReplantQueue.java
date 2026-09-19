package dev.replenishplusplus.queue;

import dev.replenishplusplus.ReplenishPlusPlus;
import dev.replenishplusplus.crop.AgeMetaRegistry;
import dev.replenishplusplus.crop.CocoaCropInfo;
import dev.replenishplusplus.crop.CropInfo;
import dev.replenishplusplus.crop.CropType;
import dev.replenishplusplus.crop.SimpleCropInfo;
import dev.replenishplusplus.util.LocationUtil;
import dev.replenishplusplus.util.WarningThrottle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Level;

public final class ReplantQueue {
    private static final int WHEEL_BITS = 13;
    private static final int WHEEL_SIZE = 1 << WHEEL_BITS;
    private static final int WHEEL_MASK = WHEEL_SIZE - 1;
    public static final int MAX_DELAY_TICKS = WHEEL_SIZE - 1;
    private static final int INITIAL_POOL_SIZE = 1 << 10;
    private static final int MAX_UNLOAD_RETRIES = 20;

    private static final int AGE_MASK = 0xFF;
    private static final int FACE_SHIFT = 8;
    private static final int FACE_MASK = 0x3;
    private static final int RETRY_SHIFT = 10;
    private static final int RETRY_MASK = 0xFF;
    private static final int SEED_FLAG_SHIFT = 18;
    private static final int SEED_FLAG_MASK = 0x1;

    private final ReplenishPlusPlus plugin;
    private final AgeMetaRegistry ageMetaRegistry;
    private final int maxPerTick;
    private final int maxPoolSize;

    private final int[] wheelHeads = new int[WHEEL_SIZE];

    private World[] poolWorlds;
    private int[] poolX;
    private int[] poolY;
    private int[] poolZ;
    private Material[] poolMaterials;
    private int[] poolMeta;
    private int[] poolNext;
    private UUID[] poolPlayerIds;

    private int freeHead = -1;
    private int cursor = 0;
    private int pendingCount = 0;
    private BukkitTask scheduledTask;
    private volatile boolean started = false;

    private World memoWorld;
    private int memoChunkX = Integer.MIN_VALUE;
    private int memoChunkZ = Integer.MIN_VALUE;
    private int memoTick = -1;
    private boolean memoLoaded;

    public ReplantQueue(ReplenishPlusPlus plugin, int maxPerTick, int maxPoolSize, AgeMetaRegistry ageMetaRegistry) {
        this.plugin = plugin;
        this.ageMetaRegistry = ageMetaRegistry;
        this.maxPerTick = maxPerTick;
        this.maxPoolSize = maxPoolSize;
        Arrays.fill(wheelHeads, -1);
        primePool();
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        scheduledTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public synchronized int flush() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        started = false;

        int flushed = 0;
        for (int slot = 0; slot < WHEEL_SIZE; slot++) {
            int head = wheelHeads[slot];
            if (head == -1) continue;
            wheelHeads[slot] = -1;

            while (head != -1) {
                int next = poolNext[head];
                poolNext[head] = -1;

                if (isChunkLoadedCached(poolWorlds[head], poolX[head], poolZ[head])) {
                    replant(head);
                    flushed++;
                } else {
                    handleFailureForUnloadedChunk(poolMaterials[head], poolPlayerIds[head], seedWasConsumed(head));
                }
                release(head);
                head = next;
            }
        }
        return flushed;
    }

    public synchronized QueueStats getStats() {
        return new QueueStats(pendingCount, maxPoolSize, poolX.length);
    }

    public synchronized void enqueue(Block block, int delayTicks, int targetAge,
                                     BlockFace cocoaFacing, UUID playerId, boolean seedConsumed) {

        if (!started) {
            handleFailureForUnloadedChunk(block.getType(), playerId, seedConsumed);
            return;
        }

        if (pendingCount >= maxPoolSize) {
            WarningThrottle.log(plugin, Level.WARNING, WarningThrottle.Category.QUEUE_BACKPRESSURE,
                    () -> "Replant queue is full (" + pendingCount + "/" + maxPoolSize + "). Dropping replant at " + LocationUtil.describe(block) + " to prevent server lag.");
            return;
        }

        int delay = clampDelay(delayTicks, block);
        int slot = (cursor + delay) & WHEEL_MASK;
        int index = acquire();

        poolWorlds[index] = block.getWorld();
        poolX[index] = block.getX();
        poolY[index] = block.getY();
        poolZ[index] = block.getZ();
        poolMaterials[index] = block.getType();
        poolMeta[index] = packMeta(targetAge, cocoaFacing, seedConsumed);
        poolPlayerIds[index] = playerId;
        poolNext[index] = wheelHeads[slot];
        wheelHeads[slot] = index;
        pendingCount++;
    }

    private synchronized void tick() {
        if (!started) return;

        int head = wheelHeads[cursor];
        if (head == -1) {
            cursor = (cursor + 1) & WHEEL_MASK;
            return;
        }
        wheelHeads[cursor] = -1;

        int processed = 0;
        int deferredHead = -1;
        int deferredTail = -1;

        while (head != -1) {
            int next = poolNext[head];
            poolNext[head] = -1;

            if (processed >= maxPerTick) {
                if (deferredHead == -1) deferredHead = head;
                else poolNext[deferredTail] = head;
                deferredTail = head;
                head = next;
                continue;
            }

            if (isChunkLoadedCached(poolWorlds[head], poolX[head], poolZ[head])) {
                replant(head);
                release(head);
                processed++;
            } else if (retryCount(head) >= MAX_UNLOAD_RETRIES) {
                final int abandoned = head;
                WarningThrottle.log(plugin, Level.WARNING, WarningThrottle.Category.ABANDONED_REPLANT,
                        () -> "Abandoning replant at " + describe(abandoned) + " - chunk remained unloaded.");
                handleFailureForUnloadedChunk(poolMaterials[head], poolPlayerIds[head], seedWasConsumed(head));
                release(head);
                processed++;
            } else {
                incrementRetry(head);
                if (deferredHead == -1) deferredHead = head;
                else poolNext[deferredTail] = head;
                deferredTail = head;
            }
            head = next;
        }

        int nextSlot = (cursor + 1) & WHEEL_MASK;
        if (deferredHead != -1) {
            poolNext[deferredTail] = wheelHeads[nextSlot];
            wheelHeads[nextSlot] = deferredHead;
        }
        cursor = nextSlot;
    }

    private boolean isChunkLoadedCached(World world, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int tickNow = Bukkit.getCurrentTick();
        // The tick stamp keeps the cached answer from leaking across ticks: a chunk that
        // (un)loads between two ticks must be re-checked, or retries act on stale state.
        if (tickNow != memoTick || chunkX != memoChunkX || chunkZ != memoChunkZ || world != memoWorld) {
            memoTick = tickNow;
            memoChunkX = chunkX;
            memoChunkZ = chunkZ;
            memoWorld = world;
            memoLoaded = world.isChunkLoaded(chunkX, chunkZ);
        }
        return memoLoaded;
    }

    private void replant(int index) {
        Material material = poolMaterials[index];
        CropInfo info = ageMetaRegistry.get(material);
        if (info == null) {
            WarningThrottle.log(plugin, Level.WARNING, WarningThrottle.Category.AGE_DATA_MISSING,
                    () -> "No age data found for plant: " + material + ", skipping replant at " + describe(index));
            return;
        }

        World world = poolWorlds[index];
        int x = poolX[index];
        int y = poolY[index];
        int z = poolZ[index];
        int metadata = poolMeta[index];
        int targetAge = metadata & AGE_MASK;

        try {
            boolean success;
            if (info instanceof CocoaCropInfo cocoa) {
                success = replantCocoa(world, x, y, z, cocoa, targetAge, (metadata >>> FACE_SHIFT) & FACE_MASK);
            } else {
                success = replantNormal(world, x, y, z, (SimpleCropInfo) info, targetAge);
            }
            if (!success) {
                handleReplantFailure(index);
            }
        } catch (Exception e) {
            WarningThrottle.log(plugin, Level.WARNING, WarningThrottle.Category.REPLANT_FAILED,
                    () -> "Failed to replant crop at " + describe(index), e);
            handleReplantFailure(index);
        }
    }

    private boolean replantNormal(World world, int x, int y, int z, SimpleCropInfo info, int targetAge) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.AIR) return false;
        if (!hasAnchor(info, world, x, y, z)) return false;

        block.setBlockData(info.stateFor(targetAge), false);
        return true;
    }

    private boolean hasAnchor(SimpleCropInfo info, World world, int x, int y, int z) {
        for (BlockFace face : info.validNeighborFaces()) {
            if (info.plantsOn(world.getBlockAt(x + face.getModX(), y + face.getModY(), z + face.getModZ()).getType())) return true;
        }
        return false;
    }

    private boolean replantCocoa(World world, int x, int y, int z, CocoaCropInfo info, int targetAge, int faceOrdinal) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.AIR) return false;

        BlockFace face = CocoaCropInfo.face(faceOrdinal);
        Material attachedType = world.getBlockAt(x + face.getModX(), y + face.getModY(), z + face.getModZ()).getType();
        if (!info.plantsOn(attachedType)) return false;

        block.setBlockData(info.stateFor(targetAge, faceOrdinal), false);
        return true;
    }

    private void handleReplantFailure(int index) {
        World world = poolWorlds[index];
        refundSeed(poolMaterials[index], seedWasConsumed(index),
                new Location(world, poolX[index] + 0.5, poolY[index] + 0.5, poolZ[index] + 0.5));

        Player player = onlinePlayer(poolPlayerIds[index]);
        if (player != null) {
            plugin.getConfigCache().replantFailedSound().play(player);
        }
    }

    private void handleFailureForUnloadedChunk(Material cropMaterial, UUID playerId, boolean seedConsumed) {
        Player player = onlinePlayer(playerId);
        // Quit players are deliberately not refunded: the chunk is unloaded, so the only refund
        // locations would force-load chunks - exactly what the lag caps exist to prevent.
        if (player == null) return;

        refundSeed(cropMaterial, seedConsumed, player.getLocation());
        plugin.getConfigCache().replantFailedSound().play(player);
    }

    private static void refundSeed(Material cropMaterial, boolean seedConsumed, Location dropLocation) {
        if (!seedConsumed) return;
        ItemStack seed = seedStack(cropMaterial);
        if (seed != null) dropLocation.getWorld().dropItemNaturally(dropLocation, seed);
    }

    private Player onlinePlayer(UUID playerId) {
        return playerId == null ? null : plugin.getServer().getPlayer(playerId);
    }

    private static ItemStack seedStack(Material cropMaterial) {
        CropType crop = CropType.fromMaterial(cropMaterial);
        return crop == null ? null : new ItemStack(crop.seed());
    }

    private int clampDelay(int delayTicks, Block block) {
        if (delayTicks > MAX_DELAY_TICKS) {
            WarningThrottle.log(plugin, Level.WARNING, WarningThrottle.Category.DELAY_TRUNCATION,
                    () -> "Replant delay truncation triggered for block at " + LocationUtil.describe(block));
            return MAX_DELAY_TICKS;
        }
        return delayTicks;
    }

    private static int packMeta(int targetAge, BlockFace face, boolean seedConsumed) {
        return (targetAge & AGE_MASK)
                | (CocoaCropInfo.faceOrdinal(face) << FACE_SHIFT)
                | ((seedConsumed ? 1 : 0) << SEED_FLAG_SHIFT);
    }

    private boolean seedWasConsumed(int index) {
        return ((poolMeta[index] >>> SEED_FLAG_SHIFT) & SEED_FLAG_MASK) == 1;
    }

    private int retryCount(int index) {
        return (poolMeta[index] >>> RETRY_SHIFT) & RETRY_MASK;
    }

    private void incrementRetry(int index) {
        int retries = retryCount(index) + 1;
        poolMeta[index] = (poolMeta[index] & ~(RETRY_MASK << RETRY_SHIFT))
                | (retries << RETRY_SHIFT);
    }

    private String describe(int index) {
        return LocationUtil.describe(poolWorlds[index], poolX[index], poolY[index], poolZ[index]);
    }

    private void primePool() {
        int size = Math.min(INITIAL_POOL_SIZE, maxPoolSize);
        poolWorlds = new World[size];
        poolX = new int[size];
        poolY = new int[size];
        poolZ = new int[size];
        poolMaterials = new Material[size];
        poolMeta = new int[size];
        poolNext = new int[size];
        poolPlayerIds = new UUID[size];
        for (int i = size - 1; i >= 0; i--) {
            poolNext[i] = freeHead;
            freeHead = i;
        }
    }

    private void growPool() {
        int oldSize = poolX.length;
        int newSize = Math.min(oldSize << 1, maxPoolSize);
        if (newSize <= oldSize) {
            throw new IllegalStateException("Replant pool exhausted (max=" + maxPoolSize + ")");
        }

        poolWorlds = Arrays.copyOf(poolWorlds, newSize);
        poolX = Arrays.copyOf(poolX, newSize);
        poolY = Arrays.copyOf(poolY, newSize);
        poolZ = Arrays.copyOf(poolZ, newSize);
        poolMaterials = Arrays.copyOf(poolMaterials, newSize);
        poolMeta = Arrays.copyOf(poolMeta, newSize);
        poolNext = Arrays.copyOf(poolNext, newSize);
        poolPlayerIds = Arrays.copyOf(poolPlayerIds, newSize);

        for (int i = newSize - 1; i >= oldSize; i--) {
            freeSlot(i);
        }
    }

    private int acquire() {
        if (freeHead == -1) growPool();
        int index = freeHead;
        freeHead = poolNext[index];
        poolNext[index] = -1;
        return index;
    }

    private void release(int index) {
        freeSlot(index);
        pendingCount--;
    }

    private void freeSlot(int index) {
        poolWorlds[index] = null;
        poolX[index] = 0;
        poolY[index] = 0;
        poolZ[index] = 0;
        poolMaterials[index] = null;
        poolMeta[index] = 0;
        poolPlayerIds[index] = null;
        poolNext[index] = freeHead;
        freeHead = index;
    }
}
