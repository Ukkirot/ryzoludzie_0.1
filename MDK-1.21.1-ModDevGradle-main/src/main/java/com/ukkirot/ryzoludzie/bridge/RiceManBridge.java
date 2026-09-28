package com.ukkirot.ryzoludzie.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.ukkirot.ryzoludzie.crafting.RiceCrafter;
import com.ukkirot.ryzoludzie.entity.ContainerTransfer;
import com.ukkirot.ryzoludzie.entity.HarvestArea;
import com.ukkirot.ryzoludzie.entity.RiceManEntity;
import com.ukkirot.ryzoludzie.registry.ModEntities;
import com.ukkirot.ryzoludzie.structure.StructureLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tłumaczy komendy JSON z mostu WebSocket na wywołania encji.
 * <p>
 * WAŻNE: handle() musi być wołane na wątku serwera, czyli w środku server.execute(...).
 * Wywołane z wątku sieciowego zwróci błąd zamiast dotykać świata.
 * <p>
 * Obsługiwane komendy (pole "type"; opcjonalne "requestId" jest odsyłane w odpowiedzi):
 * <pre>
 * {"type":"GET_STATE"}                                            (jednostki + gracze)
 * {"type":"GET_TERRAIN","chunks":[[cx,cz],...]}                     (maks. 64 chunków; kolory i wysokości powierzchni)
 * {"type":"SPAWN","x":100,"z":100[,"y":70]}                        (overworld; bez "y" na powierzchni terenu)
 * {"type":"MOVE","unit":"&lt;uuid&gt;","x":..,"z":..[,"y":..]}            (bez "y" na powierzchni terenu)
 * {"type":"ATTACK","unit":"&lt;uuid&gt;","target":"&lt;uuid | nick gracza | nearest:minecraft:pig&gt;"}
 * {"type":"GATHER","unit":"&lt;uuid&gt;"[,"x":..,"y":..,"z":..]}
 * {"type":"HARVEST","unit":"&lt;uuid&gt;","chunkX":..,"chunkZ":..}                (cały chunk)
 * {"type":"HARVEST","unit":"&lt;uuid&gt;","x1":..,"z1":..,"x2":..,"z2":..}       (prostokąt; opcjonalnie "y1","y2")
 * {"type":"HARVEST","unit":"&lt;uuid&gt;"[,"x":..,"y":..,"z":..][,"radius":12]}  (kwadrat wokół punktu / jednostki)
 *   opcje: "what":"logs|crops|all", "natural_only":true, "fell":true
 *   obszar maks. 64x64 bloków; bez "y1","y2" zakres wysokości liczy się z mapy terenu (chunki muszą być załadowane)
 * {"type":"CRAFT","unit":"&lt;uuid&gt;","item":"minecraft:chest"[,"count":1][,"place":true[,"x":..,"z":..[,"y":..]]]}
 *   wytwarza przedmiot z tego, co ma w ekwipunku (deski, patyki i stół rzemieślniczy robi sam);
 *   "place":true stawia gotowy blok w podanym miejscu albo w wolnym miejscu obok
 * {"type":"PLACE","unit":"&lt;uuid&gt;","item":"minecraft:chest"[,"x":..,"z":..[,"y":..]]}
 * {"type":"SET_STORAGE","unit":"&lt;uuid&gt;","x":..,"z":..[,"y":..]}   (przypisuje magazyn: skrzynię, beczkę itp.)
 * {"type":"SET_STORAGE","unit":"&lt;uuid&gt;","clear":true}            (odpina magazyn)
 * {"type":"DEPOSIT","unit":"&lt;uuid&gt;"[,"resume":false]}            (odkłada cały ekwipunek poza narzędziem)
 * {"type":"WITHDRAW","unit":"&lt;uuid&gt;","item":"minecraft:oak_planks"[,"count":64]}
 * {"type":"UPLOAD_STRUCTURE","name":"dom.nbt","data":"&lt;base64&gt;"}   (zapisuje strukturę do folderu ryzoludzie_structures)
 * {"type":"LIST_STRUCTURES"}                                       (lista dostępnych struktur .nbt, z rozmiarem w blokach)
 * {"type":"CHECK_STRUCTURE","unit":"&lt;uuid&gt;","name":"dom.nbt","x":..,"z":..[,"y":..]}
 *   podgląd bez rozkazu: typ drewna dobrany pod biom w (x,y,z), potrzebne materiały i czy
 *   ekwipunek jednostki + jej magazyn (jeśli przypisany) mają ich wystarczająco
 * {"type":"BUILD_STRUCTURE","unit":"&lt;uuid&gt;","name":"dom.nbt","x":..,"z":..[,"y":..]}
 *   jak CHECK_STRUCTURE, ale gdy materiałów starcza: dociąga brakujące z magazynu do ekwipunku
 *   i każe jednostce zbudować strukturę (typ drewna dobrany pod biom w miejscu budowy)
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
                    case "GET_TERRAIN" -> terrain(server, msg);
                    case "SPAWN" -> spawn(server, msg);
                    case "MOVE" -> move(server, msg);
                    case "ATTACK" -> attack(server, msg);
                    case "GATHER" -> gather(server, msg);
                    case "HARVEST" -> harvest(server, msg);
                    case "CRAFT" -> craft(server, msg);
                    case "PLACE" -> place(server, msg);
                    case "SET_STORAGE" -> setStorage(server, msg);
                    case "DEPOSIT" -> deposit(server, msg);
                    case "WITHDRAW" -> withdraw(server, msg);
                    case "UPLOAD_STRUCTURE" -> uploadStructure(server, msg);
                    case "LIST_STRUCTURES" -> listStructures(server);
                    case "CHECK_STRUCTURE" -> checkStructure(server, msg);
                    case "BUILD_STRUCTURE" -> buildStructure(server, msg);
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
        JsonArray players = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.getName().getString());
            po.addProperty("dimension", p.level().dimension().location().toString());
            po.addProperty("x", round2(p.getX()));
            po.addProperty("y", round2(p.getY()));
            po.addProperty("z", round2(p.getZ()));
            players.add(po);
        }

        JsonObject res = ok("STATE");
        res.addProperty("gameTime", server.overworld().getGameTime());
        res.add("units", units);
        res.add("players", players);
        return res;
    }

    // ---------------------------------------------------------------- teren

    private static final int MAX_TERRAIN_CHUNKS = 64;

    /**
     * Zwraca powierzchnię podanych chunków (overworld) do narysowania mapy.
     * Dla każdego bloku 6 bajtów: R, G, B (kolor z map Minecrafta), wysokość (2 bajty, licząc od
     * minY świata) i głębokość wody (0 = brak wody). Bajty są zakodowane w base64, blok po bloku,
     * wierszami (z rośnie co 16 bloków). Niezaładowane chunki wracają w "missing".
     */
    private static JsonObject terrain(MinecraftServer server, JsonObject msg) {
        ServerLevel level = server.overworld();
        JsonElement el = msg.get("chunks");
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException("Missing or invalid field: chunks");
        }
        JsonArray requested = el.getAsJsonArray();
        if (requested.size() > MAX_TERRAIN_CHUNKS) {
            throw new IllegalArgumentException("Too many chunks (max " + MAX_TERRAIN_CHUNKS + " per request)");
        }

        JsonArray chunks = new JsonArray();
        JsonArray missing = new JsonArray();
        for (JsonElement item : requested) {
            if (!item.isJsonArray() || item.getAsJsonArray().size() != 2) {
                throw new IllegalArgumentException("Each chunk must be [chunkX, chunkZ]");
            }
            JsonArray pair = item.getAsJsonArray();
            int cx;
            int cz;
            try {
                cx = pair.get(0).getAsInt();
                cz = pair.get(1).getAsInt();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Chunk coordinates must be integers");
            }
            if (level.isLoaded(new BlockPos(cx << 4, 0, cz << 4))) {
                chunks.add(scanChunk(level, cx, cz));
            } else {
                JsonArray m = new JsonArray();
                m.add(cx);
                m.add(cz);
                missing.add(m);
            }
        }

        JsonObject res = ok("TERRAIN");
        res.addProperty("minY", level.getMinBuildHeight());
        res.add("chunks", chunks);
        res.add("missing", missing);
        return res;
    }

    private static JsonObject scanChunk(ServerLevel level, int cx, int cz) {
        int minY = level.getMinBuildHeight();
        byte[] data = new byte[16 * 16 * 6];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = (cx << 4) + dx;
                int z = (cz << 4) + dz;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;

                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                boolean water = false;
                // Przechodzimy w dół przez rośliny i inne bloki bez kolizji (trawa, kwiaty, uprawy).
                for (int step = 0; step < 8 && y > minY; step++) {
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        water = !fluid.is(FluidTags.LAVA);
                        break;
                    }
                    if (state.blocksMotion()) {
                        break;
                    }
                    y--;
                    pos.set(x, y, z);
                    state = level.getBlockState(pos);
                }

                int depth = 0;
                if (water) {
                    int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                    depth = Math.max(1, Math.min(255, y - floor));
                }

                int rgb = state.getMapColor(level, pos).col;
                int h = y - minY;
                int i = (dz * 16 + dx) * 6;
                data[i] = (byte) (rgb >> 16);
                data[i + 1] = (byte) (rgb >> 8);
                data[i + 2] = (byte) rgb;
                data[i + 3] = (byte) (h >> 8);
                data[i + 4] = (byte) h;
                data[i + 5] = (byte) depth;
            }
        }

        JsonObject o = new JsonObject();
        o.addProperty("cx", cx);
        o.addProperty("cz", cz);
        o.addProperty("data", Base64.getEncoder().encodeToString(data));
        return o;
    }

    private static JsonObject spawn(MinecraftServer server, JsonObject msg) {
        ServerLevel level = server.overworld();
        BlockPos pos = posOrSurface(level, msg);
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
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        unit.commandMoveTo(posOrSurface(level, msg));
        return ack("MOVE", unit);
    }

    /** Pozycja z x, y, z. Bez "y" wysokość bierze się z powierzchni terenu w (x, z). */
    private static BlockPos posOrSurface(ServerLevel level, JsonObject o) {
        int x = requireInt(o, "x");
        int z = requireInt(o, "z");
        if (o.has("y")) {
            return new BlockPos(x, requireInt(o, "y"), z);
        }
        if (!level.isLoaded(new BlockPos(x, 0, z))) {
            throw new IllegalArgumentException("Target chunk is not loaded");
        }
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
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

    private static JsonObject craft(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        Item item = requireItem(msg, "item");
        int count = msg.has("count") ? requireInt(msg, "count") : 1;
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Field 'count' must be between 1 and 64");
        }
        boolean place = optionalBoolean(msg, "place", false);
        if (place && !(item instanceof BlockItem)) {
            throw new IllegalArgumentException(itemName(item) + " is not a block and cannot be placed");
        }
        BlockPos placeAt = (place && msg.has("x") && msg.has("z")) ? posOrSurface(level, msg) : null;

        RiceCrafter.Plan plan = RiceCrafter.plan(unit, level, item, count);
        if (!plan.ok) {
            throw new IllegalArgumentException("Cannot craft " + itemName(item) + ": missing "
                    + (plan.missing != null ? itemName(plan.missing) : "ingredients"));
        }

        unit.commandCraft(item, count, place, placeAt);

        JsonObject res = ack("CRAFT", unit);
        JsonArray steps = new JsonArray();
        for (RiceCrafter.Step step : plan.steps) {
            steps.add(RiceCrafter.describe(step, level));
        }
        res.add("steps", steps);
        return res;
    }

    private static JsonObject place(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        Item item = requireItem(msg, "item");
        if (!(item instanceof BlockItem)) {
            throw new IllegalArgumentException(itemName(item) + " is not a block and cannot be placed");
        }
        if (!RiceCrafter.has(unit, item)) {
            throw new IllegalArgumentException("Unit does not carry " + itemName(item));
        }
        BlockPos pos = (msg.has("x") && msg.has("z")) ? posOrSurface(level, msg) : null;
        unit.commandPlace(item, pos);
        return ack("PLACE", unit);
    }

    /** Przedmiot z pola JSON: "minecraft:chest" albo samo "chest". */
    private static Item requireItem(JsonObject o, String key) {
        String raw = requireString(o, key).trim();
        ResourceLocation id = ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (id == null) {
            throw new IllegalArgumentException("Bad item id: " + raw);
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .filter((item) -> item != Items.AIR)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + raw));
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static JsonObject setStorage(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (optionalBoolean(msg, "clear", false)) {
            unit.setStorageChest(null);
            return ack("SET_STORAGE", unit);
        }
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        BlockPos pos = posOrSurface(level, msg);
        if (!msg.has("y") && !(level.getBlockEntity(pos) instanceof Container)) {
            // Bez podanego "y" pos to wolne miejsce NAD najwyższym blokiem (dobre do stanięcia).
            // Skrzynia to właśnie ten najwyższy blok, więc sprawdzamy też jedno niżej.
            BlockPos below = pos.below();
            if (level.getBlockEntity(below) instanceof Container) {
                pos = below;
            }
        }
        if (!(level.getBlockEntity(pos) instanceof Container)) {
            throw new IllegalArgumentException("There is no container at that position");
        }
        unit.setStorageChest(pos);
        return ack("SET_STORAGE", unit);
    }

    private static JsonObject deposit(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (unit.getStorageChest() == null) {
            throw new IllegalArgumentException("Unit has no assigned storage; use SET_STORAGE first");
        }
        unit.commandDeposit(optionalBoolean(msg, "resume", false));
        return ack("DEPOSIT", unit);
    }

    private static JsonObject withdraw(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (unit.getStorageChest() == null) {
            throw new IllegalArgumentException("Unit has no assigned storage; use SET_STORAGE first");
        }
        Item item = requireItem(msg, "item");
        int count = msg.has("count") ? requireInt(msg, "count") : 64;
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Field 'count' must be between 1 and 64");
        }
        unit.commandWithdraw(item, count);
        return ack("WITHDRAW", unit);
    }

    private static JsonObject stop(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        unit.commandStop();
        return ack("STOP", unit);
    }

    // ---------------------------------------------------------------- struktury (.nbt)

    private static final long MAX_STRUCTURE_BYTES = 4L * 1024 * 1024; // 4 MB

    private static Path structuresDir(MinecraftServer server) {
        Path dir = server.getServerDirectory().resolve("ryzoludzie_structures");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można utworzyć folderu struktur: " + dir, e);
        }
        return dir;
    }

    private static JsonObject uploadStructure(MinecraftServer server, JsonObject msg) {
        String name = sanitizeStructureName(requireString(msg, "name"));
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(requireString(msg, "data"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Field 'data' is not valid base64");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Uploaded structure is empty");
        }
        if (bytes.length > MAX_STRUCTURE_BYTES) {
            throw new IllegalArgumentException("Structure too large (max " + (MAX_STRUCTURE_BYTES / 1024 / 1024) + " MB)");
        }

        int[] size = readStructureSize(parseStructureNbt(bytes));

        Path target = structuresDir(server).resolve(name);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save structure to disk: " + e.getMessage());
        }

        JsonObject res = ok("UPLOAD_STRUCTURE");
        res.addProperty("name", name);
        res.addProperty("bytes", bytes.length);
        res.add("size", sizeArray(size));
        return res;
    }

    private static JsonObject listStructures(MinecraftServer server) {
        Path dir = structuresDir(server);
        JsonArray list = new JsonArray();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".nbt"))
                    .sorted()
                    .forEach(p -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", p.getFileName().toString());
                        try {
                            o.addProperty("bytes", Files.size(p));
                            int[] size = readStructureSize(parseStructureNbt(Files.readAllBytes(p)));
                            if (size != null) {
                                o.add("size", sizeArray(size));
                            }
                        } catch (IOException | RuntimeException ignored) {
                            // uszkodzony/nieodczytywalny plik - pomijamy rozmiar, nazwa i tak trafia na listę
                        }
                        list.add(o);
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list structures: " + e.getMessage());
        }
        JsonObject res = ok("LIST_STRUCTURES");
        res.add("structures", list);
        return res;
    }

    private static CompoundTag parseStructureNbt(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a valid (gzip-compressed) NBT structure file: " + e.getMessage());
        }
    }

    @Nullable
    private static int[] readStructureSize(CompoundTag tag) {
        if (!tag.contains("size") || !tag.contains("blocks") || !tag.contains("palette")) {
            throw new IllegalArgumentException("File doesn't look like a Minecraft structure (missing 'size'/'blocks'/'palette')");
        }
        if (!(tag.get("size") instanceof ListTag sizeList) || sizeList.size() != 3) {
            return null;
        }
        return new int[]{sizeList.getInt(0), sizeList.getInt(1), sizeList.getInt(2)};
    }

    private static JsonArray sizeArray(@Nullable int[] size) {
        JsonArray arr = new JsonArray();
        if (size != null) {
            arr.add(size[0]);
            arr.add(size[1]);
            arr.add(size[2]);
        }
        return arr;
    }

    private static String sanitizeStructureName(String raw) {
        String name = raw.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1); // odrzuć ewentualną ścieżkę, zostaw samą nazwę pliku
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".nbt")) {
            name = name + ".nbt";
        }
        if (!name.matches("[A-Za-z0-9_\\-. ]+\\.nbt")) {
            throw new IllegalArgumentException("Structure name contains invalid characters");
        }
        return name;
    }

    private static JsonObject checkStructure(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        String name = requireString(msg, "name");
        BlockPos origin = posOrSurface(level, msg);
        StructureLoader.LoadResult loaded = StructureLoader.load(server, name, level, origin);

        Container storage = storageContainerOf(unit);
        Map<Item, Integer> have = ContainerTransfer.stockOf(unit.getInventory());
        Map<Item, Integer> storageStock = storage != null ? ContainerTransfer.stockOf(storage) : Map.<Item, Integer>of();

        JsonArray materials = new JsonArray();
        boolean materialsOk = true;
        for (Map.Entry<Item, Integer> need : loaded.materials().entrySet()) {
            int total = have.getOrDefault(need.getKey(), 0) + storageStock.getOrDefault(need.getKey(), 0);
            boolean enough = total >= need.getValue();
            materialsOk &= enough;
            JsonObject m = new JsonObject();
            m.addProperty("item", itemName(need.getKey()));
            m.addProperty("needed", need.getValue());
            m.addProperty("have", total);
            m.addProperty("ok", enough);
            materials.add(m);
        }

        JsonObject res = ok("CHECK_STRUCTURE");
        res.add("size", sizeArray(loaded.size()));
        res.addProperty("blocks", loaded.blocks().size());
        res.addProperty("materialsOk", materialsOk);
        res.add("materials", materials);
        return res;
    }

    private static JsonObject buildStructure(MinecraftServer server, JsonObject msg) {
        RiceManEntity unit = requireUnit(server, msg);
        if (!(unit.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Unit is not in a server level");
        }
        String name = requireString(msg, "name");
        BlockPos origin = posOrSurface(level, msg);
        StructureLoader.LoadResult loaded = StructureLoader.load(server, name, level, origin);

        Container storage = storageContainerOf(unit);
        Map<Item, Integer> have = ContainerTransfer.stockOf(unit.getInventory());
        Map<Item, Integer> storageStock = storage != null ? ContainerTransfer.stockOf(storage) : Map.<Item, Integer>of();

        for (Map.Entry<Item, Integer> need : loaded.materials().entrySet()) {
            int total = have.getOrDefault(need.getKey(), 0) + storageStock.getOrDefault(need.getKey(), 0);
            if (total < need.getValue()) {
                throw new IllegalArgumentException("Cannot build " + name + ": missing "
                        + (need.getValue() - total) + "x " + itemName(need.getKey()));
            }
        }

        // Dociągnij z magazynu to, czego brakuje we własnym ekwipunku - jednorazowo, przed startem budowy.
        if (storage != null) {
            for (Map.Entry<Item, Integer> need : loaded.materials().entrySet()) {
                int deficit = need.getValue() - have.getOrDefault(need.getKey(), 0);
                if (deficit <= 0) {
                    continue;
                }
                ItemStack taken = ContainerTransfer.takeUpTo(storage, need.getKey(), deficit);
                if (taken.isEmpty()) {
                    continue;
                }
                ItemStack left = unit.getInventory().addItem(taken);
                if (!left.isEmpty()) {
                    ContainerTransfer.mergeInto(storage, left); // ekwipunek pełny, reszta wraca do magazynu
                }
            }
        }

        Map<Item, Integer> haveNow = ContainerTransfer.stockOf(unit.getInventory());
        for (Map.Entry<Item, Integer> need : loaded.materials().entrySet()) {
            if (haveNow.getOrDefault(need.getKey(), 0) < need.getValue()) {
                throw new IllegalArgumentException("Cannot build " + name
                        + ": not enough room in the unit's inventory to carry all materials");
            }
        }

        unit.commandBuild(name, origin);

        JsonObject res = ack("BUILD_STRUCTURE", unit);
        res.add("size", sizeArray(loaded.size()));
        res.addProperty("blocks", loaded.blocks().size());
        return res;
    }

    @Nullable
    private static Container storageContainerOf(RiceManEntity unit) {
        BlockPos chest = unit.getStorageChest();
        if (chest == null || !(unit.level() instanceof ServerLevel level) || !level.isLoaded(chest)) {
            return null;
        }
        return level.getBlockEntity(chest) instanceof Container c ? c : null;
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
        ItemStack tool = r.getMainHandItem();
        if (!tool.isEmpty()) {
            o.addProperty("tool", itemName(tool.getItem()));
        }
        if (r.getNoticeText() != null) {
            JsonObject n = new JsonObject();
            n.addProperty("level", r.getNoticeLevel());
            n.addProperty("text", r.getNoticeText());
            o.add("notice", n);
            r.clearNotice(); // dashboard dostaje go raz, w najbliższym GET_STATE
        }
        BlockPos storage = r.getStorageChest();
        if (storage != null) {
            JsonObject s = new JsonObject();
            s.addProperty("x", storage.getX());
            s.addProperty("y", storage.getY());
            s.addProperty("z", storage.getZ());
            o.add("storage", s);
        }
        if (r.getCommand() == RiceManEntity.Command.CRAFT && r.getCraftItem() != null) {
            o.addProperty("craft", itemName(r.getCraftItem()));
        }
        if (r.getCommand() == RiceManEntity.Command.PLACE && r.getPlaceItem() != null) {
            o.addProperty("place", itemName(r.getPlaceItem()));
        }
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
