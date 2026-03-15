from __future__ import annotations

import json
import logging
import sys
import time


class StructuredFormatter(logging.Formatter):
    """JSON-structured log formatter."""

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(record.created)),
            "level": record.levelname,
            "module": record.name,
            "msg": record.getMessage(),
        }
        # Include extra fields passed via `extra=` kwarg
        for key in ("frame_id", "bytes", "buffer_depth", "duration_s",
                     "chunk_duration_s", "transcription_time_s", "rtf",
                     "segments", "text", "confidence", "start", "end",
                     "reason", "host", "port", "server", "sample_rate",
                     "chunk_size_bytes", "model", "device", "compute_type",
                     "client", "error"):
            val = getattr(record, key, None)
            if val is not None:
                entry[key] = val
        return json.dumps(entry)


def setup_logging(level: str = "INFO", name: str = "cocog") -> logging.Logger:
    """Configure structured JSON logging to stderr."""
    logger = logging.getLogger(name)
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))

    if not logger.handlers:
        handler = logging.StreamHandler(sys.stderr)
        handler.setFormatter(StructuredFormatter())
        logger.addHandler(handler)

    return logger
