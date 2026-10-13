package app.domik.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlePermissionsTest {
    @Test
    fun `android 11 and older needs location`() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BlePermissions.scanPermissions(30))
        assertTrue(BlePermissions.needsLocationEnabled(30))
        assertTrue(BlePermissions.notificationPermissions(30).isEmpty())
    }

    @Test
    fun `android 12 and newer needs only bluetooth scan`() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), BlePermissions.scanPermissions(31))
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), BlePermissions.scanPermissions(36))
        assertFalse(BlePermissions.needsLocationEnabled(31))
    }

    @Test
    fun `notification permission from android 13`() {
        assertTrue(BlePermissions.notificationPermissions(32).isEmpty())
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), BlePermissions.notificationPermissions(33))
    }
}
