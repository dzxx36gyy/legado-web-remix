package io.legado.web

import io.legado.web.service.tts.EdgeTts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeTtsTest {

    /** 期望值由 edge-tts 的 drm.generate_sec_ms_gec_v2 生成 */
    @Test
    fun secMsGecMatchesReference() {
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            EdgeTts.secMsGec(0),
        )
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeTts.secMsGec(1700000000),
        )
        assertEquals(
            "4404CE4124108A51-9D8B24A039B7BDF487FCD8B8BF5FA5C1BAE53368325CCF467D2F888B39C50DEA",
            EdgeTts.secMsGec(1762000123),
        )
    }

    @Test
    fun voiceNames() {
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)",
            EdgeTts.fullVoiceName("zh-CN-XiaoxiaoNeural"),
        )
        assertEquals(
            "Microsoft Server Speech Text to Speech Voice (zh-CN-liaoning, XiaobeiNeural)",
            EdgeTts.fullVoiceName("zh-CN-liaoning-XiaobeiNeural"),
        )
        assertNull(EdgeTts.fullVoiceName("zh-CN-Xiao'xiao Neural"))
        assertNull(EdgeTts.fullVoiceName("501002"))
    }

    @Test
    fun splitKeepsEscapedPartsUnderLimit() {
        val parts = EdgeTts.splitText("甲<乙&".repeat(1000))
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.toByteArray().size <= 4096) }
        assertEquals("甲<乙&".repeat(1000), parts.joinToString("").replace("&lt;", "<").replace("&amp;", "&"))
    }

    @Test
    fun splitCountsEmojiAsFourBytes() {
        val text = String(Character.toChars(0x1F600)).repeat(2000)
        val parts = EdgeTts.splitText(text)
        parts.forEach { assertTrue(it.toByteArray().size <= 4096) }
        assertEquals(text, parts.joinToString(""))
    }
}
