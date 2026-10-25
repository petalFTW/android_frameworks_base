/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shared.system.smartspace;

import android.graphics.Rect;
import com.android.systemui.shared.system.smartspace.SmartspaceState;

// Methods for System UI to interface with Launcher to perform the unlock animation.
interface ILauncherUnlockAnimationController {
    // Get the home bits ready to move.
    void prepareForUnlock(boolean animateSmartspace, in Rect lockscreenSmartspaceBounds,
        int selectedPage);

    // Scrub the unlock; force it when an animator is being stubborn.
    oneway void setUnlockAmount(float amount, boolean forceIfAnimating);

    // Let Launcher run the whole thing.
    oneway void playUnlockAnimation(boolean unlocked, long duration, long startDelay);

    // Set the selected page on Launcher's smartspace.
    oneway void setSmartspaceSelectedPage(int selectedPage);

    // Set the visibility of Launcher's smartspace.
    void setSmartspaceVisibility(int visibility);

    // Tell SystemUI where smartspace ended up.
    oneway void dispatchSmartspaceStateToSysui();

    // Screen fractions, so both sides agree where this starts.
    oneway void setUnlockOrigin(float x, float y);
}
