package com.ukkirot.ryzoludzie.client;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

/** Renderer ryżoludzia: biały prostokąt z rączkami, trzymane narzędzie widać w rączce. */
public class RiceManRenderer extends MobRenderer<RiceManEntity, RiceManModel> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(RyzoludzieMod.MODID, "textures/entity/riceman.png");

    public RiceManRenderer(EntityRendererProvider.Context context) {
        super(context, new RiceManModel(context.bakeLayer(RiceManModel.LAYER)), 0.3F);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(RiceManEntity entity) {
        return TEXTURE;
    }
}
