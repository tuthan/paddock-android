package io.github.tuthan.paddock.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The foss flavor's constant is what its billing reports (review F5): the widget process reads the constant and must not build a store to ask. */
class DistributionTest {
    @Test fun theFlavorConstantIsWhatTheBillingReports() {
        assertFalse(Distribution.SELLS_PRO)
        assertEquals(NoBilling.sellsPro, Distribution.SELLS_PRO)
    }
}
