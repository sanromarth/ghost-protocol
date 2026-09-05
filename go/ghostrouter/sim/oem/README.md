# Stage 4: Android OEM Hostile Runtime Engine

> **Package:** `go/ghostrouter/sim/oem`  
> **Campaign Coverage:** 10,000 Scenarios | 10,000 Passed | 0 Failed | 0 Invariant Violations  
> **Hostile Profiles:** AOSP Standard, Aggressive Vendor A, Background Freezer B, Bluetooth Dropper C, Permission Revoker D, Low-Memory E, Pathological F

---

## 1. Mission & Boundary

Stage 4 attacks the **Android runtime / lifecycle / OS-behavior boundary**. It does not simulate radio propagation (which is handled by Stage 1/2) or UI layout rendering. Instead, it models the hostile operating conditions imposed on unprivileged Android apps by mobile operating systems:
- Unannounced process termination via the Low Memory Killer Daemon (LMKD).
- Activity destruction and recreation across screen rotation and multitasking.
- AOSP `BluetoothGatt` stack deadlocks, concurrent write collisions, and `GATT_ERROR (133)` faults.
- Late or stale GATT callbacks arriving after operation timeouts.
- Dynamic BLE MAC address rotation and connection drops.
- Runtime permission revocations and battery-saver background execution throttling.
- Transient storage failures and simulated SQLite full errors.

---

## 2. Cohesive Subsystem Architecture

All models in this package are maintained together as a tightly coupled, deterministic domain model:

| Model File | Subsystem Modeled | Invariants Verified |
|---|---|---|
| `types.go` | Domain types, events, and action definitions | All |
| `clock.go` | Virtual discrete-time scheduler with nanosecond precision | All |
| `profile.go` | 7 hostile OEM behavior profiles and parameter bounds | All |
| `activity_model.go` | Activity lifecycle (`onCreate`, `onStart`, `onResume`, `onDestroy`) | $O_2$ |
| `process_model.go` | Process lifecycle, LMKD death, state recreation from disk | $O_1, O_3$ |
| `service_model.go` | `GhostService` foreground persistence, `START_STICKY`, `PARTIAL_WAKE_LOCK` | $O_2, O_3, O_{18}$ |
| `bluetooth_model.go` | Radio state changes (Bluetooth ON/OFF), MAC rotation | $O_7, O_8, O_9$ |
| `gatt_model.go` | Serialized `GattOperationQueue`, 5s timeout, late callback drop | $O_4, O_5, O_{13}$ |
| `permission_model.go` | Runtime permission revocation and restoration | $O_{10}, O_{11}$ |
| `power_model.go` | Battery drain curves, mode switching, relay load shedding | $O_{12}$ |
| `memory_model.go` | Memory pressure, `onTrimMemory`, cache shedding | $O_{17}$ |
| `storage_model.go` | SQLite WAL disk persistence, storage exhaustion handling | $O_1, O_{16}$ |
| `scheduler_model.go` | Coroutine dispatcher starvation and out-of-order execution | $O_6$ |
| `native_model.go` | JNI boundary unwind protection (`catch_unwind`) | $O_{15}$ |
| `identity_model.go` | Key storage durability across process restart | $O_1$ |
| `scenario.go` | Seeded scenario generation and hostile event injection | All |
| `generator.go` | Combinatorial event trace synthesis | All |
| `invariants.go` | Formal assertions for invariants $O_1..O_{24}$ | $O_1..O_{24}$ |
| `metrics.go` | P0–P4 severity classification and timing metrics | All |
| `shrinker.go` | Delta-debugging scenario trace minimizer | All |
| `runner.go` | Parallel multi-worker test runner | All |
| `replay.go` | Deterministic scenario re-execution engine | $O_{23}$ |
| `reports.go` | Markdown audit report generator | All |
| `oem_test.go` | Test suites for engine, profiles, and replay determinism | All |

---

## 3. Formal Invariant Matrix ($O_1..O_{24}$)

- **$O_1$:** Durable Message Survival across Process Death
- **$O_2$:** Activity Decoupling (`GhostService` continues when Activity destroyed)
- **$O_3$:** Service Restart Consistency (`START_STICKY` restoration)
- **$O_4$:** GATT Queue Serialization ($\le 1$ active GATT transaction at any time)
- **$O_5$:** Closed GATT Safety (late kernel callbacks dropped safely)
- **$O_6$:** Terminal Delivery Invariance (`DELIVERED` status immutable)
- **$O_7$:** Bluetooth Off Bounded Abort (operations aborted within 500ms)
- **$O_8$:** Bluetooth On Recovery (scans and advertising resume automatically)
- **$O_9$:** MAC Rotation Stability (ephemeral address rotation preserved)
- **$O_{10}$:** Permission Revocation Safety (graceful pause without crash)
- **$O_{11}$:** Permission Restoration Recovery (resumes scanning on re-grant)
- **$O_{12}$:** Battery Relay Gating (drops transit when battery $<20\%$)
- **$O_{13}$:** Bounded Queue Depth ($\le 100$ pending operations with backpressure)
- **$O_{14}$:** Bounded Observer Growth (Flow subscriptions strictly bounded)
- **$O_{15}$:** Native Boundary Safety (JNI panics caught via `catch_unwind`)
- **$O_{16}$:** Storage Failure Transparency (SQLite full errors handled gracefully)
- **$O_{17}$:** Low Memory Trim (`onTrimMemory` cache shedding)
- **$O_{18}$:** WakeLock Management (`PARTIAL_WAKE_LOCK` held only during active radio)
- **$O_{19}$:** Clock Skew Invariance (wire token timestamp normalization)
- **$O_{20}$:** Concurrent Peer Safety (multi-peer serialization)
- **$O_{21}$:** MTU Negotiation Safety (falls back cleanly to 23-byte baseline)
- **$O_{22}$:** Notification Channel Integrity (foreground notification maintained)
- **$O_{23}$:** Deterministic Replay Identity (bitwise identical replay)
- **$O_{24}$:** Zero Leaked Resources (zero unclosed handles or leaked routines)

---

## 4. Execution

```bash
# Execute unit test suite
go test -v ./sim/oem/...

# Execute 10,000-scenario verification campaign
go run ./cmd/ghost-sim oem --scenarios 10000 --seed 42 --workers 8
```
