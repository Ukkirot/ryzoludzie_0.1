package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import com.ukkirot.ryzoludzie.entity.ToolUse;
import com.ukkirot.ryzoludzie.structure.MineMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Executes chunk-scoped resource mining or the legacy nearby dropped-item pickup. */
public class GatherItemsGoal extends Goal {
    private static final double PICKUP_DISTANCE_SQR = 4.0D;
    private static final double MARKER_REACH_SQR = 4.0D;
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final RiceManEntity mob;
    private final double speedModifier;
    private final Set<Integer> ignoredItemIds = new HashSet<>();
    private final List<BlockPos> blockTargets = new ArrayList<>();
    @Nullable
    private ItemEntity targetItem;
    @Nullable
    private BlockPos targetBlock;
    @Nullable
    private BlockPos markerStandPos;
    private int blockTargetIndex;
    private int repathCooldown;
    private int ticksOnTarget;
    private int breakTicks;
    private boolean atMineMarker;
    private int minedBlocks;
    private int collectedItems;

    public GatherItemsGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.GATHER;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        targetItem = null;
        targetBlock = null;
        markerStandPos = null;
        atMineMarker = false;
        ignoredItemIds.clear();
        blockTargets.clear();
        blockTargetIndex = 0;
        breakTicks = 0;
        minedBlocks = 0;
        collectedItems = 0;
        repathCooldown = 0;
        ticksOnTarget = 0;
        if (!mob.getGatherBlocks().isEmpty()) {
            scanChunkForBlocks();
        }
    }

    @Override
    public void tick() {
        if (!mob.getGatherBlocks().isEmpty()) {
            tickMining();
        } else {
            tickPickup();
        }
    }

    @Override
    public void stop() {
        clearBreakingProgress();
        targetItem = null;
        targetBlock = null;
        mob.getNavigation().stop();
    }

    private void scanChunkForBlocks() {
        if (!(mob.level() instanceof ServerLevel level)) {
            mob.failCommand("INVALID_WORLD", "GATHER działa tylko na serwerze");
            return;
        }
        BlockPos center = mob.getCommandPos() != null ? mob.getCommandPos() : mob.blockPosition();
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        if (!level.hasChunkAt(new BlockPos(minX, level.getMinBuildHeight(), minZ))) {
            mob.failCommand("CHUNK_NOT_LOADED", "chunk " + chunkX + ", " + chunkZ
                    + " nie jest załadowany");
            return;
        }
        BlockPos marker = MineMarker.findInChunk(level, chunkX, chunkZ);
        if (marker == null) {
            mob.failCommand("MINE_MARKER_REQUIRED", "w chunku " + chunkX + ", " + chunkZ
                    + " nie ma znacznika kopalni, przy którym ryżoludź ma pracować");
            return;
        }
        markerStandPos = marker.above();

        if (!mob.getGatherPositions().isEmpty()) {
            for (BlockPos pos : mob.getGatherPositions()) {
                if (level.hasChunkAt(pos) && mob.getGatherBlocks().stream()
                        .anyMatch(level.getBlockState(pos)::is)) {
                    blockTargets.add(pos.immutable());
                }
            }
        } else {
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        if (state.is(Blocks.AIR) || state.is(com.ukkirot.ryzoludzie.RyzoludzieMod.MINE_MARKER.get())) {
                            continue;
                        }
                        if (mob.getGatherBlocks().stream().anyMatch(state::is)) {
                            blockTargets.add(pos.immutable());
                        }
                    }
                }
            }
        }
        blockTargets.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX()));
        if (blockTargets.isEmpty()) {
            fail("NO_TARGET_BLOCKS", "nie znaleziono wskazanych bloków w chunku "
                    + chunkX + ", " + chunkZ + ": "
                    + mob.getGatherBlocks().stream()
                    .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                    .distinct().reduce((left, right) -> left + ", " + right).orElse("?"));
            return;
        }
        mob.setNotice("info", "GATHER: wykryto " + blockTargets.size()
                + " bloków do wydobycia w chunku " + chunkX + ", " + chunkZ);
    }

    private void tickMining() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (markerStandPos == null) {
            fail("MINE_MARKER_REQUIRED", "nie znaleziono znacznika kopalni");
            return;
        }
        if (!atMineMarker) {
            if (++ticksOnTarget > GIVE_UP_TICKS) {
                fail("MINE_MARKER_UNREACHABLE", "nie udało się dojść do znacznika kopalni "
                        + markerStandPos.getX() + ", " + markerStandPos.getY() + ", " + markerStandPos.getZ());
                return;
            }
            if (mob.blockPosition().distSqr(markerStandPos) <= MARKER_REACH_SQR) {
                mob.getNavigation().stop();
                atMineMarker = true;
                ticksOnTarget = 0;
                mob.setNotice("info", "GATHER: jednostka dotarła do znacznika kopalni");
            } else if (--repathCooldown <= 0) {
                repathCooldown = 10;
                Path path = mob.getNavigation().createPath(markerStandPos, 0);
                if (path == null) {
                    fail("MINE_MARKER_UNREACHABLE", "nie ma ścieżki do znacznika kopalni "
                            + markerStandPos.getX() + ", " + markerStandPos.getY() + ", " + markerStandPos.getZ());
                    return;
                }
                mob.getNavigation().moveTo(path, speedModifier);
            }
            return;
        }
        if (targetBlock == null || !isTargetBlock(level, targetBlock)) {
            clearBreakingProgress();
            targetBlock = nextBlockTarget(level);
            ticksOnTarget = 0;
            repathCooldown = 0;
            if (targetBlock == null) {
                BlockPos center = mob.getCommandPos() != null ? mob.getCommandPos() : mob.blockPosition();
                mob.setNotice("info", "GATHER zakończono: wydobyto " + minedBlocks + " z "
                        + blockTargets.size() + " wykrytych bloków, zebrano "
                        + collectedItems + " przedmiotów w chunku "
                        + (center.getX() >> 4) + ", " + (center.getZ() >> 4));
                mob.finishCommand();
                return;
            }
        }
        BlockState targetState = level.getBlockState(targetBlock);
        ToolUse.equipBestTool(mob, targetState);
        if (!ToolUse.dropsAllowed(mob, targetState)) {
            fail("MISSING_TOOL", "brak odpowiedniego kilofa do wydobycia "
                    + BuiltInRegistries.BLOCK.getKey(targetState.getBlock())
                    + "; dodaj wymagany kilof do inventory ryżoludzia");
            return;
        }
        Vec3 center = Vec3.atCenterOf(targetBlock);
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(center.x, center.y, center.z);
        mineTarget(level);
    }

    private void mineTarget(ServerLevel level) {
        BlockPos pos = targetBlock;
        if (pos == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        ToolUse.equipBestTool(mob, state);
        if (!ToolUse.dropsAllowed(mob, state)) {
            fail("MISSING_TOOL", "brak narzędzia odpowiedniego do wydobycia "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return;
        }
        int requiredTicks = ToolUse.breakTicks(mob, state, level, pos);
        if (requiredTicks < 0) {
            fail("UNBREAKABLE_BLOCK", "nie można wydobyć bloku "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return;
        }
        breakTicks++;
        if (breakTicks % 6 == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(mob.getId(), pos, Math.min(9, breakTicks * 10 / requiredTicks));
        if (breakTicks < requiredTicks) {
            return;
        }

        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), mob,
                mob.getMainHandItem());
        if (drops.isEmpty()) {
            fail("NO_BLOCK_DROPS", "blok " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " nie daje dropu przy obecnym narzędziu; blok pozostał nienaruszony");
            return;
        }
        var projected = new net.minecraft.world.SimpleContainer(mob.getInventory().getContainerSize());
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++) {
            projected.setItem(i, mob.getInventory().getItem(i).copy());
        }
        for (ItemStack drop : drops) {
            if (!projected.addItem(drop.copy()).isEmpty()) {
                fail("INVENTORY_FULL", "brak miejsca w ekwipunku na drop z "
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                return;
            }
        }

        level.destroyBlockProgress(mob.getId(), pos, -1);
        level.destroyBlock(pos, false, mob);
        ToolUse.damageTool(mob);
        for (ItemStack drop : drops) {
            ItemStack leftover = mob.getInventory().addItem(drop);
            collectedItems += drop.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) {
                Block.popResource(level, pos, leftover);
            }
        }
        minedBlocks++;
        targetBlock = null;
        breakTicks = 0;
        ticksOnTarget = 0;
    }

    private boolean isTargetBlock(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return mob.getGatherBlocks().stream().anyMatch(state::is);
    }

    @Nullable
    private BlockPos nextBlockTarget(ServerLevel level) {
        while (blockTargetIndex < blockTargets.size()) {
            BlockPos pos = blockTargets.get(blockTargetIndex++);
            if (isTargetBlock(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    private void tickPickup() {
        if (targetItem == null || !targetItem.isAlive()) {
            targetItem = findItem();
            ticksOnTarget = 0;
            if (targetItem == null) {
                mob.finishCommand();
                return;
            }
        }
        if (++ticksOnTarget > GIVE_UP_TICKS) {
            ignoredItemIds.add(targetItem.getId());
            targetItem = null;
            return;
        }
        if (mob.distanceToSqr(targetItem) < PICKUP_DISTANCE_SQR) {
            pickUp(targetItem);
            targetItem = null;
            return;
        }
        if (--repathCooldown <= 0) {
            repathCooldown = 10;
            mob.getNavigation().moveTo(targetItem, speedModifier);
        }
        if (mob.getNavigation().isDone()) {
            mob.getMoveControl().setWantedPosition(
                    targetItem.getX(), targetItem.getY(), targetItem.getZ(), speedModifier);
        }
    }

    private void clearBreakingProgress() {
        if (targetBlock != null && mob.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(mob.getId(), targetBlock, -1);
        }
        breakTicks = 0;
    }

    private void fail(String code, String message) {
        if (!blockTargets.isEmpty()) {
            message += " (wydobyto dotąd " + minedBlocks + " z " + blockTargets.size()
                    + " wykrytych bloków, zebrano " + collectedItems + " przedmiotów)";
        }
        mob.failCommand(code, message);
    }

    @Nullable
    private ItemEntity findItem() {
        BlockPos center = mob.getCommandPos() != null ? mob.getCommandPos() : mob.blockPosition();
        AABB area = new AABB(center).inflate(RiceManEntity.GATHER_RADIUS);
        List<ItemEntity> items = mob.level().getEntitiesOfClass(ItemEntity.class, area,
                e -> e.isAlive() && !e.getItem().isEmpty()
                        && !ignoredItemIds.contains(e.getId())
                        && mob.getInventory().canAddItem(e.getItem()));
        return items.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    private void pickUp(ItemEntity item) {
        ItemStack stack = item.getItem();
        int before = stack.getCount();
        ItemStack left = mob.getInventory().addItem(stack.copy());
        int taken = before - left.getCount();
        if (taken > 0) {
            mob.take(item, taken);
            if (left.isEmpty()) {
                item.discard();
            } else {
                item.setItem(left);
            }
        } else {
            ignoredItemIds.add(item.getId());
        }
    }
}
