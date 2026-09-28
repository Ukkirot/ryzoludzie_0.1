package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.crafting.RiceCrafter;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import com.ukkirot.ryzoludzie.structure.StructureLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * Realizuje komendę BUILD: stawia bloki wczytanej struktury jeden po drugim (od dołu w górę,
 * jak je uporządkował StructureLoader), zużywając odpowiadające przedmioty z ekwipunku. Materiały
 * są sprawdzane i ściągane z magazynu z góry przez RiceManBridge, więc ten goal tylko stawia -
 * gdy mimo to czegoś zabraknie (np. materiał zużyty przez inne zlecenie w międzyczasie), przerywa
 * z komunikatem, zamiast chodzić po dokładki.
 * <p>
 * Blok już stojący w docelowym stanie jest pomijany bez zużycia materiału - dzięki temu wznowienie
 * przerwanej budowy (np. po restarcie serwera) nie stawia niczego drugi raz.
 */
public class BuildStructureGoal extends Goal {
    private static final double REACH_SQR = 12.25D; // 3,5 bloku
    private static final int WORK_TICKS_PER_BLOCK = 10;
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final RiceManEntity mob;
    private final double speedModifier;

    private List<StructureLoader.PlacementBlock> blocks = List.of();
    private int index;
    private int workTicks;
    private int ticksOnTarget;
    private int repathCooldown;

    public BuildStructureGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.BUILD
                && mob.getBuildStructure() != null && mob.getBuildOrigin() != null;
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
        blocks = List.of();
        index = 0;
        workTicks = 0;
        ticksOnTarget = 0;
        repathCooldown = 0;

        String name = mob.getBuildStructure();
        BlockPos origin = mob.getBuildOrigin();
        if (name == null || origin == null || !(mob.level() instanceof ServerLevel level)) {
            mob.finishCommand();
            return;
        }
        try {
            blocks = StructureLoader.load(level.getServer(), name, level, origin).blocks();
        } catch (IllegalArgumentException e) {
            fail(name, e.getMessage());
            return;
        }
        if (blocks.isEmpty()) {
            mob.finishCommand();
        }
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
        if (index >= blocks.size()) {
            mob.finishCommand();
            return;
        }
        StructureLoader.PlacementBlock target = blocks.get(index);
        BlockPos pos = target.worldPos();

        if (!level.isLoaded(pos)) {
            fail(mob.getBuildStructure(), "obszar budowy jest poza załadowanym terenem");
            return;
        }
        if (level.getBlockState(pos).equals(target.state())) {
            index++; // ten blok już stoi (np. wznowienie po restarcie) - nic do zrobienia
            ticksOnTarget = 0;
            workTicks = 0;
            return;
        }

        if (++ticksOnTarget > GIVE_UP_TICKS) {
            fail(mob.getBuildStructure(), "nie da się dojść do miejsca budowy");
            return;
        }

        Vec3 center = Vec3.atCenterOf(pos);
        if (mob.distanceToSqr(center) > REACH_SQR) {
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                Path path = mob.getNavigation().createPath(pos, 1);
                if (path == null) {
                    fail(mob.getBuildStructure(), "nie da się dojść do miejsca budowy");
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
        if (workTicks < WORK_TICKS_PER_BLOCK) {
            return;
        }

        if (!placeBlock(level, pos, target.state())) {
            return; // fail() już ustawił notice i zakończył komendę
        }
        index++;
        workTicks = 0;
        ticksOnTarget = 0;
    }

    /** Zużywa 1 sztukę materiału (jeśli blok w ogóle ma formę przedmiotu) i stawia blok. */
    private boolean placeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        Item item = state.getBlock().asItem();
        boolean needsItem = item != Items.AIR;
        if (needsItem && !RiceCrafter.takeOne(mob, item)) {
            fail(mob.getBuildStructure(), "zabrakło " + BuiltInRegistries.ITEM.getKey(item) + " w ekwipunku");
            return false;
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
        return true;
    }

    private void fail(@Nullable String structureName, @Nullable String reason) {
        String name = structureName != null ? structureName : "?";
        mob.setNotice("warn", "budowa " + name + " przerwana: " + reason);
        RyzoludzieMod.LOGGER.warn("[Ryzoludzie] BUILD {} przerwany: {}", name, reason);
        mob.finishCommand();
    }
}
