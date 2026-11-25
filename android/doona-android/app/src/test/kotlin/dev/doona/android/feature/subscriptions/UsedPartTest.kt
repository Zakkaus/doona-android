package dev.doona.android.feature.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Test

/** The traffic line writes used over total as doona web does: the unit once when both sides share it. */
class UsedPartTest {
    @Test
    fun theSharedUnitIsWrittenOnce() = assertEquals("70/268 MB", "${usedPart("70 MB", "268 MB")}/268 MB")

    @Test
    fun differentUnitsStayOnBothSides() = assertEquals("461 GB", usedPart("461 GB", "1.1 TB"))

    @Test
    fun aDecimalKeepsItsDigits() = assertEquals("1.5", usedPart("1.5 GB", "20 GB"))
}
