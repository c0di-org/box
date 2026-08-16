package dev.localagent.workstation.agent

/**
 * One harness Box can start inside the guest.
 *
 * The descriptor is product/UI metadata; [command] is the actual process agentd
 * launches. Keeping them together makes `harnessId` the only switch the backend
 * needs and prevents a task labelled as one agent from accidentally starting
 * another.
 */
internal data class HarnessRuntime(
    val descriptor: HarnessDescriptor,
    val command: Array<String>,
    /** Claude alone consumes Box's current Claude model and credential file. */
    val claudeEnvironment: Boolean = false,
)

internal val CLAUDE_RUNTIME = HarnessRuntime(
    descriptor = HarnessDescriptor(
        id = "claude-code",
        name = "Claude Code",
        command = "claude",
        mark = HarnessMarkKind.Burst,
    ),
    command = arrayOf(
        "/usr/bin/node",
        "/opt/local-agent/harness/box-claude-harness.mjs",
    ),
    claudeEnvironment = true,
)

internal val DEEPSEEK_RUNTIME = HarnessRuntime(
    descriptor = HarnessDescriptor(
        id = "deepseek-harness",
        name = "DeepSeek Harness",
        command = "dsh",
        mark = HarnessMarkKind.Knot,
    ),
    command = arrayOf(
        "/opt/local-agent/deepseek/node/bin/node",
        "/opt/local-agent/deepseek/app/box-deepseek-harness.mjs",
    ),
)

internal val CODEX_RUNTIME = HarnessRuntime(
    descriptor = HarnessDescriptor(
        id = "codex",
        name = "Codex",
        command = "codex",
        mark = HarnessMarkKind.Prism,
    ),
    command = arrayOf(
        "/usr/bin/node",
        "/opt/local-agent/codex/box-codex-harness.mjs",
    ),
)

/** Offered in this order: the existing Claude behavior remains the default. */
internal val INSTALLED_HARNESSES = listOf(CLAUDE_RUNTIME, DEEPSEEK_RUNTIME, CODEX_RUNTIME)

internal fun harnessRuntime(id: String): HarnessRuntime? =
    INSTALLED_HARNESSES.firstOrNull { it.descriptor.id == id }
