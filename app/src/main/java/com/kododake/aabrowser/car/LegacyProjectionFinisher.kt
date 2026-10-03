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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.car

import android.util.Log
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.gms.car.CarActivityHost

internal object LegacyProjectionFinisher {
    private const val TAG = "AABrowserProjection"

    fun finish(activity: CarActivity) {
        try {
            findHost(activity).finish()
        } catch (error: ReflectiveOperationException) {
            Log.e(TAG, "Unable to finish the legacy projected activity through its host", error)
            activity.onBackPressed()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to finish the legacy projected activity through its host", error)
            activity.onBackPressed()
        }
    }

    private fun findHost(activity: CarActivity): CarActivityHost {
        var type: Class<*>? = activity.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (!CarActivityHost::class.java.isAssignableFrom(field.type)) continue

                field.isAccessible = true
                val host = field.get(activity)
                if (host is CarActivityHost) return host
            }
            type = type.superclass
        }
        throw NoSuchFieldException("CarActivityHost field not found")
    }
}
