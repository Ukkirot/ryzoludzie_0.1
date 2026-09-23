package com.ukkirot.ryzoludzie.entity;

import com.ukkirot.ryzoludzie.entity.ai.CraftGoal;
import com.ukkirot.ryzoludzie.entity.ai.GatherItemsGoal;
import com.ukkirot.ryzoludzie.entity.ai.HarvestBlocksGoal;
import com.ukkirot.ryzoludzie.entity.ai.IdleStrollGoal;
import com.ukkirot.ryzoludzie.entity.ai.MoveToCommandGoal;
import com.ukkirot.ryzoludzie.entity.ai.PlaceBlockGoal;
import com.ukkirot.ryzoludzie.entity.ai.StorageGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
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
import net.minecraft.world.item.Item;
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

    public enum Command {IDLE, MOVE, ATTACK, GATHER, HARVEST, CRAFT, PLACE, DEPOSIT, WITHDRAW}

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

    public static final int INVENTORY_SIZE = 27;
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

    // CRAFT: co wytworzyć, ile sztuk i czy potem postawić (w placePos albo w wolnym miejscu obok)
    @Nullable
    private Item craftItem;
    private int craftCount = 1;
    private boolean placeAfterCraft;
    // PLACE: co postawić i gdzie (null = w wolnym miejscu obok jednostki)
    @Nullable
    private Item placeItem;
    @Nullable
    private BlockPos placePos;

    // Magazyn: dowolny Container (skrzynia, beczka), do którego jednostka odkłada i z którego pobiera.
    @Nullable
    private BlockPos storageChest;
    // DEPOSIT wywołany automatycznie przez pełny ekwipunek podczas HARVEST wraca potem do pracy.
    private boolean depositResume;
    // WITHDRAW: co i ile zabrać z magazynu.
    @Nullable
    private Item withdrawItem;
    private int withdrawCount = 64;

    // Ostatni komunikat od jednostki (np. "brak w magazynie X"), do pokazania w dashboardzie.
    // Konsumowany przy pierwszym odczycie (RiceManBridge czyści go po wysłaniu w GET_STATE).
    @Nullable
    private String noticeLevel;
    @Nullable
    private String noticeText;

    public RiceManEntity(EntityType<? extends RiceManEntity> type, Level level) {
        super(type, level);
        // Narzędzie w ręku to ten sam przedmiot, który leży w ekwipunku. Nie może wypaść drugi raz po śmierci.
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        this.setCanPickUpLoot(false);
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
        this.goalSelector.addGoal(3, new CraftGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new PlaceBlockGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new StorageGoal(this, 1.0D));
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

    /**
     * Wytwarza przedmiot z tego, co ma w ekwipunku (razem z półproduktami: deski, patyki, stół).
     * Gdy trzeba użyć stołu rzemieślniczego, którego nie ma w pobliżu, ryżoludź sam go zrobi i postawi.
     * placeAfter=true: gotowy przedmiot (blok) postawi w placePos albo w wolnym miejscu obok siebie.
     */
    public void commandCraft(Item item, int count, boolean placeAfter, @Nullable BlockPos placeAt) {
        this.setTarget(null);
        this.command = Command.CRAFT;
        this.craftItem = item;
        this.craftCount = Math.max(1, count);
        this.placeAfterCraft = placeAfter;
        this.placePos = placeAt != null ? placeAt.immutable() : null;
        this.commandPos = null;
        this.getNavigation().stop();
    }

    /** Stawia blok z ekwipunku w podanym miejscu (albo w wolnym miejscu obok jednostki, gdy pos == null). */
    public void commandPlace(Item item, @Nullable BlockPos pos) {
        this.setTarget(null);
        this.command = Command.PLACE;
        this.placeItem = item;
        this.placePos = pos != null ? pos.immutable() : null;
        this.commandPos = null;
        this.getNavigation().stop();
    }

    /** Przypisuje magazyn (dowolny Container pod tą pozycją) albo null, żeby go odpiąć. */
    public void setStorageChest(@Nullable BlockPos pos) {
        this.storageChest = pos != null ? pos.immutable() : null;
    }

    @Nullable
    public BlockPos getStorageChest() {
        return storageChest;
    }

    /**
     * Odkłada cały ekwipunek (poza narzędziem w ręku) do przypisanego magazynu.
     * resumeHarvest=true: po odłożeniu wraca do przerwanego HARVEST w tym samym obszarze
     * (używane automatycznie, gdy ekwipunek zapełni się w trakcie pracy).
     */
    public void commandDeposit(boolean resumeHarvest) {
        this.setTarget(null);
        this.command = Command.DEPOSIT;
        this.depositResume = resumeHarvest;
        this.getNavigation().stop();
    }

    public boolean isDepositResume() {
        return depositResume;
    }

    /** Zabiera do count sztuk item z przypisanego magazynu do ekwipunku. */
    public void commandWithdraw(Item item, int count) {
        this.setTarget(null);
        this.command = Command.WITHDRAW;
        this.withdrawItem = item;
        this.withdrawCount = Math.max(1, count);
        this.getNavigation().stop();
    }

    @Nullable
    public Item getWithdrawItem() {
        return withdrawItem;
    }

    public int getWithdrawCount() {
        return withdrawCount;
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

    @Nullable
    public Item getCraftItem() {
        return craftItem;
    }

    public int getCraftCount() {
        return craftCount;
    }

    public boolean isPlaceAfterCraft() {
        return placeAfterCraft;
    }

    @Nullable
    public Item getPlaceItem() {
        return placeItem;
    }

    @Nullable
    public BlockPos getPlacePos() {
        return placePos;
    }

    /** level: "warn" albo "info". Nadpisuje poprzedni komunikat, jeśli jeszcze nie odczytany. */
    public void setNotice(String level, String text) {
        this.noticeLevel = level;
        this.noticeText = text;
    }

    @Nullable
    public String getNoticeLevel() {
        return noticeLevel;
    }

    @Nullable
    public String getNoticeText() {
        return noticeText;
    }

    public void clearNotice() {
        this.noticeLevel = null;
        this.noticeText = null;
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
        if (craftItem != null) {
            tag.putString("RcCraftItem", BuiltInRegistries.ITEM.getKey(craftItem).toString());
        }
        tag.putInt("RcCraftCount", craftCount);
        tag.putBoolean("RcPlaceAfterCraft", placeAfterCraft);
        if (placeItem != null) {
            tag.putString("RcPlaceItem", BuiltInRegistries.ITEM.getKey(placeItem).toString());
        }
        if (placePos != null) {
            tag.putIntArray("RcPlacePos", new int[]{placePos.getX(), placePos.getY(), placePos.getZ()});
        }
        if (storageChest != null) {
            tag.putIntArray("RcStorageChest", new int[]{storageChest.getX(), storageChest.getY(), storageChest.getZ()});
        }
        tag.putBoolean("RcDepositResume", depositResume);
        if (withdrawItem != null) {
            tag.putString("RcWithdrawItem", BuiltInRegistries.ITEM.getKey(withdrawItem).toString());
        }
        tag.putInt("RcWithdrawCount", withdrawCount);
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
        this.craftItem = readItem(tag, "RcCraftItem");
        this.craftCount = Math.max(1, tag.getInt("RcCraftCount"));
        this.placeAfterCraft = tag.getBoolean("RcPlaceAfterCraft");
        this.placeItem = readItem(tag, "RcPlaceItem");
        int[] pp = tag.getIntArray("RcPlacePos");
        this.placePos = pp.length == 3 ? new BlockPos(pp[0], pp[1], pp[2]) : null;
        int[] sc = tag.getIntArray("RcStorageChest");
        this.storageChest = sc.length == 3 ? new BlockPos(sc[0], sc[1], sc[2]) : null;
        this.depositResume = tag.getBoolean("RcDepositResume");
        this.withdrawItem = readItem(tag, "RcWithdrawItem");
        this.withdrawCount = tag.contains("RcWithdrawCount") ? Math.max(1, tag.getInt("RcWithdrawCount")) : 64;
        if (tag.contains("RcInventory", Tag.TAG_LIST)) {
            inventory.fromTag(tag.getList("RcInventory", Tag.TAG_COMPOUND), this.level().registryAccess());
        }
        // Ręka to kopia narzędzia z ekwipunku, więc po wczytaniu czyścimy ją (narzędzie i tak leży w ekwipunku).
        this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (this.command == Command.CRAFT && this.craftItem == null) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.PLACE && this.placeItem == null) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.DEPOSIT && this.storageChest == null) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.WITHDRAW && (this.storageChest == null || this.withdrawItem == null)) {
            this.command = Command.IDLE;
        }
        // Cel ataku nie jest zapisywany, więc po wczytaniu świata ATTACK nie ma sensu.
        if (this.command == Command.ATTACK) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.HARVEST && this.harvestArea == null) {
            this.command = Command.IDLE;
        }
    }

    @Nullable
    private static Item readItem(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(key));
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }
}
