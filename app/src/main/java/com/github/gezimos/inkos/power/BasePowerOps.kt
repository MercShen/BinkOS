package com.github.gezimos.inkos.power

/**
 * Base layer: everything reachable with `adb shell` (uid 2000) rights.
 *
 * Nothing here needs root, and nothing here touches the display pipeline - the
 * e-ink refresh mode stays owned by the vendor's e-ink centre. These commands
 * were verified as permitted for uid 2000 on a HiBreak B651 (3.4.6).
 */
object BasePowerOps {

    private const val DEFAULT_RADIOS = "cell,bluetooth,uwb,wifi,wimax"

    /** App-ops that stop an app doing work while the screen is off. */
    private val BACKGROUND_OPS = listOf(
        "RUN_ANY_IN_BACKGROUND",
        "RUN_IN_BACKGROUND",
        "WAKE_LOCK",
        "START_FOREGROUND"
    )

    fun build(targets: List<String>, whitelist: Set<String>): List<PowerOp> {
        val ops = mutableListOf<PowerOp>()
        ops.addAll(cellularRadio())
        ops.addAll(sensors())
        for (pkg in targets) {
            if (pkg in whitelist) continue
            ops.addAll(forPackage(pkg))
        }
        return ops
    }

    /**
     * Cellular off — with one hard-won ordering rule.
     *
     * This ROM's `BluetoothManagerService.enable()/disable()` throws a
     * NullPointerException (`BluetoothAirplaneModeListener` is null) while
     * airplane mode is on. Any Bluetooth toggle in that state kills the caller -
     * the vendor control centre and Settings both crash - so Bluetooth must be
     * switched off *before* airplane mode goes on, and back on only *after* it
     * goes off. Those two steps are marked `sequential` and run in a fixed order
     * instead of in the parallel batch.
     *
     * `airplane_mode_radios` is deliberately left alone: narrowing it to `cell`
     * is a persistent global write that survives uninstall, and it buys nothing
     * that this ordering does not already give.
     */
    private fun cellularRadio(): List<PowerOp> = listOf(
        PowerOp(
            key = "radio.bluetooth",
            probe = "dumpsys bluetooth_manager | grep -m1 '^  enabled'",
            pure = "cmd bluetooth_manager disable",
            sequential = true,
            normalFromProbe = { before ->
                // Only switch it back on if it was on to begin with.
                if (before?.contains("true") == true) "cmd bluetooth_manager enable" else null
            }
        ),
        PowerOp(
            key = "radio.cell",
            probe = "cmd connectivity airplane-mode",
            pure = "cmd connectivity airplane-mode enable",
            normal = "cmd connectivity airplane-mode disable",
            sequential = true
        )
    )
    /** Sensors that exist only to serve the phone side of the device. */
    private fun sensors(): List<PowerOp> = listOf(
        settingOp("sensor.auto_rotate", "system", "accelerometer_rotation", "0", "1"),
        settingOp("sensor.auto_brightness", "system", "screen_brightness_mode", "0", "1"),
        settingOp("sensor.tilt_wake", "secure", "wake_gesture_enabled", "0", "1"),
        settingOp("sensor.doze_tilt", "secure", "doze_tilt_to_wake", "0", "1"),
        settingOp("sensor.proximity", "system", "proximity_sensor", "0", "1")
    )

    private fun settingOp(
        key: String,
        namespace: String,
        name: String,
        pureValue: String,
        fallback: String
    ): PowerOp = PowerOp(
        key = key,
        probe = "settings get $namespace $name",
        pure = "settings put $namespace $name $pureValue",
        normalFromProbe = { before ->
            val value = before?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: fallback
            "settings put $namespace $name $value"
        }
    )

    /** Background restrictions for one package: bucket, doze whitelist, app-ops. */
    private fun forPackage(pkg: String): List<PowerOp> {
        val ops = mutableListOf<PowerOp>()

        ops += PowerOp(
            key = "bg.bucket.$pkg",
            probe = "am get-standby-bucket $pkg",
            pure = "am set-standby-bucket $pkg restricted",
            normalFromProbe = { before -> "am set-standby-bucket $pkg ${bucketName(before)}" }
        )

        ops += PowerOp(
            key = "bg.doze.$pkg",
            probe = "dumpsys deviceidle whitelist | grep $pkg",
            pure = "dumpsys deviceidle whitelist -$pkg",
            normalFromProbe = { before ->
                if (before.isNullOrBlank()) null else "dumpsys deviceidle whitelist +$pkg"
            }
        )

        for (op in BACKGROUND_OPS) {
            ops += PowerOp(
                key = "bg.appops.$pkg.$op",
                probe = "cmd appops get $pkg $op",
                pure = "cmd appops set $pkg $op ignore",
                normalFromProbe = { before -> "cmd appops set $pkg $op ${appOpsMode(before)}" }
            )
        }
        return ops
    }

    /** `am get-standby-bucket` prints the numeric bucket; `set-` wants the name. */
    private fun bucketName(raw: String?): String = when (raw?.trim()) {
        "5" -> "exempt"
        "10" -> "active"
        "20" -> "working_set"
        "30" -> "frequent"
        "40" -> "rare"
        "45" -> "restricted"
        "50" -> "never"
        else -> "active"
    }

    /**
     * `cmd appops get <pkg> <op>` prints either
     *   `RUN_ANY_IN_BACKGROUND: ignore`
     * or
     *   `No operations.\nDefault mode: allow`.
     * The token after the last `: ` is the mode in both shapes.
     */
    private fun appOpsMode(raw: String?): String {
        val text = raw?.trim().orEmpty()
        val idx = text.lastIndexOf(": ")
        val token = if (idx >= 0) text.substring(idx + 2).trim() else text
        return when {
            token.startsWith("ignore") -> "ignore"
            token.startsWith("allow") -> "allow"
            token.startsWith("deny") -> "deny"
            else -> "default"
        }
    }
}
