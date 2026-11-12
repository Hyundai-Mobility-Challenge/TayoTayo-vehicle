package com.hanhyo.pleosshuttle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hanhyo.pleosshuttle.ui.VehicleSharingControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic component tests: no saved trip, credentials, SDK, permissions or server access. */
class VehicleTrackingControlsDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unconnectedControlsCannotStartSharingOrDemo() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(false, false, "기관 연결 필요", "모의 시험"),
                false, false, "꺼짐", true, { error("Must not enable") }, { error("Must not send") })
        } } }
        compose.onNodeWithTag("vehicle-tracking-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle-demo-location").assertIsNotEnabled()
    }
    @Test fun activeDemoIsExplicitAndSwitchCanStopSharing() {
        var sends = 0; var stopped = false
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(true, true, "모의 체험", "모의 시험"),
                true, false, "전송 대기", true, { stopped = !it }, { sends++ })
        } } }
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithTag("vehicle-demo-location").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("vehicle-tracking-toggle").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }
    @Test fun movingDemoRequiresStartAndSupportsPauseResumeStop() {
        var phase by mutableStateOf(MovingDemoPhase.STOPPED)
        var starts = 0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(true, true, "모의 체험", "모의 시험"),
                true, false, "전송 대기", true, {}, {}, demoPhase = phase, demoProgress = 20,
                onStart = { starts++; phase = MovingDemoPhase.RUNNING }, onPause = { phase = MovingDemoPhase.PAUSED },
                onResume = { phase = MovingDemoPhase.RUNNING }, onStop = { phase = MovingDemoPhase.STOPPED })
        } } }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("moving-demo-start").performScrollTo().performClick()
        compose.onNodeWithTag("moving-demo-start").assertDoesNotExist()
        compose.onNodeWithTag("vehicle-demo-location").assertIsNotEnabled()
        compose.onNodeWithTag("moving-demo-pause").performScrollTo().performClick()
        compose.onNodeWithTag("moving-demo-status").assertTextContains("일시정지", substring = true)
        compose.onNodeWithTag("moving-demo-resume").performScrollTo().performClick()
        compose.onNodeWithTag("moving-demo-stop").performScrollTo().performClick()
        compose.onNodeWithTag("moving-demo-start").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, starts) }
    }
    @Test fun optOutRemainsAvailableAfterTripEndsOrConnectionFails() {
        var stopped = false
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            VehicleSharingControls(VehicleTrackingControls(false, false, "종료", "Pleos 내비"),
                true, true, "종료", false, { stopped = !it }, {})
        } } }
        compose.onNodeWithTag("vehicle-tracking-toggle").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(stopped) }
        compose.onNodeWithTag("vehicle-demo-location").assertDoesNotExist()
    }
}
