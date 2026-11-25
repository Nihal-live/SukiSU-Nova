package com.sukisu.nova.ui.screen.susfs.util

/**
 * Runtime verification for SUSFS settings.
 *
 * The SUSFS screen used to report `ConfigRuntimeStatus.UNVERIFIED`
 * ("Runtime state not verified") for every single apply, because the status was
 * hardcoded at the call site (`report()` in SuSFSViewModel defaults `status` to
 * UNVERIFIED and none of the call sites overrode it). Nothing ever read back
 * what the kernel/daemon actually stored.
 *
 * This object does the real read-back. It is deliberately pure: no Android, no
 * shell. The caller supplies two functions that read the daemon's persisted
 * config, so the logic here can be unit-tested against a stub of real
 * `ksud susfs config get/list --json` output.
 *
 * Wire format notes (verified against userspace/ksud/src/cli.rs:1729):
 *
 *   `ksud susfs config list --json`
 *       -> serde_json::to_string(&load_config())
 *       -> a single-line JSON OBJECT: {"key":"value",...}
 *
 *   `ksud susfs config get <key>`
 *       -> println!("{}", susfs_config::get(&key)?)
 *       -> the raw value, or an EMPTY line when the key is absent
 *         (store.rs:get -> unwrap_or_default())
 *
 *   `ksud susfs config set <key> <value>`
 *       -> writes the file, prints "ok"
 *
 * The read-back must therefore compare against the JSON object, not against the
 * `key=value` newline dump (that is what `list` without `--json` prints).
 */
object SuSFSRuntimeVerify {

    /** Result of a verification attempt. */
    sealed interface Outcome {
        /** The daemon's stored value matched what we asked for. */
        data object Verified : Outcome

        /**
         * The read succeeded but the value differs.
         * [stored] is what the daemon actually has (null = key absent).
         */
        data class Mismatch(val stored: String?) : Outcome

        /** The read itself failed; we genuinely cannot tell. Not an apply failure. */
        data class Unreadable(val reason: String?) : Outcome
    }

    /**
     * Parse `ksud susfs config list --json` output into a key -> value map.
     *
     * Kept dependency-light on purpose: the manager already ships org.json, but
     * this accepts the exact single-line object serde produces and tolerates
     * surrounding whitespace / stray log lines, because the daemon writes its
     * log lines to logcat rather than stdout but Caller.kt joins out+err in some
     * paths.
     */
    fun parseConfigListJson(raw: String): Map<String, String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyMap()
        // Take the outermost {...} so a stray log line or a trailing "ok" cannot break the parse.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val body = text.substring(start + 1, end)
        if (body.isBlank()) return emptyMap()

        val out = LinkedHashMap<String, String>()
        var i = 0
        fun skipWs() { while (i < body.length && body[i].isWhitespace()) i++ }
        while (i < body.length) {
            skipWs()
            if (i >= body.length) break
            if (body[i] != '"') { i++; continue }
            val key = readJsonString(body, i) ?: break
            i = key.second
            skipWs()
            if (i >= body.length || body[i] != ':') break
            i++
            skipWs()
            if (i >= body.length || body[i] != '"') {
                // Non-string JSON value: not something the SUSFS config store emits
                // (it is HashMap<String,String>), so skip to the next comma.
                while (i < body.length && body[i] != ',') i++
                if (i < body.length) i++
                continue
            }
            val value = readJsonString(body, i) ?: break
            i = value.second
            out[key.first] = value.first
            skipWs()
            if (i < body.length && body[i] == ',') i++
        }
        return out
    }

    /** Read a JSON string starting at [start] (which must be a quote). Returns value + next index. */
    private fun readJsonString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= s.length) return null
                    when (val esc = s[i + 1]) {
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '/' -> { sb.append('/'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 6 > s.length) return null
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar()); i += 6
                        }
                        else -> { sb.append(esc); i += 2 }
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    /**
     * Compare an expected boolean against what the daemon stored.
     *
     * SUSFS stores booleans as the strings "true"/"false" (store.rs
     * reset_to_defaults uses those literals), so "1"/"0" is accepted too in case
     * a future daemon normalises differently.
     */
    fun verifyBool(expected: Boolean, storedRaw: String?): Outcome {
        if (storedRaw == null) return Outcome.Mismatch(null)
        return when (storedRaw.trim().lowercase()) {
            "" -> Outcome.Mismatch(null)
            "true", "1" -> if (expected) Outcome.Verified else Outcome.Mismatch(storedRaw)
            "false", "0" -> if (!expected) Outcome.Verified else Outcome.Mismatch(storedRaw)
            else -> Outcome.Mismatch(storedRaw)
        }
    }

    /**
     * Compare an expected string against what the daemon stored.
     *
     * Used for uname/build-time and the comma-joined UID filter lists. The
     * comparison trims both sides because `ksud config set` round-trips the value
     * verbatim but the daemon prints it with println!, which appends a newline.
     */
    fun verifyString(expected: String, storedRaw: String?): Outcome {
        if (storedRaw == null) return Outcome.Mismatch(null)
        val stored = storedRaw.trim()
        return if (stored == expected.trim()) Outcome.Verified else Outcome.Mismatch(storedRaw)
    }

    /**
     * Compare an expected multi-value set against the daemon's single string.
     *
     * The manager joins sets with [separator] before writing (see
     * SuSFSConfig.setMulti), so the read-back must split on the same separator
     * and compare as a set: order is not meaningful and must not cause a false
     * mismatch.
     */
    fun verifyMulti(expected: Set<String>, storedRaw: String?, separator: String): Outcome {
        if (storedRaw == null) return Outcome.Mismatch(null)
        val stored = storedRaw.trim()
        val storedSet = if (stored.isEmpty()) emptySet()
        else stored.split(separator).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val expectedSet = expected.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (storedSet == expectedSet) Outcome.Verified else Outcome.Mismatch(storedRaw)
    }

    /**
     * Map an [Outcome] to a UI status.
     *
     * Key point: [Outcome.Unreadable] must NOT produce UNVERIFIED, because the
     * apply itself already succeeded and the user is not being lied to by the
     * write. It maps to UNVERIFIED only when we truly could not read at all,
     * which is the only honest meaning of "not verified".
     */
    fun toStatus(outcome: Outcome, supported: Boolean? = true): com.sukisu.nova.ui.component.ConfigRuntimeStatus =
        when {
            supported == false -> com.sukisu.nova.ui.component.ConfigRuntimeStatus.UNSUPPORTED
            outcome is Outcome.Verified -> com.sukisu.nova.ui.component.ConfigRuntimeStatus.APPLIED
            outcome is Outcome.Mismatch -> com.sukisu.nova.ui.component.ConfigRuntimeStatus.MISMATCH
            else -> com.sukisu.nova.ui.component.ConfigRuntimeStatus.UNVERIFIED
        }
}
