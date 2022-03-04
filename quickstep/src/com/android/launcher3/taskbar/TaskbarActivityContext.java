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
package com.android.launcher3.taskbar;

import static android.content.pm.PackageManager.FEATURE_PC;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_FOLDER_OPEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_BOTTOM_TAPPABLE_ELEMENT;
import static com.android.systemui.shared.system.WindowManagerWrapper.ITYPE_EXTRA_NAVIGATION_BAR;

import android.animation.AnimatorSet;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo.Config;
import android.content.pm.LauncherApps;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.R;
import com.android.launcher3.dot.DotInfo;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.logger.LauncherAtom;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.PopupDataProvider;
import com.android.launcher3.taskbar.allapps.TaskbarAllAppsController;
import com.android.launcher3.touch.ItemClickHandler;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.SettingsCache;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.util.ViewCache;
import com.android.launcher3.views.ActivityContext;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider;

import java.io.PrintWriter;

/**
 * The {@link ActivityContext} with which we inflate Taskbar-related Views. This allows UI elements
 * that are used by both Launcher and Taskbar (such as Folder) to reference a generic
 * ActivityContext and BaseDragLayer instead of the Launcher activity and its DragLayer.
 */
public class TaskbarActivityContext extends BaseTaskbarContext {

    private static final boolean ENABLE_THREE_BUTTON_TASKBAR =
            SystemProperties.getBoolean("persist.debug.taskbar_three_button", false);
    private static final String TAG = "TaskbarActivityContext";

    private static final String WINDOW_TITLE = "Taskbar";

    private final TaskbarDragLayer mDragLayer;
    private final TaskbarControllers mControllers;

    private final WindowManager mWindowManager;
    private final @Nullable RoundedCorner mLeftCorner, mRightCorner;
    private final int mTaskbarHeightForIme;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mIsFullscreen;
    // The size we should return to when we call setTaskbarWindowFullscreen(false)
    private int mLastRequestedNonFullscreenHeight;

    private final SysUINavigationMode.Mode mNavMode;
    private final boolean mImeDrawsImeNavBar;
    private final ViewCache mViewCache = new ViewCache();

    private final boolean mIsSafeModeEnabled;
    private final boolean mIsUserSetupComplete;
    private final boolean mIsNavBarKidsMode;
    private boolean mIsDestroyed = false;
    // The flag to know if the window is excluded from magnification region computation.
    private boolean mIsExcludeFromMagnificationRegion = false;
    private boolean mBindingItems = false;

    private final TaskbarShortcutMenuAccessibilityDelegate mAccessibilityDelegate;

    public TaskbarActivityContext(Context windowContext, DeviceProfile dp,
            TaskbarNavButtonController buttonController, ScopedUnfoldTransitionProgressProvider
            unfoldTransitionProgressProvider) {
        super(windowContext);
        mDeviceProfile = dp;

        mNavMode = SysUINavigationMode.getMode(windowContext);
        mImeDrawsImeNavBar = SysUINavigationMode.getImeDrawsImeNavBar(windowContext);
        mIsSafeModeEnabled = TraceHelper.allowIpcs("isSafeMode",
                () -> getPackageManager().isSafeMode());
        mIsUserSetupComplete = SettingsCache.INSTANCE.get(this).getValue(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), 0);
        mIsNavBarKidsMode = SettingsCache.INSTANCE.get(this).getValue(
                Settings.Secure.getUriFor(Settings.Secure.NAV_BAR_KIDS_MODE), 0);

        final Resources resources = getResources();
        updateIconSize(resources);

        mTaskbarHeightForIme = resources.getDimensionPixelSize(R.dimen.taskbar_ime_size);

        // Inflate views.
        mDragLayer = (TaskbarDragLayer) mLayoutInflater.inflate(
                R.layout.taskbar, null, false);
        TaskbarView taskbarView = mDragLayer.findViewById(R.id.taskbar_view);
        TaskbarScrimView taskbarScrimView = mDragLayer.findViewById(R.id.taskbar_scrim);
        FrameLayout navButtonsView = mDragLayer.findViewById(R.id.navbuttons_view);
        StashedHandleView stashedHandleView = mDragLayer.findViewById(R.id.stashed_handle);

        Display display = windowContext.getDisplay();
        Context c = display.getDisplayId() == Display.DEFAULT_DISPLAY
                ? windowContext.getApplicationContext()
                : windowContext.getApplicationContext().createDisplayContext(display);
        mWindowManager = c.getSystemService(WindowManager.class);
        mLeftCorner = display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        mRightCorner = display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);

        mAccessibilityDelegate = new TaskbarShortcutMenuAccessibilityDelegate(this);

        // Construct controllers.
        mControllers = new TaskbarControllers(this,
                new TaskbarDragController(this),
                buttonController,
                getPackageManager().hasSystemFeature(FEATURE_PC)
                        ? new DesktopNavbarButtonsViewController(this, navButtonsView) :
                        new NavbarButtonsViewController(this, navButtonsView),
                new RotationButtonController(this,
                        c.getColor(R.color.taskbar_nav_icon_light_color),
                        c.getColor(R.color.taskbar_nav_icon_dark_color),
                        R.drawable.ic_sysbar_rotate_button_ccw_start_0,
                        R.drawable.ic_sysbar_rotate_button_ccw_start_90,
                        R.drawable.ic_sysbar_rotate_button_cw_start_0,
                        R.drawable.ic_sysbar_rotate_button_cw_start_90,
                        () -> getDisplay().getRotation()),
                new TaskbarDragLayerController(this, mDragLayer),
                new TaskbarViewController(this, taskbarView),
                new TaskbarScrimViewController(this, taskbarScrimView),
                new TaskbarUnfoldAnimationController(this, unfoldTransitionProgressProvider,
                        mWindowManager, WindowManagerGlobal.getWindowManagerService()),
                new TaskbarKeyguardController(this),
                new StashedHandleViewController(this, stashedHandleView),
                new TaskbarStashController(this),
                new TaskbarEduController(this),
                new TaskbarAutohideSuspendController(this),
                new TaskbarPopupController(this),
                new TaskbarForceVisibleImmersiveController(this),
                new TaskbarAllAppsController(this));
    }

    public void init(TaskbarSharedState sharedState) {
        mLastRequestedNonFullscreenHeight = getDefaultTaskbarWindowHeight();
        mWindowLayoutParams = createDefaultWindowLayoutParams();

        WindowManagerWrapper wmWrapper = WindowManagerWrapper.getInstance();
        wmWrapper.setProvidesInsetsTypes(
                mWindowLayoutParams,
                new int[] { ITYPE_EXTRA_NAVIGATION_BAR, ITYPE_BOTTOM_TAPPABLE_ELEMENT }
        );
        // Adjust the frame by the rounded corners (ie. leaving just the bar as the inset) when
        // the IME is showing
        mWindowLayoutParams.providedInternalImeInsets = Insets.of(0,
                getDefaultTaskbarWindowHeight() - mTaskbarHeightForIme, 0, 0);

        mWindowLayoutParams.insetsRoundedCornerFrame = true;

        // Initialize controllers after all are constructed.
        mControllers.init(sharedState);
        updateSysuiStateFlags(sharedState.sysuiStateFlags, true /* fromInit */);

        mWindowManager.addView(mDragLayer, mWindowLayoutParams);
    }

    @Override
    public void updateDeviceProfile(DeviceProfile dp) {
        mDeviceProfile = dp;
        updateIconSize(getResources());
        dispatchDeviceProfileChanged();
    }

    private void updateIconSize(Resources resources) {
        float taskbarIconSize = resources.getDimension(R.dimen.taskbar_icon_size);
        mDeviceProfile.updateIconSize(1, resources);
        float iconScale = taskbarIconSize / mDeviceProfile.iconSizePx;
        mDeviceProfile.updateIconSize(iconScale, resources);
        mDeviceProfile.updateAllAppsIconSize(1, resources); // Leave all apps unscaled.
    }

    /** Creates LayoutParams for adding a view directly to WindowManager as a new window */
    public WindowManager.LayoutParams createDefaultWindowLayoutParams() {
        WindowManager.LayoutParams windowLayoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT,
                mLastRequestedNonFullscreenHeight,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        windowLayoutParams.setTitle(WINDOW_TITLE);
        windowLayoutParams.packageName = getPackageName();
        windowLayoutParams.gravity = Gravity.BOTTOM;
        windowLayoutParams.setFitInsetsTypes(0);
        windowLayoutParams.receiveInsetsIgnoringZOrder = true;
        windowLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        windowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        windowLayoutParams.privateFlags =
                WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
        return windowLayoutParams;
    }

    public void onConfigurationChanged(@Config int configChanges) {
        mControllers.onConfigurationChanged(configChanges);
    }

    public boolean isThreeButtonNav() {
        return mNavMode == Mode.THREE_BUTTONS;
    }

    public boolean isGestureNav() {
        return mNavMode == Mode.NO_BUTTON;
    }

    public boolean imeDrawsImeNavBar() {
        return mImeDrawsImeNavBar;
    }

    public int getLeftCornerRadius() {
        return mLeftCorner == null ? 0 : mLeftCorner.getRadius();
    }

    public int getRightCornerRadius() {
        return mRightCorner == null ? 0 : mRightCorner.getRadius();
    }

    @Override
    public TaskbarDragLayer getDragLayer() {
        return mDragLayer;
    }

    @Override
    public Rect getFolderBoundingBox() {
        return mControllers.taskbarDragLayerController.getFolderBoundingBox();
    }

    @Override
    public TaskbarDragController getDragController() {
        return mControllers.taskbarDragController;
    }

    @Override
    public ViewCache getViewCache() {
        return mViewCache;
    }

    @Override
    public View.OnClickListener getItemOnClickListener() {
        return this::onTaskbarIconClicked;
    }

    /**
     * Change from hotseat/predicted hotseat to taskbar container.
     */
    @Override
    public void applyOverwritesToLogItem(LauncherAtom.ItemInfo.Builder itemInfoBuilder) {
        if (!itemInfoBuilder.hasContainerInfo()) {
            return;
        }
        LauncherAtom.ContainerInfo oldContainer = itemInfoBuilder.getContainerInfo();

        if (oldContainer.hasPredictedHotseatContainer()) {
            LauncherAtom.PredictedHotseatContainer predictedHotseat =
                    oldContainer.getPredictedHotseatContainer();
            LauncherAtom.TaskBarContainer.Builder taskbarBuilder =
                    LauncherAtom.TaskBarContainer.newBuilder();

            if (predictedHotseat.hasIndex()) {
                taskbarBuilder.setIndex(predictedHotseat.getIndex());
            }
            if (predictedHotseat.hasCardinality()) {
                taskbarBuilder.setCardinality(predictedHotseat.getCardinality());
            }

            itemInfoBuilder.setContainerInfo(LauncherAtom.ContainerInfo.newBuilder()
                    .setTaskBarContainer(taskbarBuilder));
        } else if (oldContainer.hasHotseat()) {
            LauncherAtom.HotseatContainer hotseat = oldContainer.getHotseat();
            LauncherAtom.TaskBarContainer.Builder taskbarBuilder =
                    LauncherAtom.TaskBarContainer.newBuilder();

            if (hotseat.hasIndex()) {
                taskbarBuilder.setIndex(hotseat.getIndex());
            }

            itemInfoBuilder.setContainerInfo(LauncherAtom.ContainerInfo.newBuilder()
                    .setTaskBarContainer(taskbarBuilder));
        } else if (oldContainer.hasFolder() && oldContainer.getFolder().hasHotseat()) {
            LauncherAtom.FolderContainer.Builder folderBuilder = oldContainer.getFolder()
                    .toBuilder();
            LauncherAtom.HotseatContainer hotseat = folderBuilder.getHotseat();
            LauncherAtom.TaskBarContainer.Builder taskbarBuilder =
                    LauncherAtom.TaskBarContainer.newBuilder();

            if (hotseat.hasIndex()) {
                taskbarBuilder.setIndex(hotseat.getIndex());
            }

            folderBuilder.setTaskbar(taskbarBuilder);
            folderBuilder.clearHotseat();
            itemInfoBuilder.setContainerInfo(LauncherAtom.ContainerInfo.newBuilder()
                    .setFolder(folderBuilder));
        }
    }

    @Override
    public DotInfo getDotInfoForItem(ItemInfo info) {
        return getPopupDataProvider().getDotInfoForItem(info);
    }

    @NonNull
    @Override
    public PopupDataProvider getPopupDataProvider() {
        return mControllers.taskbarPopupController.getPopupDataProvider();
    }

    @Override
    public View.AccessibilityDelegate getAccessibilityDelegate() {
        return mAccessibilityDelegate;
    }

    @Override
    public boolean isBindingItems() {
        return mBindingItems;
    }

    public void setBindingItems(boolean bindingItems) {
        mBindingItems = bindingItems;
    }

    @Override
    public void onDragStart() {
        setTaskbarWindowFullscreen(true);
    }

    @Override
    public void onPopupVisibilityChanged(boolean isVisible) {
        setTaskbarWindowFocusable(isVisible);
    }

    /**
     * Sets a new data-source for this taskbar instance
     */
    public void setUIController(@NonNull TaskbarUIController uiController) {
        mControllers.uiController.onDestroy();
        mControllers.uiController = uiController;
        mControllers.uiController.init(mControllers);
    }

    /**
     * Sets the flag indicating setup UI is visible
     */
    public void setSetupUIVisible(boolean isVisible) {
        mControllers.taskbarStashController.setSetupUIVisible(isVisible);
    }

    /**
     * Called when this instance of taskbar is no longer needed
     */
    public void onDestroy() {
        mIsDestroyed = true;
        setUIController(TaskbarUIController.DEFAULT);
        mControllers.onDestroy();
        mWindowManager.removeViewImmediate(mDragLayer);
    }

    public void updateSysuiStateFlags(int systemUiStateFlags, boolean fromInit) {
        mControllers.navbarButtonsViewController.updateStateForSysuiFlags(systemUiStateFlags,
                fromInit);
        mControllers.taskbarViewController.setImeIsVisible(
                mControllers.navbarButtonsViewController.isImeVisible());
        int shadeExpandedFlags = SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
                | SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
        onNotificationShadeExpandChanged((systemUiStateFlags & shadeExpandedFlags) != 0, fromInit);
        mControllers.taskbarViewController.setRecentsButtonDisabled(
                mControllers.navbarButtonsViewController.isRecentsDisabled()
                        || isNavBarKidsModeActive());
        mControllers.stashedHandleViewController.setIsHomeButtonDisabled(
                mControllers.navbarButtonsViewController.isHomeDisabled());
        mControllers.taskbarKeyguardController.updateStateForSysuiFlags(systemUiStateFlags);
        mControllers.taskbarStashController.updateStateForSysuiFlags(systemUiStateFlags, fromInit);
        mControllers.taskbarScrimViewController.updateStateForSysuiFlags(systemUiStateFlags,
                fromInit);
        mControllers.navButtonController.updateSysuiFlags(systemUiStateFlags);
        mControllers.taskbarForceVisibleImmersiveController.updateSysuiFlags(systemUiStateFlags);
    }

    /**
     * Hides the taskbar icons and background when the notication shade is expanded.
     */
    private void onNotificationShadeExpandChanged(boolean isExpanded, boolean skipAnim) {
        float alpha = isExpanded ? 0 : 1;
        AnimatorSet anim = new AnimatorSet();
        anim.play(mControllers.taskbarViewController.getTaskbarIconAlpha().getProperty(
                TaskbarViewController.ALPHA_INDEX_NOTIFICATION_EXPANDED).animateToValue(alpha));
        if (!isThreeButtonNav()) {
            anim.play(mControllers.taskbarDragLayerController.getNotificationShadeBgTaskbar()
                    .animateToValue(alpha));
        }
        anim.start();
        if (skipAnim) {
            anim.end();
        }
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        mControllers.rotationButtonController.onRotationProposal(rotation, isValid);
    }

    public void disableNavBarElements(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getDisplayId()) {
            return;
        }
        mControllers.rotationButtonController.onDisable2FlagChanged(state2);
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        mControllers.rotationButtonController.onBehaviorChanged(displayId, behavior);
    }

    public void onNavButtonsDarkIntensityChanged(float darkIntensity) {
        if (!isUserSetupComplete()) {
            return;
        }
        mControllers.navbarButtonsViewController.getTaskbarNavButtonDarkIntensity()
                .updateValue(darkIntensity);
    }

    /**
     * Updates the TaskbarContainer to MATCH_PARENT vs original Taskbar size.
     */
    public void setTaskbarWindowFullscreen(boolean fullscreen) {
        mControllers.taskbarAutohideSuspendController.updateFlag(
                TaskbarAutohideSuspendController.FLAG_AUTOHIDE_SUSPEND_FULLSCREEN, fullscreen);
        mIsFullscreen = fullscreen;
        setTaskbarWindowHeight(fullscreen ? MATCH_PARENT : mLastRequestedNonFullscreenHeight);
    }

    public boolean isTaskbarWindowFullscreen() {
        return mIsFullscreen;
    }

    /**
     * Notify system to inset the rounded corner frame based on the task bar insets.
     */
    public void updateInsetRoundedCornerFrame(boolean shouldInsetsRoundedCorner) {
        if (!mDragLayer.isAttachedToWindow()
                || mWindowLayoutParams.insetsRoundedCornerFrame == shouldInsetsRoundedCorner) {
            return;
        }
        mWindowLayoutParams.insetsRoundedCornerFrame = shouldInsetsRoundedCorner;
        mWindowManager.updateViewLayout(mDragLayer, mWindowLayoutParams);
    }

    /**
     * Updates the TaskbarContainer height (pass {@link #getDefaultTaskbarWindowHeight()} to reset).
     */
    public void setTaskbarWindowHeight(int height) {
        if (mWindowLayoutParams.height == height || mIsDestroyed) {
            return;
        }
        if (height == MATCH_PARENT) {
            height = mDeviceProfile.heightPx;
        } else {
            mLastRequestedNonFullscreenHeight = height;
            if (mIsFullscreen) {
                // We still need to be fullscreen, so defer any change to our height until we call
                // setTaskbarWindowFullscreen(false). For example, this could happen when dragging
                // from the gesture region, as the drag will cancel the gesture and reset launcher's
                // state, which in turn normally would reset the taskbar window height as well.
                return;
            }
        }
        mWindowLayoutParams.height = height;
        mWindowLayoutParams.providedInternalImeInsets =
                Insets.of(0, height - mTaskbarHeightForIme, 0, 0);
        mWindowManager.updateViewLayout(mDragLayer, mWindowLayoutParams);
    }

    /**
     * Returns the default height of the window, including the static corner radii above taskbar.
     */
    public int getDefaultTaskbarWindowHeight() {
        return mDeviceProfile.taskbarSize + Math.max(getLeftCornerRadius(), getRightCornerRadius());
    }

    /**
     * Returns the bottom insets taskbar provides to the IME when IME is visible.
     */
    public int getTaskbarHeightForIme() {
        return mTaskbarHeightForIme;
    }

    /**
     * Either adds or removes {@link WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE} on the taskbar
     * window.
     */
    public void setTaskbarWindowFocusable(boolean focusable) {
        if (focusable) {
            mWindowLayoutParams.flags &= ~FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= FLAG_NOT_FOCUSABLE;
        }
        mWindowManager.updateViewLayout(mDragLayer, mWindowLayoutParams);
    }

    /**
     * Either adds or removes {@link WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE} on the taskbar
     * window. If we're now focusable, also move nav buttons to a separate window above IME.
     */
    public void setTaskbarWindowFocusableForIme(boolean focusable) {
        if (focusable) {
            mControllers.navbarButtonsViewController.moveNavButtonsToNewWindow();
        } else {
            mControllers.navbarButtonsViewController.moveNavButtonsBackToTaskbarWindow();
        }
        setTaskbarWindowFocusable(focusable);
    }

    /** Adds the given view to WindowManager with the provided LayoutParams (creates new window). */
    public void addWindowView(View view, WindowManager.LayoutParams windowLayoutParams) {
        mWindowManager.addView(view, windowLayoutParams);
    }

    /** Removes the given view from WindowManager. See {@link #addWindowView}. */
    public void removeWindowView(View view) {
        mWindowManager.removeViewImmediate(view);
    }

    protected void onTaskbarIconClicked(View view) {
        Object tag = view.getTag();
        if (tag instanceof Task) {
            Task task = (Task) tag;
            ActivityManagerWrapper.getInstance().startActivityFromRecents(task.key,
                    ActivityOptions.makeBasic());
        } else if (tag instanceof FolderInfo) {
            FolderIcon folderIcon = (FolderIcon) view;
            Folder folder = folderIcon.getFolder();

            folder.setOnFolderStateChangedListener(newState -> {
                if (newState == Folder.STATE_OPEN) {
                    setTaskbarWindowFocusableForIme(true);
                } else if (newState == Folder.STATE_CLOSED) {
                    // Defer by a frame to ensure we're no longer fullscreen and thus won't jump.
                    getDragLayer().post(() -> setTaskbarWindowFocusableForIme(false));
                    folder.setOnFolderStateChangedListener(null);
                }
            });

            setTaskbarWindowFullscreen(true);

            getDragLayer().post(() -> {
                folder.animateOpen();
                getStatsLogManager().logger().withItemInfo(folder.mInfo).log(LAUNCHER_FOLDER_OPEN);

                folder.iterateOverItems((itemInfo, itemView) -> {
                    mControllers.taskbarViewController
                            .setClickAndLongClickListenersForIcon(itemView);
                    // To play haptic when dragging, like other Taskbar items do.
                    itemView.setHapticFeedbackEnabled(true);
                    return false;
                });
            });
        } else if (tag instanceof WorkspaceItemInfo) {
            WorkspaceItemInfo info = (WorkspaceItemInfo) tag;
            if (info.isDisabled()) {
                ItemClickHandler.handleDisabledItemClicked(info, this);
            } else {
                Intent intent = new Intent(info.getIntent())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    if (mIsSafeModeEnabled && !PackageManagerHelper.isSystemApp(this, intent)) {
                        Toast.makeText(this, R.string.safemode_shortcut_error,
                                Toast.LENGTH_SHORT).show();
                    } else  if (info.isPromise()) {
                        intent = new PackageManagerHelper(this)
                                .getMarketIntent(info.getTargetPackage())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);

                    } else if (info.itemType == Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                        String id = info.getDeepShortcutId();
                        String packageName = intent.getPackage();
                        getSystemService(LauncherApps.class)
                                .startShortcut(packageName, id, null, null, info.user);
                    } else {
                        startItemInfoActivity(info);
                    }

                    mControllers.uiController.onTaskbarIconLaunched(info);
                } catch (NullPointerException | ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT)
                            .show();
                    Log.e(TAG, "Unable to launch. tag=" + info + " intent=" + intent, e);
                }
            }
        } else if (tag instanceof AppInfo) {
            startItemInfoActivity((AppInfo) tag);
        } else {
            Log.e(TAG, "Unknown type clicked: " + tag);
        }

        AbstractFloatingView.closeAllOpenViews(this);
    }

    private void startItemInfoActivity(ItemInfo info) {
        Intent intent = new Intent(info.getIntent())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (info.user.equals(Process.myUserHandle())) {
                // TODO(b/216683257): Use startActivityForResult for search results that require it.
                startActivity(intent);
            } else {
                getSystemService(LauncherApps.class).startMainActivity(
                        intent.getComponent(), info.user, intent.getSourceBounds(), null);
            }
        } catch (NullPointerException | ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT)
                    .show();
            Log.e(TAG, "Unable to launch. tag=" + info + " intent=" + intent, e);
        }
    }

    /**
     * Called when we detect a long press in the nav region before passing the gesture slop.
     * @return Whether taskbar handled the long press, and thus should cancel the gesture.
     */
    public boolean onLongPressToUnstashTaskbar() {
        return mControllers.taskbarStashController.onLongPressToUnstashTaskbar();
    }

    /**
     * Called when we detect a motion down or up/cancel in the nav region while stashed.
     * @param animateForward Whether to animate towards the unstashed hint state or back to stashed.
     */
    public void startTaskbarUnstashHint(boolean animateForward) {
        mControllers.taskbarStashController.startUnstashHint(animateForward);
    }

    protected boolean isUserSetupComplete() {
        return mIsUserSetupComplete;
    }

    protected boolean isNavBarKidsModeActive() {
        return mIsNavBarKidsMode && isThreeButtonNav();
    }

    /**
     * Called when we determine the touchable region.
     *
     * @param exclude {@code true} then the magnification region computation will omit the window.
     */
    public void excludeFromMagnificationRegion(boolean exclude) {
        if (mIsExcludeFromMagnificationRegion == exclude) {
            return;
        }

        mIsExcludeFromMagnificationRegion = exclude;
        if (exclude) {
            mWindowLayoutParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        } else {
            mWindowLayoutParams.privateFlags &=
                    ~WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        }
        mWindowManager.updateViewLayout(mDragLayer, mWindowLayoutParams);
    }

    public void showPopupMenuForIcon(BubbleTextView btv) {
        setTaskbarWindowFullscreen(true);
        btv.post(() -> mControllers.taskbarPopupController.showForIcon(btv));
    }

    protected void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarActivityContext:");

        pw.println(String.format(
                "%s\tmNavMode=%s", prefix, mNavMode));
        pw.println(String.format(
                "%s\tmImeDrawsImeNavBar=%b", prefix, mImeDrawsImeNavBar));
        pw.println(String.format(
                "%s\tmIsUserSetupComplete=%b", prefix, mIsUserSetupComplete));
        pw.println(String.format(
                "%s\tmWindowLayoutParams.height=%dpx", prefix, mWindowLayoutParams.height));
        pw.println(String.format(
                "%s\tmBindInProgress=%b", prefix, mBindingItems));
        mControllers.dumpLogs(prefix + "\t", pw);
    }
}
