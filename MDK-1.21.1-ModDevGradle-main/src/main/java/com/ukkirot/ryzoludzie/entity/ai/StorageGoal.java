package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.ContainerTransfer;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Realizuje komendy DEPOSIT i WITHDRAW: jednostka podchodzi do przypisanego magazynu
 * (dowolny Container, np. skrzynia albo beczka) i przekłada przedmioty.
 * <p>
 * DEPOSIT odkłada cały ekwipunek poza narzędziem trzymanym w ręku. Gdy zostało wywołane
 * automatycznie z powodu pełnego ekwipunku w trakcie HARVEST (depositResume=true), po
 * odłożeniu jednostka wraca do przerwanej pracy w tym samym obszarze.
 */
public class StorageGoal extends Goal {
    private static final double REACH_SQR = 9.0D; // 3 bloki
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final RiceManEntity mob;
    private final double speedModifier;
    private int ticks;
    private int repathCooldown;

    public StorageGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        RiceManEntity.Command c = mob.getCommand();
        return (c == RiceManEntity.Command.DEPOSIT || c == RiceManEntity.Command.WITHDRAW)
                && mob.getStorageChest() != null;
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
        ticks = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos chest = mob.getStorageChest();
        if (chest == null || !(mob.level() instanceof ServerLevel level)) {
            mob.finishCommand();
            return;
        }
        if (!level.isLoaded(chest)) {
            fail("magazyn jest poza załadowanym terenem");
            return;
        }
        if (++ticks > GIVE_UP_TICKS) {
            fail("nie da się dojść do magazynu");
            return;
        }

        Vec3 center = Vec3.atCenterOf(chest);
        if (mob.distanceToSqr(center) > REACH_SQR) {
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                Path path = mob.getNavigation().createPath(chest, 1);
                if (path == null) {
                    fail("nie da się dojść do magazynu");
                    return;
                }
                mob.getNavigation().moveTo(path, speedModifier);
            }
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(center.x, center.y, center.z);

        BlockEntity be = level.getBlockEntity(chest);
        if (!(be instanceof Container container)) {
            fail("w tym miejscu nie ma już magazynu");
            return;
        }

        if (mob.getCommand() == RiceManEntity.Command.DEPOSIT) {
            doDeposit(container);
        } else {
            doWithdraw(container);
        }
    }

    private void doDeposit(Container container) {
        SimpleContainer inv = mob.getInventory();
        ItemStack held = mob.getMainHandItem();
        boolean leftover = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack == held) {
                continue; // narzędzie w rączce zostaje przy jednostce
            }
            ItemStack left = ContainerTransfer.mergeInto(container, stack);
            if (!left.isEmpty()) {
                leftover = true;
            }
            inv.setItem(i, left);
        }
        if (leftover) {
            String msg = "magazyn jest pełny, część przedmiotów została w ekwipunku";
            mob.setNotice("warn", msg);
            RyzoludzieMod.LOGGER.warn("[Ryzoludzie] DEPOSIT {}: {}", mob.getUUID(), msg);
        }
        boolean resume = mob.isDepositResume() && mob.getHarvestArea() != null;
        if (resume) {
            mob.commandHarvest(mob.getHarvestArea(), mob.getHarvestMode(), mob.isNaturalOnly(), mob.isFellTrees());
        } else {
            mob.finishCommand();
        }
    }

    private void doWithdraw(Container container) {
        Item item = mob.getWithdrawItem();
        if (item == null) {
            mob.finishCommand();
            return;
        }
        int wanted = mob.getWithdrawCount();
        ItemStack taken = ContainerTransfer.takeUpTo(container, item, wanted);
        int got = taken.getCount();
        if (!taken.isEmpty()) {
            ItemStack left = mob.getInventory().addItem(taken);
            if (!left.isEmpty()) {
                got -= left.getCount();
                ContainerTransfer.mergeInto(container, left); // ekwipunek pełny, reszta wraca do magazynu
            }
        }
        if (got < wanted) {
            String name = BuiltInRegistries.ITEM.getKey(item).toString();
            String msg = got == 0
                    ? "w magazynie nie ma " + name
                    : "w magazynie było tylko " + got + " z " + wanted + " (" + name + ")";
            mob.setNotice("warn", msg);
            RyzoludzieMod.LOGGER.warn("[Ryzoludzie] WITHDRAW {}: {}", mob.getUUID(), msg);
        }
        mob.finishCommand();
    }

    private void fail(String reason) {
        mob.setNotice("warn", (mob.getCommand() == RiceManEntity.Command.WITHDRAW ? "pobieranie" : "odkładanie")
                + " przerwane: " + reason);
        RyzoludzieMod.LOGGER.warn("[Ryzoludzie] {} przerwany: {}", mob.getCommand(), reason);
        mob.finishCommand();
    }
}
