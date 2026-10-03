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

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;

final class ProjectedContentPresentation extends Presentation {
    private final Runnable onChangePerspective;

    ProjectedContentPresentation(
            Context context,
            Display display,
            Runnable onChangePerspective
    ) {
        super(context, display, R.style.Theme_LegacyProjectionContent);
        this.onChangePerspective = onChangePerspective;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
        }

        setContentView(
                new ResponsiveProjectionContentView(
                        getContext(),
                        R.string.select_legacy_perspective,
                        onChangePerspective
                )
        );
    }
}
