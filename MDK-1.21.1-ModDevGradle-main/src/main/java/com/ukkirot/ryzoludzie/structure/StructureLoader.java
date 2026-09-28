package com.ukkirot.ryzoludzie.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wczytuje plik struktury (.nbt, standardowy format bloku struktury Minecrafta) i zamienia go
 * na uporządkowaną (od dołu w górę) listę bloków do postawienia względem origin, z typem drewna
 * podmienionym pod biom w origin (WoodPalette), oraz sumaryczne zapotrzebowanie na materiały.
 * <p>
 * Nie obsługuje encji bloków z zapisanym NBT (np. zawartości skrzyń w strukturze) ani obrotu/lustra
 * struktury - stawia ją zawsze tak, jak została zapisana.
 */
public final class StructureLoader {
    private static final String STRUCTURES_DIR_NAME = "ryzoludzie_structures";

    private StructureLoader() {
    }

    public record PlacementBlock(BlockPos worldPos, BlockState state) {
    }

    public record LoadResult(int[] size, List<PlacementBlock> blocks, Map<Item, Integer> materials) {
    }

    public static Path structuresDir(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve(STRUCTURES_DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można utworzyć folderu struktur: " + dir, e);
        }
        return dir;
    }

    /**
     * Wczytuje strukturę o podanej nazwie i liczy jej bloki względem origin, z typem drewna
     * podmienionym pod biom w origin. Rzuca IllegalArgumentException, gdy plik nie istnieje albo
     * nie jest poprawną strukturą.
     */
    public static LoadResult load(MinecraftServer server, String name, ServerLevel level, BlockPos origin) {
        Path file = structuresDir(server).resolve(name);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("Nie znaleziono struktury: " + name);
        }

        CompoundTag tag;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalArgumentException("Uszkodzony plik struktury: " + name);
        }

        if (!tag.contains("size") || !tag.contains("blocks") || !tag.contains("palette")) {
            throw new IllegalArgumentException("Plik nie wygląda jak struktura Minecrafta: " + name);
        }

        ListTag sizeTag = tag.getList("size", IntTag.TAG_INT);
        if (sizeTag.size() != 3) {
            throw new IllegalArgumentException("Niepoprawne pole 'size' w strukturze: " + name);
        }
        int[] size = {sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2)};

        HolderGetter<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i));
        }

        String woodType = WoodPalette.woodTypeForBiome(level, origin);

        List<PlacementBlock> blocks = new ArrayList<>();
        Map<Item, Integer> materials = new HashMap<>();
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag entry = blocksTag.getCompound(i);
            ListTag posTag = entry.getList("pos", IntTag.TAG_INT);
            if (posTag.size() != 3) {
                continue;
            }
            int paletteIndex = entry.getInt("state");
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                continue;
            }
            BlockState state = WoodPalette.swap(palette[paletteIndex], woodType);
            if (state.isAir()) {
                continue; // nic do postawienia - i tak nie zajmuje materiału
            }
            BlockPos worldPos = origin.offset(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));
            blocks.add(new PlacementBlock(worldPos, state));

            Item item = itemFor(state);
            if (item != null) {
                materials.merge(item, 1, Integer::sum);
            }
        }

        // Od dołu w górę, żeby budynek nie "wisiał" w powietrzu w trakcie stawiania.
        blocks.sort(Comparator.comparingInt((PlacementBlock b) -> b.worldPos().getY())
                .thenComparingInt(b -> b.worldPos().getZ())
                .thenComparingInt(b -> b.worldPos().getX()));

        return new LoadResult(size, blocks, materials);
    }

    /** Przedmiot odpowiadający blokowi, albo null dla bloków bez formy przedmiotu (woda, ogień, ...). */
    @Nullable
    private static Item itemFor(BlockState state) {
        Item item = state.getBlock().asItem();
        return item == Items.AIR ? null : item;
    }
}
