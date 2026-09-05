# GHOST Protocol — Extreme Mesh Torture Engine

**Author:** PEDDI SANKARA RAO  

The **GHOST Extreme Mesh Torture Engine** (`ghostrouter/sim/torture`) is a testing campaign system designed to subject the GHOST Protocol delay-tolerant mesh routing engine to extreme, pathological, and edge-case network conditions.

Instead of hand-crafting a few happy-path scenarios, the Torture Engine deterministically generates and evaluates **10,000+ distinct adversarial scenarios** across vast multidimensional parameter spaces, rigorously checking formal invariants (I1–I15) at critical state transitions and intermediate checkpoints.

---

## 1. Threat Philosophy

Real-world off-grid deployments (protests, natural disasters, active surveillance, RF interference, physical crowd mobility) are inherently hostile environments:
* Radios flap and disappear unpredictably.
* Phones reboot, suffer battery depletion, or get killed by the OS.
* Malicious or confused nodes flood duplicates or inject malformed packets.
* Network partitions isolate subgraphs for hours or days.
* Packets return in loops or arrive out-of-order.

The Torture Engine operates on the principle of **hostile universe generation**:
> *"What happens if everything goes wrong at exactly the worst possible moment?"*

The engine deliberately creates these conditions, combines them, randomizes them deterministically, and tests whether the routing core preserves correctness, security, identity, and conservation laws.

---

## 2. Generated Dimensions

The Torture Engine systematically explores 10 orthogonal operational dimensions:

| Dimension | Generated Range & Pathologies |
| :--- | :--- |
| **Topology** | 12 Pathological shapes: Chain, Star, Ring, Complete, Sparse, Disconnected Islands, Bridge, Bottleneck, Dynamic, Oscillating, Hub Failure, Rapid Churn. |
| **Node Count** | $0, 1, 2, 3, 4, 5, 10, 25, 50, 100+$ nodes. |
| **Workload** | Zero-byte payloads, 1-byte, 100-byte, 1000-byte, up to maximum 4000-byte messages; burst traffic at $t=0$; self-destined messages ($S=D$); nonexistent destinations. |
| **Channel Loss** | Uniform packet loss ($0\%, 1\%, 5\%, 10\%, 20\%, 50\%, 80\%, 95\%, 99\%, 100\%$); correlated burst blackouts (dropping $N$ consecutive packets). |
| **Battery / Policy** | Battery drain cycles ($100\% \to 19\% \to 50\% \to 5\%$); critical thresholds ($19.999\%$ vs $20.001\%$); relay willingness gating ($0.0$ to $1.0$). |
| **Mobility & Contacts** | Contact flapping ($UP \leftrightarrow DOWN$), long encounters ($1\text{h}$), micro-encounters ($1\text{ms}$), partition reconvergence. |
| **TTL Boundaries** | Contacts at $TTL - 1\text{s}$, $TTL$, $TTL + 1\text{s}$, 24 hours, 48 hours, 7 days. |
| **Hop Count** | Chain diffusion across $MaxHops-1$ ($9$), $MaxHops$ ($10$), and $MaxHops+1$ ($11$) boundaries. |
| **Node Lifecycle** | Mid-transit crashes, power-off cutoffs, reboots, and persistent BoltDB recoveries. |
| **Adversarial / Malformed** | Truncated headers, illegal opcodes, corrupted payloads, random fuzz bytes, duplicate flood attacks (up to 100 duplicates). |

---

## 3. Four Specialized Campaigns

The 10,000-scenario campaign is structured into four distinct campaign tiers:

1. **Boundary Campaign (Scenarios 0–999)**:
   Targets exact edge cases: 0/1/2 nodes, $S=D$, nonexistent destination, all nodes offline, all relay willingness $=0$, copy count boundaries ($L-1, L, L+1$), hop limits ($9, 10, 11$), TTL boundaries ($24\text{h} \pm 1\text{s}$), storage quotas ($499, 500, 501$), and battery thresholds ($19\%$ vs $20\%$).
2. **Combination Campaign (Scenarios 1,000–5,999)**:
   Combines 2 to 6 simultaneous stressors (e.g., high packet loss + dynamic partition + crash/restart + TTL boundary + duplicate storm).
3. **Chaos Campaign (Scenarios 6,000–7,999)**:
   Generates heavily randomized but syntactically valid sequences of 10–40 events (contacts, sends, battery adjustments, time jumps, reboots).
4. **Pathological Topology & Stress Campaign (Scenarios 8,000–9,999)**:
   Pathological graphs (chains, stars, bottlenecks, bridges, islands) with varying node counts ($4$ to $35+$ nodes) and long virtual time horizons ($24\text{h}$ to $7$ days).

---

## 4. Formal Invariant Matrix (I1–I15)

Every scenario continuously verifies 15 formal invariants:

* **I1 (Message Accounting)**: Every message created must be strictly accounted for:
  $$\text{MessagesCreated} = \text{Delivered} + \text{Pending} + \text{Expired} + \text{Failed} + \text{Rejected}$$
* **I2 (Delivery Correctness)**: Total logical deliveries $\le \text{Created}$. Delivered only to the intended destination node ($Dst$). Zero cross-recipient deliveries.
* **I3 (Copy Conservation)**: The sum of `CopiesRemaining` across all carriers in the network must never exceed $L=4$. Uncontrolled copy explosions are strictly forbidden.
* **I4 (Hop Limit)**: No message or packet in transit may exceed $\text{MaxHops}=10$.
* **I5 (TTL Enforcement)**: Messages past TTL ($\text{CreatedAt} + \text{TTL} < \text{Now}$) are rejected and pruned by the janitor; they can never be newly forwarded.
* **I6 (Deduplication)**: Even under intense duplicate storms (e.g. 100 copies), the application handler on the destination node receives exactly **one** logical delivery callback.
* **I7 (Relay Gating)**: Nodes with battery $<20\%$ or willingness $\le 0.0$ never accept or store transit relay messages.
* **I8 (Storage Bounds)**: Transit message storage is strictly capped at $500$ messages per node.
* **I9 (Crash Isolation)**: Crashed nodes (`!IsAlive`) have their router stopped and cannot forward, receive, or deliver packets.
* **I10 (Identity Stability)**: Node cryptographic IDs ($32$ bytes) are completely immutable across reboots, time advances, and crashes.
* **I11 (Infinite Forwarding Protection)**: Total forwarding events per message are bounded by finite graph properties ($\le L \times MaxHops = 40$).
* **I12 (Retry Bounds)**: Direct delivery attempts per peer are bounded ($\le 3$), preventing infinite GATT retry loops.
* **I13 (Persistence Durability)**: Valid unexpired messages and routing state survive reboots through BoltDB persistence.
* **I14 (Security & Malformed Robustness)**: Malformed, truncated, or unauthenticated packets are safely dropped without crashing, panicking, or causing unauthenticated deliveries.
* **I15 (Determinism)**: Given identical seeds and configurations, independent runs must produce identical event sequences and metrics.

### Hardened Edge-Case Invariants

During combinatorial chaos testing, two critical routing failure modes were diagnosed and hardened:

1. **Persistent Inbound Delivery Deduplication ($I_6$):**
   - *Failure Mechanism:* In Spray-and-Wait ($L=4$), redundant carriers carry duplicate copies. If destination node $D$ crashed after initial delivery, an in-memory dedup filter was reinitialized empty. When a secondary carrier arrived after reboot, $D$ would re-deliver the payload to the application layer.
   - *Hardening:* In `router.go:OnMessageReceived()`, inbound messages destined for the local node are durably committed to BoltDB with `StatusDelivered` *before* invoking `handler.OnDeliver()`. In addition, `store.go:SaveMessage()` protects local delivered messages from transit quota eviction.
2. **Relay Gating Under Battery Depletion ($I_7$):**
   - *Failure Mechanism:* A node whose battery depleted below 20% ($\text{willingness} \le 0.0$) stopped accepting incoming transit messages, but continued to spray pre-stored transit messages from earlier encounters during `OnPeerDiscovered()`.
   - *Hardening:* In `router.go:OnPeerDiscovered()`, explicit willingness checks gate both direct deliveries and spray loops for transit messages (`!bytes.Equal(msg.Src, r.localID)`). Transit messages remain safely retained in BoltDB to resume forwarding once the battery recharges above threshold.

---

## 5. Automated Test-Case Shrinking

When an invariant violation is discovered, the **Shrinker** (`torture/shrinker.go`) applies delta-debugging and binary reduction:
1. **Binary search on event prefix**: Identifies the exact prefix `Events[:k]` where the violation first manifests.
2. **Delta reduction of intermediate events**: Iteratively eliminates non-essential intermediate actions (e.g. redundant contacts or battery updates).
3. **Peripheral node pruning**: Removes nodes that do not participate in the failing interaction.

This reduces large multi-node scenarios into a minimal, reproducible regression vector (e.g., 2 nodes, 1 message, 3 events).

---

## 6. Failure Corpus & Deduplication

All unique failures are recorded in `testdata/torture/failures/<hash>/`:
* `manifest.json`: Full configuration, seed, failing invariant, error message, and reproduction command.
* `reproduce.sh`: Shell script to execute instant replay.

Failures are deduplicated by root-cause signature (`hash(InvariantID + ErrorSummary)`) and classified into severity tiers:
* **P0**: Cryptographic / Security failure (unauthenticated delivery, encryption bypass).
* **P1**: Message loss / corruption / identity mutation.
* **P2**: Routing correctness / persistence / duplicate delivery defect.
* **P3**: Resource / stability degradation (storage quota overflow, retry explosion).
* **P4**: Observability / harness issue.

---

## 7. CLI Usage

### Running a Torture Campaign
```bash
# Run 10,000 scenarios using 8 parallel workers
./bin/ghost-sim torture --scenarios 10000 --seed 123456789 --workers 8

# Output results in machine-readable JSON
./bin/ghost-sim torture --scenarios 10000 --json > torture_results.json
```

### Replaying a Scenario
```bash
# Replay scenario by index
./bin/ghost-sim replay --campaign 123456789 --index 42

# Replay scenario by derived seed
./bin/ghost-sim replay --seed 2074860703886818178
```

---

## 8. Deterministic Isolation Architecture

To ensure total safety during parallel execution:
* Every scenario runs within its own temporary BoltDB directory (`os.MkdirTemp`).
* Each scenario instantiates an isolated `SimEngine` and `SimClock`.
* Pseudo-random numbers are generated exclusively from per-scenario derived seeds (`sha256(campaign_seed, index)`).
* Zero wall-clock dependencies (`time.Sleep`).
* Zero cross-worker shared memory or race conditions.
