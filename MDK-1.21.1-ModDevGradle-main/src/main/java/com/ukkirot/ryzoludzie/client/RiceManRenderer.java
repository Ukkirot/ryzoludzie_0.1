package com.ukkirot.ryzoludzie.client;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Humanoidalny model z tymczasową teksturą zombie.
 * Żeby dodać własną: wrzuć 64x64 PNG do
 * assets/ryzoludzie/textures/entity/riceman.png i zmień TEXTURE na
 * ResourceLocation.fromNamespaceAndPath("ryzoludzie", "textures/entity/riceman.png").
 */
public class RiceManRenderer extends HumanoidMobRenderer<RiceManEntity, HumanoidModel<RiceManEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public RiceManRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(RiceManEntity entity) {
        return TEXTURE;
    }
}
