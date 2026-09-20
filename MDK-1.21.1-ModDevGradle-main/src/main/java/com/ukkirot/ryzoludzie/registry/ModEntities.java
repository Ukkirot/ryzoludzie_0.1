package com.ukkirot.ryzoludzie.registry;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, RyzoludzieMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RiceManEntity>> RICEMAN =
            ENTITY_TYPES.register("riceman", () ->
                    EntityType.Builder.of(RiceManEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build("riceman"));

    private ModEntities() {
    }
}
