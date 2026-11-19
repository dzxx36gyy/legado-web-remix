package io.legado.web.local

import io.legado.web.i18n.Messages
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalImportMessagesTest {
    @Test
    fun translatesArchiveLimitsAndSkipReasons() {
        val english = Messages.Lang.EN
        assertEquals("The archive contains too many entries (maximum 2000)",
            Messages.translate("压缩包里的文件过多（最多 2000 个）", english))
        assertEquals("At most 50 books can be imported from an archive",
            Messages.translate("一个压缩包最多导入 50 本", english))
        assertEquals("Extract and upload PDF files separately",
            Messages.translate("压缩包里的 PDF 请解压后单独上传", english))
    }
}
