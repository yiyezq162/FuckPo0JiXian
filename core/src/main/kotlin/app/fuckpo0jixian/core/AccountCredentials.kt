package app.fuckpo0jixian.core

/** A credential store and state file cannot commit atomically. Revoke authority durably first. */
object AccountCredentials {
    /** [value] is what people pasted: a token keeps the current server, an official link brings its own. */
    fun save(store: StateStore, value: String, read: () -> String?, write: (String) -> Unit): State {
        val link = requireNotNull(Po0Credential.parse(value)) { "TOKEN_INVALID" }
        val token = link.token
        val previous = store.load()
        val endpoint = link.endpoint ?: previous.endpoint
        val same = endpoint == previous.endpoint && runCatching { read() }.getOrNull() == token
        val safe = if (same) previous.copy(paused = true, lastSuccess = 0, serverNotBefore = previous.serverDeadline()) else
            State(paused = true, nextAllowed = previous.nextAllowed, serverNotBefore = previous.serverDeadline(),
                nextProbeAllowed = previous.nextProbeAllowed, deviceName = previous.deviceName,
                fallbackMinutes = previous.fallbackMinutes, status = "TOKEN_CHANGE_PENDING", endpoint = endpoint)
        // Failure here must leave the credential untouched. Failure/termination after here is safe on restart.
        store.save(safe)
        if (!same) write(token)
        return safe.copy(authBlocked = false, status = "TOKEN_SAVED")
    }
}
