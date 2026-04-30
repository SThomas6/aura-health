package com.example.mob_dev_portfolio.data

/**
 * Curated list of common symptom names exposed in the symptom-name
 * picker. Defined here (rather than as a string-array resource) so
 * tests, view-models, and the seeder can reference the exact same list
 * without round-tripping through a [android.content.res.Resources]
 * lookup. The list is intentionally short and broad — niche symptoms
 * land via the "Other" free-text branch.
 */
object SymptomCatalog {
    /** Sentinel the picker inserts at the bottom of the preset list to
     *  switch the field into free-text mode. Kept as a constant so the
     *  picker and any tests stay in sync on the wording. */
    const val OTHER: String = "Other"

    val presets: List<String> = listOf(
        "Headache",
        "Migraine",
        "Nausea",
        "Fatigue",
        "Dizziness",
        "Back pain",
        "Stomach ache",
        "Sore throat",
        "Chest tightness",
        "Joint pain",
        "Anxiety",
        "Insomnia",
    )
}

/**
 * Curated list of contextual factor tags the editor offers as chips so
 * the user can flag plausible triggers without typing them out. The AI
 * pipeline reads these straight off the entity, so the wording here is
 * also what Gemini sees in the prompt — kept short and unambiguous.
 */
object ContextTagCatalog {
    val tags: List<String> = listOf(
        "Stress",
        "Poor sleep",
        "Exercise",
        "Dehydration",
        "Caffeine",
        "Alcohol",
        "Travel",
        "Menstrual cycle",
        "Weather change",
        "Screen time",
    )
}
