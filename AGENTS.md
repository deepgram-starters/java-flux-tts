# java-flux-tts

Java (Javalin) demo app for Deepgram Flux streaming text-to-speech (v2 Speak).

## Architecture

- **Backend:** Java (Javalin) on port 8081
- **Frontend:** Vite + vanilla JS on port 8080 (git submodule: `flux-tts-html`)
- **API type:** WebSocket — `WS /api/tts`
- **Deepgram API:** Flux v2 Speak — streaming text-to-speech (`wss://api.deepgram.com/v2/speak`)
- **Auth:** JWT session tokens via `/api/session` (WebSocket auth uses the `access_token.<jwt>` subprotocol)

## Key Files

| File | Purpose |
|------|---------|
| `src/main/java/com/deepgram/starter/App.java` | Backend — HTTP endpoints + Deepgram `speak.v2` WebSocket bridge |
| `deepgram.toml` | Metadata, lifecycle commands, tags |
| `Makefile` | Standardized build/run targets |
| `sample.env` | Environment variable template |
| `frontend/` | `flux-tts-html` submodule — text input, streamed audio playback |
| `contracts/` | `starter-contracts` submodule — conformance tests |

## Quick Start

```bash
make init            # clone submodules + install dependencies
cp sample.env .env   # then set DEEPGRAM_API_KEY
make start           # backend on :8081, frontend on :8080
```

Open http://localhost:8080.

## Endpoints

- `GET /api/session` — issues a short-lived JWT for WebSocket auth
- `GET /api/metadata` — project metadata from `deepgram.toml`
- `WS  /api/tts` — streaming TTS bridge (auth required)

## Message flow (browser <-> backend <-> Deepgram)

Client -> server (JSON control messages):

```jsonc
{ "type": "Speak", "text": "..." }   // synthesize text
{ "type": "Flush" }                   // finish the turn, flush audio
{ "type": "Close" }                   // end the session
```

Server -> client:

- binary `linear16` audio frames (played via Web Audio in the frontend)
- JSON control messages: `Connected`, `SpeechStarted`, `SpeechMetadata`, `Flushed`, `Warning`, `Error`

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DEEPGRAM_API_KEY` | — | **Required.** Deepgram API key. |
| `DEEPGRAM_BASE_URL` | production | Override the Deepgram endpoint (e.g. a staging host). |
| `DEEPGRAM_TTS_MODEL` | `flux-alexis-en` | Flux voice (`flux-{voice}-{language}`). |
| `PORT` | `8081` | Backend port. |
| `HOST` | `0.0.0.0` | Backend host. |
| `SESSION_SECRET` | random per boot | Set in production for stable JWT signing. |

`model`, `encoding`, and `sample_rate` may also be passed as query params on `/api/tts`.

## Notes

- The API key stays server-side; the browser only ever receives a short-lived JWT.
- Backend uses the Deepgram Java SDK `client.speak().v2().v2WebSocket()`. `mvn package` produces `target/java-flux-tts-1.0.0-shaded.jar`; requires JDK 21+.
