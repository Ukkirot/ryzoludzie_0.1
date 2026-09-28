package com.ukkirot.ryzoludzie.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Arrays;
import java.util.List;

/**
 * Podmienia typ drewna w BlockState (np. minecraft:oak_planks -&gt; minecraft:spruce_planks),
 * zachowując pozostałe właściwości bloku (kierunek, half, waterlogged, ...), oraz dobiera typ
 * drewna na podstawie biomu, w którym ryżoludź akurat buduje.
 * <p>
 * Działa na nazwie bloku: rozbija ją na segmenty (podział po "_"), szuka w niej znanego typu
 * drewna (najdłuższe dopasowania, jak "dark_oak", sprawdzane przed krótszymi jak "oak") i
 * podmienia te segmenty na segmenty docelowego typu. Dzięki temu obejmuje od razu kłody, deski,
 * schody, płyty, płoty, drzwi, klapy itd. - bez wypisywania każdej kombinacji osobno.
 */
public final class WoodPalette {

    /** Typy drewna jako sekwencje segmentów nazwy, od najdłuższych (więcej segmentów) do najkrótszych. */
    private static final List<String[]> WOOD_TYPES = List.of(
            new String[]{"dark", "oak"},
            new String[]{"oak"},
            new String[]{"spruce"},
            new String[]{"birch"},
            new String[]{"jungle"},
            new String[]{"acacia"},
            new String[]{"mangrove"},
            new String[]{"cherry"},
            new String[]{"bamboo"}
    );

    private WoodPalette() {
    }

    /** Typ drewna (np. "spruce") pasujący do biomu w danym miejscu; domyślnie "oak". */
    public static String woodTypeForBiome(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (isAny(biome, Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
            return "spruce";
        }
        if (isAny(biome, Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            return "birch";
        }
        if (isAny(biome, Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE)) {
            return "jungle";
        }
        if (isAny(biome, Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA)) {
            return "acacia";
        }
        if (isAny(biome, Biomes.DARK_FOREST)) {
            return "dark_oak";
        }
        if (isAny(biome, Biomes.MANGROVE_SWAMP)) {
            return "mangrove";
        }
        if (isAny(biome, Biomes.CHERRY_GROVE)) {
            return "cherry";
        }
        return "oak";
    }

    @SafeVarargs
    private static boolean isAny(Holder<Biome> biome, ResourceKey<Biome>... keys) {
        for (ResourceKey<Biome> key : keys) {
            if (biome.is(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Podmienia typ drewna bloku na targetWood (np. "spruce", "dark_oak"), zachowując właściwości
     * bloku. Jeśli blok nie jest rozpoznanym blokiem drewnianym, albo docelowy odpowiednik nie
     * istnieje w rejestrze, zwraca state bez zmian.
     */
    public static BlockState swap(BlockState state, String targetWood) {
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String[] segments = id.getPath().split("_");
        String[] targetSegments = targetWood.split("_");

        for (String[] type : WOOD_TYPES) {
            int idx = indexOfSubsequence(segments, type);
            if (idx < 0) {
                continue;
            }
            if (type.length == targetSegments.length && Arrays.equals(type, targetSegments)) {
                return state; // już jest właściwym typem
            }
            String[] newSegments = replaceRange(segments, idx, type.length, targetSegments);
            ResourceLocation newId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), String.join("_", newSegments));
            Block newBlock = BuiltInRegistries.BLOCK.getOptional(newId).orElse(null);
            if (newBlock == null) {
                return state; // brak odpowiednika (np. nie każdy typ ma dany wariant) - zostaw oryginał
            }
            return copyProperties(state, newBlock);
        }
        return state; // to nie jest rozpoznany blok drewniany
    }

    private static int indexOfSubsequence(String[] haystack, String[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (!haystack[i + j].equals(needle[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static String[] replaceRange(String[] source, int from, int len, String[] replacement) {
        String[] result = new String[source.length - len + replacement.length];
        System.arraycopy(source, 0, result, 0, from);
        System.arraycopy(replacement, 0, result, from, replacement.length);
        System.arraycopy(source, from + len, result, from + replacement.length, source.length - from - len);
        return result;
    }

    private static BlockState copyProperties(BlockState source, Block targetBlock) {
        BlockState result = targetBlock.defaultBlockState();
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyProperty(result, source, property);
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState target, BlockState source, Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }
}
