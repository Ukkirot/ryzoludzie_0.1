package com.ukkirot.ryzoludzie.registry;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RyzoludzieMod.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> RICEMAN_SPAWN_EGG =
            ITEMS.register("riceman_spawn_egg",
                    () -> new DeferredSpawnEggItem(ModEntities.RICEMAN, 0xF2EBD3, 0x9C7A4B, new Item.Properties()));

    private ModItems() {
    }
}
