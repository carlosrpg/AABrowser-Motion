/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://gnu.org/licenses/>.
 */

package com.kododake.aabrowser.projectionpoc;

import android.os.Bundle;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarUiController;

public final class ProjectedDemoActivity extends CarActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_LegacyProjectionPoc);
        super.onCreate(savedInstanceState);

        setIgnoreConfigChanges(0xFFFFFFFF);
        CarUiController controller = getCarUiController();
        controller.getStatusBarController().hideAppHeader();
        controller.getMenuController().hideMenuButton();

        setContentView(
                new ResponsiveProjectionContentView(
                        this,
                        R.string.select_fullscreen_perspective,
                        this::finishProjection
                )
        );
    }

    @Override
    public void onStart() {
        super.onStart();
        ProjectionPerspectiveCoordinator.activateLegacy(this);
    }

    @Override
    public void onStop() {
        ProjectionPerspectiveCoordinator.deactivateLegacy(this);
        super.onStop();
    }

    void finishProjection() {
        LegacyProjectionFinisher.finish(this);
    }
}
