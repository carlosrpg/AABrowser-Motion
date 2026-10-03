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

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

final class ProjectionPerspectiveCoordinator {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static WeakReference<ProjectedDemoActivity> legacyActivity =
            new WeakReference<>(null);
    private static Object fullscreenOwner;
    private static Runnable closeFullscreen;

    private ProjectionPerspectiveCoordinator() {
    }

    static void activateLegacy(ProjectedDemoActivity activity) {
        Runnable fullscreenCloser;
        synchronized (ProjectionPerspectiveCoordinator.class) {
            legacyActivity = new WeakReference<>(activity);
            fullscreenCloser = closeFullscreen;
            fullscreenOwner = null;
            closeFullscreen = null;
        }
        if (fullscreenCloser != null) {
            MAIN_HANDLER.post(fullscreenCloser);
        }
    }

    static void deactivateLegacy(ProjectedDemoActivity activity) {
        synchronized (ProjectionPerspectiveCoordinator.class) {
            if (legacyActivity.get() == activity) {
                legacyActivity.clear();
            }
        }
    }

    static void activateFullscreen(Object owner, Runnable fullscreenCloser) {
        ProjectedDemoActivity activity;
        synchronized (ProjectionPerspectiveCoordinator.class) {
            fullscreenOwner = owner;
            closeFullscreen = fullscreenCloser;
            activity = legacyActivity.get();
            legacyActivity.clear();
        }
        if (activity != null) {
            MAIN_HANDLER.post(activity::finishProjection);
        }
    }

    static void deactivateFullscreen(Object owner) {
        synchronized (ProjectionPerspectiveCoordinator.class) {
            if (fullscreenOwner == owner) {
                fullscreenOwner = null;
                closeFullscreen = null;
            }
        }
    }
}
