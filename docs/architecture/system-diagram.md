# GHOST Protocol System Architecture

> **Version:** v0.3.7 — includes Security Posture Engine, Nearby Discovery (0x10/0x11), 24h Rotating BIP-39 Codes (0x20–0x23), Cell Groups (0x30), Delivery Receipts (0x40), Contact Introductions (0x50), and Room Schema v9.  
> **Target Platform:** Android 8.0+ (API 26+), pure AOSP, zero Google Play Services.

---

## 1. System Overview

GHOST is an offline mesh communications system for Android devices. Devices communicate over Bluetooth Low Energy (BLE) 5.0. Cryptography is handled by a native Rust crate (`ghost-crypto`) over JNI. Multi-hop delay-tolerant mesh routing is handled by a native Go engine (`ghostrouter`) running BoltDB over gomobile.

Higher-level application orchestration — security postures, one-tap discovery, ephemeral code rotation, group messaging, delivery receipts, contact vouching, and battery-aware duty cycles — is handled in Kotlin.

```mermaid
graph TD
    subgraph "Android App Layer (Kotlin)"
        UI[Jetpack Compose UI<br>ChatScreen, GroupChatScreen, ContactList, IntroSheet, HUD]
        Service[GhostService<br>Foreground Service, WakeLock, 30s Policy Loop]
        Posture[SecurityPostureManager<br>NORMAL / PROTEST / EMERGENCY / STEALTH]
        Power[PowerPolicyEngine<br>ACTIVE / ECO / CRITICAL / DEEP_SLEEP]
        Discovery[DiscoveryManager<br>Opcode 0x10/0x11 Nearby Consent Handshake]
        ShortCode[ShortCodeManager<br>Opcode 0x20-0x23 24h Rotating BIP-39 Codes]
        GroupSend[GroupMessageSender<br>Pairwise Unicast Envelopes 0x30]
        GroupRecv[GroupMessageReceiver<br>Opcode 0x30 Demux, Ed25519 Verify, Dedup]
        Receipt[DeliveryReceiptHandler<br>Opcode 0x40 E2E Double Check ✓✓]
        Intro[IntroductionHandler<br>Opcode 0x50 One-Way Trust Vouching]
        BLE[BleManager<br>GATT Client/Server, MTU 512, Batch Writes]
        Room[Room DB v9<br>contacts, messages, groups, group_messages, telemetry]
    end

    subgraph "Rust Engine (JNI)"
        Crypto[ghost-crypto<br>Ed25519, X25519 ECDH, AES-256-GCM]
    end

    subgraph "Go Engine (gomobile)"
        Router[GhostRouter<br>Spray-and-Wait L=4, BoltDB, Relay Gate]
        Batch[Batch Serializer<br>EncodeBatch / DecodeBatch]
    end

    UI <--> Service
    Service --> Posture
    Service --> Power
    Service --> Discovery
    Service --> ShortCode
    Service --> GroupSend
    Service --> GroupRecv
    Service --> Receipt
    Service --> Intro
    Service <--> BLE
    Service <--> Room
    GroupSend --> Crypto
    GroupRecv --> Crypto
    Receipt --> Crypto
    Intro --> Crypto
    GroupSend <--> Router
    Service <--> Router
    Router --> Batch
    Service -.->|"setRelayWillingness(w)"| Router
    BLE -.->|"GATT Chained Writes"| BLE
```

---

## 2. FFI & Process Boundaries

Kotlin serves as the system coordinator. Rust and Go share zero address space and do not talk directly; all data passes through Kotlin as raw byte arrays:

```mermaid
graph LR
    Kotlin[Kotlin Host Layer<br>GhostService + BleManager]
    Rust[Rust Crate<br>ghost-crypto<br>Ed25519, X25519, AES-256-GCM]
    Go[Go Engine<br>ghostrouter<br>Spray-and-Wait, BoltDB]

    Kotlin -- "JNI (libghost_crypto.so)" --> Rust
    Kotlin -- "gomobile (ghostrouter.aar)" --> Go
    Rust -. "No Direct Connection" .- Go
```

### JNI Invariant
JNI buffers must be treated as transient. Data pointers passed into Go from Kotlin via gomobile can be freed immediately after the call returns. The Go router copies incoming byte slices via `make([]byte, len(src))` before persisting into BoltDB.

---

## 3. Wire Protocol Demuxing (Byte 0)

Incoming GATT write requests land in `BleManager.onCharacteristicWriteRequest`. Packets are demuxed by inspecting `data[0]`:

```
Byte 0 Value   Handler                    Routing Path
-----------------------------------------------------------------
0x10           DiscoveryManager           Kotlin-to-Kotlin direct
0x11           DiscoveryManager           Kotlin-to-Kotlin direct
0x20           ShortCodeManager           Kotlin-to-Kotlin direct
0x21           ShortCodeManager           Kotlin-to-Kotlin direct
0x22           GhostRouter (multi-hop)    Go Spray-and-Wait mesh
0x23           GhostRouter (multi-hop)    Go Spray-and-Wait mesh
0x30           GroupMessageReceiver       Kotlin unicast envelope
0x40           DeliveryReceiptHandler     Kotlin E2E delivery ack
0x50           IntroductionHandler        Kotlin vouching envelope
All other      GhostRouter (0x01)         Go Spray-and-Wait mesh
```

---

## 4. Message Flow Diagrams

### 4.1 1:1 Direct / Multi-Hop Message Send Flow

```mermaid
sequenceDiagram
    participant User
    participant UI as ChatScreen (Kotlin)
    participant Crypto as ghost-crypto (Rust)
    participant Router as GhostRouter (Go)
    participant BLE as BleManager (Kotlin)

    User->>UI: Types text, taps Send
    UI->>Crypto: encrypt(recipientX25519Pub, payload)
    Note over Crypto: payload = ed25519Pub(32) + "name\0msg" + ed25519Sig(64)
    Crypto-->>UI: ciphertext
    UI->>Router: sendMessage(dstPeerId, ciphertext)
    alt Recipient in direct range (<60s)
        Router-->>UI: (isDirect=true, blob)
        UI->>BLE: sendMessage(macAddress, blob)
        BLE-->>UI: Success / Queued
    else Recipient out of range
        Router-->>UI: (isDirect=false, null)
        Note over Router: Stored in BoltDB (copies=4)
        Note over UI: Marked STATUS_SPRAYED
    end
```

### 4.2 One-Tap Nearby Discovery Handshake (Opcodes 0x10 / 0x11)

```mermaid
sequenceDiagram
    autonumber
    participant A as Device A (Protest Mode)
    participant B as Device B (Protest Mode)

    Note over A,B: Device A detects B's 4-byte fingerprint in BLE advertisement
    A->>A: Checks 20s per-MAC rate limit
    A->>A: Displays notification: "GHOST User Nearby. Tap to Connect."
    A->>B: Writes Opcode 0x10 [0x10 || edPub_A || xPub_A || ts || name || sig_A]
    B->>B: Verifies sig_A with edPub_A
    B->>B: Displays notification: "Incoming contact request from A"
    B->>B: User taps "Accept"
    B->>A: Writes Opcode 0x11 [0x11 || edPub_B || xPub_B || ts || name || sig_B]
    A->>A: Verifies sig_B with edPub_B
    A->>A: Saves B to Room DB (isVerified = true)
    B->>B: Saves A to Room DB (isVerified = true)
    Note over A,B: Mutual contact link established in < 3 seconds
```

### 4.3 24-Hour Rotating BIP-39 Short Code Resolution (Opcodes 0x20 / 0x21)

```mermaid
sequenceDiagram
    autonumber
    participant A as Device A (Searcher)
    participant B as Device B (Target: "LION-COBALT-HARBOR-4821")

    A->>A: Computes targetCodeHash = SHA-256("LION-COBALT-HARBOR-4821")
    A->>B: Writes Opcode 0x20 [0x20 || targetCodeHash || edPub_A || sig_A]
    B->>B: Compares targetCodeHash to local active short code
    alt Code does not match
        Note over B: Silent drop. Zero radio emission (anti-probing defense)
    else Code matches
        B->>A: Writes Opcode 0x21 [0x21 || edPub_B || xPub_B || sig_B]
        A->>A: Verifies sig_B with edPub_B
        A->>A: Saves B as contact (isVerified = true)
        A->>A: Posts notification: "Short code match found!"
    end
```

### 4.4 Cell Group Fan-Out & Delivery (Opcode 0x30)

```mermaid
sequenceDiagram
    autonumber
    participant S as Sender (Group Creator)
    participant M1 as Member 1 (Direct Range)
    participant M2 as Member 2 (Relayed via Mesh)
    participant R as Intermediate Relay Node

    Note over S: User sends to Group "Squad Alpha" (3 members)
    S->>S: Encrypts wireText for Member 1 using X25519_M1
    S->>S: Encrypts wireText for Member 2 using X25519_M2
    
    Note over S,M1: Member 1 is in direct BLE range
    S->>M1: Direct GATT write: Opcode 0x30 Envelope 1 (~281 bytes)
    M1->>M1: Demux 0x30 -> Verifies sig -> Decrypts with X25519_secret_M1 -> Saves to group_messages
    
    Note over S,R: Member 2 is out of range
    S->>R: Go Router sprays Opcode 0x30 Envelope 2 to Carrier (L=2)
    Note over R: Carrier node cannot decrypt envelope (forwarded only)
    R->>M2: Carrier encounters Member 2 -> Delivers Envelope 2
    M2->>M2: Demux 0x30 -> Verifies sig -> Decrypts with X25519_secret_M2 -> Saves to group_messages
```

### 4.5 End-to-End Cryptographic Delivery Receipt (Opcode 0x40)

```mermaid
sequenceDiagram
    autonumber
    participant A as Alice (Sender)
    participant B as Bob (Recipient)

    Note over A: Alice sends 1:1 message with TS token
    A->>B: Delivers encrypted packet [TS\0timestamp\0plaintext]
    B->>B: Decrypts message -> Strips TS token -> Inserts into Room DB
    Note over B: First-delivery check passes (getByContentHash == null)
    B->>B: Computes contentHash = SHA-256(AliceId || ts || cleanPlaintext)
    B->>B: Signs contentHash with Ed25519 seed
    B->>A: Writes Opcode 0x40 [0x40 || hash(64B) || BobId(16B) || ts(8B) || sig(64B)]
    A->>A: Demux 0x40 -> Verifies Bob's Ed25519 signature
    A->>A: Looks up message by contentHash in Room DB
    A->>A: Updates status: STATUS_DELIVERED (2)
    Note over A: UI renders double checkmark (✓✓) in GhostPurple
```

### 4.6 Cryptographic Contact Introduction (Opcode 0x50)

```mermaid
sequenceDiagram
    autonumber
    participant A as Alice (Mutual Friend / Voucher)
    participant B as Bob (Target Peer)
    participant C as Carol (Recipient)

    Note over A: Alice opens Bob's verified profile -> "Introduce to..." -> Selects Carol
    A->>A: Signs Bob's keys: Ed25519(0x50 || BobKeys || BobName || CarolId || AliceId)
    A->>A: Packages Opcode 0x50 payload (147 + N bytes)
    A->>A: Encrypts to Carol's X25519 key -> Transmits via 1:1 mesh envelope
    C->>C: Decrypts outer envelope -> Demuxes Opcode 0x50
    C->>C: Verifies Alice is verified contact -> Verifies Alice's signature
    C->>C: Caches in memory (10m expiry) -> Displays notification
    C->>C: Carol taps review -> Displays IntroductionReviewBottomSheet
    Note over C: Shows Alice (Ghost Aura) + Bob (Slate avatar + INTRODUCED chip)
    C->>C: Carol taps "Add Contact"
    C->>C: Inserts Bob: isIntroduced = true, isVerified = false
    Note over C: Chat screen displays persistent one-way trust banner
    Note over B: Bob is NOT notified; receives zero keys from Carol
```

---

## 5. Security Posture State Machine

```mermaid
stateDiagram-v2
    [*] --> NORMAL
    NORMAL --> PROTEST: User selects Protest Mode
    PROTEST --> NORMAL: User disables or battery < 15%
    PROTEST --> EMERGENCY: User triggers Emergency
    EMERGENCY --> NORMAL: Battery < 15% or manual revert
    NORMAL --> STEALTH: User activates Radio Silence
    PROTEST --> STEALTH: User activates Radio Silence
    STEALTH --> NORMAL: User resumes standard operation

    state NORMAL {
        [*] --> NormalDuty
        NormalDuty: Scan 2000ms / Adv 500ms
        NormalDuty: Discovery disabled
    }

    state PROTEST {
        [*] --> ProtestDuty
        ProtestDuty: Scan 1000ms / Adv 200ms
        ProtestDuty: Background Discovery 0x10 enabled
    }

    state EMERGENCY {
        [*] --> ContinuousDuty
        ContinuousDuty: Scan 100ms continuous (100% duty)
        ContinuousDuty: 100ms advertising
    }

    state STEALTH {
        [*] --> PassiveOnly
        PassiveOnly: Advertising transmitter killed (0 mW)
        PassiveOnly: Passive scanner only (listen-only)
    }
```

---

## 6. Persistence Schema (Room Database v9)

```
+---------------------------------------------------------------------------------+
|                                 GHOST DATABASE (v9)                             |
+---------------------------------------------------------------------------------+

contacts
├── id: TEXT PRIMARY KEY (16-char hex)
├── name: TEXT
├── ed25519PubKey: TEXT (Base64)
├── x25519PubKey: TEXT (Base64)
├── bleAddress: TEXT (nullable)
├── isVerified: INTEGER (0 or 1)
├── isIntroduced: INTEGER (0 or 1, added in v9)
└── createdAt: INTEGER

messages
├── id: TEXT PRIMARY KEY (UUID)
├── contactId: TEXT (FK)
├── content: TEXT
├── isOutgoing: INTEGER
├── timestamp: INTEGER
├── isVerified: INTEGER
├── status: INTEGER (0=PEND, 1=SENT, 2=DELIV, 3=FAIL, 4=SPRAY)
├── replyToSender: TEXT (nullable)
├── replyToText: TEXT (nullable)
└── contentHash: TEXT (nullable, indexed, added in v8)

groups
├── groupId: TEXT PRIMARY KEY (64-char hex)
├── name: TEXT
├── creatorContactId: TEXT (16-char hex)
├── memberContactIdsJson: TEXT (JSON array string)
├── createdAt: INTEGER
└── isActive: INTEGER (0 or 1)

group_messages
├── id: INTEGER PRIMARY KEY AUTOINCREMENT
├── groupId: TEXT
├── senderContactId: TEXT
├── text: TEXT
├── timestamp: INTEGER
├── status: INTEGER (0=PEND, 1=SENT, 2=DELIV, 3=FAIL, 4=SPRAY)
├── replyToSender: TEXT (nullable)
├── replyToText: TEXT (nullable)
├── contentHash: TEXT (nullable, indexed, added in v8)
└── deliveredMemberIdsJson: TEXT (JSON array, added in v8)

telemetry_snapshots
├── id: INTEGER PRIMARY KEY AUTOINCREMENT
├── timestamp: INTEGER
├── batteryPercent: INTEGER
├── batteryTemperature: REAL
├── isCharging: INTEGER
├── bleScanTimeMs: INTEGER
├── bleAdvertiseTimeMs: INTEGER
├── gattConnections: INTEGER
├── gattBytesTx: INTEGER
├── gattBytesRx: INTEGER
├── cpuWakeups: INTEGER
├── messagesForwarded: INTEGER
├── messagesDelivered: INTEGER
├── avgDeliveryLatencyMs: INTEGER
├── currentMode: TEXT
└── peerCount: INTEGER
```
