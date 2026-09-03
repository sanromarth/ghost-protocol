# GHOST Protocol Performance Budgets

> **Version:** v0.2.0 — measured values from actual hardware testing and power policy engine.
> Target device: 1GB RAM, 8GB storage, Android 8.0+, quad-core ARM Cortex-A53 @ 1.4GHz

## 1. Measured Performance (v0.2.0)

| Metric | Value | Notes |
|---|---|---|
| APK size (debug) | 46 MB | Includes Rust .so (arm64 + x86_64) + Go .aar (arm64 + x86_64). Release with R8 would be ~15-20MB. |
| Cold start time | ~1.5s | Measured on-device via `ActivityTaskManager: Displayed` logcat |
| BLE message latency | 2–4 seconds | Dominated by BLE connection setup (connect → MTU → discover → write → disconnect) |
| Batched BLE latency | ~4–5 seconds (5 msgs) | Single connection setup + sequential GATT writes (vs ~15–20s for 5 individual sessions) |
| BLE advertising packet | 21 bytes (main) + 11 bytes (scan response) | Under the 31-byte BLE legacy limit |
| Go router AAR | ~5.0 MB | arm64 + x86_64 native libs (includes batch serializer + relay gate) |
| Rust crypto .so | ~300 KB per ABI | Ed25519 + X25519 + AES-256-GCM |
| Go unit tests | 15/15 pass in <0.05s | Store, serializer, router, spray, batch roundtrip, relay willingness gate |
| BLE range | ~10m indoors | Standard BLE 5.0, no external antenna |
| Lines of code | ~6,500 | Kotlin (~5,200) + Go (~950) + Rust (~300) |

## 2. Component Resource Usage

| Component | RAM (est.) | Storage | CPU Idle | CPU Active |
|---|---|---|---|---|
| Kotlin App + BLE + Power | ~45 MB | ~2 MB (Room DB v6 + telemetry) | 1–3% (mode-dependent) | 15% (GATT writes) |
| Rust crypto (.so) | ~2 MB | ~300 KB per ABI | 0% | 5% (encrypt/sign) |
| Go router + BoltDB | ~5 MB | Up to 50 MB (message store) | 1% (janitor) | 10% (routing) |
| **Total** | **~52 MB** | **~53 MB max** | **~1–4%** | **~30%** |

## 3. Battery Budget by Power Mode (v0.2.0)

v0.2 replaces static continuous scanning with the **PowerPolicyEngine**:

| Mode | Scan Interval / Window | Adv Interval | WakeLock | Estimated Drain | Intended Scenario |
|---|---|---|---|---|---|
| **ACTIVE** | 500ms / 100ms | 100ms | Held (partial) | ~3–4%/hr | Charging or crowded mesh (>10 peers with pending queue) |
| **ECO** | 2000ms / 100ms | 500ms | Held (partial) | ~1.5–2%/hr | Default walking around mode |
| **CRITICAL** | 60,000ms / 200ms | 1000ms | **Released** | ~0.5%/hr | Battery < 20%, unplugged. Relay disabled (0.0). |
| **DEEP_SLEEP** | 300,000ms / 500ms | 2000ms | **Released** | **~0.2%/hr** | Screen off, stationary, no peers for >30m, battery > 20% |

### Message Batching Efficiency

- **Without batching (v0.1.5):** 5 messages = 5 × (connect → MTU negotiation → service discovery → write → disconnect) ≈ **15–20 seconds** of active radio on-time.
- **With batching (v0.2.0):** 5 messages = 1 connection setup + 5 sequential writes ≈ **4–5 seconds** of active radio on-time.
- **Gain:** ~70% reduction in radio on-time and GATT connection overhead during burst transmissions.

## 4. Storage Budget

| Component | Size | Notes |
|---|---|---|
| APK (debug) | 46 MB | Mostly native libs for 2 ABIs |
| Room database | <1 MB typical | Contacts + messages |
| BoltDB (Go router) | Up to 50 MB | Auto-prune when exceeded |
| SharedPreferences | <1 KB | Identity blob + username |
| **Total on-device** | **~97 MB max** | |

## 5. BLE Throughput

| Metric | Value |
|---|---|
| BLE Coded PHY theoretical | ~125 kbps |
| Practical throughput (MTU 512) | ~10-20 kbps |
| Max message size (single write) | 512 bytes (MTU) |
| Typical encrypted message | ~400 bytes |
| Routing header overhead | ~200 bytes JSON |

## 6. Startup Performance

| Phase | Duration |
|---|---|
| Rust crypto init (library load) | <50ms |
| Go router init (BoltDB open) | ~200ms |
| Room database init | ~80ms | With new database indexes |
| BLE service start | ~500ms |
| **Total cold start** | **~1.5s** |

## 7. Spray-and-Wait Overhead

| Scenario | Copies | BLE Transmissions | Storage per msg |
|---|---|---|---|
| Direct delivery (peer in range) | 1 | 1 × ~600 bytes | 0 (not stored) |
| Spray (peer out of range) | 4 | Up to 4 × ~600 bytes | ~600 bytes × 4 in BoltDB |
| 100 users, 10 msg/hr each | 4,000/hr | ~2.4 MB/hr BLE traffic | ~2.4 MB in BoltDB |

> **Note:** In dense networks (100+ nodes), BLE spectrum contention becomes the bottleneck, not CPU or storage. Not tested at scale.
