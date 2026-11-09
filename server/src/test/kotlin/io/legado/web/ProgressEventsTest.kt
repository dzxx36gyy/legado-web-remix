package io.legado.web

import io.legado.web.service.ProgressEvent
import io.legado.web.service.ProgressEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressEventsTest {
    @Test
    fun progressReachesOnlyOtherClientsOfEnabledUser() {
        val previous = ProgressEvents.loadUserEnabled
        ProgressEvents.loadUserEnabled = { it == 7001L }
        val sender = ProgressEvents.subscribe(7001, "sender")
        val receiver = ProgressEvents.subscribe(7001, "receiver")
        val other = ProgressEvents.subscribe(7002, "receiver")
        try {
            val event = ProgressEvent(42, 2, 3456, "chapter", 1234, "sender")
            ProgressEvents.publish(7001, event)
            assertEquals(event, receiver.channel.tryReceive().getOrNull())
            assertNull(sender.channel.tryReceive().getOrNull())
            assertNull(other.channel.tryReceive().getOrNull())
            ProgressEvents.publish(7002, event)
            assertNull(other.channel.tryReceive().getOrNull())
        } finally {
            listOf(sender, receiver, other).forEach(ProgressEvents::unsubscribe)
            ProgressEvents.loadUserEnabled = previous
        }
    }
}
