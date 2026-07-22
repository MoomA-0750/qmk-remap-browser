package com.moomatechnica.qmkremap.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSelectionPolicyTest {

    private fun device(id: Int, name: String) =
        UsbHidDeviceInfo(deviceId = id, vendorId = 0xFEED, productId = 0x0001, productName = name)

    @Test
    fun `zero permitted devices returns empty`() {
        val decision = DeviceSelectionPolicy.decide(emptyList())

        assertTrue(decision is DeviceSelectionPolicy.Decision.ReturnEmpty)
    }

    @Test
    fun `exactly one permitted device is auto-selected without a picker`() {
        val only = device(1, "Corne")

        val decision = DeviceSelectionPolicy.decide(listOf(only))

        assertTrue(decision is DeviceSelectionPolicy.Decision.AutoSelect)
        assertEquals(only, (decision as DeviceSelectionPolicy.Decision.AutoSelect).device)
    }

    @Test
    fun `multiple permitted devices show a picker listing all of them`() {
        val devices = listOf(device(1, "Corne"), device(2, "Lily58"), device(3, "Sofle"))

        val decision = DeviceSelectionPolicy.decide(devices)

        assertTrue(decision is DeviceSelectionPolicy.Decision.ShowPicker)
        assertEquals(devices, (decision as DeviceSelectionPolicy.Decision.ShowPicker).devices)
    }
}
