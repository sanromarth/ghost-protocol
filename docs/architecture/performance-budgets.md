# GHOST Protocol Performance Budgets

> **Version:** v0.3.5 — Measured values from hardware testing across Android API 34 devices.  
> **Reference Device:** Android 8.0+ baseline (2GB RAM, quad-core Cortex-A53 @ 1.4GHz, BLE 5.0).

---

## 1. Measured Performance & Codebase Scale

| Metric | Measured Value | Engineering Context |
|---|---|---|
| **APK Size (Debug)** | ~46 MB | Includes unstripped native shared objects for `arm64-v8a` and `x86_64`. Release with R8 is ~18 MB. |
| **Cold Start Time** | ~1.5s | Time to first interactive Compose frame (`ActivityTaskManager: Displayed` logcat). |
| **Direct 1:1 Send Latency** | 2–4s | Dominated by Android BLE GATT connection overhead (connect $\rightarrow$ request MTU $\rightarrow$ discover services $\rightarrow$ write $\rightarrow$ disconnect). |
| **Cell Group Fanout (3 peers)**| ~4–6s | Sequential pairwise GATT writes to in-range peers or Go router queueing. |
| **Batched Burst Latency** | ~4s (5 msgs) | Single GATT connection setup with chained sequential writes, saving ~70% radio on-time. |
| **Go Router Tests** | 15/15 passing (<0.05s) | BoltDB store, spray routing, batch serialization, and relay willingness gating. |
| **Kotlin Unit Tests** | Passing (11s) | Room DAOs, GroupProtocol codec, BIP-39 HMAC derivation, signature verification. |
| **Total Codebase** | ~10,200 LOC | Kotlin (~7.6k LOC), Go (~1.7k LOC), Rust (~0.3k LOC), Tests (~0.6k LOC). |

---

## 2. Component Resource Usage

| Component | Language | RAM (Resident) | Storage (Disk) | CPU (Idle) | CPU (Active Burst) |
|---|---|---|---|---|---|
| **Android App + Compose UI** | Kotlin | ~45 MB | ~2 MB (Room DB v7 + cache) | 1–2% | 12% (render/layout) |
| **BLE Radio Subsystem** | Kotlin | ~6 MB | N/A | 1–3% (mode-dependent)| 15% (GATT transactions)|
| **Rust Crypto Engine** | Rust | ~2 MB | ~300 KB per ABI (.so) | 0% | 5% (X25519/Ed25519) |
| **Go Mesh Router (BoltDB)** | Go | ~5 MB | Up to 50 MB (pruned auto) | 1% (janitor loop) | 8% (Spray-and-Wait) |
| **Total App Footprint** | Multi | **~58 MB** | **~53 MB max** | **~2–4%** | **~25–35%** |

---

## 3. Battery Drain Curves by Power Mode & Security Posture

Continuous BLE scanning is catastrophic for battery life if uncontrolled. GHOST balances radio power through coordinated engine duty cycles:

### Power Policy Engine (30-second loop)

| Power Mode | Scan Interval / Window | Adv Interval | WakeLock Held? | Est. Drain | Operational Context |
|---|---|---|---|---|---|
| **`ACTIVE`** | 500ms / 100ms | 100ms | Yes (partial) | ~3–4% / hr | Connected to charger, or dense mesh (>10 peers with pending queue) |
| **`ECO`** | 2000ms / 100ms | 500ms | Yes (partial) | ~1.5–2% / hr | Default operating mode for general walking around |
| **`CRITICAL`** | 60,000ms / 200ms | 1000ms | **Released** | ~0.5% / hr | Battery < 20%. Relay willingness drops to 0.0 (transit blobs dropped). |
| **`DEEP_SLEEP`** | 300,000ms / 500ms | 2000ms | **Released** | **~0.2% / hr** | Screen off, stationary, no peers seen for >30 minutes |

### Security Posture Overrides

| Posture | Scan Mode | Adv Interval | Relay Policy | Intended Use |
|---|---|---|---|---|
| **`NORMAL`** | Governed by `PowerPolicyEngine` | Governed by `PowerPolicyEngine` | Battery-dependent | Standard daily communication |
| **`PROTEST`** | 1000ms interval / 200ms window | 200ms | Normal relaying | Fast-moving crowd with 1-tap discovery active |
| **`EMERGENCY`** | Continuous (`SCAN_MODE_LOW_LATENCY`) | 100ms | Immediate relaying | Blackout or disaster zone (maximum mesh speed) |
| **`STEALTH`** | Passive only (`SCAN_MODE_OPPORTUNISTIC`) | **Transmitter killed (0ms)** | Receive-only (no relaying)| Evading radio direction-finders |

---

## 4. Packet Size Budgets (MTU 512)

Android BLE ATT MTU can be negotiated up to 512 bytes. Payloads under 509 bytes fit inside a single GATT write without requiring L2CAP chunking or write-chaining:

```
0x01 Routed Message:     ~400 bytes (JSON header ~180B + Ciphertext ~180B + overhead)  --> 1 write
0x10 Discovery Request:  ~145 bytes (32B edPub + 32B xPub + 8B ts + name + 64B sig)    --> 1 write
0x11 Discovery Response: ~145 bytes (32B edPub + 32B xPub + 8B ts + name + 64B sig)    --> 1 write
0x20 Short Code Query:   ~129 bytes (32B hash + 32B edPub + 64B sig)                   --> 1 write
0x21 Short Code Response:~129 bytes (32B edPub + 32B xPub + 64B sig)                   --> 1 write
0x30 Cell Group Envelope:~281 bytes (32B groupId + 16B sender + 8B ts + 160B cipher + 64B sig) --> 1 write
```

Every protocol packet in GHOST v0.3.5 is explicitly budgeted to remain well under the 509-byte single-write boundary, eliminating MTU fragmentation failures across varied Android vendor hardware.

---

## 5. Storage Retention & Flash Wear

To prevent flash memory wear and unbounded growth on budget devices:
- **Room `messages` & `group_messages`:** Auto-pruned at 48 hours rolling window (`timestamp < now - 48h`).
- **Room `telemetry_snapshots`:** Auto-pruned at 48 hours rolling window.
- **Go BoltDB message store:** Maximum file size capped at 50 MB. Spray records older than TTL (24h default) are evicted by the janitor routine every 10 minutes.
