# GHOST Go Routing & Simulation Subsystem

> **Directory:** `go/`  
> **Module Root:** `go/ghostrouter/` (`go.mod`)  
> **Go Version:** 1.22+  
> **Export Target:** Android AAR via `gomobile bind`

---

## 1. Purpose & Responsibilities

The Go subsystem implements the network-layer core of GHOST:
1. **Spray-and-Wait Epidemic Router (`router.go`):** Bounded store-carry-forward delay-tolerant routing with $L=4$ copies and $\text{MaxHops}=10$.
2. **Persistent Storage Engine (`store.go`):** Embedded BoltDB key-value store managing delay-tolerant message payloads with a strict 50 MB budget and automatic 24-hour TTL janitor sweeps.
3. **Crash-Proof Deduplication Engine (`dedup.go`):** SQLite WAL-mode deduplication table (`seen_packets`) surviving process termination and system reboots ($I_6$).
4. **Transport Batch Serializer (`serializer.go`):** Single-session composite GATT packet serializer cutting radio connection time by ~70%.
5. **Adversarial Simulation Hierarchy (`sim/`):** Unified 4-stage virtual-time verification engine comprising 120,000 deterministic test scenarios.

---

## 2. gomobile Binding Constraints

The root Go package `go/ghostrouter` is exported directly to Android via `gomobile bind`:
```bash
gomobile bind -target android/arm64,android/amd64 -androidapi 26 \
    -o android/app/libs/ghostrouter.aar .
```

### Critical Architectural Rule:
- `gomobile bind` **refuses** to export Go packages named `internal` or packages located inside `internal/`.
- All public types bridged to Kotlin (`GhostRouter`, `DeliverHandler`, `BlobList`, `NewRouter`) **must remain** in package `ghostrouter` at `go/ghostrouter/`.

---

## 3. Directory Structure

```text
go/ghostrouter/
├── go.mod, go.sum             # Go module definition
├── router.go                  # Delay-tolerant Spray-and-Wait router
├── message.go                 # Wire packet formats and byte serialization
├── store.go                   # BoltDB persistent message carrier store
├── dedup.go                   # Persistent SQLite WAL deduplication store
├── serializer.go              # Single-session GATT batch encoder
├── router_test.go             # Unit tests for router, store, and dedup
├── simulator_test.go          # Integration tests between router and simulator
│
├── cmd/
│   └── ghost-sim/             # Unified CLI simulation binary
│       └── main.go
│
├── sim/                       # 4-Stage Adversarial Simulation Suite
│   ├── README.md              # Simulation architecture overview
│   ├── *.go                   # Stage 1: Deterministic Virtual Mesh Simulator
│   ├── torture/               # Stage 2: Extreme Mesh Torture Engine
│   ├── ux/                    # Stage 3: UX & State Machine Torture Engine
│   └── oem/                   # Stage 4: Android OEM Hostile Runtime Engine
│
└── testdata/                  # Regression test fixtures & failure manifests
    └── torture/failures/...
```

---

## 4. Testing & Verification

### Running Unit & Race Tests
```bash
cd go/ghostrouter
CGO_ENABLED=0 go test -v ./...
```

### Compiling & Running the Simulator CLI
```bash
cd go/ghostrouter
go build -o bin/ghost-sim ./cmd/ghost-sim

# Stage 1: Mesh routing
./bin/ghost-sim run --nodes 20 --scenario 04

# Stage 2: Extreme mesh torture (100,000 scenarios)
./bin/ghost-sim torture --scenarios 1000 --seed 42 --workers 8

# Stage 3: UX & application torture (10,000 scenarios)
./bin/ghost-sim ux --scenarios 1000 --seed 42 --workers 8

# Stage 4: Android OEM Hell Engine (10,000 scenarios)
./bin/ghost-sim oem --scenarios 10000 --seed 42 --workers 8
```
