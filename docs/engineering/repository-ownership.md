# GHOST Protocol — Repository Ownership & Subsystem Boundaries

- **Document:** `docs/engineering/repository-ownership.md`  
- **Status:** Active Governance Model  
- **Version:** `v0.4.3`  
- **Date:** September 5, 2026  
- **Maintained by:** GHOST Protocol

---

## 1. Ownership Model Principles

To ensure architectural stability and clear boundaries for contributors, the GHOST Protocol codebase enforces explicit subsystem responsibilities. Every directory has a defined engineering domain, boundary invariants, and review requirements.

---

## 2. Subsystem Ownership Matrix

| Subsystem | Primary Path | Tech Stack | Responsible Domain | Key Invariants & Review Gates |
|---|---|---|---|---|
| **Android Application** | `android/` | Kotlin 2.0 / Jetpack Compose / Room v9 | Android Client & Lifecycle | Strict 48dp touch targets; sub-1ms optimistic send bubble; Room DB monotonic status progression ($U_2, U_{15}$); zero main-thread crypto/queries during scroll (60 FPS); pure AOSP compatibility. |
| **Mesh Router & Carrier Storage** | `go/ghostrouter/` | Go 1.22 / BoltDB / SQLite WAL | DTN Routing & Store | Bounded Spray-and-Wait ($L=4, \text{MaxHops}=10$); persistent deduplication survives reboots ($I_6$); battery relay load shedding ($I_7$); gomobile export boundary in root package. |
| **Simulation Hierarchy** | `go/ghostrouter/sim/` | Go 1.22 / Virtual Time Engine | Deterministic Verification | Discrete virtual clock (nanosecond precision); deterministic seeded scenarios; 120,000-scenario regression gate; zero external network access; zero data races. |
| **Cryptographic Core** | `rust/` | Rust 1.75 / `x25519-dalek` / `aes-gcm` / `ed25519-dalek` | Cryptographic Primitives & FFI | Pure Rust core decoupled from JNI; `std::panic::catch_unwind` on every JNI entrypoint; deterministic RFC 8032 signatures; 12 native host tests pass via `cargo test`. |
| **Protocol Standards & RFCs** | `docs/rfc/` | Markdown Specifications | Protocol Specification | Wire protocol opcode immutability; 512-byte ATT MTU packet budget; strict formal RFC revision workflow for any packet alteration. |
| **Security Architecture** | `docs/security/` | Security Specs | Security & Threat Modeling | Threat model maintenance; physical RF boundary accuracy; review of mobile OS and cryptographic invariants. |
| **Developer Tooling & Scripts** | `scripts/` | Bash / POSIX Shell | Build & Native Tooling | Self-contained, POSIX-compliant scripts; automated fallbacks for SDK/NDK/JDK environment variables; zero hardcoded user paths. |
| **Release & Governance** | Root (`README.md`, `CHANGELOG.md`, `SECURITY.md`, `LICENSE`) | Markdown / Open Source Governance | Project Maintenance | SemVer 2.0 compliance; complete CHANGELOG tracking; vulnerability response coordination. |

---

## 3. Subsystem Cross-Cutting Boundaries

```
[ Android Kotlin Layer ]
         │
         ├── (JNI) ──────────► [ Rust Cryptographic Core ] (libghost_crypto.so)
         │                     - Pure core (*_core)
         │                     - std::panic::catch_unwind boundary
         │
         └── (gomobile) ─────► [ Go Router & BoltDB Carrier ] (ghostrouter.aar)
                               - Spray-and-Wait (L=4)
                               - Persistent SQLite WAL Dedup (seen_packets)
                               ▲
                               │
               [ 4-Stage Simulation Suite ]
               - Stage 1: Deterministic Mesh Sim (sim/)
               - Stage 2: Extreme Mesh Torture (sim/torture/)
               - Stage 3: UX & State Machine (sim/ux/)
               - Stage 4: Android OEM Runtime Engine (sim/oem/)
```

---

## 4. Change Review & Gating Procedures

1. **Wire Protocol Alterations:** Any modification to opcodes (`0x01`, `0x10`, `0x11`, `0x20`–`0x23`, `0x30`, `0x31`, `0x40`, `0x50`) requires an RFC revision and maintainer approval.
2. **Cryptographic Primitives:** Any change in `rust/ghost-crypto/` requires security review and a passing `cargo test` verification.
3. **Routing & Invariant Changes:** Any modification to `router.go`, `store.go`, or `dedup.go` requires a full execution of the 100,000-scenario Stage 2 torture engine (`ghost-sim torture`) with 0 invariant violations.
4. **Android State Machine Changes:** Any alteration to `ChatViewModel`, `GhostService`, `MessageDao`, or `GattOperationQueue` requires running both the Stage 3 UX torture engine (`ghost-sim ux`) and Stage 4 OEM runtime engine (`ghost-sim oem`) with 0 violations.
