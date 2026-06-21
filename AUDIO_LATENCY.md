# Audio Latency & TCP: Why Delay Grows Over Time

This document explains the root cause of the "audio delay keeps growing the longer you
talk" bug, why TCP makes it inevitable, and how the jitter buffer fix works. Written for
agents working on the networking/audio path.

## The setup

- Two devices on a LAN. One is server, one is client (or both directions).
- Audio is captured at 16 kHz mono 16-bit PCM (2 bytes/frame, 32 KB/sec).
- Chunks of ~1024 frames = 2048 bytes = **64 ms** of audio sent per packet.
- Transport is WebSocket (which is TCP underneath).
- Receiver plays through `AudioTrack` in `MODE_STREAM` with a ~4096 byte (~128 ms)
  internal buffer.

## Why "audio in = audio out" doesn't prevent backlog

Intuitively: sender produces 64 ms of audio every 64 ms, receiver plays 64 ms of audio
every 64 ms. Perfect match, right? Almost — but not exactly, because of two things.

### 1. Clock drift

Both devices have a "16000 Hz" crystal oscillator. But no two crystals are identical.
Typical tolerance is ±50 ppm (parts per million):

| Device | Nominal | Actual (example) |
|--------|---------|-------------------|
| Sender mic | 16000 Hz | 16000.8 Hz (+50 ppm) |
| Receiver speaker | 16000 Hz | 15999.4 Hz (-38 ppm) |

Over 1 minute:
- Sender produces: 16000.8 × 60 = 960,048 samples
- Receiver plays:  15999.4 × 60 = 959,964 samples
- **Difference: 84 samples = 5.25 ms queued up with nowhere to go**

Over 10 minutes: ~52 ms. Over 30 minutes: ~157 ms. It never stops.

### 2. Momentary stalls on the receiver

Even with perfect clocks, any time the receiver is briefly slower than the sender, a
backlog forms:

- **Android GC pause**: 5–50 ms where no app code runs
- **Thread scheduling**: the OS doesn't guarantee your thread runs immediately
- **Per-sample DSP cost**: the boost/limiter loop is O(n) per chunk (~1–2 ms). When
  processing back-to-back chunks (e.g., multiple speakers), this adds up:
  - 2 chunks: DSP(2ms) + write(64ms) + DSP(2ms) + write(64ms) = 132 ms to handle 128 ms
    of audio → 4 ms behind
- **TCP retransmission**: if a segment is lost, ALL subsequent data waits behind it
  (head-of-line blocking) even if later packets already arrived

## Why TCP makes this fatal

With UDP (what real VoIP uses), late packets simply don't arrive or get dropped. The
receiver self-corrects every few ms.

With TCP:
- **Reliable**: every byte sent WILL arrive. No exceptions.
- **In-order**: byte N+1 is never delivered before byte N.
- **Never drops data**: even if you're 10 seconds behind, you will receive all 10 seconds
  of audio and must deal with it.

So once you fall behind by X ms, you stay behind by ≥ X ms forever — unless you
explicitly throw away stale data yourself (which is what the jitter buffer does).

## TCP flow control (send/receive synchronization)

TCP has a built-in mechanism to prevent overwhelming the receiver:

1. Every ACK the receiver sends includes a **window size** = "I have this many bytes free
   in my receive buffer."
2. The sender is **not allowed** to send more than `window` unacknowledged bytes.
3. If window = 0, the sender **stops** until the receiver frees space.

This means the system is synchronized — the sender won't flood the receiver. But this
does NOT help with latency:

- When the receiver's app is slow to read (e.g., blocked in `track.write()`), the recv
  buffer fills up → window shrinks to 0 → sender blocks.
- But the sender's app (recording loop) keeps producing audio → it queues in the
  WebSocket library's in-memory write queue (unbounded).
- When the receiver unblocks, it reads one chunk, frees a tiny window, sender sends a bit
  more... but the library-side queue is already huge.
- Eventually ALL the queued audio drains through — in order — and the user hears audio
  from the past.

Flow control prevents **data loss**, not **latency accumulation**.

## Why AudioTrack.write() blocks

The speaker hardware (DAC) consumes samples at a fixed, exact rate: one sample every
62.5 µs (1/16000 sec). You cannot speed this up.

`AudioTrack` has a small internal buffer (~4096 bytes = 128 ms). `write()` means "put
this data into the buffer." If the buffer is full, `write()` waits until the hardware
drains enough to fit your data. For a 2048-byte (64 ms) chunk going into a full buffer,
that takes **exactly 64 ms of wall-clock time**.

This blocking is not a bug — it's what paces playback to real-time. The problem is
**which thread** blocks.

## The old broken design

```
WebSocket read thread:
  onMessage(audioBytes)
    → playAudio(audioBytes)
      → per-sample DSP loop (1-2ms)
      → track.write(result)    ← BLOCKS for ~64ms
      → returns
    → onMessage can fire again
```

While blocked in `write()`:
- No new WebSocket messages can be read
- TCP recv buffer fills up (kernel keeps accepting segments from the network)
- Receiver advertises window = 0
- Sender's TCP blocks, WebSocket library queues in memory
- Latency grows monotonically

There was a later attempt to "fix" this by measuring
`totalFramesWritten - playbackHeadPosition` and calling `track.flush()` if it exceeded
350 ms. This was ineffective because:
- `write()` is blocking, so AudioTrack internal buffer is capped at ~128 ms by
  construction — the 350 ms threshold is unreachable.
- Even if flush fired, it only discards the ≤128 ms inside AudioTrack — the real
  backlog in the TCP/WebSocket stack (potentially seconds) would immediately refill it.

## The fix: bounded jitter buffer + dedicated playback thread

```
WebSocket read thread (fast, never blocks):
  onMessage(audioBytes)
    → playAudio(audioBytes)
      → synchronized enqueue into ArrayDeque (O(1), ~microseconds)
      → if queue > 200ms: drop oldest chunks
      → notify playback thread
      → return immediately

Dedicated AudioPlayback thread (blocks on hardware, that's fine):
  playbackLoop():
    → wait for queue to have data
    → dequeue chunk
    → DSP (boost, limiter)
    → track.write(result)    ← blocks here, pacing playback to real-time
    → loop
```

Why this works:
1. The read thread never blocks → it always drains the TCP recv buffer immediately →
   receiver always advertises a large window → sender is never flow-controlled → no
   upstream backlog forms.
2. If audio arrives faster than real-time (drift, burst), the jitter buffer drops the
   oldest chunks once it exceeds ~200 ms.
3. Worst-case latency is bounded: jitter buffer (≤200 ms) + AudioTrack (≤128 ms) ≈
   **≤330 ms**, self-correcting.
4. The tradeoff: when it drops, you hear a brief discontinuity (a tiny skip). This is
   far preferable to unbounded growing delay for a live intercom.

## Tuning

The `maxBufferedBytes` constant in `AudioEngine.kt` controls the jitter buffer cap:

```kotlin
private val maxBufferedBytes = SAMPLE_RATE * 2 * 200 / 1000  // ~200ms
```

- **Lower** (e.g., 100 ms): tighter latency, more frequent drops (more audio skips).
- **Higher** (e.g., 300 ms): fewer drops, but higher baseline latency.
- 200 ms is a reasonable default for a LAN intercom where round-trip is <1 ms.

## Summary for agents

- Never do blocking I/O or heavy computation on the WebSocket read thread.
- `playAudio()` must remain non-blocking (enqueue only).
- The playback thread owns `AudioTrack.write()` and all DSP — it's the only thing allowed
  to block on audio hardware.
- TCP will never save you from latency — you must manage it yourself by dropping stale
  data.
- If you ever see latency growing over time, check whether the read thread is being
  blocked by something downstream.
