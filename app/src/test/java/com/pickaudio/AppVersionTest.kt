package com.pickaudio

import com.pickaudio.update.isNewerAppVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun currentGitHubReleaseIsNotReportedAsAnUpdate() {
        assertFalse(isNewerAppVersion("1.1.6", 8, "1.1.6", 0))
    }

    @Test fun olderVersionIsRejectedEvenWhenItsCodeIsLarger() {
        assertFalse(isNewerAppVersion("1.2.0", 9, "1.1.6", 116))
    }

    @Test fun newerGitHubReleaseWorksWithoutAnAndroidCode() {
        assertTrue(isNewerAppVersion("1.2.0", 9, "1.2.1", 0))
    }

    @Test fun versionSegmentsAreComparedNumerically() {
        assertTrue(isNewerAppVersion("1.9.9", 9, "1.10.0", 0))
    }

    @Test fun sameNamedBuildCanUpdateUsingAnExplicitAndroidCode() {
        assertTrue(isNewerAppVersion("1.2.0", 9, "1.2.0", 10))
        assertFalse(isNewerAppVersion("1.2.0", 9, "1.2.0", 9))
    }

    @Test fun versionPrefixesAndTrailingZerosAreEquivalent() {
        assertFalse(isNewerAppVersion("v1.2", 9, "V1.2.0", 0))
    }

    @Test fun nonNumericVersionsUseExplicitAndroidCodes() {
        assertTrue(isNewerAppVersion("development", 9, "release", 10))
        assertFalse(isNewerAppVersion("development", 9, "release", 0))
    }
}
