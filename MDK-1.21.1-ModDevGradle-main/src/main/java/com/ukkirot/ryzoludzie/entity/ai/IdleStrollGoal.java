package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

/** Losowe spacerowanie, ale wyłącznie w stanie IDLE, żeby nie walczyć z komendami. */
public class IdleStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final RiceManEntity riceMan;

    public IdleStrollGoal(RiceManEntity mob, double speedModifier) {
        super(mob, speedModifier);
        this.riceMan = mob;
    }

    @Override
    public boolean canUse() {
        return riceMan.getCommand() == RiceManEntity.Command.IDLE && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return riceMan.getCommand() == RiceManEntity.Command.IDLE && super.canContinueToUse();
    }
}
