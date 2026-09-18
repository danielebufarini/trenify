package it.danielebufarini.trenify.core.ui

import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Provider-neutral FS §23 presentation mapping (T7.14-A).
 *
 * Maps a preserved [DomainFailure] to the user-facing message without
 * leaking transport/provider internals. Callers pass whether useful cached
 * content is still visible so the offline condition renders the
 * stale-while-offline wording instead of a bare error.
 */
fun failureMessageResource(failure: DomainFailure, hasCachedContent: Boolean): StringResource = when (failure) {
    DomainFailure.OFFLINE ->
        if (hasCachedContent) Res.string.error_offline_cached else Res.string.error_offline
    DomainFailure.TEMPORARY -> Res.string.error_temporary
    DomainFailure.NOT_FOUND -> Res.string.error_train_not_found
    DomainFailure.INVALID_REQUEST -> Res.string.error_invalid_request
    DomainFailure.UNSUPPORTED -> Res.string.error_unsupported
    DomainFailure.INVALID_RESPONSE -> Res.string.error_invalid_response
}

/**
 * Presentation context for [DomainFailure.NOT_FOUND] (T7.14 final pass):
 * the repository taxonomy stays provider-neutral, while the
 * user-facing semantics depend on what is already known. A
 * train-number search with no match reports the entered number; any
 * already-identified [TrainRunId] (detail, monitor, Home summary,
 * correlated leg) reports unavailable realtime instead of claiming the
 * train does not exist.
 */
enum class NotFoundContext {
    /** Train-number search: the entered number matched nothing. */
    TRAIN_SEARCH,

    /** Already-identified train/run: realtime is unavailable for it. */
    KNOWN_TRAIN,
}

/**
 * List-screen NOT_FOUND normalization (T7.14-A): for board and strike lists
 * a 404 means "no content", never a train message — the empty state (no
 * data) or the stale flag (cached data) already carries the truth. Returns
 * null for NOT_FOUND so callers store no failure.
 */
fun DomainFailure?.unlessNotFound(): DomainFailure? = if (this == DomainFailure.NOT_FOUND) null else this
