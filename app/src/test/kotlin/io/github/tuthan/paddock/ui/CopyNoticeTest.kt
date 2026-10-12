package io.github.tuthan.paddock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 15 (AC-15.5): Android 13 and later confirm a copy themselves, 12L and lower do not; a cut or a left-out part is always said. */
class CopyNoticeTest {
    @Test fun onTwelveAndLowerTheAppConfirms() {
        for (api in listOf(26, 31, 32)) {
            assertEquals("api $api", "Copied 1 line.", copyNotice(api, 1, 0, 0))
            assertEquals("api $api", "Copied 12 lines.", copyNotice(api, 12, 0, 0))
        }
    }

    @Test fun onThirteenAndLaterThereIsNothingToAddWhenNothingWasCutOrLeftOut() {
        for (api in listOf(33, 36, 37)) assertNull("api $api", copyNotice(api, 12, 0, 0))
    }

    @Test fun aCutLineIsSaidOnEveryLevel() {
        assertEquals("Copied 3 lines. 1 line was cut at 2,000 characters.", copyNotice(32, 3, 1, 0))
        assertEquals("2 lines were cut at 2,000 characters.", copyNotice(36, 3, 2, 0))
    }

    @Test fun leftOutLinesAreSaidOnEveryLevel() {
        assertEquals("Copied 120 lines. 80 older lines were left out: the copy would be too long.", copyNotice(26, 120, 0, 80))
        assertEquals("1 older line was left out: the copy would be too long.", copyNotice(36, 120, 0, 1))
    }

    @Test fun bothAreSaidTogether() {
        assertEquals("1 line was cut at 2,000 characters. 5 older lines were left out: the copy would be too long.", copyNotice(33, 10, 1, 5))
    }
}
