package com.hanhyo.pleosshuttle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hanhyo.pleosshuttle.ui.VehicleSharingControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Component tests for the developer location-sharing switch: no saved trip, credentials, SDK, permissions or server access. */
class VehicleTrackingControlsDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unconnectedControlsCannotStartSharing() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(false, "기관 연결 필요"),
                false, false, "꺼짐", { error("Must not enable") })
        } } }
        compose.onNodeWithTag("vehicle-tracking-toggle").assertIsNotEnabled()
    }
    @Test fun activeSharingCanBeStoppedWithTheSwitch() {
        var stopped = false
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(true, "출발 후 공유"),
                true, false, "전송 대기", { stopped = !it })
        } } }
        compose.onNodeWithTag("vehicle-tracking-toggle").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }
    @Test fun optOutRemainsAvailableAfterTripEndsOrConnectionFails() {
        var stopped = false
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(false, "종료"),
                true, true, "종료", { stopped = !it })
        } } }
        compose.onNodeWithTag("vehicle-tracking-toggle").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }
}
