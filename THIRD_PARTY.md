# LibVLC

- Component: `org.videolan.android:libvlc-all:3.7.6`
- Authors: VLC authors, VideoLAN, VideoLabs, and contributors
- License: GNU Lesser General Public License, version 2.1 or later
- License text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
- Project and native build instructions: https://code.videolan.org/videolan/libvlcjni
- Published artifacts: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.6/
- Java source: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.6/libvlc-all-3.7.6-sources.jar
- Embedded libvlcjni revision: `c0cc8ce`
- Embedded VLC revision: `8c96b44e6d`
- VLC source: https://code.videolan.org/videolan/vlc

The native `.so` files and library classes are packaged unmodified. JPlay's source
and build script allow replacement/relinking of the library for a personal build.
LibVLC in turn includes third-party codec and protocol libraries; consult its
corresponding source and contrib license notices before redistributing binaries.
The generated APK is currently a personal local install, not a published release.

# NAS crash uploads

Playback continues to use LibVLC. Small crash-report uploads use SMBJ and its
runtime dependencies, pinned in `smb-dependencies.txt` and `dependencies.sha256`.

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| SMBJ | 0.14.0 | Apache-2.0 | https://github.com/hierynomus/smbj |
| ASN One | 0.6.0 | Apache-2.0 | https://github.com/hierynomus/asn-one |
| Bouncy Castle provider | 1.79 | MIT | https://www.bouncycastle.org/licence.html |
| MBassador | 1.3.0 | MIT | https://github.com/bennidi/mbassador |
| SLF4J API and NOP provider | 2.0.9 | MIT | https://www.slf4j.org/license.html |

The build excludes desktop JVM multi-release class variants and JAR signatures,
then converts the base classes to Android DEX. The SLF4J NOP service provider is
retained so library debug logging cannot emit raw SMB authentication messages.

# Build tool

The direct build uses Google's R8/D8 8.6.24, checksum-pinned in
`dependencies.sha256`. SDK 34's bundled D8 8.2.2-dev crashed compiling Bouncy Castle
1.79; the pinned compiler completes the full release build. R8 is a build-time
dependency and is not included in the APK.

- Source, license notices and download instructions: https://r8.googlesource.com/r8/+/refs/tags/8.6.24
- Binary: https://storage.googleapis.com/r8-releases/raw/8.6.24/r8lib.jar

The native crash summary parser reads a small allowlist of fields from Android's
public tombstone schema: https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto
It does not bundle raw tombstones, generated protobuf code, logs or memory dumps.
