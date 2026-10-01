# SSH library decision

**Decided:** 2026-10-01. **Choice: ConnectBot sshlib 2.2.48.** sshj 0.41.1 is rejected. The spike (commits `66a9676`, `631ead0`) is deleted in the commit that adds this record; the raw run logs are in the docs vault under `herdr-android/evidence/phase-02-2026-10-01/spike-runs/`.

## Rule applied

The Phase 02 note: the winner is the library passing S1 to S6 with the smaller graph. Only sshlib passes S1 to S6 as specified, so the graph comparison does not arise. The numbers below are measured, not estimated.

## Matrix

Runs: API 26 (`sc-api26`) and API 36 (`sc-api36`) emulators, software-backed Keystore, against a throwaway loopback sshd run as the current user (`tools/test-sshd.sh`). Each library was run as a debug build on API 26, and as a minified, debug-signed R8 build (`-PspikeTest=r8test`) on API 26 and API 36. **Every cell below held in all of those runs unless stated** (S6 ranges are over those runs). The S3 per-connection log analysis was done on the debug runs. No physical phone was available.

| Test | sshlib 2.2.48 | sshj 0.41.1 (BouncyCastle forced to 1.86) |
| --- | --- | --- |
| S1 Keystore P-256 signing | **Pass.** A first-class hook: `authenticateWithPublicKey(user, SignatureProxy)`; the proxy signs with the Keystore handle and returns the SSH signature blob. sshd logged `Accepted publickey … ECDSA` | **Pass, with a workaround.** sshj's own ECDSA signer asks BouncyCastle to sign, which cannot use a Keystore handle. It needed a replacement `KeyAlgorithm` factory and `Signature` class, plus swapping Android's stripped `BC` provider for the full one at position 1 |
| S2 imported Ed25519, passphrase | **Pass** (`authenticateWithPublicKey(user, pem, passphrase)`) | **Pass** (`loadKeys(pem, null, PasswordFinder)`) |
| S3 host-key callback before auth | **Pass.** `connect(ServerHostKeyVerifier)` surfaces algorithm and key blob; rejecting aborts. Per-connection sshd log analysis: of 7 connections exactly one had no authentication line, and it ended `Connection closed … [preauth]` | **Pass.** Same log result; sshj's disconnect message additionally sends the host-key fingerprint (MD5 hex) to the server |
| S4 exec | **Pass.** Exit 3, `out`/`err` apart, 300 kB stdout intact | **Pass** |
| S5 three concurrent channels | **Pass,** 2006 to 2010 ms for three 2 s commands | **Pass,** 2008 to 2013 ms |
| S6 dead link within 30 s at a 15 s keepalive | **Pass, 24.88 to 24.91 s** across four runs. The library has no keepalive of its own; the adapter pings every 15 s with a 10 s reply timeout | **Fail, 30.031 to 30.036 s** across three runs. Its built-in runner gives up on the second unanswered 15 s tick (`maxAliveCount` 1), so the floor is 2 × 15 s plus scheduling. A shorter interval would pass; the note fixes 15 s |
| S7 release APK, R8 | **76,834 bytes** | **414,172 bytes** |
| S8 review | Package 100, no critical or high alert. Deep: overall 27, from `kotlin-stdlib` and `simplesocks` (medium `networkAccess`, `usesEval`, low `unmaintained`). **14 resolved components** | sshj itself 100. Published 0.41.1 pins BouncyCastle 1.84 with a **critical CVE and a high CVE** (deep overall 25). Needs a crypto-library substitution to be usable. **8 resolved components** after the override |

S6 used a TCP blackhole proxy (`tools/blackhole-proxy.py`) that stops relaying without closing sockets, as a deterministic stand-in for airplane mode. The real airplane-mode check stays a device item.

## Why sshlib

1. It is the only candidate that meets S1 to S6 as written, including the 15 s keepalive and 30 s detection requirement.
2. The Keystore signing hook is part of its public API; sshj needed signer-level replacement and a security-provider swap, which is the kind of code that breaks on a library update.
3. sshj as published fails the dependency review (critical CVE through BouncyCastle 1.84). It was usable only through a version override of a crypto library, which the review had to approve as an exception.
4. 77 kB against 414 kB after R8.

The smaller graph belongs to sshj (8 components against 14); that is the cost accepted. The extra components are `tink`, `jbcrypt`, `simplesocks`, `asia.hombre:kyber` with `keccak`, and the `kotlincrypto` error and random libraries.

## Requirements the production adapter inherits

- **Cipher list.** API 26 and 27 have no ChaCha20 provider and sshlib offers `chacha20-poly1305@openssh.com` whenever the server does; key exchange then fails after the host key is verified. Restrict to `aes256-gcm@openssh.com`, `aes128-gcm@openssh.com`, `aes256-ctr`, `aes128-ctr` (both directions).
- **Signing.** `Signature.getInstance("SHA256withECDSA")` with no provider name; naming `AndroidKeyStore` fails on API 26 for both libraries. The proxy returns `string("ecdsa-sha2-nistp256") || string(mpint r || mpint s)`, converted from the DER signature.
- **Keepalive.** Adapter-owned: ping on a dedicated thread every 15 s with a 10 s reply timeout; treat a timeout as link down. `Connection.ping()` holds the connection monitor while it waits.
- **Closing.** Because `ping()` can hold the monitor on a stalled link, `close()` blocks; close from a daemon thread and do not wait for it.
- **Exit status.** After reading the streams, wait for `EXIT_STATUS or CLOSED`, not EOF: EOF can arrive before the status and yields a null exit.
- **Host key.** Verify inside `connect(ServerHostKeyVerifier, …)`; it runs before any authentication.
- **R8.** The minified build passed S1 to S6 with only the adapter kept and `-dontwarn **`; the production rules must keep what the adapter reaches and be re-tested under R8.

## Not settled by the spike

- Keystore backing on a physical phone (S1, AC-02.2) and real airplane-mode detection (S6, AC-02.6): no device.
- (Resolved 2026-10-01: Hung Vo approved sshlib with its transitives for production, conditional on trying the `kyber` exclusion in the adapter slice; see `dependency-reviews.md`.) Originally: sshlib's transitives were to enter the production catalog only after their own review row: `kyber` is a single-maintainer post-quantum library, and the question is whether the build can drop it, and `tink`, without losing a needed key exchange. The spike approval covered the spike only.
- sshlib was last published 2026-06-01; the update cadence is unknown.

## Kyber exclusion (2026-10-01)

Condition from the production approval: try excluding `asia.hombre:kyber`, `keccak` and `org.kotlincrypto` under R8, then `tink`, against the transport suite. Run with `-PminifiedTest=true` (the debug variant through R8 with the production rules plus `proguard-test.pro`), API 26 and API 36, against the throwaway loopback OpenSSH 10.5.

| Configuration | Result |
| --- | --- |
| kyber present | 19 of 19; negotiated `mlkem768x25519-sha256`, `aes256-gcm@openssh.com`, host key `ssh-ed25519` |
| kyber, keccak, kotlincrypto excluded | 20 of 20 (with the new negotiation test); negotiated `curve25519-sha256`, same cipher and host key. sshlib falls back by itself when the ML-KEM classes are absent; no KEX list needs to be set |
| also `tink` excluded | Fails: `NoClassDefFoundError com.google.crypto.tink.subtle.X25519`. `tink` stays |

**Shipped: the exclusion.** Three libraries (`kyber`, `keccak`, and the two `kotlincrypto` artifacts, each with its `-jvm` variant) leave the release runtime graph; sshlib's subtree is then `tink`, `jbcrypt` and `simplesocks`. **What it costs:** the post-quantum hybrid `mlkem768x25519-sha256`, which OpenSSH 10 prefers by default, is no longer negotiated; sessions use `curve25519-sha256`. Traffic captured today could be decrypted later by an attacker with a quantum computer. For this app that traffic is herdr control data and agent prompts over a LAN or VPN, so the exposure is accepted, but it is a real downgrade rather than a free simplification. To revert, delete the three `exclude` lines in `app/build.gradle.kts`, drop the `-dontwarn asia.hombre.kyber.**` rule, regenerate the locks, and the negotiation test must then be relaxed to accept `mlkem768x25519-sha256`.

**R8 (production rules, `app/proguard-rules.pro`).** sshlib loads ciphers, digests, key exchanges and signature classes by name, so `com.trilead.ssh2.crypto.{cipher,digest,dh}` and `signature` are kept; without that the minified build fails at key exchange (`ClassNotFoundException AesGcm$AES256`). The rest of the library shrinks normally. `-dontwarn` covers the deliberately absent kyber classes and annotation-only classes.
