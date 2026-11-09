package io.legado.web

import io.legado.web.service.WebDavService
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressFileNameTest {

    @Test
    fun matchesAppNaming() {
        assertEquals("斗罗_x.json", WebDavService.progressFileName("斗罗", "x"))
        assertEquals("a%20b_c.json", WebDavService.progressFileName("a b", "c"))
        // normalizeFileName 先把 / : 变成 _，之后才转义，所以不会出现 %2F %3A
        assertEquals("a_b_c_d.json", WebDavService.progressFileName("a/b", "c:d"))
        // % 必须最先转义，否则后面生成的 %xx 会被再次转义
        assertEquals("50%25%2B%26_%40me.json", WebDavService.progressFileName("50%+&", "@me"))
    }
}
