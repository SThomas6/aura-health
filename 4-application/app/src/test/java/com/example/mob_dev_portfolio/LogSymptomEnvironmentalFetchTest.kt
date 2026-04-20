package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.environment.EnvironmentalFetchResult
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalSnapshot
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.LocationResult
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers every acceptance criterion from the Environmental Data Retrieval
 * & Resiliency story:
 *
 * 1. Saving a log with a location triggers an API call that populates env fields.
 * 2. Editing a past log bypasses the network layer entirely.
 * 3. 5-second timeout → non-blocking notification, symptom still saved locally.
 * 4. WiFi/Data disabled → non-blocking notification, symptom saved with null env.
 *
 * Plus a couple of structural invariants (no location ⇒ no fetch, HTTP error
 * path, API-returned partial data is persisted verbatim).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogSymptomEnvironmentalFetchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun save_with_location_triggers_fetch_and_persists_env_fields() = runTest(dispatcher) {
        val repo = CapturingRepo()
        val env = RecordingEnvService(
            result = EnvironmentalFetchResult.Success(
                EnvironmentalSnapshot(
                    weatherCode = 3,
                    weatherDescription = "Overcast",
                    temperatureCelsius = 14.5,
                    humidityPercent = 72,
                    pressureHpa = 1011.0,
                    airQualityIndex = 35,
                ),
            ),
        )
        val vm = newVm(repo, env, provider = fixedLocation(51.5074, -3.1791))
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceUntilIdle()

        assertEquals("Exactly one env fetch per save", 1, env.callCount)
        val saved = repo.lastInsert!!
        assertEquals(3, saved.weatherCode!!)
        assertEquals("Overcast", saved.weatherDescription)
        assertEquals(14.5, saved.temperatureCelsius!!, 1e-9)
        assertEquals(72, saved.humidityPercent!!)
        assertEquals(1011.0, saved.pressureHpa!!, 1e-9)
        assertEquals(35, saved.airQualityIndex!!)
    }

    @Test
    fun save_without_location_does_not_call_env_service() = runTest(dispatcher) {
        val repo = CapturingRepo()
        val env = RecordingEnvService(result = EnvironmentalFetchResult.Success(EnvironmentalSnapshot.EMPTY))
        val vm = newVm(repo, env, provider = fixedLocation(0.0, 0.0))
        fillValid(vm)

        // NOT opting into location — the pipeline has nothing to geo-lookup.
        vm.save { }
        advanceUntilIdle()

        assertEquals(0, env.callCount)
        val saved = repo.lastInsert!!
        assertNull(saved.weatherCode)
        assertNull(saved.airQualityIndex)
    }

    @Test
    fun editing_bypasses_env_fetch_and_preserves_original_values() = runTest(dispatcher) {
        // Seed the repo with an existing log that already has env data from a
        // previous save. Editing it (any field) must NOT re-fetch, and the
        // original env values must round-trip unchanged.
        val existing = SymptomLog(
            id = 42L,
            symptomName = "Migraine",
            description = "Started after lunch",
            startEpochMillis = 1_799_000_000_000L,
            endEpochMillis = null,
            severity = 6,
            medication = "",
            contextTags = emptyList(),
            notes = "",
            createdAtEpochMillis = 1_799_000_000_000L,
            locationLatitude = 51.51,
            locationLongitude = -3.18,
            locationName = "Cardiff, Wales",
            weatherCode = 61,
            weatherDescription = "Rain",
            temperatureCelsius = 11.2,
            humidityPercent = 88,
            pressureHpa = 998.4,
            airQualityIndex = 22,
        )
        val repo = CapturingRepo(seed = existing)
        val env = RecordingEnvService(
            result = EnvironmentalFetchResult.Success(
                EnvironmentalSnapshot(weatherCode = 0, weatherDescription = "Clear sky"),
            ),
        )
        val vm = newVm(
            repo,
            env,
            provider = fixedLocation(51.5074, -3.1791),
            editingId = 42L,
        )
        advanceUntilIdle() // let loadForEditing complete

        // Make an edit — change severity — then save.
        vm.onSeverityChange(8)
        vm.save { }
        advanceUntilIdle()

        // Core acceptance: the env service was never consulted during the edit.
        assertEquals("Editing must bypass the network layer entirely", 0, env.callCount)

        val saved = repo.lastUpdate!!
        // Original environmental values preserved exactly.
        assertEquals(61, saved.weatherCode!!)
        assertEquals("Rain", saved.weatherDescription)
        assertEquals(11.2, saved.temperatureCelsius!!, 1e-9)
        assertEquals(88, saved.humidityPercent!!)
        assertEquals(998.4, saved.pressureHpa!!, 1e-9)
        assertEquals(22, saved.airQualityIndex!!)
        // The edit actually landed.
        assertEquals(8, saved.severity)
    }

    @Test
    fun timeout_saves_log_with_null_env_and_surfaces_snackbar() = runTest(dispatcher) {
        // Service sleeps longer than the VM's timeout. The VM must abort the
        // wait at the 5s boundary (we compress it to 100ms here), persist the
        // log with null env fields, and expose a transient error message.
        val repo = CapturingRepo()
        val env = RecordingEnvService(delayMillis = 10_000L) // would take 10s; VM budget is 100ms
        val vm = newVm(
            repo,
            env,
            provider = fixedLocation(51.51, -3.18),
            environmentalTimeoutMillis = 100L,
        )
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceTimeBy(200L)
        advanceUntilIdle()

        // Log is persisted.
        assertEquals(1, repo.insertCount)
        val saved = repo.lastInsert!!
        // Env fields null — partial/bad data would be worse than none.
        assertNull(saved.weatherCode)
        assertNull(saved.temperatureCelsius)
        assertNull(saved.airQualityIndex)

        // Non-blocking notification surfaced. Exact wording pinned so the
        // UI can rely on it (and the user story explicitly asks for a
        // "timeout error notification").
        val err = vm.state.value.transientError
        assertNotNull("expected a transient error for timeout", err)
        assertTrue(
            "expected timeout language, got: $err",
            err!!.contains("timed out", ignoreCase = true),
        )
    }

    @Test
    fun offline_saves_log_with_null_env_and_surfaces_snackbar() = runTest(dispatcher) {
        val repo = CapturingRepo()
        val env = RecordingEnvService(result = EnvironmentalFetchResult.NoNetwork)
        val vm = newVm(repo, env, provider = fixedLocation(51.51, -3.18))
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceUntilIdle()

        assertEquals(1, repo.insertCount)
        val saved = repo.lastInsert!!
        assertNull(saved.weatherCode)
        assertNull(saved.airQualityIndex)

        val err = vm.state.value.transientError
        assertNotNull("expected transient error for offline save", err)
        assertTrue(
            "expected offline language, got: $err",
            err!!.contains("internet", ignoreCase = true),
        )
    }

    @Test
    fun api_error_saves_log_with_null_env_and_surfaces_snackbar() = runTest(dispatcher) {
        val repo = CapturingRepo()
        val env = RecordingEnvService(
            result = EnvironmentalFetchResult.ApiError("Weather service error (503)."),
        )
        val vm = newVm(repo, env, provider = fixedLocation(51.51, -3.18))
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceUntilIdle()

        assertEquals(1, repo.insertCount)
        assertNull(repo.lastInsert!!.weatherCode)
        assertEquals("Weather service error (503).", vm.state.value.transientError)
    }

    @Test
    fun service_throwing_does_not_crash_save() = runTest(dispatcher) {
        // Contract: the VM must never let an exception from the service
        // bubble out and kill the save. We simulate a misbehaving service
        // that throws IllegalStateException mid-fetch.
        val repo = CapturingRepo()
        val env = ThrowingEnvService(IllegalStateException("unexpected"))
        val vm = newVm(repo, env, provider = fixedLocation(51.51, -3.18))
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceUntilIdle()

        assertEquals(1, repo.insertCount)
        assertNotNull(vm.state.value.transientError)
    }

    @Test
    fun partial_env_data_is_persisted_verbatim() = runTest(dispatcher) {
        // Open-Meteo air-quality endpoint returns 503 but forecast works — the
        // service layer hands us a Success with airQualityIndex=null. The VM
        // must persist what we have, not treat it as a failure.
        val repo = CapturingRepo()
        val env = RecordingEnvService(
            result = EnvironmentalFetchResult.Success(
                EnvironmentalSnapshot(
                    weatherCode = 1,
                    weatherDescription = "Mainly clear",
                    temperatureCelsius = 20.0,
                    humidityPercent = 40,
                    pressureHpa = 1020.0,
                    airQualityIndex = null,
                ),
            ),
        )
        val vm = newVm(repo, env, provider = fixedLocation(51.51, -3.18))
        fillValid(vm)

        vm.onAttachLocationChange(true)
        vm.onLocationPermissionResult(granted = true)
        vm.save { }
        advanceUntilIdle()

        val saved = repo.lastInsert!!
        assertEquals(1, saved.weatherCode!!)
        assertEquals(20.0, saved.temperatureCelsius!!, 1e-9)
        assertNull(saved.airQualityIndex) // the hole stays a hole
    }

    private fun fillValid(vm: LogSymptomViewModel) {
        vm.onSymptomNameChange("Headache")
        vm.onDescriptionChange("Throbbing at temples")
    }

    private fun fixedLocation(lat: Double, lng: Double): LocationProvider =
        object : LocationProvider {
            override suspend fun fetchCurrentLocation(): LocationResult =
                LocationResult.Coordinates(lat, lng)
        }

    private fun newVm(
        repo: SymptomLogRepository,
        env: EnvironmentalService,
        provider: LocationProvider,
        editingId: Long = 0L,
        environmentalTimeoutMillis: Long = 5_000L,
    ) = LogSymptomViewModel(
        repository = repo,
        locationProvider = provider,
        reverseGeocoder = StubGeocoder,
        environmentalService = env,
        editingId = editingId,
        nowProvider = { 1_800_000_000_000L },
        environmentalTimeoutMillis = environmentalTimeoutMillis,
    )
}

private object StubGeocoder : ReverseGeocoder {
    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String =
        "Somewhere, Somewhere"
}

private class RecordingEnvService(
    private val result: EnvironmentalFetchResult? = null,
    private val delayMillis: Long = 0L,
) : EnvironmentalService {
    var callCount = 0
        private set

    override suspend fun fetch(
        latitude: Double,
        longitude: Double,
    ): EnvironmentalFetchResult {
        callCount += 1
        if (delayMillis > 0L) delay(delayMillis)
        return result
            ?: EnvironmentalFetchResult.Success(EnvironmentalSnapshot.EMPTY)
    }
}

private class ThrowingEnvService(private val error: Throwable) : EnvironmentalService {
    override suspend fun fetch(
        latitude: Double,
        longitude: Double,
    ): EnvironmentalFetchResult {
        throw error
    }
}

private class CapturingRepo(
    private val seed: SymptomLog? = null,
) : SymptomLogRepository(StubEnvDao()) {

    var insertCount = 0
        private set
    var lastInsert: SymptomLog? = null
        private set
    var lastUpdate: SymptomLog? = null
        private set

    override fun observeById(id: Long): Flow<SymptomLog?> =
        MutableStateFlow(if (seed?.id == id) seed else null).asStateFlow()

    override suspend fun save(log: SymptomLog): Long {
        insertCount += 1
        lastInsert = log
        return insertCount.toLong()
    }

    override suspend fun update(log: SymptomLog): Int {
        lastUpdate = log
        return 1
    }
}

private class StubEnvDao : SymptomLogDao {
    override suspend fun insert(entity: SymptomLogEntity): Long = 0L
    override suspend fun update(entity: SymptomLogEntity): Int = 0
    override fun observeAll() = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()
    override fun observeFiltered(
        query: String?,
        minSeverity: Int,
        maxSeverity: Int,
        startAfter: Long?,
        startBefore: Long?,
        sortKey: String,
    ) = MutableStateFlow<List<SymptomLogEntity>>(emptyList()).asStateFlow()

    override fun observeById(id: Long) = MutableStateFlow<SymptomLogEntity?>(null).asStateFlow()
    override fun observeCount() = MutableStateFlow(0).asStateFlow()
    override suspend fun delete(id: Long) = Unit
    override suspend fun listChronologicalAsc(): List<SymptomLogEntity> = emptyList()
    override suspend fun totalCount(): Int = 0
    override suspend fun averageSeverity(): Double? = null
}
