/*
 * Copyright (C) 2018 Florian Dreier
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package de.dreier.mytargets.features.regression

import android.content.Intent
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.base.navigation.NavigationController.Companion.ITEM
import de.dreier.mytargets.features.settings.ESettingsScreens
import de.dreier.mytargets.features.settings.SettingsActivity
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.features.settings.backup.provider.EBackupLocation
import de.dreier.mytargets.features.training.environment.EnvironmentActivity
import de.dreier.mytargets.features.training.edit.EditTrainingActivity
import de.dreier.mytargets.features.training.edit.EditTrainingFragment.Companion.CREATE_FREE_TRAINING_ACTION
import de.dreier.mytargets.features.training.standardround.StandardRoundActivity
import de.dreier.mytargets.shared.models.Environment
import de.dreier.mytargets.shared.models.Dimension
import de.dreier.mytargets.shared.models.Dimension.Unit.CENTIMETER
import de.dreier.mytargets.shared.models.Dimension.Unit.METER
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.views.TargetViewBase
import de.dreier.mytargets.test.base.UITestBase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashRegressionTest : UITestBase() {

    @Before
    fun setUp() {
        SettingsManager.standardRound = 93L
        SettingsManager.target = Target(WAFull.ID, 0, Dimension(122f, CENTIMETER))
        SettingsManager.distance = Dimension(10f, METER)
        SettingsManager.indoor = false
        SettingsManager.inputMethod = TargetViewBase.EInputMethod.PLOTTING
        SettingsManager.timerEnabled = false
        SettingsManager.shotsPerEnd = 3
        SettingsManager.backupLocation = EBackupLocation.INTERNAL_STORAGE
    }

    @Test
    fun editTrainingLaunchAndRecreate_doesNotCrash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            EditTrainingActivity::class.java
        ).apply {
            action = CREATE_FREE_TRAINING_ACTION
        }

        ActivityScenario.launch<EditTrainingActivity>(intent).use { scenario ->
            onView(withId(R.id.trainingDate)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.trainingDate)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun standardRoundLaunchAndRecreate_doesNotCrash() {
        val standardRound = ApplicationInstance.db.standardRoundDAO()
            .loadAugmentedStandardRound(SettingsManager.standardRound)!!
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            StandardRoundActivity::class.java
        ).apply {
            putExtra(ITEM, standardRound)
        }

        ActivityScenario.launch<StandardRoundActivity>(intent).use { scenario ->
            onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun environmentLaunchAndRecreate_doesNotCrash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            EnvironmentActivity::class.java
        ).apply {
            putExtra(ITEM, Environment.getDefault(SettingsManager.indoor))
        }

        ActivityScenario.launch<EnvironmentActivity>(intent).use { scenario ->
            onView(withId(R.id.location)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.location)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backupSettingsLaunchAndRecreate_doesNotCrash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            SettingsActivity::class.java
        ).apply {
            putExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, ESettingsScreens.BACKUP.key)
        }

        ActivityScenario.launch<SettingsActivity>(intent).use { scenario ->
            onView(withId(android.R.id.list)).check(matches(hasDescendant(withText(R.string.backup_now))))
            scenario.recreate()
            onView(withId(android.R.id.list)).check(matches(hasDescendant(withText(R.string.backup_now))))
        }
    }
}
