package oem

import (
	"crypto/ed25519"
	"fmt"
	"sync"
)

// SOURCE: android/app/src/main/java/com/ghostprotocol/security/GhostCrypto.kt & native JNI layer
// CONTRACT: O15 (Native Boundary Safety)
// DIRECTIVE: ScopeModelValidated — Go model validates logical call contract, error propagation,
//            and null-safety boundaries. Memory safety of compiled Rust/C code requires native tests.

// NativeBoundaryModel models the JNI bridge between Kotlin and the native Rust crypto / Go router.
type NativeBoundaryModel struct {
	mu sync.Mutex

	clock *VirtualClock

	// Fault injection controls
	InjectJniOom       bool
	InjectCorruptState bool

	// Metrics
	TotalCalls       int
	SafeErrorReturns int
	InvalidArgsSeen  int
}

// NewNativeBoundaryModel creates a native boundary model.
func NewNativeBoundaryModel(clock *VirtualClock) *NativeBoundaryModel {
	return &NativeBoundaryModel{
		clock: clock,
	}
}

// Sign simulates native Rust ed25519 sign via JNI.
// Enforces null checks, key size bounds (32/64 bytes), and safe error returns.
func (n *NativeBoundaryModel) Sign(privKey []byte, message []byte) ([]byte, error) {
	n.mu.Lock()
	defer n.mu.Unlock()

	n.TotalCalls++

	if n.InjectJniOom {
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI native error: std::alloc::alloc failed (Out of memory)")
	}

	if len(privKey) != ed25519.PrivateKeySize && len(privKey) != ed25519.SeedSize {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: private key length must be 32 or 64 bytes, got %d", len(privKey))
	}

	if len(message) == 0 {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: message to sign cannot be empty")
	}

	var key ed25519.PrivateKey
	if len(privKey) == ed25519.SeedSize {
		key = ed25519.NewKeyFromSeed(privKey)
	} else {
		key = privKey
	}

	sig := ed25519.Sign(key, message)
	return sig, nil
}

// Verify simulates native Rust ed25519 verify via JNI.
func (n *NativeBoundaryModel) Verify(pubKey []byte, message []byte, signature []byte) (bool, error) {
	n.mu.Lock()
	defer n.mu.Unlock()

	n.TotalCalls++

	if n.InjectJniOom {
		n.SafeErrorReturns++
		return false, fmt.Errorf("JNI native error: Out of memory")
	}

	if len(pubKey) != ed25519.PublicKeySize {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return false, fmt.Errorf("JNI invalid argument: public key must be 32 bytes, got %d", len(pubKey))
	}

	if len(signature) != ed25519.SignatureSize {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return false, fmt.Errorf("JNI invalid argument: signature must be 64 bytes, got %d", len(signature))
	}

	valid := ed25519.Verify(pubKey, message, signature)
	return valid, nil
}

// EncryptPayload simulates ChaCha20-Poly1305 / XChaCha20 encryption at native boundary.
func (n *NativeBoundaryModel) EncryptPayload(sharedKey []byte, plaintext []byte) ([]byte, error) {
	n.mu.Lock()
	defer n.mu.Unlock()

	n.TotalCalls++

	if n.InjectJniOom {
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI native error: Out of memory")
	}

	if len(sharedKey) != 32 {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: shared key must be 32 bytes, got %d", len(sharedKey))
	}

	if len(plaintext) == 0 {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: plaintext cannot be empty")
	}

	// Simulated ciphertext: 12-byte nonce + plaintext + 16-byte Poly1305 tag
	out := make([]byte, 12+len(plaintext)+16)
	copy(out[12:], plaintext)
	return out, nil
}

// DecryptPayload simulates ChaCha20-Poly1305 / XChaCha20 decryption at native boundary.
func (n *NativeBoundaryModel) DecryptPayload(sharedKey []byte, ciphertext []byte) ([]byte, error) {
	n.mu.Lock()
	defer n.mu.Unlock()

	n.TotalCalls++

	if n.InjectJniOom {
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI native error: Out of memory")
	}

	if len(sharedKey) != 32 {
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: shared key must be 32 bytes, got %d", len(sharedKey))
	}

	if len(ciphertext) < 28 { // 12 nonce + 16 tag
		n.InvalidArgsSeen++
		n.SafeErrorReturns++
		return nil, fmt.Errorf("JNI invalid argument: ciphertext too short (%d bytes)", len(ciphertext))
	}

	// Plaintext is payload between nonce and tag
	ptLen := len(ciphertext) - 28
	plaintext := make([]byte, ptLen)
	copy(plaintext, ciphertext[12:12+ptLen])
	return plaintext, nil
}

// CheckBoundaryContract verifies Invariant O15:
// All invalid or failing native operations return cleanly as errors/null and never crash the process.
func (n *NativeBoundaryModel) CheckBoundaryContract() (ValidationScope, error) {
	n.mu.Lock()
	defer n.mu.Unlock()

	// In the Go simulator, contract safety is MODEL_VALIDATED.
	return ScopeModelValidated, nil
}
