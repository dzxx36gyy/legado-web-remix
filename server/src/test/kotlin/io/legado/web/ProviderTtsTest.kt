package io.legado.web

import com.google.gson.JsonParser
import io.legado.web.service.tts.ProviderTts
import io.legado.web.service.tts.ProviderVoiceDiscovery
import io.legado.web.service.tts.StoredTtsEngine
import okio.Buffer
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderTtsTest {
    @Test
    fun mimoChineseVoiceAndSeparatedMessagesFollowOfficialProtocol() {
        val config = ProviderTts.validate(ProviderTts.Config("xiaomi", "冰糖", "test-key"))
        val request = ProviderTts.xiaomiRequest(config, "正文<&", 1.0)
        val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url.toString())
        assertEquals("test-key", request.header("api-key"))
        assertEquals("mimo-v2.5-tts", body.get("model").asString)
        assertEquals("冰糖", body.getAsJsonObject("audio").get("voice").asString)
        val messages = body.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
        assertFalse(messages[0].asJsonObject.get("content").asString.contains("正文"))
        assertEquals("assistant", messages[1].asJsonObject.get("role").asString)
        assertEquals("正文<&", messages[1].asJsonObject.get("content").asString)
        assertFails { ProviderTts.validate(config.copy(voice = "default_zh")) }
    }

    @Test
    fun fishAlwaysSelectsValidatedModelInHeaderIncludingLegacyDefault() {
        val legacy = """{"provider":{"provider":"fishaudio","voice":"ref-1","apiKey":"encrypted","secretId":"","region":""}}"""
        val stored = StoredTtsEngine.parse(legacy) as StoredTtsEngine.Provider
        assertEquals("s2.1-pro-free", ProviderTts.fishRequest(stored.config, "text", 1.0).header("model"))
        listOf("s2.1-pro", "s2-pro", "s1", "drama-3").forEach { model ->
            val config = ProviderTts.validate(stored.config.copy(model = model))
            val request = ProviderTts.fishRequest(config, "text", 1.2)
            assertEquals(model, request.header("model"))
            assertEquals("https://api.fish.audio/v1/tts", request.url.toString())
            val body = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals("ref-1", body.get("reference_id").asString)
            assertEquals(1.2, body.getAsJsonObject("prosody").get("speed").asDouble)
        }
        assertFails { ProviderTts.validate(stored.config.copy(model = "unknown")) }
    }

    @Test
    fun azureSsmlEscapesTextAndUsesRegionalAuthenticatedMp3Protocol() {
        val c = ProviderTts.Config("azure", "zh-CN-XiaoxiaoNeural", "test-key", region = "eastus")
        val request = ProviderVoiceDiscovery.azureRequest(c, "<voice name=\"evil\">&'正文", 20)
        assertEquals("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1", request.url.toString())
        assertEquals("test-key", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("audio-24khz-48kbitrate-mono-mp3", request.header("X-Microsoft-OutputFormat"))
        assertTrue(request.body!!.contentType().toString().startsWith("application/ssml+xml"))
        val xml = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals(1, doc.getElementsByTagName("voice").length)
        assertEquals("<voice name=\"evil\">&'正文", doc.getElementsByTagName("prosody").item(0).textContent)
        assertEquals("20%", doc.getElementsByTagName("prosody").item(0).attributes.getNamedItem("rate").nodeValue)
        listOf("", "eastus.evil.com", "eastus/../", "user@eastus", "eastus:443", "EASTUS").forEach {
            assertFails { ProviderVoiceDiscovery.azureRequest(c.copy(region = it), "text", 0) }
        }
        assertFails { ProviderVoiceDiscovery.azureRequest(c.copy(voice = "x\"><evil"), "text", 0) }
    }

    @Test
    fun voiceDiscoveryUsesBoundedPublicFishPaginationAndAuthenticatedAzureList() {
        val fish = ProviderTts.Config("fishaudio", apiKey = "test-key")
        val request = ProviderVoiceDiscovery.request(fish, "中文 & title", 3, 100)
        assertEquals("api.fish.audio", request.url.host)
        assertEquals("/model", request.url.encodedPath)
        assertEquals("3", request.url.queryParameter("page_number"))
        assertEquals("100", request.url.queryParameter("page_size"))
        assertEquals("中文 & title", request.url.queryParameter("title"))
        assertEquals("false", request.url.queryParameter("self"))
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertFails { ProviderVoiceDiscovery.request(fish, "", 1, 101) }
        assertFails { ProviderVoiceDiscovery.request(fish, "", 0, 50) }
        val azure = ProviderVoiceDiscovery.request(ProviderTts.Config("azure", apiKey = "test-key", region = "westus"), "", 1, 50)
        assertEquals("https://westus.tts.speech.microsoft.com/cognitiveservices/voices/list", azure.url.toString())
        assertEquals("test-key", azure.header("Ocp-Apim-Subscription-Key"))
        val result = ProviderVoiceDiscovery.parse(ProviderTts.Provider.fishaudio, """{"items":[{"_id":"voice-id","title":"声音"}],"total":207}""", "", 3, 100)
        assertEquals("voice-id", result.voices.single().id)
        assertEquals(207, result.total)
        val list = """[{"ShortName":"zh-CN-XiaoxiaoNeural","DisplayName":"晓晓","Locale":"zh-CN","Gender":"Female"},{"ShortName":"en-US-JennyNeural","DisplayName":"Jenny","Locale":"en-US","Gender":"Female"}]"""
        val filtered = ProviderVoiceDiscovery.parse(ProviderTts.Provider.azure, list, "zh-CN", 1, 50)
        assertEquals("zh-CN-XiaoxiaoNeural", filtered.voices.single().id)
        assertEquals(1, filtered.total)
    }
}
