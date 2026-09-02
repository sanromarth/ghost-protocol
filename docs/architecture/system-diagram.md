# GHOST Protocol System Architecture

> **Version:** v0.1.5 — hardened after 10 audit rounds (67 bugs fixed).
> For the full 7-layer vision, see `docs/rfc/rfc-001-physics.md` through `rfc-007-application.md`.

## 1. Overview

GHOST Protocol v0.1.5 is a 3-component offline mesh messenger for Android. Two phones discover each other over BLE 5.0, exchange Ed25519/X25519 keys via QR code, and send end-to-end encrypted text messages routed through a Go Spray-and-Wait router. Messages can hop through intermediate phones when sender and receiver are not in direct BLE range. No servers, no internet, no accounts.

**Languages:** Kotlin (UI + BLE + glue layer), Rust (crypto via JNI), Go (routing via gomobile)

## 2. Implemented Architecture

```mermaid
graph TD
    subgraph "Android App (Kotlin)"
        UI[Jetpack Compose UI<br>ChatScreen, ContactList, QR, Settings]
        Service[GhostService<br>Foreground, WakeLock, BLE management]
        BLE[BleManager<br>BLE 5.0 advertising, scanning, GATT]
        Room[Room Database<br>Contacts, Messages, Indexes added]
    end

    subgraph "Rust (JNI)"
        Crypto[ghost-crypto<br>Ed25519, X25519, AES-256-GCM]
    end

    subgraph "Go (gomobile)"
        Router[GhostRouter<br>Spray-and-Wait, BoltDB]
    end

    UI <--> Service
    Service <--> BLE
    Service <--> Room
    UI --> Crypto
    Service --> Crypto
    Service <--> Router
    BLE -.->|"BLE 5.0 GATT (10s timeout)"| BLE
```

## 3. FFI Boundaries

```mermaid
graph LR
    Kotlin[Kotlin<br>Android App + Service + BLE]
    Rust[Rust<br>ghost-crypto crate<br>Ed25519, X25519, AES-256-GCM]
    Go[Go<br>ghostrouter package<br>Spray-and-Wait, BoltDB]

    Kotlin -- "JNI (libghost_crypto.so)" --> Rust
    Kotlin -- "gomobile (ghostrouter.aar)" --> Go
    Rust -. "No direct connection" .- Go
```

## 4. Message Send Flow (v0.1.5)

```mermaid
sequenceDiagram
    participant User
    participant UI as ChatScreen (Kotlin)
    participant Crypto as ghost-crypto (Rust JNI)
    participant Router as GhostRouter (Go)
    participant BLE as BleManager (Kotlin)

    User->>UI: Type message, tap Send
    UI->>Crypto: encrypt(x25519_pub, payload)
    Note over Crypto: payload = ed25519_pub(32) + "username\0message" + ed25519_sig(64)
    Crypto-->>UI: ciphertext
    UI->>Router: sendMessage(dstId, ciphertext)
    alt Destination seen <60s ago
        Router-->>UI: (isDirect=true, routedBlob)
        UI->>BLE: sendMessage(bleAddress, blob)
        BLE-->>UI: success/failure
        Note over UI: ✓ SENT or ⚠ FAILED
    else Destination not reachable
        Router-->>UI: (isDirect=false, null)
        Note over Router: Store in BoltDB (copies=4)
        Note over UI: 📡 SPRAYED
    end
```

## 5. Message Receive Flow

```mermaid
sequenceDiagram
    participant BLE as BleManager (Kotlin)
    participant Service as GhostService (Kotlin)
    participant Router as GhostRouter (Go)
    participant Crypto as ghost-crypto (Rust JNI)
    participant DB as Room Database

    BLE->>Service: incomingMessages.collect(data)
    Service->>Router: onMessageReceived(data)
    alt Message for us (dst matches localId)
        Router-->>Service: "delivered" (via DeliverHandler.onDeliver(senderId))
        Service->>Crypto: decrypt(x25519_secret, ciphertext)
        Crypto-->>Service: plaintext = ed25519_pub + "name\0text" + signature
        Service->>Crypto: verify(ed25519_pub, data, signature)
        Service->>DB: messageDao.insert(message)
        Note over Service: Update contact name if changed
    else Message for someone else
        Router-->>Service: "forwarded"
        Note over Router: Store in BoltDB for relay spraying
    else Routing header decode fails
        Router-->>Service: "error: ..."
        Service->>Crypto: directDecryptAndSave(data) [fallback]
    end
```

## 6. Component Summary

| Component | Language | Size | Purpose |
|---|---|---|---|
| `android/app/` | Kotlin | ~4,000 LOC | UI (Compose), BLE (GATT), Room DB, Service |
| `rust/ghost-crypto/` | Rust | ~300 LOC | Ed25519 sign/verify, X25519 DH, AES-256-GCM encrypt/decrypt |
| `go/ghostrouter/` | Go | ~800 LOC | Spray-and-Wait routing, BoltDB persistence, wire format |

## 7. Key Data Structures

| Structure | Format |
|---|---|
| Identity blob | `ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)` = 128 bytes |
| QR payload | `GHOST:<Base64(ed25519_pub + x25519_pub + name_utf8)>` |
| Contact ID | `SHA-256(ed25519_pub).take(8).toHex()` → 16-char hex string |
| BLE fingerprint | `SHA-256(ed25519_pub).take(4)` → 4 bytes in scan response |
| Router peer ID | `SHA-256(ed25519_pub)` → 32 raw bytes |
| Message ID | `computeMessageID(payload + random_nonce)` → collision-resistant |
| Encrypted payload | `[ed25519_pub(32)] [username\0message] [ed25519_sig(64)]` → encrypted with X25519+AES-256-GCM |
| Routing wire format | `[4B headerLen][JSON RoutingHeader][encrypted payload]` |

## 8. Deployment Model
- Single debug APK (~46 MB, includes arm64 + x86_64 native libs)
- **No Google Play Services** dependencies
- Can be sideloaded via USB, file share, or direct APK transfer
- Target: Android 8.0+ (API 26), 1GB RAM, no internet required
