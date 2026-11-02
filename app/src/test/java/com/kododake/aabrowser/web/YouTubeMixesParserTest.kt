package com.kododake.aabrowser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMixesParserTest {

    // evaluateJavascript hands back the script's JSON.stringify output as a JSON string literal.
    private fun encoded(json: String): String = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun keepsOnlyValidMixesAndDropsDuplicates() {
        val payload = """{"done":true,"items":[
            {"title":"My Mix","thumbnail":"https://i.ytimg.com/vi/abcdefghijk/hq.jpg","videoId":"abcdefghijk","playlistId":"RDMMabcdefghijk"},
            {"title":"Dup","thumbnail":"https://i.ytimg.com/x.jpg","videoId":"abcdefghijk","playlistId":"RDMMabcdefghijk"},
            {"title":"Bad id","thumbnail":"https://i.ytimg.com/x.jpg","videoId":"short","playlistId":"RDzzz"},
            {"title":"Not a mix","thumbnail":"https://i.ytimg.com/x.jpg","videoId":"abcdefghijk","playlistId":"PLabc"},
            {"title":"","thumbnail":"https://i.ytimg.com/y.jpg","videoId":"ABCDEFGHIJK","playlistId":"RDAMVMxyz"}
        ]}"""

        val mixes = YouTubeMixesParser.parse(encoded(payload))!!.mixes

        assertEquals(listOf("RDMMabcdefghijk", "RDAMVMxyz"), mixes.map { it.playlistId })
        assertEquals("RDAMVMxyz", mixes[1].title)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk&list=RDMMabcdefghijk&start_radio=1", mixes[0].watchUrl)
    }

    @Test
    fun reportsStillLoadingUntilDone() {
        assertNull(YouTubeMixesParser.parse(null))
        assertNull(YouTubeMixesParser.parse("null"))
        assertNull(YouTubeMixesParser.parse(encoded("""{"done":false,"items":[]}""")))
        assertNull(YouTubeMixesParser.parse("not json"))
    }

    @Test
    fun partialResultsAreAcceptedOnlyWhenAsked() {
        val payload = encoded("""{"done":false,"items":[{"title":"x","thumbnail":"https://i.ytimg.com/x.jpg","videoId":"abcdefghijk","playlistId":"RDabc"}]}""")
        assertNull(YouTubeMixesParser.parse(payload))
        assertEquals(1, YouTubeMixesParser.parse(payload, acceptPartial = true)!!.mixes.size)
    }

    @Test
    fun doneWithoutItemsIsEmpty() {
        val outcome = YouTubeMixesParser.parse(encoded("""{"done":true}"""))!!
        assertTrue(outcome.mixes.isEmpty())
        assertFalse(outcome.pageNotReady)
    }

    @Test
    fun reportsPageNotReady() {
        assertTrue(YouTubeMixesParser.parse(encoded("""{"done":true,"notReady":true,"items":[]}"""))!!.pageNotReady)
    }
}
