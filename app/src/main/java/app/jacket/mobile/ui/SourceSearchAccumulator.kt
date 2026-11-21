package app.jacket.mobile.ui

import app.jacket.mobile.data.IndexerFailure
import app.jacket.mobile.data.MediaSourceType
import app.jacket.mobile.data.SourceResult

/** Immutable aggregate state emitted while the configured source groups finish independently. */
internal data class SourceSearchSnapshot(
    val results: List<SourceResult>,
    val failures: List<IndexerFailure>,
    val completedGroups: Int,
    val totalGroups: Int,
    val progressMessages: List<String>,
    val timedOutMessage: String? = null
) {
    val timedOut: Boolean
        get() = timedOutMessage != null
}

/**
 * Thread-safe, replace-by-provider accumulator for concurrent source searches.
 *
 * A provider can publish several partial snapshots (for example, built-in trackers).
 * Replacing that provider's snapshot prevents duplicate/stale rows while preserving
 * results already received from other providers.
 */
internal class SourceSearchAccumulator(private val totalGroups: Int) {
    private data class GroupState(
        val results: List<SourceResult>,
        val failures: List<IndexerFailure>,
        val completed: Boolean,
        val progressMessage: String?
    )

    private val groups = LinkedHashMap<String, GroupState>()
    private var deadlineFailure: IndexerFailure? = null
    private var timedOutMessage: String? = null

    init {
        require(totalGroups >= 0) { "Количество групп поиска не может быть отрицательным" }
    }

    @Synchronized
    fun update(
        groupId: String,
        results: List<SourceResult>,
        failures: List<IndexerFailure>,
        completed: Boolean,
        progressMessage: String? = null
    ): SourceSearchSnapshot {
        require(groupId.isNotBlank()) { "У группы поиска должен быть ID" }
        val previous = groups[groupId]
        groups[groupId] = GroupState(
            results = results.toList(),
            failures = failures.toList(),
            completed = completed || previous?.completed == true,
            progressMessage = progressMessage ?: previous?.progressMessage
        )
        return snapshotLocked()
    }

    @Synchronized
    fun markTimedOut(message: String, failure: IndexerFailure): SourceSearchSnapshot {
        timedOutMessage = message.trim().take(180).ifBlank { "Общий лимит поиска источников превышен" }
        deadlineFailure = failure
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): SourceSearchSnapshot = snapshotLocked()

    private fun snapshotLocked(): SourceSearchSnapshot {
        val mergedResults = groups.values
            .flatMap { it.results }
            .sortedBy(::sourcePriority)
            .distinctBy { it.id }
            .take(MAX_RESULTS)
        val mergedFailures = (groups.values.flatMap { it.failures } + listOfNotNull(deadlineFailure))
            .distinctBy { it.indexerId }
        return SourceSearchSnapshot(
            results = mergedResults,
            failures = mergedFailures,
            completedGroups = groups.values.count { it.completed }.coerceAtMost(totalGroups),
            totalGroups = totalGroups,
            progressMessages = groups.values.mapNotNull { state ->
                state.progressMessage?.takeIf(String::isNotBlank)
            },
            timedOutMessage = timedOutMessage
        )
    }

    private fun sourcePriority(source: SourceResult): Int = when (source.sourceType) {
        MediaSourceType.HDREZKA -> 0
        MediaSourceType.LAMPAC -> 1
        MediaSourceType.FILMIX -> 2
        MediaSourceType.TORRENT -> 3
        MediaSourceType.LICENSED_DIRECT -> 4
    }

    private companion object {
        const val MAX_RESULTS = 160
    }
}
