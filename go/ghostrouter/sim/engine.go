package sim

import (
	"bytes"
	"container/heap"
	"fmt"
	"os"
	"sync"
	"time"

	"ghostrouter"
)

// MsgMeta tracks lifecycle metadata for a message injected into the virtual mesh.
type MsgMeta struct {
	ID        []byte
	IDHex     string
	SrcName   string
	DstName   string
	DstID     []byte
	Payload   []byte
	CreatedAt time.Time
	Delivered bool
	DeliverAt time.Time
	MaxHop    int
}

// SimEngine is the discrete-event simulation engine for GHOST virtual mesh.
// It orchestrates virtual nodes, deterministic radio links, event queues,
// invariants checking, and metrics collection.
type SimEngine struct {
	mu sync.Mutex

	Seed    int64
	Clock   *SimClock
	Radio   *VirtualRadio
	Trace   *TraceLogger
	TempDir string

	Nodes   map[string]*SimNode
	nodeIDs map[string]string // hex(ID) -> Name
	msgs    map[string]*MsgMeta

	// Event priority queue
	pq eventQueue

	// Metrics
	latencies         *LatencyCollector
	messagesCreated   int
	messagesDelivered int
	messagesExpired   int
	messagesFailed    int
	packetsForwarded  int
	packetsDropped    int
	duplicatesDropped int
	maxObservedHops   int
	maxTransitStorage int

	// Invariant verification tracking
	RelayGatingViolations []RelayGatingViolation
}

// RelayGatingViolation records an illegal transit relay attempt while willingness <= 0.
type RelayGatingViolation struct {
	Node        string
	MessageID   string
	Action      string // "spray transit message" or "accept transit message"
	Battery     int
	Willingness float32
}

// NewSimEngine initializes a deterministic simulation engine.
func NewSimEngine(seed int64, verbose bool) (*SimEngine, error) {
	tempDir, err := os.MkdirTemp("", fmt.Sprintf("ghost_sim_%d_*", seed))
	if err != nil {
		return nil, fmt.Errorf("failed to create sim temp dir: %w", err)
	}

	clock := NewSimClock()
	trace := NewTraceLogger(clock, os.Stdout, verbose)
	radio := NewVirtualRadio(seed, trace)

	engine := &SimEngine{
		Seed:      seed,
		Clock:     clock,
		Radio:     radio,
		Trace:     trace,
		TempDir:   tempDir,
		Nodes:     make(map[string]*SimNode),
		nodeIDs:   make(map[string]string),
		msgs:      make(map[string]*MsgMeta),
		latencies: &LatencyCollector{},
	}
	heap.Init(&engine.pq)

	return engine, nil
}

// AddNode creates and adds a new virtual node to the simulation.
func (e *SimEngine) AddNode(name string) (*SimNode, error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if _, exists := e.Nodes[name]; exists {
		return nil, fmt.Errorf("node %s already exists", name)
	}

	node, err := NewSimNode(name, e.Seed, e.Clock, e.Trace, e.TempDir)
	if err != nil {
		return nil, err
	}

	e.Nodes[name] = node
	e.nodeIDs[fmt.Sprintf("%x", node.ID)] = name
	return node, nil
}

// GetNode retrieves a virtual node by name.
func (e *SimEngine) GetNode(name string) *SimNode {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.Nodes[name]
}

// Connect establishes a bidirectional link between two nodes.
func (e *SimEngine) Connect(a, b string, rssi int) {
	e.Radio.Connect(a, b, rssi)
}

// Disconnect removes the link between two nodes.
func (e *SimEngine) Disconnect(a, b string) {
	e.Radio.Disconnect(a, b)
}

// SendMessage injects a new message from srcNode to dstNode.
func (e *SimEngine) SendMessage(srcName, dstName string, payload []byte) ([]byte, error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	src := e.Nodes[srcName]
	dst := e.Nodes[dstName]
	if src == nil || !src.IsAlive {
		return nil, fmt.Errorf("source node %s not found or inactive", srcName)
	}
	if dst == nil {
		return nil, fmt.Errorf("destination node %s not found", dstName)
	}

	res := src.Router.SendMessage(dst.ID, payload)
	if res.Status != "queued" && res.Status != "direct" {
		return nil, fmt.Errorf("SendMessage returned status: %s", res.Status)
	}

	msgID := res.MessageID
	if len(msgID) == 0 {
		return nil, fmt.Errorf("failed to retrieve generated message ID from SendMessage")
	}

	msgIDHex := fmt.Sprintf("%x", msgID)
	meta := &MsgMeta{
		ID:        msgID,
		IDHex:     msgIDHex,
		SrcName:   srcName,
		DstName:   dstName,
		DstID:     dst.ID,
		Payload:   payload,
		CreatedAt: e.Clock.Now(),
		MaxHop:    0,
	}
	e.msgs[msgIDHex] = meta
	e.messagesCreated++

	shortID := msgIDHex
	if len(shortID) > 8 {
		shortID = shortID[:8]
	}

	if e.Trace != nil {
		e.Trace.Log(TraceEntry{
			Type:      TraceMessageCreated,
			MessageID: shortID,
			Source:    srcName,
			Dest:      dstName,
			Details:   fmt.Sprintf("payload_len=%d", len(payload)),
		})
	}

	return msgID, nil
}

// Exchange executes a contact encounter between two connected nodes.
// Messages are discovered, evaluated for radio loss, and processed by the recipient.
func (e *SimEngine) Exchange(aName, bName string) (delivered int, forwarded int, dropped int, err error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	nodeA := e.Nodes[aName]
	nodeB := e.Nodes[bName]

	if nodeA == nil || nodeB == nil {
		return 0, 0, 0, fmt.Errorf("invalid nodes: %s, %s", aName, bName)
	}
	if !nodeA.IsAlive || !nodeB.IsAlive {
		return 0, 0, 0, nil // Dead nodes cannot communicate
	}

	connected, rssi := e.Radio.IsConnected(aName, bName)
	if !connected {
		return 0, 0, 0, nil // Out of contact
	}

	// Step 1: A -> B
	d1, f1, dr1 := e.transferOneWay(nodeA, nodeB, rssi)

	// Step 2: B -> A
	d2, f2, dr2 := e.transferOneWay(nodeB, nodeA, rssi)

	return d1 + d2, f1 + f2, dr1 + dr2, nil
}

func (e *SimEngine) transferOneWay(src, dst *SimNode, rssi int) (delivered int, forwarded int, dropped int) {
	blobs := src.Router.OnPeerDiscovered(dst.ID, rssi)
	if blobs == nil || blobs.Size() == 0 {
		return 0, 0, 0
	}

	for i := 0; i < blobs.Size(); i++ {
		blob := blobs.Get(i)
		if len(blob) == 0 {
			continue
		}

		// Check if blob is a batch
		batchMsgs, err := ghostrouter.DecodeBatch(blob)
		var msgsToProcess [][]byte
		if err == nil && len(batchMsgs) > 0 {
			msgsToProcess = batchMsgs
		} else {
			msgsToProcess = [][]byte{blob}
		}

		for _, rawMsg := range msgsToProcess {
			// Check simulated radio channel loss
			if e.Radio.ShouldDrop(src.Name, dst.Name) {
				dropped++
				e.packetsDropped++
				if e.Trace != nil {
					e.Trace.Log(TraceEntry{
						Type:   TraceDropped,
						Source: src.Name,
						Dest:   dst.Name,
						Reason: "radio packet loss",
					})
				}
				continue
			}

			// Inspect message header for metrics before delivery
			var msgIDHex string
			decoded, err := ghostrouter.DecodeMessage(rawMsg)
			if err == nil && decoded.Header != nil {
				msgIDHex = fmt.Sprintf("%x", decoded.Header.MessageID)
				if decoded.Header.HopCount > e.maxObservedHops {
					e.maxObservedHops = decoded.Header.HopCount
				}
				if meta, ok := e.msgs[msgIDHex]; ok {
					if decoded.Header.HopCount > meta.MaxHop {
						meta.MaxHop = decoded.Header.HopCount
					}
				}

				// Check I7 Relay Gating: Low battery / critical nodes must NEVER spray transit messages
				isTransitForSrc := !bytes.Equal(decoded.Header.Src, src.ID)
				if isTransitForSrc && (src.BatteryPercent < 20 || (src.Router != nil && src.Router.GetRelayWillingness() <= 0)) {
					willingness := float32(0.0)
					if src.Router != nil {
						willingness = src.Router.GetRelayWillingness()
					}
					e.RecordRelayGatingViolation(src.Name, msgIDHex, "spray transit message", src.BatteryPercent, willingness)
				}
			}

			shortID := msgIDHex
			if len(shortID) > 8 {
				shortID = shortID[:8]
			}

			status := dst.Router.OnMessageReceived(rawMsg)
			switch {
			case status == "delivered":
				delivered++
				e.messagesDelivered++
				if meta, ok := e.msgs[msgIDHex]; ok && !meta.Delivered {
					meta.Delivered = true
					meta.DeliverAt = e.Clock.Now()
					latency := meta.DeliverAt.Sub(meta.CreatedAt)
					e.latencies.Add(latency)
				}
				// BLE GATT write confirmation: sender clears delivered status in local store
				if decoded != nil && decoded.Header != nil {
					src.Router.MarkDelivered(decoded.Header.MessageID)
				}

			case status == "forwarded":
				forwarded++
				e.packetsForwarded++
				src.PacketsForwarded++
				dst.PacketsReceived++

				// Check I7 Relay Gating: Low battery / critical nodes must NEVER accept transit messages
				if decoded != nil && decoded.Header != nil {
					isTransitForDst := !bytes.Equal(decoded.Header.Src, dst.ID) && !bytes.Equal(decoded.Header.Dst, dst.ID)
					if isTransitForDst && (dst.BatteryPercent < 20 || (dst.Router != nil && dst.Router.GetRelayWillingness() <= 0)) {
						willingness := float32(0.0)
						if dst.Router != nil {
							willingness = dst.Router.GetRelayWillingness()
						}
						e.RecordRelayGatingViolation(dst.Name, msgIDHex, "accept transit message", dst.BatteryPercent, willingness)
					}
				}
				if decoded != nil && decoded.Header != nil && e.Trace != nil {
					e.Trace.Log(TraceEntry{
						Type:      TraceForward,
						MessageID: shortID,
						Source:    src.Name,
						Carrier:   dst.Name,
						HopCount:  decoded.Header.HopCount,
						Copies:    decoded.Header.CopiesRemaining,
					})
				}

			case status == "dropped: duplicate":
				dropped++
				e.duplicatesDropped++
				dst.DuplicatesDropped++
				if e.Trace != nil {
					e.Trace.Log(TraceEntry{
						Type:      TraceDropped,
						MessageID: shortID,
						Source:    dst.Name,
						Reason:    "duplicate",
					})
				}

			case status == "dropped: TTL expired":
				dropped++
				e.messagesExpired++
				if e.Trace != nil {
					e.Trace.Log(TraceEntry{
						Type:      TraceDropped,
						MessageID: shortID,
						Source:    dst.Name,
						Reason:    "TTL expired",
					})
				}

			default:
				// Other drops (hop limit, low battery, no copies, etc.)
				dropped++
				if e.Trace != nil {
					e.Trace.Log(TraceEntry{
						Type:      TraceDropped,
						MessageID: shortID,
						Source:    dst.Name,
						Reason:    status,
					})
				}
			}
		}
	}

	// Update max transit storage metric across all nodes
	count := dst.Router.MessageCount()
	if count > e.maxTransitStorage {
		e.maxTransitStorage = count
	}

	return delivered, forwarded, dropped
}

// Advance moves virtual time forward by duration d, running periodic janitors.
func (e *SimEngine) Advance(d time.Duration) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.Clock.Advance(d)

	// Run janitor on all live nodes to prune expired messages
	for _, node := range e.Nodes {
		if node.IsAlive && node.Router != nil {
			pruned, _ := node.Router.RunJanitor()
			if pruned > 0 && e.Trace != nil {
				e.Trace.Log(TraceEntry{
					Type:   TraceTTLPruned,
					Source: node.Name,
					Copies: pruned,
				})
			}
		}
	}
}

// ExchangeAllActive executes encounters between all currently connected node pairs.
func (e *SimEngine) ExchangeAllActive() (totalDelivered, totalForwarded, totalDropped int) {
	links := e.Radio.GetAllLinks()
	for _, link := range links {
		d, f, dr, _ := e.Exchange(link[0], link[1])
		totalDelivered += d
		totalForwarded += f
		totalDropped += dr
	}
	return
}

// CheckInvariants verifies the 10 protocol correctness invariants defined in Section 23:
// 1. Identity stability (IDs never mutate).
// 2. Message integrity (Message IDs match hash / don't mutate).
// 3. TTL (no expired message forwarded; janitor removes expired).
// 4. Hop count (no packet exceeding MaxHops=10).
// 5. Deduplication (no duplicate logical delivers on destination).
// 6. Copy count (binary split conservation: total copies in network <= initial L=4).
// 7. Relay policy (nodes with willingness=0.0 do not store relay copies).
// 8. Crash state (crashed nodes do not participate).
// 9. Transit storage bounds (MessageCount <= 500 cap).
// 10. Delivery correctness (delivered to correct destination).
func (e *SimEngine) CheckInvariants() []error {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.checkInvariantsLocked()
}

func (e *SimEngine) checkInvariantsLocked() []error {
	var errors []error

	nowUnix := e.Clock.NowUnix()

	for name, node := range e.Nodes {
		// 1. Identity stability
		expectedID := GenerateNodeID(e.Seed, name)
		if !bytes.Equal(node.ID, expectedID) {
			errors = append(errors, fmt.Errorf("invariant 1 (Identity): node %s ID mutated", name))
		}

		if !node.IsAlive {
			// 8. Crash state
			if node.Router != nil {
				errors = append(errors, fmt.Errorf("invariant 8 (Crash): crashed node %s still has active router", name))
			}
			continue
		}

		// 5. Deduplication
		seenDelivered := make(map[string]bool)
		for _, rec := range node.DeliveredMessages {
			key := string(rec.Payload)
			if seenDelivered[key] {
				errors = append(errors, fmt.Errorf("invariant 5 (Deduplication): node %s received duplicate delivery of payload '%s'", name, key))
			}
			seenDelivered[key] = true

			// 10. Delivery correctness
			// The node receiving the deliver callback must be the actual target intended by the sender
			matchedTarget := false
			for _, meta := range e.msgs {
				if bytes.Equal(meta.Payload, rec.Payload) {
					if meta.DstName == name {
						matchedTarget = true
					}
					break
				}
			}
			if !matchedTarget {
				errors = append(errors, fmt.Errorf("invariant 10 (Delivery): message delivered to wrong node %s", name))
			}
		}

		// Check stored messages
		store := node.Router.GetStore()
		if store != nil {
			msgs, _ := store.GetPendingMessages()

			// 9. Transit storage bounds
			if len(msgs) > 500 {
				errors = append(errors, fmt.Errorf("invariant 9 (Storage): node %s exceeded 500 transit message limit (has %d)", name, len(msgs)))
			}

			for _, m := range msgs {
				// 2. Message integrity
				if len(m.ID) == 0 || len(m.Src) == 0 || len(m.Dst) == 0 {
					errors = append(errors, fmt.Errorf("invariant 2 (Integrity): corrupt message fields in node %s", name))
				}

				// 3. TTL
				if m.CreatedAt+m.TTLSeconds < nowUnix {
					errors = append(errors, fmt.Errorf("invariant 3 (TTL): node %s has unpruned expired message %x (created %d, ttl %d, now %d)",
						name, m.ID[:4], m.CreatedAt, m.TTLSeconds, nowUnix))
				}

				// 4. Hop count
				if m.HopCount > ghostrouter.MaxHops {
					errors = append(errors, fmt.Errorf("invariant 4 (HopCount): node %s has message %x exceeding MaxHops (%d > %d)",
						name, m.ID[:4], m.HopCount, ghostrouter.MaxHops))
				}

				// 7. Relay policy
				if node.BatteryPercent < 20 && !bytes.Equal(m.Src, node.ID) && !bytes.Equal(m.Dst, node.ID) {
					errors = append(errors, fmt.Errorf("invariant 7 (RelayPolicy): low battery node %s stored transit relay message %x", name, m.ID[:4]))
				}
			}
		}
	}

	// 6. Copy count conservation
	for idHex, meta := range e.msgs {
		totalCopies := 0
		for _, node := range e.Nodes {
			if node.IsAlive && node.Router != nil {
				m, _ := node.Router.GetStore().GetMessage(meta.ID)
				if m != nil && m.Status != ghostrouter.StatusDelivered {
					totalCopies += m.CopiesRemaining
				}
			}
		}
		if totalCopies > ghostrouter.SprayCopies {
			errors = append(errors, fmt.Errorf("invariant 6 (CopyCount): message %s has %d copies in circulation (max initial %d)",
				idHex[:8], totalCopies, ghostrouter.SprayCopies))
		}
	}

	return errors
}

// Results compiles the complete machine-readable simulation summary.
func (e *SimEngine) Results(scenarioName string) *SimResults {
	e.mu.Lock()
	defer e.mu.Unlock()

	avg, p50, p95, p99 := e.latencies.Compute()

	invErrors := e.checkInvariantsLocked()
	var errStrs []string
	for _, err := range invErrors {
		errStrs = append(errStrs, err.Error())
	}

	simSec := e.Clock.Elapsed().Seconds()

	messagesPending := 0
	messagesFailed := 0
	for _, meta := range e.msgs {
		if !meta.Delivered {
			isPending := false
			for _, node := range e.Nodes {
				if node.IsAlive && node.Router != nil {
					m, _ := node.Router.GetStore().GetMessage(meta.ID)
					if m != nil && m.Status == ghostrouter.StatusPending {
						isPending = true
						break
					}
				}
			}
			if isPending {
				messagesPending++
			} else {
				messagesFailed++
			}
		}
	}

	return &SimResults{
		Scenario:          scenarioName,
		Seed:              e.Seed,
		Nodes:             len(e.Nodes),
		MessagesCreated:   e.messagesCreated,
		MessagesDelivered: e.messagesDelivered,
		MessagesPending:   messagesPending,
		MessagesExpired:   e.messagesExpired,
		MessagesFailed:    messagesFailed,
		PacketsForwarded:  e.packetsForwarded,
		PacketsDropped:    e.packetsDropped,
		DuplicatesDropped: e.duplicatesDropped,
		MaxHops:           e.maxObservedHops,
		MaxTransitStorage: e.maxTransitStorage,
		SimulatedTime:     e.Clock.Elapsed().String(),
		SimulatedSeconds:  simSec,
		AvgLatencyMs:      avg,
		P50LatencyMs:      p50,
		P95LatencyMs:      p95,
		P99LatencyMs:      p99,
		InvariantsPassed:  len(invErrors) == 0,
		InvariantErrors:   errStrs,
	}
}

// RecordRelayGatingViolation records an invariant violation where a node with willingness <= 0 relays transit traffic.
func (e *SimEngine) RecordRelayGatingViolation(nodeName, msgID, action string, battery int, willingness float32) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.RelayGatingViolations = append(e.RelayGatingViolations, RelayGatingViolation{
		Node:        nodeName,
		MessageID:   msgID,
		Action:      action,
		Battery:     battery,
		Willingness: willingness,
	})
}

// GetRelayGatingViolations returns a snapshot of recorded relay gating violations.
func (e *SimEngine) GetRelayGatingViolations() []RelayGatingViolation {
	e.mu.Lock()
	defer e.mu.Unlock()
	res := make([]RelayGatingViolation, len(e.RelayGatingViolations))
	copy(res, e.RelayGatingViolations)
	return res
}

// Close gracefully stops all nodes and removes temporary files.
func (e *SimEngine) Close() {
	e.mu.Lock()
	defer e.mu.Unlock()

	for _, node := range e.Nodes {
		node.Close()
	}
	if e.TempDir != "" {
		_ = os.RemoveAll(e.TempDir)
	}
}

// Internal event priority queue types
type simEvent struct {
	timestamp time.Time
	index     int
	action    func() error
}

type eventQueue []*simEvent

func (pq eventQueue) Len() int           { return len(pq) }
func (pq eventQueue) Less(i, j int) bool { return pq[i].timestamp.Before(pq[j].timestamp) }
func (pq eventQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
	pq[i].index = i
	pq[j].index = j
}
func (pq *eventQueue) Push(x interface{}) {
	n := len(*pq)
	item := x.(*simEvent)
	item.index = n
	*pq = append(*pq, item)
}
func (pq *eventQueue) Pop() interface{} {
	old := *pq
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	item.index = -1
	*pq = old[0 : n-1]
	return item
}
