package ux

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"sync"
	"time"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/router/GhostRouter.kt
// SOURCE: android/app/src/main/java/com/ghostprotocol/crypto/GhostCrypto.kt
// CONTRACT: U11
// MODEL: Native FFI boundary simulation (Kotlin <-> Go gomobile & Kotlin <-> Rust JNI)
// modeling JNI memory pinning, cryptographic computation, and native fault injection.

// NativeBridgeModel simulates native FFI latency and fault boundaries.
type NativeBridgeModel struct {
	mu sync.Mutex

	profile        DeviceProfile
	InjectedDelay  time.Duration
	FailCrypto     bool
	FailRouting    bool
	PanicOnBridge  bool
	TimeoutOnBridge bool

	// Metrics
	TotalCalls     int64
	TotalFailures  int64
	TotalLatencies time.Duration
}

// NewNativeBridgeModel creates a native bridge model configured with device profile defaults.
func NewNativeBridgeModel(profile DeviceProfile) *NativeBridgeModel {
	return &NativeBridgeModel{
		profile:       profile,
		InjectedDelay: profile.BridgeLatencyBase,
	}
}

// EncryptAndSign simulates Rust JNI cryptographic operations (X25519-AES-GCM + Ed25519 sign).
func (b *NativeBridgeModel) EncryptAndSign(plaintext []byte) ([]byte, time.Duration, error) {
	b.mu.Lock()
	b.TotalCalls++
	delay := b.InjectedDelay
	fail := b.FailCrypto
	panicFail := b.PanicOnBridge
	b.mu.Unlock()

	if panicFail {
		return nil, delay, fmt.Errorf("FATAL: SIGSEGV inside Rust JNI libghost_crypto.so")
	}
	if fail {
		b.mu.Lock()
		b.TotalFailures++
		b.mu.Unlock()
		return nil, delay, fmt.Errorf("Rust crypto error: KeyExchangeFailed")
	}

	// Authentic mock payload representation:
	// [32B senderPubKey][plaintext][64B ed25519 sig] + 28B AES-GCM tag/nonce overhead
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	sig := ed25519.Sign(priv, plaintext)

	full := make([]byte, 0, len(pub)+len(plaintext)+len(sig)+28)
	full = append(full, pub...)
	full = append(full, plaintext...)
	full = append(full, sig...)
	// Simulated ciphertext padding
	pad := make([]byte, 28)
	full = append(full, pad...)

	b.mu.Lock()
	b.TotalLatencies += delay
	b.mu.Unlock()
	return full, delay, nil
}

// DecryptAndVerify simulates Rust JNI inbound message decryption and signature verification.
func (b *NativeBridgeModel) DecryptAndVerify(ciphertext []byte) ([]byte, bool, time.Duration, error) {
	b.mu.Lock()
	b.TotalCalls++
	delay := b.InjectedDelay
	fail := b.FailCrypto
	panicFail := b.PanicOnBridge
	b.mu.Unlock()

	if panicFail {
		return nil, false, delay, fmt.Errorf("FATAL: SIGSEGV inside Rust JNI libghost_crypto.so")
	}
	if fail || len(ciphertext) < 96 {
		b.mu.Lock()
		b.TotalFailures++
		b.mu.Unlock()
		return nil, false, delay, fmt.Errorf("Rust crypto error: DecryptionFailed")
	}

	// Extract payload
	payloadLen := len(ciphertext) - 28
	if payloadLen < 96 {
		payloadLen = len(ciphertext)
	}
	extracted := ciphertext[:payloadLen]

	b.mu.Lock()
	b.TotalLatencies += delay
	b.mu.Unlock()
	return extracted, true, delay, nil
}

// RouteSendMessage simulates Go gomobile router.SendMessage(dstID, ciphertext).
// Returns (isDirect, blob, latency, error).
func (b *NativeBridgeModel) RouteSendMessage(dstID []byte, ciphertext []byte, peerRecent bool) (bool, []byte, time.Duration, error) {
	b.mu.Lock()
	b.TotalCalls++
	delay := b.InjectedDelay
	fail := b.FailRouting
	panicFail := b.PanicOnBridge
	timeout := b.TimeoutOnBridge
	b.mu.Unlock()

	if panicFail {
		return false, nil, delay, fmt.Errorf("FATAL: runtime panic in Go gomobile runtime: nil pointer dereference")
	}
	if timeout {
		return false, nil, delay + 5*time.Second, fmt.Errorf("Go bridge timeout: lock contention")
	}
	if fail {
		b.mu.Lock()
		b.TotalFailures++
		b.mu.Unlock()
		return false, nil, delay, fmt.Errorf("Go router store error: BoltDB write failed")
	}

	// In GHOST protocol: if peer was seen in last 60s, returns direct delivery blob
	isDirect := peerRecent
	var blob []byte
	if isDirect {
		blob = make([]byte, len(ciphertext))
		copy(blob, ciphertext)
	}

	b.mu.Lock()
	b.TotalLatencies += delay
	b.mu.Unlock()
	return isDirect, blob, delay, nil
}

// ComputeMessageHash computes SHA-256(senderContactId || timestampBE || plaintext).
// SOURCE: DeliveryReceiptProtocol.kt
func ComputeMessageHash(senderContactID string, timestampMs int64, text string) string {
	h := sha256.New()
	h.Write([]byte(senderContactID))
	ts := make([]byte, 8)
	for i := 7; i >= 0; i-- {
		ts[i] = byte(timestampMs & 0xFF)
		timestampMs >>= 8
	}
	h.Write(ts)
	h.Write([]byte(text))
	return fmt.Sprintf("%x", h.Sum(nil))
}
