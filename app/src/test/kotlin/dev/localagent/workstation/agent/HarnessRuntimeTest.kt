package dev.localagent.workstation.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRuntimeTest {
    @Test
    fun installedHarnessesKeepClaudeDefaultAndOfferDeepSeek() {
        assertEquals(listOf("claude-code", "deepseek-harness"), INSTALLED_HARNESSES.map { it.descriptor.id })
        assertEquals("Claude Code", INSTALLED_HARNESSES.first().descriptor.name)
        assertEquals("DeepSeek Harness", INSTALLED_HARNESSES.last().descriptor.name)
        assertNull(harnessRuntime("codex"))
    }

    @Test
    fun commandsUseIndependentRuntimes() {
        val claude = harnessRuntime("claude-code")!!
        val deepseek = harnessRuntime("deepseek-harness")!!

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
        assertTrue(claude.claudeEnvironment)
        assertFalse(deepseek.claudeEnvironment)
        assertNull(harnessRuntime("not-installed"))
    }

    @Test
    fun genericProductCapabilitiesStayOptIn() {
        assertFalse(harnessRuntime("claude-code")!!.descriptor.capabilities.hasSettings)
        assertFalse(harnessRuntime("deepseek-harness")!!.descriptor.capabilities.hasSettings)
    }
}
