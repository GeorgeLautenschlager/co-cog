from __future__ import annotations

import os
from pathlib import Path

import yaml
import pytest

from cocog.config import load_config, AppConfig


def test_load_defaults():
    """Loading with no file gives all defaults."""
    cfg = load_config(path="/nonexistent/path.yaml")
    assert cfg.server.port == 9090
    assert cfg.server.audio.sample_rate == 16000
    assert cfg.server.transcription.model_size == "large-v3"
    assert cfg.client.server_url == "ws://localhost:9090/audio"


def test_load_from_yaml(tmp_path):
    """Loading from a YAML file picks up values."""
    cfg_path = tmp_path / "test.yaml"
    with open(cfg_path, "w") as f:
        yaml.dump({
            "server": {"port": 8080, "transcription": {"model_size": "tiny"}},
            "client": {"server_url": "ws://10.0.0.1:8080/audio"},
        }, f)

    cfg = load_config(cfg_path)
    assert cfg.server.port == 8080
    assert cfg.server.transcription.model_size == "tiny"
    assert cfg.client.server_url == "ws://10.0.0.1:8080/audio"
    # Defaults still apply for unset fields
    assert cfg.server.audio.sample_rate == 16000


def test_env_overrides(tmp_path, monkeypatch):
    """Environment variables override YAML values."""
    cfg_path = tmp_path / "test.yaml"
    with open(cfg_path, "w") as f:
        yaml.dump({"server": {"port": 9090}}, f)

    monkeypatch.setenv("COCOG_TRANSCRIPTION_MODEL_SIZE", "small")
    monkeypatch.setenv("COCOG_CLIENT_SERVER_URL", "ws://custom:1234/audio")

    cfg = load_config(cfg_path)
    assert cfg.server.transcription.model_size == "small"
    assert cfg.client.server_url == "ws://custom:1234/audio"


def test_config_is_frozen():
    """Config dataclasses are immutable."""
    cfg = load_config(path="/nonexistent.yaml")
    with pytest.raises(AttributeError):
        cfg.server.port = 1234  # type: ignore[misc]
