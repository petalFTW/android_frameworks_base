/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.petalos.PetalScreenDithering
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class PetalScreenDitheringModule {
    @Binds
    @IntoMap
    @ClassKey(PetalScreenDithering::class)
    abstract fun bindPetalScreenDithering(impl: PetalScreenDithering): CoreStartable
}
