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

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class ResponsiveProjectionContentView extends ScrollView {
    private final LinearLayout content;
    private final LinearLayout textContent;
    private final TextView title;
    private final TextView description;
    private final Button inputTest;
    private final Button changePerspective;
    private final TextView perspectiveStatus;
    private int clickCount;

    ResponsiveProjectionContentView(
            Context context,
            int perspectiveChangeMessage,
            Runnable onChangePerspective
    ) {
        super(context);
        setFillViewport(true);
        setBackgroundColor(Color.rgb(12, 18, 28));

        content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER);

        textContent = new LinearLayout(context);
        textContent.setOrientation(LinearLayout.VERTICAL);
        textContent.setGravity(Gravity.CENTER);

        title = new TextView(context);
        title.setText(R.string.projection_content_title);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        description = new TextView(context);
        description.setText(R.string.projection_content_description);
        description.setTextColor(Color.rgb(190, 210, 230));
        description.setGravity(Gravity.CENTER);

        inputTest = new Button(context);
        inputTest.setText(R.string.projection_input_test);
        inputTest.setOnClickListener(view -> {
            clickCount++;
            inputTest.setText(
                    context.getString(R.string.projection_input_test_count, clickCount)
            );
        });

        changePerspective = new Button(context);
        changePerspective.setText(R.string.change_perspective);

        perspectiveStatus = new TextView(context);
        perspectiveStatus.setText(perspectiveChangeMessage);
        perspectiveStatus.setTextColor(Color.rgb(154, 203, 255));
        perspectiveStatus.setGravity(Gravity.CENTER);
        perspectiveStatus.setVisibility(View.GONE);

        changePerspective.setOnClickListener(view -> {
            inputTest.setEnabled(false);
            changePerspective.setEnabled(false);
            perspectiveStatus.setVisibility(View.VISIBLE);
            postDelayed(onChangePerspective, 700L);
        });

        textContent.addView(title);
        textContent.addView(description);
        textContent.addView(perspectiveStatus);
        content.addView(textContent);
        content.addView(inputTest);
        content.addView(changePerspective);
        addView(
                content,
                new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float widthDp = width / density;
        float heightDp = height / density;
        boolean compact = Math.min(widthDp, heightDp) < 420f;
        boolean horizontal = widthDp >= 720f && widthDp > heightDp;

        int horizontalPadding = dp(compact ? 20 : horizontal ? 56 : 40);
        int verticalPadding = dp(compact ? 16 : 32);
        int contentGap = dp(compact ? 16 : 28);

        content.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                verticalPadding
        );
        title.setTextSize(compact ? 24 : horizontal ? 34 : 30);
        description.setTextSize(compact ? 15 : 18);
        description.setPadding(0, dp(compact ? 12 : 18), 0, 0);
        perspectiveStatus.setTextSize(compact ? 14 : 16);
        perspectiveStatus.setPadding(0, dp(compact ? 10 : 14), 0, 0);

        LinearLayout.LayoutParams textParams;
        LinearLayout.LayoutParams buttonParams;
        if (horizontal) {
            textContent.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            title.setGravity(Gravity.START);
            description.setGravity(Gravity.START);
            textParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            textParams.setMarginEnd(contentGap);
            buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            inputTest.setLayoutParams(buttonParams);
            LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            changeParams.setMarginStart(contentGap);
            changePerspective.setLayoutParams(changeParams);
        } else {
            textContent.setGravity(Gravity.CENTER);
            title.setGravity(Gravity.CENTER);
            description.setGravity(Gravity.CENTER);
            textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            buttonParams.topMargin = contentGap;
            inputTest.setLayoutParams(buttonParams);
            LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            changeParams.topMargin = dp(compact ? 8 : 12);
            changePerspective.setLayoutParams(changeParams);
        }
        textContent.setLayoutParams(textParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
