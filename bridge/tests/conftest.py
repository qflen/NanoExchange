import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for p in (ROOT / "bridge" / "src", ROOT / "client" / "src"):
    s = str(p)
    if s not in sys.path:
        sys.path.insert(0, s)
