package app.jacket.mobile.lampac

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LampacArtifactUrlPolicyTest {
    @Test
    fun acceptsPinnedHttpsHostsOnDefaultOrStandardPort() {
        assertTrue(isAllowedLampacArtifactUri(URI("https://github.com/releases/file.zip")))
        assertTrue(isAllowedLampacArtifactUri(URI("https://release-assets.githubusercontent.com:443/file.zip")))
        assertTrue(isAllowedLampacArtifactUri(URI("https://builds.dotnet.microsoft.com/runtime.tar.gz")))
    }

    @Test
    fun rejectsUntrustedSchemesHostsAndPorts() {
        assertFalse(isAllowedLampacArtifactUri(URI("http://github.com/file.zip")))
        assertFalse(isAllowedLampacArtifactUri(URI("https://example.com/file.zip")))
        assertFalse(isAllowedLampacArtifactUri(URI("https://github.com:8443/file.zip")))
        assertFalse(isAllowedLampacArtifactUri(URI("https://github.com:80/file.zip")))
    }

    @Test
    fun rejectsUserInfoAndFragments() {
        assertFalse(isAllowedLampacArtifactUri(URI("https://user:password@github.com/file.zip")))
        assertFalse(isAllowedLampacArtifactUri(URI("https://github.com/file.zip#fragment")))
    }
}
