package com.ukkirot.ryzoludzie;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import com.ukkirot.ryzoludzie.bridge.RiceManBridge;

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
        RyzoludzieMod.LOGGER.info("[Ryzoludzie] Otrzymano komende: {} (watek: {})",
                message, Thread.currentThread().getName());

        JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            conn.send("{\"ok\":false,\"error\":\"niepoprawny JSON\"}");
            return;
        }

        // Stary format {"action":"spawn"} dalej dziala, bo przepisujemy go na "type"
        if (!json.has("type") && json.has("action")) {
            json.add("type", json.get("action"));
        }

        // WAZNE: nie wolno dotykac swiata gry z watku WebSocketa!
        mcServer.execute(() -> {
            RyzoludzieMod.LOGGER.info("[Ryzoludzie] Wykonanie na watku: {}", Thread.currentThread().getName());
            JsonObject response = RiceManBridge.handle(mcServer, json);
            try {
                conn.send(response.toString());
            } catch (Exception e) {
                RyzoludzieMod.LOGGER.warn("[Ryzoludzie] Nie udalo sie wyslac odpowiedzi", e);
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