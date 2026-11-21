package app.jacket.mobile.ui

import app.jacket.mobile.data.IndexerFailure
import app.jacket.mobile.data.MediaSourceType
import app.jacket.mobile.data.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SourceSearchAccumulatorTest {
    @Test
    fun mergesGroupsInProviderPriorityOrderAndReplacesPartialSnapshot() {
        val accumulator = SourceSearchAccumulator(totalGroups = 3)
        val torrent = SourceResult(
            id = "torrent-1",
            title = "Torrent release",
            providerName = "Tracker",
            sourceType = MediaSourceType.TORRENT
        )
        val earlyFilmix = SourceResult(
            id = "filmix-old",
            title = "Filmix result",
            providerName = "Filmix",
            sourceType = MediaSourceType.FILMIX
        )
        val finalFilmix = earlyFilmix.copy(id = "filmix-final", qualityLabel = "1080p")
        val hdrezka = SourceResult(
            id = "rezka-1",
            title = "HDRezka result",
            providerName = "HDRezka",
            sourceType = MediaSourceType.HDREZKA
        )

        accumulator.update("indexers", listOf(torrent), emptyList(), completed = true)
        val partial = accumulator.update(
            "filmix",
            listOf(earlyFilmix),
            emptyList(),
            completed = false,
            progressMessage = "Filmix: загружает карточки"
        )
        assertEquals(1, partial.completedGroups)
        assertEquals(3, partial.totalGroups)
        assertFalse(partial.timedOut)

        accumulator.update("filmix", listOf(finalFilmix), emptyList(), completed = true)
        val final = accumulator.update("rezka", listOf(hdrezka), emptyList(), completed = true)

        assertEquals(
            listOf(MediaSourceType.HDREZKA, MediaSourceType.FILMIX, MediaSourceType.TORRENT),
            final.results.map { it.sourceType }
        )
        assertFalse(final.results.any { it.id == earlyFilmix.id })
        assertTrue(final.results.any { it.id == finalFilmix.id })
        assertEquals(3, final.completedGroups)
    }

    @Test
    fun prefersHigherPriorityProviderWhenDuplicateIdsArriveInReverseOrder() {
        val accumulator = SourceSearchAccumulator(totalGroups = 2)
        val torrent = SourceResult(
            id = "shared-id",
            title = "Torrent",
            providerName = "Tracker",
            sourceType = MediaSourceType.TORRENT
        )
        val rezka = SourceResult(
            id = "shared-id",
            title = "HDRezka",
            providerName = "HDRezka",
            sourceType = MediaSourceType.HDREZKA
        )

        accumulator.update("indexers", listOf(torrent), emptyList(), completed = true)
        val snapshot = accumulator.update("rezka", listOf(rezka), emptyList(), completed = true)

        assertEquals(1, snapshot.results.size)
        assertEquals("HDRezka", snapshot.results.single().title)
    }

    @Test
    fun deduplicatesFailuresAndRecordsAggregateDeadline() {
        val accumulator = SourceSearchAccumulator(totalGroups = 2)
        val failure = IndexerFailure("filmix", "Filmix", "Сайт недоступен")
        accumulator.update("filmix", emptyList(), listOf(failure), completed = true)
        val snapshot = accumulator.markTimedOut(
            "Источники не ответили за 60 секунд",
            IndexerFailure("source-search-deadline", "Остальные источники", "Превышено время поиска")
        )

        assertEquals(2, snapshot.failures.size)
        assertTrue(snapshot.timedOut)
        assertEquals("Источники не ответили за 60 секунд", snapshot.timedOutMessage)
        assertEquals(1, snapshot.completedGroups)
    }

    @Test
    fun concurrentUpdatesDoNotLoseProviderResults() {
        val accumulator = SourceSearchAccumulator(totalGroups = 2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val first = Thread {
            ready.countDown()
            start.await()
            accumulator.update(
                "filmix",
                listOf(SourceResult("f", "Filmix", "Filmix", sourceType = MediaSourceType.FILMIX)),
                emptyList(),
                completed = true
            )
        }
        val second = Thread {
            ready.countDown()
            start.await()
            accumulator.update(
                "rezka",
                listOf(SourceResult("r", "Rezka", "HDRezka", sourceType = MediaSourceType.HDREZKA)),
                emptyList(),
                completed = true
            )
        }
        first.start()
        second.start()
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        first.join(1_000)
        second.join(1_000)

        val snapshot = accumulator.snapshot()
        assertEquals(2, snapshot.results.size)
        assertEquals(2, snapshot.completedGroups)
    }
}
