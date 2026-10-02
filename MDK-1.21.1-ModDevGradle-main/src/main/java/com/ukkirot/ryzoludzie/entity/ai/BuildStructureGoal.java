package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.ContainerTransfer;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import com.ukkirot.ryzoludzie.structure.StructureLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Places a whole structure at its destination, taking materials from the unit and its storage. */
public class BuildStructureGoal extends Goal {
    private final RiceManEntity mob;

    public BuildStructureGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
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
    public void start() {
        String name = mob.getBuildStructure();
        BlockPos origin = mob.getBuildOrigin();
        if (name == null || origin == null || !(mob.level() instanceof ServerLevel level)) {
            fail(name, "dane budowy są niekompletne");
            return;
        }

        StructureLoader.LoadResult loaded;
        try {
            loaded = StructureLoader.load(level.getServer(), name, level, origin);
        } catch (IllegalArgumentException e) {
            fail(name, e.getMessage());
            return;
        }

        Map<Item, Integer> required = new LinkedHashMap<>();
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        for (StructureLoader.PlacementBlock block : loaded.blocks()) {
            BlockPos pos = block.worldPos();
            if (!level.isLoaded(pos)) {
                fail(name, "obszar budowy jest poza załadowanym terenem przy " + pos);
                return;
            }
            BlockState previous = level.getBlockState(pos);
            if (previous.equals(block.state())) {
                continue;
            }
            originals.put(pos, previous);
            Item item = block.state().getBlock().asItem();
            if (item != Items.AIR) {
                required.merge(item, 1, Integer::sum);
            }
        }

        BlockPos storagePos = mob.getStorageChest();
        Container storage = null;
        if (storagePos != null) {
            if (!level.isLoaded(storagePos)) {
                fail(name, "przypisany magazyn jest poza załadowanym terenem");
                return;
            }
            storage = ContainerTransfer.containerAt(level, storagePos);
            if (storage == null) {
                fail(name, "w przypisanym miejscu nie ma już magazynu");
                return;
            }
        }

        Container inventory = mob.getInventory();
        Map<Item, Integer> inventoryStock = ContainerTransfer.stockOf(inventory);
        Map<Item, Integer> storageStock = storage != null
                ? ContainerTransfer.stockOf(storage) : Map.of();
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            int inInventory = inventoryStock.getOrDefault(entry.getKey(), 0);
            int inStorage = storageStock.getOrDefault(entry.getKey(), 0);
            if (inInventory + inStorage < entry.getValue()) {
                fail(name, "brakuje " + (entry.getValue() - inInventory - inStorage) + "x "
                        + itemName(entry.getKey()) + " (ekwipunek: " + inInventory
                        + ", magazyn: " + inStorage + ")");
                return;
            }
        }

        List<Debit> debits = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            int remaining = take(inventory, entry.getKey(), entry.getValue(), debits);
            if (remaining > 0 && storage != null) {
                remaining = take(storage, entry.getKey(), remaining, debits);
            }
            if (remaining > 0) {
                restore(debits);
                fail(name, "zmieniła się ilość " + itemName(entry.getKey())
                        + " podczas przygotowania budowy");
                return;
            }
        }

        List<BlockPos> placed = new ArrayList<>();
        for (StructureLoader.PlacementBlock block : loaded.blocks()) {
            BlockPos pos = block.worldPos();
            if (level.getBlockState(pos).equals(block.state())) {
                continue;
            }
            if (!level.setBlock(pos, block.state(), 3)) {
                boolean restored = rollback(level, originals, placed);
                restore(debits);
                fail(name, "nie udało się postawić bloku przy " + pos
                        + (restored ? "" : "; nie wszystkie wcześniejsze bloki udało się cofnąć"));
                return;
            }
            placed.add(pos);
        }

        mob.getNavigation().stop();
        mob.finishCommand();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    private static int take(Container source, Item item, int count, List<Debit> debits) {
        ItemStack taken = ContainerTransfer.takeUpTo(source, item, count);
        if (!taken.isEmpty()) {
            debits.add(new Debit(source, taken));
        }
        return count - taken.getCount();
    }

    private void restore(List<Debit> debits) {
        for (Debit debit : debits) {
            ItemStack remaining = ContainerTransfer.mergeInto(debit.source(), debit.stack());
            if (!remaining.isEmpty()) {
                RyzoludzieMod.LOGGER.error("Could not restore BUILD materials to their source: {}",
                        remaining);
                mob.spawnAtLocation(remaining);
            }
        }
    }

    private boolean rollback(ServerLevel level, Map<BlockPos, BlockState> originals, List<BlockPos> placed) {
        boolean restored = true;
        for (int i = placed.size() - 1; i >= 0; i--) {
            BlockPos pos = placed.get(i);
            BlockState original = originals.get(pos);
            if (original == null || !level.setBlock(pos, original, 3)) {
                restored = false;
                RyzoludzieMod.LOGGER.error("Could not roll back partially placed BUILD block at {}", pos);
            }
        }
        return restored;
    }

    private static String itemName(Item item) {
        return Objects.requireNonNull(BuiltInRegistries.ITEM.getKey(item)).toString();
    }

    private void fail(@Nullable String structureName, @Nullable String reason) {
        String name = structureName != null ? structureName : "?";
        String message = "budowa " + name + " nie powiodła się: " + reason;
        RyzoludzieMod.LOGGER.warn("[Ryzoludzie] BUILD {} nieudany: {}", name, reason);
        mob.failCommand("BUILD_FAILED", message);
    }

    private record Debit(Container source, ItemStack stack) {
    }
}
