package app.jacket.mobile.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockPolicyTest {
    @Test
    fun matchesExactAdDomainsAndTheirSubdomains() {
        assertTrue(AdBlockPolicy.isKnownAdDomain("doubleclick.net"))
        assertTrue(AdBlockPolicy.isKnownAdDomain("securepubads.g.doubleclick.net"))
        assertFalse(AdBlockPolicy.isKnownAdDomain("notdoubleclick.net"))
        assertFalse(AdBlockPolicy.isKnownAdDomain("doubleclick.net.example.org"))
    }

    @Test
    fun trackerDomainsAreKeptSeparateFromAdDomains() {
        assertTrue(AdBlockPolicy.isKnownTrackerDomain("www.google-analytics.com"))
        assertFalse(AdBlockPolicy.isKnownTrackerDomain("googlevideo.com"))
        assertFalse(AdBlockPolicy.isKnownAdDomain("www.google-analytics.com"))
    }

    @Test
    fun domainMatchingIsCaseInsensitiveAndDoesNotMatchLookalikes() {
        assertTrue(AdBlockPolicy.matchesDomain("ADS.EXAMPLE.COM", "example.com"))
        assertFalse(AdBlockPolicy.matchesDomain("fakeexample.com", "example.com"))
    }
}
