package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate

/** Typed Swift access to semantic identities whose Kotlin inline wrappers export as Any.
 * These accessors only unwrap existing values; they never infer, resolve or select a provider.
 */
object NativeSemanticIdentity {
    fun station(station: Station): String = station.id.value
    fun favoriteRoute(route: FavoriteRoute): String = route.id.value
    fun favoriteTrain(train: FavoriteTrain): String = train.id.value
    fun history(entry: SearchHistoryEntry): String = entry.id.value
    fun monitor(monitor: TrainMonitor): String = monitor.id.value
    fun recurringTrainNumber(train: FavoriteTrain): String = train.number.value
    fun trainRun(id: TrainRunId): NativeTrainRunIdentity = NativeTrainRunIdentity(
        id.key, id.provider.value, id.number.value, id.origin.value, id.serviceDate,
    )
}

data class NativeTrainRunIdentity(
    val key: String,
    val provider: String,
    val number: String,
    val origin: String,
    val serviceDate: LocalDate,
)
