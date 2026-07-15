# Java Flux Text-to-Speech

Get started using Deepgram's **Flux streaming text-to-speech** (v2 speak) with this Java demo app.

Unlike the Flux **transcription** starter ([`java-flux`](https://github.com/deepgram-starters/java-flux)), which is a raw Jetty WebSocket proxy, this starter uses the official [Deepgram Java SDK](https://github.com/deepgram/deepgram-java-sdk) `client.speak().v2().v2WebSocket()` on the backend — the SDK manages the Deepgram connection, auth, and binary-audio framing.

## How it works

```
Browser  ──(JSON control: Speak / Flush / Close)──▶  Javalin backend  ──(Java SDK speak.v2)──▶  Deepgram Flux TTS
Browser  ◀──(binary audio frames + JSON control)───  Javalin backend  ◀──(Java SDK speak.v2)───  Deepgram Flux TTS
```

- The backend never exposes your API key — the browser authenticates with a short-lived JWT (`/api/session`), passed on the WebSocket `access_token.<jwt>` subprotocol.
- One `V2WebSocketClient` is opened per browser client and bridges messages both ways.
- Audio frames from Deepgram (`onSpeakV2Audio`, delivered as `ByteString`) are forwarded to the browser as binary; control messages (`Connected`, `SpeechStarted`, `SpeechMetadata`, `Flushed`, `Warning`, `Error`, …) are forwarded as JSON `{"type": ..., "data": ...}`.

### Client → backend message protocol

Send JSON text frames on the `/api/tts` WebSocket:

```jsonc
{ "type": "Speak", "text": "Hello from Flux." }   // synthesize text
{ "type": "Flush" }                                // finish the current turn, flush audio
{ "type": "Close" }                                // end the session
```

## Prerequisites

- Java 21+, Maven 3.9+
- A Deepgram API key ([console.deepgram.com](https://console.deepgram.com/))

## Local Development

### Makefile (Recommended)

```bash
make init
cp sample.env .env   # add your DEEPGRAM_API_KEY
make start           # backend on http://localhost:8081
```

### Maven

```bash
git clone https://github.com/deepgram-starters/java-flux-tts.git
cd java-flux-tts
mvn package -DskipTests
cp sample.env .env   # add your DEEPGRAM_API_KEY
java -jar target/java-flux-tts-1.0.0-shaded.jar
```

## Configuration

Environment variables (see `sample.env`):

| Variable | Default | Description |
|---|---|---|
| `DEEPGRAM_API_KEY` | — | **Required.** Your Deepgram API key. |
| `PORT` | `8081` | Backend port. |
| `HOST` | `0.0.0.0` | Backend host. |
| `DEEPGRAM_TTS_MODEL` | `flux-alexis-en` | Flux voice (`flux-{voice}-{language}`). |
| `SESSION_SECRET` | random per boot | Set in production for stable JWT signing. |

`model`, `encoding`, and `sample_rate` may also be passed as query params on the `/api/tts` WebSocket.

## License

MIT - See [LICENSE](./LICENSE)
