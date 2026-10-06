package io.github.tuthan.paddock.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraPlansTest {
    private val common = listOf(FrameSize(640, 480), FrameSize(1280, 720), FrameSize(1280, 960), FrameSize(1920, 1080), FrameSize(4000, 3000))

    private fun camera(id: String, autofocus: Boolean = true, focal35: Float? = 26f, sizes: List<FrameSize> = common) = CameraCandidate(id, autofocus, focal35, sizes)

    @Test fun mainCameraBeatsAnUltraWideListedFirst() {
        val plan = CameraPlans.choose(listOf(camera("2", focal35 = 14f), camera("0", focal35 = 24f), camera("3", focal35 = 52f)))!!
        assertEquals("0", plan.cameraId)
    }

    @Test fun aCameraWithAutofocusBeatsOneWithout() {
        val plan = CameraPlans.choose(listOf(camera("0", autofocus = false, focal35 = 26f), camera("4", autofocus = true, focal35 = 52f)))!!
        assertEquals("4", plan.cameraId)
    }

    @Test fun aCameraThatDoesNotSayItsLensComesAfterOnesThatDo() {
        val plan = CameraPlans.choose(listOf(camera("0", focal35 = null), camera("1", focal35 = 14f)))!!
        assertEquals("1", plan.cameraId)
    }

    @Test fun theListOrderDecidesBetweenEqualCameras() {
        assertEquals("5", CameraPlans.choose(listOf(camera("5"), camera("1")))!!.cameraId)
        assertEquals("5", CameraPlans.choose(listOf(camera("5", focal35 = null), camera("1", focal35 = null)))!!.cameraId)
    }

    @Test fun aCameraWithNoUsableFrameSizeIsSkipped() {
        val plan = CameraPlans.choose(listOf(camera("0", sizes = listOf(FrameSize(4000, 3000))), camera("1", focal35 = 14f)))!!
        assertEquals("1", plan.cameraId)
    }

    @Test fun noCameraGivesNoPlan() {
        assertNull(CameraPlans.choose(emptyList()))
        assertNull(CameraPlans.choose(listOf(camera("0", sizes = emptyList()))))
    }

    @Test fun thePlanCarriesTheAutofocusOfTheChosenCamera() {
        assertEquals(false, CameraPlans.choose(listOf(camera("0", autofocus = false)))!!.autofocus)
        assertEquals(true, CameraPlans.choose(listOf(camera("0", autofocus = true)))!!.autofocus)
    }

    @Test fun theFrameIsTheLargestFourByThreeWithinTheBudget() {
        assertEquals(FrameSize(1280, 960), CameraPlans.frameSize(common))
        // 640x480 gave a 57-module code 2 to 3 pixels a module on a screen, which nothing reads.
        assertEquals(FrameSize(1280, 960), CameraPlans.choose(listOf(camera("0")))!!.frame)
    }

    @Test fun aFourByThreeSizeIsPreferredToALargerSixteenByNine() {
        assertEquals(FrameSize(640, 480), CameraPlans.frameSize(listOf(FrameSize(640, 480), FrameSize(1280, 720))))
    }

    @Test fun withNoFourByThreeTheLargestWithinTheBudgetIsUsed() {
        assertEquals(FrameSize(1280, 720), CameraPlans.frameSize(listOf(FrameSize(320, 200), FrameSize(1280, 720), FrameSize(1920, 1080))))
    }

    @Test fun aSizeOverTheBudgetIsNeverUsed() {
        assertNull(CameraPlans.frameSize(listOf(FrameSize(1920, 1080), FrameSize(4000, 3000))))
        assertEquals(FrameSize(1280, 960), CameraPlans.frameSize(listOf(FrameSize(1600, 1200), FrameSize(1280, 960))))
    }
}
