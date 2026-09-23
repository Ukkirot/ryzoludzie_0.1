package com.ukkirot.ryzoludzie.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Ryżoludź: jednoblokowy biały prostokąt (8 x 15 x 6 pikseli) z dwiema małymi rączkami.
 * Idzie, kołysząc się na boki i machając rączkami. Rączki są dzieckiem korpusu, więc kołyszą się razem z nim.
 * Układ tekstury (64 x 32): korpus zaczyna się w (0, 0), prawa rączka w (28, 0), lewa w (28, 8).
 */
public class RiceManModel extends EntityModel<RiceManEntity> implements ArmedModel {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(RyzoludzieMod.MODID, "riceman"), "main");

    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private float attackAnim;

    public RiceManModel(ModelPart root) {
        this.root = root;
        this.body = root.getChild("body");
        this.rightArm = body.getChild("right_arm");
        this.leftArm = body.getChild("left_arm");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Punkt obrotu korpusu leży przy stopach (y = 24), więc kołysanie wygląda jak kiwanie się na boki.
        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -15.0F, -3.0F, 8.0F, 15.0F, 6.0F),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        body.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(28, 0).addBox(-2.0F, 0.0F, -1.0F, 2.0F, 4.0F, 2.0F),
                PartPose.offset(-4.0F, -9.0F, 0.0F));
        body.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(28, 8).addBox(0.0F, 0.0F, -1.0F, 2.0F, 4.0F, 2.0F),
                PartPose.offset(4.0F, -9.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void prepareMobModel(RiceManEntity entity, float limbSwing, float limbSwingAmount, float partialTick) {
        this.attackAnim = entity.getAttackAnim(partialTick);
    }

    @Override
    public void setupAnim(RiceManEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float amount = Math.min(1.0F, limbSwingAmount * 4.0F);

        body.zRot = Mth.cos(limbSwing * 0.6662F) * 0.12F * amount;
        rightArm.xRot = Mth.cos(limbSwing * 0.6662F + (float) Math.PI) * 1.2F * amount;
        leftArm.xRot = Mth.cos(limbSwing * 0.6662F) * 1.2F * amount;
        rightArm.zRot = 0.0F;
        leftArm.zRot = 0.0F;

        // Delikatne "oddychanie" rączek, gdy stoi w miejscu.
        float idle = Mth.sin(ageInTicks * 0.09F) * 0.05F * (1.0F - amount);
        rightArm.zRot += idle + 0.05F;
        leftArm.zRot -= idle + 0.05F;

        // Machnięcie prawą rączką przy pracy i ataku (mob.swing).
        if (attackAnim > 0.0F) {
            float swing = Mth.sin(attackAnim * (float) Math.PI);
            rightArm.xRot = -2.2F * swing;
        }
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }

    @Override
    public void translateToHand(HumanoidArm arm, PoseStack poseStack) {
        body.translateAndRotate(poseStack);
        (arm == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(poseStack);
    }
}
