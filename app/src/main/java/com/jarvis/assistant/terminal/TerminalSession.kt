package com.jarvis.assistant.terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isTimedOut: Boolean = false
) {
    val success: Boolean get() = exitCode == 0 && !isTimedOut
    val combinedOutput: String
        get() {
            val out = stdout.trim()
            val err = stderr.trim()
            return when {
                out.isNotEmpty() && err.isNotEmpty() -> "$out\n$err"
                out.isNotEmpty() -> out
                err.isNotEmpty() -> err
                else -> ""
            }
        }
}

object TerminalSession {

    private const val TAG = "TerminalSession"
    private const val MAX_OUTPUT_CHARS = 32_768
    private val ANSI_REGEX = Regex("""\u001B\[[;?0-9]*[a-zA-Z]""")

    /**
     * Executes a single command line in the local Android sandboxed shell (/system/bin/sh).
     */
    suspend fun executeCommand(
        context: Context,
        command: String,
        workingDir: File? = null,
        timeoutMs: Long = 15_000
    ): CommandResult = withContext(Dispatchers.IO) {
        val safeDir = workingDir?.takeIf { it.exists() && it.isDirectory }
            ?: File(context.filesDir, "terminal").apply { if (!exists()) mkdirs() }

        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(safeDir)

        val env = pb.environment()
        val existingPath = System.getenv("PATH") ?: "/system/bin:/system/xbin:/vendor/bin"
        env["PATH"] = "$existingPath:/apex/com.android.runtime/bin"
        env["HOME"] = safeDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["SHELL"] = "/system/bin/sh"
        env["TERM"] = "xterm-256color"

        try {
            val process = pb.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stdoutBuilder.length < MAX_OUTPUT_CHARS) {
                                stdoutBuilder.append(line).append("\n")
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stderrBuilder.length < MAX_OUTPUT_CHARS) {
                                stderrBuilder.append(line).append("\n")
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.join(200)
                stderrThread.join(200)
                return@withContext CommandResult(
                    exitCode = -1,
                    stdout = cleanAnsi(stdoutBuilder.toString()),
                    stderr = "Command timed out after ${timeoutMs / 1000}s",
                    isTimedOut = true
                )
            }

            stdoutThread.join(500)
            stderrThread.join(500)

            val exitCode = process.exitValue()
            CommandResult(
                exitCode = exitCode,
                stdout = cleanAnsi(stdoutBuilder.toString()),
                stderr = cleanAnsi(stderrBuilder.toString()),
                isTimedOut = false
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.localizedMessage ?: "Process execution failed",
                isTimedOut = false
            )
        }
    }

    /**
     * Strips ANSI escape sequences for clean Android UI presentation.
     */
    fun cleanAnsi(input: String): String {
        return input.replace(ANSI_REGEX, "")
    }

    /**
     * Checks if the Termux application is installed on device.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the Termux application if installed.
     */
    fun openTermux(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.termux")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Dispatches a command to Termux via the com.termux.RUN_COMMAND intent.
     */
    fun dispatchTermuxCommand(
        context: Context,
        command: String,
        workingDir: String? = null,
        inBackground: Boolean = false
    ): Boolean {
        if (!isTermuxInstalled(context)) return false
        return try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir ?: "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", inBackground)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch Termux command", e)
            false
        }
    }
}
