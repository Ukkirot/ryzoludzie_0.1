package com.ukkirot.ryzoludzie;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class RicemanSocketServer extends WebSocketServer {

    private final MinecraftServer mcServer;

    public RicemanSocketServer(int port, MinecraftServer mcServer) {
        super(new InetSocketAddress(port));
        this.mcServer = mcServer;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        RyzoludzieMod.LOGGER.info("[Ryzoludzie] Klient podlaczony: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        RyzoludzieMod.LOGGER.info("[Ryzoludzie] Klient rozlaczony: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        RyzoludzieMod.LOGGER.info("[Ryzoludzie] Otrzymano komende: {}", message);

        JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            conn.send("{\"error\":\"niepoprawny JSON\"}");
            return;
        }

        String action = json.has("action") ? json.get("action").getAsString() : "";

        // WAZNE: nie wolno dotykac swiata gry z watku WebSocketa!
        // Trzeba przelaczyc sie na glowny watek serwera przez mcServer.execute(...)
        mcServer.execute(() -> {
            switch (action) {
                case "spawn" -> {
                    RyzoludzieMod.LOGGER.info("[Ryzoludzie] SPAWN - tu docelowo stworzymy RiceManEntity");
                    conn.send("{\"status\":\"ok\",\"action\":\"spawn\"}");
                }
                case "despawn" -> {
                    RyzoludzieMod.LOGGER.info("[Ryzoludzie] DESPAWN - tu docelowo usuniemy encje");
                    conn.send("{\"status\":\"ok\",\"action\":\"despawn\"}");
                }
                default -> conn.send("{\"error\":\"nieznana akcja: " + action + "\"}");
            }
        });
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        RyzoludzieMod.LOGGER.error("[Ryzoludzie] Blad WebSocket", ex);
    }

    @Override
    public void onStart() {
        RyzoludzieMod.LOGGER.info("[Ryzoludzie] Serwer WebSocket wystartowal na porcie {}", getPort());
    }
}