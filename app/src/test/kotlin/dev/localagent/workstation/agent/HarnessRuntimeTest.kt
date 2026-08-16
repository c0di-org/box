package dev.localagent.workstation.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRuntimeTest {
    @Test
    fun installedHarnessesKeepClaudeDefaultAndOfferDeepSeekThenCodex() {
        assertEquals(
            listOf("claude-code", "deepseek-harness", "codex"),
            INSTALLED_HARNESSES.map { it.descriptor.id },
        )
        assertEquals("Claude Code", INSTALLED_HARNESSES.first().descriptor.name)
        assertEquals("DeepSeek Harness", INSTALLED_HARNESSES[1].descriptor.name)
        assertEquals("Codex", INSTALLED_HARNESSES.last().descriptor.name)
    }

    @Test
    fun commandsUseIndependentRuntimes() {
        val claude = harnessRuntime("claude-code")!!
        val deepseek = harnessRuntime("deepseek-harness")!!
        val codex = harnessRuntime("codex")!!

        assertArrayEquals(
            arrayOf("/usr/bin/node", "/opt/local-agent/harness/box-claude-harness.mjs"),
            claude.command,
        )
        assertArrayEquals(
            arrayOf(
                "/opt/local-agent/deepseek/node/bin/node",
                "/opt/local-agent/deepseek/app/box-deepseek-harness.mjs",
            ),
            deepseek.command,
        )
        assertArrayEquals(
            arrayOf("/usr/bin/node", "/opt/local-agent/codex/box-codex-harness.mjs"),
            codex.command,
        )
        assertTrue(claude.claudeEnvironment)
        assertFalse(deepseek.claudeEnvironment)
        assertFalse(codex.claudeEnvironment)
        assertNull(harnessRuntime("not-installed"))
    }
}
