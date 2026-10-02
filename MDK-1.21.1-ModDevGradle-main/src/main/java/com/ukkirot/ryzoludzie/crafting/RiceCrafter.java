package com.ukkirot.ryzoludzie.crafting;

import com.ukkirot.ryzoludzie.entity.ContainerTransfer;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes one crafting-recipe application without planning intermediate crafts. */
public final class RiceCrafter {
    private RiceCrafter() {
    }

    public record CraftOutcome(boolean success, @Nullable Item output, int outputCount,
                               @Nullable String missing, String code, String message) {
    }

    public static CraftOutcome craftOnce(RiceManEntity unit, ServerLevel level, Item target,
                                         @Nullable ResourceLocation recipeId, @Nullable Container storage) {
        List<RecipeHolder<CraftingRecipe>> recipes = level.getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> !holder.value().isSpecial())
                .filter(holder -> !holder.value().getResultItem(level.registryAccess()).isEmpty())
                .filter(holder -> holder.value().getResultItem(level.registryAccess()).is(target))
                .filter(holder -> recipeId == null || holder.id().equals(recipeId))
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .toList();
        if (recipes.isEmpty()) {
            return new CraftOutcome(false, null, 0, null, "NO_RECIPE",
                    recipeId == null ? "Brak receptury dla " + itemName(target)
                            : "Brak receptury " + recipeId + " wytwarzającej " + itemName(target));
        }

        SimpleContainer inventory = unit.getInventory();
        Map<Item, Integer> stock = ContainerTransfer.stockOf(inventory);
        if (storage != null) {
            ContainerTransfer.stockOf(storage).forEach((item, count) -> stock.merge(item, count, Integer::sum));
        }

        RecipeHolder<CraftingRecipe> selected = null;
        Map<Item, Integer> allocation = null;
        List<String> missingOptions = List.of();
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            Map<Item, Integer> candidate = new HashMap<>();
            List<String> missing = new ArrayList<>();
            if (allocateIngredients(holder.value().getIngredients(), 0, stock, candidate, missing)) {
                selected = holder;
                allocation = candidate;
                break;
            }
            if (missingOptions.isEmpty()) {
                missingOptions = missing;
            }
        }
        if (selected == null || allocation == null) {
            String missing = missingOptions.isEmpty() ? "składników przepisu" : String.join(" lub ", missingOptions);
            return new CraftOutcome(false, null, 0, missing, "MISSING_INGREDIENTS",
                    "Brakuje składników do jednego craftu: " + missing);
        }

        ItemStack result = selected.value().getResultItem(level.registryAccess()).copy();
        SimpleContainer projectedInventory = new SimpleContainer(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            projectedInventory.setItem(i, inventory.getItem(i).copy());
        }
        for (Map.Entry<Item, Integer> ingredient : allocation.entrySet()) {
            ContainerTransfer.takeUpTo(projectedInventory, ingredient.getKey(), ingredient.getValue());
        }
        if (!projectedInventory.canAddItem(result)) {
            return new CraftOutcome(false, null, 0, null, "OUTPUT_INVENTORY_FULL",
                    "Brak miejsca w ekwipunku na wynik " + itemName(result.getItem()));
        }

        for (Map.Entry<Item, Integer> ingredient : allocation.entrySet()) {
            int remaining = ingredient.getValue();
            ItemStack fromInventory = ContainerTransfer.takeUpTo(inventory, ingredient.getKey(), remaining);
            remaining -= fromInventory.getCount();
            if (remaining > 0 && storage != null) {
                ItemStack fromStorage = ContainerTransfer.takeUpTo(storage, ingredient.getKey(), remaining);
                remaining -= fromStorage.getCount();
            }
            if (remaining > 0) {
                throw new IllegalStateException("Craft ingredient stock changed during atomic execution: "
                        + itemName(ingredient.getKey()));
            }
        }
        ItemStack leftover = inventory.addItem(result);
        if (!leftover.isEmpty()) {
            throw new IllegalStateException("Craft output did not fit after inventory capacity validation");
        }
        inventory.setChanged();
        return new CraftOutcome(true, result.getItem(), result.getCount(), null, "", "");
    }

    private static boolean allocateIngredients(List<Ingredient> ingredients, int index, Map<Item, Integer> stock,
                                               Map<Item, Integer> allocation, List<String> missingOptions) {
        if (index >= ingredients.size()) {
            return true;
        }
        Ingredient ingredient = ingredients.get(index);
        if (ingredient.isEmpty()) {
            return allocateIngredients(ingredients, index + 1, stock, allocation, missingOptions);
        }

        List<Item> options = java.util.Arrays.stream(ingredient.getItems())
                .map(ItemStack::getItem)
                .distinct()
                .sorted(Comparator.comparingInt((Item item) -> stock.getOrDefault(item, 0)).reversed()
                        .thenComparing(RiceCrafter::itemName))
                .toList();
        for (Item option : options) {
            int available = stock.getOrDefault(option, 0);
            if (available <= 0) {
                continue;
            }
            stock.put(option, available - 1);
            allocation.merge(option, 1, Integer::sum);
            if (allocateIngredients(ingredients, index + 1, stock, allocation, missingOptions)) {
                stock.put(option, available);
                return true;
            }
            stock.put(option, available);
            allocation.compute(option, (item, count) -> count != null && count == 1 ? null : count - 1);
        }
        if (missingOptions.isEmpty()) {
            missingOptions.add(options.isEmpty() ? "nieznanego składnika"
                    : options.stream().map(RiceCrafter::itemName).reduce((a, b) -> a + " / " + b).orElse("składników"));
        }
        return false;
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static boolean has(RiceManEntity unit, Item item) {
        SimpleContainer inv = unit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean takeOne(RiceManEntity unit, Item item) {
        SimpleContainer inv = unit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                inv.removeItem(i, 1);
                return true;
            }
        }
        return false;
    }

    public static void giveBack(RiceManEntity unit, ServerLevel level, Item item) {
        ItemStack leftover = unit.getInventory().addItem(new ItemStack(item));
        if (!leftover.isEmpty()) {
            Block.popResource(level, unit.blockPosition(), leftover);
        }
    }
}
