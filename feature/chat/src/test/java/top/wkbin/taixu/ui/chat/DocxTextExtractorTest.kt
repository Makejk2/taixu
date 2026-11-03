package top.wkbin.taixu.ui.chat

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** DOCX 正文抽取：document.xml 定位、段落/换行/制表语义与 sidecar 落盘的契约测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxTextExtractorTest {

    private fun newDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.cacheDir.resolve("docx-test-${System.nanoTime()}").apply { mkdirs() }
    }

    private fun writeDocx(dir: File, documentXml: String): File {
        val file = dir.resolve("报告.docx")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("docProps/core.xml"))
            zip.write("<core/>".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `extracts paragraphs line breaks and tabs`() {
        val dir = newDir()
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段：项目结论</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段带换行</w:t><w:br/><w:t>续行</w:t><w:tab/><w:t>表格对齐</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val text = DocxTextExtractor.extract(writeDocx(dir, xml))!!
        assertEquals("第一段：项目结论\n第二段带换行\n续行\t表格对齐", text)
        dir.deleteRecursively()
    }

    @Test
    fun `sidecar text lands next to the docx`() {
        val dir = newDir()
        val docx = writeDocx(dir, """
            <w:document xmlns:w="http://x"><w:body><w:p><w:r><w:t>正文内容</w:t></w:r></w:p></w:body></w:document>
        """.trimIndent())
        val sidecar = DocxTextExtractor.writeSidecarText(docx)!!
        assertEquals("报告.docx.txt", sidecar.name)
        assertEquals("正文内容", sidecar.readText().trim())
        assertTrue(sidecar.parentFile == dir)
        dir.deleteRecursively()
    }

    @Test
    fun `non docx zip or broken archive returns null`() {
        val dir = newDir()
        val notDocx = dir.resolve("fake.docx")
        notDocx.writeText("plain text, not a zip")
        assertNull(DocxTextExtractor.extract(notDocx))
        assertNull(DocxTextExtractor.writeSidecarText(notDocx))
        dir.deleteRecursively()
    }
}
