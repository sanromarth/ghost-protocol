package ghostrouter

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// testDeliverHandler implements DeliverHandler for testing
type testDeliverHandler struct {
	delivered bool
	payload   []byte
	failTest  func(args ...interface{})
}

func (h *testDeliverHandler) OnDeliver(dst []byte, payload []byte) {
	if h.failTest != nil {
		h.failTest("onDeliver should NOT be called for forwarded messages")
		return
	}
	h.delivered = true
	h.payload = payload
}

func tempDBPath(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	return filepath.Join(dir, "test_router.db")
}

func TestStoreOpenClose(t *testing.T) {
	path := tempDBPath(t)
	store, err := OpenStore(path)
	if err != nil {
		t.Fatalf("failed to open store: %v", err)
	}
	defer store.Close()

	if _, err := os.Stat(path); os.IsNotExist(err) {
		t.Fatal("DB file should exist after OpenStore")
	}
}

func TestStoreSaveAndGetMessage(t *testing.T) {
	store, _ := OpenStore(tempDBPath(t))
	defer store.Close()

	msg := &Message{
		ID:              []byte("test-id-00000000000000000000000"),
		Src:             bytes.Repeat([]byte{0x01}, 32),
		Dst:             bytes.Repeat([]byte{0x02}, 32),
		Payload:         []byte("hello encrypted"),
		CopiesRemaining: 4,
		TTLSeconds:      86400,
		HopCount:        0,
		CreatedAt:       time.Now().Unix(),
		Status:          StatusPending,
	}

	if err := store.SaveMessage(msg); err != nil {
		t.Fatalf("SaveMessage failed: %v", err)
	}

	got, err := store.GetMessage(msg.ID)
	if err != nil {
		t.Fatalf("GetMessage failed: %v", err)
	}

	if !bytes.Equal(got.Payload, msg.Payload) {
		t.Errorf("payload mismatch: got %s, want %s", got.Payload, msg.Payload)
	}
	if got.CopiesRemaining != 4 {
		t.Errorf("CopiesRemaining: got %d, want 4", got.CopiesRemaining)
	}
}

func TestStoreDeleteExpired(t *testing.T) {
	store, _ := OpenStore(tempDBPath(t))
	defer store.Close()

	msg := &Message{
		ID:              []byte("expired-msg-0000000000000000000"),
		Src:             bytes.Repeat([]byte{0x01}, 32),
		Dst:             bytes.Repeat([]byte{0x02}, 32),
		Payload:         []byte("old message"),
		CopiesRemaining: 2,
		TTLSeconds:      60,
		HopCount:        1,
		CreatedAt:       time.Now().Unix() - 70,
		Status:          StatusPending,
	}
	store.SaveMessage(msg)

	deleted, err := store.DeleteExpired(time.Now().Unix())
	if err != nil {
		t.Fatalf("DeleteExpired failed: %v", err)
	}
	if deleted != 1 {
		t.Errorf("expected 1 deleted, got %d", deleted)
	}

	_, err = store.GetMessage(msg.ID)
	if err == nil {
		t.Error("expired message should have been deleted")
	}
}

func TestStorePeerCRUD(t *testing.T) {
	store, _ := OpenStore(tempDBPath(t))
	defer store.Close()

	peer := &PeerInfo{
		ID:             bytes.Repeat([]byte{0xAA}, 32),
		LastSeen:       time.Now().UnixMilli(),
		LastRSSI:       -65,
		EncounterCount: 1,
	}

	if err := store.SavePeer(peer); err != nil {
		t.Fatalf("SavePeer failed: %v", err)
	}

	got, err := store.GetPeer(peer.ID)
	if err != nil {
		t.Fatalf("GetPeer failed: %v", err)
	}
	if got.LastRSSI != -65 {
		t.Errorf("RSSI mismatch: got %d, want -65", got.LastRSSI)
	}

	peers, err := store.GetAllPeers()
	if err != nil {
		t.Fatalf("GetAllPeers failed: %v", err)
	}
	if len(peers) != 1 {
		t.Errorf("expected 1 peer, got %d", len(peers))
	}
}

func TestSerializerRoundTrip(t *testing.T) {
	msg := &Message{
		ID:              bytes.Repeat([]byte{0x11}, 32),
		Src:             bytes.Repeat([]byte{0x22}, 32),
		Dst:             bytes.Repeat([]byte{0x33}, 32),
		Payload:         []byte("test payload data 1234567890"),
		CopiesRemaining: 3,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       1725196800,
	}

	encoded := EncodeMessage(msg)
	result, err := decodeMessage(encoded)
	if err != nil {
		t.Fatalf("decodeMessage failed: %v", err)
	}

	if !bytes.Equal(result.Header.MessageID, msg.ID) {
		t.Error("MessageID mismatch")
	}
	if !bytes.Equal(result.Header.Src, msg.Src) {
		t.Error("Src mismatch")
	}
	if !bytes.Equal(result.Header.Dst, msg.Dst) {
		t.Error("Dst mismatch")
	}
	if result.Header.CopiesRemaining != 3 {
		t.Errorf("CopiesRemaining: got %d, want 3", result.Header.CopiesRemaining)
	}
	if result.Header.HopCount != 1 {
		t.Errorf("HopCount: got %d, want 1", result.Header.HopCount)
	}
	if !bytes.Equal(result.Payload, msg.Payload) {
		t.Error("payload mismatch")
	}
}

func TestRouterDirectDelivery(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	handler := &testDeliverHandler{}
	router.SetHandler(handler)

	msg := &Message{
		ID:              bytes.Repeat([]byte{0x11}, 32),
		Src:             bytes.Repeat([]byte{0xBB}, 32),
		Dst:             localID,
		Payload:         []byte("secret message for me"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       time.Now().Unix(),
	}
	encoded := EncodeMessage(msg)
	result := router.OnMessageReceived(encoded)

	if result != "delivered" {
		t.Errorf("expected 'delivered', got '%s'", result)
	}
	if !handler.delivered {
		t.Error("onDeliver callback was not called")
	}
	if !bytes.Equal(handler.payload, msg.Payload) {
		t.Error("delivered payload mismatch")
	}
}

func TestRouterForwarding(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	router.SetHandler(&testDeliverHandler{failTest: t.Error})

	otherDst := bytes.Repeat([]byte{0xCC}, 32)
	msg := &Message{
		ID:              bytes.Repeat([]byte{0x22}, 32),
		Src:             bytes.Repeat([]byte{0xBB}, 32),
		Dst:             otherDst,
		Payload:         []byte("not for me"),
		CopiesRemaining: 3,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       time.Now().Unix(),
	}
	encoded := EncodeMessage(msg)
	result := router.OnMessageReceived(encoded)

	if result != "forwarded" {
		t.Errorf("expected 'forwarded', got '%s'", result)
	}

	stored, err := router.store.GetMessage(msg.ID)
	if err != nil {
		t.Fatalf("forwarded message not found in store: %v", err)
	}
	if stored.Status != StatusSprayed {
		t.Errorf("status should be Sprayed, got %d", stored.Status)
	}
}

func TestRouterHopLimit(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	msg := &Message{
		ID:              bytes.Repeat([]byte{0x33}, 32),
		Src:             bytes.Repeat([]byte{0xBB}, 32),
		Dst:             bytes.Repeat([]byte{0xCC}, 32),
		Payload:         []byte("too many hops"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        MaxHops,
		CreatedAt:       time.Now().Unix(),
	}
	encoded := EncodeMessage(msg)
	result := router.OnMessageReceived(encoded)

	if result != "dropped: hop limit" {
		t.Errorf("expected 'dropped: hop limit', got '%s'", result)
	}
}

func TestRouterTTLExpiry(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	msg := &Message{
		ID:              bytes.Repeat([]byte{0x44}, 32),
		Src:             bytes.Repeat([]byte{0xBB}, 32),
		Dst:             bytes.Repeat([]byte{0xCC}, 32),
		Payload:         []byte("expired"),
		CopiesRemaining: 2,
		TTLSeconds:      60,
		HopCount:        1,
		CreatedAt:       time.Now().Unix() - 120,
	}
	encoded := EncodeMessage(msg)
	result := router.OnMessageReceived(encoded)

	if result != "dropped: TTL expired" {
		t.Errorf("expected 'dropped: TTL expired', got '%s'", result)
	}
}

func TestRouterDuplicate(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	msg := &Message{
		ID:              bytes.Repeat([]byte{0x55}, 32),
		Src:             bytes.Repeat([]byte{0xBB}, 32),
		Dst:             bytes.Repeat([]byte{0xCC}, 32),
		Payload:         []byte("dup test"),
		CopiesRemaining: 3,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       time.Now().Unix(),
	}
	encoded := EncodeMessage(msg)

	result1 := router.OnMessageReceived(encoded)
	if result1 != "forwarded" {
		t.Errorf("first receive: expected 'forwarded', got '%s'", result1)
	}

	result2 := router.OnMessageReceived(encoded)
	if result2 != "dropped: duplicate" {
		t.Errorf("second receive: expected 'dropped: duplicate', got '%s'", result2)
	}
}

func TestRouterSprayOnPeerDiscovery(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	dstID := bytes.Repeat([]byte{0xCC}, 32)
	carrierID := bytes.Repeat([]byte{0xDD}, 32)

	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	// Queue a message for dst
	sendResult := router.SendMessage(dstID, []byte("hello far away"))
	if sendResult.Blob != nil {
		t.Error("expected nil blob (dst not recently seen)")
	}
	if sendResult.Status != "queued" {
		t.Errorf("expected 'queued', got '%s'", sendResult.Status)
	}

	// Discover a carrier (not the dst)
	blobs := router.OnPeerDiscovered(carrierID, -70)
	if blobs.Size() != 1 {
		t.Fatalf("expected 1 blob to spray, got %d", blobs.Size())
	}

	// Verify the sprayed blob
	decoded, err := decodeMessage(blobs.Get(0))
	if err != nil {
		t.Fatalf("failed to decode sprayed blob: %v", err)
	}
	if !bytes.Equal(decoded.Header.Dst, dstID) {
		t.Error("sprayed blob has wrong destination")
	}
	if !bytes.Equal(decoded.Payload, []byte("hello far away")) {
		t.Errorf("payload mismatch: got %s", decoded.Payload)
	}
	if decoded.Header.HopCount != 1 {
		t.Errorf("hop count should be 1, got %d", decoded.Header.HopCount)
	}
}

func TestRouterSendDirectWhenPeerRecent(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	dstID := bytes.Repeat([]byte{0xBB}, 32)

	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	// Discover the peer first
	router.OnPeerDiscovered(dstID, -60)

	// Now send — should return direct blob
	sendResult := router.SendMessage(dstID, []byte("direct hello"))
	if sendResult.Blob == nil {
		t.Fatal("expected non-nil blob for direct delivery")
	}
	if sendResult.Status != "direct" {
		t.Errorf("expected 'direct', got '%s'", sendResult.Status)
	}

	decoded, err := decodeMessage(sendResult.Blob)
	if err != nil {
		t.Fatalf("failed to decode direct blob: %v", err)
	}
	if !bytes.Equal(decoded.Header.Dst, dstID) {
		t.Error("dst mismatch")
	}
	if !bytes.Equal(decoded.Payload, []byte("direct hello")) {
		t.Error("payload mismatch")
	}
}

func TestRouterDeliverOnPeerDiscovery(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	dstID := bytes.Repeat([]byte{0xBB}, 32)

	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	// Queue a message
	router.SendMessage(dstID, []byte("delayed hello"))

	// Discover the dst — should deliver
	blobs := router.OnPeerDiscovered(dstID, -50)
	if blobs.Size() != 1 {
		t.Fatalf("expected 1 blob for delivery, got %d", blobs.Size())
	}

	decoded, err := decodeMessage(blobs.Get(0))
	if err != nil {
		t.Fatalf("decode error: %v", err)
	}
	if !bytes.Equal(decoded.Payload, []byte("delayed hello")) {
		t.Errorf("payload mismatch: got %s", decoded.Payload)
	}
}

func TestRouterGetStats(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	defer router.Stop()

	stats := router.GetStats()
	if len(stats) < 10 {
		t.Errorf("stats too short: %s", stats)
	}
}

func TestBatchEncodingRoundtrip(t *testing.T) {
	// Create 3 test messages with different payloads
	messages := make([]*Message, 3)
	for i := 0; i < 3; i++ {
		messages[i] = &Message{
			ID:              bytes.Repeat([]byte{byte(0x10 + i)}, 32),
			Src:             bytes.Repeat([]byte{0xAA}, 32),
			Dst:             bytes.Repeat([]byte{0xBB}, 32),
			Payload:         []byte("test payload " + string(rune('A'+i))),
			CopiesRemaining: 4 - i,
			TTLSeconds:      86400,
			HopCount:        i,
			CreatedAt:       time.Now().Unix(),
			Status:          StatusPending,
		}
	}

	// Encode each message individually
	encodedMsgs := make([][]byte, 3)
	for i, msg := range messages {
		encodedMsgs[i] = EncodeMessage(msg)
		if len(encodedMsgs[i]) == 0 {
			t.Fatalf("EncodeMessage returned empty for message %d", i)
		}
	}

	// Batch encode
	batched, err := EncodeBatch(encodedMsgs)
	if err != nil {
		t.Fatalf("EncodeBatch failed: %v", err)
	}

	// Verify batch header: first byte should be count=3
	if batched[0] != 3 {
		t.Errorf("batch count: got %d, want 3", batched[0])
	}

	// Decode batch
	decoded, err := DecodeBatch(batched)
	if err != nil {
		t.Fatalf("DecodeBatch failed: %v", err)
	}

	if len(decoded) != 3 {
		t.Fatalf("decoded count: got %d, want 3", len(decoded))
	}

	// Verify each decoded message matches the original encoded message
	for i := 0; i < 3; i++ {
		if !bytes.Equal(decoded[i], encodedMsgs[i]) {
			t.Errorf("message %d mismatch: decoded %d bytes, original %d bytes", i, len(decoded[i]), len(encodedMsgs[i]))
		}

		// Also verify the decoded message can be parsed back
		result, err := decodeMessage(decoded[i])
		if err != nil {
			t.Errorf("decodeMessage on decoded[%d] failed: %v", i, err)
			continue
		}
		if !bytes.Equal(result.Header.MessageID, messages[i].ID) {
			t.Errorf("message %d ID mismatch after roundtrip", i)
		}
		if !bytes.Equal(result.Payload, messages[i].Payload) {
			t.Errorf("message %d payload mismatch after roundtrip", i)
		}
	}

	// Verify single message still works through existing path (not batched)
	singleEncoded := EncodeMessage(messages[0])
	singleResult, err := decodeMessage(singleEncoded)
	if err != nil {
		t.Fatalf("single message decode failed: %v", err)
	}
	if !bytes.Equal(singleResult.Payload, messages[0].Payload) {
		t.Error("single message payload mismatch")
	}

	// Verify edge cases
	_, err = EncodeBatch(nil)
	if err == nil {
		t.Error("EncodeBatch(nil) should fail")
	}
	_, err = EncodeBatch([][]byte{})
	if err == nil {
		t.Error("EncodeBatch(empty) should fail")
	}
	_, err = DecodeBatch([]byte{})
	if err == nil {
		t.Error("DecodeBatch(empty) should fail")
	}
	_, err = DecodeBatch([]byte{0})
	if err == nil {
		t.Error("DecodeBatch(count=0) should fail")
	}
}

func TestRelayWillingnessGate(t *testing.T) {
	localID := bytes.Repeat([]byte{0xAA}, 32)
	senderID := bytes.Repeat([]byte{0xCC}, 32)
	otherDstID := bytes.Repeat([]byte{0xDD}, 32)

	// Create a message destined for someone else (forwarded case)
	forwardedMsg := &Message{
		ID:              bytes.Repeat([]byte{0x42}, 32),
		Src:             senderID,
		Dst:             otherDstID,
		Payload:         []byte("relay test payload"),
		CopiesRemaining: 2,
		TTLSeconds:      86400,
		HopCount:        1,
		CreatedAt:       time.Now().Unix(),
		Status:          StatusSprayed,
	}
	encoded := EncodeMessage(forwardedMsg)

	// Test 1: willingness = 0 → forwarded message should be dropped
	router, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	router.SetHandler(&testDeliverHandler{})
	router.Start()
	defer router.Stop()

	router.SetRelayWillingness(0)
	result := router.OnMessageReceived(encoded)
	if result != "dropped: low battery, not relaying" {
		t.Errorf("willingness=0: got %q, want %q", result, "dropped: low battery, not relaying")
	}

	// Test 2: willingness = 1.0 → same message should be forwarded
	router2, err := NewRouter(localID, tempDBPath(t))
	if err != nil {
		t.Fatalf("NewRouter failed: %v", err)
	}
	router2.SetHandler(&testDeliverHandler{})
	router2.Start()
	defer router2.Stop()

	router2.SetRelayWillingness(1.0)
	result2 := router2.OnMessageReceived(encoded)
	if result2 != "forwarded" {
		t.Errorf("willingness=1.0: got %q, want %q", result2, "forwarded")
	}

	// Test 3: GetRelayWillingness returns correct value
	if w := router.GetRelayWillingness(); w != 0 {
		t.Errorf("GetRelayWillingness: got %f, want 0", w)
	}
	if w := router2.GetRelayWillingness(); w != 1.0 {
		t.Errorf("GetRelayWillingness: got %f, want 1.0", w)
	}

	// Test 4: Clamping
	router.SetRelayWillingness(-5)
	if w := router.GetRelayWillingness(); w != 0 {
		t.Errorf("clamping negative: got %f, want 0", w)
	}
	router.SetRelayWillingness(10)
	if w := router.GetRelayWillingness(); w != 1 {
		t.Errorf("clamping >1: got %f, want 1", w)
	}
}

func TestDedupCache(t *testing.T) {
	cache := NewDedupCache(10, 60) // max 10 entries, 60s TTL
	now := int64(1000)

	// 1. Initial lookup -> not seen
	if cache.Seen("msg1", now) {
		t.Error("msg1 should not be seen initially")
	}

	// 2. Add and check -> seen
	cache.Add("msg1", now)
	if !cache.Seen("msg1", now) {
		t.Error("msg1 should be seen immediately after add")
	}

	// 3. Expiration -> after 70s (> 60s TTL), Seen returns false
	if cache.Seen("msg1", now+70) {
		t.Error("msg1 should be expired after 70s")
	}

	// 4. FIFO capacity eviction under cache pressure
	for i := 0; i < 15; i++ {
		cache.Add(string(rune('A'+i)), now)
	}
	if cache.Size() > 10 {
		t.Errorf("cache size %d exceeded maxEntries 10", cache.Size())
	}
	// Oldest entries (A, B, C, D, E) should have been evicted
	if cache.Seen(string('A'), now) {
		t.Error("entry 'A' should have been evicted by FIFO pressure")
	}
	// Newest entry (O) must still exist
	if !cache.Seen(string('O'), now) {
		t.Error("entry 'O' should still exist in cache")
	}
}

func TestPrunePreservesLocalPendingMessages(t *testing.T) {
	store, err := OpenStore(tempDBPath(t))
	if err != nil {
		t.Fatalf("OpenStore failed: %v", err)
	}
	defer store.Close()

	localID := bytes.Repeat([]byte{0xAA}, 32)
	store.SetLocalID(localID)
	now := time.Now().Unix()

	// Insert 100 locally authored pending messages
	var localMsgIDs [][]byte
	for i := 0; i < 100; i++ {
		id := []byte(fmt.Sprintf("local-msg-%04d", i))
		localMsgIDs = append(localMsgIDs, id)
		msg := &Message{
			ID:              id,
			Src:             localID,
			Dst:             bytes.Repeat([]byte{0xBB}, 32),
			Payload:         []byte("local important payload"),
			CopiesRemaining: 4,
			TTLSeconds:      86400,
			HopCount:        0,
			CreatedAt:       now - int64(1000-i), // old timestamps
			Status:          StatusPending,
		}
		if err := store.SaveMessage(msg); err != nil {
			t.Fatalf("SaveMessage local failed: %v", err)
		}
	}

	// Insert 450 transit relay messages across diverse destinations (total = 550 > 500 max cap)
	remoteSrc := bytes.Repeat([]byte{0xCC}, 32)
	for i := 0; i < 450; i++ {
		id := []byte(fmt.Sprintf("relay-msg-%04d", i))
		dst := []byte(fmt.Sprintf("dest-%04d-padding-to-32-bytes-000", i/10)) // 10 msgs per dest (< 50 quota)
		msg := &Message{
			ID:              id,
			Src:             remoteSrc,
			Dst:             dst,
			Payload:         []byte("transit relay payload"),
			CopiesRemaining: 2,
			TTLSeconds:      86400,
			HopCount:        1,
			CreatedAt:       now - int64(500-i),
			Status:          StatusSprayed,
		}
		if err := store.SaveMessage(msg); err != nil {
			t.Fatalf("SaveMessage relay failed: %v", err)
		}
	}

	if count := store.MessageCount(); count != 550 {
		t.Fatalf("initial message count: got %d, want 550", count)
	}

	// Run PruneIfNeeded
	if err := store.PruneIfNeeded(); err != nil {
		t.Fatalf("PruneIfNeeded failed: %v", err)
	}

	// Post-prune count must be pruned down to 400
	newCount := store.MessageCount()
	if newCount > 400 {
		t.Errorf("post-prune message count %d > 400 prune target", newCount)
	}

	// CRITICAL INVARIANT: ALL 100 locally authored pending messages MUST still exist
	for idx, id := range localMsgIDs {
		msg, err := store.GetMessage(id)
		if err != nil || msg == nil {
			t.Fatalf("VIOLATION: locally authored unsent message #%d (%s) was evicted during prune!", idx, string(id))
		}
	}
}

func TestMaxMessagesPerDstQuota(t *testing.T) {
	store, err := OpenStore(tempDBPath(t))
	if err != nil {
		t.Fatalf("OpenStore failed: %v", err)
	}
	defer store.Close()

	localID := bytes.Repeat([]byte{0x11}, 32)
	store.SetLocalID(localID)
	now := time.Now().Unix()

	targetDst := bytes.Repeat([]byte{0x99}, 32)
	remoteSrc := bytes.Repeat([]byte{0x22}, 32)

	// Save 55 relay messages for targetDst
	for i := 0; i < 55; i++ {
		id := []byte(string(rune(3000+i)) + "-flood-msg")
		msg := &Message{
			ID:              id,
			Src:             remoteSrc,
			Dst:             targetDst,
			Payload:         []byte("spam payload"),
			CopiesRemaining: 2,
			TTLSeconds:      86400,
			HopCount:        1,
			CreatedAt:       now + int64(i),
			Status:          StatusSprayed,
		}
		if err := store.SaveMessage(msg); err != nil {
			t.Fatalf("SaveMessage flood failed: %v", err)
		}
	}

	// Messages for targetDst must be capped at 50
	dstMsgs, err := store.GetMessagesForDst(targetDst)
	if err != nil {
		t.Fatalf("GetMessagesForDst failed: %v", err)
	}
	if len(dstMsgs) > 50 {
		t.Errorf("destination flood quota exceeded: got %d messages, want <= 50", len(dstMsgs))
	}
}

func TestStoreCorruptionRecovery(t *testing.T) {
	corruptPath := tempDBPath(t)
	// Write corrupted garbage bytes into the database file
	if err := os.WriteFile(corruptPath, []byte("NOT_A_VALID_BOLTDB_HEADER_GARBAGE_BYTES_0123456789"), 0600); err != nil {
		t.Fatalf("failed to write corrupt file: %v", err)
	}

	// OpenStore must recover automatically without returning an error
	store, err := OpenStore(corruptPath)
	if err != nil {
		t.Fatalf("OpenStore failed to recover from corrupted file: %v", err)
	}
	defer store.Close()

	// Verify we can read and write to the recovered store
	msg := &Message{
		ID:              bytes.Repeat([]byte{0x77}, 32),
		Src:             bytes.Repeat([]byte{0x11}, 32),
		Dst:             bytes.Repeat([]byte{0x22}, 32),
		Payload:         []byte("recovered message"),
		CopiesRemaining: 4,
		TTLSeconds:      3600,
		CreatedAt:       time.Now().Unix(),
		Status:          StatusPending,
	}
	if err := store.SaveMessage(msg); err != nil {
		t.Fatalf("SaveMessage on recovered store failed: %v", err)
	}

	retrieved, err := store.GetMessage(msg.ID)
	if err != nil {
		t.Fatalf("GetMessage on recovered store failed: %v", err)
	}
	if !bytes.Equal(retrieved.Payload, msg.Payload) {
		t.Errorf("payload mismatch in recovered store: got %s, want %s", string(retrieved.Payload), string(msg.Payload))
	}
}
