> Historical verification log from the agent sessions that built Just Play, newest first. It describes
> builds up to 1.5.0; Multiplay, covered below, was removed in 1.5.1. Referenced `build/` logs
> and captures were local and are not part of the repository.

# Current release — 1.4.1/code 9 — installed, verified and paused

Final APK SHA-256: `d87cc899b6c8e8d3f2c72a3f181de68f63b0e787cfe9ef2c9f4537c74c9d0c38`.
`build/multiplay-four-labels-release.log` passes Java/D8, APK v2/v3 signatures,
alignment and non-debuggable checks. Only picker display labels and active tab
styling changed from the installed behavioral-pass build 501c31bb. Episode codes
come first (e.g. E15 · Shingeki no Kyojin) with recognized release/quality clutter
removed from display only; metadata and original filenames remain intact.
Files/Recent active tab now has a pink fill and dark text. Final APK is installed.
Platform independently inspected `just-play-picker-final-files.png`: E01/E02/E03/E04
and series names are visibly distinct in both cards and tray, with × removal and
Add 4 videos. `four-polish-files.xml` and `four-polish-recent.xml` confirm the active
tab switches and all four selections persist. Source/build are frozen; no further
source changes are needed. Library left the saved four-slot wall paused.

On 501c31bb, library verified exactly four migrated paused slots in 2×2, previous
focused episode 15 moved to tile 1 with 6:20/24:08. Paused Wake Locks: size=0. All four
advance 241–247 frames over 10.247s, lost 0, only slot 0 audible
(`build/four-summary.json`). Single empty-tile selection returns playback on one
video tap, without an Add step (`four-single-picker.xml`, `four-single-selected.png`).

Batch selection PASS: with slot 1 already filled, select episodes 02/03, navigate
to parent Anime and back, both remain selected. Add 04 reaches3 of 3; attempting an
extra choice leaves count 3. Tap 03 in the tray removes it, count 2; reselect restores 3.
Add 3 returns the wall with occupied 01 plus02/04/03. Evidence: `four-parent-selection.xml`,
`four-return-selection.xml`, `four-capacity.xml`, `four-removal.xml`,
`four-final-add.xml`, `four-batch-result.png` and log. `four-selection-tray.png`
exposed identical truncated release prefixes, addressed by the final label update.

Final ready-state PASS: library canceled the four-choice display preview without
altering the saved wall, then Restore returned the original four slots paused.
`build/four-release-ready.xml` shows **0 PLAYING · 4 / 4 TILES**, all four statuses
Paused, and **Play all**. Saved order is episodes 01/02/04/03 with tile 2 selected for
audio. `build/four-release-ready-power.txt` reports **Wake Locks: size=0**.
`build/four-release-installed.txt` confirms **1.4.1/code 9**. Platform independently
read these final XML, power and package records. Library visually checked both
`just-play-picker-final-files.png` and `just-play-picker-final-recent.png`: distinct
E01–E04 with retained series names, pink/dark active-tab contrast, and retained
four-choice selection after switching. No further actions are pending for this
correction; implementation/source/build remain frozen.

# Historical verification archive

The records below retain their original time and artifact context. Any older
“pending” or “in progress” wording is historical. The final section above resolves
all four-slot/501 batch/display/paused-screen checks; it does not claim coverage of
separately documented untested PiP routes, every codec or sustained thermal limits.
Sixteen-slot results describe a superseded implementation, not the current scope.

## Installed 501c31bb device checks — initial capture

The corrected 501c31bb… APK is installed. Platform reviewed the in-app
four-migrated.png and XML: exactly four cells arranged 2×2; all restored paused.
Previous focused episode15 is now tile1 at6:20 /24:08, followed by episodes01–03.
four-paused-power.txt reports Wake Locks:size=0. Library reports the paused screen
flag check passed. four-playing.log (PID27283)18:53:34.923→45.177 shows all four
positions and displayed frames advancing, lost0; only slot0 has volume100 and
advancing audio, the remaining three have volume0 and audio0.

four-single-picker.xml shows compact one-tile selection. four-single-selected.png
shows the chosen video rendered at its original aspect in tile1 after return.
four-batch-picker.xml shows an occupied tile1 plus three selectable destinations
and a fixed four-tile tray. Cross-folder batch selection/removal/Add interaction
validation later passed as recorded in the current release section above. No platform phone inputs.

## Corrected four-video release — 1.4.1/code 9

Aku corrected the requested scope to four videos total in a 2×2 layout and rejected
the previous selection UX. This supersedes the sixteen-slot product scope below.

APK SHA-256: `501c31bb4f86e9bd465b80859e3aeff355eaf1be5f881779ef53e2ef8ea7c777`.
`build/multiplay-four-release.log` passes Java/D8, APK v2/v3 signatures, zip
alignment and non-debuggable checks. Service/player capacity and picker are four;
grid uses two rows and columns. Legacy restore migrates focused video plus up to
three others with saved state. Paused-screen condition is foreground&&playing>0.

Picker now has compact navigation/search without the show metadata panels,
one-tap single selection, a persistent four-destination names/thumbnails tray,
numbered selection badges, direct removal, and Add N videos. Device validation of
this corrected artifact later passed as recorded above. Earlier sixteen-slot
measurements below describe the superseded implementation, not user acceptance.

## Final cached-duration build — 1.4.0/code 8

Release APK SHA-256: `7aa4674e9a3e588c3816f9515dda86f28156e30e3539e865bcd205eb8a0075fd`.
Build log: `build/multiplay-final-release.log`. Java/D8, APK v2/v3 signing,
alignment and non-debuggable checks pass. The only source change since the
geometry-tested build initializes a slot's title and duration from the same
cached LibraryStore.Item. Final installation and paused-restore duration
validation remain with the library/device session. Source/build are frozen.

## Historical release — 1.4.0/code 8 (superseded sixteen-slot implementation)

Final APK SHA-256:
`7aa4674e9a3e588c3816f9515dda86f28156e30e3539e865bcd205eb8a0075fd`.
Installed in place on the Pixel 9 Pro; NAS profile and saved state retained.
Full compile/D8, APK v2/v3 signatures, zip alignment and non-debuggable checks
pass (`build/multiplay-final-release.log`). The final correction initializes a
slot's duration from its existing library metadata. The complete sixteen-stream
and lifecycle pass below used geometry-corrected build `a5f98e0951f757eb37323e796ac1d981b2f2f8b322cbef50ba61411314c03958`;
only that cached-duration initialization changed afterward.

Final-device checks passed: opening after Stop offered Restore without playing;
explicit Restore returned all 16 paused assignments, focused tile 16 displayed
`14:43 / 24:08`, and seeking was enabled before creating a decoder. +10s changed
the paused position to 14:53. Focused playback resumed there, expanded to a visible
video at the correct aspect, and returned to the grid. The wall is left paused
and ready; CPU wake locks are zero and rotation remains free. Evidence:
`build/multi-final-empty.xml`, `build/multi-final-restored.xml`,
`build/multi-final-seek.xml`, `build/multiplay-expanded.png`,
`build/multi-final-ready.xml`, `build/multi-final.log`.

The NAS picker selected sixteen different episodes from the same season using
**Select videos shown**. Independent ffprobe of all sixteen sources confirms
HEVC Main10, 1920×1080, 24000/1001fps (`build/multiplay-source-videos.json`).
All sixteen play concurrently. In the corrected build, 18:28:41.587→18:28:51.849,
each slot advances 242–248 displayed frames, every lost counter remains 0, and all
positions advance. Selected slot 15 alone has volume 100 and increasing audio;
all other audio counters remain flat with volume 0. Evidence:
`build/multi-final-sixteen-summary.json`, `build/multi-geometry-steady.log`.
Earlier startup and four-minute samples also showed all 16 advancing with lost 0.
This is a measured sample, not a sustained thermal or all-codec capacity guarantee.

Portrait and landscape screenshots show all sixteen real videos at their original
aspect. Initial TextureView Matrix scaling applied a second fit over native
letterboxing and visibly squashed images; removing that scaling corrected both
geometry and overlay visibility. `build/multiplay-landscape.png` is the inspected
in-app view. Temporary rotation lock was restored to its original `free` setting.

Tapping tile 16 moved audio from slot 0 to slot 15. **Pause all** held all sixteen
positions, displayed counters and audio counters exactly across 10.291s; resume
worked. The app update preserved all sixteen assignments, desired paused states,
focused slot 15 and its 4:12 position. Opening the empty wall offered **Restore wall**
without auto-playing, and explicitly restoring then selecting **Play all** resumed
all sixteen. `build/multi-focus-sixteen.log`, `build/multi-pause-summary.json`,
`build/multi-geometry-restore.xml`, `build/multi-geometry-restored.xml`.

Home entered actual PiP, task 7221 mode=pinned, with the selected video visible.
The other fifteen native players retired; service statistics report active=1,
playing=1, retiring=0, focused=15 and grid=false. The resize dropped 15 frames;
subsequent PiP samples held lost=15 while video/audio/position advanced. Returning
through the library's Multiplay button restored active=16/playing=16 from saved
positions. The focused native stream continued; its lost count rose once more to 16
on return, then held. `build/multi-pip-activities.txt`, `build/multi-pip-steady.log`,
`build/multi-return-grid.log`, `build/multi-background-library.log`.
The private PiP screenshots include the home screen and must not be published.
Android's transient PiP fullscreen/close buttons were not successfully validated;
app-based return is verified. A separate screen-off Multiplay check was not run.

Back returned to the library with one selected audio stream continuing and the
Multiplay Now playing bar visible. Returning, pausing all, then **Stop all**
removed the service and MediaSession. Power reports Wake Locks: size=0, with no
Multiplay Wi-Fi lock tag; native teardown completed. `build/multi-stop.log`,
`build/multi-stop-services.txt`, `build/multi-stop-session.txt`,
`build/multi-stop-locks.txt`. Retained app exits during this pass are package
updates; there is no new Java/native crash or ANR (`build/multi-exits.txt`).

No tests or test infrastructure were added. Manual checks do not cover every
codec mix, Wi-Fi failure, failed-slot retry, duplicate-URI history, focused removal,
or every external Android media-control route.

## Installed 1.4.0 geometry build — restore and PiP pass

Library installed the corrected `a5f98e…` APK. Platform independently reviewed
`build/multiplay-landscape.png`: proper video aspect and all 16 numbered overlays
are visible. `build/multiplay-source-videos.json` confirms16 distinct files, all
HEVC Main10 1920×1080 at24000/1001 fps. The saved wall restored16 paused assignments
and positions after the update (`multi-geometry-restore.xml` and
`multi-geometry-restored.xml`); Play all resumed16 with lost 0 in the grid logs.
The library restored Android's original free-rotation setting after its landscape
check. Paused restored duration initially displays0:00 until opening playback;
this minor cached-duration initialization issue is recorded for follow-up.

Home→PiP leaves one active/playing focused stream and retires15 other native
players. In `build/multi-pip-steady.log`,18:30:13.639→18:30:44.201 advances position
390,711→421,284ms, displayed frames3,295→4,028, audio13,142→16,008. The transition
adds15 lost frames; that counter remains15 throughout the following30 seconds.
This is stable PiP playback after a measurable transition drop, not a zero-loss
transition. Visible PiP/task pinning was checked by the library session. The
private PiP/home screenshot is excluded from published evidence because it
contains unrelated calendar content. Platform did not open that private image.
Expand/Stop and background-library return checks are still in progress.

## Geometry correction build — 1.4.0/code8

Rebuilt APK SHA-256
`a5f98e0951f757eb37323e796ac1d981b2f2f8b322cbef50ba61411314c03958`.
Full release checks pass (`build/multiplay-geometry-release.log`). This replaces
`a67b9fe…` for the next phone pass by removing Activity's extra TextureView Matrix;
service code is unchanged. Deployment/corrected-image validation belongs to the
library session and is pending its result.

Original-build focus switching and Pause all now have runtime evidence:
`build/multi-focus-sixteen.log` shows focus moving0→15, volume moving100→0 on slot 0
and0→100 on slot 15, with audio starting on slot 15. `build/multi-paused.log` shows
all 16 active but playing0, unchanged positions/displayed/audio across repeated
samples, and all volumes0. Captured cumulative lost counters remain0 at roughly
four minutes of stream playback. Geometry and later lifecycle checks remain open.

## First sixteen-stream runtime sample — geometry fix pending

Library session installed1.4.0 hash`a67b9fe…` and selected16 separate Attack on
Titan episodes, reported as1080p HEVC Main10 at23.976fps. Platform independently
parsed `build/multi-sixteen-steady.log`: three full steady snapshots from
18:21:16.959 through18:21:37.326 show all 16 streams advancing487–493 displayed
frames each, with lost 0 in every slot. Only focused slot 0 has increasing audio
counters and volume 100; the other15 remain volume 0 with flat audio0or2.
`build/multi-sixteen-summary.json` contains the per-slot deltas. PID18619 stays
constant. These are sampled throughput and routing results, not a thermal soak.

Screenshot `build/multi-sixteen-steady.png` exposes a separate UI geometry defect:
TextureView's Matrix applies a second fit to VLC's already letterboxed output,
squashing the pictures. Library owns the Activity fix and phone validation;
platform holds source/build pending explicit readiness. Corrected geometry,
focus switching, PiP/background return and Stop/restore remain to validate.

## Multiplay candidate — 1.4.0/code8, device validation pending

`build/jplay-release.apk` SHA-256
`a67b9fe148dd565b1de6cbb12d77d0e9e9fdf112ccd17620be675f68f5bbd2a1`.
Full release compilation, D8, signatures v2/v3, alignment and non-debuggable checks
pass (`build/multiplay-release.log`). Library session owns deployment and all phone
validation; this build result alone does not establish 16-stream performance.

Service owns sixteen independent LibVLC players, staggered startup (600ms), focused
audio with other audio tracks disabled, per-slot pause/seek/retry/clear, saved
assignments and independent history sessions including duplicate-URI slots.
Background/expanded/PiP retires nonfocused native players and preserves their
positions/desire/history for grid return. MediaSession notification actions share
the focused player; audio focus, noisy handling, CPU/Wi-Fi locks and explicit Stop
are integrated. Stop preserves the saved wall; clear modifies it. Merely opening
an empty wall does not overwrite saved assignments or auto-play. Cross-service
and prior-instance retirement gates serialize native decoder resource reuse.
No tests or test infrastructure were added. All 1.3.0 metadata/folder fixes retained.

## Previous release — 1.3.0/code 7

APK SHA-256 `e8a688db23b34d30628462f5fde22f73d0fadf8d2b018e1b48c261f8eb600d69`.
Full release compilation, D8, v2/v3 signing, alignment and non-debuggable checks
pass (`build/metadata-release.log`). Installed in place on Pixel 9 Pro; profile,
resume positions, history and the per-video software setting are retained.

TVmaze matching and portable NAS metadata are implemented. Opening The Orville
selected the unique exact match, TVmaze ID 20263. The Android app wrote and read
back `/NAS/Library/Shows/The Orville/.justplay/show.json` (40,310 bytes) with
36 episodes, source URL and CC BY-SA 4.0 license. One poster and all 12 season-one
episode thumbnails are present beside it: 14 files total, no temporary parts.
An independent read from the workstation NAS mount parsed the catalogue and
verified all 13 JPEGs; names, lengths and SHA-256 checksums are recorded in
`build/metadata-nas-verification.json`. Screenshots `build/metadata-show.png`
and `build/metadata-season.png` show the real poster, plot, genres, episode names
and episode artwork. The `.justplay` directory is excluded from the app browser.

Folder persistence passes a genuine process restart. Before force-stop and after
a cold launch, MainActivity displays `/NAS/Library/Shows/The Orville/Season 1`,
not Shows or Movies (`build/folder-before.xml`, `build/folder-cold-reopen.txt`).
Sparse runtime records show the first load `source=api`, then season navigation
and cold launch `source=cache`, all `nasSaved=true artwork=true` for 36 episodes
(`build/metadata-cache-events.txt`). No online show lookup is needed on those cache
loads. Network failure injection and alternate-device restoration were not run.

The user subsequently browsed Fallout/Season 1 independently. Its displayed
metadata matched TVmaze ID 49041, and the NAS contains its catalogue (17 episodes),
poster and 8 season-one thumbnails: 10 files, no parts. No agent input navigated
there. Read-only UI evidence confirmed the current Fallout season path and titles.
The manual match dialog is implemented but its full selection flow was not run:
the user changed folders during the attempted check, so no candidate was selected.
The user then played Naruto Shippuden and A Certain Scientific Railgun S without
agent inputs. Final read-only checks show normal visible Railgun video on the
installed 1.3.0 APK (`build/metadata-final-playback.png`). Automatic-decoder Naruto
positions advanced 997,507 → 1,007,451 ms, displayed 192 → 431, decoded audio
856 → 1,888, lost frames 0. Background audio then continued on native generation 1
with surface detached. User selection of Railgun opened generation 3 and rendered
normally. These samples do not establish a fix for intermittent hardware artifacts.
Final exit records show the controlled force-stop, package updates and a subsequent
REMOVE TASK exit; no Java/native crash or ANR (`build/metadata-final-exits.txt`).
This metadata build changes no player code. The previously verified software
fallback and PiP/background fixes remain in place. Playback was left running.
No tests or test infrastructure were added.

## Previous release — 1.2.2/code 5

APK SHA-256 `2cb10d83cadf36f32a67928d9d5886de0e0745abbcf9fb8e8920973f726de1e8`.
Full release checks pass (`build/pip-close-release.log`) and APK installed in
place. Includes the 1.2.1 surface, thumbnail, decoder and diagnostic fixes below.

Actual PiP X-button validation on the 1.2.1 surface-fix build found that Android
hid/stopped the Activity without delivering the custom Close PendingIntent:
audio continued (`build/pipfix-close-later.log`). Version 1.2.2 adds a fallback
for PiP exit while the Activity remains stopped, after allowing 300 ms for
fullscreen resume. It requires an interactive, unlocked device and the same
current media; screen-off/keyguard and fullscreen return are excluded. The
explicit Android close action is retained.

The 1.2.2 PiP X-button runtime check passes. At 03:06:11.806 PiP exited
while the Activity was stopped; at 03:06:12.111 the guarded fallback stopped
playback, and at 03:06:12.204 the service was destroyed. Service/session dumps
contain no remaining Just Play playback service or MediaSession. Evidence:
`build/closefix.log`, `build/closefix-services.txt`, `build/closefix-session.txt`.
No tests/test infrastructure were added. Final playback resume/return check
is in progress.

## Playback follow-up — 2026-09-12, version 1.2.1

The platform session resumed active work after Aku corrected an unrequested pause.
The library session was interrupted; platform explicitly took sole phone/build
ownership in COORDINATION.md and notified the peer before phone inputs.

- Captured a visibly green cached thumbnail for Orville S01E04 in
  `build/continue-ui.png`. Source is HEVC Main 10, 1920×1080, BT.709 SDR.
  Live automatic-decoder captures at this pass were normally coloured, including
  a backward seek (`build/auto-after-seek.png`). This does not disprove the
  intermittent report or establish its trigger.
- Switched the episode to software decoding using the existing control. Actual
  normal-colour frames appear in `build/software-playing.png`; sampled position
  advanced 1,765,417 → 1,775,343 ms, displayed frames 13 → 251, decoded audio
  165 → 785, with lost frames staying zero. Android codec-resource records show
  the app hardware codec released at the switch. Physical audio quality and
  long-term battery/thermal behaviour were not measured.
- Restored the original global Automatic default before update. Explicit Stop
  removed PlaybackService and its active MediaSession; `Wake Locks: size=0`.
  Wi-Fi lock dump showed no active app stream lock. Evidence: `build/stop-*.txt`.
- Release 1.2.1/code 4 builds, signs (APK v2/v3), aligns, and installs in place.
  SHA-256: `29a56d0bc6745cc69ffe4f6845a9b0a94b6950567757405c6483199d1b2b7c13`.
  Build log: `build/render-fix-release.log`. NAS profile and history retained.
- Changes: decoder choice is now per video, with the old global preference as
  fallback; thumbnail capture waits for displayed frames and player capture
  rejects stale native-player callbacks; preview uses the same decoder choice.
  Sparse lifecycle, audio-focus, surface and requested-decoder diagnostics were
  added. These changes are a tested software-decoding workaround and capture
  hardening, not proof that the underlying intermittent hardware corruption is fixed.

Follow-up PiP validation reproduced black software video and a cropped strip on
fullscreen return in the first 1.2.1 build. Removed the app-forced native buffer
size, made PiP surfaces fill their window, and deferred layout updates outside
the parent layout callback, following the bundled LibVLC VideoHelper behaviour.
The rebuilt release passes compile/signature/alignment and is installed:
`c12dad8a63dbcdaa5e92326684f99a04afc20cb51c8206904d672610708e36eb`
(`build/pip-surface-release.log`). Software video now appears in PiP
(`build/pipfix-pip.png`); positions 214,826 → 235,013 ms, displayed 226 → 710,
audio decoded 717 → 1,979, lost frames remain zero. The earlier decoder-switch
run accumulated 16 dropped frames at startup; later steady samples held at 16.
The repaired run above starts with zero.

Per-video software preference survived this reinstall, and the episode resumed
at its saved 205,442 ms. Screen-off playback passes on the final surface-fix artifact: Android reports
Dozing with JustPlay and AudioMix partial locks held, position 280,433 → 288,613
ms across the two measurements. Video surface detaches, video track becomes -1,
and audio decoding continues on native generation 1. Evidence:
`build/pipfix-screenoff-*` and `build/pipfix-screenoff.log`.
The owner subsequently unlocked the phone. Returning through the now-playing
bar restores normal full video (`build/pipfix-unlocked-return.png`) on the same
native generation 1, without a create-player event or reset. Further sampled
position advances 386,081 → 436,673 ms with lost frames staying zero.

Android exit history contains package-update exits plus the earlier deliberate
force-stop; no Java/native crash or ANR entry appears in the retained records
(`build/final-exit-info.txt`). The final audio-output check finds a live 48 kHz
stereo AudioTrack, but the phone media stream is set to volume 0/muted by the
phone setting. It was preserved. Audio decoding/continuity is verified; audible
speaker quality is not claimed (`build/final-audio.txt`, `build/final-audio-flinger.txt`).

The first 1.2.1 pass also verified targeted PiP pause held exactly 66,137 ms
across two samples, then resumed at 68,139 ms; history remained at 14 plays across
decoder restart and PiP return. The earlier green thumbnail was replaced with a
normal-colour captured frame (`build/fix-library-return.png`).

Older historical evidence below is
labelled by its own version and does not imply those APKs remain installed.

## NAS upload runtime verification — 2026-09-12 02:04

The earlier pending NAS upload check is now complete on the Pixel using the
installed 1.1.0 branding build. The labelled sample action uploaded
`JPlay/Crashes/sample-36cc2225-f4b7-4705-8857-bbae3fd0dea4.json` (575 bytes).
Independent NAS readback confirms valid JSON, `kind=sample_report`, `sample=true`,
app version 1.1.0 and Android API36. The phone reports zero pending reports after
its own verified upload; no `.part` files remain. Evidence:
`build/nas-upload-verification.txt` and `build/crash-report-sample.png`.
This validates Android SMB upload and local-queue removal after readback. It does
not simulate a real Java/native crash or a long offline retry window.

## Just Play branding — 2026-09-12

- Original folded play-ribbon/spark artwork reviewed at large and launcher sizes
  in `build/just-play-brand-preview.png`; SVG/PNG masters in `design/brand/`.
- Android resource packaging resolves display name **Just Play** and the API-33
  adaptive icon, with API-26 and legacy variants also supplied.
- Full release compile, v2/v3 signing, alignment and non-debuggable checks pass:
  `build/branding-release-build.log`.
- Installed in place, preserving data and signing identity, after a read-only
  check showed no active Just Play media session. No UI taps were used.
- Installed/current artifact SHA-256:
  `05aaca117f87c68e8923dfc3e83c6a7da797e1423681040a746e26357402253d`.
- Includes the previously pending episode-title refinement. Prior feature runtime
  validation gaps described below remain open; branding checks do not close them.

## Version 1.1 integration — 2026-09-12

The expanded library/player/crash-report implementation compiles and packages as
version 1.1.0 (code 2). Phone validation is currently owned by the library session;
new device results will be recorded separately from the historical MVP evidence
below. The path `build/jplay-release.apk` is replaced by each coordinated build.

Platform checks completed before the library's final toolbar rebuild:

- Full release javac/D8 compilation, APK v2/v3 signing, zip alignment and
  non-debuggable manifest checks passed (`build/release-1.1-build.log`).
- SDK 34's D8 8.2.2-dev failed on Bouncy Castle 1.79's `ASN1Sequence$2` with an
  internal null-pointer exception. The build now uses checksum-pinned official
  Google R8/D8 8.6.24, which completes compilation with the same library versions.
- Player startup honors `startMs=0` for Start over; `-1` loads saved history.
  Opening a stream does not record a zero position before playback starts.
- Native stop runs outside the UI thread. Surface reuse waits for prior player
  cleanup, and events/layout callbacks from old players are ignored.
- Crash collection writes a bounded local queue. Uploads have socket/request
  timeouts, flush and rename a temporary NAS file, then verify its bytes before
  deleting the phone copy. Runtime NAS upload validation remains pending.
- No new tests or test infrastructure were created. These are build checks and
  source review; they do not establish every option's behavior on the phone.

### Library-session device evidence

- The integrated 1.1.0 release was installed in place over Wi-Fi ADB. Existing
  encrypted NAS access and saved progress survived. The build used for these
  device observations has SHA-256
  `bc211ad40a23e0c8214b710c2b567708b76445fa737a47013899014b0b5ce0e8`.
- The Movies folder lists 14 entries with cleaned titles. Landscape initially
  wasted vertical space; the toolbar was compacted into a single row and checked
  on the phone. `build/design-portrait.png` actually captures the resulting
  landscape layout after the user rotated the phone during capture.
- Continue watching displays The Orville, its 43-minute duration, 1080p resolution,
  saved position, and a real video thumbnail. Screenshot:
  `build/design-portrait.png`; the preceding portrait accessibility layout is
  `build/design-portrait.xml`. The two captures are not synchronized snapshots.
- Actual 1920×1080 playback rendered in `build/design-active.png`. The log at
  `build/design-playback.log` advances from 26764 to 66888 ms, displayed pictures
  from 201 to 1162, and decoded audio from 657 to 3165; lost pictures remain zero.
- The phone was also being used interactively during validation. Guarded UI
  automation stopped when its expected JPlay screen was unavailable. A request
  for a brief uninterrupted validation window is pending. No labelled sample
  report has yet been submitted successfully, so NAS upload remains unverified
  on Android. Do not treat source review or the upload button as runtime proof.
- Standalone silent thumbnail generation, every individual playback option, and
  Android crash/native-exit collection still need targeted device checks. No
  deliberate crash was induced. The displayed thumbnail may have come from
  normal playback capture.
- Original automatic rotation (`wm user-rotation free`) was restored.
- A final filename refinement retains season/episode identifiers and uses clean
  embedded titles when available. Its release build passes compilation,
  signature/alignment and non-debuggable checks. Latest artifact SHA-256:
  `a8302d3a438df32cf4c76a18fa433f6dd96ab8b6eca1b7435dd852a765fe02e7`. This refinement has not yet been installed; the earlier complete
  feature build remains on the phone. Latest build log:
  `build/design-release-build.log`.

## MVP verification — 2026-09-12

The MVP was built, installed on the Pixel 9 Pro over wireless ADB, and left playing
**The Batman** directly from the NAS over Wi-Fi. The installed build is the
non-debuggable `in.akuj.jplay` 1.0.0 ARM64 release. The C architecture discussion is
deferred per Aku's latest “get an MVP going” direction.

### Artifact

- APK: `build/jplay-release.apk` (approximately 24 MiB).
- SHA-256: `2d9861c8c28f22c0d4e29e94216498f937b7521a32efac134a35728ff60ef91c`.
- Build: direct Android SDK 34 aapt2/javac/D8/zipalign/apksigner; Java 25.
- Signature verification passed APK v2 and v3; zip alignment passed.
- On-device `run-as in.akuj.jplay id` was refused with “package not debuggable”.
- The dedicated `build/jplay.keystore` was preserved for the in-place update.
- Release update preserved the encrypted NAS login and playback positions.

### Media and network

NAS SMB browse listed 14 entries in the Movies folder. Filtering and selecting
files worked. Authentication used the user's existing login, imported privately
into the debug app and encrypted using Android Keystore. The plaintext import was
consumed and deleted by the app. No credentials were put in source, APK assets,
command arguments or diagnostic output.

`ffprobe` reports the chosen file as H.264 Main, 3840×2160, 8-bit YUV420, with
48 kHz six-channel E-AC-3 audio. Duration is 10,550.566 seconds; average container
bitrate is 12,455,693 bit/s. Source inspection is in `build/batman-stream.json`.
The earlier Rogue One screenshot also shows real rendered imagery from a HEVC
Main 10 file, but the detailed control checks below used The Batman.

### Observed checks

| Check | Evidence and result |
| --- | --- |
| Video rendering | Visually inspected actual movie frames in portrait and landscape, including `build/mvp-playing-1.png`, `build/mvp-playing-2.png` and `build/release-playback.png`. |
| Advancing playback | Initial run advanced from 30,100 to 60,965 ms; displayed pictures rose from 722 to 1,388 and audio decoded from 2,007 to 3,937. Independently reviewed by the sibling session. |
| Pause | MediaSession remained PAUSED at exactly 85,739 ms in two captures roughly 16 seconds apart. |
| Seek and resume | Pressing +10s while paused and then Play produced PLAYING at exactly 95,739 ms; a subsequent screenshot shows the new scene. |
| Rotation | Playback continued after portrait-to-landscape rotation. A lost-picture count of 170 appeared around that transition; subsequent steady playback samples retained the same count. This is not flawless playback. |
| Audio output | Android reports the app's AudioTrack started, stereo 48 kHz output, `mutedState:none`, speaker route, and a nonzero unmuted music-stream volume. Native decoded-audio counters advance. Physical listening quality was not independently assessed. |
| Saved position | Before the release update, the movie was paused at 151,973 ms. After update, selecting the same movie resumed near that saved position, rather than restarting the film. |
| Final release | At 01:15:09.802, position=157,228 ms, displayed=191, audioDecoded=639, lost=0. At 01:15:19.923, position=167,529 ms, displayed=437, audioDecoded=1,283, lost=0. MediaSession reports PLAYING at speed 1.0; a fresh screenshot contains rendered imagery and the app AudioTrack is started/unmuted. |

The pause/seek captures are `build/media-paused.txt`,
`build/media-paused-later.txt`, and `build/media-resumed.txt`. Release evidence is
in `build/release-playback-log.txt`, `build/release-media-session.txt`,
`build/release-audio.txt`, and `build/release-playback.png`. The independent
review is `build/mvp-independent-review.md`.

### Fix and remaining scope

Removed a misleading bitrate diagnostic: an upstream kilobits-per-second
conversion was incorrectly labelled megabits per second. The final build reports
read bytes and decoder counters without that figure. Playback data itself was not
affected. README now reflects the active MVP direction.

No crash appeared in the captured app logs. These are bounded real-device checks,
not a claim that every codec, file, Wi-Fi condition, subtitle type, HDR path, or
hours-long session works perfectly. Wi-Fi loss/reconnect, PiP, every track option,
and software decoding were not exhaustively exercised. No test suite or test
infrastructure was added to this previously empty workspace.
