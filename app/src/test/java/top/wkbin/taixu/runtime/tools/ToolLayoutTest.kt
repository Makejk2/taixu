package top.wkbin.taixu.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLayoutTest {
    @Test
    fun keepsProgramDataAndCommandEntrypointsSeparated() {
        assertEquals("/opt/taixu/tools/openclaw", ToolLayout.toolDirectory("openclaw"))
        assertEquals("/opt/taixu/data/openclaw", ToolLayout.toolDataDirectory("openclaw"))
        assertEquals("/opt/taixu/bin/openclaw", ToolLayout.commandPath("openclaw"))
    }
}
