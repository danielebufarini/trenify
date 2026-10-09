package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import kotlin.test.Test

class StationIdentityReopenIosTest {
    @Test fun providerIdentityAndLegacyCachesSurviveRealFileReopen() = runBlocking {
        // Unique sandbox file names match the existing native persistence regression convention.
        val prefix = "station-identity-${NSUUID().UUIDString()}"
        verifyStationIdentityAfterDriverReopen { italo, _ ->
            NativeSqliteDriver(TrenifyDatabase.Schema, "$prefix-$italo.db")
        }
    }
}
