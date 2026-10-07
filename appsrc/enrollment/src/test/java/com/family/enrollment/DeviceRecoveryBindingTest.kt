package com.family.enrollment

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceRecoveryBindingTest {
    private fun derive(id: String = "0123456789abcdef", role: String = "parent", signer: String = DeviceRecoveryBindingProvider.RELEASE_SIGNER) =
        DeviceRecoveryBindingProvider.derive(id, role, "com.family.$role", signer)
    @Test fun stableAcrossUidKeyAndVersionChanges() { assertEquals(derive(), derive()); assertEquals(43, derive().length) }
    @Test fun userDeviceRoleAndSignerAreSeparated() {
        assertNotEquals(derive(), derive("0123456789abcdee"))
        assertNotEquals(derive(), derive(role = "child"))
        assertNotEquals(derive(), derive(signer = "a".repeat(64)))
    }
    @Test fun malformedIdAndWrongPackageFailClosed() {
        for (id in listOf("", "unknown", "0000000000000000")) assertTrue(runCatching { derive(id) }.isFailure)
        assertTrue(runCatching { DeviceRecoveryBindingProvider.derive("0123456789abcdef", "parent", "com.family.child", DeviceRecoveryBindingProvider.RELEASE_SIGNER) }.isFailure)
    }
}
