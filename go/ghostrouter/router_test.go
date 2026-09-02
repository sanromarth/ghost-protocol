package ghostrouter

import (
	"bytes"
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
