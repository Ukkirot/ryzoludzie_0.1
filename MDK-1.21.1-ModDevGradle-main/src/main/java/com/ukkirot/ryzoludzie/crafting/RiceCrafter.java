package com.ukkirot.ryzoludzie.crafting;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafter ryżoludzi. Korzysta z zwykłych receptur gry (RecipeManager), więc rozumie też receptury z modów.
 * <p>
 * plan(): z tego, co jednostka ma w ekwipunku, układa łańcuch kroków prowadzący do celu, np. skrzynia to
 * kłody, deski, stół rzemieślniczy (jeśli żaden nie stoi w pobliżu), a na końcu skrzynia. Jeśli czegoś
 * zabraknie, zwraca, czego dokładnie.
 * craft(): wykonuje jeden krok, czyli zabiera składniki z ekwipunku i dodaje wynik.
 * <p>
 * Przepisy 2x2 robi się z ręki, a większe wymagają stołu rzemieślniczego w pobliżu.
 */
public final class RiceCrafter {
    public static final int TABLE_SEARCH_RADIUS = 16;
    private static final int MAX_DEPTH = 5;

    private RiceCrafter() {
    }

    /** Jeden krok planu: wytworzenie według receptury albo postawienie stołu rzemieślniczego. */
    public record Step(@Nullable RecipeHolder<CraftingRecipe> recipe, boolean placeTable) {
        static Step craft(RecipeHolder<CraftingRecipe> recipe) {
            return new Step(recipe, false);
        }

        static Step table() {
            return new Step(null, true);
        }
    }

    public static final class Plan {
        public final List<Step> steps = new ArrayList<>();
        public boolean ok;
        /** Gdy plan się nie udał: pierwszy brakujący przedmiot bazowy (np. kłody). */
        @Nullable
        public Item missing;
    }

    // ---------------------------------------------------------------- planowanie

    public static Plan plan(RiceManEntity unit, ServerLevel level, Item target, int count) {
        Plan plan = new Plan();
        boolean tableExists = findTable(level, unit.blockPosition(), TABLE_SEARCH_RADIUS) != null;
        Ctx ctx = new Ctx(level, plan, tableExists);
        Map<Item, Integer> stock = stockOf(unit.getInventory());
        plan.ok = need(ctx, stock, target, count, 0);
        if (plan.ok) {
            plan.missing = null;
        } else {
            plan.steps.clear();
            plan.missing = ctx.missing;
        }
        return plan;
    }

    private static final class Ctx {
        final ServerLevel level;
        final Plan plan;
        final boolean tableExists;
        final Map<Item, List<RecipeHolder<CraftingRecipe>>> recipeCache = new HashMap<>();
        @Nullable
        Item missing;

        Ctx(ServerLevel level, Plan plan, boolean tableExists) {
            this.level = level;
            this.plan = plan;
            this.tableExists = tableExists;
        }

        boolean tableReady() {
            if (tableExists) {
                return true;
            }
            for (Step s : plan.steps) {
                if (s.placeTable()) {
                    return true;
                }
            }
            return false;
        }

        void noteMissing(@Nullable Item item) {
            if (missing == null) {
                missing = item;
            }
        }
    }

    /** Zapewnia w wirtualnym magazynie co najmniej count sztuk przedmiotu, dopisując kroki do planu. */
    private static boolean need(Ctx c, Map<Item, Integer> stock, Item item, int count, int depth) {
        int have = stock.getOrDefault(item, 0);
        if (have >= count) {
            return true;
        }
        List<RecipeHolder<CraftingRecipe>> recipes = depth > MAX_DEPTH ? List.of() : recipesFor(c, item);
        if (recipes.isEmpty()) {
            c.noteMissing(item);
            return false;
        }
        int missing = count - have;
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            int mark = c.plan.steps.size();
            Map<Item, Integer> trial = new HashMap<>(stock);
            int perCraft = Math.max(1, holder.value().getResultItem(c.level.registryAccess()).getCount());
            int times = (missing + perCraft - 1) / perCraft;
            boolean ok = true;
            for (int t = 0; t < times && ok; t++) {
                ok = craftOnce(c, trial, holder, depth);
            }
            if (ok) {
                stock.clear();
                stock.putAll(trial);
                return true;
            }
            while (c.plan.steps.size() > mark) { // wycofaj kroki nieudanej próby
                c.plan.steps.remove(c.plan.steps.size() - 1);
            }
        }
        return false;
    }

    private static boolean craftOnce(Ctx c, Map<Item, Integer> trial, RecipeHolder<CraftingRecipe> holder, int depth) {
        CraftingRecipe recipe = holder.value();
        if (needsTable(recipe) && !c.tableReady()) {
            if (!need(c, trial, Items.CRAFTING_TABLE, 1, depth + 1)) {
                return false;
            }
            trial.merge(Items.CRAFTING_TABLE, -1, Integer::sum);
            c.plan.steps.add(Step.table());
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (!satisfy(c, trial, ingredient, depth)) {
                return false;
            }
        }
        c.plan.steps.add(Step.craft(holder));
        ItemStack out = recipe.getResultItem(c.level.registryAccess());
        trial.merge(out.getItem(), out.getCount(), Integer::sum);
        return true;
    }

    /** Składnik: najpierw bierzemy to, co już jest, a jeśli nie ma, próbujemy wytworzyć któryś pasujący przedmiot. */
    private static boolean satisfy(Ctx c, Map<Item, Integer> trial, Ingredient ingredient, int depth) {
        ItemStack[] options = ingredient.getItems();
        for (ItemStack option : options) {
            if (trial.getOrDefault(option.getItem(), 0) > 0) {
                trial.merge(option.getItem(), -1, Integer::sum);
                return true;
            }
        }
        for (ItemStack option : options) {
            Item item = option.getItem();
            Map<Item, Integer> copy = new HashMap<>(trial);
            if (need(c, copy, item, 1, depth + 1)) {
                copy.merge(item, -1, Integer::sum);
                trial.clear();
                trial.putAll(copy);
                return true;
            }
        }
        if (options.length > 0) {
            c.noteMissing(options[0].getItem());
        }
        return false;
    }

    private static List<RecipeHolder<CraftingRecipe>> recipesFor(Ctx c, Item item) {
        return c.recipeCache.computeIfAbsent(item, it -> {
            List<RecipeHolder<CraftingRecipe>> out = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> holder : c.level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe = holder.value();
                if (recipe.isSpecial()) {
                    continue; // receptury dynamiczne (barwienie, fajerwerki itp.)
                }
                ItemStack result = recipe.getResultItem(c.level.registryAccess());
                if (!result.isEmpty() && result.is(it)) {
                    out.add(holder);
                }
            }
            out.sort(Comparator.comparingInt(h -> ingredientCount(h.value())));
            return out;
        });
    }

    private static int ingredientCount(CraftingRecipe recipe) {
        int n = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** Receptury większe niż 2x2 wymagają stołu rzemieślniczego. */
    public static boolean needsTable(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return ingredientCount(recipe) > 4;
    }

    // ---------------------------------------------------------------- wykonanie

    /** Wykonuje jeden krok: zabiera składniki z ekwipunku i dodaje wynik. Zwraca false, gdy czegoś brakuje. */
    public static boolean craft(RiceManEntity unit, ServerLevel level, RecipeHolder<CraftingRecipe> holder) {
        CraftingRecipe recipe = holder.value();
        SimpleContainer inv = unit.getInventory();
        int[] used = new int[inv.getContainerSize()];
        List<Integer> slots = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            int found = -1;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getCount() - used[i] > 0 && ingredient.test(s)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            used[found]++;
            slots.add(found);
        }
        for (int slot : slots) {
            inv.removeItem(slot, 1);
        }
        ItemStack left = inv.addItem(recipe.getResultItem(level.registryAccess()).copy());
        if (!left.isEmpty()) {
            Block.popResource(level, unit.blockPosition(), left);
        }
        return true;
    }

    /** Najbliższy stół rzemieślniczy w promieniu radius (i +-4 bloki w pionie) albo null. */
    @Nullable
    public static BlockPos findTable(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 4, radius))) {
            if (!level.isLoaded(p) || !level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                continue;
            }
            double d = p.distSqr(origin);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- ekwipunek

    public static boolean has(RiceManEntity unit, Item item) {
        SimpleContainer inv = unit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                return true;
            }
        }
        return false;
    }

    /** Zabiera jedną sztukę przedmiotu z ekwipunku. */
    public static boolean takeOne(RiceManEntity unit, Item item) {
        SimpleContainer inv = unit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) {
                inv.removeItem(i, 1);
                return true;
            }
        }
        return false;
    }

    public static void giveBack(RiceManEntity unit, ServerLevel level, Item item) {
        ItemStack left = unit.getInventory().addItem(new ItemStack(item));
        if (!left.isEmpty()) {
            Block.popResource(level, unit.blockPosition(), left);
        }
    }

    private static Map<Item, Integer> stockOf(SimpleContainer inv) {
        Map<Item, Integer> stock = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                stock.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return stock;
    }

    /** Opis kroku do odpowiedzi mostu i dziennika. */
    public static String describe(Step step, ServerLevel level) {
        if (step.placeTable()) {
            return "place minecraft:crafting_table";
        }
        ItemStack result = step.recipe().value().getResultItem(level.registryAccess());
        return "craft " + BuiltInRegistries.ITEM.getKey(result.getItem())
                + (result.getCount() > 1 ? " x" + result.getCount() : "");
    }
}
