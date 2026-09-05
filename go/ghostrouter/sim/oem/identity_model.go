package oem

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/IdentityManager.kt & ConversationRepository.kt
// CONTRACT: O9 (Mac Rotation Stability), O23 (Identity Immutability)
// MODEL: Persistent Ed25519 identity keys vs transient, rotating BLE MAC addresses.

// PeerRecord models a known contact / peer binding permanent cryptographic ID to transient MAC.
type PeerRecord struct {
	PermanentID   string // 32-byte hex ID / Ed25519 pubkey hash
	Fingerprint   string // 8-hex prefix (contact.id.take(8))
	Name          string
	ActiveBleMac  string
	MacHistory    []string
	LastUpdatedNs int64
}

// IdentityModel simulates cryptographic identity management and Android BLE MAC rotation.
type IdentityModel struct {
	mu sync.Mutex

	clock *VirtualClock

	localEd25519Pubkey []byte
	localPermanentID   string
	localFingerprint   string
	localCurrentMac    string
	localMacHistory    []string

	knownPeers map[string]*PeerRecord // PermanentID -> PeerRecord
	macToPeer  map[string]string      // Current MAC -> PermanentID

	// Metrics
	TotalMacRotations int
	ResolutionErrors  int
}

// NewIdentityModel initializes a node with a deterministic cryptographic identity.
func NewIdentityModel(nodeIndex int, clock *VirtualClock) *IdentityModel {
	pub := make([]byte, 32)
	for i := range pub {
		pub[i] = byte((nodeIndex*31 + i*17) & 0xFF)
	}

	h := sha256.Sum256(pub)
	permID := hex.EncodeToString(h[:])
	fingerprint := permID[:8]
	initialMac := fmt.Sprintf("AA:BB:CC:DD:%02X:%02X", (nodeIndex>>8)&0xFF, nodeIndex&0xFF)

	return &IdentityModel{
		clock:              clock,
		localEd25519Pubkey: pub,
		localPermanentID:   permID,
		localFingerprint:   fingerprint,
		localCurrentMac:    initialMac,
		localMacHistory:    []string{initialMac},
		knownPeers:         make(map[string]*PeerRecord),
		macToPeer:          make(map[string]string),
	}
}

// LocalPermanentID returns node's invariant 32-byte cryptographic ID.
func (id *IdentityModel) LocalPermanentID() string {
	id.mu.Lock()
	defer id.mu.Unlock()
	return id.localPermanentID
}

// LocalFingerprint returns node's 8-hex prefix.
func (id *IdentityModel) LocalFingerprint() string {
	id.mu.Lock()
	defer id.mu.Unlock()
	return id.localFingerprint
}

// LocalMac returns the currently active BLE MAC address.
func (id *IdentityModel) LocalMac() string {
	id.mu.Lock()
	defer id.mu.Unlock()
	return id.localCurrentMac
}

// RotateLocalMac simulates Android BLE random address rotation (RPA).
// Notice that the cryptographic identity and fingerprint DO NOT change.
func (id *IdentityModel) RotateLocalMac(newMac string) {
	id.mu.Lock()
	defer id.mu.Unlock()

	id.localCurrentMac = newMac
	id.localMacHistory = append(id.localMacHistory, newMac)
	id.TotalMacRotations++
}

// RegisterPeer adds or updates a known peer contact with cryptographic identity and initial MAC.
func (id *IdentityModel) RegisterPeer(permID string, name string, initialMac string) {
	id.mu.Lock()
	defer id.mu.Unlock()

	fingerprint := permID
	if len(fingerprint) > 8 {
		fingerprint = fingerprint[:8]
	}

	peer := &PeerRecord{
		PermanentID:   permID,
		Fingerprint:   fingerprint,
		Name:          name,
		ActiveBleMac:  initialMac,
		MacHistory:    []string{initialMac},
		LastUpdatedNs: id.clock.NowNs(),
	}

	id.knownPeers[permID] = peer
	id.macToPeer[initialMac] = permID
}

// UpdatePeerMac simulates encountering an existing contact advertising with a new rotated MAC.
// ConversationRepository resolves peer by fingerprint: contact.id.take(8) == packet.fingerprint.
func (id *IdentityModel) UpdatePeerMac(permID string, newMac string) error {
	id.mu.Lock()
	defer id.mu.Unlock()

	peer, exists := id.knownPeers[permID]
	if !exists {
		id.ResolutionErrors++
		return fmt.Errorf("peer %s not found for MAC rotation update", permID)
	}

	// Remove old MAC mapping
	delete(id.macToPeer, peer.ActiveBleMac)

	// Update with new rotated MAC
	peer.ActiveBleMac = newMac
	peer.MacHistory = append(peer.MacHistory, newMac)
	peer.LastUpdatedNs = id.clock.NowNs()
	id.macToPeer[newMac] = permID

	return nil
}

// ResolvePeerByMac looks up the permanent cryptographic peer from a current MAC address.
func (id *IdentityModel) ResolvePeerByMac(mac string) (*PeerRecord, bool) {
	id.mu.Lock()
	defer id.mu.Unlock()

	permID, exists := id.macToPeer[mac]
	if !exists {
		return nil, false
	}
	peer := id.knownPeers[permID]
	return peer, true
}

// ResolvePeerByFingerprint looks up the peer by 8-hex fingerprint (invariant across MAC rotations).
func (id *IdentityModel) ResolvePeerByFingerprint(fp string) (*PeerRecord, bool) {
	id.mu.Lock()
	defer id.mu.Unlock()

	for _, peer := range id.knownPeers {
		if peer.Fingerprint == fp {
			return peer, true
		}
	}
	return nil, false
}

// CheckIdentityImmutability verifies Invariant O23:
// The cryptographic identity and fingerprint never mutated despite MAC rotations.
func (id *IdentityModel) CheckIdentityImmutability(initialFingerprint string) error {
	id.mu.Lock()
	defer id.mu.Unlock()

	if id.localFingerprint != initialFingerprint {
		return fmt.Errorf("O23 violation: local fingerprint mutated from %s to %s",
			initialFingerprint, id.localFingerprint)
	}
	return nil
}
