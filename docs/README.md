# GHOST Protocol — Technical Documentation Index

> **Version:** `v0.4.3`  
> **Master Index:** Authoritative technical specifications, mathematical foundations, architectural diagrams, API references, testing frameworks, and audit reports.

---

## Directory Organization

```text
docs/
├── whitepaper.md              # Academic & protocol foundation paper
├── architecture/              # System topologies, budgets, and state machines
├── security/                  # Threat models, physical reality analysis, and OS defenses
├── testing/                   # 4-Stage simulation hierarchy and torture frameworks
├── releases/                  # Version milestone status reports and roadmaps
├── engineering/               # Subsystem ownership model, boundaries, and governance
├── api/                       # Public API specifications (Kotlin, Go, Rust)
├── rfc/                       # Formal Layer 1–7 RFC specifications
└── assets/                    # Brand identity assets, logos, and generator
```

---

## 1. Core Architecture & Security

- [`architecture/system-diagram.md`](architecture/system-diagram.md): Complete end-to-end system architecture, `GattOperationQueue` FIFO flow, and 4-tier verification hierarchy.
- [`architecture/performance-budgets.md`](architecture/performance-budgets.md): Physical frame times (<16.6ms), perceived send ACK (<1ms), battery depletion curves (0.2–2.0%/hr), and memory limits.
- [`security/threat-model.md`](security/threat-model.md): Comprehensive evaluation of passive RF sniffers, active direction finders, malicious carrier relays, and hostile Android OEM task-killers.
- [`whitepaper.md`](whitepaper.md): Primary protocol paper detailing mathematical foundations, delay-tolerant routing, and empirical results.

---

## 2. Simulation & Verification Framework

- [`testing/simulation.md`](testing/simulation.md): Architecture of the 4-Stage Simulation Suite (`sim`, `torture`, `ux`, `oem`) and OEM runtime invariants ($O_1..O_{24}$).
- [`testing/torture-testing.md`](testing/torture-testing.md): Extreme Mesh Torture Engine specification, 15 routing invariants ($I_1..I_{15}$), edge-case hardening ($I_6, I_7$), and delta-debugging shrinker.

---

## 3. Developer & API References

- [`api/kotlin-ffi-bridge.md`](api/kotlin-ffi-bridge.md): Android Kotlin FFI coordination, `GattOperationQueue`, and Room atomic status guards.
- [`api/go-package-api.md`](api/go-package-api.md): Go packages: `ghostrouter`, `sim`, `sim/torture`, `sim/ux`, and `sim/oem`.
- [`api/rust-crate-api.md`](api/rust-crate-api.md): Pure Rust cryptographic core (`*_core`), JNI panic unwinding (`std::panic::catch_unwind`), and native test suite.

---

## 4. Formal Protocol RFCs (Layer 1–7)

| RFC | Title | Layer | Implementation Status |
|---|---|---|---|
| [`rfc/rfc-001-physics.md`](rfc/rfc-001-physics.md) | Physics Layer | Layer 1 | Omnimodal transport specification (BLE, Acoustic, Optical). |
| [`rfc/rfc-002-privacy.md`](rfc/rfc-002-privacy.md) | Privacy Layer | Layer 2 | Traffic morphing and constant-rate Poisson chaff generation. |
| [`rfc/rfc-003-transport.md`](rfc/rfc-003-transport.md) | Transport Layer | Layer 3 | Hybrid classical/post-quantum session key exchange. |
| [`rfc/rfc-004-routing.md`](rfc/rfc-004-routing.md) | Routing Layer | Layer 4 | Delay-tolerant Spray-and-Wait ($L=4$) and BoltDB carrier store. |
| [`rfc/rfc-005-identity.md`](rfc/rfc-005-identity.md) | Identity Layer | Layer 5 | Decentralized identity, QR exchange, and BIP-39 short codes. |
| [`rfc/rfc-006-economy.md`](rfc/rfc-006-economy.md) | Economy Layer | Layer 6 | Credit-based incentive system and reputation consensus. |
| [`rfc/rfc-007-application.md`](rfc/rfc-007-application.md) | Application Layer | Layer 7 | Jetpack Compose UI, optimistic pipeline, and OEM resilience. |

---

## 5. Releases & Milestone History

- [`releases/GHOST-v0.4-Status.md`](releases/GHOST-v0.4-Status.md): Milestone status for v0.4 (4-stage simulation verification, 120,000 scenarios).
- [`releases/GHOST-v0.3-Status.md`](releases/GHOST-v0.3-Status.md): Milestone status for v0.3 (Protest Mode, Cell Groups, Trust Web, Receipts).
- [`releases/GHOST-v0.3-Roadmap-ProtestMode.md`](releases/GHOST-v0.3-Roadmap-ProtestMode.md): Progress roadmap for v0.3 Protest Mode releases.
- [`releases/GHOST-v0.2-Status.md`](releases/GHOST-v0.2-Status.md): Milestone status for v0.2 (Physical power policy, GATT batching).
- [`releases/GHOST-v0.1-Status.md`](releases/GHOST-v0.1-Status.md): Milestone status for v0.1 (Foundational proof of concept).

---

## 6. Engineering & Governance

- [`engineering/repository-ownership.md`](engineering/repository-ownership.md): Subsystem ownership model, boundaries, and maintainer responsibilities.

