package com.example.mob_dev_portfolio.data

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
