"""Build the audited pure-Python Bleak compatibility wheel used on Android."""

from __future__ import annotations

import base64
import csv
import hashlib
import sys
import zipfile
from io import StringIO
from pathlib import Path

NAME = "bleak"
VERSION = "3.0.2"
DIST_INFO = f"{NAME}-{VERSION}.dist-info"
REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIR = REPO_ROOT / "app" / "stubs" / "bleak"


def record_row(path: str, data: bytes) -> tuple[str, str, str]:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=")
    return path, f"sha256={digest.decode('ascii')}", str(len(data))


def record(rows: list[tuple[str, str, str]]) -> bytes:
    output = StringIO()
    writer = csv.writer(output, lineterminator="\n")
    writer.writerows(rows)
    writer.writerow((f"{DIST_INFO}/RECORD", "", ""))
    return output.getvalue().encode()


def build(output: Path) -> None:
    entries = {
        path.relative_to(SOURCE_DIR).as_posix(): path.read_bytes()
        for path in sorted(SOURCE_DIR.rglob("*.py"))
    }
    entries[f"{DIST_INFO}/METADATA"] = (
        "Metadata-Version: 2.1\nName: bleak\nVersion: 3.0.2\n"
        "Summary: Android stub for FindMy.py's unused desktop BLE scanner\n"
    ).encode()
    entries[f"{DIST_INFO}/WHEEL"] = (
        "Wheel-Version: 1.0\nGenerator: build_bleak_stub_wheel.py\n"
        "Root-Is-Purelib: true\nTag: py3-none-any\n"
    ).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as wheel:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            wheel.writestr(info, data)
            rows.append(record_row(name, data))
        info = zipfile.ZipInfo(f"{DIST_INFO}/RECORD", (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        wheel.writestr(info, record(rows))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: build_bleak_stub_wheel.py OUTPUT")
    build(Path(sys.argv[1]))
