package com.ukkirot.ryzoludzie.entity;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Przenoszenie przedmiotów między ekwipunkiem jednostki a dowolnym Containerem (skrzynia, beczka, ...). */
public final class ContainerTransfer {
    private ContainerTransfer() {
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
}
