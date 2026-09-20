package com.ukkirot.ryzoludzie.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
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
