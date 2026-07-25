package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneCaptureOwnershipTest {
    @Test
    fun `resource is disposed exactly once when owner closes without a lease`() {
        var disposeCount = 0
        val owner = OwnedResource("frame") { disposeCount++ }

        owner.close()
        owner.close()

        assertEquals(1, disposeCount)
        assertNull(owner.valueOrNull())
        assertNull(owner.acquireLease())
    }

    @Test
    fun `mining lease keeps resource alive across popup close`() {
        var disposeCount = 0
        val owner = OwnedResource("frame") { disposeCount++ }
        val lease = owner.acquireLease()

        assertNotNull(lease)
        owner.close()

        assertEquals("frame", owner.valueOrNull())
        assertEquals(0, disposeCount)

        lease!!.close()
        lease.close()

        assertNull(owner.valueOrNull())
        assertEquals(1, disposeCount)
    }

    @Test
    fun `last of multiple leases performs the single disposal`() {
        var disposeCount = 0
        val owner = OwnedResource("frame") { disposeCount++ }
        val first = owner.acquireLease()!!
        val second = owner.acquireLease()!!

        owner.close()
        first.close()
        assertEquals(0, disposeCount)

        second.close()
        assertEquals(1, disposeCount)
    }
}
