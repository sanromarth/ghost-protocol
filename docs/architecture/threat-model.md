# GHOST Protocol Threat Model

> **Version:** v0.2.0 — honest engineering assessment of protections and attack surfaces.
> Evaluated following v0.1.5 stability audit and v0.2.0 power/routing hardening.

## 1. Threat Actors

| Actor | Can Attack v0.2.0? | Details |
|---|---|---|
| **Passive eavesdropper** | ✅ Mitigated | AES-256-GCM + ephemeral X25519 per message |
| **Relay flood / Battery drainer** | ✅ Mitigated | `PowerPolicyEngine` throttles radio; `relayWillingness` drops transit packets under 20% battery |
| **Replay attacker** | ✅ Mitigated | 60s sliding window deduplicates on ciphertext (unique ephemeral nonces) |
| **Local adversary (physical access)** | ⚠ Partially mitigated | Keys in app-private storage. No PIN/biometric lock. |
| **Network adversary (malicious relay)** | ⚠ Partially mitigated | Cannot read/forge messages. Can drop transit blobs. |
| **State-level adversary** | ❌ Not mitigated | BLE traffic analysis reveals device proximity and cluster graphs |
| **Quantum adversary** | ❌ Not mitigated | Classical X25519/Ed25519 only |

## 2. What v0.2.0 Actually Protects

### ✅ Tier 1: Resilience & Battery Protection
- **Memory Safety:** Rust FFI panics across boundaries eliminated.
- **Concurrency:** Go router deadlocks and BoltDB lock contention resolved.
- **Denial-of-Sleep / Battery Exhaustion:** Malicious peers flooding relay requests cannot drain a dying phone. At battery < 20%, `relayWillingness` drops to 0.0, discarding transit blobs before disk write.
- **Connection Storms:** Concurrent GATT connections serialized to prevent Android error 133.
- **Collision & Replay Resistance:** Message hashing uses ciphertext (including ephemeral nonce).

### ✅ Tier 1: Confidentiality
- **E2E encryption:** Every message encrypted with X25519 ECDH + AES-256-GCM.
- **Forward secrecy:** Fresh ephemeral X25519 keypair per message.
- **Relay blindness:** Relay nodes handle opaque binary blobs. Content, sender identity, and recipient public keys are encrypted.

### ✅ Tier 1: Integrity & Authentication
- **Ed25519 digital signatures:** Authenticates sender and prevents payload tampering.
- **AEAD authentication tag:** Bit-flip attacks detected by GCM.
- **Zero-TOFU QR Exchange:** Cryptographic identity exchanged in-person.
- **Reciprocal Mutual Verification:** Two-way verification confirms both parties scanned each other before activating the signature Ghost Aura (animated Ethereal Ring) on the peer's avatar.

## 3. What v0.2.0 Does NOT Protect

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
| **v0.2.0 ✓** | PowerPolicyEngine + relay willingness gate | Battery exhaustion / Denial-of-Sleep attacks |
| **v0.2.0 ✓** | Reciprocal mutual QR verification + ciphertext dedup | Replay attacks & unilateral contact impersonation |
| **v0.2.5** | Contact Introductions (signed vouching envelope) | Sybil / unknown peer injection |
| **v0.3.0** | Protest Mode (1-tap consent discovery + 24h short codes) | Physical capture during high-friction setup |
| **v0.3.5** | Fixed-size packet padding + cover traffic | Traffic analysis & packet length correlation |
| **v0.4.0** | WiFi Direct multi-transport fallback | BLE 2.4GHz RF jamming (partial) |
| **v1.0.0** | ML-KEM-1024 + ML-DSA-65 hybrid PQ crypto | Store-now-decrypt-later quantum adversary |
| **v1.0.0** | Shamir (5,3) identity recovery + biometric seed | Key loss & device seizure |
