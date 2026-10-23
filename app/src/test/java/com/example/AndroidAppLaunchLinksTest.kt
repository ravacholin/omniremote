package com.example

import com.example.data.model.StreamingAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppLaunchLinksTest {
    @Test fun `Flow avoids the unsupported market launch URI including saved defaults`() {
        val pkg = "ar.com.flow.androidtv"
        val expected = listOf("https://play.google.com/store/apps/details?id=$pkg")
        assertEquals(expected, StreamingAppCatalog.androidLaunchLinks(pkg))
        assertEquals(expected, StreamingAppCatalog.androidLaunchLinks(pkg, "market://launch?id=$pkg"))
    }

    @Test fun `TvDimmer uses its official store page instead of unregistered package schemes`() {
        val pkg = "com.tribalapps.tvdimmer"
        val expected = listOf("https://play.google.com/store/apps/details?id=$pkg")
        for (saved in listOf("", "market://launch?id=$pkg", "android-app://$pkg")) {
            assertEquals(expected, StreamingAppCatalog.androidLaunchLinks(pkg, saved))
        }
    }

    @Test fun `explicit app supported deep links remain preferred`() {
        assertEquals(listOf("myapp://home"), StreamingAppCatalog.androidLaunchLinks("com.example.custom", "myapp://home"))
        assertEquals("https://www.disneyplus.com", StreamingAppCatalog.androidLaunchLinks("com.disney.disneyplus").first())
    }

    @Test fun `custom android app links are preserved outside the TvDimmer repair`() {
        val link = "android-app://com.example.custom"
        assertEquals(listOf(link), StreamingAppCatalog.androidLaunchLinks("com.example.custom", link))
    }
}
