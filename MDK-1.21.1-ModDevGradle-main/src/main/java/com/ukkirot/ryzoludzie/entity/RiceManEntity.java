package com.ukkirot.ryzoludzie.entity;

import com.ukkirot.ryzoludzie.entity.ai.BuildStructureGoal;
import com.ukkirot.ryzoludzie.entity.ai.GatherItemsGoal;
import com.ukkirot.ryzoludzie.entity.ai.HarvestBlocksGoal;
import com.ukkirot.ryzoludzie.entity.ai.IdleStrollGoal;
import com.ukkirot.ryzoludzie.entity.ai.MoveToCommandGoal;
import com.ukkirot.ryzoludzie.entity.ai.PlaceBlockGoal;
import com.ukkirot.ryzoludzie.entity.ai.ScoutGoal;
import com.ukkirot.ryzoludzie.entity.ai.StorageGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Podstawowa jednostka frakcji Ryżoludzi.
 * <p>
 * Steruje nią zewnętrzny dashboard (przez RiceManBridge) za pomocą metod commandXxx().
 * Encja trzyma tylko prosty stan komendy; samo wykonanie robią goale AI.
 */
public class RiceManEntity extends PathfinderMob {

    public enum Command {IDLE, MOVE, ATTACK, GATHER, HARVEST, SCOUT, CRAFT, PLACE, DEPOSIT, WITHDRAW, BUILD}

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
    private String activeCommandId;
    @Nullable
    private String activeCommandType;
    private final List<CommandResult> commandResults = new ArrayList<>();
    @Nullable
    private BlockPos commandPos;
    private HarvestMode harvestMode = HarvestMode.ALL;
    @Nullable
    private HarvestArea harvestArea;
    private boolean naturalOnly = true;
    private boolean fellTrees = true;

    // PLACE: co postawić i gdzie (null = w wolnym miejscu obok jednostki)
    @Nullable
    private Item placeItem;
    @Nullable
    private BlockPos placePos;

    // BUILD: nazwa pliku struktury (.nbt) i róg (origin), od którego liczone są względne pozycje bloków.
    @Nullable
    private String buildStructure;
    @Nullable
    private BlockPos buildOrigin;

    // Magazyn: dowolny Container (skrzynia, beczka), do którego jednostka odkłada i z którego pobiera.
    @Nullable
    private BlockPos storageChest;
    // DEPOSIT wywołany automatycznie przez pełny ekwipunek podczas HARVEST wraca potem do pracy.
    private boolean depositResume;
    // WITHDRAW: co i ile zabrać z magazynu.
    @Nullable
    private Item withdrawItem;
    private int withdrawCount = 64;
    private List<Block> gatherBlocks = List.of();
    private List<BlockPos> gatherPositions = List.of();

    // Ostatni komunikat od jednostki (np. "brak w magazynie X"), do pokazania w dashboardzie.
    // Konsumowany przy pierwszym odczycie (RiceManBridge czyści go po wysłaniu w GET_STATE).
    @Nullable
    private String noticeLevel;
    @Nullable
    private String noticeText;

    public record CommandResult(String eventId, String commandId, String command, String status,
                                @Nullable String reasonCode, @Nullable String reasonMessage,
                                @Nullable String resultItem, int resultCount) {
    }

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
        this.goalSelector.addGoal(3, new PlaceBlockGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new StorageGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new BuildStructureGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new ScoutGoal(this, 1.0D));
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

    /** Mines the requested block types in the target chunk, or picks up nearby drops if none were specified. */
    public void commandGather(@Nullable BlockPos center, List<Block> blocks, List<BlockPos> positions) {
        this.setTarget(null);
        this.command = Command.GATHER;
        this.commandPos = (center != null ? center : this.blockPosition()).immutable();
        this.gatherBlocks = blocks.stream()
                .flatMap(block -> com.ukkirot.ryzoludzie.structure.MineMarker.blockVariants(block).stream())
                .distinct()
                .toList();
        this.gatherPositions = positions.stream().map(BlockPos::immutable).toList();
        this.getNavigation().stop();
    }

    public List<Block> getGatherBlocks() {
        return gatherBlocks;
    }

    public List<BlockPos> getGatherPositions() {
        return gatherPositions;
    }

    public void commandScout(BlockPos chunkCenter) {
        this.setTarget(null);
        this.command = Command.SCOUT;
        this.commandPos = chunkCenter.immutable();
        this.getNavigation().stop();
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

    /** Stawia blok z ekwipunku w podanym miejscu (albo w wolnym miejscu obok jednostki, gdy pos == null). */
    public void commandPlace(Item item, @Nullable BlockPos pos) {
        this.setTarget(null);
        this.command = Command.PLACE;
        this.placeItem = item;
        this.placePos = pos != null ? pos.immutable() : null;
        this.commandPos = null;
        this.getNavigation().stop();
    }

    /** Stawia całą strukturę (.nbt) w origin, pobierając materiały z ekwipunku i magazynu. */
    public void commandBuild(String structureName, BlockPos origin) {
        this.setTarget(null);
        this.command = Command.BUILD;
        this.buildStructure = structureName;
        this.buildOrigin = origin.immutable();
        this.commandPos = null;
        this.getNavigation().stop();
    }

    @Nullable
    public String getBuildStructure() {
        return buildStructure;
    }

    @Nullable
    public BlockPos getBuildOrigin() {
        return buildOrigin;
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
        if (command != Command.IDLE) {
            finishCommand("CANCELLED", null, null);
        }
    }

    public void setActiveCommandId(String commandId, String commandType) {
        this.activeCommandId = commandId;
        this.activeCommandType = commandType;
    }

    @Nullable
    public String getActiveCommandId() {
        return activeCommandId;
    }

    public List<CommandResult> getCommandResults() {
        return List.copyOf(commandResults);
    }

    public void acknowledgeCommandResults(List<String> eventIds) {
        commandResults.removeIf(result -> eventIds.contains(result.eventId()));
    }

    public void recordCommandResult(String commandId, String commandType, String status,
                                    @Nullable String reasonCode, @Nullable String reasonMessage,
                                    @Nullable Item resultItem, int resultCount) {
        commandResults.add(new CommandResult(UUID.randomUUID().toString(), commandId, commandType, status,
                reasonCode, reasonMessage,
                resultItem != null ? BuiltInRegistries.ITEM.getKey(resultItem).toString() : null, resultCount));
    }

    /** Wołane przez goale, gdy komenda zakończyła się poprawnie. */
    public void finishCommand() {
        finishCommand("SUCCEEDED", null, null);
    }

    public void finishCommandWithMessage(String message) {
        finishCommand("SUCCEEDED", null, message);
    }

    public void failCommand(String reasonCode, String reasonMessage) {
        finishCommand("FAILED", reasonCode, reasonMessage);
    }

    public void cancelCommand(String reasonCode, String reasonMessage) {
        finishCommand("CANCELLED", reasonCode, reasonMessage);
    }

    private void finishCommand(String status, @Nullable String reasonCode, @Nullable String reasonMessage) {
        if (activeCommandId != null && command != Command.IDLE) {
            String commandType = activeCommandType != null ? activeCommandType : command.name();
            commandResults.add(new CommandResult(UUID.randomUUID().toString(), activeCommandId, commandType,
                    status, reasonCode, reasonMessage, null, 0));
            activeCommandId = null;
            activeCommandType = null;
        }
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
        if (activeCommandId != null) {
            tag.putString("RcActiveCommandId", activeCommandId);
        }
        if (activeCommandType != null) {
            tag.putString("RcActiveCommandType", activeCommandType);
        }
        ListTag results = new ListTag();
        for (CommandResult result : commandResults) {
            CompoundTag entry = new CompoundTag();
            entry.putString("eventId", result.eventId());
            entry.putString("commandId", result.commandId());
            entry.putString("command", result.command());
            entry.putString("status", result.status());
            if (result.reasonCode() != null) {
                entry.putString("reasonCode", result.reasonCode());
            }
            if (result.reasonMessage() != null) {
                entry.putString("reasonMessage", result.reasonMessage());
            }
            if (result.resultItem() != null) {
                entry.putString("resultItem", result.resultItem());
                entry.putInt("resultCount", result.resultCount());
            }
            results.add(entry);
        }
        tag.put("RcCommandResults", results);
        tag.putString("RcHarvestMode", harvestMode.name());
        tag.putBoolean("RcNaturalOnly", naturalOnly);
        tag.putBoolean("RcFellTrees", fellTrees);
        if (harvestArea != null) {
            tag.putIntArray("RcHarvestArea", harvestArea.toArray());
        }
        if (commandPos != null) {
            tag.putIntArray("RcPos", new int[]{commandPos.getX(), commandPos.getY(), commandPos.getZ()});
        }
        if (placeItem != null) {
            tag.putString("RcPlaceItem", BuiltInRegistries.ITEM.getKey(placeItem).toString());
        }
        if (placePos != null) {
            tag.putIntArray("RcPlacePos", new int[]{placePos.getX(), placePos.getY(), placePos.getZ()});
        }
        if (buildStructure != null) {
            tag.putString("RcBuildStructure", buildStructure);
        }
        if (buildOrigin != null) {
            tag.putIntArray("RcBuildOrigin", new int[]{buildOrigin.getX(), buildOrigin.getY(), buildOrigin.getZ()});
        }
        if (storageChest != null) {
            tag.putIntArray("RcStorageChest", new int[]{storageChest.getX(), storageChest.getY(), storageChest.getZ()});
        }
        tag.putBoolean("RcDepositResume", depositResume);
        if (withdrawItem != null) {
            tag.putString("RcWithdrawItem", BuiltInRegistries.ITEM.getKey(withdrawItem).toString());
        }
        tag.putInt("RcWithdrawCount", withdrawCount);
        ListTag gatherBlocksTag = new ListTag();
        for (Block block : gatherBlocks) {
            gatherBlocksTag.add(net.minecraft.nbt.StringTag.valueOf(
                    BuiltInRegistries.BLOCK.getKey(block).toString()));
        }
        tag.put("RcGatherBlocks", gatherBlocksTag);
        ListTag gatherPositionsTag = new ListTag();
        for (BlockPos pos : gatherPositions) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.getX());
            entry.putInt("y", pos.getY());
            entry.putInt("z", pos.getZ());
            gatherPositionsTag.add(entry);
        }
        tag.put("RcGatherPositions", gatherPositionsTag);
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
        this.activeCommandId = tag.contains("RcActiveCommandId")
                ? tag.getString("RcActiveCommandId") : null;
        this.activeCommandType = tag.contains("RcActiveCommandType")
                ? tag.getString("RcActiveCommandType") : null;
        this.commandResults.clear();
        ListTag results = tag.getList("RcCommandResults", Tag.TAG_COMPOUND);
        for (int i = 0; i < results.size(); i++) {
            CompoundTag entry = results.getCompound(i);
            try {
                this.commandResults.add(new CommandResult(
                        entry.getString("eventId"),
                        entry.getString("commandId"),
                        entry.getString("command"),
                        entry.getString("status"),
                        entry.contains("reasonCode") ? entry.getString("reasonCode") : null,
                        entry.contains("reasonMessage") ? entry.getString("reasonMessage") : null,
                        entry.contains("resultItem") ? entry.getString("resultItem") : null,
                        entry.getInt("resultCount")));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed result entries from an interrupted or older save.
            }
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
        this.placeItem = readItem(tag, "RcPlaceItem");
        int[] pp = tag.getIntArray("RcPlacePos");
        this.placePos = pp.length == 3 ? new BlockPos(pp[0], pp[1], pp[2]) : null;
        this.buildStructure = tag.contains("RcBuildStructure") ? tag.getString("RcBuildStructure") : null;
        int[] bo = tag.getIntArray("RcBuildOrigin");
        this.buildOrigin = bo.length == 3 ? new BlockPos(bo[0], bo[1], bo[2]) : null;
        int[] sc = tag.getIntArray("RcStorageChest");
        this.storageChest = sc.length == 3 ? new BlockPos(sc[0], sc[1], sc[2]) : null;
        this.depositResume = tag.getBoolean("RcDepositResume");
        this.withdrawItem = readItem(tag, "RcWithdrawItem");
        this.withdrawCount = tag.contains("RcWithdrawCount") ? Math.max(1, tag.getInt("RcWithdrawCount")) : 64;
        ListTag gatherBlocksTag = tag.getList("RcGatherBlocks", Tag.TAG_STRING);
        List<Block> loadedGatherBlocks = new ArrayList<>();
        for (int i = 0; i < gatherBlocksTag.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(gatherBlocksTag.getString(i));
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(loadedGatherBlocks::add);
            }
        }
        this.gatherBlocks = List.copyOf(loadedGatherBlocks);
        ListTag gatherPositionsTag = tag.getList("RcGatherPositions", Tag.TAG_COMPOUND);
        List<BlockPos> loadedGatherPositions = new ArrayList<>();
        for (int i = 0; i < gatherPositionsTag.size(); i++) {
            CompoundTag entry = gatherPositionsTag.getCompound(i);
            loadedGatherPositions.add(new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z")));
        }
        this.gatherPositions = List.copyOf(loadedGatherPositions);
        if (tag.contains("RcInventory", Tag.TAG_LIST)) {
            inventory.fromTag(tag.getList("RcInventory", Tag.TAG_COMPOUND), this.level().registryAccess());
        }
        // Ręka to kopia narzędzia z ekwipunku, więc po wczytaniu czyścimy ją (narzędzie i tak leży w ekwipunku).
        this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (this.command == Command.PLACE && this.placeItem == null) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.BUILD && (this.buildStructure == null || this.buildOrigin == null)) {
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
        // Stare wersje zapisywały CRAFT jako aktywne zadanie wieloetapowe.
        if (this.command == Command.CRAFT) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.HARVEST && this.harvestArea == null) {
            this.command = Command.IDLE;
        }
        if (this.command == Command.IDLE) {
            this.activeCommandId = null;
            this.activeCommandType = null;
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
