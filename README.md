<p align="center">
  <img src="docs/assets/logo.png" width="120" alt="GHOST logo">
</p>

<h1 align="center">GHOST Protocol</h1>

<p align="center">
  <em>Messages that find their way.</em>
</p>

<p align="center">
  Offline encrypted mesh messenger for Android.<br>
  No servers. No accounts. No surrender.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8%2B-green" alt="Android 8+">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License">
  <img src="https://img.shields.io/badge/size-%3C50MB-blueviolet" alt="APK <50MB">
  <img src="https://img.shields.io/badge/version-v0.2.0-orange" alt="Version v0.2.0">
</p>

---

Send encrypted messages without internet, servers, or phone numbers. Just Bluetooth.

GHOST is an offline mesh messenger that does end-to-end encrypted messaging over Bluetooth Low Energy. No infrastructure needed — two phones in range can exchange encrypted messages directly. For phones out of range, messages hop through intermediate devices using delay-tolerant Spray-and-Wait routing.

Built with Kotlin (UI + BLE), Go (mesh routing), and Rust (crypto). Three languages, two FFI bridges, zero servers.

## Why GHOST?

Most "offline" messengers rely on theoretical whitepapers, proprietary ciphers, or secretly depend on internet gateways and relay servers. GHOST is built on four core principles:

- **Honest Threat Model** — No overpromised privacy claims. We don't claim metadata invisibility or magical onion routing over raw Bluetooth advertisements. GHOST guarantees payload confidentiality (X25519 + AES-256-GCM), sender authenticity (Ed25519 signatures), and replay protection. What's not protected (local physical extraction without OS lockscreen, RF proximity eavesdropping) is explicitly documented in our [Threat Model](docs/architecture/threat-model.md).
- **Battery-Aware DTN Routing** — Measured on hardware, not assumed. Constant BLE scanning destroys phone batteries in hours. GHOST's `PowerPolicyEngine` dynamically shifts across four power states (`ACTIVE`, `ECO`, `CRITICAL`, `DEEP_SLEEP`), batches multi-message GATT transfers to cut radio on-time by ~70%, and releases partial wake locks when idle. When a phone drops below 20% battery, it sheds relay burdens to protect dying phones.
- **Standard, Audited Cryptography** — Zero homegrown cryptographic schemes. Built in Rust with standard libraries from the `dalek-cryptography` ecosystem (X25519 key agreement, Ed25519 digital signatures) and standard `aes-gcm`. All cryptographic operations cross a strictly typed, panic-safe JNI boundary.
- **True Zero Infrastructure** — No Nostr relays, no DHT bootstraps, no central directory, no SIM card or phone number required. Two phones running GHOST can exchange keys offline via QR code in a subway tunnel, protest, or disaster zone and communicate immediately.

## What it does

- **E2E encrypted messaging** — X25519 + AES-256-GCM, Ed25519 signatures, all in Rust
- **BLE mesh routing** — Spray-and-Wait store-and-forward in Go, multi-hop relay via nearby devices
- **Message batching** — multi-message bundling into single GATT connections with sequential write-chaining
- **Battery-aware power policy** — 4 dynamic power modes (ACTIVE, ECO, CRITICAL, DEEP_SLEEP), wake lock management, and relay willingness gating
- **Battery telemetry** — SQLite/Room snapshot logging with CSV export for empirical power profiling
- **Reciprocal QR key exchange** — scan a code to add a contact, automatic reciprocal return scan, zero servers
- **Cypherpunk UI/UX** — Ghost Aura (animated Ethereal Ring), live RF proximity waves (`∿∿∿ ~3m`), heartbeat haptic confirmation, delay-tolerant message shimmer physics, and 1-tap true `#000000` OLED Survival HUD
- **Swipe-to-reply UI** — Compose-based chat with gesture support and in-line reply previews
- **Offline-first** — works with zero network, no Google Play Services needed

## Quick start

```bash
# Build native libs (Go AAR + Rust .so)
./scripts/build-native.sh

# Or compile the Go AAR manually:
cd go/ghostrouter
gomobile bind -target android/arm64,android/amd64 -androidapi 26 -o ../../android/app/libs/ghostrouter.aar .

# Build the APK
./gradlew assembleDebug

# Install on device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

**You'll need:** Android SDK (API 34), NDK 27.2, Rust + `cargo-ndk`, Go 1.22+ + `gomobile`, Java 17/21.

## Architecture

```
Kotlin (UI + BLE + Power)  →  Go (Routing)  →  Rust (Crypto)
   Compose UI                   gomobile         JNI
   PowerPolicyEngine            BoltDB           dalek
   Room DB (v4)                 Spray-Wait       AES-GCM
   GATT S/C + Batching          Batch Serializer
```

| Layer | Language | ~LOC | What it does |
|-------|----------|------|-------------|
| App + UI | Kotlin | 5,200 | Compose UI, BLE GATT, PowerPolicyEngine, BatteryTelemetry, Room DB |
| Mesh router | Go | 950 | Spray-and-Wait routing, batch serializer, relay gating, BoltDB store |
| Crypto | Rust | 300 | Ed25519, X25519, AES-256-GCM via JNI |

## How it works

1. First launch creates an Ed25519 + X25519 keypair
2. You add contacts by scanning their QR code (public key exchange)
3. Messages are encrypted with the recipient's X25519 key, signed with your Ed25519 key
4. BLE GATT writes deliver messages to nearby devices — directly or via relay
5. When multiple messages are queued for a peer, they are batched into a single connection
6. Background power policy throttles scan/advertising intervals and drops relay burdens when battery drops below 20%
7. Username changes propagate automatically inside encrypted payloads

## Security note

This is alpha software. It hasn't been professionally audited. Don't use it for anything where your safety depends on message confidentiality.

v0.1.5 resolved 67 internal audit issues (FFI panics, mutex deadlocks, GATT leaks), and v0.2 adds policy-based relay gating and sequential write protection. See the [threat model](docs/architecture/threat-model.md) for what's protected and what's not.

## Performance

| Metric | Value |
|--------|-------|
| APK size (debug) | ~46 MB |
| Cold start | ~1.5s |
| BLE message latency | 2–4s direct |
| BLE range | ~10m indoors |
| Lines of code | ~8,400 (Kotlin: 6.3k, Go: 1.7k, Rust: 0.3k) |
| Go unit tests | 15/15 passing |

## Docs

- [Whitepaper](docs/whitepaper.md) — protocol design and security analysis
- [v0.1 Status](docs/GHOST-v0.1-Status.md) — baseline audit & status report
- [v0.2 Status](docs/GHOST-v0.2-Status.md) — battery power system, GATT batching, & cypherpunk UI status
- [v0.3 Protest Mode Spec](docs/GHOST-v0.3-Roadmap-ProtestMode.md) — frictionless nearby discovery & short codes
- [Changelog](CHANGELOG.md) — version history
- [System Architecture](docs/architecture/system-diagram.md) — component diagram
- [API Reference](docs/api/) — Go, Kotlin, Rust module APIs
- [Performance Budgets](docs/architecture/performance-budgets.md) — battery & resource targets
- [Threat Model](docs/architecture/threat-model.md) — honest security analysis

## Roadmap

| Version | Milestone | Key Deliverables |
|---|---|---|
| **v0.1 ✓** | Core Mesh Alpha | BLE direct messaging, Noise/X25519 E2E crypto, Spray-and-Wait DTN routing, Compose dark UI |
| **v0.2 ✓** | Power & Reliability | PowerPolicyEngine, Battery Telemetry, GATT batching, DTN re-encounter delivery, Reciprocal mutual QR, Ethereal Ring, Survival HUD |
| **v0.2.5** | Trust Web | Contact Introductions (friend-of-a-friend signed vouching envelopes) |
| **v0.3** | Protest Mode & Groups | Nearby BLE discovery (one-tap mutual consent), 24h rotating BIP-39 short codes, delivery receipts, multi-peer group chat |
| **v0.4** | Survival Broadcast | Channel 0 Emergency Public Broadcast (unencrypted localized alert bursts), WiFi Direct high-bandwidth transport |
| **v1.0** | Cypherpunk Production | Post-quantum Kyber/Dilithium hybrid crypto, Sphinx cover traffic, biometric Shamir identity recovery |

## License

MIT
