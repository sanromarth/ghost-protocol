package ghostrouter

// Status constants for message lifecycle.
const (
	StatusPending   = 0
	StatusSprayed   = 1
	StatusDelivered = 2
	StatusExpired   = 3
	StatusFailed    = 4
)

// SprayCopies is the initial number of copies for spray-and-wait.
const SprayCopies = 4

// DefaultTTLSeconds is the default time-to-live for messages (24 hours).
const DefaultTTLSeconds int64 = 86400

// MaxHops is the maximum number of hops a message can traverse.
const MaxHops = 10

// MaxDBSize is the soft limit for the BoltDB file (50 MB).
const MaxDBSize int64 = 50 * 1024 * 1024

// PruneTarget is the target size after pruning oldest messages.
const PruneTarget int64 = 40 * 1024 * 1024

// Message is the routing-layer envelope wrapping the encrypted payload from Layer 3.
type Message struct {
	ID              []byte // 32 bytes, SHA-256 of (src + dst + payload + createdAt)
	Src             []byte // 32 bytes, sender Ed25519 pubkey hash
	Dst             []byte // 32 bytes, recipient Ed25519 pubkey hash
	Payload         []byte // encrypted blob from Rust
	CopiesRemaining int
	TTLSeconds      int64
	HopCount        int
	CreatedAt       int64 // Unix seconds
	Status          int
}

// RoutingHeader is sent in cleartext with every forwarded copy.
type RoutingHeader struct {
	MessageID       []byte `json:"MessageID"`
	Src             []byte `json:"Src"`
	Dst             []byte `json:"Dst"`
	CopiesRemaining int    `json:"CopiesRemaining"`
	TTLSeconds      int64  `json:"TTLSeconds"`
	HopCount        int    `json:"HopCount"`
	CreatedAt       int64  `json:"CreatedAt"`
}

// PeerInfo tracks a mesh node we've encountered.
type PeerInfo struct {
	ID             []byte `json:"ID"` // 32 bytes, Ed25519 pubkey hash
	LastSeen       int64  `json:"LastSeen"` // Unix millis
	LastRSSI       int    `json:"LastRSSI"` // dBm
	EncounterCount int    `json:"EncounterCount"`
}
