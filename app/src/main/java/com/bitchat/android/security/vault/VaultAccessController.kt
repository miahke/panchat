package com.bitchat.android.security.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VaultAccessState(val uiUnlocked: Boolean, val backgroundAllowed: Boolean)
internal class VaultPasswordAttempt internal constructor(internal val uiEpoch: Long, internal val keyEpoch: Long)

/**
 * User-selected background policy. UI hiding invalidates UI work but retains the already-open key.
 * A fresh controller owns a closed session. Application entry points must be wired separately;
 * this component alone is not an Activity gate, biometric verifier or a transport shutdown hook.
 */
internal class VaultAccessController {
    private val session = VaultKeySession()
    val records: VaultRecordCipher get() = session
    private var uiEpoch = 0L
    private val mutableState = MutableStateFlow(VaultAccessState(uiUnlocked = false, backgroundAllowed = false))
    val state = mutableState.asStateFlow()

    @Synchronized fun beginPasswordAttempt(): VaultPasswordAttempt {
        uiEpoch++
        mutableState.value = VaultAccessState(false, session.isUnlocked)
        return VaultPasswordAttempt(uiEpoch, session.generation)
    }

    /** Candidate must come from a successful VaultPersistence open, never directly from UI input. */
    @Synchronized fun completePassword(attempt: VaultPasswordAttempt, candidate: ByteArray): Boolean {
        try {
            if (attempt.uiEpoch != uiEpoch || attempt.keyEpoch != session.generation) return false
            val accepted = if (session.isUnlocked) session.matches(candidate, attempt.keyEpoch)
                else session.install(candidate, attempt.keyEpoch)
            if (!accepted) return false
            uiEpoch++ // One result authorizes one UI session.
            mutableState.value = VaultAccessState(uiUnlocked = true, backgroundAllowed = true)
            return true
        } finally { candidate.fill(0) }
    }

    @Synchronized fun hideInterface() {
        uiEpoch++
        mutableState.value = VaultAccessState(uiUnlocked = false, backgroundAllowed = session.isUnlocked)
    }

    /** Runtime must also stop transports/clear plaintext caches when observing this transition. */
    @Synchronized fun fullLock() {
        uiEpoch++
        session.lock()
        mutableState.value = VaultAccessState(uiUnlocked = false, backgroundAllowed = false)
    }

    override fun toString(): String = "VaultAccessController([redacted])"
}
