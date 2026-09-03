# GHOST Protocol Go Package API Reference

> **Version:** v0.2.0 — reflects actual implemented code in `go/ghostrouter/`.
> The `ghost-economy` and `ghost-mesh` packages described in RFCs do not exist yet.

## ghostrouter (Implemented)

### Package: `go/ghostrouter/`

Spray-and-Wait opportunistic routing with BoltDB persistence, compiled via gomobile into `ghostrouter.aar`.

### Exported Types

```go
// Router — main entry point, instantiated once per app lifetime
type Router struct { /* unexported fields */ }

// DeliverHandler — Kotlin implements this to receive delivered messages
type DeliverHandler interface {
    OnDeliver(senderId []byte)
}

// SendResult — wraps multi-return for gomobile compatibility
type SendResult struct {
    IsDirect bool    // true if sent directly, false if queued for spray
    Blob     []byte  // routed blob to send via BLE (nil if queued)
}

// BlobList — wraps [][]byte for gomobile (slices of slices not supported)
type BlobList struct { /* unexported */ }
func (b *BlobList) Size() int
func (b *BlobList) Get(i int) []byte
```

### Exported Functions

```go
// NewRouter creates a router with local identity and BoltDB path
// CRITICAL: localID is copy()'d internally — gomobile JNI memory freed after call
func NewRouter(localID []byte, dbPath string, handler DeliverHandler) (*Router, error)

// SendMessage routes a message to destination
// Returns (isDirect, routedBlob) via SendResult
// If direct: blob is ready to send via BLE
// If queued: blob is nil, message stored in BoltDB with copies=4
// Throws error if send fails
func (r *Router) SendMessage(dst []byte, payload []byte) (*SendResult, error)

// OnPeerDiscovered notifies router that a peer is in BLE range
// If multiple messages are queued, batches them into a single blob
// Returns BlobList of messages to send (direct deliveries + spray copies)
func (r *Router) OnPeerDiscovered(peerID []byte, rssi int) *BlobList

// OnMessageReceived processes incoming routed data
// Returns: "delivered", "forwarded", "dropped: <reason>", or "error: <details>"
// Evaluates relayWillingness gate before saving forwarded messages.
func (r *Router) OnMessageReceived(data []byte) string

// SetRelayWillingness sets the relay willingness (0.0 to 1.0)
// At 0, forwarded messages are dropped (leaf node / low battery).
// At 1.0, all forwarded messages are accepted for relay.
func (r *Router) SetRelayWillingness(w float32)

// GetRelayWillingness returns current relay willingness
func (r *Router) GetRelayWillingness() float32

// GetStats returns JSON-encoded router statistics (includes relayWillingness)
func (r *Router) GetStats() string

// Stop closes BoltDB and stops background goroutines
func (r *Router) Stop()

// EncodeBatch packs multiple encoded messages into a single blob
func EncodeBatch(encodedMessages [][]byte) ([]byte, error)

// DecodeBatch unpacks a batched blob into individual encoded messages
func DecodeBatch(data []byte) ([][]byte, error)

// ShortHex returns first 8 hex characters for safe logging
func ShortHex(data []byte) string
```

### Wire Formats

#### Single Message Wire Format
```
[4 bytes: header length N, big-endian uint32]
[N bytes: JSON-encoded RoutingHeader]
[remaining bytes: encrypted payload (opaque to router)]
```

#### Batch Wire Format (v0.2.0)
```
[1 byte: count N (1-255)]
[4 bytes: length of msg 1, big-endian uint32]
[msg1 bytes]
[4 bytes: length of msg 2, big-endian uint32]
[msg2 bytes]
...
```

### RoutingHeader (JSON)

```json
{
  "MessageID": "uuid-v4",
  "Src": "<base64 SHA-256(ed25519_pub)>",
  "Dst": "<base64 SHA-256(ed25519_pub)>",
  "CopiesRemaining": 4,
  "TTLSeconds": 86400,
  "HopCount": 0,
  "MaxHops": 10,
  "CreatedAt": 1725235200
}
```

### Internal Types (unexported)

```go
type Message struct {
    Header    RoutingHeader
    Payload   []byte
    StoredAt  int64
}

type PeerInfo struct {
    ID             []byte
    LastSeen       int64
    LastRSSI       int
    EncounterCount int
}
```

### Concurrency Model
- Background janitor goroutine runs every 60s: deletes expired messages (TTL > 24h), prunes BoltDB if > 50MB, removes stale peers (> 7 days)
- All store operations are serialized through BoltDB's transaction model
- No global state — `Router` is instantiated with explicit dependencies

### Algorithm: Binary Spray-and-Wait

1. **Send:** If destination peer seen < 60s ago → direct send. Otherwise → store with `copies = 4`
2. **Spray:** When a relay peer is discovered, split copies: give `floor(copies/2)` to relay, keep `ceil(copies/2)`
3. **Wait:** When only 1 copy remains, hold until destination is encountered directly
4. **Deliver:** When destination peer is discovered, deliver all queued messages for that peer
5. **Dedup:** Messages deduplicated by MessageID (seen set in BoltDB)
6. **Expiry:** Messages expire after 24h (TTL), max 10 hops

### gomobile Constraints
- `[][]byte` → wrapped in `BlobList` with `Size()/Get()` methods
- `func` callbacks → replaced with `DeliverHandler` Go interface
- Multi-return values → wrapped in `SendResult` struct
- Go `int` maps to Java `long` in `OnPeerDiscovered`
- All `[]byte` params must be `copy()`'d before storing (JNI memory freed after call)

---

## ghost-economy (NOT IMPLEMENTED)

Planned for v1.0. See `docs/rfc/rfc-006-economy.md` for design spec.

## ghost-mesh (NOT IMPLEMENTED)

Planned for v1.0. See `docs/rfc/rfc-004-routing.md` for advanced routing design spec.
