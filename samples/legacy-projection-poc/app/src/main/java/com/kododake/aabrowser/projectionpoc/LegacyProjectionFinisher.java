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

import android.util.Log;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.gms.car.CarActivityHost;

import java.lang.reflect.Field;

final class LegacyProjectionFinisher {
    private static final String TAG = "LegacyProjectionPoc";

    private LegacyProjectionFinisher() {
    }

    static void finish(CarActivity activity) {
        try {
            findHost(activity).finish();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "Unable to finish the legacy projected activity through its host", error);
            activity.onBackPressed();
        }
    }

    private static CarActivityHost findHost(CarActivity activity)
            throws ReflectiveOperationException {
        Class<?> type = activity.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!CarActivityHost.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object host = field.get(activity);
                if (host instanceof CarActivityHost carActivityHost) {
                    return carActivityHost;
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException("CarActivityHost field not found");
    }
}
