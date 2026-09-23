package com.ukkirot.ryzoludzie.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;

/** Wspólna logika stawiania bloków (stół rzemieślniczy, skrzynia, później elementy budynków). */
public final class BlockPlacer {
    private static final int SEARCH_RADIUS = 3;

    private BlockPlacer() {
    }

    /** Czy w tym miejscu da się coś postawić: załadowane, puste (albo do zastąpienia) i stabilne podłoże. */
    public static boolean canPlace(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            return false;
        }
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * Najbliższe wolne miejsce w promieniu 3 bloków od origin (nie w komórce jednostki
     * i nie tuż przy niej), żeby postawiony blok jej nie blokował.
     */
    @Nullable
    public static BlockPos findSpot(Level level, BlockPos origin) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    double d = p.distSqr(origin);
                    if (d < 2.0D || d >= bestDist) {
                        continue;
                    }
                    if (canPlace(level, p) && level.getBlockState(p.above()).canBeReplaced()) {
                        best = p.immutable();
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    /** Stawia blok z podanego przedmiotu. Zużywanie przedmiotu z ekwipunku robi wołający. */
    public static boolean place(ServerLevel level, LivingEntity placer, BlockPos pos, Item item) {
        if (!(item instanceof BlockItem blockItem) || !canPlace(level, pos)) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        BlockHitResult hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false);
        BlockPlaceContext context = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit);
        BlockState state = blockItem.getBlock().getStateForPlacement(context);
        if (state == null || !state.canSurvive(level, pos)
                || !level.isUnobstructed(state, pos, CollisionContext.empty())) {
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        state.getBlock().setPlacedBy(level, pos, state, placer, stack);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(placer, state));
        return true;
    }
}
