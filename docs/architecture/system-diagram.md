# GHOST Protocol System Architecture

> **Version:** v0.2.0 — includes PowerPolicyEngine, BatteryTelemetry, and GATT message batching.
> For the full 7-layer vision, see `docs/rfc/rfc-001-physics.md` through `rfc-007-application.md`.

## 1. Overview

GHOST Protocol v0.2.0 is an offline mesh messenger for Android. Two phones discover each other over BLE 5.0, exchange Ed25519/X25519 keys via QR code, and send end-to-end encrypted text messages routed through a Go Spray-and-Wait router. Messages can hop through intermediate phones when sender and receiver are not in direct BLE range. 

v0.2.0 introduces a centralized **PowerPolicyEngine** (4 dynamic modes: ACTIVE, ECO, CRITICAL, DEEP_SLEEP), **Message Batching** over single GATT sessions with sequential write-chaining, **Relay Willingness Gating** to shed relay burdens on dying batteries, and **BatteryTelemetry** with SQLite snapshot logging and CSV export.

**Languages:** Kotlin (UI + BLE + Power + glue layer), Rust (crypto via JNI), Go (routing + batch serializer via gomobile)

## 2. Implemented Architecture

```mermaid
graph TD
    subgraph "Android App (Kotlin)"
        UI[Jetpack Compose UI<br>ChatScreen, ContactList, Settings, QR]
        Service[GhostService<br>Foreground, WakeLock, 30s Policy Loop]
        Power[PowerPolicyEngine<br>ACTIVE / ECO / CRITICAL / DEEP_SLEEP]
        Telem[BatteryTelemetry<br>Room DB v4, 7-day retention, CSV export]
        BLE[BleManager<br>BLE 5.0 adv/scan policy, GATT batching]
        Room[Room Database<br>Contacts, Messages, Telemetry]
    end

    subgraph "Rust (JNI)"
        Crypto[ghost-crypto<br>Ed25519, X25519, AES-256-GCM]
    end

    subgraph "Go (gomobile)"
        Router[GhostRouter<br>Spray-and-Wait, BoltDB, Relay Gate]
        Batch[Batch Serializer<br>EncodeBatch / DecodeBatch]
    end

    UI <--> Service
    Service --> Power
    Service --> Telem
    Telem --> Room
    Service <--> BLE
    Service <--> Room
    UI --> Crypto
    Service --> Crypto
    Service <--> Router
    Router --> Batch
    Service -.->|"setRelayWillingness(w)"| Router
    BLE -.->|"GATT Batch (MTU 512, chained writes)"| BLE
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
| `android/app/` | Kotlin | ~5,200 LOC | UI (Compose), BLE (GATT + batching), PowerPolicyEngine, BatteryTelemetry, Room DB (v4) |
| `rust/ghost-crypto/` | Rust | ~300 LOC | Ed25519 sign/verify, X25519 DH, AES-256-GCM encrypt/decrypt |
| `go/ghostrouter/` | Go | ~950 LOC | Spray-and-Wait routing, batch serializer, relay willingness gating, BoltDB store |

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
| Single wire format | `[4B headerLen][JSON RoutingHeader][encrypted payload]` |
| Batch wire format | `[1B count][4B len1][msg1][4B len2][msg2]...` |
| Telemetry record | `TelemetryEntity` in Room (`telemetry_snapshots`): 15 metrics, 7-day retention |

## 8. Deployment Model
- Single debug APK (~46 MB, includes arm64 + x86_64 native libs)
- **No Google Play Services** dependencies
- Can be sideloaded via USB, file share, or direct APK transfer
- Target: Android 8.0+ (API 26), 1GB RAM, no internet required
