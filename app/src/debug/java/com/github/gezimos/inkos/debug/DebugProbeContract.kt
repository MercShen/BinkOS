package com.github.gezimos.inkos.debug

/** Validate all extras before a probe can touch preferences or files. */
object DebugProbeContract {
    private val readCommands = setOf("params", "gestures", "checkwall")
    private val fileCommands = setOf("alias", "ziprestore", "wallpaper", "font", "file")
    private val boolCommands = readCommands + setOf("zipbackup", "dump")

    fun rejection(extras: Map<String, Any?>): String? {
        val known = fileCommands + boolCommands + setOf("clear", "confirm_write", "confirm_clear")
        if (extras.keys.any { it !in known }) return "Unknown probe parameter"
        for (name in boolCommands + setOf("clear", "confirm_write", "confirm_clear")) {
            if (extras.containsKey(name) && extras[name] !is Boolean) return "$name must be a boolean (--ez)"
        }
        for (name in fileCommands) {
            if (extras.containsKey(name) && (extras[name] !is String || (extras[name] as String).isBlank())) {
                return "$name must be a non-empty string (--es)"
            }
        }
        val commands = extras.keys.filter { it in fileCommands || (it in boolCommands && extras[it] == true) }
        if (commands.size != 1) return "Choose exactly one probe operation"
        val command = commands.single()
        if (extras.containsKey("clear") && command != "file") return "clear is supported only with file"
        if (command !in readCommands && extras["confirm_write"] != true) {
            return "Writes require --ez confirm_write true after authorization"
        }
        // ZIP restore clears prefs/files internally too, even without a separate clear extra.
        if ((command == "ziprestore" || extras["clear"] == true) && extras["confirm_clear"] != true) {
            return "Destructive restore requires --ez confirm_clear true and a verified snapshot"
        }
        return null
    }
}
