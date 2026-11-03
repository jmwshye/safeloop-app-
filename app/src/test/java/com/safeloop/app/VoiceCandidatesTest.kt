package com.safeloop.app

import android.os.Bundle
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCandidatesTest {
    private fun results(vararg texts: String, pending: List<String> = emptyList()) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(texts.toList()))
        putStringArrayList("android.speech.extra.UNSTABLE_TEXT", ArrayList(pending))
    }

    @Test fun everyCandidateIsKeptInOrder() {
        assertEquals(listOf("관찬아", "괜찮아요"), speechCandidates(results("관찬아", "괜찮아요")))
    }
    @Test fun unfinishedWordsAreJoinedSoAnswersAreCaughtEarly() {
        val found = speechCandidates(results("괜찮", pending = listOf("아요")))
        assertEquals("괜찮아요", found.first())
        assertEquals(SpokenAnswer.OKAY, spokenAnswer(found))
    }
    @Test fun emptyResultsGiveNothing() {
        assertTrue(speechCandidates(null).isEmpty())
        assertTrue(speechCandidates(Bundle()).isEmpty())
        assertTrue(speechCandidates(results("", " ")).isEmpty())
    }
}
