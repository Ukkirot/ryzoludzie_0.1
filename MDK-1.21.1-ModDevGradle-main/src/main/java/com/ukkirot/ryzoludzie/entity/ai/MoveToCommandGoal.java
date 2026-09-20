package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** Idzie do pozycji z komendy MOVE. Kończy komendę po dojściu albo gdy nie da się dojść. */
public class MoveToCommandGoal extends Goal {
    private static final int MAX_RETRIES = 5;
    private static final double ARRIVED_DISTANCE_SQR = 2.25D; // 1.5 bloku

    private final RiceManEntity mob;
    private final double speedModifier;
    private int retries;
    @Nullable
    private BlockPos lastTarget;

    public MoveToCommandGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.MOVE && mob.getCommandPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        retries = 0;
        lastTarget = mob.getCommandPos();
        moveToTarget();
    }

    @Override
    public void tick() {
        BlockPos pos = mob.getCommandPos();
        if (pos == null) {
            return;
        }
        // Dostaliśmy nową komendę MOVE w trakcie starej.
        if (!pos.equals(lastTarget)) {
            lastTarget = pos;
            retries = 0;
            moveToTarget();
            return;
        }
        if (mob.distanceToSqr(Vec3.atBottomCenterOf(pos)) < ARRIVED_DISTANCE_SQR) {
            mob.getNavigation().stop();
            mob.finishCommand();
            return;
        }
        if (mob.getNavigation().isDone()) {
            if (++retries > MAX_RETRIES) {
                mob.finishCommand(); // nieosiągalne
                return;
            }
            moveToTarget();
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    private void moveToTarget() {
        BlockPos p = mob.getCommandPos();
        if (p != null) {
            mob.getNavigation().moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, speedModifier);
        }
    }
}
