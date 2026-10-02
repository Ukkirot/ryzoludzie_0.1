package com.ukkirot.ryzoludzie.structure;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class MineMarker {
    private MineMarker() {
    }

    @Nullable
    public static BlockPos findInChunk(ServerLevel level, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        if (!level.hasChunkAt(new BlockPos(minX, level.getMinBuildHeight(), minZ))) {
            return null;
        }
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(RyzoludzieMod.MINE_MARKER.get())) {
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    public static List<Block> blockVariants(Block block) {
        List<Block> variants = new ArrayList<>();
        variants.add(block);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (!id.getPath().startsWith("deepslate_") && id.getPath().endsWith("_ore")) {
            ResourceLocation deepslateId = ResourceLocation.fromNamespaceAndPath(
                    id.getNamespace(), "deepslate_" + id.getPath());
            BuiltInRegistries.BLOCK.getOptional(deepslateId).ifPresent(variants::add);
        }
        return List.copyOf(variants);
    }
}
