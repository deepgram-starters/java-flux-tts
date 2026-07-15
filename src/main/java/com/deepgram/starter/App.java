/**
 * Java Flux TTS Starter - Backend Server
 *
 * Bridges a browser WebSocket to Deepgram's Flux streaming text-to-speech
 * (v2 speak) using the official Deepgram Java SDK's `client.speak().v2().v2WebSocket()`.
 *
 * Unlike the Flux transcription starter (java-flux), which is a raw Jetty
 * WebSocket proxy, the Deepgram side here goes through the SDK, which manages the
 * connection, auth, and binary-audio framing.
 *
 * Flow:
 *   browser --(JSON: Speak/Flush/Close)--> backend --(SDK)--> Deepgram Flux TTS
 *   browser <--(binary audio + JSON control)-- backend <--(SDK)-- Deepgram Flux TTS
 *
 * Routes:
 *   GET  /api/session   - Issue JWT session token
 *   GET  /api/metadata  - Project metadata from deepgram.toml
 *   WS   /api/tts       - Streaming TTS bridge to Deepgram Flux (auth required)
 */

package com.deepgram.starter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.deepgram.DeepgramClient;
import com.deepgram.core.Environment;
import com.deepgram.resources.speak.v2.types.SpeakV2Close;
import com.deepgram.resources.speak.v2.types.SpeakV2Flush;
import com.deepgram.resources.speak.v2.types.SpeakV2Speak;
import com.deepgram.resources.speak.v2.websocket.V2ConnectOptions;
import com.deepgram.resources.speak.v2.websocket.V2WebSocketClient;
import com.deepgram.types.SpeakV2Encoding;
import com.deepgram.types.SpeakV2SampleRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.github.cdimascio.dotenv.Dotenv;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;

import java.io.File;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class App {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ---- Configuration ----
    private static final int PORT = Integer.parseInt(getEnv("PORT", "8081"));
    private static final String HOST = getEnv("HOST", "0.0.0.0");

    /** Default Flux TTS voice. Flux models follow `flux-{voice}-{language}`. */
    private static final String DEFAULT_MODEL = getEnv("DEEPGRAM_TTS_MODEL", "flux-alexis-en");
    private static final String DEFAULT_ENCODING = "linear16";
    private static final String DEFAULT_SAMPLE_RATE = "24000";

    private static final Set<Integer> RESERVED_CLOSE_CODES = Set.of(1004, 1005, 1006, 1015);

    // ---- Session auth (JWT) ----
    private static final String SESSION_SECRET = initSessionSecret();
    private static final long JWT_EXPIRY_SECONDS = 3600;
    private static final Algorithm jwtAlgorithm = Algorithm.HMAC256(SESSION_SECRET);
    private static final JWTVerifier jwtVerifier = JWT.require(jwtAlgorithm).build();

    /** One SDK client, reused across connections; the browser never sees the API key. */
    private static DeepgramClient deepgram;

    private static final Set<WsContext> activeConnections = ConcurrentHashMap.newKeySet();

    // ========================================================================
    // Helpers
    // ========================================================================

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        try {
            value = dotenv.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        } catch (Exception ignored) {
            // dotenv may not be available
        }
        return defaultValue;
    }

    private static String initSessionSecret() {
        String secret = getEnv("SESSION_SECRET", null);
        if (secret != null && !secret.isEmpty()) {
            return secret;
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String createSessionToken() {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(JWT_EXPIRY_SECONDS))
                .sign(jwtAlgorithm);
    }

    private static String validateWsToken(String protocols) {
        if (protocols == null || protocols.isEmpty()) return null;
        for (String proto : protocols.split(",")) {
            String trimmed = proto.trim();
            if (trimmed.startsWith("access_token.")) {
                String token = trimmed.substring("access_token.".length());
                try {
                    jwtVerifier.verify(token);
                    return trimmed;
                } catch (JWTVerificationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String loadApiKey() {
        String key = getEnv("DEEPGRAM_API_KEY", null);
        if (key == null || key.isEmpty() || key.equals("%api_key%")) {
            System.err.println();
            System.err.println("  ERROR: Deepgram API key not found!");
            System.err.println();
            System.err.println("Create a .env file with:");
            System.err.println("   DEEPGRAM_API_KEY=your_api_key_here");
            System.err.println();
            System.err.println("Get your API key at: https://console.deepgram.com");
            System.err.println();
            System.exit(1);
        }
        return key;
    }

    private static int getSafeCloseCode(int code) {
        if (code >= 1000 && code <= 4999 && !RESERVED_CLOSE_CODES.contains(code)) {
            return code;
        }
        return 1000;
    }

    private static SpeakV2Encoding parseEncoding(String value) {
        if (value == null) return SpeakV2Encoding.LINEAR16;
        switch (value.toLowerCase()) {
            case "mulaw":
                return SpeakV2Encoding.MULAW;
            case "alaw":
                return SpeakV2Encoding.ALAW;
            case "linear16":
            default:
                return SpeakV2Encoding.LINEAR16;
        }
    }

    private static SpeakV2SampleRate parseSampleRate(String value) {
        if (value == null) return SpeakV2SampleRate.TWENTY_FOUR_THOUSAND;
        switch (value) {
            case "8000":
                return SpeakV2SampleRate.EIGHT_THOUSAND;
            case "16000":
                return SpeakV2SampleRate.SIXTEEN_THOUSAND;
            case "32000":
                return SpeakV2SampleRate.THIRTY_TWO_THOUSAND;
            case "44100":
                return SpeakV2SampleRate.FORTY_FOUR_THOUSAND_ONE_HUNDRED;
            case "48000":
                return SpeakV2SampleRate.FORTY_EIGHT_THOUSAND;
            case "24000":
            default:
                return SpeakV2SampleRate.TWENTY_FOUR_THOUSAND;
        }
    }

    /** Forward a Deepgram control message to the browser as JSON: {"type": ..., "data": ...}. */
    private static void forwardControl(WsContext ctx, String type, Object message) {
        try {
            if (!ctx.session.isOpen()) return;
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", type);
            node.set("data", objectMapper.valueToTree(message));
            ctx.send(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            System.err.println("Error forwarding control message to client: " + e.getMessage());
        }
    }

    // ========================================================================
    // HTTP routes
    // ========================================================================

    private static void handleSession(Context ctx) {
        ctx.json(Map.of("token", createSessionToken()));
    }

    private static void handleMetadata(Context ctx) {
        try {
            TomlMapper tomlMapper = new TomlMapper();
            JsonNode tomlData = tomlMapper.readTree(new File("deepgram.toml"));
            JsonNode meta = tomlData.get("meta");
            if (meta == null) {
                ctx.status(500).json(Map.of(
                        "error", "INTERNAL_SERVER_ERROR",
                        "message", "Missing [meta] section in deepgram.toml"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metaMap = objectMapper.treeToValue(meta, Map.class);
            ctx.json(metaMap);
        } catch (Exception e) {
            System.err.println("Error reading metadata: " + e.getMessage());
            ctx.status(500).json(Map.of(
                    "error", "INTERNAL_SERVER_ERROR",
                    "message", "Failed to read metadata from deepgram.toml"));
        }
    }

    // ========================================================================
    // WebSocket bridge (/api/tts)
    // ========================================================================

    /**
     * Per-connection bridge to a Deepgram Flux TTS WebSocket. Buffers browser
     * messages that arrive before the Deepgram socket is open.
     */
    static final class TtsBridge {
        private final V2WebSocketClient ws;
        private boolean ready = false;
        private final List<JsonNode> pending = new ArrayList<>();

        TtsBridge(V2WebSocketClient ws) {
            this.ws = ws;
        }

        synchronized void dispatch(JsonNode msg) {
            if (!ready) {
                pending.add(msg);
                return;
            }
            send(msg);
        }

        synchronized void markReady() {
            ready = true;
            for (JsonNode m : pending) {
                send(m);
            }
            pending.clear();
        }

        private void send(JsonNode msg) {
            String type = msg.path("type").asText("");
            try {
                switch (type) {
                    case "Speak":
                        ws.sendSpeak(SpeakV2Speak.builder()
                                .text(msg.path("text").asText(""))
                                .build());
                        break;
                    case "Flush":
                        ws.sendFlush(SpeakV2Flush.builder().build());
                        break;
                    case "Close":
                        ws.sendClose(SpeakV2Close.builder().build());
                        break;
                    default:
                        System.out.println("Ignoring unknown client message type: " + type);
                }
            } catch (Exception e) {
                System.err.println("Failed to forward message to Deepgram: " + e.getMessage());
            }
        }

        void disconnect() {
            try {
                ws.disconnect();
            } catch (Exception ignored) {
                // already closed
            }
        }
    }

    private static void handleTtsWebSocket(WsConfig ws) {
        ws.onConnect(ctx -> {
            String protocols = ctx.header("Sec-WebSocket-Protocol");
            if (validateWsToken(protocols) == null) {
                System.out.println("WebSocket auth failed: invalid or missing token");
                ctx.closeSession(4401, "Unauthorized");
                return;
            }

            System.out.println("Client connected to /api/tts (authenticated)");
            activeConnections.add(ctx);

            String model = firstNonEmpty(ctx.queryParam("model"), DEFAULT_MODEL);
            String encoding = firstNonEmpty(ctx.queryParam("encoding"), DEFAULT_ENCODING);
            String sampleRate = firstNonEmpty(ctx.queryParam("sample_rate"), DEFAULT_SAMPLE_RATE);

            V2WebSocketClient dgSocket = deepgram.speak().v2().v2WebSocket();
            TtsBridge bridge = new TtsBridge(dgSocket);
            ctx.attribute("bridge", bridge);

            // Deepgram -> browser
            dgSocket.onSpeakV2Audio(audio -> {
                try {
                    if (ctx.session.isOpen()) {
                        ctx.send(ByteBuffer.wrap(audio.toByteArray()));
                    }
                } catch (Exception e) {
                    System.err.println("Error forwarding audio to client: " + e.getMessage());
                }
            });
            dgSocket.onConnected(connected -> forwardControl(ctx, "Connected", connected));
            dgSocket.onSpeechStarted(started -> forwardControl(ctx, "SpeechStarted", started));
            dgSocket.onSpeechMetadata(meta -> forwardControl(ctx, "SpeechMetadata", meta));
            dgSocket.onFlushed(flushed -> forwardControl(ctx, "Flushed", flushed));
            dgSocket.onSessionMetadata(meta -> forwardControl(ctx, "SessionMetadata", meta));
            dgSocket.onWarning(warning -> forwardControl(ctx, "Warning", warning));
            dgSocket.onErrorMessage(error -> forwardControl(ctx, "Error", error));

            dgSocket.onError(error -> {
                System.err.println("Deepgram socket error: " + error.getMessage());
                if (ctx.session.isOpen()) {
                    ctx.closeSession(1011, "Deepgram connection error");
                }
            });
            dgSocket.onDisconnected(reason -> {
                System.out.println("Deepgram connection closed: " + reason.getCode() + " " + reason.getReason());
                if (ctx.session.isOpen()) {
                    ctx.closeSession(getSafeCloseCode(reason.getCode()),
                            reason.getReason() != null ? reason.getReason() : "");
                }
            });

            V2ConnectOptions options = V2ConnectOptions.builder()
                    .model(model)
                    .encoding(parseEncoding(encoding))
                    .sampleRate(parseSampleRate(sampleRate))
                    .build();

            System.out.println("Connecting to Deepgram Flux TTS: model=" + model
                    + ", encoding=" + encoding + ", sample_rate=" + sampleRate);

            dgSocket.connect(options).whenComplete((v, err) -> {
                if (err != null) {
                    System.err.println("Deepgram connection failed to open: " + err.getMessage());
                    if (ctx.session.isOpen()) {
                        ctx.closeSession(1011, "Deepgram connection failed to open");
                    }
                    return;
                }
                System.out.println("Connected to Deepgram Flux TTS");
                bridge.markReady();
            });
        });

        // browser -> Deepgram (JSON control messages)
        ws.onMessage(ctx -> {
            TtsBridge bridge = ctx.attribute("bridge");
            if (bridge == null) return;
            try {
                JsonNode msg = objectMapper.readTree(ctx.message());
                bridge.dispatch(msg);
            } catch (Exception e) {
                System.err.println("Ignoring non-JSON message from client");
            }
        });

        ws.onClose(ctx -> {
            System.out.println("Client disconnected: " + ctx.status() + " "
                    + (ctx.reason() != null ? ctx.reason() : ""));
            TtsBridge bridge = ctx.attribute("bridge");
            if (bridge != null) {
                bridge.disconnect();
            }
            activeConnections.remove(ctx);
        });

        ws.onError(ctx -> {
            Throwable error = ctx.error();
            if (error != null) {
                System.err.println("Client WebSocket error: " + error.getMessage());
            }
            TtsBridge bridge = ctx.attribute("bridge");
            if (bridge != null) {
                bridge.disconnect();
            }
            activeConnections.remove(ctx);
        });
    }

    private static String firstNonEmpty(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    // ========================================================================
    // Entry point
    // ========================================================================

    public static void main(String[] args) {
        String apiKey = loadApiKey();

        // DEEPGRAM_BASE_URL (e.g. a staging host like wss://api.staging.deepgram.com)
        // overrides the default production endpoint. speak.v2 uses the environment's
        // production URL for the /v2/speak websocket.
        var builder = DeepgramClient.builder().apiKey(apiKey);
        String baseUrl = getEnv("DEEPGRAM_BASE_URL", null);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            String https = baseUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
            builder.environment(Environment.custom()
                    .base(https)
                    .production(baseUrl)
                    .agent(baseUrl)
                    .agentRest(https)
                    .build());
            System.out.println("Using custom Deepgram base URL: " + baseUrl);
        }
        deepgram = builder.build();

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.get("/api/session", App::handleSession);
        app.get("/api/metadata", App::handleMetadata);
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.ws("/api/tts", App::handleTtsWebSocket);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            System.out.println("Closing " + activeConnections.size() + " active connection(s)...");
            for (WsContext wsCtx : activeConnections) {
                try {
                    wsCtx.closeSession(1001, "Server shutting down");
                } catch (Exception e) {
                    System.err.println("Error closing WebSocket: " + e.getMessage());
                }
            }
            System.out.println("Shutdown complete");
        }));

        app.start(HOST, PORT);

        String separator = "=".repeat(70);
        System.out.println();
        System.out.println(separator);
        System.out.println("  Backend API running at http://localhost:" + PORT);
        System.out.println("  GET  /api/session");
        System.out.println("  WS   /api/tts (auth required)");
        System.out.println("  GET  /api/metadata");
        System.out.println("  GET  /health");
        System.out.println(separator);
        System.out.println();
    }
}
