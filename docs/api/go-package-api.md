# GHOST Protocol Go Package API Reference

> **Version:** v0.4.3 — reflects production routing engine and the 4-tier simulation verification suite.
> **Source Directory:** `go/ghostrouter/`
> **Packages:** `ghostrouter`, `ghostrouter/sim`, `ghostrouter/sim/torture`, `ghostrouter/sim/ux`, `ghostrouter/sim/oem`.

---

## 1. Package: `ghostrouter`

Delay-Tolerant Network (DTN) Binary Spray-and-Wait opportunistic router with BoltDB message store and persistent SQLite deduplication, compiled via `gomobile` into `ghostrouter.aar`.

### Exported Types

```go
// Router — main entry point, instantiated once per service lifetime
type Router struct { /* unexported fields */ }

// DeliverHandler — implemented by Kotlin to receive incoming application deliveries
type DeliverHandler interface {
    OnDeliver(senderId []byte, payload []byte)
}

// SendResult — wraps multi-return for gomobile compatibility
type SendResult struct {
    IsDirect bool    // true if destination encountered directly; false if stored for spray
    Blob     []byte  // routed wire blob ready for BLE transmission (nil if queued)
}

// BlobList — wraps [][]byte for gomobile compatibility
type BlobList struct { /* unexported */ }
func (b *BlobList) Size() int
func (b *BlobList) Get(i int) []byte
```

### Exported Functions & Methods

```go
// NewRouter instantiates the routing engine
// CRITICAL: localID is cloned internally to protect against ephemeral JNI buffer reuse
func NewRouter(localID []byte, dbPath string, handler DeliverHandler) (*Router, error)

// Start initiates background worker routines and janitor cleanup
func (r *Router) Start()

// Stop safely flushes BoltDB transactions, closes deduplication tables, and halts workers
func (r *Router) Stop()

// SendMessage queues or transmits an outbound message
func (r *Router) SendMessage(dst []byte, payload []byte) (*SendResult, error)

// OnPeerDiscovered evaluates encounter candidate deliveries and returns batched transmission blobs
func (r *Router) OnPeerDiscovered(peerID []byte, rssi int) *BlobList

// OnMessageReceived ingests an incoming routed blob from BLE
// Returns: "delivered", "forwarded", "dropped: <reason>", or "error: <details>"
func (r *Router) OnMessageReceived(data []byte) string

// SetRelayWillingness updates dynamic relay gating (0.0 to 1.0)
// When w == 0.0, transit messages are rejected to conserve battery.
func (r *Router) SetRelayWillingness(w float32)

// GetRelayWillingness returns current relay willingness factor
func (r *Router) GetRelayWillingness() float32

// SetTimeProvider overrides system time for deterministic virtual testing
func (r *Router) SetTimeProvider(provider func() time.Time)

// GetStats returns JSON-encoded router performance and queue metrics
func (r *Router) GetStats() string

// Batch serialization helpers
func EncodeBatch(encodedMessages [][]byte) ([]byte, error)
func DecodeBatch(data []byte) ([][]byte, error)
func ShortHex(data []byte) string
```

### Persistent Deduplication & Quota Invariants
- **Reboot Invariance ($I_6$):** Delivered message IDs are written to an atomic persistent SQLite store. Upon process reboot, secondary carriers delivering spray copies are cleanly recognized as duplicates.
- **Destination Quotas:** Capped at 50 transit messages per destination to prevent buffer starvation.
- **Eviction Order:** (1) Expired TTL ($>24\text{h}$), (2) Delivered messages, (3) Transit relays, (4) Fully sprayed local messages. Local unsent pending messages are strictly protected.

---

## 2. Package: `ghostrouter/sim` (Stage 1)

Deterministic discrete-event virtual mesh simulator.

```go
// SimClock — monotonic virtual timestamp manager
type SimClock struct { /* unexported */ }
func NewSimClock(epoch time.Time) *SimClock
func (c *SimClock) Now() time.Time
func (c *SimClock) Advance(d time.Duration) time.Time

// SimNode — virtual participant running production Router
type SimNode struct {
    Name    string
    ID      []byte
    Router  *ghostrouter.Router
    Battery int
}

// SimEngine — coordinates message injection, encounters, and invariant checks
type SimEngine struct { /* unexported */ }
func NewSimEngine(seed int64) *SimEngine
func (e *SimEngine) AddNode(name string) *SimNode
func (e *SimEngine) Connect(a, b string, rssi int)
func (e *SimEngine) Disconnect(a, b string)
func (e *SimEngine) Exchange(a, b string)
func (e *SimEngine) Advance(d time.Duration)
func (e *SimEngine) RunCanonical(name string) (*SimResults, error)
```

---

## 3. Package: `ghostrouter/sim/torture` (Stage 2)

Extreme Mesh Torture Engine generating 100,000 combinatorial adversarial scenarios.

```go
type TortureConfig struct {
    ScenarioCount int
    MasterSeed    int64
    WorkerCount   int
    OutputDir     string
}

type CampaignMetrics struct {
    TotalScenarios  int
    PassedScenarios int
    FailedScenarios int
    Violations      map[InvariantID]int
}

// Invariants I1 through I15
const (
    I1_DeliveryCorrectness    InvariantID = "I1_DeliveryCorrectness"
    I2_CopyConservation       InvariantID = "I2_CopyConservation"
    I3_HopLimits              InvariantID = "I3_HopLimits"
    I4_TTLExpiration          InvariantID = "I4_TTLExpiration"
    I5_StorageBounds          InvariantID = "I5_StorageBounds"
    I6_PersistentDedup        InvariantID = "I6_PersistentDedup"
    I7_RelayGating            InvariantID = "I7_RelayGating"
    /* ... I8 through I15 ... */
)

func RunCampaign(cfg TortureConfig) (*CampaignMetrics, error)
```

---

## 4. Package: `ghostrouter/sim/ux` (Stage 3)

UX and Jetpack Compose responsiveness torture engine.

```go
type UXCampaignConfig struct {
    ScenarioCount int
    MasterSeed    int64
    WorkerCount   int
}

// Invariants U1 through U15
const (
    U1_NoDuplicateLogicalMsg InvariantID = "U1_NoDuplicateLogicalMsg"
    U2_NoFalseDelivery       InvariantID = "U2_NoFalseDelivery"
    U3_DurableStateSurvives  InvariantID = "U3_DurableStateSurvives"
    U4_NoImpossibleTransition InvariantID = "U4_NoImpossibleTransition"
    U5_NoStaleRollback       InvariantID = "U5_NoStaleRollback"
    /* ... U6 through U15 ... */
)

func RunCampaign(cfg UXCampaignConfig) (*CampaignResult, error)
```

---

## 5. Package: `ghostrouter/sim/oem` (Stage 4)

Hostile Android OEM runtime engine modeling OS lifecycle, LMKD, GATT 133, and vendor restrictions.

```go
type CampaignConfig struct {
    TotalScenarios int
    BaseSeed       int64
    NumWorkers     int
    ProfileFilter  *OemProfileType
    OnProgress     func(completed, total, passed, failed int)
}

// Invariants O1 through O24
const (
    O1_DurableMessageSurvival     InvariantID = "O1_DurableMessageSurvival"
    O2_ActivityDecoupling         InvariantID = "O2_ActivityDecoupling"
    O3_ServiceRestartConsistency  InvariantID = "O3_ServiceRestartConsistency"
    O4_GattQueueSerialization     InvariantID = "O4_GattQueueSerialization"
    O5_ClosedGattSafety           InvariantID = "O5_ClosedGattSafety"
    O6_TerminalDeliveryInvariance InvariantID = "O6_TerminalDeliveryInvariance"
    O7_BluetoothOffBoundedAbort   InvariantID = "O7_BluetoothOffBoundedAbort"
    O8_BluetoothOnRecovery        InvariantID = "O8_BluetoothOnRecovery"
    O9_MacRotationStability       InvariantID = "O9_MacRotationStability"
    O10_PermissionRevocationSafety InvariantID = "O10_PermissionRevocationSafety"
    O11_PermissionRestorationRecov InvariantID = "O11_PermissionRestorationRecov"
    O12_BatteryRelayGating        InvariantID = "O12_BatteryRelayGating"
    O13_BoundedQueueDepth         InvariantID = "O13_BoundedQueueDepth"
    O14_BoundedObserverGrowth     InvariantID = "O14_BoundedObserverGrowth"
    O15_NativeBoundarySafety      InvariantID = "O15_NativeBoundarySafety"
    O16_StorageFailureTransparent InvariantID = "O16_StorageFailureTransparent"
    O17_NoLogicalDuplicates       InvariantID = "O17_NoLogicalDuplicates"
    O18_NoCommittedMessageLoss    InvariantID = "O18_NoCommittedMessageLoss"
    O19_ValidStateProgression     InvariantID = "O19_ValidStateProgression"
    O20_DeadlockFreeService       InvariantID = "O20_DeadlockFreeService"
    O21_ExactDeterministicReplay  InvariantID = "O21_ExactDeterministicReplay"
    O22_WireProtocolInvariance    InvariantID = "O22_WireProtocolInvariance"
    O23_IdentityImmutability      InvariantID = "O23_IdentityImmutability"
    O24_EventualQuiescence        InvariantID = "O24_EventualQuiescence"
)

func RunOemCampaign(cfg CampaignConfig) (*CampaignMetrics, []*ScenarioResult, error)
func VerifyDeterministicReplay(scenario *OemScenario, iterations int) error
func ImportPhysicalTrace(filePath string) (*PhysicalObservationTrace, error)
func ConvertPhysicalTraceToScenario(trace *PhysicalObservationTrace, profile OemProfile) *OemScenario
func GenerateForensicReport(metrics *CampaignMetrics, results []*ScenarioResult, seed int64) string
```
