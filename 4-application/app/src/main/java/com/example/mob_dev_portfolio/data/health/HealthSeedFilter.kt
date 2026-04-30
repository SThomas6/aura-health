package com.example.mob_dev_portfolio.data.health

import androidx.health.connect.client.records.Record
import com.example.mob_dev_portfolio.BuildConfig

/**
 * Filter that drops Health Connect records previously written by the
 * demo flavor's seeder when the current build is `production`.
 *
 * ### Why this exists
 * Health Connect records live in the **system** Health Connect store,
 * not in our app's database. Uninstalling the demo build does *not*
 * automatically erase the records it wrote — the user has to clear
 * them manually via the Health Connect system app. If the same user
 * (or marker, on the same emulator) runs the demo flavor first and
 * then switches to production, production reads HC and sees the demo
 * build's leftover sample data, undermining the whole point of the
 * "clean install" production behaviour.
 *
 * Health Connect's `dataOriginFilter` API is include-only — there is no
 * "exclude this app's records" filter. So we filter post-read by the
 * deterministic `clientRecordId` prefix that `HealthSampleSeeder`
 * tags every seeded record with. Real records written by Samsung
 * Health, Fitbit, Google Fit, Health Connect's manual entry, etc.
 * never have this prefix and are passed through untouched.
 *
 * ### Behaviour
 * - **Demo build** (`BuildConfig.SEED_SAMPLE_DATA == true`) — no-op.
 *   The demo flavor *expects* to see its own seed data, since the
 *   dashboard charts depend on it.
 * - **Production build** (`SEED_SAMPLE_DATA == false`) — drops every
 *   record whose `clientRecordId` starts with [SEED_CLIENT_ID_PREFIX].
 *
 * The filter is applied immediately after every `client.readRecords(...)`
 * call in [HealthConnectService] and [HealthHistoryRepository].
 *
 * ### Why not filter at write time?
 * The user-installed demo build wrote the records under its own
 * `applicationId` (`com.example.mob_dev_portfolio.demo`) and tagged
 * each one with this prefix. The production build can't reach back
 * and delete them — Health Connect scopes deletes to the caller's
 * data origin. Read-side filtering is the only mechanism available.
 */

/**
 * Stable `clientRecordId` prefix written by `HealthSampleSeeder` on
 * every seeded record. Mirrored here as a top-level public const so
 * the filter helper doesn't need to reach into the seeder's
 * companion object — the seeder is a write path, the filter is a
 * read path, and they should be able to evolve independently.
 *
 * If `HealthSampleSeeder.CLIENT_ID_PREFIX` ever changes, change this
 * value too — the unit test `HealthSeedFilterTest` pins them in sync.
 */
internal const val SEED_CLIENT_ID_PREFIX = "aura-seed-v1"

/**
 * Drop demo-seeded records when running on a production build.
 * No-op on demo builds. See file-level KDoc for rationale.
 */
internal fun <T : Record> List<T>.excludingDemoSeedInProduction(): List<T> {
    if (BuildConfig.SEED_SAMPLE_DATA) return this
    return filterNot { record ->
        record.metadata.clientRecordId?.startsWith(SEED_CLIENT_ID_PREFIX) == true
    }
}
