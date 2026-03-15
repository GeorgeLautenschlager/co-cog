"""Top-level entry point: python -m cocog server|client"""

from __future__ import annotations

import sys


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] not in ("server", "client"):
        print("Usage: python -m cocog <server|client> [config.yaml]")
        sys.exit(1)

    command = sys.argv[1]
    # Remove the subcommand from argv so sub-modules see [script, config_path?]
    sys.argv = [sys.argv[0]] + sys.argv[2:]

    if command == "server":
        from cocog.server.__main__ import main as server_main

        server_main()
    elif command == "client":
        from cocog.client.__main__ import main as client_main

        client_main()


if __name__ == "__main__":
    main()
