package com.hyperxr

import com.hyperxr.viewers.ViewerCatalog
import com.hyperxr.xrapps.CardboardParams
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class ViewerCatalogTest {
    @Test fun idsAreUniqueAndDefaultExists() {
        assertEquals(ViewerCatalog.all.size, ViewerCatalog.all.map { it.id }.toSet().size)
        assertNotNull(ViewerCatalog.byId(ViewerCatalog.DEFAULT_ID))
    }

    @Test fun valuesFitTheSliders() {
        for (v in ViewerCatalog.all) {
            assertTrue(v.id, v.fovDeg in 70f..110f)
            assertTrue(v.id, v.lensSeparationMm in 50f..70f)
            assertTrue(v.id, v.k1 in 0f..0.6f && v.k2 in 0f..0.6f)
            assertTrue(v.id, v.phoneMin < v.phoneMax)
        }
    }

    @Test fun askedModelsArePresent() {
        for (id in listOf("vrbox-1", "vrbox-2", "shinecon-g05", "shinecon-g05a", "daydream-2016", "gearvr-2015", "cardboard-v1", "cardboard-v2"))
            assertNotNull(id, ViewerCatalog.byId(id))
        assertTrue(ViewerCatalog.search("g05").size >= 2)
        assertTrue(ViewerCatalog.search("", "VR Shinecon").all { it.brand == "VR Shinecon" })
    }

    @Test fun cardboardFileCarriesTheViewer() {
        val v = ViewerCatalog.byId("cardboard-v1")!!
        val p = CardboardParams.fromSettings(v.lensSeparationMm, v.fovDeg, v.k1, v.k2, vendor = v.brand, model = v.name, screenToLensMm = v.screenToLensMm, button = 1)
        assertEquals(0.042f, p.screenToLensM, 1e-6f)
        assertEquals("Cardboard v1", p.model)
        assertTrue(CardboardParams.encode(p).isNotEmpty())
    }
}
