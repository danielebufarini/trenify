package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import kotlin.test.Test

class StationAutocompleteReopenIosTest {
    @Test fun persistedNegativeRecoversAfterRealDatabaseReopenWithoutPrefixHistory() = runBlocking {
        val name = "station-autocomplete-negative-${NSUUID().UUIDString()}.db"
        verifyStationAutocompleteNegativeAfterDriverReopen {
            NativeSqliteDriver(TrenifyDatabase.Schema, name)
        }
    }

    @Test fun negativeAndDegradedCachesRecoverAcrossRealDatabaseReopen() = runBlocking {
        val name = "station-autocomplete-recovery-${NSUUID().UUIDString()}.db"
        verifyStationAutocompleteRecoveryAfterDriverReopen {
            NativeSqliteDriver(TrenifyDatabase.Schema, name)
        }
    }
}
