#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
errors = []

if (root / "keystore/debug.keystore").exists():
    errors.append("committed debug.keystore is forbidden")

pins = (root / "common-security/src/main/java/com/understory/security/SuitePins.kt").read_text()
if "DEBUG_CERT_SHA256" in pins:
    errors.append("debug certificate must not be represented as a trust pin")
if "DEBUG_IDENTITY_TRUSTED = false" not in pins:
    errors.append("debug trust revocation marker missing")

for rel, marker in [
    ("common-security/src/main/java/com/understory/security/Tamper.kt", "if (BuildConfig.DEBUG) return true"),
    ("common-security/src/main/java/com/understory/security/SuiteAttestation.kt", "if (BuildConfig.DEBUG)"),
    ("common-security/src/main/java/com/understory/security/SuiteCapabilityRegistry.kt", "if (BuildConfig.DEBUG)"),
]:
    if marker not in (root / rel).read_text():
        errors.append(f"{rel} does not enforce the debug trust boundary")

for pattern in ("*.jks", "*.p12", "*.pfx", "*.keystore"):
    for path in root.rglob(pattern):
        if ".git" not in path.parts:
            errors.append(f"private-key container present: {path.relative_to(root)}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print("public signing boundary: valid")
