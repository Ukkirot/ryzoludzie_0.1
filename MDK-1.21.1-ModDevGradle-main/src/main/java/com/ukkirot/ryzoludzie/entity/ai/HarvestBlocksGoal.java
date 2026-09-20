package com.ukkirot.ryzoludzie.entity.ai;

import com.ukkirot.ryzoludzie.RyzoludzieMod;
import com.ukkirot.ryzoludzie.entity.HarvestArea;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Realizuje komendę HARVEST w zadanym obszarze (HarvestArea). Poza obszarem niczego nie rusza.
 * <p>
 * Kolejność pracy:
 * - drzewa: wybiera najbliższe drzewo i kończy je w całości, zanim przejdzie do następnego.
 * Dosięga tylko dolnych kłód (ok. 4-5 bloków nad ziemią). Przy fellTrees=true po przecięciu
 * pierwszej kłody reszta kłód tego drzewa (w obszarze) jest ścinana po kolei od góry,
 * co FELL_LOG_TICKS ticków, więc drzewo nie znika w jednej chwili;
 * - uprawy: zbiera dojrzałe i sadzi od nowa (za darmo, bez zużycia nasion);
 * - drop trafia od razu do ekwipunku jednostki.
 * <p>
 * Zabezpieczenia:
 * - naturalOnly=true: kłody tną się tylko, gdy drzewo (klaster połączonych kłód, razem z ukośnymi
 * sąsiadami) ma liście niepostawione przez gracza (persistent=false). Domy z kłód są bezpieczne;
 * - respektuje gamerule mobGriefing;
 * - cele, do których nie da się dojść, są po jakimś czasie pomijane.
 */
public class HarvestBlocksGoal extends Goal {
    private static final double REACH_SQR = 16.0D; // 4 bloki od oczu
    private static final int MAX_LOG_HEIGHT_ABOVE_BASE = 4; // dosięg z ziemi
    private static final int GIVE_UP_TICKS = 20 * 10;
    private static final int LOG_BREAK_TICKS = 25;
    /**
     * Ile ticków zajmuje ścięcie każdej kolejnej kłody drzewa po pierwszej (20 ticków = 1 sekunda).
     * Chcesz, żeby drzewo padało wolniej, zwiększ; szybciej, zmniejsz.
     */
    private static final int FELL_LOG_TICKS = 12;
    private static final int CROP_BREAK_TICKS = 8;
    private static final int TREE_SCAN_LIMIT = 300;

    private final RiceManEntity mob;
    private final double speedModifier;

    /** Pozycje (BlockPos.asLong) pominięte w tym przebiegu: nie-drzewa, nieosiągalne itp. */
    private final Set<Long> ignored = new HashSet<>();
    /** Kandydaci z ostatniego skanu obszaru. */
    private final List<BlockPos> cache = new ArrayList<>();
    /** Kłody drzewa, nad którym aktualnie pracujemy (cały klaster, także poza obszarem). */
    private final Set<Long> currentTree = new HashSet<>();
    private int treeBaseY;

    /** Kłody ściętego drzewa czekające na ścięcie (od góry). */
    private final Deque<BlockPos> fellQueue = new ArrayDeque<>();
    @Nullable
    private BlockPos fellTarget;
    private int fellTicks;

    @Nullable
    private BlockPos target;
    private int ticksOnTarget;
    private int breakTicks;
    private int repathCooldown;

    public HarvestBlocksGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.HARVEST && mob.getHarvestArea() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        target = null;
        ignored.clear();
        cache.clear();
        currentTree.clear();
        fellQueue.clear();
        fellTarget = null;
        fellTicks = 0;
        ticksOnTarget = 0;
        breakTicks = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        resetProgress(mob.level());
        if (fellTarget != null) {
            mob.level().destroyBlockProgress(mob.getId(), fellTarget, -1);
        }
        fellTarget = null;
        fellQueue.clear();
        target = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        HarvestArea area = mob.getHarvestArea();
        if (area == null) {
            mob.finishCommand();
            return;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            RyzoludzieMod.LOGGER.warn("[Ryzoludzie] HARVEST przerwany: gamerule mobGriefing jest wylaczony");
            mob.finishCommand();
            return;
        }

        // Trwa wycinanie reszty drzewa: stoimy przy pniu i ścinamy kłoda po kłodzie.
        if (fellTarget != null || !fellQueue.isEmpty()) {
            tickFelling(level, area);
            return;
        }

        if (target == null || !isHarvestable(level, area, target)) {
            resetProgress(level);
            target = findTarget(level, area);
            ticksOnTarget = 0;
            if (target == null) {
                mob.finishCommand(); // w obszarze nic wiecej do zrobienia
                return;
            }
        }

        if (++ticksOnTarget > GIVE_UP_TICKS) {
            ignoreTarget(level);
            return;
        }

        Vec3 center = Vec3.atCenterOf(target);
        if (mob.getEyePosition().distanceToSqr(center) <= REACH_SQR) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(center.x, center.y, center.z);
            chop(level, area);
        } else {
            if (breakTicks > 0) {
                resetProgress(level); // odeszlismy od bloku w trakcie
            }
            if (--repathCooldown <= 0) {
                repathCooldown = 10;
                Path path = mob.getNavigation().createPath(target, 1);
                if (path == null) {
                    ignoreTarget(level); // nie da sie dojsc
                    return;
                }
                mob.getNavigation().moveTo(path, speedModifier);
            }
        }
    }

    // ---------------------------------------------------------------- ciecie

    private void chop(ServerLevel level, HarvestArea area) {
        BlockPos pos = target;
        if (pos == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        int required = state.is(BlockTags.LOGS) ? LOG_BREAK_TICKS : CROP_BREAK_TICKS;

        breakTicks++;
        if (breakTicks % 6 == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(mob.getId(), pos, Math.min(9, breakTicks * 10 / required));

        if (breakTicks >= required) {
            harvest(level, area, pos, state);
        }
    }

    private void harvest(ServerLevel level, HarvestArea area, BlockPos pos, BlockState state) {
        level.destroyBlockProgress(mob.getId(), pos, -1);
        boolean overflow = breakAndCollect(level, pos, state);

        // Ścięte drzewo: reszta kłód (w obszarze) będzie ścinana po kolei od góry, w tickFelling().
        if (!overflow && mob.isFellTrees() && state.is(BlockTags.LOGS)) {
            List<BlockPos> rest = new ArrayList<>();
            for (long key : currentTree) {
                BlockPos lp = BlockPos.of(key);
                if (area.contains(lp) && level.isLoaded(lp) && level.getBlockState(lp).is(BlockTags.LOGS)) {
                    rest.add(lp);
                }
            }
            rest.sort(Comparator.comparingInt((BlockPos lp) -> lp.getY()).reversed());
            fellQueue.addAll(rest);
            fellTarget = null;
            fellTicks = 0;
            mob.getNavigation().stop();
        }

        target = null;
        breakTicks = 0;
        if (overflow) {
            mob.finishCommand(); // ekwipunek pelny
        }
    }

    /** Ścina jedną kłodę z kolejki co FELL_LOG_TICKS ticków, z animacją pękania i machaniem ręką. */
    private void tickFelling(ServerLevel level, HarvestArea area) {
        if (fellTarget == null) {
            fellTarget = nextFellLog(level, area);
            fellTicks = 0;
            if (fellTarget == null) {
                return; // kolejka pusta, od następnego ticka wracamy do zwykłej pracy
            }
        }

        Vec3 center = Vec3.atCenterOf(fellTarget);
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(center.x, center.y, center.z);

        fellTicks++;
        if (fellTicks % 6 == 1) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(mob.getId(), fellTarget, Math.min(9, fellTicks * 10 / FELL_LOG_TICKS));

        if (fellTicks >= FELL_LOG_TICKS) {
            BlockPos pos = fellTarget;
            level.destroyBlockProgress(mob.getId(), pos, -1);
            fellTarget = null;
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS) && breakAndCollect(level, pos, state)) {
                fellQueue.clear();
                mob.finishCommand(); // ekwipunek pelny
            }
        }
    }

    /** Następna kłoda z kolejki, która nadal jest kłodą w obszarze. */
    @Nullable
    private BlockPos nextFellLog(ServerLevel level, HarvestArea area) {
        BlockPos p;
        while ((p = fellQueue.pollFirst()) != null) {
            if (area.contains(p) && level.isLoaded(p) && level.getBlockState(p).is(BlockTags.LOGS)) {
                return p;
            }
        }
        return null;
    }

    /** Niszczy blok, dodaje drop do ekwipunku, uprawy sadzi od nowa. Zwraca true, gdy zabrakło miejsca. */
    private boolean breakAndCollect(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), mob, ItemStack.EMPTY);
        level.destroyBlock(pos, false, mob);
        if (state.getBlock() instanceof CropBlock crop) {
            level.setBlock(pos, crop.getStateForAge(0), Block.UPDATE_ALL); // ponowne zasadzenie
        }
        boolean overflow = false;
        for (ItemStack drop : drops) {
            ItemStack left = mob.getInventory().addItem(drop);
            if (!left.isEmpty()) {
                Block.popResource(level, pos, left);
                overflow = true;
            }
        }
        return overflow;
    }

    private void resetProgress(Level level) {
        if (target != null) {
            level.destroyBlockProgress(mob.getId(), target, -1);
        }
        breakTicks = 0;
    }

    private void ignoreTarget(ServerLevel level) {
        resetProgress(level);
        if (target != null) {
            ignored.add(target.asLong());
        }
        target = null;
    }

    // ---------------------------------------------------------------- wybór celu

    private boolean isHarvestable(Level level, HarvestArea area, BlockPos pos) {
        if (!area.contains(pos) || !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        RiceManEntity.HarvestMode mode = mob.getHarvestMode();
        return (mode.allowsLogs() && state.is(BlockTags.LOGS))
                || (mode.allowsCrops() && isMatureCrop(state));
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    @Nullable
    private BlockPos findTarget(ServerLevel level, HarvestArea area) {
        BlockPos mobPos = mob.blockPosition();

        // 1) najpierw dokończ bieżące drzewo
        BlockPos inTree = findInCurrentTree(level, area, mobPos);
        if (inTree != null) {
            return inTree;
        }
        finishTree();

        // 2) potem następny cel z obszaru (przy pustym albo nieaktualnym cache skanujemy od nowa)
        boolean fresh = false;
        if (cache.isEmpty()) {
            rescan(level, area);
            fresh = true;
        }
        BlockPos next = pickFromCache(level, area, mobPos);
        if (next == null && !fresh) {
            rescan(level, area);
            next = pickFromCache(level, area, mobPos);
        }
        return next;
    }

    private void rescan(ServerLevel level, HarvestArea area) {
        cache.clear();
        for (BlockPos p : BlockPos.betweenClosed(
                area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ())) {
            if (!ignored.contains(p.asLong()) && isHarvestable(level, area, p)) {
                cache.add(p.immutable());
            }
        }
    }

    /** Bierze z cache najbliższy cel. Dla kłody ustawia bieżące drzewo i wybiera pierwszą kłodę do cięcia. */
    @Nullable
    private BlockPos pickFromCache(ServerLevel level, HarvestArea area, BlockPos mobPos) {
        while (!cache.isEmpty()) {
            int bestIdx = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < cache.size(); i++) {
                double d = cache.get(i).distSqr(mobPos);
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
            BlockPos p = cache.remove(bestIdx);
            if (ignored.contains(p.asLong()) || !isHarvestable(level, area, p)) {
                continue;
            }

            if (!level.getBlockState(p).is(BlockTags.LOGS)) {
                return p; // dojrzała uprawa
            }

            Set<Long> cluster = new HashSet<>();
            boolean hasNaturalLeaves = scanTree(level, p, cluster);
            if (mob.isNaturalOnly() && !hasNaturalLeaves) {
                ignored.addAll(cluster); // to nie drzewo, tylko konstrukcja albo pniak
                continue;
            }

            currentTree.clear();
            currentTree.addAll(cluster);
            int baseY = Integer.MAX_VALUE;
            for (long key : cluster) {
                baseY = Math.min(baseY, BlockPos.of(key).getY());
            }
            treeBaseY = baseY;

            BlockPos start = findInCurrentTree(level, area, mobPos);
            if (start != null) {
                return start;
            }
            finishTree(); // całe drzewo poza obszarem albo poza zasięgiem
        }
        return null;
    }

    /** Najbliższa dosięgalna kłoda bieżącego drzewa, leżąca w obszarze. */
    @Nullable
    private BlockPos findInCurrentTree(ServerLevel level, HarvestArea area, BlockPos mobPos) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (long key : currentTree) {
            if (ignored.contains(key)) {
                continue;
            }
            BlockPos p = BlockPos.of(key);
            if (p.getY() > treeBaseY + MAX_LOG_HEIGHT_ABOVE_BASE) {
                continue; // za wysoko, żeby dosięgnąć z ziemi
            }
            if (!area.contains(p) || !level.isLoaded(p) || !level.getBlockState(p).is(BlockTags.LOGS)) {
                continue;
            }
            double d = p.distSqr(mobPos);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Drzewo skończone: pozostałe kłody (niedosięgalne, poza obszarem) nie będą więcej wybierane. */
    private void finishTree() {
        if (!currentTree.isEmpty()) {
            ignored.addAll(currentTree);
            currentTree.clear();
        }
    }

    /**
     * Zbiera klaster połączonych kłód (razem z ukośnymi sąsiadami, do TREE_SCAN_LIMIT) do visited.
     * Zwraca true, gdy któraś kłoda klastra sąsiaduje z liściem persistent=false (naturalne drzewo).
     */
    private boolean scanTree(ServerLevel level, BlockPos start, Set<Long> visited) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start.asLong());
        boolean hasNaturalLeaves = false;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();

            if (!hasNaturalLeaves) {
                for (Direction dir : Direction.values()) {
                    BlockPos n = cur.relative(dir);
                    if (level.isLoaded(n)) {
                        BlockState ns = level.getBlockState(n);
                        if (ns.getBlock() instanceof LeavesBlock && !ns.getValue(LeavesBlock.PERSISTENT)) {
                            hasNaturalLeaves = true;
                            break;
                        }
                    }
                }
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = cur.offset(dx, dy, dz);
                        if (visited.size() >= TREE_SCAN_LIMIT
                                || visited.contains(n.asLong())
                                || !level.isLoaded(n)) {
                            continue;
                        }
                        if (level.getBlockState(n).is(BlockTags.LOGS)) {
                            visited.add(n.asLong());
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return hasNaturalLeaves;
    }
}
