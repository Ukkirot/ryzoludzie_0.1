package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.crafting.RiceCrafter;
import com.ukkirot.ryzoludzie.entity.BlockPlacer;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Realizuje komendę PLACE: ryżoludź podchodzi na odległość ręki, macha rączkami i stawia blok.
 * Bez podanego miejsca stawia go w wolnym miejscu tuż obok siebie.
 */
public class PlaceBlockGoal extends Goal {
    private static final double REACH_SQR = 12.25D; // 3,5 bloku
    private static final int PLACE_TICKS = 12;
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final RiceManEntity mob;
    private final double speedModifier;
    @Nullable
    private BlockPos spot;
    private int ticks;
    private int workTicks;
    private int repathCooldown;

    public PlaceBlockGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.PLACE && mob.getPlaceItem() != null;
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
        spot = mob.getPlacePos();
        ticks = 0;
        workTicks = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        Item item = mob.getPlaceItem();
        if (item == null || !(mob.level() instanceof ServerLevel level)) {
            mob.finishCommand();
            return;
        }
        if (!RiceCrafter.has(mob, item)) {
            fail(item, "ryżoludź nie ma tego przedmiotu");
            return;
        }
        if (spot == null) {
            spot = BlockPlacer.findSpot(level, mob.blockPosition());
            if (spot == null) {
                fail(item, "nie ma wolnego miejsca obok");
                return;
            }
        }
        if (!level.isLoaded(spot)) {
            fail(item, "miejsce jest poza załadowanym terenem");
            return;
        }
        if (++ticks > GIVE_UP_TICKS) {
            fail(item, "nie da się dojść do miejsca");
            return;
        }

        Vec3 center = Vec3.atCenterOf(spot);
        if (mob.distanceToSqr(center) > REACH_SQR) {
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                Path path = mob.getNavigation().createPath(spot, 1);
                if (path == null) {
                    fail(item, "nie da się dojść do miejsca");
                    return;
                }
                mob.getNavigation().moveTo(path, speedModifier);
            }
            return;
        }

        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(center.x, center.y, center.z);
        workTicks++;
        if (workTicks % 6 == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (workTicks < PLACE_TICKS) {
            return;
        }

        if (!RiceCrafter.takeOne(mob, item)) {
            fail(item, "ryżoludź nie ma tego przedmiotu");
            return;
        }
        if (BlockPlacer.place(level, mob, spot, item)) {
            mob.finishCommand();
        } else {
            RiceCrafter.giveBack(mob, level, item);
            fail(item, "tu nie da się tego postawić");
        }
    }

    private void fail(Item item, String reason) {
        String name = BuiltInRegistries.ITEM.getKey(item).toString();
        mob.setNotice("warn", "stawianie " + name + " przerwane: " + reason);
        RyzoludzieMod.LOGGER.warn("[Ryzoludzie] PLACE {} przerwany: {}", name, reason);
        mob.finishCommand();
    }
}
