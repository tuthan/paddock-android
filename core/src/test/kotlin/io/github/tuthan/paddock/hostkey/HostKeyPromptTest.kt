package io.github.tuthan.paddock.hostkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostKeyPromptTest {
    private val ed = PresentedHostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
    private val ecdsa = PresentedHostKey("ecdsa-sha2-nistp256", ByteArray(40) { (it * 3).toByte() })

    @Test fun firstTrustShowsTheKeyTypeTheFingerprintAndTheCommandToCompare() {
        val p = HostKeyPrompts.firstTrust("10.0.0.2:22", ed)
        assertEquals("ED25519", p.algorithm)
        assertEquals(ed.fingerprint, p.fingerprint)
        assertTrue(p.fingerprint.startsWith("SHA256:"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub", p.compareCommand)
        assertEquals("10.0.0.2:22", p.endpoint)
    }

    @Test fun theCompareCommandNamesTheFileForTheKeyTypeThatWasPresented() {
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub", HostKeyPrompts.compareCommand("ecdsa-sha2-nistp384"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub", HostKeyPrompts.compareCommand("rsa-sha2-512"))
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub", HostKeyPrompts.compareCommand("something-new"))
    }

    @Test fun anUnknownAlgorithmIsShownAsItsWireNameNotHidden() = assertEquals("sk-ssh-ed25519@openssh.com", HostKeyPrompts.displayAlgorithm("sk-ssh-ed25519@openssh.com"))

    @Test fun changedShowsBothKeysAndWhenTheOldOneWasFirstTrusted() {
        val pin = PinnedHostKey("laptop", "10.0.0.2:22", ed.algorithm, ed.blob, ed.fingerprint, 1_000L, 5_000L)
        val p = HostKeyPrompts.changed("10.0.0.2:22", HostKeyState.Changed(pin, ecdsa))
        assertEquals(ed.fingerprint, p.oldFingerprint)
        assertEquals(ecdsa.fingerprint, p.newFingerprint)
        assertEquals("ED25519", p.oldAlgorithm)
        assertEquals("ECDSA P-256", p.newAlgorithm)
        assertEquals(1_000L, p.oldFirstSeenMillis)
        assertEquals("ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub", p.compareCommand)
    }
}
