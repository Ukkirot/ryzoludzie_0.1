package com.ukkirot.ryzoludzie.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Przenoszenie przedmiotów między ekwipunkiem jednostki a dowolnym Containerem (skrzynia, beczka, ...). */
public final class ContainerTransfer {
    private ContainerTransfer() {
    }

    /** Resolves a block container, combining both halves of a double chest. */
    public static Container containerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Container chest = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            if (chest != null) {
                return chest;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    /**
     * Wkłada stack do target, ile się zmieści (najpierw dokłada do istniejących stosów, potem
     * w wolne miejsca). Nie modyfikuje przekazanego stack; zwraca resztę, która się nie zmieściła
     * (może być pusta).
     */
    public static ItemStack mergeInto(Container target, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int i = 0; i < target.getContainerSize() && !remaining.isEmpty(); i++) {
                ItemStack slot = target.getItem(i);
                boolean matches = pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining);
                boolean empty = pass == 1 && slot.isEmpty();
                if (!matches && !empty) {
                    continue;
                }
                int max = target.getMaxStackSize(remaining);
                int room = empty ? max : max - slot.getCount();
                int move = Math.min(remaining.getCount(), room);
                if (move <= 0) {
                    continue;
                }
                if (empty) {
                    target.setItem(i, remaining.copyWithCount(move));
                } else {
                    slot.grow(move);
                }
                remaining.shrink(move);
            }
        }
        target.setChanged();
        return remaining;
    }

    /** Zabiera do count sztuk item z target. Zwraca zabrany stack (może być pusty, gdy nic nie było). */
    public static ItemStack takeUpTo(Container target, Item item, int count) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < target.getContainerSize() && count > 0; i++) {
            ItemStack slot = target.getItem(i);
            if (slot.isEmpty() || !slot.is(item)) {
                continue;
            }
            ItemStack taken = slot.split(Math.min(count, slot.getCount()));
            count -= taken.getCount();
            if (result.isEmpty()) {
                result = taken;
            } else {
                result.grow(taken.getCount());
            }
        }
        target.setChanged();
        return result;
    }

    /** Sumuje zawartość dowolnego Containera (ekwipunku jednostki albo skrzyni) po przedmiotach. */
    public static Map<Item, Integer> stockOf(Container container) {
        Map<Item, Integer> stock = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty()) {
                stock.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return stock;
    }
}
