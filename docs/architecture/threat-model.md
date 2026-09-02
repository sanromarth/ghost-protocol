# GHOST Protocol Threat Model

> **Version:** v0.1.5 — honest assessment of what v0.1.5 protects against following a 10-round audit fixing 67 vulnerabilities.
> For the full aspirational threat model, see the original RFCs.

## 1. Threat Actors

| Actor | Can Attack v0.1.5? | Details |
|---|---|---|
| **Passive eavesdropper** | ✅ Mitigated | AES-256-GCM + ephemeral X25519 per message |
| **Local adversary (physical access)** | ⚠ Partially mitigated | Keys in app-private storage. No PIN/biometric lock. |
| **Network adversary (malicious relay)** | ⚠ Partially mitigated | Can't read/forge messages. Can delay or drop them. |
| **State-level adversary** | ❌ Not mitigated | BLE traffic analysis reveals device locations and social graphs |
| **Quantum adversary** | ❌ Not mitigated | X25519/Ed25519 are classically secure only |

## 2. What v0.1.5 Actually Protects

### ✅ Tier 1: Resilience (PROTECTED via v0.1.5 Hardening)
- **Memory Safety:** Rust unwrap panics across FFI boundary mitigated.
- **Concurrency:** Go router deadlocks and BoltDB lock contention fixed.
- **Collision Resistance:** Message ID generation includes random nonces to prevent collisions.
- **Resource Exhaustion:** GATT client connection timeout (10s) and GATT server buffer overflow fixes prevent DoS.


### ✅ Tier 1: Confidentiality (PROTECTED)
- **E2E encryption:** Every message encrypted with X25519 ECDH + AES-256-GCM
- **Forward secrecy:** Fresh ephemeral X25519 keypair per message. Compromising long-term key doesn't reveal past messages
- **Relay blindness:** Relay nodes carry encrypted blobs. They cannot read the content or verify the sender

### ✅ Tier 1: Integrity (PROTECTED)
- **Ed25519 signatures:** Every message signed with sender's Ed25519 key. Tampering is detectable
- **AES-GCM authentication tag:** Bit-flip attacks detected by the AEAD construction

### ✅ Tier 1: Authentication (PROTECTED)
- **QR code key exchange:** Cryptographic identity verified in-person. No TOFU (trust-on-first-use) vulnerability
- **Sender verification:** Receiver verifies Ed25519 signature against stored public key. Spoofing requires stealing the private key

## 3. What v0.1.5 Does NOT Protect

### ⚠ Tier 2: Metadata (PARTIALLY EXPOSED)
| Metadata | Status | Why |
|---|---|---|
| **Who talks to whom** | Exposed | BLE fingerprints (4 bytes of SHA-256) are broadcast in clear. Any BLE scanner sees which devices interact |
| **When messages are sent** | Exposed | No cover traffic. Timing analysis trivially reveals communication patterns |
| **Device location** | Exposed | BLE signal is detectable within ~10m. Directional antenna extends this |
| **Message size** | Exposed | No padding to fixed size. Message length is visible in BLE GATT write |

### ❌ Tier 3: Advanced Attacks (NOT MITIGATED)
| Attack | v0.1.5 Status |
|---|---|
| **RF jamming** | Single transport (BLE). Jamming 2.4GHz blocks all communication. No ultrasonic/IR fallback. |
| **Traffic analysis / social graph** | No cover traffic, no packet morphing. Adversary with BLE sniffer maps the entire network graph. |
| **Sybil attack** | No proof-of-personhood. Any device can generate unlimited identities and join the mesh. |
| **Relay manipulation** | Malicious relay can selectively drop messages. No accountability or reputation system. |
| **Eclipse attack** | Attacker surrounds a target with colluding nodes. All sprayed copies go to attacker. No defense. |

### ❌ Tier 4: Device Compromise (NOT MITIGATED)
| Attack | v0.1.5 Status |
|---|---|
| **Device seizure** | Keys stored in app-private dir, not hardware enclave. Root access = key extraction. |
| **No dead-man switch** | No auto-wipe after failed attempts. No remote wipe. |
| **Key recovery** | No Shamir secret sharing. Key loss = permanent identity loss. |
| **Biometric lock** | None. Anyone with physical access to unlocked phone can read all messages. |

## 4. Honest Security Comparison

| Feature | GHOST v0.1.5 | Signal | Briar |
|---|---|---|---|
| E2E encryption | ✅ X25519 + AES-256-GCM | ✅ Double Ratchet | ✅ |
| Forward secrecy | ✅ Ephemeral per message | ✅ Per message | ✅ |
| No server required | ✅ | ❌ | ✅ |
| Cover traffic | ❌ | ❌ | ❌ |
| Traffic analysis resistance | ❌ | ❌ | ⚠ (Tor) |
| Post-quantum | ❌ | ✅ (PQXDH) | ❌ |
| Multi-transport | ❌ | N/A | ⚠ (WiFi/BT) |
| Sybil resistance | ❌ | ✅ (phone number) | ❌ |

## 5. Security Assumptions

- Android app sandbox is not compromised (root = game over)
- BLE hardware is not backdoored
- The user verifies QR codes in-person (no MITM on the initial exchange)
- Ed25519 and X25519 remain computationally secure (classical adversary only)

## 6. Residual Risks

- **Store-now-decrypt-later:** Adversary captures BLE traffic now, waits for quantum computers to break X25519. PQ crypto planned for v1.0.
- **Rubber hose cryptanalysis:** Physical coercion. No technical mitigation possible.
- **Android baseband exploits:** Out of scope — requires OS-level defense.
- **Username leakage:** v0.1.5 embeds username in encrypted payload. If the encryption key is compromised, the username is exposed along with the message.

## 7. Roadmap to Stronger Security

| Version | Addition | Threat Mitigated |
|---|---|---|
| v0.2 | Delivery receipts + encounter-aware routing | Relay manipulation (partial) |
| v0.3 | Fixed-size packets + cover traffic | Traffic analysis (partial) |
| v0.3 | WiFi Direct transport fallback | RF jamming (partial) |
| v1.0 | ML-KEM-1024 + ML-DSA-65 hybrid PQ crypto | Quantum adversary |
| v1.0 | Shamir (5,3) identity recovery | Key loss |
| v1.0 | Biometric seed + dead-man switch | Device seizure |
