package com.shilapi.xcertplay.airplay

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
internal object AirPlayControlDiagnostics {
    fun openingFacts(connection: Int): Map<String, String> = mapOf(
        "connection" to "$connection", "requests" to "0", "lastRequest" to "none", "lastStatus" to "0",
        "rxAgoMs" to "-1", "txAgoMs" to "-1", "pairSetup" to "false", "pairVerified" to "false",
        "verifyOnly" to "false", "record" to "false", "streams" to "none")

    fun route(method: String, path: String): String {
        if (method in setOf("SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER")) return method.lowercase()
        return path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup", "feedback", "command") } ?: "other"
    }

    fun request(method: String, path: String, bytes: Int): String {
        val verb = method.takeIf { it in setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER") } ?: "OTHER"
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup") } ?: "other"
        return "airplay control request method=$verb route=$route contentBytes=$bytes"
    }
}
