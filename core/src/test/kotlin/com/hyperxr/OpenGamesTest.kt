package com.hyperxr

import com.hyperxr.xrapps.OpenGame
import com.hyperxr.xrapps.OpenGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenGamesTest {
    // shape of api.github.com/search/repositories (trimmed)
    private val search = """
      {"total_count":3,"items":[
        {"name":"quest-doom","full_name":"CommunityPokeOrg/quest-doom","description":"DOOM for Meta Quest","stargazers_count":120,
         "pushed_at":"2026-09-18T10:00:00Z","archived":false,"license":{"key":"gpl-2.0","spdx_id":"GPL-2.0"}},
        {"name":"no-licence","full_name":"someone/no-licence","description":"x","stargazers_count":500,"license":null},
        {"name":"other","full_name":"someone/other","description":"y","stargazers_count":50,"license":{"spdx_id":"NOASSERTION"}}
      ]}"""

    @Test fun onlyOpenSourceLicencesAreKept() {
        val r = OpenGames.parseSearch(search, OpenGame.Kind.OPENXR)
        assertEquals(listOf("CommunityPokeOrg/quest-doom"), r.map { it.repo })
        assertEquals("GPL-2.0", r[0].license)
        assertEquals("2026-09-18", r[0].updated)
    }

    @Test fun mergeKeepsOnePerRepository() {
        val a = OpenGames.parseSearch(search, OpenGame.Kind.OPENXR)
        val m = OpenGames.merge(listOf(a, a, OpenGames.CURATED))
        assertEquals(m.size, m.map { it.repo.lowercase() }.toSet().size)
    }

    @Test fun releaseApksAre64BitFirst() {
        val rel = """{"tag_name":"v1.2","assets":[
          {"name":"game-armeabi-v7a.apk","browser_download_url":"https://x/a.apk","size":10},
          {"name":"notes.txt","browser_download_url":"https://x/n.txt","size":1},
          {"name":"game-arm64-v8a.apk","browser_download_url":"https://x/b.apk","size":20}]}"""
        val apks = OpenGames.parseRelease(rel)
        assertEquals(listOf("game-arm64-v8a.apk", "game-armeabi-v7a.apk"), apks.map { it.name })
        assertEquals("v1.2", apks[0].tag)
    }

    @Test fun curatedListHasOpenLicences() {
        assertTrue(OpenGames.CURATED.all { it.license in OpenGames.OPEN_LICENSES })
        assertTrue(OpenGames.searchUrl("topic:openxr topic:android").contains("topic%3Aopenxr"))
    }
}
