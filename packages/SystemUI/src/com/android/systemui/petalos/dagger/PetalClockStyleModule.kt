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

package com.android.systemui.petalos.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.petalos.PetalClockStyleController
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Dagger bindings for the petalOS lock screen clock style feature. */
@Module
abstract class PetalClockStyleModule {
    @Binds
    @IntoMap
    @ClassKey(PetalClockStyleController::class)
    abstract fun bindPetalClockStyleController(
        impl: PetalClockStyleController,
    ): CoreStartable
}
