package com.ukkirot.ryzoludzie.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.ukkirot.ryzoludzie.entity.HarvestArea;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import com.ukkirot.ryzoludzie.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/**
 * Tłumaczy komendy JSON z mostu WebSocket na wywołania encji.
 * <p>
 * WAŻNE: handle() musi być wołane na wątku serwera, czyli w środku server.execute(...).
 * Wywołane z wątku sieciowego zwróci błąd zamiast dotykać świata.
 * <p>
 * Obsługiwane komendy (pole "type"; opcjonalne "requestId" jest odsyłane w odpowiedzi):
 * <pre>
 * {"type":"GET_STATE"}
 * {"type":"SPAWN","x":100,"y":70,"z":100}                        (overworld)
 * {"type":"MOVE","unit":"&lt;uuid&gt;","x":..,"y":..,"z":..}
 * {"type":"ATTACK","unit":"&lt;uuid&gt;","target":"&lt;uuid | nick gracza | nearest:minecraft:pig&gt;"}
 * {"type":"GATHER","unit":"&lt;uuid&gt;"[,"x":..,"y":..,"z":..]}
 * {"type":"HARVEST","unit":"&lt;uuid&gt;","chunkX":..,"chunkZ":..}                (cały chunk)
 * {"type":"HARVEST","unit":"&lt;uuid&gt;","x1":..,"z1":..,"x2":..,"z2":..}       (prostokąt; opcjonalnie "y1","y2")
 * {"type":"HARVEST","unit":"&lt;uuid&gt;"[,"x":..,"y":..,"z":..][,"radius":12]}  (kwadrat wokół punktu / jednostki)
 *   opcje: "what":"logs|crops|all", "natural_only":true, "fell":true
 *   obszar maks. 64x64 bloków; bez "y1","y2" zakres wysokości liczy się z mapy terenu (chunki muszą być załadowane)
 * {"type":"STOP","unit":"&lt;uuid&gt;"}
 * </pre>
 */
public final class RiceManBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAREST_SEARCH_RADIUS = 32.0D;

    private RiceManBridge() {
    }

    public static JsonObject handle(MinecraftServer server, JsonObject msg) {
        JsonObject response;
        if (!server.isSameThread()) {
            LOGGER.error("[RiceManBridge] handle() called off the server thread: {}", Thread.currentThread().getName());
            response = error("Internal error: command executed off the server thread");
        } else {
            try {
                String type = requireString(msg, "type").toUpperCase(Locale.ROOT);
                response = switch (type) {
                    case "GET_STATE" -> getState(server);
                    case "SPAWN" -> spawn(server, msg);
                    case "MOVE" -> move(server, msg);
                    case "ATTACK" -> attack(server, msg);
                    case "GATHER" -> gather(server, msg);
                    case "HARVEST" -> harvest(server, msg);
                    case "STOP" -> stop(server, msg);
                    default -> throw new IllegalArgumentException("Unknown command type: " + type);
                };
            } catch (IllegalArgumentException e) {
                response = error(e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.warn("[RiceManBridge] failed to handle message {}", msg, e);
                response = error("Invalid message: " + e);
            }
        }
        if (msg.has("requestId")) {
            response.add("requestId", msg.get("requestId"));
        }
        return response;
    }

    // ---------------------------------------------------------------- komendy

    private static JsonObject getState(MinecraftServer server) {
        JsonArray units = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof RiceManEntity r && r.isAlive()) {
                    units.add(describe(r));
                }
            }
        }
        JsonObject res = ok("STATE");
        res.addProperty("gameTime", server.overworld().getGameTime());
        res.add("units", units);
        return res;
    }

    private static JsonObject spawn(MinecraftServer server, JsonObject msg) {
        ServerLevel level = server.overworld();
        BlockPos pos = requirePos(msg);
        RiceManEntity unit = ModEntities.RICEMAN.get().create(level);
        if (unit == null) {
            throw new IllegalArgumentException("Could not create riceman");
        }
        unit.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        unit.setPersistenceRequired();
        level.addFreshEntity(unit);
        return ack("SPAWN", unit);
    }

    private static JsonObject move(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        unit.commandMoveTo(requirePos(msg));
        return ack("MOVE", unit);
    }

    private static JsonObject attack(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        LivingEntity target = resolveTarget(server, unit, requireString(msg, "target"));
        unit.commandAttack(target);
        return ack("ATTACK", unit);
    }

    private static JsonObject gather(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        unit.commandGather(hasPos(msg) ? requirePos(msg) : null);
        return ack("GATHER", unit);
    }

    private static final int DEFAULT_RADIUS = 12;
    private static final int MAX_RADIUS = 32;
    private static final int MAX_AREA_SIDE = 64;

    private static JsonObject harvest(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        RiceManEntity.HarvestMode mode = parseHarvestMode(msg);
        boolean naturalOnly = optionalBoolean(msg, "natural_only", true);
        boolean fell = optionalBoolean(msg, "fell", true);
        HarvestArea area = parseArea(unit, msg);

        unit.commandHarvest(area, mode, naturalOnly, fell);

        JsonObject res = ack("HARVEST", unit);
        res.add("area", areaJson(area));
        return res;
    }

    /** Chunk (chunkX/chunkZ), prostokąt (x1,z1,x2,z2) albo kwadrat wokół punktu (x,y,z,radius). */
    private static HarvestArea parseArea(RiceManEntity unit, JsonObject msg) {
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }

        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (msg.has("chunkX") || msg.has("chunkZ")) {
            int cx = requireInt(msg, "chunkX");
            int cz = requireInt(msg, "chunkZ");
            minX = cx << 4;
            maxX = minX + 15;
            minZ = cz << 4;
            maxZ = minZ + 15;
        } else if (msg.has("x1") || msg.has("z1") || msg.has("x2") || msg.has("z2")) {
            int ax = requireInt(msg, "x1");
            int az = requireInt(msg, "z1");
            int bx = requireInt(msg, "x2");
            int bz = requireInt(msg, "z2");
            minX = Math.min(ax, bx);
            maxX = Math.max(ax, bx);
            minZ = Math.min(az, bz);
            maxZ = Math.max(az, bz);
        } else {
            BlockPos c = hasPos(msg) ? requirePos(msg) : unit.blockPosition();
            int r = msg.has("radius") ? requireInt(msg, "radius") : DEFAULT_RADIUS;
            r = Math.max(2, Math.min(r, MAX_RADIUS));
            return HarvestArea.of(c.getX() - r, c.getY() - 6, c.getZ() - r,
                    c.getX() + r, c.getY() + 12, c.getZ() + r);
        }

        if (maxX - minX + 1 > MAX_AREA_SIDE || maxZ - minZ + 1 > MAX_AREA_SIDE) {
            throw new IllegalArgumentException("Area too large (max " + MAX_AREA_SIDE + "x" + MAX_AREA_SIDE + " blocks)");
        }

        int minY;
        int maxY;
        if (msg.has("y1") && msg.has("y2")) {
            int a = requireInt(msg, "y1");
            int b = requireInt(msg, "y2");
            minY = Math.min(a, b);
            maxY = Math.max(a, b);
        } else {
            // Zakres wysokości z mapy terenu: od najniższego gruntu do czubków najwyższych pni.
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    if (!level.isLoaded(new BlockPos(cx << 4, 0, cz << 4))) {
                        throw new IllegalArgumentException("Area is not loaded (chunk " + cx + ", " + cz + ")");
                    }
                }
            }
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    lo = Math.min(lo, h);
                    hi = Math.max(hi, h);
                }
            }
            minY = lo - 3;
            maxY = hi + 1;
        }
        return HarvestArea.of(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static JsonObject areaJson(HarvestArea a) {
        JsonObject o = new JsonObject();
        o.addProperty("x1", a.minX());
        o.addProperty("y1", a.minY());
        o.addProperty("z1", a.minZ());
        o.addProperty("x2", a.maxX());
        o.addProperty("y2", a.maxY());
        o.addProperty("z2", a.maxZ());
        return o;
    }

    private static boolean optionalBoolean(JsonObject o, String key, boolean defaultValue) {
        if (!o.has(key)) {
            return defaultValue;
        }
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Field '" + key + "' must be true or false");
        }
        return el.getAsBoolean();
    }

    private static RiceManEntity.HarvestMode parseHarvestMode(JsonObject msg) {
        String what = msg.has("what") ? requireString(msg, "what").toLowerCase(Locale.ROOT) : "all";
        return switch (what) {
            case "logs", "wood", "trees" -> RiceManEntity.HarvestMode.LOGS;
            case "crops", "farm" -> RiceManEntity.HarvestMode.CROPS;
            case "all" -> RiceManEntity.HarvestMode.ALL;
            default -> throw new IllegalArgumentException("Field 'what' must be logs, crops or all");
        };
    }

    private static JsonObject stop(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        unit.commandStop();
        return ack("STOP", unit);
    }

    // ---------------------------------------------------------------- wyszukiwanie

    private static RiceManEntity requireUnit(MinecraftServer server, JsonObject msg) {
        String raw = requireString(msg, "unit");
        UUID id = parseUuid(raw);
        if (id == null) {
            throw new IllegalArgumentException("Field 'unit' must be a UUID");
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof RiceManEntity r && r.isAlive()) {
                return r;
            }
        }
        throw new IllegalArgumentException("No such riceman: " + id);
    }

    private static LivingEntity resolveTarget(MinecraftServer server, RiceManEntity unit, String spec) {
        if (spec.startsWith("nearest:")) {
            ResourceLocation typeId = ResourceLocation.tryParse(spec.substring("nearest:".length()));
            if (typeId == null) {
                throw new IllegalArgumentException("Bad entity type in target: " + spec);
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + typeId));
            AABB area = unit.getBoundingBox().inflate(NEAREST_SEARCH_RADIUS);
            return unit.level().getEntitiesOfClass(LivingEntity.class, area,
                            e -> e != unit && e.isAlive() && e.getType() == type)
                    .stream()
                    .min(Comparator.comparingDouble((LivingEntity e) -> unit.distanceToSqr(e)))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No " + typeId + " within " + (int) NEAREST_SEARCH_RADIUS + " blocks"));
        }

        ServerPlayer player = server.getPlayerList().getPlayerByName(spec);
        if (player != null) {
            return player;
        }

        UUID id = parseUuid(spec);
        if (id != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(id);
                if (e instanceof LivingEntity living && living.isAlive()) {
                    return living;
                }
            }
        }
        throw new IllegalArgumentException("Target not found: " + spec);
    }

    // ---------------------------------------------------------------- JSON

    private static JsonObject describe(RiceManEntity r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.getUUID().toString());
        o.addProperty("dimension", r.level().dimension().location().toString());
        o.addProperty("x", round2(r.getX()));
        o.addProperty("y", round2(r.getY()));
        o.addProperty("z", round2(r.getZ()));
        o.addProperty("health", round2(r.getHealth()));
        o.addProperty("command", r.getCommand().name());
        HarvestArea harvestArea = r.getHarvestArea();
        if (r.getCommand() == RiceManEntity.Command.HARVEST && harvestArea != null) {
            o.add("area", areaJson(harvestArea));
        }

        JsonObject items = new JsonObject();
        for (ItemStack s : r.getInventory().getItems()) {
            if (s.isEmpty()) {
                continue;
            }
            String key = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            int prev = items.has(key) ? items.get(key).getAsInt() : 0;
            items.addProperty(key, prev + s.getCount());
        }
        o.add("inventory", items);
        return o;
    }

    private static JsonObject ok(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("type", type);
        return o;
    }

    private static JsonObject ack(String type, RiceManEntity unit) {
        JsonObject o = ok(type);
        o.addProperty("unit", unit.getUUID().toString());
        return o;
    }

    private static JsonObject error(@Nullable String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message != null ? message : "Invalid message");
        return o;
    }

    private static String requireString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid field: " + key);
        }
        return el.getAsString();
    }

    private static int requireInt(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid field: " + key);
        }
        try {
            return el.getAsInt();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
    }

    private static boolean hasPos(JsonObject o) {
        return o.has("x") && o.has("y") && o.has("z");
    }

    private static BlockPos requirePos(JsonObject o) {
        return new BlockPos(requireInt(o, "x"), requireInt(o, "y"), requireInt(o, "z"));
    }

    @Nullable
    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}
