# GHOST Protocol Performance Budgets

> **Version:** v0.1.5 — measured values from actual hardware testing.
> Target device: 1GB RAM, 8GB storage, Android 8.0+, quad-core ARM Cortex-A53 @ 1.4GHz

## 1. Measured Performance (v0.1.5)

| Metric | Value | Notes |
|---|---|---|
| APK size (debug) | 46 MB | Includes Rust .so (arm64 + x86_64) + Go .aar (arm64 + x86_64). Release with R8 would be ~15-20MB. |
| Cold start time | ~1.5s | Measured on-device via `ActivityTaskManager: Displayed` logcat |
| BLE message latency | 2–4 seconds | Dominated by BLE connection setup (connect → MTU → discover → write → disconnect) |
| BLE advertising packet | 21 bytes (main) + 11 bytes (scan response) | Under the 31-byte BLE legacy limit |
| Go router AAR | 4.9 MB | arm64 + x86_64 native libs |
| Rust crypto .so | ~300 KB per ABI | Ed25519 + X25519 + AES-256-GCM |
| Go unit tests | 13/13 pass in 0.009s | Store, serializer, router, spray, dedup |
| BLE range | ~10m indoors | Standard BLE 5.0, no external antenna |
| Lines of code | ~5,500 | Kotlin (~4,000) + Go (~800) + Rust (~300), excluding stubs |

## 2. Component Resource Usage

| Component | RAM (est.) | Storage | CPU Idle | CPU Active |
|---|---|---|---|---|
| Kotlin App + BLE Service | ~40 MB | ~1 MB (Room DB) | 3% (BLE scan) | 15% (GATT writes) |
| Rust crypto (.so) | ~2 MB | ~300 KB per ABI | 0% | 5% (encrypt/sign) |
| Go router + BoltDB | ~5 MB | Up to 50 MB (message store) | 1% (janitor) | 10% (routing) |
| **Total** | **~47 MB** | **~52 MB max** | **~4%** | **~30%** |

## 3. Battery Budget (Estimated)

| Activity | Battery Impact |
|---|---|
| BLE advertising (always-on) | ~1-2%/hour |
| BLE scanning (LOW_LATENCY) | ~2-3%/hour |
| GATT write (per message) | Negligible (~0.01%/message) |
| Go BoltDB janitor (60s interval) | Negligible |
| WakeLock (PARTIAL) | ~0.5%/hour |
| **Estimated daily idle** | **~30-40%/day** |

> **Note:** Battery impact varies significantly by device, Android version, and Doze mode behavior. No systematic power profiling has been done yet.

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
