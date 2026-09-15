/*
 * Copyright (C) 2026 The petalOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.island.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.island.IslandController
import com.android.systemui.island.StatusBarIconHider
import com.android.systemui.statusbar.core.StatusBarInitializer.StatusBarViewLifecycleListener
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoMap

// island wiring
@Module(includes = [IslandStatusBarModule::class])
abstract class IslandModule {
    @Binds
    @IntoMap
    @ClassKey(IslandController::class)
    abstract fun bindIslandController(controller: IslandController): CoreStartable
}

@Module
object IslandStatusBarModule {

    // make the hider listen for the status bar view, ugly but it works
    @Provides
    @ElementsIntoSet
    fun statusBarIconHiderAsLifecycleListener(
        hider: Lazy<StatusBarIconHider>,
    ): Set<StatusBarViewLifecycleListener> = setOf(hider.get())
}
