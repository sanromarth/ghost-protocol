# GHOST Protocol — v0.4 Implementation & Verification Status

- **Version:** v0.4.4
- **Date:** September 5, 2026
- **Target Platform:** Android API 26 minimum, API 34 compile/target
- **Android Test Suite:** 52/52 passing (`./gradlew testDebugUnitTest`)
- **Go Test Suite & Race Detector:** Pass with 0 data races (`go test -race ./...`)
- **Rust Native Cryptography:** 12/12 passing (`cargo test --lib`)
- **Storage:** Room schema v9 (`GhostDatabase`), BoltDB (`router.db`)
- **Status:** Virtual and unit verification complete; pending physical device validation

---

## 1. Scope and Summary

The v0.4 milestone focuses on reliability, storage resilience, battery management, and fault injection testing for the GHOST Protocol implementation.

Testing and hardening targeted common mobile operating system failure modes:
- Process termination by vendor background managers.
- Bluetooth Low Energy (BLE) controller collisions producing `GATT 133` status errors.
- Concurrent Room database updates and out-of-order delivery receipts causing message status regressions.
- Unbounded transit message accumulation exhausting device storage.
- Low-memory killer (LMKD) process termination during message transit.
- Dynamic Bluetooth permission and adapter state transitions.

Hardening across v0.4.0 through v0.4.4 was tested using a 4-stage simulation and torture harness covering routing, application state, and synthetic OEM profiles. Over 120,000 deterministic test scenarios were executed across these test suites.

---

## 2. Milestone Progression (v0.4.0 – v0.4.4)

```text
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                 v0.4 MILESTONE PIPELINE                                 │
├─────────────────┬─────────────────┬─────────────────┬─────────────────┬─────────────────┤
│     v0.4.0      │     v0.4.1      │     v0.4.2      │     v0.4.3      │     v0.4.4      │
│ Baseline Hardening│ Mesh Torture   │ UX Pipeline     │ Android OEM Hell│ Low-End BLE Fixes│
├─────────────────┼─────────────────┼─────────────────┼─────────────────┼─────────────────┤
│ • GattOpQueue   │ • 100k Scenarios│ • 10k Scenarios │ • 10k Scenarios │ • Dynamic MTU   │
│ • BoltDB Quotas │ • Fixed Defect A│ • Fixed SQLite  │ • 7 OEM Profiles│   Framing (0xFB)│
│ • Low-Bat Cutoff│   (Reboot Dedup)│   Status Race   │ • Invariants    │ • 100ms MTU Wait│
│ • Rust JNI Panic│ • Fixed Defect B│ • Causal Receipt│   O1–O24        │ • Burst Decouple│
│   Boundaries    │   (Relay Gating)│   Scheduling    │ • ghost-sim oem │ • QR Queue      │
└─────────────────┴─────────────────┴─────────────────┴─────────────────┴─────────────────┘
```

### 2.1 v0.4.0: Core Reliability & Mesh Resilience
- **Serialized GATT Queue (`GattOperationQueue.kt`):** Eliminates `GATT 133` controller collision errors via strict FIFO single-connection serialization, 150ms per-MAC cool-off, 15s watchdog timer, and dual API 33+ / legacy write fallback.
- **Go DTN Store Quotas & Eviction Priority (`go/ghostrouter`):** 50-message transit cap per destination, strict eviction protection for local unsent messages, and automatic BoltDB corruption recovery.
- **Power Policy Low-Battery Cutoff (`PowerPolicyEngine.kt`):** Strict cutoff of third-party transit relaying (`relayWillingness = 0.0f`) when battery drops below 20% while not charging, with a $\pm 2\%$ hysteresis band.
- **Rust / JNI Panic Isolation (`ghost-crypto`):** Pure Rust algorithm core wrapped in `std::panic::catch_unwind` error boundaries, preventing native panics from aborting the host JVM.

### 2.2 v0.4.1: Extreme Mesh Torture Hardening
- **Extreme Mesh Torture Engine (`sim/torture`):** Evaluated 100,000 deterministic scenarios across 10 dimensions and 4 campaign tiers (Boundary, Combination, Chaos, Pathological).
- **Defect A Closed (`I6_Dedup`):** Destination nodes rebooting while multiple carriers held spray copies no longer experience duplicate application delivery. Resolved by integrating persistent SQLite delivery deduplication (`dedup.go`).
- **Defect B Closed (`I7_RelayGating`):** Ensured nodes dropping below 20% battery immediately cease spraying transit messages during peer discovery.
- **Protocol Invariance:** Verified zero changes to wire format, opcodes, crypto primitives, or Spray-and-Wait parameters ($L=4, \text{MaxHops}=10, \text{TTL}=24\text{h}$).

### 2.3 v0.4.2: UX & Application State Torture Hardening
- **UX Responsiveness Torture Engine (`sim/ux`):** Modeled Compose UI recomposition, coroutine dispatchers, and StateFlow collectors under 10,000 deterministic scenarios.
- **Atomic SQLite Status Guard (`MessageDao.kt`, `GroupMessageDao.kt`):** Fixed an asynchronous race condition where delayed transport callbacks could downgrade a message from `DELIVERED` (`2`) back to `SENT` (`1`) or `PENDING` (`0`). Enforced:
  ```sql
  UPDATE messages SET status = :status WHERE id = :id AND (status != 2 OR :status = 2)
  ```
- **Generator Causality Fix:** Eliminated unclassified acausal delivery receipts by modeling physical GATT transmission latencies before receipt scheduling.

### 2.4 v0.4.3: Android OEM Hostile Runtime Engine
- **OEM Hell Engine (`sim/oem`):** Models the hostile Android OS/vendor boundary across 7 synthetic profiles (`OEM_STOCK`, `OEM_BACKGROUND_AGGRESSIVE`, `OEM_BLE_UNSTABLE`, `OEM_MEMORY_PRESSURE`, `OEM_BATTERY_AGGRESSIVE`, `OEM_SERVICE_HOSTILE`, `OEM_MAXIMUM_HOSTILITY`).
- **Invariants $O_1$ through $O_{24}$:** 24 formal invariants verified across 10,000 scenarios with zero violations ($P_0=0, P_1=0, P_2=0, P_3=0, P_4=0$).
- **Validation Scope Attribution:** Clearly distinguishes what is validated in the virtual model (`MODEL_VALIDATED`), what is validated in Android JVM tests (`ANDROID_UNIT_VALIDATED`), and what requires physical hardware (`PHYSICAL_DEVICE_VALIDATED`).

### 2.5 v0.4.4: Low-End Android BLE Reliability & Transport Framing
- **Dynamic MTU Transport Framing (`0xFB`, `GattOperationQueue.kt`):**
  Budget Android chipsets (e.g. MediaTek MT6739, Unisoc SC9863A) often fail ATT MTU exchange and stay locked at default 23 bytes (20 bytes effective payload). Outgoing envelopes exceeding `negotiatedMtu - 3` are now dynamically sliced into 7-byte framed fragments (`[0xFB][2B transferId][2B fragIndex][2B totalFrags][data...]`). Payloads within negotiated MTU are sent unfragmented, preserving backward compatibility.
- **Link Stabilization Delay:**
  Calling `requestMtu(512)` immediately upon `STATE_CONNECTED` frequently triggered controller crashes or `GATT 133` disconnect loops on budget radios. Enforced a 100ms delay after physical link connection before initiating MTU negotiation.
- **Inbound Bounded Reassembly (`BleManager.kt`):**
  Inbound `0xFB` frames are reassembled in memory per peer MAC. Sessions are bounded to 16 concurrent peers, 30s timeout, and a 64 KB maximum payload limit. Out-of-order and duplicate fragments are handled idempotently. Reassembly drops nested `0xFB` frames.
- **Scan Burst Decoupling (`GhostService.kt`):**
  Switched `BleManager.peers` observer from `Flow.collectLatest` to `Flow.collect`, and detached group retransmissions into independent child jobs with a 10-second per-group debounce map. High-frequency BLE scan events (every 100–300ms) no longer cancel running database transactions or in-flight GATT writes.
- **Reciprocal QR Verification Queue (`GhostService.kt`, `QRScanScreen.kt`):**
  Resolved a race condition where scanning a contact's QR code before observing their BLE advertisement dropped the verification handshake packet. Handshakes are now cached in memory (`pendingOutboundVerifications`) and flushed automatically upon peer discovery.
- **Truthful Status Progression (`GroupMessageSender.kt`):**
  Direct group writes remain `STATUS_PENDING` while the GATT write is in flight, transitioning to `STATUS_SENT` upon write confirmation from the controller callback, or falling back to `STATUS_SPRAYED` if direct unicast fails. Terminal `STATUS_DELIVERED` state protections ($U_2, U_{15}$) remain intact.
- **Envelope Validation & Key Integrity (`GroupMessageReceiver.kt`):**
  Incoming group envelopes are sanitized with Base64 whitespace/newline trimming and enforced to 32-byte Ed25519 public keys. Identity validation remains strictly authoritative via Ed25519; invalid signatures drop the packet with diagnostic logging.

---

## 3. The 4-Stage Adversarial Verification Architecture

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                    GHOST VERIFICATION HIERARCHY                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ STAGE 1: Deterministic Virtual Mesh Simulator                               │
│ • Scope: Discrete-event routing, 10–1000 nodes, BoltDB store                │
│ • Invariants: Delivery correctness, TTL bounds, partition healing           │
│ • CLI: ghost-sim run [scenario]                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ STAGE 2: Extreme Mesh Torture Engine                                        │
│ • Scope: 100,000 combinatorial scenarios, 10 parameter dimensions           │
│ • Invariants: I1–I15 (Conservation of copies, hop limits, storage quotas)  │
│ • CLI: ghost-sim torture --scenarios 100000                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│ STAGE 3: UX / Application Pipeline Torture Engine                           │
│ • Scope: 10,000 scenarios, Compose UI, ViewModel, Repository, Room         │
│ • Invariants: U1–U15 (Perceived ACK <1ms, 60 FPS scroll, atomic status)     │
│ • CLI: ghost-sim ux --scenarios 10000                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ STAGE 4: Android OEM Hostile Runtime Engine                                 │
│ • Scope: 10,000 scenarios across 7 hostile OEM profiles                     │
│ • Invariants: O1–O24 (Process kills, service restarts, GATT 133, MAC RPA)   │
│ • CLI: ghost-sim oem --scenarios 10000                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│ STAGE 5: Physical OEM Hardware Validation (Next Phase)                      │
│ • Scope: Physical multi-device lab (Pixel, Samsung, Xiaomi, OnePlus)        │
│ • Validation: Hardware RF, thermal throttling, F2FS physical reboot         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Comprehensive Invariant Validation Matrix

| Invariant | Category | Target Verification | Validation Scope | Result |
| :--- | :--- | :--- | :--- | :--- |
| **$I_1–I_{15}$** | Routing Core | Copy conservation ($L \le 4$), hop limits ($\le 10$), TTL ($24\text{h}$), deduplication, store bounds | `MODEL_VALIDATED` | **100,000 / 100,000 PASS** |
| **$U_1–U_{15}$** | UX Pipeline | Perceived ACK $<1\text{ms}$, 60 FPS scroll, no impossible status transitions, bounded collectors | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_1$** | Durability | Room messages survive volatile memory clearance and process death | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_2$** | Lifecycle | `GhostService` continues mesh routing when Activity is destroyed | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_3$** | Resilience | Service restarts cleanly via AlarmManager / START_STICKY | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_4$** | BLE Queue | Exactly $\le 1$ active client GATT connection across all peers | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_5$** | BLE Safety | Stale/late GATT callbacks arriving after teardown drop cleanly | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_6$** | Consistency | Status `DELIVERED` is final; cannot downgrade to `SENT` or `PENDING` | `MODEL_VALIDATED` / `ANDROID_UNIT_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_7$** | Radio Safety | Turning Bluetooth OFF aborts in-flight GATT and halts scanning | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_8$** | Recovery | Turning Bluetooth ON restores radio operations after stabilization | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_9$** | Identity | Sessions and conversations survive transient BLE MAC RPA rotations | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{10}–O_{11}$** | Permissions | Revoking BLE permissions stops radio cleanly; restoring resumes | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{12}$** | Energy | Battery $<20\%$ strictly gates third-party transit relaying | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{13}–O_{14}$** | Bounds | GATT queue depth and active UI flow observers stay strictly bounded | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{15}$** | FFI Safety | Native JNI calls return structured errors without crashing process | `MODEL_VALIDATED` (Native tests verify ASAN) | **10,000 / 10,000 PASS** |
| **$O_{16}–O_{20}$** | Storage/OS | Storage failure transparency, no duplicates, zero service deadlocks | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{21}$** | Determinism | Exact 5x repeated execution yields byte-identical state hashes | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |
| **$O_{22}–O_{24}$** | Protocol | Wire protocol unchanged, identity immutable, eventual quiescence | `MODEL_VALIDATED` | **10,000 / 10,000 PASS** |

---

## 5. Methodological Distinctions & Boundaries

1. **Virtual Process Durability vs. Physical Device Reboot Durability ($O_1$):**
   The virtual model proves that committed Room database state survives Linux process termination, LMKD memory clearance, and subsequent process respawn (`MODEL_VALIDATED`). However, the virtual engine does *not* claim to validate physical NAND/F2FS flash durability during sudden battery pull or hardware kernel panic (`PHYSICAL_DEVICE_VALIDATED`). Physical reboot validation is reserved for physical device lab testing.
2. **Virtual Hostile Soak vs. Physical Soak:**
   The virtual engine runs 24-hour equivalent hostile soak workloads in under 1 second of wall-clock time, testing logic, state transitions, and queues. Physical soak testing remains necessary to observe physical vendor hardware thermal throttling, radio antenna heating, and battery driver quirks.
3. **Native Boundary Validation ($O_{15}$):**
   The Go simulation validates logical contract safety, error code returns, and null handling. Physical memory safety (e.g. C/Rust pointer safety, buffer overrun prevention, thread synchronization in native space) is validated by pure Rust unit tests and Valgrind/ASAN.

---

## 6. Complete Unit & Integration Test Coverage

```text
Kotlin Android Tests (app/src/test/):
  - GroupProtocolTest.kt:              15 tests (PASS)
  - BleReliabilityTest.kt:             11 tests (PASS)
  - DeliveryReceiptProtocolTest.kt:     6 tests (PASS)
  - MessageStatusTransitionTest.kt:     4 tests (PASS)
  - PowerPolicyTest.kt:                 5 tests (PASS)
  - ShortCodeTest.kt:                   5 tests (PASS)
  - IntroductionProtocolTest.kt:        6 tests (PASS)
  Total Android Unit Tests:            52 / 52 PASS (100%)

Go Core & Simulation Tests (go/ghostrouter/):
  - router_test.go:                    14 tests (PASS)
  - simulator_test.go:                  7 tests (PASS)
  - sim/oem/oem_test.go:                8 tests (PASS)
  - ThreadSanitizer Race Detector:      PASS (0 data races)

Rust Native Cryptography (rust/ghost-crypto/):
  - lib.rs unit tests:                 12 tests (PASS)

Campaign Totals:
  - Stage 2 Extreme Mesh Torture:     100,000 scenarios PASS (0 violations)
  - Stage 3 UX Responsiveness:         10,000 scenarios PASS (0 violations)
  - Stage 4 Android OEM Hell Engine:   10,000 scenarios PASS (0 violations)
```

---

## 7. Next Steps: Physical Device Validation (Stage 5)
 
With invariant verification complete across virtual models and JVM unit tests, the remaining verification step is physical device testing:
1. Deploy `app-release.apk` across test devices (Google Pixel, Samsung Galaxy, Xiaomi Redmi, OnePlus).
2. Export physical event traces via the `PhysicalObservationTrace` JSON bridge.
3. Ingest physical traces into regression test harnesses to validate model fidelity.
