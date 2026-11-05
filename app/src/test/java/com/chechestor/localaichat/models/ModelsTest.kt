package com.chechestor.localaichat.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ModelHttpTest {
    @Test
    fun huggingFaceMayRedirectToItsCdn() {
        assertTrue(ModelHttp.isAllowedHost("huggingface.co", "huggingface.co"))
        assertTrue(ModelHttp.isAllowedHost("huggingface.co", "cdn-lfs.huggingface.co"))
        assertTrue(ModelHttp.isAllowedHost("huggingface.co", "cdn-lfs-us-1.hf.co"))
        assertTrue(ModelHttp.isAllowedHost("huggingface.co", "cas-bridge.xethub.hf.co"))
    }

    @Test
    fun otherHostsAreForbidden() {
        assertFalse(ModelHttp.isAllowedHost("huggingface.co", "evil.com"))
        assertFalse(ModelHttp.isAllowedHost("huggingface.co", "huggingface.co.evil.com"))
        assertFalse(ModelHttp.isAllowedHost("huggingface.co", "fakehf.co"))
        assertFalse(ModelHttp.isAllowedHost("alphacephei.com", "cdn.alphacephei.com"))
        assertTrue(ModelHttp.isAllowedHost("alphacephei.com", "alphacephei.com"))
    }

    @Test
    fun contentRangeTotal() {
        assertEquals(1000L, ModelHttp.totalFromContentRange("bytes 100-999/1000"))
        assertNull(ModelHttp.totalFromContentRange("bytes 100-999/*"))
        assertNull(ModelHttp.totalFromContentRange(null))
    }
}

class ModelCatalogTest {
    @Test
    fun urlsArePinnedHttpsToAllowedHosts() {
        for (m in ModelCatalog.ALL) {
            val u = URL(m.url)
            assertEquals("https", u.protocol)
            assertTrue(u.path.endsWith("/" + m.fileName))
            assertEquals(64, m.sha256.length)
            assertTrue(m.sizeBytes > 0)
        }
        // Ссылки Hugging Face — на коммит, а не на ветку main.
        assertFalse(ModelCatalog.ALL.any { it.url.contains("/resolve/main/") })
    }
}

class ModelFolderTest {
    @Test
    fun onlyDownloadLocalAiChatIsAccepted() {
        assertTrue(ModelStore.isExpectedDocId("primary:Download/LocalAIChat"))
        assertTrue(ModelStore.isExpectedDocId("primary:Download/localaichat"))
        assertFalse(ModelStore.isExpectedDocId("primary:Download"))
        assertFalse(ModelStore.isExpectedDocId("primary:Documents/LocalAIChat"))
        assertFalse(ModelStore.isExpectedDocId("primary:Download/LocalAIChat/sub"))
        assertFalse(ModelStore.isExpectedDocId("1234-5678:Download/LocalAIChat"))
    }
}
