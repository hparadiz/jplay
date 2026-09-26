#!/usr/bin/env python3
"""Import an existing Samba login through private ADB stdin, never argv or logs.

Requires the debug APK for run-as. After import, install the release APK with
the same signing key; encrypted app data survives and run-as is disabled.
"""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

# Defaults come from the environment, then the project's .env (see .env.example).
env = {}
env_file = Path(__file__).resolve().parent.parent / ".env"
if env_file.exists():
    for line in env_file.read_text().splitlines():
        m = re.match(r"^([A-Z_][A-Z0-9_]*)=(.*)$", line.strip())
        if m:
            env[m[1]] = m[2].strip("\"'")
def setting(key):
    return os.environ.get(key) or env.get(key) or None

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", default=setting("JPLAY_PHONE_SERIAL"), required=not setting("JPLAY_PHONE_SERIAL"))
p.add_argument("--credentials", type=Path, default=setting("JPLAY_NAS_CREDENTIALS"), required=not setting("JPLAY_NAS_CREDENTIALS"))
p.add_argument("--host", default=setting("JPLAY_NAS_HOST"), required=not setting("JPLAY_NAS_HOST"))
p.add_argument("--share", default=setting("JPLAY_NAS_SHARE"), required=not setting("JPLAY_NAS_SHARE"))
p.add_argument("--folder", default=setting("JPLAY_NAS_FOLDER") or "")
a = p.parse_args()
a.credentials = Path(a.credentials).expanduser()
credentials = {}
for line in a.credentials.read_text().splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        k, v = line.split("=", 1)
        credentials[k.strip()] = v.strip()
profile = dict(host=a.host, share=a.share, folder=a.folder,
               user=credentials.get("username", credentials.get("user", "")),
               password=credentials.get("password", ""),
               domain=credentials.get("domain", ""), bufferMs=3000)
adb = ["adb", "-s", a.serial]
subprocess.run(adb + ["shell", "am", "force-stop", "in.akuj.jplay"], check=True, stdout=subprocess.DEVNULL)
subprocess.run(adb + ["shell", "run-as", "in.akuj.jplay", "mkdir", "-p", "files"], check=True)
subprocess.run(adb + ["shell", "run-as in.akuj.jplay sh -c 'umask 077; cat > files/provision.json'"],
               input=json.dumps(profile).encode(), check=True)
subprocess.run(adb + ["shell", "am", "start", "-n", "in.akuj.jplay/.MainActivity"], check=True)
print("Private profile delivered; the app encrypts it on first launch.")
