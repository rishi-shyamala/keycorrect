package dev.keycorrect.core.clipboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlCleanerTest {
    private fun clean(url: String) = UrlCleaner.clean(url)

    @Test
    fun stripsUtmParametersAndKeepsOrderAndFragment() {
        assertThat(clean("https://example.com/a?x=1&utm_source=tw&y=2&utm_medium=s#frag"))
            .isEqualTo("https://example.com/a?x=1&y=2#frag")
    }

    @Test
    fun removesQuestionMarkWhenNothingRemains() {
        assertThat(clean("https://example.com/a?utm_source=x&fbclid=abc"))
            .isEqualTo("https://example.com/a")
        assertThat(clean("https://example.com/a?gclid=1#top"))
            .isEqualTo("https://example.com/a#top")
    }

    @Test
    fun stripsKnownTrackers() {
        listOf(
                "fbclid",
                "gclid",
                "dclid",
                "msclkid",
                "mc_eid",
                "igshid",
                "ref_src",
                "_hsenc",
                "_hsmi",
            )
            .forEach {
                assertThat(clean("https://example.com/p?id=7&$it=zzz"))
                    .isEqualTo("https://example.com/p?id=7")
            }
    }

    @Test
    fun trackerNamesAreCaseInsensitive() {
        assertThat(clean("https://example.com/?UTM_Source=a&FBCLID=b&q=kotlin"))
            .isEqualTo("https://example.com/?q=kotlin")
    }

    @Test
    fun siIsStrippedOnlyOnYoutubeAndSpotify() {
        assertThat(clean("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf"))
            .isEqualTo("https://youtu.be/dQw4w9WgXcQ")
        assertThat(clean("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=AbCdEf&t=10"))
            .isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10")
        assertThat(clean("https://open.spotify.com/track/abc?si=123456"))
            .isEqualTo("https://open.spotify.com/track/abc")
        assertThat(clean("https://example.com/search?si=keep")).isNull()
        assertThat(clean("https://notyoutube.com/x?si=keep")).isNull()
    }

    @Test
    fun nullWhenNothingChanged() {
        assertThat(clean("https://example.com/")).isNull()
        assertThat(clean("https://example.com/a?x=1&y=2#utm_source=fragment")).isNull()
        assertThat(clean("https://example.com/a?utmx=1")).isNull()
    }

    @Test
    fun nullWhenNotASingleHttpUrl() {
        listOf(
                "",
                "hello",
                "see https://example.com/?utm_source=x",
                "https://a.com/?utm_source=x https://b.com/",
                "ftp://example.com/?utm_source=x",
                "example.com/?utm_source=x",
                "https://example.com/?utm_source=x\nhttps://b.com",
            )
            .forEach { assertThat(clean(it)).isNull() }
    }

    @Test
    fun trimsSurroundingWhitespaceAndAcceptsHttp() {
        assertThat(clean("  http://example.com/?utm_campaign=c\n")).isEqualTo("http://example.com/")
    }

    @Test
    fun keepsPercentEncodingAndRepeatedParamsUntouched() {
        assertThat(clean("https://example.com/s?q=caf%C3%A9&tag=a&tag=b&utm_term=x"))
            .isEqualTo("https://example.com/s?q=caf%C3%A9&tag=a&tag=b")
    }

    @Test
    fun handlesValuelessAndUserInfoHosts() {
        assertThat(clean("https://example.com/?fbclid&a")).isEqualTo("https://example.com/?a")
        assertThat(clean("https://user@www.youtube.com:443/watch?v=1&si=x"))
            .isEqualTo("https://user@www.youtube.com:443/watch?v=1")
    }
}
