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

import static android.os.SystemClock.uptimeMillis;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public final class FullscreenCarService extends CarAppService {
    @NonNull
    @Override
    public Session onCreateSession(@NonNull SessionInfo sessionInfo) {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new FullscreenViewScreen(getCarContext());
            }
        };
    }

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    private static final class FullscreenViewScreen extends Screen implements SurfaceCallback {
        private static final long SCROLL_END_DELAY_MS = 120L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private VirtualDisplay virtualDisplay;
        private ProjectedContentPresentation presentation;
        private float scrollX;
        private float scrollY;
        private long scrollDownTime;
        private long lastScrollTime;
        private boolean scrollInProgress;
        private int surfaceWidth;
        private int surfaceHeight;
        private final Runnable finishScroll = new Runnable() {
            @Override
            public void run() {
                if (!scrollInProgress) {
                    return;
                }
                long now = uptimeMillis();
                long remaining = lastScrollTime + SCROLL_END_DELAY_MS - now;
                if (remaining > 0) {
                    handler.postDelayed(this, remaining);
                    return;
                }
                dispatchMotionEvent(scrollDownTime, now, ACTION_UP, scrollX, scrollY);
                scrollInProgress = false;
            }
        };

        FullscreenViewScreen(@NonNull CarContext carContext) {
            super(carContext);
            carContext.getCarService(AppManager.class).setSurfaceCallback(this);
            ProjectionPerspectiveCoordinator.activateFullscreen(
                    this,
                    carContext::finishCarApp
            );
            getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(@NonNull LifecycleOwner owner) {
                    ProjectionPerspectiveCoordinator.deactivateFullscreen(
                            FullscreenViewScreen.this
                    );
                    releaseViewHost();
                }
            });
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            Action labelAction = new Action.Builder()
                    .setTitle(getCarContext().getString(R.string.fullscreen_action_label))
                    .setOnClickListener(() -> {
                    })
                    .build();
            return new NavigationTemplate.Builder()
                    .setActionStrip(
                            new ActionStrip.Builder()
                                    .addAction(labelAction)
                                    .build()
                    )
                    .setMapActionStrip(
                            new ActionStrip.Builder()
                                    .addAction(Action.PAN)
                                    .build()
                    )
                    .build();
        }

        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
            releaseViewHost();

            Surface surface = container.getSurface();
            if (surface == null || !surface.isValid()) {
                return;
            }
            surfaceWidth = container.getWidth();
            surfaceHeight = container.getHeight();
            if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                return;
            }
            DisplayManager displayManager =
                    (DisplayManager) getCarContext().getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                return;
            }

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
            virtualDisplay = displayManager.createVirtualDisplay(
                    getCarContext().getString(R.string.fullscreen_app_name),
                    surfaceWidth,
                    surfaceHeight,
                    Math.max(container.getDpi(), 160),
                    surface,
                    flags
            );
            if (virtualDisplay == null) {
                return;
            }

            presentation = new ProjectedContentPresentation(
                    getCarContext(),
                    virtualDisplay.getDisplay(),
                    getCarContext()::finishCarApp
            );
            presentation.show();
            resetScrollPosition();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
            releaseViewHost();
        }

        @Override
        public void onClick(float x, float y) {
            long now = uptimeMillis();
            dispatchMotionEvent(now, now, ACTION_DOWN, x, y);
            dispatchMotionEvent(now, now + 10L, ACTION_UP, x, y);
        }

        @Override
        public void onScroll(float distanceX, float distanceY) {
            long now = uptimeMillis();
            lastScrollTime = now;
            if (!scrollInProgress) {
                scrollInProgress = true;
                scrollDownTime = now;
                resetScrollPosition();
                dispatchMotionEvent(now, now, ACTION_DOWN, scrollX, scrollY);
            }
            scrollX = clamp(scrollX - distanceX, 0f, surfaceWidth - 1f);
            scrollY = clamp(scrollY - distanceY, 0f, surfaceHeight - 1f);
            dispatchMotionEvent(scrollDownTime, now, ACTION_MOVE, scrollX, scrollY);
            handler.removeCallbacks(finishScroll);
            handler.postDelayed(finishScroll, SCROLL_END_DELAY_MS);
        }

        private void dispatchMotionEvent(
                long downTime,
                long eventTime,
                int action,
                float x,
                float y
        ) {
            ProjectedContentPresentation currentPresentation = presentation;
            if (currentPresentation == null) {
                return;
            }
            MotionEvent event = MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    0
            );
            currentPresentation.dispatchTouchEvent(event);
            event.recycle();
        }

        private void resetScrollPosition() {
            scrollX = surfaceWidth / 2f;
            scrollY = surfaceHeight / 2f;
        }

        private static float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(value, maximum));
        }

        private void releaseViewHost() {
            handler.removeCallbacks(finishScroll);
            scrollInProgress = false;

            ProjectedContentPresentation currentPresentation = presentation;
            presentation = null;
            if (currentPresentation != null) {
                currentPresentation.dismiss();
            }

            VirtualDisplay currentDisplay = virtualDisplay;
            virtualDisplay = null;
            if (currentDisplay != null) {
                currentDisplay.release();
            }
            surfaceWidth = 0;
            surfaceHeight = 0;
        }
    }
}
