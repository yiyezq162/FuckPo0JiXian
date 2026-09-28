package app.fuckpo0jixian.core

/** A credential store and state file cannot commit atomically. Revoke authority durably first. */
object AccountCredentials {
    fun save(store: StateStore, value: String, read: () -> String?, write: (String) -> Unit): State {
        val token = value.trim()
        require(token.matches(Regex("pgnfw_[A-Za-z0-9_-]+")))
        val previous = store.load()
        val same = runCatching { read() }.getOrNull() == token
        val safe = if (same) previous.copy(paused = true, lastSuccess = 0, serverNotBefore = previous.serverDeadline()) else
            State(paused = true, nextAllowed = previous.nextAllowed, serverNotBefore = previous.serverDeadline(),
                nextProbeAllowed = previous.nextProbeAllowed, deviceName = previous.deviceName,
                fallbackMinutes = previous.fallbackMinutes, status = "TOKEN_CHANGE_PENDING")
        // Failure here must leave the credential untouched. Failure/termination after here is safe on restart.
        store.save(safe)
        if (!same) write(token)
        return safe.copy(authBlocked = false, status = "TOKEN_SAVED")
    }
}
