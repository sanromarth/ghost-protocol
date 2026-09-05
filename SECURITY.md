# Security Policy

## Supported Versions

GHOST Protocol follows a security-hardened release cadence. Critical security patches are backported to the current and immediately preceding minor release.

| Version | Supported          | Security Audit Status |
| ------- | ------------------ | --------------------- |
| 0.4.x   | :white_check_mark: | Formally Verified (120,000 Scenarios) |
| 0.3.x   | :white_check_mark: | Verified on Physical Hardware |
| 0.2.x   | :x:                | Deprecated |
| 0.1.x   | :x:                | Deprecated |

---

## Reporting a Vulnerability

The GHOST Protocol team takes security vulnerabilities seriously. Because GHOST is designed for high-risk environments (human rights advocacy, internet blackouts, active conflict and protest zones), vulnerabilities can have severe physical safety implications.

### Disclosure Process
1. **Private Reporting:** If you discover a vulnerability, **do not open a public GitHub issue, pull request, or discussion thread**.
2. **Contact:** Email the security team directly at:
   ```text
   security@ghostprotocol.org
   ```
   *(Or contact the core repository maintainers via encrypted communication).*
3. **Information to Include:**
   - Detailed description of the attack vector, affected component (Kotlin, Go, Rust, BLE), and failure mode.
   - Proof-of-concept code, simulation seed, or step-by-step reproduction instructions.
   - Threat actor profile (Passive RF eavesdropper, active direction-finder, malicious relay carrier, hostile OEM OS, local physical extraction).
   - Any proposed mitigations or patches.

### Response Timelines
- **Initial Acknowledgment:** Within **24 hours**.
- **Severity Triage & Verification:** Within **72 hours**.
- **Remediation & Patch Release:** Within **14 days** for critical vulnerabilities, accompanied by a public security advisory.

---

## Security Invariants & Boundaries

When analyzing GHOST, please review our formal threat model in [`docs/security/threat-model.md`](docs/security/threat-model.md). Key boundaries:

1. **Content Confidentiality:** Payloads must remain encrypted end-to-end via X25519 ECDH and AES-256-GCM. Intermediate carrier nodes must never be capable of decrypting transit packets.
2. **Sender Integrity:** All messages, receipts, and handshakes must be authenticated via deterministic Ed25519 digital signatures.
3. **Replay Protection:** Senders and relays cannot inject duplicate messages without triggering deterministic signature rejection ($I_6$).
4. **RF Reality:** In `STEALTH` mode, devices are strictly listen-only (zero RF transmissions). In `NORMAL` and `PROTEST` modes, Bluetooth transmissions are physically detectable by directional RF equipment within radio line-of-sight.
