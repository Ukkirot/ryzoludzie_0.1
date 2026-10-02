package com.ukkirot.ryzoludzie.entity.ai;

import com.google.gson.JsonObject;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Patrols one chunk for about a minute, then reports ore and wood counts by block type. */
public class ScoutGoal extends Goal {
    private static final int SCOUT_TICKS = 20 * 60;
    private static final int MAX_WAYPOINT_RETRIES = 5;

    private final RiceManEntity mob;
    private final double speedModifier;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int elapsedTicks;
    private int waypointIndex;
    private int waypointRetries;
    @Nullable
    private BlockPos chunkCenter;
    @Nullable
    private BlockPos currentWaypoint;

    public ScoutGoal(RiceManEntity mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getCommand() == RiceManEntity.Command.SCOUT && mob.getCommandPos() != null;
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
        elapsedTicks = 0;
        waypointIndex = 0;
        waypointRetries = 0;
        waypoints.clear();
        chunkCenter = null;
        currentWaypoint = null;
        mob.getNavigation().stop();

        if (!(mob.level() instanceof ServerLevel level)) {
            mob.failCommand("INVALID_WORLD", "SCOUT działa tylko na serwerze");
            return;
        }
        BlockPos target = mob.getCommandPos();
        if (target == null) {
            mob.failCommand("INVALID_COMMAND", "brak wskazanego chunku dla SCOUT");
            return;
        }
        int chunkX = target.getX() >> 4;
        int chunkZ = target.getZ() >> 4;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        if (!level.hasChunkAt(new BlockPos(minX, level.getMinBuildHeight(), minZ))) {
            mob.failCommand("CHUNK_NOT_LOADED", "chunk " + chunkX + ", " + chunkZ + " nie jest załadowany");
            return;
        }
        chunkCenter = new BlockPos(minX + 8, target.getY(), minZ + 8);
        addWaypoint(level, minX + 2, minZ + 2);
        addWaypoint(level, minX + 13, minZ + 2);
        addWaypoint(level, minX + 13, minZ + 13);
        addWaypoint(level, minX + 2, minZ + 13);
        addWaypoint(level, minX + 8, minZ + 8);
        waypoints.sort(Comparator.comparingDouble((BlockPos pos) -> mob.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        if (waypoints.isEmpty()) {
            mob.failCommand("NO_SCOUT_ROUTE", "nie udało się wyznaczyć trasy w chunku");
            return;
        }
        setWaypoint(0);
        mob.setNotice("info", "SCOUT: rozpoczęto zwiad chunku " + chunkX + ", " + chunkZ
                + " (około 60 sekund)");
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || chunkCenter == null) {
            return;
        }
        if (++elapsedTicks >= SCOUT_TICKS) {
            report(level);
            return;
        }

        BlockPos waypoint = currentWaypoint;
        if (waypoint == null) {
            mob.failCommand("NO_SCOUT_ROUTE", "trasa zwiadu nie jest dostępna");
            return;
        }
        if (mob.distanceToSqr(Vec3.atBottomCenterOf(waypoint)) <= 4.0D) {
            waypointIndex = (waypointIndex + 1) % waypoints.size();
            waypointRetries = 0;
            setWaypoint(waypointIndex);
            return;
        }
        if (!mob.getNavigation().isDone()) {
            return;
        }
        if (++waypointRetries > MAX_WAYPOINT_RETRIES) {
            mob.failCommand("SCOUT_ROUTE_BLOCKED", "nie udało się przejść do punktu zwiadu "
                    + waypoint.getX() + ", " + waypoint.getZ());
            return;
        }
        Path path = mob.getNavigation().createPath(waypoint, 0);
        if (path == null) {
            mob.failCommand("SCOUT_ROUTE_BLOCKED", "brak drogi do punktu zwiadu "
                    + waypoint.getX() + ", " + waypoint.getZ());
            return;
        }
        mob.getNavigation().moveTo(path, speedModifier);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        waypoints.clear();
        chunkCenter = null;
        currentWaypoint = null;
    }

    private void addWaypoint(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        waypoints.add(new BlockPos(x, y, z));
    }

    private void setWaypoint(int index) {
        BlockPos waypoint = waypoints.get(index);
        if (waypoint == null) {
            mob.failCommand("NO_SCOUT_ROUTE", "trasa zwiadu nie jest dostępna");
            return;
        }
        currentWaypoint = waypoint;
        mob.getNavigation().moveTo(waypoint.getX() + 0.5D, waypoint.getY(),
                waypoint.getZ() + 0.5D, speedModifier);
    }

    private void report(ServerLevel level) {
        BlockPos center = chunkCenter;
        if (center == null) {
            mob.failCommand("NO_SCOUT_ROUTE", "nie można ustalić badanego chunku");
            return;
        }
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        Map<String, Integer> oreCounts = new TreeMap<>();
        Map<String, Integer> woodCounts = new TreeMap<>();
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (blockId.endsWith("_ore")) {
                        oreCounts.merge(blockId, 1, Integer::sum);
                    }
                    if (state.is(BlockTags.LOGS)) {
                        woodCounts.merge(blockId, 1, Integer::sum);
                    }
                }
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("type", "SCOUT_REPORT");
        report.addProperty("chunkX", chunkX);
        report.addProperty("chunkZ", chunkZ);
        report.addProperty("totalOres", addCounts(report, "ores", oreCounts));
        report.addProperty("totalWood", addCounts(report, "wood", woodCounts));
        mob.finishCommandWithMessage("SCOUT_REPORT:" + report);
    }

    private static int addCounts(JsonObject report, String field, Map<String, Integer> counts) {
        JsonObject values = new JsonObject();
        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            values.addProperty(entry.getKey(), entry.getValue());
            total += entry.getValue();
        }
        report.add(field, values);
        return total;
    }
}
