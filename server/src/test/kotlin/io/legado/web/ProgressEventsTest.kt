package io.legado.web

import io.legado.web.service.ProgressEvent
import io.legado.web.service.ProgressEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressEventsTest {
    @Test
    fun noSubscribersSkipsPreferenceReadAndLookupFailureDoesNotEscape() {
        var reads = 0
        val events = ProgressEvents { reads++; error("unavailable") }
        val event = ProgressEvent(42, 2, 0, null, 1234, null)
        events.publish(1, event)
        assertEquals(0, reads)
        val sub = events.subscribe(1, null)
        try {
            events.publish(1, event)
            assertEquals(1, reads)
            assertNull(sub.channel.tryReceive().getOrNull())
        } finally { events.unsubscribe(sub) }
    }

    @Test
    fun progressReachesOnlyOtherClientsOfEnabledUser() {
        val events = ProgressEvents { it == 7001L }
        val sender = events.subscribe(7001, "sender")
        val receiver = events.subscribe(7001, "receiver")
        val other = events.subscribe(7002, "receiver")
        try {
            val event = ProgressEvent(42, 2, 3456, "chapter", 1234, "sender")
            events.publish(7001, event)
            assertEquals(event, receiver.channel.tryReceive().getOrNull())
            assertNull(sender.channel.tryReceive().getOrNull())
            assertNull(other.channel.tryReceive().getOrNull())
            events.publish(7002, event)
            assertNull(other.channel.tryReceive().getOrNull())
        } finally {
            listOf(sender, receiver, other).forEach(events::unsubscribe)
        }
    }
}
