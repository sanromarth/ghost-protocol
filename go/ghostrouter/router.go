package ghostrouter

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"
)

// shortHex returns a safe hex prefix of a byte slice for logging.
// Prevents panic on slices shorter than 4 bytes.
func shortHex(b []byte) []byte {
	if len(b) < 4 {
		return b
	}
	return b[:4]
}

// BlobList is a gomobile-compatible wrapper for [][]byte.
// gomobile can't export [][]byte, so we use a helper struct.
type BlobList struct {
	blobs [][]byte
}

// Size returns the number of blobs.
func (bl *BlobList) Size() int {
	if bl == nil {
		return 0
	}
	return len(bl.blobs)
}

// Get returns the blob at index i.
func (bl *BlobList) Get(i int) []byte {
	if bl == nil || i < 0 || i >= len(bl.blobs) {
		return nil
	}
	return bl.blobs[i]
}

// SendResult holds the result of SendMessage for gomobile compatibility.
type SendResult struct {
	Blob      []byte // encoded message to send, or nil if queued
	Status    string // "direct" or "queued"
	MessageID []byte // generated message ID
}

// DeliverHandler is the gomobile-compatible callback interface.
// Kotlin implements this to receive messages destined for this device.
type DeliverHandler interface {
	OnDeliver(dst []byte, payload []byte)
}

// Router is the gomobile-exported API for spray-and-wait mesh routing.
type Router struct {
	store   *MessageStore
	localID []byte

	handler DeliverHandler

	// In-memory bounded dedup for delivered messages (prevents BLE GATT retry duplicates)
	deliveredDedup *DedupCache

	// relayWillingness controls whether this node accepts forwarded messages
	// for relay. 0.0 = drop all forwarded messages (leaf node / low battery),
	// 1.0 = accept all (full relay participation). Set from Kotlin via
	// SetRelayWillingness(). This is a policy gate, not an algorithm change —
	// the Spray-and-Wait binary split logic remains untouched.
	relayWillingness float32

	// deliveryAttempts tracks how many times a message has been queued for direct delivery
	deliveryAttempts map[string]int

	timeNow func() time.Time

	// onAfterPersistDeliverHook is a test seam to inject crashes immediately after
	// durable persistence of delivered state but before invoking the application callback.
	onAfterPersistDeliverHook func(msgID []byte)

	stopCh   chan struct{}
	stopOnce sync.Once
	wg       sync.WaitGroup
	mu       sync.Mutex
}

// SetAfterPersistDeliverHook installs a test-only callback called immediately after persisting delivery.
func (r *Router) SetAfterPersistDeliverHook(fn func(msgID []byte)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.onAfterPersistDeliverHook = fn
}

// SetTimeProvider overrides wall-clock time for deterministic testing and simulation.
func (r *Router) SetTimeProvider(fn func() time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.timeNow = fn
	if r.store != nil {
		r.store.SetTimeProvider(fn)
	}
}

func (r *Router) now() time.Time {
	if r.timeNow != nil {
		return r.timeNow()
	}
	return time.Now()
}

// RunJanitor manually triggers message expiration and storage pruning.
func (r *Router) RunJanitor() (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	deleted, err := r.store.DeleteExpired(r.now().Unix())
	if err != nil {
		return 0, err
	}
	if err := r.store.PruneIfNeeded(); err != nil {
		return deleted, err
	}
	return deleted, nil
}

// NewRouter creates a router. Called once from Kotlin on app startup.
func NewRouter(localID []byte, dbPath string) (*Router, error) {
	store, err := OpenStore(dbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open store: %w", err)
	}

	// Copy localID — gomobile slices are backed by JNI memory
	// that gets freed after the call returns
	id := make([]byte, len(localID))
	copy(id, localID)

	store.SetLocalID(id)

	log.Printf("GHOST_ROUTE: NewRouter localID=%x (len=%d)", id[:min(8, len(id))], len(id))

	return &Router{
		store:            store,
		localID:          id,
		deliveredDedup:   NewDedupCache(2048, DefaultTTLSeconds),
		deliveryAttempts: make(map[string]int),
		relayWillingness: 1.0, // Default: full relay participation
		stopCh:           make(chan struct{}),
	}, nil
}

// SetHandler sets the Kotlin callback handler for messages destined for this device.
func (r *Router) SetHandler(h DeliverHandler) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.handler = h
}

// SetRelayWillingness sets the relay willingness (0.0 to 1.0).
// At 0, forwarded messages are dropped (leaf node / low battery).
// At 1.0, all forwarded messages are accepted for relay.
// This is a policy gate — the Spray-and-Wait binary split logic is untouched.
func (r *Router) SetRelayWillingness(w float32) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if w < 0 {
		w = 0
	}
	if w > 1 {
		w = 1
	}
	r.relayWillingness = w
	log.Printf("GHOST_ROUTE: relay willingness set to %.2f", w)
}

// GetRelayWillingness returns the current relay willingness value.
func (r *Router) GetRelayWillingness() float32 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.relayWillingness
}

// Start begins background goroutines for maintenance.
func (r *Router) Start() {
	// Expiry janitor: every 60 seconds
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				deleted, err := r.store.DeleteExpired(r.now().Unix())
				if err != nil {
					log.Printf("GHOST_ROUTE: expiry janitor error: %v", err)
				} else if deleted > 0 {
					log.Printf("GHOST_ROUTE: expired %d messages", deleted)
				}
				if err := r.store.PruneIfNeeded(); err != nil {
					log.Printf("GHOST_ROUTE: prune error: %v", err)
				}
			case <-r.stopCh:
				return
			}
		}
	}()

	// Peer stale cleaner: every 5 minutes
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				cutoff := r.now().Add(-24 * time.Hour).UnixMilli()
				deleted, err := r.store.DeleteStalePeers(cutoff)
				if err != nil {
					log.Printf("GHOST_ROUTE: stale peer cleaner error: %v", err)
				} else if deleted > 0 {
					log.Printf("GHOST_ROUTE: removed %d stale peers", deleted)
				}
			case <-r.stopCh:
				return
			}
		}
	}()

	log.Printf("GHOST_ROUTE: Router started, localID=%x", r.localID)
}

// Stop shuts down background goroutines and closes the store.
func (r *Router) Stop() {
	r.stopOnce.Do(func() {
		close(r.stopCh)
	})
	r.wg.Wait()
	if err := r.store.Close(); err != nil {
		log.Printf("GHOST_ROUTE: error closing store: %v", err)
	}
	log.Printf("GHOST_ROUTE: Router stopped")
}

// computeMessageID generates a unique ID from message fields + random nonce.
func computeMessageID(src, dst, payload []byte, createdAt int64) []byte {
	h := sha256.New()
	h.Write(src)
	h.Write(dst)
	h.Write(payload)
	ts := make([]byte, 8)
	binary.BigEndian.PutUint64(ts, uint64(createdAt))
	h.Write(ts)
	// Random nonce prevents collision when same message sent twice in 1 second
	nonce := make([]byte, 8)
	rand.Read(nonce)
	h.Write(nonce)
	return h.Sum(nil)
}

// SendMessage is called by Kotlin when the user taps "Send".
// Returns a SendResult with the encoded blob (or nil if queued).
func (r *Router) SendMessage(dst []byte, payload []byte) *SendResult {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Copy parameters — gomobile slices are backed by JNI memory
	dstCopy := make([]byte, len(dst))
	copy(dstCopy, dst)
	payloadCopy := make([]byte, len(payload))
	copy(payloadCopy, payload)

	now := r.now().Unix()
	msgID := computeMessageID(r.localID, dstCopy, payloadCopy, now)

	msg := &Message{
		ID:              msgID,
		Src:             r.localID,
		Dst:             dstCopy,
		Payload:         payloadCopy,
		CopiesRemaining: SprayCopies,
		TTLSeconds:      DefaultTTLSeconds,
		HopCount:        0,
		CreatedAt:       now,
		Status:          StatusPending,
	}

	if err := r.store.SaveMessage(msg); err != nil {
		log.Printf("GHOST_ROUTE: error saving message: %v", err)
		return &SendResult{Status: "error"}
	}

	// Check if destination peer was recently seen (within 60 seconds)
	peer, err := r.store.GetPeer(dst)
	if err == nil && peer != nil {
		lastSeenSecs := peer.LastSeen / 1000
		if now-lastSeenSecs < 60 {
			encoded := EncodeMessage(msg)
			// Mark as delivered so it's not re-sprayed
			r.store.UpdateMessageStatus(msg.ID, StatusDelivered)
			log.Printf("GHOST_ROUTE: SendMessage direct to %x (%d bytes)", shortHex(dst), len(encoded))
			return &SendResult{Blob: encoded, Status: "direct", MessageID: msgID}
		}
	}

	log.Printf("GHOST_ROUTE: SendMessage queued for spray to %x", shortHex(dst))
	return &SendResult{Status: "queued", MessageID: msgID}
}

// OnPeerDiscovered is called by Kotlin every time BLE discovers a peer.
// Returns a BlobList of encoded messages to send to this peer.
func (r *Router) OnPeerDiscovered(peerID []byte, rssi int) *BlobList {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Copy peerID — gomobile slices are backed by JNI memory
	pid := make([]byte, len(peerID))
	copy(pid, peerID)

	// 1. Save/update peer
	existingPeer, _ := r.store.GetPeer(pid)
	encounterCount := 1
	if existingPeer != nil {
		encounterCount = existingPeer.EncounterCount + 1
	}

	peer := &PeerInfo{
		ID:             pid,
		LastSeen:       r.now().UnixMilli(),
		LastRSSI:       rssi,
		EncounterCount: encounterCount,
	}
	if err := r.store.SavePeer(peer); err != nil {
		log.Printf("GHOST_ROUTE: error saving peer: %v", err)
	}

	var blobs [][]byte

	// 2. Direct deliveries: messages destined for this peer
	dstMsgs, err := r.store.GetMessagesForDst(pid)
	if err != nil {
		log.Printf("GHOST_ROUTE: error getting messages for dst: %v", err)
	}
	for _, msg := range dstMsgs {
		// When relay willingness is <= 0 (battery critical / leaf mode), do not deliver transit messages
		if r.relayWillingness <= 0 && !bytes.Equal(msg.Src, r.localID) {
			continue
		}

		msgKey := fmt.Sprintf("%x", msg.ID)
		r.deliveryAttempts[msgKey]++
		encoded := EncodeMessage(msg)
		blobs = append(blobs, encoded)
		if r.deliveryAttempts[msgKey] >= 3 {
			r.store.UpdateMessageStatus(msg.ID, StatusDelivered)
		}
		log.Printf("GHOST_ROUTE: delivering msg %x to final dst %x (%d bytes, attempt %d)",
			shortHex(msg.ID), shortHex(pid), len(encoded), r.deliveryAttempts[msgKey])
	}

	// 3. Spray copies for messages not destined for this peer
	pendingMsgs, err := r.store.GetPendingMessages()
	if err != nil {
		log.Printf("GHOST_ROUTE: error getting pending messages: %v", err)
	}
	for _, msg := range pendingMsgs {
		// Policy gate: critically depleted node (relay willingness <= 0) must never spray transit relay messages
		if r.relayWillingness <= 0 && !bytes.Equal(msg.Src, r.localID) {
			continue
		}

		if bytes.Equal(msg.Dst, pid) {
			continue
		}
		// Don't spray a message BACK to its sender — wasteful relay loop
		if bytes.Equal(msg.Src, pid) {
			continue
		}
		if msg.CopiesRemaining <= 1 || msg.HopCount >= MaxHops {
			continue
		}
		if msg.CreatedAt+msg.TTLSeconds < r.now().Unix() {
			continue
		}

		// Binary spray: give half the copies
		forwardMsg := &Message{
			ID:              msg.ID,
			Src:             msg.Src,
			Dst:             msg.Dst,
			Payload:         msg.Payload,
			CopiesRemaining: msg.CopiesRemaining / 2,
			TTLSeconds:      msg.TTLSeconds,
			HopCount:        msg.HopCount + 1,
			CreatedAt:       msg.CreatedAt,
			Status:          StatusSprayed,
		}

		encoded := EncodeMessage(forwardMsg)
		blobs = append(blobs, encoded)

		msg.CopiesRemaining = msg.CopiesRemaining - forwardMsg.CopiesRemaining
		msg.Status = StatusSprayed
		r.store.SaveMessage(msg)

		log.Printf("GHOST_ROUTE: sprayed msg %x to carrier %x (gave %d copies, kept %d, hop %d)",
			shortHex(msg.ID), shortHex(pid), forwardMsg.CopiesRemaining, msg.CopiesRemaining, forwardMsg.HopCount)
	}

	// Batch multiple messages into a single blob for efficient GATT transmission.
	// Single messages use the existing path (backward compatible).
	if len(blobs) > 1 {
		batched, err := EncodeBatch(blobs)
		if err != nil {
			log.Printf("GHOST_ROUTE: batch encoding failed (%d blobs): %v, falling back to first blob only", len(blobs), err)
			return &BlobList{blobs: blobs[:1]}
		}
		log.Printf("GHOST_ROUTE: batched %d messages into %d bytes for peer %x", len(blobs), len(batched), shortHex(pid))
		return &BlobList{blobs: [][]byte{batched}}
	}

	return &BlobList{blobs: blobs}
}

// OnMessageReceived is called by Kotlin when ANY BLE data arrives.
// Returns: "delivered", "forwarded", "dropped: <reason>", or "error: <details>".
func (r *Router) OnMessageReceived(data []byte) string {
	r.mu.Lock()
	defer r.mu.Unlock()

	// Copy data — gomobile slices are backed by JNI memory
	dataCopy := make([]byte, len(data))
	copy(dataCopy, data)

	result, err := decodeMessage(dataCopy)
	if err != nil {
		log.Printf("GHOST_ROUTE: failed to decode message: %v", err)
		return fmt.Sprintf("error: %v", err)
	}

	header := result.Header
	payload := result.Payload

	// Dedup check — must happen BEFORE deliver to prevent BLE GATT retry duplicates
	msgIDKey := fmt.Sprintf("%x", header.MessageID)
	nowUnix := r.now().Unix()
	if r.deliveredDedup.Seen(msgIDKey, nowUnix) {
		log.Printf("GHOST_ROUTE: dropping msg %x: already delivered (dedup)", shortHex(header.MessageID))
		return "dropped: duplicate"
	}
	existing, _ := r.store.GetMessage(header.MessageID)
	if existing != nil {
		log.Printf("GHOST_ROUTE: dropping msg %x: duplicate in store", shortHex(header.MessageID))
		return "dropped: duplicate"
	}

	// Is this message for us?
	if bytes.Equal(header.Dst, r.localID) {
		log.Printf("GHOST_ROUTE: message %x is for us! Delivering %d bytes payload",
			shortHex(header.MessageID), len(payload))

		// Step 1: Durably record delivered message state BEFORE invoking application callback
		deliveredMsg := &Message{
			ID:              header.MessageID,
			Src:             header.Src,
			Dst:             header.Dst,
			Payload:         payload,
			CopiesRemaining: 0,
			TTLSeconds:      header.TTLSeconds,
			HopCount:        header.HopCount,
			CreatedAt:       header.CreatedAt,
			Status:          StatusDelivered,
		}
		if err := r.store.SaveMessage(deliveredMsg); err != nil {
			log.Printf("GHOST_ROUTE: error persisting delivered message %x: %v", shortHex(header.MessageID), err)
			return fmt.Sprintf("error: failed to persist delivered message: %v", err)
		}

		// Step 2: Update in-memory dedup cache
		r.deliveredDedup.Add(msgIDKey, nowUnix)

		// Test seam hook for crash consistency testing
		if r.onAfterPersistDeliverHook != nil {
			r.onAfterPersistDeliverHook(header.MessageID)
		}

		// Step 3: Invoke callback
		handler := r.handler
		r.mu.Unlock()
		if handler != nil {
			handler.OnDeliver(header.Src, payload)
		}
		r.mu.Lock() // Re-acquire for deferred unlock
		return "delivered"
	}

	// Check hop limit
	if header.HopCount >= MaxHops {
		log.Printf("GHOST_ROUTE: dropping msg %x: hop limit %d reached", shortHex(header.MessageID), header.HopCount)
		return "dropped: hop limit"
	}

	// Check copies
	if header.CopiesRemaining <= 0 {
		log.Printf("GHOST_ROUTE: dropping msg %x: no copies remaining", shortHex(header.MessageID))
		return "dropped: no copies"
	}

	// Check TTL
	if header.CreatedAt+header.TTLSeconds < r.now().Unix() {
		log.Printf("GHOST_ROUTE: dropping msg %x: TTL expired", shortHex(header.MessageID))
		return "dropped: TTL expired"
	}

	// Policy gate: relay willingness (set by PowerPolicyEngine via Kotlin)
	// When willingness is 0, this node operates as a leaf — it won't store
	// forwarded messages. This does NOT change the Spray-and-Wait algorithm;
	// the sender's binary split logic is untouched. Only the receiver's
	// decision to accept relay work is affected.
	if r.relayWillingness <= 0 {
		log.Printf("GHOST_ROUTE: dropping msg %x: low battery, not relaying (willingness=%.2f)",
			shortHex(header.MessageID), r.relayWillingness)
		return "dropped: low battery, not relaying"
	}

	// Forward: save to our store for spraying to future peers
	msg := &Message{
		ID:              header.MessageID,
		Src:             header.Src,
		Dst:             header.Dst,
		Payload:         payload,
		CopiesRemaining: header.CopiesRemaining,
		TTLSeconds:      header.TTLSeconds,
		HopCount:        header.HopCount,
		CreatedAt:       header.CreatedAt,
		Status:          StatusSprayed,
	}

	if err := r.store.SaveMessage(msg); err != nil {
		return fmt.Sprintf("error: failed to save forwarded message: %v", err)
	}

	log.Printf("GHOST_ROUTE: forwarded msg %x (src=%x dst=%x hop=%d copies=%d)",
		shortHex(header.MessageID), shortHex(header.Src), shortHex(header.Dst), header.HopCount, header.CopiesRemaining)

	return "forwarded"
}

// GetStats returns debug stats as a JSON string.
func (r *Router) GetStats() string {
	r.mu.Lock()
	defer r.mu.Unlock()

	pendingMsgs, _ := r.store.GetPendingMessages()
	allPeers, _ := r.store.GetAllPeers()

	stats := map[string]interface{}{
		"localID":          fmt.Sprintf("%x", r.localID[:8]),
		"messagesStored":   r.store.MessageCount(),
		"messagesPending":  len(pendingMsgs),
		"peersKnown":       len(allPeers),
		"peerCount":        r.store.PeerCount(),
		"relayWillingness": r.relayWillingness,
	}

	data, _ := json.Marshal(stats)
	return string(data)
}

// MarkDelivered marks a message as delivered in BoltDB store.
// Callable by Kotlin or test harness upon confirmed BLE GATT delivery.
func (r *Router) MarkDelivered(msgID []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	_ = r.store.UpdateMessageStatus(msgID, StatusDelivered)
}

// LocalID returns a copy of the router's local node ID.
func (r *Router) LocalID() []byte {
	r.mu.Lock()
	defer r.mu.Unlock()
	cp := make([]byte, len(r.localID))
	copy(cp, r.localID)
	return cp
}

// MessageCount returns the number of messages currently in transit/storage.
func (r *Router) MessageCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.store == nil {
		return 0
	}
	return r.store.MessageCount()
}

// GetStore returns the underlying MessageStore (useful for testing and simulation).
func (r *Router) GetStore() *MessageStore {
	return r.store
}
