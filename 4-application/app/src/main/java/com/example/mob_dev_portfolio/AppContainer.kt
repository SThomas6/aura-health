package com.example.mob_dev_portfolio

import android.content.Context
import com.example.mob_dev_portfolio.data.AuraDatabase
import com.example.mob_dev_portfolio.data.SymptomLogRepository

interface AppContainer {
    val symptomLogRepository: SymptomLogRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val database by lazy { AuraDatabase.get(context) }
    override val symptomLogRepository: SymptomLogRepository by lazy {
        SymptomLogRepository(database.symptomLogDao())
    }
}
