"""Make the nanoexchange-client source tree importable without install."""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT_SRC = ROOT / "client" / "src"
if str(CLIENT_SRC) not in sys.path:
    sys.path.insert(0, str(CLIENT_SRC))

ANALYTICS_SRC = ROOT / "analytics" / "src"
if str(ANALYTICS_SRC) not in sys.path:
    sys.path.insert(0, str(ANALYTICS_SRC))
