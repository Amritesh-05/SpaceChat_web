import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class server {

    private static final int PORT = 8080;
    private static final int MAX_HISTORY = 120;
    private static final int RATE_LIMIT_MSGS = 8;
    private static final long RATE_LIMIT_WINDOW_MS = 4_000L;
    private static final long SESSION_IDLE_MS = 45L * 60L * 1000L;
    private static final long TYPING_TTL_MS = 3_500L;

    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Random RNG = new Random();
    private static final ScheduledExecutorService HOUSEKEEPER = Executors.newScheduledThreadPool(2);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withLocale(Locale.US)
            .withZone(ZoneId.systemDefault());

    public static void main(String[] args) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(PORT), 0);
        httpServer.createContext("/api/health", exchange -> sendJson(exchange, 200, Map.of(
                "ok", true,
                "port", PORT,
                "rooms", ROOMS.size()
        )));
        httpServer.createContext("/api/rooms/create", server::handleCreateRoom);
        httpServer.createContext("/api/rooms/join", server::handleJoinRoom);
        httpServer.createContext("/api/rooms/", new RoomApiHandler());
        httpServer.createContext("/", new StaticHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool());

        HOUSEKEEPER.scheduleAtFixedRate(server::cleanupSessions, 1, 1, TimeUnit.MINUTES);
        HOUSEKEEPER.scheduleAtFixedRate(server::cleanupTyping, 1, 1, TimeUnit.SECONDS);

        log("ChatSpace Web is starting on http://localhost:" + PORT);
        httpServer.start();
    }

    private static void handleCreateRoom(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String username = sanitize(text(body.get("username")));
        String roomName = sanitizeRoom(text(body.get("roomName")));
        String password = text(body.get("password")).trim();
        int maxUsers = clampInt(body.get("maxUsers"), 2, 200, 30);

        if (username.isEmpty()) {
            sendError(exchange, 400, "Username is required.");
            return;
        }

        String roomCode = uniqueCode();
        String sessionId = token();
        Room room = new Room(roomCode, roomName.isEmpty() ? username + "'s lounge" : roomName, username, password, maxUsers);
        Session session = new Session(sessionId, roomCode, username);
        room.sessions.put(sessionId, session);
        ROOMS.put(roomCode, room);
        SESSIONS.put(sessionId, session);

        log("ROOM_CREATED " + roomCode + " by " + username);
        sendJson(exchange, 200, roomJoinPayload(room, sessionId, username));
    }

    private static void handleJoinRoom(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String roomCode = text(body.get("code")).trim().toUpperCase(Locale.ROOT);
        String username = sanitize(text(body.get("username")));
        String password = text(body.get("password")).trim();

        Room room = ROOMS.get(roomCode);
        if (room == null) {
            sendError(exchange, 404, "Room not found.");
            return;
        }
        synchronized (room.lock) {
            if (room.ended) {
                sendError(exchange, 410, "This room has already ended.");
                return;
            }
            if (username.isEmpty()) {
                sendError(exchange, 400, "Username is required.");
                return;
            }
            if (room.passwordRequired() && !room.password.equals(password)) {
                sendError(exchange, 403, "Wrong room password.");
                return;
            }
            if (room.sessions.size() >= room.maxUsers) {
                sendError(exchange, 409, "Room is full.");
                return;
            }
            if (room.usernameTaken(username)) {
                sendError(exchange, 409, "That username is already in use.");
                return;
            }

            String sessionId = token();
            Session session = new Session(sessionId, roomCode, username);
            room.sessions.put(sessionId, session);
            SESSIONS.put(sessionId, session);
            room.broadcast(Map.of("type", "room.notice", "notice", username + " joined the room."));
            room.broadcastState("presence.updated");
            log("ROOM_JOIN " + username + " -> " + roomCode);
            sendJson(exchange, 200, roomJoinPayload(room, sessionId, username));
        }
    }

    private static class RoomApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String suffix = path.substring("/api/rooms/".length());
            String[] parts = suffix.split("/");
            if (parts.length == 0 || parts[0].isBlank()) {
                sendError(exchange, 404, "Unknown room endpoint.");
                return;
            }
            String roomCode = parts[0].toUpperCase(Locale.ROOT);
            Room room = ROOMS.get(roomCode);
            if (room == null) {
                sendError(exchange, 404, "Room not found.");
                return;
            }

            if (parts.length == 2 && "state".equals(parts[1])) {
                handleState(exchange, room);
                return;
            }
            if (parts.length == 2 && "events".equals(parts[1])) {
                handleEvents(exchange, room);
                return;
            }
            if (parts.length == 2 && "message".equals(parts[1])) {
                handleMessage(exchange, room);
                return;
            }
            if (parts.length == 2 && "typing".equals(parts[1])) {
                handleTyping(exchange, room);
                return;
            }
            if (parts.length == 2 && "reaction".equals(parts[1])) {
                handleReaction(exchange, room);
                return;
            }
            if (parts.length == 2 && "edit".equals(parts[1])) {
                handleEdit(exchange, room);
                return;
            }
            if (parts.length == 2 && "delete".equals(parts[1])) {
                handleDelete(exchange, room);
                return;
            }
            if (parts.length == 2 && "topic".equals(parts[1])) {
                handleTopic(exchange, room);
                return;
            }
            if (parts.length == 2 && "signal".equals(parts[1])) {
                handleSignal(exchange, room);
                return;
            }
            if (parts.length == 2 && "mute".equals(parts[1])) {
                handleMute(exchange, room, true);
                return;
            }
            if (parts.length == 2 && "unmute".equals(parts[1])) {
                handleMute(exchange, room, false);
                return;
            }
            if (parts.length == 2 && "kick".equals(parts[1])) {
                handleKick(exchange, room);
                return;
            }
            if (parts.length == 2 && "leave".equals(parts[1])) {
                handleLeave(exchange, room);
                return;
            }
            if (parts.length == 2 && "end".equals(parts[1])) {
                handleEnd(exchange, room);
                return;
            }

            sendError(exchange, 404, "Unknown room endpoint.");
        }
    }

    private static void handleState(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        session.touch();
        sendJson(exchange, 200, roomStatePayload(room, session.username));
    }

    private static void handleEvents(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();

        ClientStream stream = new ClientStream(output);
        room.streams.add(stream);
        session.touch();
        writeSse(output, Map.of("type", "hello", "roomCode", room.code));

        try {
            while (!stream.closed) {
                Thread.sleep(15_000L);
                session.touch();
                writeComment(output, "keep-alive");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            stream.closed = true;
            room.streams.remove(stream);
            try {
                output.close();
            } catch (IOException ignored) {
            }
            exchange.close();
        }
    }

    private static void handleMessage(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }

        Map<String, Object> body = readJsonBody(exchange);
        String text = text(body.get("text")).trim();
        String replyToId = text(body.get("replyToId")).trim();
        if (text.isEmpty()) {
            sendError(exchange, 400, "Message cannot be empty.");
            return;
        }
        if (!session.rateOk()) {
            sendError(exchange, 429, "Slow down a bit.");
            return;
        }
        if (room.mutedUsers.contains(session.username)) {
            sendError(exchange, 403, "You are muted in this room.");
            return;
        }

        synchronized (room.lock) {
            Map<String, Object> replyTo = Collections.emptyMap();
            if (!replyToId.isEmpty()) {
                Message parent = room.findMessage(replyToId);
                if (parent != null) {
                    replyTo = parent.replyPreview();
                }
            }
            Message message = new Message(token(), session.username, text, replyTo);
            room.messages.add(message);
            while (room.messages.size() > MAX_HISTORY) {
                room.messages.remove(0);
            }
            room.typing.remove(session.username);
            room.broadcast(Map.of("type", "message.created", "message", message.toMap()));
            room.broadcastState("presence.updated");
            sendJson(exchange, 200, Map.of("ok", true, "message", message.toMap()));
        }
    }

    private static void handleTyping(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        boolean active = bool(body.get("active"));

        if (active) {
            room.typing.put(session.username, System.currentTimeMillis() + TYPING_TTL_MS);
        } else {
            room.typing.remove(session.username);
        }
        room.broadcastState("typing.updated");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleReaction(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String messageId = text(body.get("messageId")).trim();
        String emoji = text(body.get("emoji")).trim();
        if (messageId.isEmpty() || emoji.isEmpty()) {
            sendError(exchange, 400, "Message and emoji are required.");
            return;
        }

        Message message = room.findMessage(messageId);
        if (message == null) {
            sendError(exchange, 404, "Message not found.");
            return;
        }
        synchronized (message.lock) {
            Set<String> users = message.reactions.computeIfAbsent(emoji, key -> new LinkedHashSet<>());
            if (!users.add(session.username)) {
                users.remove(session.username);
                if (users.isEmpty()) {
                    message.reactions.remove(emoji);
                }
            }
        }

        room.broadcast(Map.of("type", "message.updated", "message", message.toMap()));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleEdit(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String messageId = text(body.get("messageId")).trim();
        String newText = text(body.get("text")).trim();
        Message message = room.findMessage(messageId);
        if (message == null) {
            sendError(exchange, 404, "Message not found.");
            return;
        }
        if (!message.sender.equals(session.username)) {
            sendError(exchange, 403, "You can only edit your own messages.");
            return;
        }
        if (newText.isEmpty()) {
            sendError(exchange, 400, "Edited message cannot be empty.");
            return;
        }
        synchronized (message.lock) {
            message.text = newText;
            message.edited = true;
        }
        room.broadcast(Map.of("type", "message.updated", "message", message.toMap()));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleDelete(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String messageId = text(body.get("messageId")).trim();
        Message message = room.findMessage(messageId);
        if (message == null) {
            sendError(exchange, 404, "Message not found.");
            return;
        }
        if (!message.sender.equals(session.username) && !room.admin.equals(session.username)) {
            sendError(exchange, 403, "Only the sender or room admin can delete that message.");
            return;
        }
        synchronized (room.lock) {
            room.messages.remove(message);
        }
        room.broadcast(Map.of("type", "message.deleted", "messageId", messageId));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleTopic(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        if (!room.admin.equals(session.username)) {
            sendError(exchange, 403, "Only the admin can change the topic.");
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        room.topic = sanitizeRoom(text(body.get("topic")));
        room.broadcast(Map.of("type", "room.topic", "topic", room.topic));
        sendJson(exchange, 200, Map.of("ok", true, "topic", room.topic));
    }

    private static void handleSignal(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String target = sanitize(text(body.get("target")));
        String signalType = text(body.get("signalType")).trim();
        Object data = body.get("data");
        if (signalType.isEmpty()) {
            sendError(exchange, 400, "Signal type is required.");
            return;
        }

        room.broadcast(Map.of(
                "type", "call.signal",
                "from", session.username,
                "target", target,
                "signalType", signalType,
                "data", data == null ? Collections.emptyMap() : data
        ));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleMute(HttpExchange exchange, Room room, boolean muting) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        if (!room.admin.equals(session.username)) {
            sendError(exchange, 403, "Only the admin can do that.");
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String target = sanitize(text(body.get("username")));
        if (target.isEmpty() || target.equals(room.admin) || !room.hasUser(target)) {
            sendError(exchange, 400, "Choose a valid room member.");
            return;
        }
        if (muting) {
            room.mutedUsers.add(target);
        } else {
            room.mutedUsers.remove(target);
        }
        room.broadcast(Map.of("type", "room.notice", "notice", muting ? target + " was muted." : target + " was unmuted."));
        room.broadcastState("presence.updated");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleKick(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        if (!room.admin.equals(session.username)) {
            sendError(exchange, 403, "Only the admin can kick members.");
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String target = sanitize(text(body.get("username")));
        Session targetSession = room.findSessionByUsername(target);
        if (targetSession == null || target.equals(room.admin)) {
            sendError(exchange, 400, "Choose a valid room member.");
            return;
        }
        room.removeSession(targetSession.id, true, target + " was removed by the admin.");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleLeave(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        room.removeSession(session.id, false, session.username + " left the room.");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static void handleEnd(HttpExchange exchange, Room room) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        Session session = requireSession(exchange, room);
        if (session == null) {
            return;
        }
        if (!room.admin.equals(session.username)) {
            sendError(exchange, 403, "Only the admin can end the room.");
            return;
        }
        room.end("The room was ended by the admin.");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendError(exchange, 405, "Method not allowed.");
            return false;
        }
        return true;
    }

    private static Session requireSession(HttpExchange exchange, Room room) throws IOException {
        String sessionId = query(exchange.getRequestURI(), "session");
        if (sessionId.isEmpty()) {
            Map<String, Object> body = tryReadJsonBody(exchange);
            if (body != null) {
                sessionId = text(body.get("session")).trim();
                exchange.setAttribute("jsonBody", body);
            }
        }
        Session session = SESSIONS.get(sessionId);
        if (session == null || !Objects.equals(session.roomCode, room.code) || room.sessions.get(sessionId) == null) {
            sendError(exchange, 401, "Your session is no longer valid.");
            return null;
        }
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        Object cached = exchange.getAttribute("jsonBody");
        if (cached instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> body = tryReadJsonBody(exchange);
        return body == null ? Collections.emptyMap() : body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryReadJsonBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        byte[] bytes = input.readAllBytes();
        if (bytes.length == 0) {
            return Collections.emptyMap();
        }
        Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, ?> payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("ok", false, "error", message));
    }

    private static void writeSse(OutputStream output, Map<String, ?> payload) throws IOException {
        String body = "data: " + Json.stringify(payload) + "\n\n";
        output.write(body.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeComment(OutputStream output, String text) throws IOException {
        output.write((":" + text + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static Map<String, Object> roomJoinPayload(Room room, String sessionId, String username) {
        return Map.of("ok", true, "session", sessionId, "room", roomStatePayload(room, username));
    }

    private static Map<String, Object> roomStatePayload(Room room, String currentUsername) {
        return Map.of(
                "code", room.code,
                "name", room.name,
                "topic", room.topic,
                "admin", room.admin,
                "maxUsers", room.maxUsers,
                "you", currentUsername,
                "muted", room.mutedUsers.contains(currentUsername),
                "members", room.memberPayload(),
                "messages", room.messagePayload(),
                "typing", room.typingUsers()
        );
    }

    private static void cleanupSessions() {
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            if (now - session.lastSeen > SESSION_IDLE_MS) {
                Room room = ROOMS.get(session.roomCode);
                if (room != null) {
                    room.removeSession(session.id, false, session.username + " disconnected.");
                } else {
                    SESSIONS.remove(session.id);
                }
            }
        }
    }

    private static void cleanupTyping() {
        long now = System.currentTimeMillis();
        for (Room room : ROOMS.values()) {
            boolean changed = false;
            for (Map.Entry<String, Long> entry : new ArrayList<>(room.typing.entrySet())) {
                if (entry.getValue() < now) {
                    room.typing.remove(entry.getKey());
                    changed = true;
                }
            }
            if (changed) {
                room.broadcastState("typing.updated");
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(text(value));
    }

    private static int clampInt(Object value, int min, int max, int fallback) {
        try {
            int parsed = value instanceof Number n ? n.intValue() : Integer.parseInt(text(value).trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[\\r\\n:|]+", " ").trim();
        return cleaned.length() > 24 ? cleaned.substring(0, 24).trim() : cleaned;
    }

    private static String sanitizeRoom(String value) {
        String cleaned = value.replaceAll("[\\r\\n]+", " ").trim();
        return cleaned.length() > 60 ? cleaned.substring(0, 60).trim() : cleaned;
    }

    private static String uniqueCode() {
        String value;
        do {
            value = newCode();
        } while (ROOMS.containsKey(value));
        return value;
    }

    private static String newCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            builder.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static String token() {
        return Long.toHexString(RNG.nextLong()) + Long.toHexString(System.nanoTime());
    }

    private static String query(URI uri, String key) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String piece : raw.split("&")) {
            int idx = piece.indexOf('=');
            String currentKey = idx >= 0 ? piece.substring(0, idx) : piece;
            if (currentKey.equals(key)) {
                return idx >= 0 ? decode(piece.substring(idx + 1)) : "";
            }
        }
        return "";
    }

    private static String decode(String value) {
        return value.replace("%20", " ").replace("%2B", "+");
    }

    private static void log(String message) {
        System.out.println("[" + LOG_TIME.format(Instant.now()) + "] " + message);
    }

    private static class Room {
        final Object lock = new Object();
        final String code;
        final String name;
        final String admin;
        final String password;
        final int maxUsers;
        volatile String topic = "Welcome to the room";
        volatile boolean ended = false;

        final Map<String, Session> sessions = new ConcurrentHashMap<>();
        final Set<String> mutedUsers = ConcurrentHashMap.newKeySet();
        final Map<String, Long> typing = new ConcurrentHashMap<>();
        final List<Message> messages = new CopyOnWriteArrayList<>();
        final List<ClientStream> streams = new CopyOnWriteArrayList<>();

        Room(String code, String name, String admin, String password, int maxUsers) {
            this.code = code;
            this.name = name;
            this.admin = admin;
            this.password = password == null ? "" : password;
            this.maxUsers = maxUsers;
        }

        boolean passwordRequired() {
            return !password.isBlank();
        }

        boolean usernameTaken(String username) {
            return sessions.values().stream().anyMatch(session -> session.username.equalsIgnoreCase(username));
        }

        boolean hasUser(String username) {
            return sessions.values().stream().anyMatch(session -> session.username.equals(username));
        }

        Session findSessionByUsername(String username) {
            for (Session session : sessions.values()) {
                if (session.username.equals(username)) {
                    return session;
                }
            }
            return null;
        }

        Message findMessage(String messageId) {
            for (Message message : messages) {
                if (message.id.equals(messageId)) {
                    return message;
                }
            }
            return null;
        }

        List<Map<String, Object>> messagePayload() {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (Message message : messages) {
                payload.add(message.toMap());
            }
            return payload;
        }

        List<Map<String, Object>> memberPayload() {
            List<Map<String, Object>> payload = new ArrayList<>();
            List<String> usernames = new ArrayList<>();
            for (Session session : sessions.values()) {
                usernames.add(session.username);
            }
            usernames.sort(String::compareToIgnoreCase);
            for (String username : usernames) {
                payload.add(Map.of(
                        "username", username,
                        "admin", username.equals(admin),
                        "muted", mutedUsers.contains(username)
                ));
            }
            return payload;
        }

        List<String> typingUsers() {
            long now = System.currentTimeMillis();
            List<String> users = new ArrayList<>();
            for (Map.Entry<String, Long> entry : typing.entrySet()) {
                if (entry.getValue() >= now) {
                    users.add(entry.getKey());
                }
            }
            users.sort(String::compareToIgnoreCase);
            return users;
        }

        void broadcast(Map<String, ?> payload) {
            for (ClientStream stream : new ArrayList<>(streams)) {
                try {
                    writeSse(stream.output, payload);
                } catch (IOException e) {
                    stream.closed = true;
                    streams.remove(stream);
                }
            }
        }

        void broadcastState(String type) {
            broadcast(Map.of("type", type, "members", memberPayload(), "typing", typingUsers()));
        }

        void removeSession(String sessionId, boolean kicked, String notice) {
            Session session = sessions.remove(sessionId);
            SESSIONS.remove(sessionId);
            if (session == null) {
                return;
            }
            typing.remove(session.username);
            mutedUsers.remove(session.username);

            if (sessions.isEmpty()) {
                ended = true;
                ROOMS.remove(code);
                log("ROOM_REMOVED " + code + " (empty)");
                return;
            }

            if (kicked) {
                broadcast(Map.of("type", "member.kicked", "username", session.username));
            }
            broadcast(Map.of("type", "room.notice", "notice", notice));
            broadcastState("presence.updated");
        }

        void end(String notice) {
            ended = true;
            ROOMS.remove(code);
            for (Session session : new ArrayList<>(sessions.values())) {
                SESSIONS.remove(session.id);
            }
            sessions.clear();
            typing.clear();
            broadcast(Map.of("type", "room.ended", "notice", notice));
            log("ROOM_ENDED " + code + " by " + admin);
        }
    }

    private static class Session {
        final String id;
        final String roomCode;
        final String username;
        final Deque<Long> recentMessages = new ArrayDeque<>();
        volatile long lastSeen = System.currentTimeMillis();

        Session(String id, String roomCode, String username) {
            this.id = id;
            this.roomCode = roomCode;
            this.username = username;
        }

        void touch() {
            lastSeen = System.currentTimeMillis();
        }

        boolean rateOk() {
            long now = System.currentTimeMillis();
            while (!recentMessages.isEmpty() && now - recentMessages.peekFirst() > RATE_LIMIT_WINDOW_MS) {
                recentMessages.pollFirst();
            }
            if (recentMessages.size() >= RATE_LIMIT_MSGS) {
                return false;
            }
            recentMessages.addLast(now);
            return true;
        }
    }

    private static class Message {
        final Object lock = new Object();
        final String id;
        final String sender;
        final long timestamp;
        final Map<String, Object> replyTo;
        volatile String text;
        volatile boolean edited;
        final Map<String, Set<String>> reactions = new LinkedHashMap<>();

        Message(String id, String sender, String text, Map<String, Object> replyTo) {
            this.id = id;
            this.sender = sender;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
            this.replyTo = replyTo == null ? Collections.emptyMap() : new LinkedHashMap<>(replyTo);
        }

        Map<String, Object> toMap() {
            synchronized (lock) {
                Map<String, Object> serializedReactions = new LinkedHashMap<>();
                for (Map.Entry<String, Set<String>> entry : reactions.entrySet()) {
                    serializedReactions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                return Map.of(
                        "id", id,
                        "sender", sender,
                        "text", text,
                        "timestamp", timestamp,
                        "edited", edited,
                        "replyTo", replyTo,
                        "reactions", serializedReactions
                );
            }
        }

        Map<String, Object> replyPreview() {
            synchronized (lock) {
                return Map.of(
                        "id", id,
                        "sender", sender,
                        "text", text.length() > 80 ? text.substring(0, 80) : text
                );
            }
        }
    }

    private static class ClientStream {
        final OutputStream output;
        volatile boolean closed;

        ClientStream(OutputStream output) {
            this.output = output;
        }
    }

    private static class StaticHandler implements HttpHandler {
        private final Path webRoot = Path.of("web");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed.");
                return;
            }

            String rawPath = exchange.getRequestURI().getPath();
            String local = "/".equals(rawPath) ? "/index.html" : rawPath;
            Path file = webRoot.resolve(local.substring(1)).normalize();

            if (!file.startsWith(webRoot) || Files.isDirectory(file) || !Files.exists(file)) {
                file = webRoot.resolve("index.html");
            }
            byte[] content = Files.readAllBytes(file);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", guessMime(file));
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(content);
            }
        }
    }

    private static String guessMime(Path file) {
        String mime = URLConnection.guessContentTypeFromName(file.getFileName().toString());
        return mime == null ? "text/plain; charset=utf-8" : mime;
    }

    private static class Json {
        static Object parse(String text) {
            return new Parser(text).parseValue();
        }

        static String stringify(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof String s) {
                return "\"" + escape(s) + "\"";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return value.toString();
            }
            if (value instanceof Map<?, ?> map) {
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(stringify(String.valueOf(entry.getKey())));
                    builder.append(':');
                    builder.append(stringify(entry.getValue()));
                }
                return builder.append('}').toString();
            }
            if (value instanceof Collection<?> collection) {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object item : collection) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(stringify(item));
                }
                return builder.append(']').toString();
            }
            return stringify(String.valueOf(value));
        }

        private static String escape(String value) {
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t");
        }

        private static class Parser {
            private final String input;
            private int index;

            Parser(String input) {
                this.input = input == null ? "" : input.trim();
            }

            Object parseValue() {
                skipWhitespace();
                if (index >= input.length()) {
                    return Collections.emptyMap();
                }
                char c = input.charAt(index);
                if (c == '{') {
                    return parseObject();
                }
                if (c == '[') {
                    return parseArray();
                }
                if (c == '"') {
                    return parseString();
                }
                if (c == 't' || c == 'f') {
                    return parseBoolean();
                }
                if (c == 'n') {
                    index += 4;
                    return null;
                }
                return parseNumber();
            }

            private Map<String, Object> parseObject() {
                Map<String, Object> result = new LinkedHashMap<>();
                index++;
                skipWhitespace();
                while (index < input.length() && input.charAt(index) != '}') {
                    String key = parseString();
                    skipWhitespace();
                    index++;
                    Object value = parseValue();
                    result.put(key, value);
                    skipWhitespace();
                    if (index < input.length() && input.charAt(index) == ',') {
                        index++;
                        skipWhitespace();
                    }
                }
                if (index < input.length()) {
                    index++;
                }
                return result;
            }

            private List<Object> parseArray() {
                List<Object> result = new ArrayList<>();
                index++;
                skipWhitespace();
                while (index < input.length() && input.charAt(index) != ']') {
                    result.add(parseValue());
                    skipWhitespace();
                    if (index < input.length() && input.charAt(index) == ',') {
                        index++;
                        skipWhitespace();
                    }
                }
                if (index < input.length()) {
                    index++;
                }
                return result;
            }

            private String parseString() {
                StringBuilder builder = new StringBuilder();
                index++;
                while (index < input.length()) {
                    char c = input.charAt(index++);
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\' && index < input.length()) {
                        char next = input.charAt(index++);
                        switch (next) {
                            case '"' -> builder.append('"');
                            case '\\' -> builder.append('\\');
                            case '/' -> builder.append('/');
                            case 'b' -> builder.append('\b');
                            case 'f' -> builder.append('\f');
                            case 'n' -> builder.append('\n');
                            case 'r' -> builder.append('\r');
                            case 't' -> builder.append('\t');
                            case 'u' -> {
                                String hex = input.substring(index, Math.min(index + 4, input.length()));
                                builder.append((char) Integer.parseInt(hex, 16));
                                index += 4;
                            }
                            default -> builder.append(next);
                        }
                    } else {
                        builder.append(c);
                    }
                }
                return builder.toString();
            }

            private Object parseNumber() {
                int start = index;
                while (index < input.length()) {
                    char c = input.charAt(index);
                    if (!(Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+')) {
                        break;
                    }
                    index++;
                }
                String value = input.substring(start, index);
                return value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value);
            }

            private Boolean parseBoolean() {
                if (input.startsWith("true", index)) {
                    index += 4;
                    return true;
                }
                index += 5;
                return false;
            }

            private void skipWhitespace() {
                while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                    index++;
                }
            }
        }
    }
}
