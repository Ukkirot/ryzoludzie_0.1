package com.ukkirot.ryzoludzie.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Jak ryżoludzie używają narzędzi. Narzędzie leży w ekwipunku, a do ręki trafia ta sama sztuka
 * (ten sam obiekt ItemStack), więc zużycie liczy się w jednym miejscu.
 * <p>
 * Czas niszczenia bloku liczony jest jak w grze (twardość bloku, szybkość narzędzia, czy to właściwe
 * narzędzie), a potem przyspieszany przez WORK_SPEED. Bez narzędzia praca trwa wielokrotnie dłużej,
 * a bloki wymagające narzędzia (np. kamień, rudy) nic nie wyrzucają.
 */
public final class ToolUse {
    /** Mnożnik tempa pracy względem gry. Zwiększ, żeby wszystko szło szybciej. */
    public static final float WORK_SPEED = 3.0F;
    private static final int MIN_BREAK_TICKS = 6;

    private ToolUse() {
    }

    /** Wkłada do ręki najlepsze narzędzie z ekwipunku do tego bloku (albo puste, gdy żadne nie pomaga). */
    public static void equipBestTool(RiceManEntity mob, BlockState state) {
        SimpleContainer inv = mob.getInventory();
        ItemStack best = ItemStack.EMPTY;
        float bestScore = 1.0F;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            boolean correct = s.isCorrectToolForDrops(state);
            float score = speed + (correct ? 100.0F : 0.0F);
            if ((speed > 1.0F || correct) && score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        if (mob.getMainHandItem() != best) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, best);
        }
    }

    /** Ile ticków trwa zniszczenie bloku aktualnym narzędziem. -1, gdy blok jest niezniszczalny. */
    public static int breakTicks(RiceManEntity mob, BlockState state, BlockGetter level, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return -1;
        }
        if (hardness == 0.0F) {
            return MIN_BREAK_TICKS;
        }
        ItemStack tool = mob.getMainHandItem();
        float speed = tool.getDestroySpeed(state);
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float perTick = speed / hardness / (correct ? 30.0F : 100.0F) * WORK_SPEED;
        return Math.max(MIN_BREAK_TICKS, (int) Math.ceil(1.0F / perTick));
    }

    /** Czy blok w ogóle coś wyrzuci przy aktualnym narzędziu (kamień bez kilofa nic nie daje). */
    public static boolean dropsAllowed(RiceManEntity mob, BlockState state) {
        return !state.requiresCorrectToolForDrops() || mob.getMainHandItem().isCorrectToolForDrops(state);
    }

    /** Zużywa narzędzie o 1 po zniszczonym bloku. */
    public static void damageTool(RiceManEntity mob) {
        ItemStack tool = mob.getMainHandItem();
        if (!tool.isEmpty() && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, mob, EquipmentSlot.MAINHAND);
        }
    }
}
