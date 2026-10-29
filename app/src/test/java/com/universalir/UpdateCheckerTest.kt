package com.universalir

import com.universalir.data.repository.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("0.2.1", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9-debug"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.2.0-debug"))
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.2.0"))
    }
}
