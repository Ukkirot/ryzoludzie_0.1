package com.ukkirot.ryzoludzie.entity;

import com.ukkirot.ryzoludzie.entity.ai.GatherItemsGoal;
import com.ukkirot.ryzoludzie.entity.ai.HarvestBlocksGoal;
import com.ukkirot.ryzoludzie.entity.ai.IdleStrollGoal;
import com.ukkirot.ryzoludzie.entity.ai.MoveToCommandGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Podstawowa jednostka frakcji Ryżoludzi.
 * <p>
 * Steruje nią zewnętrzny dashboard (przez RiceManBridge) za pomocą metod commandXxx().
 * Encja trzyma tylko prosty stan komendy; samo wykonanie robią goale AI.
 */
public class RiceManEntity extends PathfinderMob {

    public enum Command {IDLE, MOVE, ATTACK, GATHER, HARVEST}

    /** Co ma zbierać komenda HARVEST. */
    public enum HarvestMode {
        LOGS(true, false),
        CROPS(false, true),
        ALL(true, true);

        private final boolean logs;
        private final boolean crops;

        HarvestMode(boolean logs, boolean crops) {
            this.logs = logs;
            this.crops = crops;
        }

        public boolean allowsLogs() {
            return logs;
        }

        public boolean allowsCrops() {
            return crops;
        }
    }

    public static final int INVENTORY_SIZE = 9;
    public static final double GATHER_RADIUS = 12.0D;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private Command command = Command.IDLE;
    @Nullable
    private BlockPos commandPos;
    private HarvestMode harvestMode = HarvestMode.ALL;
    @Nullable
    private HarvestArea harvestArea;
    private boolean naturalOnly = true;
    private boolean fellTrees = true;

    public RiceManEntity(EntityType<? extends RiceManEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.1D, true));
        this.goalSelector.addGoal(2, new MoveToCommandGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new GatherItemsGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new HarvestBlocksGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new IdleStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        // Oddaje, gdy ktoś go uderzy (ale nie gdy uderzy go inny Riceman).
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, RiceManEntity.class));
    }

    // ---------------------------------------------------------------- komendy

    public void commandMoveTo(BlockPos pos) {
        this.setTarget(null);
        this.command = Command.MOVE;
        this.commandPos = pos.immutable();
        this.getNavigation().stop();
    }

    public void commandAttack(LivingEntity target) {
        this.command = Command.ATTACK;
        this.commandPos = null;
        this.setTarget(target);
    }

    /** Zbiera przedmioty leżące na ziemi w promieniu GATHER_RADIUS od center (albo od swojej pozycji). */
    public void commandGather(@Nullable BlockPos center) {
        this.setTarget(null);
        this.command = Command.GATHER;
        this.commandPos = (center != null ? center : this.blockPosition()).immutable();
    }

    /**
     * Pracuje w podanym obszarze: ścina drzewa (po jednym, do końca) i/lub zbiera dojrzałe uprawy.
     * Poza obszarem niczego nie rusza. Kończy się, gdy w obszarze nic już nie zostało.
     * <p>
     * naturalOnly=true: tnie tylko kłody z naturalnych drzew (połączone z liśćmi, których nie
     * postawił gracz), więc konstrukcje z kłód są bezpieczne.
     * fellTrees=true: po przecięciu pnia reszta kłód tego drzewa pada od razu, więc nie zostają
     * wiszące szczyty wysokich drzew.
     */
    public void commandHarvest(HarvestArea area, HarvestMode mode, boolean naturalOnly, boolean fellTrees) {
        this.setTarget(null);
        this.command = Command.HARVEST;
        this.harvestArea = area;
        this.harvestMode = mode;
        this.naturalOnly = naturalOnly;
        this.fellTrees = fellTrees;
        this.commandPos = null;
    }

    public void commandStop() {
        this.setTarget(null);
        this.getNavigation().stop();
        this.finishCommand();
    }

    /** Wołane przez goale, gdy komenda się skończyła (albo się nie da jej wykonać). */
    public void finishCommand() {
        this.command = Command.IDLE;
        this.commandPos = null;
    }

    public Command getCommand() {
        return command;
    }

    @Nullable
    public BlockPos getCommandPos() {
        return commandPos;
    }

    public HarvestMode getHarvestMode() {
        return harvestMode;
    }

    @Nullable
    public HarvestArea getHarvestArea() {
        return harvestArea;
    }

    public boolean isNaturalOnly() {
        return naturalOnly;
    }

    public boolean isFellTrees() {
        return fellTrees;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    // ---------------------------------------------------------------- zachowanie

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // Cel zginął albo zniknął -> koniec komendy ATTACK.
        if (command == Command.ATTACK) {
            LivingEntity target = this.getTarget();
            if (target == null || !target.isAlive()) {
                this.setTarget(null);
                this.finishCommand();
            }
        }
    }

    /** Jednostki frakcji nie mogą znikać, gdy gracz się oddali. */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack stack : inventory.removeAllItems()) {
            this.spawnAtLocation(stack);
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ---------------------------------------------------------------- NBT

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("RcCommand", command.name());
        tag.putString("RcHarvestMode", harvestMode.name());
        tag.putBoolean("RcNaturalOnly", naturalOnly);
        tag.putBoolean("RcFellTrees", fellTrees);
        if (harvestArea != null) {
            tag.putIntArray("RcHarvestArea", harvestArea.toArray());
        }
        if (commandPos != null) {
            tag.putIntArray("RcPos", new int[]{commandPos.getX(), commandPos.getY(), commandPos.getZ()});
        }
        tag.put("RcInventory", inventory.createTag(this.level().registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            this.command = Command.valueOf(tag.getString("RcCommand"));
        } catch (IllegalArgumentException e) {
            this.command = Command.IDLE;
        }
        try {
            this.harvestMode = HarvestMode.valueOf(tag.getString("RcHarvestMode"));
        } catch (IllegalArgumentException e) {
            this.harvestMode = HarvestMode.ALL;
        }
        this.harvestArea = HarvestArea.fromArray(tag.getIntArray("RcHarvestArea"));
        this.naturalOnly = !tag.contains("RcNaturalOnly") || tag.getBoolean("RcNaturalOnly");
        this.fellTrees = !tag.contains("RcFellTrees") || tag.getBoolean("RcFellTrees");
        int[] p = tag.getIntArray("RcPos");
        this.commandPos = p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : null;
        if (tag.contains("RcInventory", Tag.TAG_LIST)) {
            inventory.fromTag(tag.getList("RcInventory", Tag.TAG_COMPOUND), this.level().registryAccess());
        }
        // Cel ataku nie jest zapisywany, więc po wczytaniu świata ATTACK nie ma sensu.
        if (this.command == Command.ATTACK) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.HARVEST && this.harvestArea == null) {
            this.command = Command.IDLE;
        }
    }
}
