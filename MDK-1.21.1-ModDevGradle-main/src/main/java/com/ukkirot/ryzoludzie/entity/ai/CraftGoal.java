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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Realizuje komendę CRAFT. Układa plan przez RiceCrafter i wykonuje go krok po kroku:
 * wytwarzanie (każdy krok trwa chwilę, z machaniem rączkami) i stawianie stołu rzemieślniczego,
 * jeśli żaden nie stoi w pobliżu. Przepisy 3x3 wymagają stołu w zasięgu, więc ryżoludź do niego podchodzi.
 * Na końcu, jeśli o to poproszono, stawia gotowy blok (przekazuje komendę PLACE).
 */
public class CraftGoal extends Goal {
    private static final int CRAFT_TICKS = 20;
    private static final int PLACE_TABLE_TICKS = 15;
    private static final double TABLE_REACH_SQR = 9.0D; // 3 bloki
    private static final int GIVE_UP_TICKS = 20 * 20;

    private final RiceManEntity mob;
    private final double speedModifier;

    private List<RiceCrafter.Step> steps = new ArrayList<>();
    private int index;
    private int workTicks;
    private int walkTicks;
    private int repathCooldown;
    @Nullable
    private BlockPos tablePos;
    @Nullable
    private BlockPos placeSpot;

    public CraftGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.CRAFT && mob.getCraftItem() != null;
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
        steps = new ArrayList<>();
        index = 0;
        workTicks = 0;
        walkTicks = 0;
        repathCooldown = 0;
        tablePos = null;
        placeSpot = null;

        Item item = mob.getCraftItem();
        if (item == null || !(mob.level() instanceof ServerLevel level)) {
            mob.finishCommand();
            return;
        }
        RiceCrafter.Plan plan = RiceCrafter.plan(mob, level, item, mob.getCraftCount());
        if (!plan.ok) {
            fail("brakuje składników (" + (plan.missing != null ? BuiltInRegistries.ITEM.getKey(plan.missing) : "?") + ")");
            return;
        }
        steps = new ArrayList<>(plan.steps);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (index >= steps.size()) {
            complete();
            return;
        }
        RiceCrafter.Step step = steps.get(index);
        if (step.placeTable()) {
            tickPlaceTable(level);
        } else if (step.recipe() != null) {
            tickCraft(level, step.recipe().value(), step);
        } else {
            index++;
        }
    }

    // ---------------------------------------------------------------- kroki

    private void tickPlaceTable(ServerLevel level) {
        if (placeSpot == null || !BlockPlacer.canPlace(level, placeSpot)) {
            placeSpot = BlockPlacer.findSpot(level, mob.blockPosition());
        }
        if (placeSpot == null) {
            fail("nie ma miejsca na stół rzemieślniczy");
            return;
        }
        mob.getNavigation().stop();
        lookAt(placeSpot);
        if (!work(PLACE_TABLE_TICKS)) {
            return;
        }
        if (!RiceCrafter.takeOne(mob, Items.CRAFTING_TABLE)) {
            fail("brak stołu rzemieślniczego w ekwipunku");
            return;
        }
        if (BlockPlacer.place(level, mob, placeSpot, Items.CRAFTING_TABLE)) {
            tablePos = placeSpot;
            placeSpot = null;
            index++;
        } else {
            RiceCrafter.giveBack(mob, level, Items.CRAFTING_TABLE);
            placeSpot = null; // spróbujemy w innym miejscu
        }
    }

    private void tickCraft(ServerLevel level, CraftingRecipe recipe, RiceCrafter.Step step) {
        if (RiceCrafter.needsTable(recipe)) {
            if (tablePos == null || !level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
                tablePos = RiceCrafter.findTable(level, mob.blockPosition(), RiceCrafter.TABLE_SEARCH_RADIUS);
                if (tablePos == null) {
                    fail("nie ma stołu rzemieślniczego w pobliżu");
                    return;
                }
            }
            if (mob.distanceToSqr(Vec3.atCenterOf(tablePos)) > TABLE_REACH_SQR) {
                walkTo(tablePos);
                return;
            }
            mob.getNavigation().stop();
            lookAt(tablePos);
        }
        if (!work(CRAFT_TICKS)) {
            return;
        }
        if (!RiceCrafter.craft(mob, level, step.recipe())) {
            fail("zabrakło składników w trakcie wytwarzania");
            return;
        }
        index++;
    }

    private void complete() {
        Item item = mob.getCraftItem();
        if (mob.isPlaceAfterCraft() && item != null) {
            mob.commandPlace(item, mob.getPlacePos()); // przejmuje PlaceBlockGoal
        } else {
            mob.finishCommand();
        }
    }

    // ---------------------------------------------------------------- pomocnicze

    /** Odlicza czas pracy z machaniem rączkami. Zwraca true, gdy praca się skończyła. */
    private boolean work(int required) {
        workTicks++;
        if (workTicks % 6 == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (workTicks >= required) {
            workTicks = 0;
            return true;
        }
        return false;
    }

    private void walkTo(BlockPos pos) {
        if (++walkTicks > GIVE_UP_TICKS) {
            fail("nie da się dojść do stołu rzemieślniczego");
            return;
        }
        if (--repathCooldown <= 0) {
            repathCooldown = 10;
            Path path = mob.getNavigation().createPath(pos, 1);
            if (path == null) {
                fail("nie da się dojść do stołu rzemieślniczego");
                return;
            }
            mob.getNavigation().moveTo(path, speedModifier);
        }
    }

    private void lookAt(BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        mob.getLookControl().setLookAt(c.x, c.y, c.z);
    }

    private void fail(String reason) {
        Item item = mob.getCraftItem();
        String name = item != null ? BuiltInRegistries.ITEM.getKey(item).toString() : "?";
        mob.setNotice("warn", "wytwarzanie " + name + " przerwane: " + reason);
        RyzoludzieMod.LOGGER.warn("[Ryzoludzie] CRAFT {} przerwany: {}", name, reason);
        mob.finishCommand();
    }
}
