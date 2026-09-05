# GHOST Protocol — 4-Stage Simulation Architecture

> **Author:** PEDDI SANKARA RAO  
> **Status:** Specification  
> **Target:** Discrete-event mesh, UX, and OEM lifecycle simulation suite  

The GHOST verification suite validates protocol correctness, DTN Spray-and-Wait invariants, routing algorithms, persistent storage, deduplication, TTL expiration, relay quotas, UI state machines, and Android runtime lifecycle boundaries in discrete virtual time. It does not replace physical radio validation on real device hardware.

---

```text
                 GHOST PROTOCOL 4-STAGE VERIFICATION
                                  │
    ┌─────────────────────────────┼─────────────────────────────┐
    ▼                             ▼                             ▼
STAGE 1: MESH SIM            STAGE 2: TORTURE              STAGE 3: UX ENGINE
10–1000 nodes                100,000 scenarios             10,000 scenarios
Routing & DTN                Combinatorial Chaos           Compose / ViewModel / Room
Invariants I1–I15            Invariants I1–I15             Invariants U1–U15
`ghost-sim run`              `ghost-sim torture`           `ghost-sim ux`
    │                             │                             │
    └─────────────────────────────┼─────────────────────────────┘
                                  ▼
                     STAGE 4: ANDROID OEM HELL
                     10,000 scenarios / 7 OEM Profiles
                     LMKD / GATT 133 / Background Freeze
                     Invariants O1–O24
                     `ghost-sim oem`
                                  │
                                  ▼
                     STAGE 5: PHYSICAL OEM LAB
                     Hardware RF / Thermal / F2FS Reboot
                     PhysicalObservationTrace Bridge
```

---

## 1. Overview & Verification Hierarchy

GHOST is an offline, serverless, delay-tolerant, end-to-end encrypted BLE mesh communication system. Hand-held testing across 2–5 phones is essential for radio validation, but mathematically incapable of exploring pathological race conditions, high-order network partitions, multi-day TTL expirations, buffer exhaustion, or OEM vendor task-killer aggression.

To certify the codebase for high-risk deployments, GHOST implements a 4-tier simulation hierarchy driven by the unified CLI tool `ghost-sim` (`go/ghostrouter/cmd/ghost-sim`):

| Stage | Subsystem Target | Invariant Set | Campaign Size | Primary CLI Command |
| :--- | :--- | :--- | :--- | :--- |
| **Stage 1** | Go DTN Routing Core & Mesh Topologies | Canonical $I_1–I_{15}$ | 12 Scenarios + Stress | `ghost-sim run <scenario>` |
| **Stage 2** | Extreme Mesh Fuzzing & Network Chaos | Formal $I_1–I_{15}$ | 100,000 Scenarios | `ghost-sim torture --scenarios 100000` |
| **Stage 3** | Android UI, Coroutines, StateFlow, Room | Formal $U_1–U_{15}$ | 10,000 Scenarios | `ghost-sim ux --scenarios 10000` |
| **Stage 4** | Hostile Android OEM Runtime & OS Boundary | Formal $O_1–O_{24}$ | 10,000 Scenarios | `ghost-sim oem --scenarios 10000` |

---

## 2. Stage 1: Deterministic Virtual Mesh Simulator (`sim`)

Stage 1 validates basic routing mechanics and multi-node topologies using the production Go router (`ghostrouter.Router`):
- **`SimClock`**: Virtual discrete timestamp provider eliminating `time.Sleep`.
- **`SimNode`**: Virtual participant running a real `ghostrouter.Router` with isolated BoltDB storage.
- **`VirtualRadio`**: Deterministic radio channel model managing topology adjacency, RSSI levels, and packet drops.
- **`SimEngine`**: Coordinates message injection, symmetrical encounter passes, and invariant assertions.

### Canonical Scenarios
1. `direct`: $A \leftrightarrow B$ direct delivery confirmation.
2. `one_relay`: Delay-tolerant store-and-forward routing ($A \rightarrow B \rightarrow C$).
3. `partition`: Partition healing and delay-tolerant delivery across disconnected subgraphs.
4. `spray`: Exact $L=4$ binary spray copy distribution and conservation.
5. `duplicate`: Multi-path duplicate mitigation and deduplication.
6. `ttl`: 24-hour TTL expiration and background janitor pruning.
7. `hop_limit`: Hop count limit enforcement ($\text{MaxHops}=10$).
8. `low_battery`: Battery depletion relay gating ($<20\% \implies \text{willingness} = 0.0$).
9. `crash_restart`: Mid-transit crash, reboot, and BoltDB disk persistence.
10. `repeated`: Rapid connection flapping and deduplication stability.
11. `packet_loss`: Routing tolerance under 20%, 50%, and 80% packet loss.
12. `100_node`: 100-node $10 \times 10$ mesh grid diffusion.

---

## 3. Stage 2: Extreme Mesh Torture Engine (`sim/torture`)

The Extreme Mesh Torture Engine subjects the routing engine to 100,000 deterministic combinatorial scenarios across 10 parameter dimensions:
- **Topology**: 12 pathological graphs (chains, stars, bottlenecks, rings, bridges, dynamic churn).
- **Network Conditions**: Correlated burst blackouts, 0–100% loss rates, micro-encounters ($1\text{ms}$).
- **Energy & State**: Mid-transit crashes, power-off cutoffs, and sudden battery drops.

### Invariants Verified ($I_1–I_{15}$)
- $I_1$ Delivery Correctness: Delivered payloads match byte-for-byte.
- $I_2$ Copy Conservation: Total copies in flight never exceed initial $L=4$.
- $I_3$ Hop Limits: No message exceeds $\text{MaxHops}=10$.
- $I_4$ TTL Expiration: No message delivered after $TTL = 24\text{h}$.
- $I_5$ Storage Bounds: Storage never exceeds 500 messages per node.
- $I_6$ Persistent Deduplication: Delivered messages never re-delivered after node reboot.
- $I_7$ Relay Gating: Battery $<20\%$ strictly gates relaying.

---

## 4. Stage 3: UX & Application Pipeline Torture Engine (`sim/ux`)

Stage 3 attacks the user-facing Android application pipeline:
$$\text{User Action} \to \text{Compose UI} \to \text{ChatViewModel} \to \text{ConversationRepository} \to \text{Room} \to \text{Go/Rust} \to \text{BLE/GATT}$$

### Modeled Internals
- **Compose Frame Timing**: Simulates 16.6ms frame intervals and recomposition passes.
- **Coroutines & Dispatchers**: Models asynchronous handoffs between `Dispatchers.Main` and `Dispatchers.Default` / `IO`.
- **Atomic SQLite Status Guard**: Verifies that status `DELIVERED` is immutable and cannot be rolled back by delayed transport callbacks:
  ```sql
  UPDATE messages SET status = :status WHERE id = :id AND (status != 2 OR :status = 2)
  ```
- **Invariants ($U_1–U_{15}$)**: Sub-1ms perceived send ACK, 60 FPS scroll performance, no impossible status transitions, bounded coroutine observers.

---

## 5. Stage 4: Android OEM Hostile Runtime Engine (`sim/oem`)

Stage 4 models the hostile mobile operating system boundary across 7 synthetic hostility profiles:
1. `OEM_STOCK`: AOSP / Google Pixel baseline.
2. `OEM_BACKGROUND_AGGRESSIVE`: Strict background freezer.
3. `OEM_BLE_UNSTABLE`: Flaky vendor stack with high `GATT 133` and unexpected disconnect rates.
4. `OEM_MEMORY_PRESSURE`: Low RAM budget (128–256 MB) with aggressive LMKD process termination.
5. `OEM_BATTERY_AGGRESSIVE`: Aggressive deep sleep and wake-lock suppression.
6. `OEM_SERVICE_HOSTILE`: Aggressive task-killer terminating services upon recents swipe.
7. `OEM_MAXIMUM_HOSTILITY`: Combinatorial simultaneous application of all failure mechanisms.

### Invariants Verified ($O_1–O_{24}$)
- $O_1$ Durable Message Survival across process death.
- $O_2$ Activity Decoupling: `GhostService` continues background mesh routing when Activity is destroyed.
- $O_3$ Service Restart Consistency: Clean service resurrection via `AlarmManager`.
- $O_4$ GATT Queue Serialization: Exactly $\le 1$ active client connection across all peers.
- $O_5$ Closed GATT Safety: Late/duplicate callbacks dropped cleanly without panic.
- $O_6$ Terminal Delivery Invariance: Status `DELIVERED` cannot downgrade.
- $O_7$ Bluetooth Off Bounded Abort: Radio operations halt cleanly within bounded time.
- $O_8$ Bluetooth On Recovery: Radio operations resume after 1000ms stabilization.
- $O_9$ MAC Rotation Stability: Conversations mapped to immutable Ed25519 identity, surviving BLE RPA rotation.
- $O_{10}–O_{11}$ Permission Safety: Dynamic revocation and restoration handled without `SecurityException`.
- $O_{12}$ Battery Relay Gating: Battery $<20\%$ gates relaying across all security postures.
- $O_{13}–O_{14}$ Resource Bounds: GATT queue depth and active flow collectors strictly bounded.
- $O_{15}$ Native Boundary Safety: JNI errors return structured results without crashing the process.
- $O_{16}–O_{20}$ OS Resilience: Storage failure transparency, no duplicates, zero deadlocks.
- $O_{21}$ Deterministic Replay: 5x repeated execution yields byte-identical state hashes.
- $O_{22}–O_{24}$ Protocol Invariance: Wire protocol unchanged, identity immutable, eventual quiescence.

---

## 6. Validation Scope Attribution Matrix

To ensure absolute methodological integrity, every invariant is explicitly tagged with its validation scope:

| Scope Label | Meaning & Validation Authority |
| :--- | :--- |
| `MODEL_VALIDATED` | Verified by the discrete deterministic Go simulation engine under simulated virtual time. |
| `ANDROID_UNIT_VALIDATED` | Verified by JUnit/Robolectric test suites running against the compiled Android JVM bytecode. |
| `PHYSICAL_DEVICE_VALIDATED` | Requires physical Android mobile hardware (Pixel, Samsung, Xiaomi) with real radios and batteries. |

> [!IMPORTANT]
> **Virtual Process Durability vs. Physical Device Reboot Durability:**
> Invariant $O_1$ is validated for virtual process termination and respawn (`MODEL_VALIDATED`). Committed Room rows survive memory clearance and PID changes.
> However, virtual simulation does **not** prove physical NAND/F2FS flash durability during sudden battery pull or kernel panic (`PHYSICAL_DEVICE_VALIDATED`). Physical reboot testing remains required on actual OEM devices.

---

## 7. CLI Command Reference (`ghost-sim`)

```bash
# Build the simulator CLI
cd go/ghostrouter
go build -o bin/ghost-sim ./cmd/ghost-sim

# Stage 1: Run canonical scenario
./bin/ghost-sim run direct
./bin/ghost-sim run partition --json
./bin/ghost-sim run stress --nodes 100 --messages 1000

# Stage 2: Run Extreme Mesh Torture Campaign
./bin/ghost-sim torture --scenarios 100000 --seed 123456789 --workers 8

# Stage 3: Run UX Responsiveness Campaign
./bin/ghost-sim ux --scenarios 10000 --seed 42 --workers 8

# Stage 4: Run Android OEM Hostile Runtime Engine
./bin/ghost-sim oem --scenarios 10000 --seed 42 --workers 8

# Replay specific scenario deterministically
./bin/ghost-sim replay --campaign 42 --index 573
```
