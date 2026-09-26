#!/usr/bin/env bash
# Build with the installed Android SDK, without an IDE or a Gradle daemon.
set -euo pipefail
umask 077
cd -- "$(dirname -- "$0")"
variant="${1:-release}"
[[ "$variant" == release || "$variant" == debug ]] || { echo 'Usage: ./build.sh [release|debug]' >&2; exit 1; }
# Local settings live in .env (see .env.example). Variables already in the environment win.
if [[ -f .env ]]; then
    while IFS='=' read -r key value; do
        [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
        value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
        [[ -n "${!key+x}" ]] || export "$key=$value"
    done < .env
fi
controller="${JPLAY_CONTROLLER:-}"
if [[ -n "$controller" && ! "$controller" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ && ! "$controller" =~ ^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$ ]]; then
    echo "JPLAY_CONTROLLER must be an IP address, not '$controller'" >&2; exit 1
fi
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
java_dir="${JPLAY_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ ! -x "$java_dir/bin/javac" && -x /usr/lib/jvm/openjdk-bin-25/bin/javac ]]; then java_dir=/usr/lib/jvm/openjdk-bin-25; fi
tools_dir="$sdk_dir/build-tools/34.0.0"
android_jar="$sdk_dir/platforms/android-34/android.jar"
for path in "$java_dir/bin/javac" "$tools_dir/aapt2" "$tools_dir/zipalign" "$android_jar"; do
    [[ -f "$path" ]] || { echo "Missing build component: $path" >&2; exit 1; }
done
mkdir -p .deps build
aar=.deps/libvlc-all-3.7.6.aar
if [[ ! -f "$aar" ]]; then curl -fL --retry 2 https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.6/libvlc-all-3.7.6.aar -o "$aar"; fi
# SDK 34's D8 8.2.2 crashes on Bouncy Castle 1.79; use Google's pinned compiler.
d8_jar=.deps/r8-8.6.24.jar
if [[ ! -f "$d8_jar" ]]; then curl -fL --retry 2 https://storage.googleapis.com/r8-releases/raw/8.6.24/r8lib.jar -o "$d8_jar"; fi
mkdir -p .deps/smb
while read -r artifact; do
    [[ -n "$artifact" ]] || continue
    jar_name="${artifact##*/}"
    if [[ ! -f ".deps/smb/$jar_name" ]]; then
        curl -fL --retry 2 "https://repo.maven.apache.org/maven2/$artifact" -o ".deps/smb/$jar_name"
    fi
done < smb-dependencies.txt
sha256sum -c dependencies.sha256
work=build/intermediates
rm -rf -- "$work"
mkdir -p "$work"/{vlc,generated,classes,dex,jars,package/lib}
unzip -q "$aar" -d "$work/vlc"
"$tools_dir/aapt2" compile --dir app/src/main/res -o "$work/app-res.zip"
"$tools_dir/aapt2" compile --dir "$work/vlc/res" -o "$work/vlc-res.zip"
# Build-time defaults from .env, as an overlay so nothing personal lives in app/src.
mkdir -p "$work/config-res/values"
printf '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <string name="default_controller" translatable="false">%s</string>\n</resources>\n' \
    "$controller" > "$work/config-res/values/config.xml"
"$tools_dir/aapt2" compile --dir "$work/config-res" -o "$work/config-res.zip"
resource_options=()
[[ "$variant" != debug ]] || resource_options+=(--debug-mode)
"$tools_dir/aapt2" link -I "$android_jar" --manifest app/src/main/AndroidManifest.xml \
    --java "$work/generated" --extra-packages org.videolan "${resource_options[@]}" \
    -o "$work/resources.apk" "$work/app-res.zip" "$work/vlc-res.zip" "$work/config-res.zip"
mapfile -t sources < <(find app/src/main/java "$work/generated" -name '*.java' | sort)
classpath="$android_jar:$work/vlc/classes.jar"
dependency_jars=()
while read -r artifact; do
    [[ -n "$artifact" ]] || continue
    jar_name="${artifact##*/}"
    # D8 targets Android, so exclude newer-JDK multi-release variants and signatures.
    python3 - ".deps/smb/$jar_name" "$work/jars/$jar_name" "$work/package" <<'PY'
import sys, zipfile
from pathlib import Path
source, target, package = sys.argv[1:]
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
    for item in original.infolist():
        name = item.filename
        if name.endswith('.class') and not name.startswith('META-INF/') and name != 'module-info.class':
            output.writestr(name, original.read(item))
        elif name.startswith('META-INF/services/') and not item.is_dir():
            destination=Path(package, name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(original.read(item))
PY
    dependency_jars+=("$work/jars/$jar_name")
    classpath="$classpath:$work/jars/$jar_name"
done < smb-dependencies.txt
"$java_dir/bin/javac" --release 8 -parameters -Xlint:unchecked -cp "$classpath" -d "$work/classes" "${sources[@]}"
"$java_dir/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
"$java_dir/bin/java" -cp "$d8_jar" com.android.tools.r8.D8 --"$variant" \
    --lib "$android_jar" --min-api 26 --output "$work/dex" "$work/classes.jar" "$work/vlc/classes.jar" "${dependency_jars[@]}"
cp "$work/resources.apk" "$work/unsigned.apk"
cp "$work/dex/"*.dex "$work/package/"
# Default to the phone's ABI. Set JPLAY_ABIS to include more, separated by spaces.
for abi in ${JPLAY_ABIS:-arm64-v8a}; do cp -a "$work/vlc/jni/$abi" "$work/package/lib/"; done
cp -a "$work/vlc/assets" "$work/package/"
(cd "$work/package" && zip -qr ../unsigned.apk .)
"$tools_dir/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
if [[ ! -f build/jplay.keystore ]]; then
    "$java_dir/bin/keytool" -genkeypair -keystore build/jplay.keystore -storepass android -keypass android \
        -alias jplay -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=JPlay local development' -noprompt
fi
apk="build/jplay-$variant.apk"
"$java_dir/bin/java" -jar "$tools_dir/lib/apksigner.jar" sign --ks build/jplay.keystore --ks-key-alias jplay \
    --ks-pass pass:android --key-pass pass:android --out "$apk" "$work/aligned.apk"
"$java_dir/bin/java" -jar "$tools_dir/lib/apksigner.jar" verify --verbose "$apk"
"$tools_dir/zipalign" -c 4 "$apk"
if [[ "$variant" == release ]] && "$tools_dir/aapt2" dump badging "$apk" | grep -q application-debuggable; then
    echo 'Release APK must not be debuggable.' >&2
    exit 1
fi
sha256sum "$apk"
echo "Built $apk"
