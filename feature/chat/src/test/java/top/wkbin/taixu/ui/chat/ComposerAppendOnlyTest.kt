package top.wkbin.taixu.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只发不答极性：短按推理，长按只追加；斜杠命令不因长按改走 appendOnly。 */
class ComposerAppendOnlyTest {

    @Test
    fun `short press sends for inference`() {
        assertFalse(shouldAppendOnly(answer = true, rawText = "帮我看一下构建"))
    }

    @Test
    fun `long press appends without inference`() {
        assertTrue(shouldAppendOnly(answer = false, rawText = "帮我看一下构建"))
        assertTrue(shouldAppendOnly(answer = false, rawText = ""))
    }

    @Test
    fun `workflow command stays a normal send`() {
        assertFalse(shouldAppendOnly(answer = false, rawText = "/wf"))
        assertFalse(shouldAppendOnly(answer = false, rawText = "/wf demo"))
        assertFalse(shouldAppendOnly(answer = true, rawText = "/wf demo"))
    }

    @Test
    fun `btw command stays a normal send`() {
        assertFalse(shouldAppendOnly(answer = false, rawText = "/btw 顺便问一句"))
        assertFalse(shouldAppendOnly(answer = true, rawText = "/btw 顺便问一句"))
        assertTrue(shouldAppendOnly(answer = false, rawText = "/btwnot a command"))
    }
}
