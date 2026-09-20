package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zbiera przedmioty leżące na ziemi wokół punktu komendy GATHER do ekwipunku jednostki.
 * Kończy komendę, gdy nic już nie zostało (albo ekwipunek jest pełny).
 * Na razie tylko wyrzucone przedmioty; rąbanie drzew / zbiór upraw to krok 3.
 */
public class GatherItemsGoal extends Goal {
    // 2 bloki (w kwadracie). Pathfinder z accuracy=1 potrafi zakończyć ścieżkę o blok od przedmiotu,
    // więc zasięg podniesienia musi być większy niż ~1.5 bloku.
    private static final double PICKUP_DISTANCE_SQR = 4.0D;
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final RiceManEntity mob;
    private final double speedModifier;
    private final Set<Integer> ignoredItemIds = new HashSet<>();
    @Nullable
    private ItemEntity targetItem;
    private int repathCooldown;
    private int ticksOnTarget;

    public GatherItemsGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.GATHER;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getCommand() == RiceManEntity.Command.GATHER;
    }

    @Override
    public void start() {
        targetItem = null;
        ignoredItemIds.clear();
        repathCooldown = 0;
        ticksOnTarget = 0;
    }

    @Override
    public void tick() {
        if (targetItem == null || !targetItem.isAlive()) {
            targetItem = findItem();
            ticksOnTarget = 0;
            if (targetItem == null) {
                mob.finishCommand(); // nic więcej do zebrania
                return;
            }
        }

        if (++ticksOnTarget > GIVE_UP_TICKS) { // nie da się dojść, olewamy ten przedmiot
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

        // Gdy pathfinder uznał cel za osiągnięty (albo nie ma ścieżki), a przedmiot jest jeszcze
        // poza zasięgiem podniesienia, podchodzimy do niego bezpośrednio.
        if (mob.getNavigation().isDone()) {
            mob.getMoveControl().setWantedPosition(
                    targetItem.getX(), targetItem.getY(), targetItem.getZ(), speedModifier);
        }
    }

    @Override
    public void stop() {
        targetItem = null;
        mob.getNavigation().stop();
    }

    @Nullable
    private ItemEntity findItem() {
        BlockPos center = mob.getCommandPos() != null ? mob.getCommandPos() : mob.blockPosition();
        AABB area = new AABB(center).inflate(RiceManEntity.GATHER_RADIUS);
        List<ItemEntity> items = mob.level().getEntitiesOfClass(ItemEntity.class, area,
                e -> e.isAlive()
                        && !e.getItem().isEmpty()
                        && !ignoredItemIds.contains(e.getId())
                        && mob.getInventory().canAddItem(e.getItem()));
        return items.stream()
                .min(Comparator.comparingDouble((ItemEntity e) -> mob.distanceToSqr(e)))
                .orElse(null);
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