# GHOST Protocol System Architecture

> **Author:** PEDDI SANKARA RAO
> **Version:** v0.4.3 — includes Security Posture Engine, Nearby Discovery (0x10/0x11), 24h Rotating BIP-39 Codes (0x20–0x23), Cell Groups (0x30/0x31), Delivery Receipts (0x40), Contact Introductions (0x50), Serialized GATT Queue, Atomic Status Guard, Room Schema v9, and 4-Stage Adversarial Verification Architecture.
> **Target Platform:** Android 8.0+ (API 26+), pure AOSP, zero Google Play Services.

---

## 1. System Overview

GHOST is an offline mesh communications system for Android devices. Devices communicate over Bluetooth Low Energy (BLE) 5.0. Cryptography is handled by a native Rust crate (`ghost-crypto`) over JNI with `catch_unwind` panic boundaries. Multi-hop delay-tolerant mesh routing is handled by an embedded Go engine (`ghostrouter`) running BoltDB and persistent SQLite deduplication over gomobile.

Higher-level application orchestration — security postures, one-tap discovery, ephemeral code rotation, group messaging, delivery receipts, contact vouching, serialized GATT operations, atomic status immutability, and battery-aware duty cycles — is handled in Kotlin.

```mermaid
graph TD
    subgraph "Android App Layer (Kotlin)"
        UI[Jetpack Compose UI<br>ChatScreen, GroupChatScreen, ContactList, IntroSheet, HUD]
        VM[ChatViewModel<br>Sub-1ms Optimistic Bubble ACK]
        Repo[ConversationRepository<br>Off-Thread Joins & O(1) Peer Match]
        Service[GhostService<br>Foreground Service, WakeLock, 30s Policy Loop]
        Posture[SecurityPostureManager<br>NORMAL / PROTEST / EMERGENCY / STEALTH]
        Power[PowerPolicyEngine<br>ACTIVE / ECO / CRITICAL / DEEP_SLEEP]
        Discovery[DiscoveryManager<br>Opcode 0x10/0x11 Nearby Consent Handshake]
        ShortCode[ShortCodeManager<br>Opcode 0x20-0x23 24h Rotating BIP-39 Codes]
        GroupSend[GroupMessageSender<br>Pairwise Unicast Envelopes 0x30/0x31]
        GroupRecv[GroupMessageReceiver<br>Opcode 0x30 Demux, Ed25519 Verify, Dedup]
        Receipt[DeliveryReceiptHandler<br>Opcode 0x40 E2E Double Check ✓✓]
        Intro[IntroductionHandler<br>Opcode 0x50 One-Way Trust Vouching]
        GattQueue[GattOperationQueue<br>Serialized FIFO, 150ms Cool-off, Watchdog]
        BLE[BleManager<br>GATT Client/Server, MTU 512, Dual API 33+ Write]
        Room[Room DB v9<br>Atomic Status Guard AND status!=2 OR :status=2]
    end

    subgraph "Rust Engine (JNI)"
        Crypto[ghost-crypto<br>Ed25519, X25519 ECDH, AES-256-GCM<br>catch_unwind Panic Safety]
    end

    subgraph "Go Engine (gomobile)"
        Router[GhostRouter<br>Spray-and-Wait L=4, BoltDB, Relay Gate]
        Dedup[Persistent Dedup<br>SQLite Reboot Invariance]
        Batch[Batch Serializer<br>EncodeBatch / DecodeBatch]
    end

    UI <--> VM
    VM <--> Repo
    Repo <--> Room
    VM --> Service
    Service --> Posture
    Service --> Power
    Service --> Discovery
    Service --> ShortCode
    Service --> GroupSend
    Service --> GroupRecv
    Service --> Receipt
    Service --> Intro
    Service <--> BLE
    BLE <--> GattQueue
    Service <--> Room
    GroupSend --> Crypto
    GroupRecv --> Crypto
    Receipt --> Crypto
    Intro --> Crypto
    GroupSend <--> Router
    Service <--> Router
    Router <--> Dedup
    Router --> Batch
    Service -.->|"setRelayWillingness(w)"| Router
    GattQueue -.->|"Serialized Outbound Transmit"| BLE
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
0xFB           BleManager (Link Reassembly) Link-layer GATT reassembly
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

### 4.4 Cell Group Creation, Invite (Opcode 0x31) & Self-Healing Delivery (Opcode 0x30)

```mermaid
sequenceDiagram
    autonumber
    participant C as Creator (Alice)
    participant M1 as Member 1 (Direct Range)
    participant M2 as Member 2 (Offline / Relayed via Mesh)
    participant R as Intermediate Relay Node

    Note over C: Alice creates Cell Group "AVENGERS" in GroupCreationScreen
    C->>C: Inserts GroupEntity into Room DB & inserts system notice
    C->>C: Encrypts invite payload [INVITE\0AVENGERS\0membersJson] for M1 & M2
    C->>M1: Direct GATT write: Opcode 0x31 Invite Envelope (~261 bytes)
    M1->>M1: Demux 0x31 -> Verifies Alice is verified contact & checks Ed25519 sig
    M1->>M1: Decrypts with X25519 secret -> Inserts GroupEntity into Room DB
    M1->>M1: Posts high-priority invite notification & logs system welcome notice
    Note over M1: Group immediately appears in M1's Conversation List

    Note over C,M2: Member 2 is out of range during group creation (misses 0x31)
    C->>C: Alice sends group message: formatWirePayload(META, "Hi team")
    C->>R: Go Router sprays Opcode 0x30 Envelope to Relay (L=4)
    R->>M2: Relay encounters Member 2 -> Delivers Opcode 0x30 Envelope
    M2->>M2: Demux 0x30 -> Group missing in Room DB -> Decrypts envelope
    M2->>M2: Extracts self-healing META [AVENGERS, AliceId, membersJson]
    M2->>M2: Verifies membership & Alice's signature -> Inserts GroupEntity
    M2->>M2: Posts invite notification & inserts system welcome notice
    M2->>M2: Inserts group message -> Sends Opcode 0x40 Delivery Receipt to Alice
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

---

## 7. Compose UI & Data Flow Architecture (v0.3.8)

In v0.3.8, the UI layer was restructured to isolate state mutation, eliminate recomposition storms, and provide sub-millisecond perceived user acknowledgement:

```mermaid
graph TD
    subgraph "UI Composition Layer (Main Thread)"
        Screen[ChatScreen / ContactListScreen]
        FAB[GhostActionFab + NewChatBottomSheet]
        Bubble[Optimistic Message Bubble]
    end

    subgraph "State & Coordination Layer"
        VM[ChatViewModel<br>StateFlow&lt;List&lt;MessageUiModel&gt;&gt;]
        Repo[ConversationRepository<br>Flow&lt;List&lt;ConversationItem&gt;&gt;]
    end

    subgraph "Background Thread (Dispatchers.Default / IO)"
        JoinSort[Off-Thread Joins & Chronological Sorting]
        PeerIndex[O(1) Peer Fingerprint Index]
        AsyncPersist[Asynchronous Room Insert & Crypto Dispatch]
    end

    Screen -->|Tap Send| VM
    VM -->|Immediate Append| Bubble
    VM -->|Dispatch Job| AsyncPersist
    Repo -->|flowOn Dispatchers.Default| JoinSort
    JoinSort --> PeerIndex
    PeerIndex --> Repo
    Repo -->|StateFlow| Screen
    Screen --> FAB
```

### Invariants:
1. **Never Block on Persistence:** Tapping send immediately yields an in-memory `PENDING` bubble (<1ms perceived latency). Room insertion, X25519 key agreement, AES-256-GCM encryption, and GATT/Go routing execute asynchronously.
2. **Off-Thread Feed Joins:** `ConversationRepository` performs all contact-to-group merging, latest message mapping, and O(1) RF fingerprint matching off the main thread.
3. **Zero Main-Thread Cryptography in Lists:** No Base64 decoding or SHA-256 fingerprint generation occurs inside `LazyColumn` item renderers.
4. **Ergonomic Boundaries:** All interactive controls strictly enforce a 48dp minimum touch target (`GhostTheme.MinTouchTarget`).

---

## 8. Serialized GATT Operation Queue (`GattOperationQueue`)

To prevent multi-coroutine GATT controller overruns and chronic `GATT 133` disconnect loops on Android, all outbound client GATT operations flow through `GattOperationQueue`:

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> CONNECTING: Enqueue Outbound Transmit
    CONNECTING --> STABILIZING_LINK: onConnectionStateChange(CONNECTED)
    CONNECTING --> DISCONNECTING: onConnectionStateChange(DISCONNECTED / 133)
    CONNECTING --> DISCONNECTING: Watchdog Timeout (5000ms)
    STABILIZING_LINK --> NEGOTIATING_MTU: 100ms Link Delay (Avoid Controller Panic)
    NEGOTIATING_MTU --> DISCOVERING_SERVICES: onMtuChanged(N) or Fallback(23)
    DISCOVERING_SERVICES --> SLICING_PAYLOAD: onServicesDiscovered(GHOST_UUID)
    SLICING_PAYLOAD --> WRITING: payload <= MTU-3 (Unfragmented Raw)
    SLICING_PAYLOAD --> WRITING: payload > MTU-3 (0xFB Framed Chunks)
    WRITING --> WRITING: onCharacteristicWrite(Chunk N/Total)
    WRITING --> DISCONNECTING: All Chunks Written (SUCCESS)
    WRITING --> DISCONNECTING: Write Error / Disconnect
    DISCONNECTING --> CLOSED: gatt.close() + Record Disconnect Timestamp
    CLOSED --> IDLE: Trigger Next Item (After 150ms Per-MAC Cool-off)
```

### Key Invariants:
- **Strict Single Connection ($O_4$):** At most 1 active client GATT connection across all peers simultaneously.
- **150ms MAC-Level Cool-off:** Any reconnect to a recently disconnected MAC address is delayed until 150ms has elapsed.
- **Closed GATT Safety ($O_5$):** Stale or duplicate asynchronous callbacks arriving after teardown are safely ignored.
- **100ms Link Stabilization Delay:** Budget chipsets (MediaTek/Unisoc) reject `requestMtu(512)` or drop the connection if MTU negotiation is invoked immediately in `onConnectionStateChange`. Delaying 100ms stabilizes the L2CAP physical channel before issuing the request.
- **Dynamic MTU Slicing & Transport Framing (`0xFB`):** If MTU negotiation fails or defaults to 23 bytes (leaving 20 usable ATT bytes), payloads exceeding `negotiatedMtu - 3` are sliced into 7-byte framed fragments:
  `[0xFB][2B transferId][2B fragIndex][2B totalFrags][data...]`
  Payloads within `negotiatedMtu - 3` bypass framing entirely for 100% wire backward compatibility.
- **Bounded Reassembly State Machine:** Inbound `0xFB` fragments are reassembled in memory (`BleManager.kt`). Sessions are bounded to 16 concurrent peers, expire after 30 seconds of inactivity, and enforce a 64 KB total size ceiling. Out-of-order and duplicate fragments are handled idempotently. Nested `0xFB` fragments are dropped.
- **Scan Burst Decoupling:** BLE scan callbacks firing every 100–300ms previously cancelled in-flight group message retransmissions under `Flow.collectLatest`. Group retransmissions now run in independent child coroutine jobs using `Flow.collect`, debounced at 10 seconds per group.
- **Reciprocal QR Verification Queue:** When a peer QR code is scanned before their BLE advertisement has been observed by the local scanner, the outbound verification handshake is queued in memory and dispatched immediately upon peer discovery.

---

## 9. 4-Stage Simulation Verification Architecture

GHOST Protocol validation is organized as a multi-tier pipeline:

```mermaid
graph TD
    subgraph "Stage 1: Deterministic Mesh Simulator"
        S1[ghost-sim run<br>10–1000 Virtual Nodes<br>Canonical Scenarios 01–12]
    end

    subgraph "Stage 2: Extreme Mesh Torture Engine"
        S2[ghost-sim torture<br>100,000 Scenarios<br>Invariants I1–I15<br>Reboot Dedup & Relay Gating]
    end

    subgraph "Stage 3: UX & Pipeline Torture Engine"
        S3[ghost-sim ux<br>10,000 Scenarios<br>Invariants U1–U15<br>Atomic Room Status Guard]
    end

    subgraph "Stage 4: Android OEM Hell Engine"
        S4[ghost-sim oem<br>10,000 Scenarios across 7 Profiles<br>Invariants O1–O24<br>LMKD / Task Kill / Background Freeze]
    end

    subgraph "Stage 5: Physical Hardware Validation"
        S5[Physical Device Matrix<br>Pixel / Samsung / Xiaomi / OnePlus<br>PhysicalObservationTrace Bridge]
    end

    S1 --> S2
    S2 --> S3
    S3 --> S4
    S4 --> S5
```
