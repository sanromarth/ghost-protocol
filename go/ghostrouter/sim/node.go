package sim

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"ghostrouter"
)

// DeliveredRecord stores metadata for a message delivered to this node's application.
type DeliveredRecord struct {
	Src       []byte
	Payload   []byte
	DeliverAt time.Time
}

// SimNode represents a simulated GHOST network node.
// It exercises the production ghostrouter.Router directly.
type SimNode struct {
	mu sync.Mutex

	Name           string
	ID             []byte
	Router         *ghostrouter.Router
	DBPath         string
	Clock          *SimClock
	Trace          *TraceLogger
	BatteryPercent int
	IsAlive        bool

	// Metrics and state
	DeliveredMessages []*DeliveredRecord
	PacketsForwarded  int
	PacketsReceived   int
	DuplicatesDropped int
}

// nodeDeliverHandler captures application-layer deliveries.
type nodeDeliverHandler struct {
	node *SimNode
}

func (h *nodeDeliverHandler) OnDeliver(src []byte, payload []byte) {
	h.node.mu.Lock()
	defer h.node.mu.Unlock()

	rec := &DeliveredRecord{
		Src:       src,
		Payload:   payload,
		DeliverAt: h.node.Clock.Now(),
	}
	h.node.DeliveredMessages = append(h.node.DeliveredMessages, rec)

	if h.node.Trace != nil {
		h.node.Trace.Log(TraceEntry{
			Type:    TraceDelivered,
			Source:  fmt.Sprintf("%x", src[:4]),
			Dest:    h.node.Name,
			Details: fmt.Sprintf("payload_len=%d", len(payload)),
		})
	}
}

// BatteryToRelayWillingness implements the production GHOST PowerPolicyEngine
// mapping from battery percentage to relay willingness float:
// - < 20%: 0.0 (CRITICAL mode — own messages only, no relaying)
// - 20..30%: 0.3 (ECO low)
// - 31..60%: 0.6 (ECO mid)
// - > 60%: 1.0 (ACTIVE / ECO high)
func BatteryToRelayWillingness(batteryPercent int) float32 {
	switch {
	case batteryPercent < 20:
		return 0.0
	case batteryPercent <= 30:
		return 0.3
	case batteryPercent <= 60:
		return 0.6
	default:
		return 1.0
	}
}

// GenerateNodeID deterministically derives a 32-byte cryptographic ID from a seed and name.
func GenerateNodeID(seed int64, name string) []byte {
	h := sha256.Sum256([]byte(fmt.Sprintf("ghost-virtual-node-seed-%d-name-%s", seed, name)))
	id := make([]byte, 32)
	copy(id, h[:])
	return id
}

// NewSimNode creates and starts a virtual node running the production Router.
func NewSimNode(name string, seed int64, clock *SimClock, trace *TraceLogger, baseTempDir string) (*SimNode, error) {
	nodeID := GenerateNodeID(seed, name)
	dbPath := filepath.Join(baseTempDir, fmt.Sprintf("sim_node_%s_%d.db", name, seed))

	r, err := ghostrouter.NewRouter(nodeID, dbPath)
	if err != nil {
		return nil, fmt.Errorf("NewRouter failed for node %s: %w", name, err)
	}

	r.SetTimeProvider(clock.Now)

	node := &SimNode{
		Name:              name,
		ID:                nodeID,
		Router:            r,
		DBPath:            dbPath,
		Clock:             clock,
		Trace:             trace,
		BatteryPercent:    100,
		IsAlive:           true,
		DeliveredMessages: make([]*DeliveredRecord, 0),
	}

	handler := &nodeDeliverHandler{node: node}
	r.SetHandler(handler)
	r.SetRelayWillingness(BatteryToRelayWillingness(100))
	r.Start()

	if trace != nil {
		trace.Log(TraceEntry{
			Type:    TraceNodeCreated,
			Source:  name,
			Details: fmt.Sprintf("id=%x battery=100%%", nodeID[:4]),
		})
	}

	return node, nil
}

// SetBattery updates battery percentage and adapts relay willingness per production rules.
func (n *SimNode) SetBattery(percent int) {
	n.mu.Lock()
	defer n.mu.Unlock()

	if percent < 0 {
		percent = 0
	} else if percent > 100 {
		percent = 100
	}
	n.BatteryPercent = percent
	willingness := BatteryToRelayWillingness(percent)
	if n.Router != nil && n.IsAlive {
		n.Router.SetRelayWillingness(willingness)
	}

	if n.Trace != nil {
		n.Trace.Log(TraceEntry{
			Type:    TraceBatteryChanged,
			Source:  n.Name,
			Details: fmt.Sprintf("battery=%d%% willingness=%.2f", percent, willingness),
		})
	}
}

// DrainBattery decrements battery level by the given percentage.
func (n *SimNode) DrainBattery(percent int) {
	n.SetBattery(n.BatteryPercent - percent)
}

// SetRelayWillingness manually overrides the relay willingness on the router.
func (n *SimNode) SetRelayWillingness(w float32) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.Router != nil && n.IsAlive {
		n.Router.SetRelayWillingness(w)
	}
}

// Crash simulates sudden node failure (e.g. power cutoff, OS kill).
// The router is stopped, but the BoltDB persistence file is preserved.
func (n *SimNode) Crash() {
	n.mu.Lock()
	defer n.mu.Unlock()

	if !n.IsAlive {
		return
	}

	n.IsAlive = false
	if n.Router != nil {
		n.Router.Stop()
		n.Router = nil
	}

	if n.Trace != nil {
		n.Trace.Log(TraceEntry{
			Type:   TraceCrash,
			Source: n.Name,
		})
	}
}

// Restart simulates node reboot, recovering persistent routing state from BoltDB.
func (n *SimNode) Restart() error {
	n.mu.Lock()
	defer n.mu.Unlock()

	if n.IsAlive {
		return nil
	}

	r, err := ghostrouter.NewRouter(n.ID, n.DBPath)
	if err != nil {
		return fmt.Errorf("node %s restart failed: %w", n.Name, err)
	}

	r.SetTimeProvider(n.Clock.Now)
	handler := &nodeDeliverHandler{node: n}
	r.SetHandler(handler)
	r.SetRelayWillingness(BatteryToRelayWillingness(n.BatteryPercent))
	r.Start()

	n.Router = r
	n.IsAlive = true

	if n.Trace != nil {
		n.Trace.Log(TraceEntry{
			Type:    TraceRestart,
			Source:  n.Name,
			Details: fmt.Sprintf("recovered_msgs=%d", r.MessageCount()),
		})
	}

	return nil
}

// DeliveredCount returns the total number of messages delivered to this node.
func (n *SimNode) DeliveredCount() int {
	n.mu.Lock()
	defer n.mu.Unlock()
	return len(n.DeliveredMessages)
}

// Stop stops the router and marks the node as inactive.
func (n *SimNode) Stop() {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.Router != nil && n.IsAlive {
		n.Router.Stop()
		n.Router = nil
	}
	n.IsAlive = false
}

// Close cleans up database files and stops router.
func (n *SimNode) Close() {
	n.Stop()
	if n.DBPath != "" {
		_ = os.Remove(n.DBPath)
	}
}
