# co-cog

A wearable AI assistant prototype — captures ambient audio, transcribes in real time, and (soon) proactively supports focus, goal-tracking, and detail recall.

## Architecture

```
Phone (client)                    Local machine (server)
┌─────────────┐    WebSocket     ┌──────────────┐
│ sounddevice │ ──── PCM ──────► │ ws_receiver  │
│ 16kHz/mono  │    (binary)      │  raw_queue   │
└─────────────┘                  └──────┬───────┘
                                        │
                                 ┌──────▼───────┐
                                 │ audio_buffer  │
                                 │ + energy VAD  │
                                 └──────┬───────┘
                                        │
                                 ┌──────▼───────┐
                                 │ transcriber   │
                                 │ faster-whisper│
                                 └──────┬───────┘
                                        │
                                 ┌──────▼───────┐
                                 │ transcript    │
                                 │ JSONL output  │
                                 └──────────────┘
```

## Setup

```bash
# Install (editable, with all optional deps)
pip install -e ".[client,dev]"

# Or just install requirements
pip install -r requirements.txt
```

## Usage

**Start the server** (on the machine with a GPU):

```bash
python -m cocog server
# Or with a custom config:
python -m cocog server config.yaml
```

**Start the client** (on the phone or same machine):

```bash
python -m cocog client
```

**Environment overrides** (useful for testing):

```bash
COCOG_TRANSCRIPTION_MODEL_SIZE=tiny python -m cocog server
COCOG_CLIENT_SERVER_URL=ws://192.168.1.50:9090/audio python -m cocog client
```

## Configuration

Edit `config.yaml` to change defaults. Key settings:

| Setting | Default | Description |
|---------|---------|-------------|
| `server.port` | 9090 | WebSocket server port |
| `server.audio.buffer_threshold_s` | 10.0 | Max seconds before forced flush |
| `server.audio.vad_silence_duration_s` | 1.5 | Silence needed to trigger flush |
| `server.transcription.model_size` | large-v3 | Whisper model (tiny/base/small/medium/large-v3) |
| `server.transcription.device` | cuda | Device for inference |

## Tests

```bash
pytest tests/ -v
```

All tests run without GPU or microphone (transcription is mocked).

## License

GPL-3.0
