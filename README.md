# VoxLnk — P2P Local Intercom (Android)

A LAN-based push-to-talk / intercom Android app. One device runs a **server** (host),
others connect as **clients** over a WebSocket on the local network. Audio is captured,
DSP-processed (HPF, mic/playback boost, soft limiter, VOX gating), streamed as raw
16-bit/16 kHz mono PCM, and played back through a jitter-buffered audio engine.

> This README is written for AI coding agents working in this repo. It documents how to
> build/validate without polluting the host, the architecture, and the non-obvious gotchas.

## TL;DR for agents

- **No JDK/Android SDK on the host.** Build inside a Podman container (see below).
- Fast inner loop to validate Kotlin changes: `:app:compileDebugKotlin`.
- Main audio logic lives in `app/src/main/java/com/example/audio/AudioEngine.kt`.
- Networking is plain WebSocket (TCP) via `org.java_websocket` — see `network/`.
- Do **not** reintroduce blocking `AudioTrack.write()` on the network read thread (see Gotchas).

## Toolchain / versions

| Component        | Version            | Notes |
|------------------|--------------------|-------|
| Android Gradle Plugin | `9.1.1`       | requires JDK 17+ to run |
| Gradle wrapper   | `9.4.0`            | downloaded by the wrapper |
| Kotlin           | `2.2.10`           | |
| compileSdk / targetSdk | `36.1`       | needs `platforms;android-36.1` |
| build-tools      | `36.1.0`           | |
| minSdk           | `24`               | |
| Java source/target compat | `11`      | (toolchain still needs JDK 17 to run AGP) |

Versions are defined in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

## Build & validate (containerized with Podman)

The host has neither Java nor the Android SDK. Everything below runs in a throwaway
container image; the only persistent state is a named Gradle cache volume (`voxgradle`)
and the normal `app/build/` output inside the repo.

### 1. One-time: build the image

Create `Containerfile` (kept outside the repo, e.g. `/tmp/voxbuild/Containerfile`):

```dockerfile
FROM docker.io/library/eclipse-temurin:17-jdk

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -sL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip && \
    unzip -q /tmp/cmdline.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdline.zip

ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN yes | sdkmanager --licenses >/dev/null 2>&1 || true
RUN sdkmanager --install "platform-tools" "platforms;android-36.1" "build-tools;36.1.0" >/dev/null && \
    yes | sdkmanager --licenses >/dev/null 2>&1 || true

WORKDIR /work
```

Build it:

```bash
podman build -t voxbuild /tmp/voxbuild
```

### 2. Compile Kotlin (fast feedback loop)

```bash
podman run --rm \
  -v "$(pwd)":/work:z \
  -v voxgradle:/gradle-home \
  -e GRADLE_USER_HOME=/gradle-home \
  -w /work \
  voxbuild ./gradlew :app:compileDebugKotlin --console=plain --no-daemon
```

- `:z` relabels the mount for SELinux; drop it if your host doesn't use SELinux.
- `GRADLE_USER_HOME=/gradle-home` keeps Gradle's downloads in the named volume, **not**
  in the repo or the host home dir.
- First run downloads Gradle deps (slow); subsequent runs reuse the `voxgradle` volume.

### 3. Build a debug APK (full validation)

```bash
podman run --rm \
  -v "$(pwd)":/work:z \
  -v voxgradle:/gradle-home \
  -e GRADLE_USER_HOME=/gradle-home \
  -w /work \
  voxbuild ./gradlew :app:assembleDebug --console=plain --no-daemon
```

> **Gotcha:** `assembleDebug` signs with the `debugConfig` keystore at
> `./debug.keystore` (password `android`, alias `androiddebugkey`). If that file is
> missing, packaging fails. `compileDebugKotlin` does **not** need it. A previously
> committed `debug.keystore.base64` was removed from the repo, so regenerate/restore the
> keystore if you need to produce an APK.

### 4. Run unit tests

```bash
podman run --rm -v "$(pwd)":/work:z -v voxgradle:/gradle-home \
  -e GRADLE_USER_HOME=/gradle-home -w /work \
  voxbuild ./gradlew :app:testDebugUnitTest --console=plain --no-daemon
```

## Architecture

```
MainActivity ─ Compose UI (ui/VoxApp.kt) ─ VoxViewModel (ui/VoxViewModel.kt)
                                                │
                        ┌───────────────────────┼───────────────────────┐
                        ▼                        ▼                       ▼
                AudioEngine (audio/)      VoxServer (network/)    VoxClient (network/)
              capture + DSP + playback     WebSocket host          WebSocket client
                        ▲                        │                       │
                        └────────── raw PCM frames over LAN ─────────────┘
                VoxService (audio/) = foreground service to keep audio alive in background
```

Key files:

- `app/src/main/java/com/example/audio/AudioEngine.kt` — `AudioRecord`/`AudioTrack`,
  all DSP (HPF, boosts, soft limiter), VOX/voice-activity gating, and the **playback
  jitter buffer**. PCM format: 16 kHz, mono, 16-bit (2 bytes/frame).
- `app/src/main/java/com/example/audio/VoxService.kt` — foreground service + wifi lock so
  audio keeps running with the screen off / app backgrounded.
- `app/src/main/java/com/example/audio/VoxLnkTileService.kt` — quick-settings tile.
- `app/src/main/java/com/example/network/VoxServer.kt` — host: accepts clients, assigns
  IDs/nicknames, broadcasts audio frames. Wire format: `[senderId:int][nickLen:int][nick][pcm…]`.
- `app/src/main/java/com/example/network/VoxClient.kt` — client: connects, sends voice,
  receives/dispatches audio (skips self-loopback).
- `app/src/main/java/com/example/ui/VoxViewModel.kt` — wires UI ↔ audio ↔ network, server
  discovery, and client auto-reconnect.
- `AUDIO_DSP_MATH.md` — derivations for the DSP (HPF coefficient, soft limiter, etc.).

## Important gotchas (read before touching audio/networking)

- **Transport is TCP (WebSocket).** It is reliable and in-order, so it never drops late
  audio on its own. Any backlog must be dropped explicitly on the receive side, or
  latency grows without bound.
- **Playback path must not block the network read thread.** `VoxClient.onMessage` runs on
  the WebSocket read thread and calls `AudioEngine.playAudio()`. `playAudio()` only
  **enqueues** into a bounded jitter buffer (drops the oldest chunks past ~200 ms) and
  returns immediately. A dedicated `AudioPlayback` thread (`playbackLoop`) drains the
  queue and performs the blocking `AudioTrack.write()`.
  - Historical bug: doing the blocking `write()` (and a useless `AudioTrack.flush()`
    watchdog) directly on the read thread caused audio delay to grow continuously the
    longer people talked. Do not reintroduce that pattern.
- **Latency is bounded, not zero.** When the jitter buffer overflows it drops the oldest
  audio, producing a brief discontinuity instead of accumulating delay — intended for a
  live intercom. Tune via `maxBufferedBytes` in `AudioEngine.kt`.
- **`stopPlayback()` must fully tear down** the playback thread (clear queue, interrupt,
  join, release `AudioTrack`). Client reconnect relies on clean start/stop cycles.
- **No Gemini / AI Studio dependency.** Despite the original scaffold, the app does not
  use `GEMINI_API_KEY`. The `secrets` Gradle plugin reads `.env`/`.env.example`, but no
  secret is required to build or run.
- **Signing for release** uses env vars `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`
  (alias `upload`); see `signingConfigs` in `app/build.gradle.kts`.

## Conventions

- Keep changes minimal and consistent with existing style.
- Validate Kotlin changes with `:app:compileDebugKotlin` before deeper builds.
- Pre-existing deprecation warnings (e.g. the `AudioTrack` constructor, some Compose
  icons) are expected; don't churn unrelated code to silence them.
