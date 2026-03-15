from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass(frozen=True)
class AudioConfig:
    sample_rate: int = 16000
    channels: int = 1
    sample_width: int = 2  # bytes (16-bit)
    chunk_duration_s: float = 0.5
    buffer_threshold_s: float = 10.0
    vad_energy_threshold: float = 500.0
    vad_silence_duration_s: float = 1.5


@dataclass(frozen=True)
class TranscriptionConfig:
    model_size: str = "large-v3"
    device: str = "cuda"
    compute_type: str = "float16"
    beam_size: int = 5
    language: str = "en"


@dataclass(frozen=True)
class LoggingConfig:
    level: str = "INFO"
    transcript_dir: str = "transcripts"


@dataclass(frozen=True)
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 9090
    audio: AudioConfig = field(default_factory=AudioConfig)
    transcription: TranscriptionConfig = field(default_factory=TranscriptionConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)


@dataclass(frozen=True)
class ClientLoggingConfig:
    level: str = "INFO"


@dataclass(frozen=True)
class ClientConfig:
    server_url: str = "ws://localhost:9090/audio"
    sample_rate: int = 16000
    channels: int = 1
    chunk_duration_s: float = 0.5
    reconnect_delay_s: float = 2.0
    max_reconnect_attempts: int = 0  # 0 = infinite
    logging: ClientLoggingConfig = field(default_factory=ClientLoggingConfig)


@dataclass(frozen=True)
class AppConfig:
    server: ServerConfig = field(default_factory=ServerConfig)
    client: ClientConfig = field(default_factory=ClientConfig)


def _merge_env_overrides(data: dict) -> dict:
    """Apply environment variable overrides. E.g. COCOG_SERVER_PORT=9091."""
    env_map = {
        "COCOG_SERVER_HOST": ("server", "host"),
        "COCOG_SERVER_PORT": ("server", "port", int),
        "COCOG_CLIENT_SERVER_URL": ("client", "server_url"),
        "COCOG_TRANSCRIPTION_MODEL_SIZE": ("server", "transcription", "model_size"),
        "COCOG_TRANSCRIPTION_DEVICE": ("server", "transcription", "device"),
        "COCOG_TRANSCRIPTION_COMPUTE_TYPE": ("server", "transcription", "compute_type"),
        "COCOG_TRANSCRIPTION_LANGUAGE": ("server", "transcription", "language"),
        "COCOG_LOG_LEVEL": ("server", "logging", "level"),
    }
    for env_key, path_spec in env_map.items():
        val = os.environ.get(env_key)
        if val is None:
            continue
        *path, last = path_spec
        # Check if last element is a type converter
        converter = str
        if isinstance(last, type):
            converter = last
            *path, last = path[:-1] + [path[-1]]
            # Re-derive: path_spec without converter
            path = list(path_spec[:-2])
            last = path_spec[-2]
            converter = path_spec[-1]

        node = data
        for key in path:
            if not isinstance(key, type):
                node = node.setdefault(key, {})
        node[last] = converter(val)

    return data


def _dict_to_config(data: dict) -> AppConfig:
    """Convert nested dict to AppConfig dataclass tree."""
    server_data = data.get("server", {})
    client_data = data.get("client", {})

    audio = AudioConfig(**server_data.get("audio", {}))
    transcription = TranscriptionConfig(**server_data.get("transcription", {}))
    logging_cfg = LoggingConfig(**server_data.get("logging", {}))

    server = ServerConfig(
        host=server_data.get("host", "0.0.0.0"),
        port=server_data.get("port", 9090),
        audio=audio,
        transcription=transcription,
        logging=logging_cfg,
    )

    client_logging = ClientLoggingConfig(**client_data.get("logging", {}))
    client_fields = {k: v for k, v in client_data.items() if k != "logging"}
    client = ClientConfig(**client_fields, logging=client_logging)

    return AppConfig(server=server, client=client)


def load_config(path: str | Path | None = None) -> AppConfig:
    """Load config from YAML file with environment variable overrides."""
    data: dict = {}
    if path is not None:
        p = Path(path)
        if p.exists():
            with open(p) as f:
                data = yaml.safe_load(f) or {}
    else:
        # Try default locations
        for candidate in [Path("config.yaml"), Path("config.yml")]:
            if candidate.exists():
                with open(candidate) as f:
                    data = yaml.safe_load(f) or {}
                break

    data = _merge_env_overrides(data)
    return _dict_to_config(data)
