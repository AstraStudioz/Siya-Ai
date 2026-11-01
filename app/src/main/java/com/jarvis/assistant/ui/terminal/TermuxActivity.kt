package com.jarvis.assistant.ui.terminal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityTermuxBinding
import com.jarvis.assistant.network.GeminiRestClient
import com.jarvis.assistant.network.RestContent
import com.jarvis.assistant.network.RestPart
import com.jarvis.assistant.terminal.CommandGuard
import com.jarvis.assistant.terminal.CommandSafety
import com.jarvis.assistant.terminal.TerminalSession
import com.jarvis.assistant.util.PromptGenerator
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TermuxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTermuxBinding
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityTermuxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupHeader()
        setupTerminalEnvironment()
        setupQuickChips()
        setupInput()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearTerminal.setOnClickListener {
            binding.tvTerminalOutput.text = "Siya AI Terminal [Local Sandboxed Shell]\nType a command or tap quick actions below.\n$\n"
        }

        val hasTermux = TerminalSession.isTermuxInstalled(this)
        if (hasTermux) {
            binding.btnTermuxBridge.text = "Open Termux"
            binding.tvTerminalStatus.text = "Local Shell • Termux Ready"
            binding.btnTermuxBridge.setOnClickListener {
                TerminalSession.openTermux(this)
            }
        } else {
            binding.btnTermuxBridge.text = "Get Termux"
            binding.tvTerminalStatus.text = "Local Sandboxed Shell • Guard Active"
            binding.btnTermuxBridge.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases"))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupTerminalEnvironment() {
        val workingDir = filesDir.absolutePath
        val shortDir = if (workingDir.length > 25) "..." + workingDir.takeLast(22) else workingDir
        binding.tvGuardSummary.text = "Guard Active • Workspace: $shortDir"
    }

    private fun setupQuickChips() {
        binding.chipLs.setOnClickListener { runCommandDirectly("ls -la") }
        binding.chipUptime.setOnClickListener { runCommandDirectly("uptime") }
        binding.chipDf.setOnClickListener { runCommandDirectly("df -h") }
        binding.chipUname.setOnClickListener { runCommandDirectly("uname -a") }
        binding.chipWhoami.setOnClickListener { runCommandDirectly("whoami") }
        binding.chipEnv.setOnClickListener { runCommandDirectly("env") }

        binding.chipAskSiya.setOnClickListener {
            showAskSiyaDialog()
        }
    }

    private fun setupInput() {
        binding.btnRunCommand.setOnClickListener {
            val cmd = binding.etTerminalInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                handleCommandSubmission(cmd)
            }
        }

        binding.etTerminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val cmd = binding.etTerminalInput.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    handleCommandSubmission(cmd)
                }
                true
            } else {
                false
            }
        }
    }

    private fun runCommandDirectly(command: String) {
        binding.etTerminalInput.setText(command)
        binding.etTerminalInput.setSelection(command.length)
        handleCommandSubmission(command)
    }

    private fun handleCommandSubmission(command: String) {
        when (val safety = CommandGuard.evaluate(command)) {
            is CommandSafety.Blocked -> {
                appendOutput("\n$ $command\n[BLOCKED BY SIYA COMMANDGUARD]\nReason: ${safety.reason}\n$\n")
                Toast.makeText(this, "Command blocked for device security", Toast.LENGTH_SHORT).show()
                binding.etTerminalInput.setText("")
            }
            is CommandSafety.RequiresConfirmation -> {
                AlertDialog.Builder(this)
                    .setTitle("Confirmation Required")
                    .setMessage("${safety.prompt}\n\nCommand: $command\n\nProceed with execution?")
                    .setPositiveButton("Execute") { _, _ ->
                        executeCommandInternal(command)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            is CommandSafety.Safe -> {
                executeCommandInternal(command)
            }
        }
    }

    private fun executeCommandInternal(command: String) {
        commandHistory.add(command)
        historyIndex = commandHistory.size

        appendOutput("\n$ $command\n")
        binding.etTerminalInput.setText("")
        binding.pbTerminalExecuting.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = TerminalSession.executeCommand(this@TermuxActivity, command)
            binding.pbTerminalExecuting.visibility = View.GONE

            val output = result.combinedOutput
            if (output.isNotBlank()) {
                appendOutput(output + "\n")
            } else if (result.success) {
                appendOutput("[Completed successfully with exit code 0]\n")
            } else {
                appendOutput("[Failed with exit code ${result.exitCode}]\n")
            }
            appendOutput("$\n")
        }
    }

    private fun appendOutput(text: String) {
        binding.tvTerminalOutput.append(text)
        binding.scrollTerminal.post {
            binding.scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showAskSiyaDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Find all files modified today, or check storage"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            setBackgroundResource(R.drawable.bg_input_field)
            setPadding(36, 28, 36, 28)
        }

        AlertDialog.Builder(this)
            .setTitle("Ask Siya • Shell Helper")
            .setMessage("Describe what task or command you need. Siya will suggest a safe shell command.")
            .setView(input)
            .setPositiveButton("Suggest") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) {
                    consultSiyaForCommand(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun consultSiyaForCommand(query: String) {
        val apiKey = JarvisApp.instance.preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please configure your Gemini API key in Settings first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.pbTerminalExecuting.visibility = View.VISIBLE
        appendOutput("\n[Siya is analyzing command request: '$query'...]\n")

        lifecycleScope.launch {
            val systemPrompt = """
                ${PromptGenerator.GENDER_IDENTITY_BLOCK}
                You are Siya, an expert Linux and Android shell terminal assistant.
                When the user asks for a command, return ONLY the exact shell command on the first line, followed by a brief one-line explanation in English/Hindi.
                Never suggest destructive commands (rm -rf /, formatting, partition writes).
                Focus on commands that run in standard Android sh (/system/bin/sh) or Termux.
            """.trimIndent()

            val contents = listOf(
                RestContent(role = "user", parts = listOf(RestPart(text = query)))
            )

            var suggestionText = ""
            val result = withContext(Dispatchers.IO) {
                GeminiRestClient().streamGenerateContent(
                    apiKey = apiKey,
                    systemInstruction = systemPrompt,
                    contents = contents,
                    onChunk = { chunk, _ -> suggestionText = chunk }
                )
            }

            binding.pbTerminalExecuting.visibility = View.GONE
            result.onSuccess {
                val fullResponse = it.text.trim()
                appendOutput("[Siya Suggestion]:\n$fullResponse\n$\n")
                // Extract first command line if available
                val firstLine = fullResponse.lines().firstOrNull { l -> !l.startsWith("#") && l.isNotBlank() }
                    ?.removePrefix("$")
                    ?.removePrefix("```sh")
                    ?.removePrefix("```bash")
                    ?.removePrefix("```")
                    ?.removeSuffix("```")
                    ?.trim()
                if (!firstLine.isNullOrBlank()) {
                    binding.etTerminalInput.setText(firstLine)
                    binding.etTerminalInput.setSelection(firstLine.length)
                }
            }.onFailure {
                appendOutput("[Siya was unable to reach the neural engine. Please check your network or API key.]\n$\n")
            }
        }
    }
}
