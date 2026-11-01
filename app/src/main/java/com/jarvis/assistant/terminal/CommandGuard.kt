package com.jarvis.assistant.terminal

import java.util.Locale
import java.util.regex.Pattern

sealed class CommandSafety {
    object Safe : CommandSafety()
    data class RequiresConfirmation(val prompt: String) : CommandSafety()
    data class Blocked(val reason: String) : CommandSafety()
}

/**
 * CommandGuard safety engine.
 * Inspects shell commands before execution to prevent destructive operations,
 * partition wiping, device bricking, or sensitive path manipulation.
 */
object CommandGuard {

    private val BLOCKED_PATTERNS = listOf(
        // Root / system wiping patterns
        Regex("""(?i)\brm\s+-[a-z0-9]*r[a-z0-9]*f?[a-z0-9]*\s+(/|/\*|~|/\w+/\.\.|/system|/data|/storage|/sdcard|/vendor|/apex)(\s|$)""") to
                "Destructive recursive deletion of system or storage root is strictly prohibited.",
        Regex("""(?i)\brm\s+-[a-z0-9]*f?[a-z0-9]*r[a-z0-9]*\s+(/|/\*|~|/\w+/\.\.|/system|/data|/storage|/sdcard|/vendor|/apex)(\s|$)""") to
                "Destructive recursive deletion of system or storage root is strictly prohibited.",
        // Device block writes and formatting
        Regex("""(?i)>\s*/dev/(block/|null\b|zero\b)?""") to
                "Direct write redirection to device files is blocked.",
        Regex("""(?i)\bdd\s+.*of=/dev/(block/.*|sd[a-z].*|mmcblk.*)""") to
                "Direct block device modification (dd) is strictly prohibited.",
        Regex("""(?i)\bmkfs(\.\w+)?\b""") to
                "Filesystem formatting commands (mkfs) are blocked.",
        Regex("""(?i)\bfdisk\b""") to
                "Disk partitioning commands (fdisk) are blocked.",
        // Fork bombs
        Regex("""(?i):\(\)\s*\{\s*:\|:&\s*\};:""") to
                "Fork bomb detected and blocked.",
        Regex("""(?i)\b(\w+)\(\)\s*\{\s*\1\s*\|\s*\1\s*&\s*\};\s*\1\b""") to
                "Fork bomb recursion detected and blocked.",
        // Dangerous remote pipe execution
        Regex("""(?i)\b(curl|wget)\s+[^|;]+?\|\s*(sh|bash|su)\b""") to
                "Piping remote internet scripts directly to a shell interpreter is blocked.",
        // Firmware wiping and recovery reboots
        Regex("""(?i)\bfastboot\s+(erase|format)\b""") to
                "Fastboot flash erase commands are blocked.",
        Regex("""(?i)\breboot\s+(recovery|bootloader|fastboot)\b""") to
                "Rebooting to recovery or bootloader is blocked."
    )

    private val CONFIRMATION_PATTERNS = listOf(
        Regex("""(?i)\brm\s+-[a-z0-9]*r[a-z0-9]*\b""") to
                "This command will recursively delete files or directories.",
        Regex("""(?i)\bchmod\s+(-R\s+)?(777|666)\b""") to
                "This command applies overly permissive file permissions.",
        Regex("""(?i)\b(kill\s+-9|killall|pkill)\b""") to
                "This command forcefully terminates running processes."
    )

    /**
     * Evaluates a command line for safety classification.
     */
    fun evaluate(rawCommand: String?): CommandSafety {
        val trimmed = rawCommand?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return CommandSafety.Safe
        }

        // Check blocked patterns first
        for ((regex, reason) in BLOCKED_PATTERNS) {
            if (regex.containsMatchIn(trimmed)) {
                return CommandSafety.Blocked(reason)
            }
        }

        // Check if command explicitly references writing to sensitive roots
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("rm -rf /") || lower.contains("rm -r /") || lower.contains("rm -fr /")) {
            return CommandSafety.Blocked("Destructive root file deletion is prohibited.")
        }

        // Check confirmation patterns
        for ((regex, warning) in CONFIRMATION_PATTERNS) {
            if (regex.containsMatchIn(trimmed)) {
                return CommandSafety.RequiresConfirmation(warning)
            }
        }

        return CommandSafety.Safe
    }

    /**
     * Sanitizes a command string for safe, non-sensitive logging.
     * Redacts API keys, credentials, or long numeric tokens.
     */
    fun sanitizeForLog(command: String?): String {
        if (command == null) return "<null>"
        var sanitized = command
        // Mask Gemini / Google API keys
        sanitized = sanitized.replace(Regex("""AIza[0-9A-Za-z_-]{35}"""), "[REDACTED_API_KEY]")
        // Mask generic token assignments
        sanitized = sanitized.replace(Regex("""(?i)(key|token|secret|password|pwd)=([^\s;&]+)"""), "$1=[REDACTED]")
        // Truncate to reasonable length
        return if (sanitized.length > 120) sanitized.take(120) + "..." else sanitized
    }
}
