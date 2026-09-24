/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.FloatProperty;
import android.util.Property;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.C;
import com.google.android.gms.cast.framework.CastContext;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SyncedLyricsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.chromecast.ChromecastController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSlider;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.CastSync;
import org.telegram.ui.Cells.AudioPlayerCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChooseQualityLayout;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.SelectAudioAlert;
import org.telegram.ui.SyncedLyricsEditorFragment;

import java.io.File;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

public class AudioPlayerAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate, DownloadController.FileDownloadProgressListener {

    public static AudioPlayerAlert instance;

    private View actionBarBackground;
    private ActionBar actionBar;
    private View actionBarShadow;
    private View playerShadow;
    private boolean searchWas;
    private boolean searching;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter listAdapter;
    private RecyclerListView lyricsListView;
    private LinearLayoutManager lyricsLayoutManager;
    private LyricsAdapter lyricsAdapter;
    private View lyricsViewportFade;
    private final ArrayList<Integer> visibleLyrics = new ArrayList<>();
    private boolean showingLyrics;
    private boolean lyricsModeRequested;
    private boolean lyricsUserScrolling;
    private boolean lyricsUserDragging;
    private boolean applySavedModeOnOpen;
    private boolean dismissing;
    private SyncedLyricsController.Lyrics currentLyrics;
    private LinearLayout emptyView;
    private ImageView emptyImageView;
    private TextView emptyTitleTextView;
    private TextView emptySubtitleTextView;

    private FrameLayout playerLayout;
    private FrameLayout playbackControlsView;
    private ButtonWithCounterView saveToProfileButton;
    private ButtonWithCounterView unsaveFromProfileButton;
    private ItemTouchHelper itemTouchHelper;
    private CoverContainer coverContainer;
    private ClippingTextViewSwitcher titleTextView;
    private RLottieImageView prevButton;
    private RLottieImageView nextButton;
    private ClippingTextViewSwitcher authorTextView;
    private int activeLyricsLine = Integer.MIN_VALUE;
    private int activeLyricsRow = RecyclerView.NO_POSITION;
    private ActionBarMenuItem optionsButton;
    private ImageView lyricsExpandButton;
    private boolean lyricsExpandShown;
    private boolean fullscreenLyrics;
    private String playlistChromeTitle;
    private int containerMeasuredHeight;
    private int containerMeasuredWidth;
    private float lyricsPageProgress;
    private AnimatorSet lyricsPageAnimation;
    private boolean lyricsPagerTracking;
    private boolean lyricsPagerMaybeTracking;
    private float lyricsPagerStartProgress;
    private float lyricsPagerOffsetProgress;
    private int lyricsPagerStartX;
    private int lyricsPagerStartY;
    private int lyricsPagerPointerId;
    private VelocityTracker lyricsPagerVelocity;
    private final float lyricsPagerTouchSlop = AndroidUtilities.getPixelsInCM(0.3f, true);
    private int maximumVelocity;
    private ChooseQualityLayout.QualityIcon optionsIcon;
    private ActionBarMenuSubItem castItem;
    private CastMediaRouteButton castItemButton;
    private boolean castAvailable;
    private LineProgressView progressView;
    private SeekBarView seekBarView;
    private SimpleTextView timeTextView;
    private ActionBarMenuItem playbackSpeedButton;
    private SpeedIconDrawable speedIcon;
    private ActionBarMenuSlider.SpeedSlider speedSlider;
    private boolean slidingSpeed;
    private ActionBarMenuSubItem[] speedItems = new ActionBarMenuSubItem[6];
    private TextView durationTextView;
    private ActionBarMenuItem repeatButton;
    private ActionBarMenuSubItem repeatSongItem;
    private ActionBarMenuSubItem repeatListItem;
    private ActionBarMenuSubItem shuffleListItem;
    private ActionBarMenuSubItem reverseOrderItem;
    private ImageView playButton;
    private PlayPauseDrawable playPauseDrawable;
    private FrameLayout blurredView;
    private BackupImageView bigAlbumConver;
    private ActionBarMenuItem addItem;
    private ActionBarMenuItem searchItem;
    private boolean blurredAnimationInProgress;
    private View[] buttons = new View[5];
    private SpringAnimation seekBarBufferSpring;

    private boolean draggingSeekBar;

    private long lastBufferedPositionCheck;
    private boolean currentAudioFinishedLoading;

    private boolean scrollToSong = true;

    private int searchOpenPosition = -1;
    private int searchOpenOffset;

    private final boolean isProfilePlaylist;
    private final boolean padWithItem;

    private MessagesController.SavedMusicList savedMusicList;
    private boolean isMyList() {
        return savedMusicList != null && savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private ArrayList<MessageObject> playlist;
    private MessageObject lastMessageObject;
    private boolean noforwards;

    private int scrollOffsetY = Integer.MAX_VALUE;
    private int topBeforeSwitch;

    private boolean inFullSize;

    private String currentFile;

    private AnimatorSet actionBarAnimation;

    private int lastTime;
    private int lastDuration;

    private int TAG;

    private LaunchActivity parentActivity;
    int rewindingState;
    float rewindingProgress = -1;

    int rewindingForwardPressedCount;
    long lastRewindingTime;
    long lastUpdateRewindingPlayerTime;

    private boolean wasLight;
    private final Runnable resumeLyricsFollow = () -> {
        lyricsUserScrolling = false;
        updateLyricsFollow(true);
    };

    private final static float[] speeds = new float[] {
            .5f, 1f, 1.2f, 1.5f, 1.7f, 2f
    };

    private final Runnable forwardSeek = new Runnable() {
        @Override
        public void run() {
            long duration = MediaController.getInstance().getDuration();
            if (duration == 0 || duration == C.TIME_UNSET) {
                lastRewindingTime = System.currentTimeMillis();
                return;
            }
            float currentProgress = rewindingProgress;

            long t = System.currentTimeMillis();
            long dt = t - lastRewindingTime;
            lastRewindingTime = t;
            long updateDt = t - lastUpdateRewindingPlayerTime;
            if (rewindingForwardPressedCount == 1) {
                dt = dt * 3 - dt;
            } else if (rewindingForwardPressedCount == 2) {
                dt = dt * 6 - dt;
            } else {
                dt = dt * 12 - dt;
            }
            long currentTime = (long) (duration * currentProgress + dt);
            currentProgress = currentTime / (float) duration;
            if (currentProgress < 0) {
                currentProgress = 0;
            }
            rewindingProgress = currentProgress;
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null && messageObject.isMusic()) {
                if (!MediaController.getInstance().isMessagePaused()) {
                    MediaController.getInstance().getPlayingMessageObject().audioProgress = rewindingProgress;
                }
                updateProgress(messageObject);
            }
            if (rewindingState == 1 && rewindingForwardPressedCount > 0 && MediaController.getInstance().isMessagePaused()) {
                if (updateDt > 200 || rewindingProgress == 0) {
                    lastUpdateRewindingPlayerTime = t;
                    MediaController.getInstance().seekToProgress(MediaController.getInstance().getPlayingMessageObject(), currentProgress);
                }
                if (rewindingForwardPressedCount > 0 && rewindingProgress > 0) {
                    AndroidUtilities.runOnUIThread(forwardSeek, 16);
                }
            }
        }
    };

    public AudioPlayerAlert(final Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        doNotOverlayNavigationBar = true;
        fixNavigationBar();

        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject != null) {
            currentAccount = messageObject.currentAccount;
        } else {
            currentAccount = UserConfig.selectedAccount;
        }
        lyricsModeRequested = SyncedLyricsController.getInstance(currentAccount).isLyricsModePreferred();

        parentActivity = (LaunchActivity) context;
        maximumVelocity = ViewConfiguration.get(context).getScaledMaximumFlingVelocity();

        TAG = DownloadController.getInstance(currentAccount).generateObserverTag();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.syncedLyricsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);

        containerView = new FrameLayout(context) {

            private RectF rect = new RectF();
            private boolean ignoreLayout = false;
            private int lastMeasturedHeight;
            private int lastMeasturedWidth;

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (isDismissed()) return false;
                if (handleLyricsPagerTouch(e)) return true;
                return super.onTouchEvent(e);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int totalHeight = MeasureSpec.getSize(heightMeasureSpec);
                int w = MeasureSpec.getSize(widthMeasureSpec);
                containerMeasuredHeight = totalHeight;
                containerMeasuredWidth = w;
                if (totalHeight != lastMeasturedHeight || w != lastMeasturedWidth) {
                    if (blurredView.getTag() != null) {
                        showAlbumCover(false, false);
                    }
                    lastMeasturedWidth = w;
                    lastMeasturedHeight = totalHeight;
                    if (fullscreenLyrics) post(() -> {
                        if (fullscreenLyrics) applyFullscreenPlayerLayout(true);
                    });
                }
                ignoreLayout = true;
                playerLayout.setVisibility(searchWas || keyboardVisible ? INVISIBLE : VISIBLE);
                playerShadow.setVisibility(playerLayout.getVisibility());
                int availableHeight = totalHeight - getPaddingTop();

                LayoutParams layoutParams = (LayoutParams) listView.getLayoutParams();
                layoutParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;

                // Unconditional: the lyrics surface must never be measured with a stale margin,
                // which is what let a first frame render as a near-fullscreen sheet.
                layoutParams = (LayoutParams) lyricsListView.getLayoutParams();
                layoutParams.topMargin = getLyricsContentTop();
                layoutParams.bottomMargin = dp(getPlayerHeight());

                layoutParams = (LayoutParams) actionBarShadow.getLayoutParams();
                layoutParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;

                layoutParams = (LayoutParams) blurredView.getLayoutParams();
                layoutParams.topMargin = -getPaddingTop();

                int contentSize = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
                if (playlist.size() > 1) {
                    contentSize += backgroundPaddingTop + playlist.size() * dp(56);
                }
                int padding;
                if (searching || keyboardVisible) {
                    padding = dp(8);
                } else {
                    padding = (contentSize < availableHeight ? availableHeight - contentSize : availableHeight - (int) (availableHeight / 5 * 3.5f)) + dp(8);
                    if (padding > availableHeight - dp(179 + (!isMyList() && !noforwards ? 52 : 0) + 150)) {
                        padding = availableHeight - dp(179 + (!isMyList() && !noforwards ? 52 : 0) + 150);
                    }
                    if (padding < 0) {
                        padding = 0;
                    }
                }
//                if (isMyList()) {
//                    padding = Math.min(padding/2, dp(240));
//                }
                if (padWithItem) {
                    padding = 0;
                }
                if (listView.getPaddingTop() != padding) {
                    listView.setPadding(0, padding, 0, searching && keyboardVisible ? 0 : listView.getPaddingBottom());
                }
                ignoreLayout = false;
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY));
                inFullSize = getMeasuredHeight() >= totalHeight;
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
                updateEmptyViewPosition();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (handleLyricsPagerTouch(ev)) {
                    return true;
                }
                if (showingLyrics || lyricsPageProgress > 0f) {
                    return super.onInterceptTouchEvent(ev);
                }
                if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && actionBar.getAlpha() == 0.0f) {
                    boolean dismiss;
                    if (listAdapter.getItemCount() > 0) {
                        dismiss = ev.getY() < scrollOffsetY + dp(12);
                    } else {
                        dismiss = ev.getY() < getMeasuredHeight() - dp(179 + (!isMyList() && !noforwards ? 52 : 0) + 12);
                    }
                    if (dismiss) {
                        cancelLyricsPagerTracking();
                        dismiss();
                        return true;
                    }
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                // Same guard as ViewPagerFixed: while the dominant axis is still undecided a nested
                // list must not be able to lock the shell out of the gesture.
                if (disallowIntercept && lyricsPagerMaybeTracking && !lyricsPagerTracking) {
                    return;
                }
                super.requestDisallowInterceptTouchEvent(disallowIntercept);
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (fullscreenLyrics) {
                    canvas.drawColor(getThemedColor(Theme.key_dialogBackground));
                    return;
                }
                if (playlist.size() <= 1) {
                    int playlistTop = getMeasuredHeight() - playerLayout.getMeasuredHeight() - backgroundPaddingTop;
                    int lyricsTop = getLyricsContentTop() - backgroundPaddingTop;
                    int top = (int) lerp(playlistTop, lyricsTop, lyricsPageProgress);
                    shadowDrawable.setBounds(0, top, getMeasuredWidth(), getMeasuredHeight());
                    shadowDrawable.draw(canvas);
                    if (isProfilePlaylist && !isLyricsChromeActive()) {
                        actionBar.setVisibility(View.GONE);
                    }
                } else {
                    if (listView.getVisibility() != View.VISIBLE && !showingLyrics) return;

                    int offset = dp(13);
                    int top = scrollOffsetY - backgroundPaddingTop - offset;
                    top += listView.getTranslationY();
                    if (isProfilePlaylist) {
                        top -= ActionBar.getCurrentActionBarHeight();
                        top += dp(10);
                    }
                    int y = top + dp(20);

                    int height = getMeasuredHeight() + dp(15) + backgroundPaddingTop;
                    float rad = 1.0f;

                    float moveProgress = 0;
                    if (!isProfilePlaylist && top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
                        float toMove = offset + dp(11 - 7);
                        moveProgress = Math.min(1.0f, (ActionBar.getCurrentActionBarHeight() - top - backgroundPaddingTop) / toMove);
                        float availableToMove = ActionBar.getCurrentActionBarHeight() - toMove;

                        int diff = (int) (availableToMove * moveProgress);
                        top -= diff;
                        y -= diff;
                        height += diff;
                        rad = 1.0f - moveProgress;
                    }

                    top += (int) (AndroidUtilities.statusBarHeight * (1f - moveProgress));
                    y += (int) (AndroidUtilities.statusBarHeight * (1f - moveProgress));

                    if (lyricsPageProgress > 0f) {
                        final int lyricsTop = getLyricsContentTop() - backgroundPaddingTop;
                        if (scrollOffsetY == Integer.MAX_VALUE) {
                            // Opened straight into Lyrics: the playlist sheet never resolved, so
                            // there is no top to morph from. Interpolating from the sentinel used
                            // to produce a garbage bound (float precision at 2^31) and the sheet
                            // background was simply not painted, exposing the dim behind it.
                            top = lyricsTop;
                            y = top + dp(20);
                            rad = 1f;
                        } else {
                            // The sheet edge morphs between the playlist geometry and the canonical
                            // lyrics geometry so both pages stay inside one shell while swiping.
                            top = (int) lerp(top, lyricsTop, lyricsPageProgress);
                            y = (int) lerp(y, top + dp(20), lyricsPageProgress);
                            rad = lerp(rad, 1f, lyricsPageProgress);
                        }
                    }

                    shadowDrawable.setBounds(0, top, getMeasuredWidth(), height);
                    shadowDrawable.draw(canvas);

                    if (!isProfilePlaylist && rad != 1.0f) {
                        Theme.dialogs_onlineCirclePaint.setColor(getThemedColor(Theme.key_dialogBackground));
                        rect.set(backgroundPaddingLeft, backgroundPaddingTop + top, getMeasuredWidth() - backgroundPaddingLeft, backgroundPaddingTop + top + dp(24));
                        canvas.drawRoundRect(rect, dp(12) * rad, dp(12) * rad, Theme.dialogs_onlineCirclePaint);
                    }

                    if (!isProfilePlaylist && rad != 0) {
                        float alphaProgress = 1.0f;
                        int w = dp(36);
                        rect.set((getMeasuredWidth() - w) / 2, y, (getMeasuredWidth() + w) / 2, y + dp(4));
                        int color = getThemedColor(Theme.key_sheet_scrollUp);
                        int alpha = Color.alpha(color);
                        Theme.dialogs_onlineCirclePaint.setColor(color);
                        Theme.dialogs_onlineCirclePaint.setAlpha((int) (alpha * alphaProgress * rad));
                        canvas.drawRoundRect(rect, dp(2), dp(2), Theme.dialogs_onlineCirclePaint);
                    }

                    if (isProfilePlaylist && !isLyricsChromeActive()) {
                        actionBar.setVisibility(View.VISIBLE);
                        actionBar.setTranslationY(Math.max(0, top - backgroundPaddingTop - dp(10) + dp(6) * (1.0f - actionBarSlide) - actionBar.getTop()));
                        actionBarShadow.setTranslationY(Math.max(0, top - backgroundPaddingTop - dp(10) + dp(6) * (1.0f - actionBarSlide) - actionBar.getTop()));
                    }
                }
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                Bulletin.addDelegate(this, new Bulletin.Delegate() {
                    @Override
                    public int getBottomOffset(int tag) {
                        return playerLayout.getHeight();
                    }
                });
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                Bulletin.removeDelegate(this);
            }
        };
        containerView.setWillNotDraw(false);
        containerView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);

        actionBar = new ActionBar(context, resourcesProvider) {
            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                containerView.invalidate();
            }
        };
        actionBar.setBackgroundColor(0);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setItemsColor(getThemedColor(Theme.key_player_actionBarTitle), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_player_actionBarSelector), false);
        actionBar.setTitleColor(getThemedColor(Theme.key_player_actionBarTitle));
        actionBar.setSubtitleColor(getThemedColor(Theme.key_player_actionBarSubtitle));
        actionBar.setOccupyStatusBar(true);

        final ActionBarMenu menu = actionBar.createMenu();
        menu.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBarBackground = new View(context);
        actionBarBackground.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
        actionBar.addView(actionBarBackground, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBarBackground.setAlpha(0.0f);
        actionBar.setAlpha(0.0f);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (fullscreenLyrics) setFullscreenLyrics(false);
                    else dismiss();
                } else {
                    onSubItemClick(id);
                }
            }
        });

        actionBarShadow = new View(context);
        actionBarShadow.setAlpha(0.0f);
        actionBarShadow.setBackgroundResource(R.drawable.header_shadow);

        playerShadow = new View(context);
        playerShadow.setBackgroundColor(getThemedColor(Theme.key_dialogShadowLine));
        
        playerLayout = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (playbackSpeedButton != null && durationTextView != null) {
                    int x = durationTextView.getLeft() - dp(4) - playbackSpeedButton.getMeasuredWidth();
                    playbackSpeedButton.layout(x, playbackSpeedButton.getTop(), x + playbackSpeedButton.getMeasuredWidth(), playbackSpeedButton.getBottom());
                }
            }
        };

        coverContainer = new CoverContainer(context) {

            private long pressTime;

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (getImageReceiver().hasBitmapImage()) {
                        showAlbumCover(true, true);
                        pressTime = SystemClock.elapsedRealtime();
                    }
                } else if (action != MotionEvent.ACTION_MOVE) {
                    if (SystemClock.elapsedRealtime() - pressTime >= 400) {
                        showAlbumCover(false, true);
                    }
                }
                return true;
            }

            @Override
            protected void onImageUpdated(ImageReceiver imageReceiver) {
                final Bitmap b = imageReceiver.getBitmap();
                final int padding = (b != null && imageReceiver.hasImageLoaded() || imageReceiver.hasBitmapImage()) ? AndroidUtilities.dp(64) : 0;
                setCustomPaddingRight(padding, true);
                if (blurredView.getTag() != null) {
                    bigAlbumConver.setImageBitmap(b);
                }
            }
        };
        playerLayout.addView(coverContainer, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.RIGHT, 0, 20, 20, 0));

        titleTextView = new ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                final TextView textView = new MarqueeTextView(context);
                textView.setTextColor(getThemedColor(Theme.key_player_actionBarTitle));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                textView.setTypeface(AndroidUtilities.bold());
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setSingleLine(true);
                return textView;
            }
        };
        playerLayout.addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 20, 20, 20, 0));

        authorTextView = new ClippingTextViewSwitcher(context) {
            @Override
            protected TextView createTextView() {
                final TextView textView = new MarqueeTextView(context);
                textView.setTextColor(getThemedColor(Theme.key_player_time));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                textView.setEllipsize(TextUtils.TruncateAt.END);
                textView.setSingleLine(true);
                textView.setPadding(dp(6), 0, dp(6), dp(1));
                textView.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), dp(4), dp(4)));

                textView.setOnClickListener(view -> {
                    int dialogsCount = MessagesController.getInstance(currentAccount).getTotalDialogsCount();
                    if (dialogsCount <= 10 || TextUtils.isEmpty(textView.getText().toString())) {
                        return;
                    }
                    String query = textView.getText().toString();
                    if (parentActivity.getActionBarLayout().getLastFragment() instanceof DialogsActivity) {
                        DialogsActivity dialogsActivity = (DialogsActivity) parentActivity.getActionBarLayout().getLastFragment();
                        if (!dialogsActivity.onlyDialogsAdapter()) {
                            dialogsActivity.setShowSearch(query, FiltersView.FILTER_INDEX_MUSIC);
                            dismiss();
                            return;
                        }
                    }
                    DialogsActivity fragment = new DialogsActivity(null);
                    fragment.setSearchString(query);
                    fragment.setInitialSearchType(FiltersView.FILTER_INDEX_MUSIC);
                    parentActivity.presentFragment(fragment, false, false);
                    dismiss();
                });
                return textView;
            }
        };
        playerLayout.addView(authorTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 14, 47, 20, 0));

        seekBarView = new SeekBarView(context, resourcesProvider) {
            @Override
            boolean onTouch(MotionEvent ev) {
                if (rewindingState != 0) {
                    return false;
                }
                return super.onTouch(ev);
            }
        };
        seekBarView.setLineWidth(4);
        seekBarView.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (stop) {
                    MediaController.getInstance().seekToProgress(MediaController.getInstance().getPlayingMessageObject(), progress);
                }
                MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                if (messageObject != null && messageObject.isMusic()) {
                    updateProgress(messageObject);
                }
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
                draggingSeekBar = pressed;
            }

            @Override
            public CharSequence getContentDescription() {
                final String time = LocaleController.formatPluralString("Minutes", lastTime / 60) + ' ' + LocaleController.formatPluralString("Seconds", lastTime % 60);
                final String totalTime = LocaleController.formatPluralString("Minutes", lastDuration / 60) + ' ' + LocaleController.formatPluralString("Seconds", lastDuration % 60);
                return LocaleController.formatString("AccDescrPlayerDuration", R.string.AccDescrPlayerDuration, time, totalTime);
            }
        });
        seekBarView.setReportChanges(true);
        playerLayout.addView(seekBarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38 + 6, Gravity.TOP | Gravity.LEFT, 5, 67, 5, 0));

        seekBarBufferSpring = new SpringAnimation(new FloatValueHolder(0))
                .setSpring(new SpringForce()
                        .setStiffness(750f)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY))
                .addUpdateListener((animation, value, velocity) -> seekBarView.setBufferedProgress(value / 1000f));

        progressView = new LineProgressView(context);
        progressView.setVisibility(View.INVISIBLE);
        progressView.setBackgroundColor(getThemedColor(Theme.key_player_progressBackground));
        progressView.setProgressColor(getThemedColor(Theme.key_player_progress));
        playerLayout.addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.TOP | Gravity.LEFT, 21, 90, 21, 0));

        timeTextView = new SimpleTextView(context);
        timeTextView.setTextSize(12);
        timeTextView.setText("0:00");
        timeTextView.setTextColor(getThemedColor(Theme.key_player_time));
        timeTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        playerLayout.addView(timeTextView, LayoutHelper.createFrame(100, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 20, 98, 0, 0));

        durationTextView = new TextView(context);
        durationTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        durationTextView.setTextColor(getThemedColor(Theme.key_player_time));
        durationTextView.setGravity(Gravity.CENTER);
        durationTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        playerLayout.addView(durationTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 96, 20, 0));

        playbackSpeedButton = new ActionBarMenuItem(context, null, 0, getThemedColor(Theme.key_player_time), false, resourcesProvider);
        playbackSpeedButton.setLongClickEnabled(false);
        playbackSpeedButton.setShowSubmenuByMove(false);
        playbackSpeedButton.setAdditionalYOffset(-dp(224));
        playbackSpeedButton.setContentDescription(LocaleController.getString(R.string.AccDescrPlayerSpeed));
        playbackSpeedButton.setDelegate(id -> {
            if (id < 0 || id >= speeds.length) {
                return;
            }
            MediaController.getInstance().setPlaybackSpeed(true, speeds[id]);
            updatePlaybackButton(true);
        });
        playbackSpeedButton.setIcon(speedIcon = new SpeedIconDrawable(true));
        final float[] toggleSpeeds = new float[] { 1.0F, 1.5F, 2F };
        speedSlider = new ActionBarMenuSlider.SpeedSlider(getContext(), resourcesProvider);
        speedSlider.setRoundRadiusDp(6);
        speedSlider.setDrawShadow(true);
        speedSlider.setOnValueChange((value, isFinal) -> {
            slidingSpeed = !isFinal;
            MediaController.getInstance().setPlaybackSpeed(true, speedSlider.getSpeed(value));
        });
        speedItems[0] = playbackSpeedButton.addSubItem(0, R.drawable.msg_speed_slow, LocaleController.getString(R.string.SpeedSlow));
        speedItems[1] = playbackSpeedButton.addSubItem(1, R.drawable.msg_speed_normal, LocaleController.getString(R.string.SpeedNormal));
        speedItems[2] = playbackSpeedButton.addSubItem(2, R.drawable.msg_speed_medium, LocaleController.getString(R.string.SpeedMedium));
        speedItems[3] = playbackSpeedButton.addSubItem(3, R.drawable.msg_speed_fast, LocaleController.getString(R.string.SpeedFast));
        speedItems[4] = playbackSpeedButton.addSubItem(4, R.drawable.msg_speed_veryfast, LocaleController.getString(R.string.SpeedVeryFast));
        speedItems[5] = playbackSpeedButton.addSubItem(5, R.drawable.msg_speed_superfast, LocaleController.getString(R.string.SpeedSuperFast));
        if (AndroidUtilities.density >= 3.0f) {
            playbackSpeedButton.setPadding(0, 1, 0, 0);
        }
        playbackSpeedButton.setAdditionalXOffset(dp(8));
        playbackSpeedButton.setAdditionalYOffset(-dp(400));
        playbackSpeedButton.setShowedFromBottom(true);
        playerLayout.addView(playbackSpeedButton, LayoutHelper.createFrame(36, 36, Gravity.TOP | Gravity.RIGHT, 0, 86, 20, 0));
        playbackSpeedButton.setOnClickListener(v -> {
            float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(true);
            int index = -1;
            for (int i = 0; i < toggleSpeeds.length; ++i) {
                if (currentPlaybackSpeed - 0.1F <= toggleSpeeds[i]) {
                    index = i;
                    break;
                }
            }
            index++;
            if (index >= toggleSpeeds.length) {
                index = 0;
            }
            MediaController.getInstance().setPlaybackSpeed(true, toggleSpeeds[index]);

            checkSpeedHint();
        });
        playbackSpeedButton.setOnLongClickListener(view -> {
            final float speed = MediaController.getInstance().getPlaybackSpeed(true);
            speedSlider.setSpeed(speed, false);
            speedSlider.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider));
            updatePlaybackButton(false);
            playbackSpeedButton.setDimMenu(.15f);
            playbackSpeedButton.toggleSubMenu(speedSlider, null);
            MessagesController.getGlobalNotificationsSettings().edit().putInt("speedhint", -15).apply();
            return true;
        });
        updatePlaybackButton(false);

        FrameLayout bottomView = playbackControlsView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                int dist = ((right - left) - dp(8 + 48 * 5)) / 4;
                for (int a = 0; a < 5; a++) {
                    int l = dp(4 + 48 * a) + dist * a;
                    int t = dp(9);
                    buttons[a].layout(l, t, l + buttons[a].getMeasuredWidth(), t + buttons[a].getMeasuredHeight());
                }
            }
        };
        playerLayout.addView(bottomView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 66, Gravity.TOP | Gravity.LEFT, 0, 111, 0, 0));

        buttons[0] = repeatButton = new ActionBarMenuItem(context, null, 0, 0, false, resourcesProvider);
        repeatButton.setLongClickEnabled(false);
        repeatButton.setShowSubmenuByMove(false);
        repeatButton.setAdditionalYOffset(-dp(166));
        repeatButton.setBackgroundDrawable(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(18)));
        bottomView.addView(repeatButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP));
        repeatButton.setOnClickListener(v -> {
            updateSubMenu();
            repeatButton.toggleSubMenu();
        });
        repeatSongItem = repeatButton.addSubItem(3, R.drawable.player_new_repeatone, LocaleController.getString(R.string.RepeatSong));
        repeatListItem = repeatButton.addSubItem(4, R.drawable.player_new_repeatall, LocaleController.getString(R.string.RepeatList));
        repeatButton.addColoredGap().getLayoutParams().height = dp(4);
        shuffleListItem = repeatButton.addSubItem(2, R.drawable.player_new_shuffle, LocaleController.getString(R.string.ShuffleList));
        repeatButton.addColoredGap().getLayoutParams().height = dp(4);
        reverseOrderItem = repeatButton.addSubItem(1, R.drawable.player_new_order, LocaleController.getString(R.string.ReverseOrder));
        repeatButton.setShowedFromBottom(true);

        repeatButton.setDelegate(id -> {
            if (id == 1 || id == 2) {
                boolean oldReversed = SharedConfig.playOrderReversed;
                if (SharedConfig.playOrderReversed && id == 1 || SharedConfig.shuffleMusic && id == 2) {
                    MediaController.getInstance().setPlaybackOrderType(0);
                } else {
                    MediaController.getInstance().setPlaybackOrderType(id);
                }
                listAdapter.notifyDataSetChanged();
                if (oldReversed != SharedConfig.playOrderReversed) {
                    listView.stopScroll();
                    scrollToCurrentSong(false);
                }
            } else {
                if (id == 4) {
                    if (SharedConfig.repeatMode == 1) {
                        SharedConfig.setRepeatMode(0);
                    } else {
                        SharedConfig.setRepeatMode(1);
                    }
                } else {
                    if (SharedConfig.repeatMode == 2) {
                        SharedConfig.setRepeatMode(0);
                    } else {
                        SharedConfig.setRepeatMode(2);
                    }
                }
            }
            updateRepeatButton();
        });

        final int iconColor = getThemedColor(Theme.key_player_button);
        float touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        buttons[1] = prevButton = new RLottieImageView(context) {
            float startX;
            float startY;

            int pressedCount = 0;

            long lastTime;
            long lastUpdateTime;

            private final Runnable pressedRunnable = new Runnable() {
                @Override
                public void run() {
                    pressedCount++;
                    if (pressedCount == 1) {
                        rewindingState = -1;
                        rewindingProgress = MediaController.getInstance().getPlayingMessageObject().audioProgress;
                        lastTime = System.currentTimeMillis();
                        AndroidUtilities.runOnUIThread(this, 2000);
                        AndroidUtilities.runOnUIThread(backSeek);
                    } else if (pressedCount == 2) {
                        AndroidUtilities.runOnUIThread(this, 2000);
                    }
                }
            };

            private final Runnable backSeek = new Runnable() {
                @Override
                public void run() {
                    long duration = MediaController.getInstance().getDuration();
                    if (duration == 0 || duration == C.TIME_UNSET) {
                        lastTime = System.currentTimeMillis();
                        return;
                    }
                    float currentProgress = rewindingProgress;

                    long t = System.currentTimeMillis();
                    long dt = t - lastTime;
                    lastTime = t;
                    long updateDt = t - lastUpdateTime;
                    if (pressedCount == 1) {
                        dt *= 3;
                    } else if (pressedCount == 2) {
                        dt *= 6;
                    } else {
                        dt *= 12;
                    }
                    long currentTime = (long) (duration * currentProgress - dt);
                    currentProgress = currentTime / (float) duration;
                    if (currentProgress < 0) {
                        currentProgress = 0;
                    }
                    rewindingProgress = currentProgress;
                    MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                    if (messageObject != null && messageObject.isMusic()) {
                        updateProgress(messageObject);
                    }
                    if (rewindingState == -1 && pressedCount > 0) {
                        if (updateDt > 200 || rewindingProgress == 0) {
                            lastUpdateTime = t;
                            if (rewindingProgress == 0) {
                                MediaController.getInstance().seekToProgress(MediaController.getInstance().getPlayingMessageObject(), 0);
                                MediaController.getInstance().pauseByRewind();
                            } else {
                                MediaController.getInstance().seekToProgress(MediaController.getInstance().getPlayingMessageObject(), currentProgress);
                            }
                        }
                        if (pressedCount > 0 && rewindingProgress > 0) {
                            AndroidUtilities.runOnUIThread(backSeek, 16);
                        }
                    }
                }
            };

            long startTime;

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (seekBarView.isDragging() || rewindingState == 1) {
                    return false;
                }
                float x = event.getRawX();
                float y = event.getRawY();

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = x;
                        startY = y;
                        startTime = System.currentTimeMillis();
                        rewindingState = 0;
                        AndroidUtilities.runOnUIThread(pressedRunnable, 300);
                        if (getBackground() != null) {
                            getBackground().setHotspot(startX, startY);
                        }
                        setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = x - startX;
                        float dy = y - startY;

                        if ((dx * dx + dy * dy) > touchSlop * touchSlop && rewindingState == 0) {
                            AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                            setPressed(false);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                        AndroidUtilities.cancelRunOnUIThread(backSeek);
                        if (rewindingState == 0 && event.getAction() == MotionEvent.ACTION_UP && (System.currentTimeMillis() - startTime < 300)) {
                            MediaController.getInstance().playPreviousMessage();
                            prevButton.setProgress(0f);
                            prevButton.playAnimation();
                        }
                        if (pressedCount > 0) {
                            lastUpdateTime = 0;
                            backSeek.run();
                            MediaController.getInstance().resumeByRewind();
                        }
                        rewindingProgress = -1;
                        setPressed(false);
                        rewindingState = 0;
                        pressedCount = 0;
                        break;
                }
                return true;
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        };
        prevButton.setScaleType(ImageView.ScaleType.CENTER);
        prevButton.setAnimation(R.raw.player_prev, 20, 20);
        prevButton.setLayerColor("Triangle 3", iconColor);
        prevButton.setLayerColor("Triangle 4", iconColor);
        prevButton.setLayerColor("Rectangle 4", iconColor);
        prevButton.setBackgroundDrawable(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(22)));
        bottomView.addView(prevButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP));
        prevButton.setContentDescription(LocaleController.getString(R.string.AccDescrPrevious));

        buttons[2] = playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setImageDrawable(playPauseDrawable = new PlayPauseDrawable(28));
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_player_button), PorterDuff.Mode.MULTIPLY));
        playButton.setBackgroundDrawable(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(24)));
        bottomView.addView(playButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP));
        playButton.setOnClickListener(v -> {
            if (MediaController.getInstance().isDownloadingCurrentMessage()) {
                return;
            }
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(MediaController.getInstance().getPlayingMessageObject());
            } else {
                MediaController.getInstance().pauseMessage(MediaController.getInstance().getPlayingMessageObject());
            }
        });

        buttons[3] = nextButton = new RLottieImageView(context) {

            float startX;
            float startY;
            boolean pressed;

            private final Runnable pressedRunnable = new Runnable() {
                @Override
                public void run() {
                    if (MediaController.getInstance().getPlayingMessageObject() == null) {
                        return;
                    }
                    rewindingForwardPressedCount++;
                    if (rewindingForwardPressedCount == 1) {
                        pressed = true;
                        rewindingState = 1;
                        if (MediaController.getInstance().isMessagePaused()) {
                            startForwardRewindingSeek();
                        } else if (rewindingState == 1) {
                            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
                            lastUpdateRewindingPlayerTime = 0;
                        }
                        MediaController.getInstance().setPlaybackSpeed(true, 4);
                        AndroidUtilities.runOnUIThread(this, 2000);
                    } else if (rewindingForwardPressedCount == 2) {
                        MediaController.getInstance().setPlaybackSpeed(true, 7);
                        AndroidUtilities.runOnUIThread(this, 2000);
                    } else {
                        MediaController.getInstance().setPlaybackSpeed(true, 13);
                    }
                }
            };

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (seekBarView.isDragging() || rewindingState == -1) {
                    return false;
                }
                float x = event.getRawX();
                float y = event.getRawY();

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        pressed = false;
                        startX = x;
                        startY = y;
                        AndroidUtilities.runOnUIThread(pressedRunnable, 300);
                        if (getBackground() != null) {
                            getBackground().setHotspot(startX, startY);
                        }
                        setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = x - startX;
                        float dy = y - startY;

                        if ((dx * dx + dy * dy) > touchSlop * touchSlop && !pressed) {
                            AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                            setPressed(false);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        if (!pressed && event.getAction() == MotionEvent.ACTION_UP && isPressed()) {
                            MediaController.getInstance().playNextMessage();
                            nextButton.setProgress(0f);
                            nextButton.playAnimation();
                        }
                        AndroidUtilities.cancelRunOnUIThread(pressedRunnable);
                        if (rewindingForwardPressedCount > 0) {
                            MediaController.getInstance().setPlaybackSpeed(true, 1f);
                            if (MediaController.getInstance().isMessagePaused()) {
                                lastUpdateRewindingPlayerTime = 0;
                                forwardSeek.run();
                            }
                        }
                        rewindingState = 0;
                        setPressed(false);
                        rewindingForwardPressedCount = 0;
                        rewindingProgress = -1;
                        break;
                }
                return true;
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            }

        };
        nextButton.setScaleType(ImageView.ScaleType.CENTER);
        nextButton.setAnimation(R.raw.player_prev, 20, 20);
        nextButton.setLayerColor("Triangle 3", iconColor);
        nextButton.setLayerColor("Triangle 4", iconColor);
        nextButton.setLayerColor("Rectangle 4", iconColor);
        nextButton.setRotation(180f);
        nextButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(22)));
        bottomView.addView(nextButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP));
        nextButton.setContentDescription(LocaleController.getString(R.string.Next));

        buttons[4] = optionsButton = new ActionBarMenuItem(context, null, 0, iconColor, false, resourcesProvider);
        optionsButton.setIcon(optionsIcon = new ChooseQualityLayout.QualityIcon(context, R.drawable.ic_ab_other, resourcesProvider));
        optionsButton.setLongClickEnabled(false);
        optionsButton.setAdditionalYOffset(-dp(157 + 40));
        optionsButton.setBackgroundDrawable(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(18)));
        optionsButton.setOnClickListener(this::showMenuOptions);

        bottomView.addView(optionsButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP));


        castItemButton = new CastMediaRouteButton(context) {
            @Override
            public void stateUpdated(boolean connected) {
                updateColors();
                if (optionsIcon != null) {
                    optionsIcon.setCasting(CastSync.isActive(), true);
                }
            }
        };
        castAvailable = true;
        try {
            castItemButton.setRouteSelector(CastContext.getSharedInstance(context).getMergedSelector());
        } catch (Exception e) {
            FileLog.e(e);
            castAvailable = false;
        }
        castItemButton.setVisibility(View.INVISIBLE);
        if (optionsIcon != null) {
            optionsIcon.setCasting(CastSync.isActive(), true);
        }

        optionsButton.setShowedFromBottom(true);
        optionsButton.setDelegate(this::onSubItemClick);
        optionsButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        containerView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView.setOnTouchListener((v, event) -> true);

        emptyImageView = new ImageView(context);
        emptyImageView.setImageResource(R.drawable.music_empty);
        emptyImageView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogEmptyImage), PorterDuff.Mode.MULTIPLY));
        emptyView.addView(emptyImageView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        emptyTitleTextView = new TextView(context);
        emptyTitleTextView.setTextColor(getThemedColor(Theme.key_dialogEmptyText));
        emptyTitleTextView.setGravity(Gravity.CENTER);
        emptyTitleTextView.setText(LocaleController.getString(R.string.NoAudioFound));
        emptyTitleTextView.setTypeface(AndroidUtilities.bold());
        emptyTitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        emptyTitleTextView.setPadding(dp(40), 0, dp(40), 0);
        emptyView.addView(emptyTitleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 11, 0, 0));

        emptySubtitleTextView = new TextView(context);
        emptySubtitleTextView.setTextColor(getThemedColor(Theme.key_dialogEmptyText));
        emptySubtitleTextView.setGravity(Gravity.CENTER);
        emptySubtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptySubtitleTextView.setPadding(dp(40), 0, dp(40), 0);
        emptyView.addView(emptySubtitleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 6, 0, 0));

        listView = new RecyclerListView(context) {

            boolean ignoreLayout;

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);

                if (searchOpenPosition != -1 && !actionBar.isSearchFieldVisible()) {
                    ignoreLayout = true;
                    layoutManager.scrollToPositionWithOffset(searchOpenPosition, searchOpenOffset - listView.getPaddingTop());
                    super.onLayout(false, l, t, r, b);
                    ignoreLayout = false;
                    searchOpenPosition = -1;
                } else if (scrollToSong) {
                    scrollToSong = false;
                    ignoreLayout = true;
                    if (scrollToCurrentSong(true)) {
                        super.onLayout(false, l, t, r, b);
                    }
                    ignoreLayout = false;
                }
            }

            @Override
            protected boolean allowSelectChildAtPosition(float x, float y) {
                return y < playerLayout.getY() - listView.getTop();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }
        };
        listView.setClipToPadding(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
        listView.setHorizontalScrollBarEnabled(false);
        listView.setVerticalScrollBarEnabled(false);
        containerView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
        listView.setOnItemClickListener((view, position) -> {
            if (view instanceof AudioPlayerCell) {
                ((AudioPlayerCell) view).didPressedButton();
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (view instanceof AudioPlayerCell && !isMyList()) {
                showOptions((AudioPlayerCell) view, ((AudioPlayerCell) view).getMessageObject());
                return true;
            }
            return false;
        });

        lyricsListView = new RecyclerListView(context) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                updateLyricsPadding();
                if (h > 0 && showingLyrics) {
                    post(() -> updateLyricsFollow(false));
                }
            }
        };
        lyricsListView.setClipToPadding(false);
        lyricsListView.setVerticalScrollBarEnabled(false);
        lyricsListView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
        lyricsListView.setBackgroundColor(Color.TRANSPARENT);
        lyricsListView.setLayoutManager(lyricsLayoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        lyricsListView.setAdapter(lyricsAdapter = new LyricsAdapter(context));
        lyricsListView.setItemAnimator(null);
        lyricsListView.setVisibility(View.GONE);
        lyricsListView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= visibleLyrics.size()) return;
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.getInstance(currentAccount).getLyrics(playing);
            int line = visibleLyrics.get(position);
            if (playing != null && line >= 0 && line < lyrics.lines.size() && lyrics.lines.get(line).timed) {
                MediaController.getInstance().seekToProgressMs(playing, lyrics.lines.get(line).timeMs);
                lyricsUserScrolling = false;
                AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
                updateLyrics(false);
            }
        });
        FrameLayout.LayoutParams lyricsParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT);
        lyricsParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        lyricsParams.bottomMargin = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
        containerView.addView(lyricsListView, lyricsParams);

        // Viewport edge fade: continuous top/bottom gradient that hides lines scrolling in and out.
        // Drawn above the list so it applies uniformly, independent of per-row alpha or blur.
        lyricsViewportFade = new View(context) {
            private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            { setWillNotDraw(false); }
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                final int w = getWidth(), h = getHeight();
                final int bg = getThemedColor(Theme.key_player_background);
                final int fadeH = Math.min(dp(72), h / 3);
                // Top fade: opaque background -> transparent
                fadePaint.setShader(new LinearGradient(0, 0, 0, fadeH,
                        bg, 0, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, fadeH, fadePaint);
                // Bottom fade: transparent -> opaque background
                fadePaint.setShader(new LinearGradient(0, h - fadeH, 0, h,
                        0, bg, Shader.TileMode.CLAMP));
                canvas.drawRect(0, h - fadeH, w, h, fadePaint);
            }
        };
        lyricsViewportFade.setVisibility(View.GONE);
        containerView.addView(lyricsViewportFade, lyricsParams);

        lyricsListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
                    // Never fight the finger: drop the driven follow and its pending pre-roll.
                    cancelLyricsFollow();
                    lyricsUserDragging = true;
                    lyricsUserScrolling = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && lyricsUserScrolling) {
                    lyricsUserDragging = false;
                    AndroidUtilities.runOnUIThread(resumeLyricsFollow, 1200);
                } else if (newState == RecyclerView.SCROLL_STATE_SETTLING && !lyricsUserDragging) {
                    // Programmatic smoothScrollBy() also settles; it must not suspend following.
                    lyricsUserScrolling = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateLyricsDepth();
            }
        });
        lyricsListView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(@NonNull View view) { applyLyricsDepth(view); }
            @Override public void onChildViewDetachedFromWindow(@NonNull View view) { }
        });

        // Expand control. Same component, iconography, ripple, pressed state and reveal spec as
        // Telegram's own text-writer expander (ChatActivityEnterView.richButton): it lives inside
        // the surface it expands, not in the action bar, so it is present in every lyrics
        // configuration regardless of playlist size, playlist source or action-bar fade state.
        lyricsExpandButton = new ImageView(context);
        lyricsExpandButton.setImageResource(R.drawable.iv_fullscreen);
        lyricsExpandButton.setScaleType(ImageView.ScaleType.CENTER);
        lyricsExpandButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_player_actionBarTitle), PorterDuff.Mode.SRC_IN));
        // Opaque backing, like the rounded surface Telegram puts behind its own editor history
        // buttons: lyricsListView keeps clipToPadding=false so timed lines scroll through the top
        // of the viewport, and the control must occupy its own space rather than sit over text.
        lyricsExpandButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(40), getThemedColor(Theme.key_dialogBackground), getThemedColor(Theme.key_player_actionBarSelector)));
        ScaleStateListAnimator.apply(lyricsExpandButton);
        lyricsExpandButton.setContentDescription(getString(R.string.AccSwitchToFullscreen));
        lyricsExpandButton.setOnClickListener(v -> setFullscreenLyrics(true));
        lyricsExpandButton.setVisibility(View.GONE);
        lyricsExpandButton.setAlpha(0.0f);
        lyricsExpandButton.setScaleX(0.6f);
        lyricsExpandButton.setScaleY(0.6f);
        FrameLayout.LayoutParams expandParams = LayoutHelper.createFrame(40, 40, Gravity.TOP | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 6, 0, 6, 0);
        expandParams.topMargin = lyricsParams.topMargin + dp(4);
        containerView.addView(lyricsExpandButton, expandParams);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int offset = dp(13);
                    int top = scrollOffsetY - backgroundPaddingTop - offset;
                    if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight() && listView.canScrollVertically(1)) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(padWithItem ? 1 : 0);
                        if (holder != null && holder.itemView.getTop() > dp(7)) {
                            listView.smoothScrollBy(0, holder.itemView.getTop() - dp(7));
                        }
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(getCurrentFocus());
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateLayout();
                updateEmptyViewPosition();

                if (!searchWas) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    if (padWithItem) {
                        firstVisibleItem = Math.max(0, firstVisibleItem - 1);
                    }
                    int visibleItemCount = firstVisibleItem == RecyclerView.NO_POSITION ? 0 : Math.abs(layoutManager.findLastVisibleItemPosition() - firstVisibleItem) + 1;
                    int totalItemCount = recyclerView.getAdapter().getItemCount();

                    MessageObject playingMessageObject = MediaController.getInstance().getPlayingMessageObject();
                    if (SharedConfig.playOrderReversed) {
                        if (firstVisibleItem < 10) {
                            MediaController.getInstance().loadMoreMusic();
                        }
                    } else {
                        if (firstVisibleItem + visibleItemCount > totalItemCount - 10) {
                            MediaController.getInstance().loadMoreMusic();
                        }
                    }
                }
            }
        });

        saveToProfileButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("+ ");
        sb.setSpan(new ColoredImageSpan(R.drawable.filled_track_add), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(getString(R.string.AudioAddToProfile));
        saveToProfileButton.setText(sb);
        saveToProfileButton.setOnClickListener(v -> {
            final MessageObject messageObject1 = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject1 == null || parentActivity == null) {
                return;
            }
            saveToProfile(messageObject1, true, () -> {}, false);
            setVisibleInProfile(true);
            BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToMyProfileSaved))
                .show();
        });
        playerLayout.addView(saveToProfileButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 12, 12, 12, 12));

        unsaveFromProfileButton = new ButtonWithCounterView(context, resourcesProvider).setRound().setNeutral();
        unsaveFromProfileButton.setText(getString(R.string.AudioRemoveFromProfile));
        unsaveFromProfileButton.setOnClickListener(v -> {
            final MessageObject messageObject1 = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject1 == null || parentActivity == null) {
                return;
            }
            saveToProfile(messageObject1, false, () -> {}, false);
            setVisibleInProfile(false);
            BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                .createSimpleBulletin(R.raw.ic_delete, getString(R.string.AudioSaveToMyProfileUnsaved))
                .show();
        });
        playerLayout.addView(unsaveFromProfileButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 42, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 12, 12, 12, 12));

        savedMusicList = MediaController.getInstance().currentSavedMusicList;
        isProfilePlaylist = savedMusicList != null;
        actionBar.menuOccupyBack = isProfilePlaylist;
        padWithItem = isMyList();
        ((FrameLayout.LayoutParams) lyricsListView.getLayoutParams()).bottomMargin = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
        playlist = MediaController.getInstance().getPlaylist();
        if (isMyList()) {
            addItem = menu.addItem(8, R.drawable.msg_add);
        }
        searchItem = menu.addItem(0, R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {

            @Override
            public void onSearchCollapse() {
                if (searching) {
                    searchWas = false;
                    searching = false;
                    setAllowNestedScroll(true);
                    listAdapter.search(null);
                    if (addItem != null) {
                        addItem.setVisibility(View.VISIBLE);
                    }
                    updateLyricsChrome();
                }
            }

            @Override
            public void onSearchExpand() {
                searchOpenPosition = layoutManager.findLastVisibleItemPosition();
                View firstVisView = layoutManager.findViewByPosition(searchOpenPosition);
                searchOpenOffset = firstVisView == null ? 0 : firstVisView.getTop();
                searching = true;
                setAllowNestedScroll(false);
                listAdapter.notifyDataSetChanged();
                if (addItem != null) {
                    addItem.setVisibility(View.GONE);
                }
                updateLyricsChrome();
            }

            @Override
            public void onTextChanged(EditText editText) {
                if (editText.length() > 0) {
                    listAdapter.search(editText.getText().toString());
                } else {
                    searchWas = false;
                    listAdapter.search(null);
                }
            }
        });
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));
        EditTextBoldCursor editText = searchItem.getSearchField();
        editText.setHint(LocaleController.getString(R.string.Search));
        editText.setTextColor(getThemedColor(Theme.key_player_actionBarTitle));
        editText.setHintTextColor(getThemedColor(Theme.key_player_time));
        editText.setCursorColor(getThemedColor(Theme.key_player_actionBarTitle));

        if (isProfilePlaylist) {
            listView.setSections();
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));

            actionBar.setAlpha(1.0f);
            actionBarBackground.setAlpha(0.0f);
            actionBarSlideProperty.set(actionBar, 0.0f);
        }

        listAdapter.setup();
        listAdapter.notifyDataSetChanged();
        updateLyricsChrome();

        actionBar.setTitle(LocaleController.getString(R.string.AttachMusic));
        if (savedMusicList != null) {
            if (savedMusicList.dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                actionBar.setTitle(getString(R.string.ProfilePlaylistTitleMine));
            } else {
                actionBar.setTitle(formatString(R.string.ProfilePlaylistTitle, DialogObject.getShortName(savedMusicList.dialogId)));
            }
        } else if (messageObject != null && !MediaController.getInstance().currentPlaylistIsGlobalSearch()) {
            long did = messageObject.getDialogId();
            if (DialogObject.isEncryptedDialog(did)) {
                TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount).getEncryptedChat(DialogObject.getEncryptedChatId(did));
                if (encryptedChat != null) {
                    TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(encryptedChat.user_id);
                    if (user != null) {
                        actionBar.setTitle(ContactsController.formatName(user.first_name, user.last_name));
                    }
                }
            } else if (did == UserConfig.getInstance(currentAccount).getClientUserId()) {
                if (messageObject.getSavedDialogId() == UserObject.ANONYMOUS) {
                    actionBar.setTitle(LocaleController.getString(R.string.AnonymousForward));
                } else {
                    actionBar.setTitle(LocaleController.getString(R.string.SavedMessages));
                }
            } else if (DialogObject.isUserDialog(did)) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(did);
                if (user != null) {
                    actionBar.setTitle(ContactsController.formatName(user.first_name, user.last_name));
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                if (chat != null) {
                    actionBar.setTitle(chat.title);
                }
            }
        }

        if (isMyList()) {
            saveToProfileButton.setVisibility(View.GONE);
            unsaveFromProfileButton.setVisibility(View.GONE);

            itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
                @Override
                public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                    if (viewHolder.getItemViewType() != 0) {
                        return 0;
                    }
                    return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
                }

                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                    int fromPosition = viewHolder.getAdapterPosition();
                    int toPosition = target.getAdapterPosition();
                    if (padWithItem) {
                        if (fromPosition <= 0 || toPosition <= 0)
                            return false;
                        savedMusicList.move(fromPosition - 1, toPosition - 1);
                    } else {
                        savedMusicList.move(fromPosition, toPosition);
                    }
                    playlist.clear();
                    playlist.addAll(savedMusicList.list);
                    listAdapter.notifyItemMoved(fromPosition, toPosition);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {

                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                    if (viewHolder != null) {
                        listView.hideSelector(false);
                    }
                    if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                        listView.cancelClickRunnables(false);
                        if (viewHolder != null) {
                            viewHolder.itemView.setPressed(true);
                        }
                    }
                    super.onSelectedChanged(viewHolder, actionState);
                    if (viewHolder != null) {
                        viewHolder.itemView.setTag(R.id.dragging, actionState == ItemTouchHelper.ACTION_STATE_DRAG ? true : null);
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    viewHolder.itemView.setPressed(false);
                    viewHolder.itemView.setTag(R.id.dragging, null);
                }

            });
            itemTouchHelper.attachToRecyclerView(listView);
        }

        containerView.addView(playerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 179 + (!isMyList() && !noforwards ? 52 : 0), Gravity.LEFT | Gravity.BOTTOM));
        containerView.addView(playerShadow, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight(), Gravity.LEFT | Gravity.BOTTOM));
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) playerLayout.getLayoutParams();
        layoutParams.height = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
        layoutParams = (FrameLayout.LayoutParams) playerShadow.getLayoutParams();
        layoutParams.bottomMargin = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
        containerView.addView(actionBarShadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3));
        containerView.addView(actionBar);

        blurredView = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (blurredView.getTag() != null) {
                    showAlbumCover(false, true);
                }
                return true;
            }
        };
        blurredView.setAlpha(0.0f);
        blurredView.setVisibility(View.INVISIBLE);
        getContainer().addView(blurredView);

        bigAlbumConver = new BackupImageView(context);
        bigAlbumConver.setAspectFit(true);
        bigAlbumConver.setRoundRadius(dp(8));
        bigAlbumConver.setScaleX(0.9f);
        bigAlbumConver.setScaleY(0.9f);
        blurredView.addView(bigAlbumConver, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 30, 30, 30, 30));

        updateTitle(false);
        updateRepeatButton();
        updateEmptyView();
    }

    /**
     * The visible sheet height. BottomSheet uses this as the entrance and exit translation, so it
     * has to describe the surface that is actually on screen.
     *
     * <p>In Lyrics mode it did not. updateLayout() early-returns while lyrics chrome is active, so
     * when the player opened straight into Lyrics - saved mode Lyrics with the lyrics already
     * cached, which resolves inside the constructor - scrollOffsetY was still Integer.MAX_VALUE and
     * this returned {@code container.getMeasuredHeight() - Integer.MAX_VALUE}, about -2^31.
     * startOpenAnimation() then set that as the starting translationY, throwing the sheet roughly
     * 2^31px above the screen and animating it back to 0: for almost the whole animation nothing of
     * the sheet was on screen and only BottomSheet's SheetBackDrawable (0xFF000000) was visible -
     * the black frame - after which the player snapped into place with no perceptible rise.
     * dismiss() animates back to the same value, which is why closing jumped instead of sliding.
     */
    @Override
    public int getContainerViewHeight() {
        if (playerLayout == null) {
            return 0;
        }
        if (isLyricsChromeActive()) {
            // Lyrics is presented as its own shell whose top is deterministic, so it is measured
            // the same way for a single-song chat as for a full playlist. Ordering matters: the
            // single-song branch below returns the player block alone, which would leave the whole
            // lyrics viewport out of the entrance and exit translation.
            return container.getMeasuredHeight() - getLyricsContentTop();
        } else if (playlist.size() <= 1) {
            return playerLayout.getMeasuredHeight() + backgroundPaddingTop;
        } else if (scrollOffsetY == Integer.MAX_VALUE) {
            // Playlist, but its offset has not resolved yet. Measuring from the sentinel returns
            // about -2^31 and throws the sheet off-screen, so fall back to the player block rather
            // than to lyrics geometry, which is not what this sheet is showing.
            return playerLayout.getMeasuredHeight() + backgroundPaddingTop;
        } else {
            int offset = dp(13);
            int top = scrollOffsetY - backgroundPaddingTop - offset;
//            if (currentSheetAnimationType == 1) {
                top += listView.getTranslationY();
//            }
            if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
                float toMove = offset + dp(11 - 7);
                float moveProgress = Math.min(1.0f, (ActionBar.getCurrentActionBarHeight() - top - backgroundPaddingTop) / toMove);
                float availableToMove = ActionBar.getCurrentActionBarHeight() - toMove;

                int diff = (int) (availableToMove * moveProgress);
                top -= diff;
            }

            top += AndroidUtilities.statusBarHeight;

            return container.getMeasuredHeight() - top;
        }
    }

    private void startForwardRewindingSeek() {
        if (rewindingState == 1) {
            lastRewindingTime = System.currentTimeMillis();
            rewindingProgress = MediaController.getInstance().getPlayingMessageObject().audioProgress;
            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
            AndroidUtilities.runOnUIThread(forwardSeek);
        }
    }

    private void updateEmptyViewPosition() {
        if (emptyView.getVisibility() != View.VISIBLE) {
            return;
        }
        int h = playerLayout.getVisibility() == View.VISIBLE ? dp(150) : -dp(30);
        emptyView.setTranslationY((emptyView.getMeasuredHeight() - containerView.getMeasuredHeight() - h) / 2);
    }

    private void updateEmptyView() {
        emptyView.setVisibility(searching && listAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        updateEmptyViewPosition();
    }

    private boolean scrollToCurrentSong(boolean search) {
        MessageObject playingMessageObject = MediaController.getInstance().getPlayingMessageObject();
        if (playingMessageObject != null) {
            boolean found = false;
            if (search) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof AudioPlayerCell) {
                        if (((AudioPlayerCell) child).getMessageObject() == playingMessageObject) {
                            if (child.getBottom() <= listView.getMeasuredHeight()) {
                                found = true;
                            }
                            break;
                        }
                    }
                }
            }
            if (!found) {
                int idx = playlist.indexOf(playingMessageObject);
                if (padWithItem) {
                    idx++;
                }
                if (idx >= 0) {
                    if (SharedConfig.playOrderReversed) {
                        layoutManager.scrollToPosition(idx);
                    } else {
                        layoutManager.scrollToPosition(playlist.size() - idx);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onCustomMeasure(View view, int width, int height) {
        if (view == blurredView) {
            final int w = getContainer().getMeasuredWidth();
            final int h = getContainer().getMeasuredHeight();
            blurredView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCustomLayout(View view, int left, int top, int right, int bottom) {
        if (view == blurredView) {
            blurredView.layout(0, 0, blurredView.getMeasuredWidth(), blurredView.getMeasuredHeight());
            return true;
        }
        return false;
    }

    private void setMenuItemChecked(ActionBarMenuSubItem item, boolean checked) {
        if (checked) {
            item.setTextColor(getThemedColor(Theme.key_player_buttonActive));
            item.setIconColor(getThemedColor(Theme.key_player_buttonActive));
        } else {
            item.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
            item.setIconColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
        }
    }

    private HintView speedHintView;
    private long lastPlaybackClick;

    private void checkSpeedHint() {
        final long now = System.currentTimeMillis();
        if (now - lastPlaybackClick > 300) {
            int hintValue = MessagesController.getGlobalNotificationsSettings().getInt("speedhint", 0);
            hintValue++;
            if (hintValue > 2) {
                hintValue = -10;
            }
            MessagesController.getGlobalNotificationsSettings().edit().putInt("speedhint", hintValue).apply();
            if (hintValue >= 0) {
                showSpeedHint();
            }
        }
        lastPlaybackClick = now;
    }

    private void showSpeedHint() {
        if (containerView != null) {
            speedHintView = new HintView(getContext(), 5, false) {
                @Override
                public void setVisibility(int visibility) {
                    super.setVisibility(visibility);
                    if (visibility != View.VISIBLE) {
                        try {
                            ((ViewGroup) getParent()).removeView(this);
                        } catch (Exception e) {}
                    }
                }
            };
            speedHintView.setExtraTranslationY(dp(6));
            speedHintView.setText(LocaleController.getString(R.string.SpeedHint));
            playerLayout.addView(speedHintView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, 0, 6, 0));
            speedHintView.showForView(playbackSpeedButton, true);
        }
    }

    private void updateSubMenu() {
        setMenuItemChecked(shuffleListItem, SharedConfig.shuffleMusic);
        setMenuItemChecked(reverseOrderItem, SharedConfig.playOrderReversed);
        setMenuItemChecked(repeatListItem, SharedConfig.repeatMode == 1);
        setMenuItemChecked(repeatSongItem, SharedConfig.repeatMode == 2);
    }

    private boolean equals(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    private void updatePlaybackButton(boolean animated) {
        if (playbackSpeedButton == null) {
            return;
        }
        float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(true);
        speedIcon.setValue(currentPlaybackSpeed, animated);
        speedSlider.setSpeed(currentPlaybackSpeed, animated);
        updateColors();

        boolean isFinal = !slidingSpeed;
        slidingSpeed = false;

        for (int a = 0; a < speedItems.length; a++) {
            if (isFinal && equals(currentPlaybackSpeed, speeds[a])) {
                speedItems[a].setColors(getThemedColor(Theme.key_featuredStickers_addButtonPressed), getThemedColor(Theme.key_featuredStickers_addButtonPressed));
            } else {
                speedItems[a].setColors(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), getThemedColor(Theme.key_actionBarDefaultSubmenuItem));
            }
        }
    }

    public void updateColors() {
        if (playbackSpeedButton != null) {
            float currentPlaybackSpeed = MediaController.getInstance().getPlaybackSpeed(true);
            final int color = getThemedColor(!equals(currentPlaybackSpeed, 1.0f) ? Theme.key_featuredStickers_addButtonPressed : Theme.key_inappPlayerClose);
            if (speedIcon != null) {
                speedIcon.setColor(color);
            }
            playbackSpeedButton.setBackground(Theme.createSelectorDrawable(color & 0x19ffffff, 1, dp(14)));
        }
        if (castItem != null) {
            castItem.setEnabledByColor(castItemButton != null && castItemButton.isConnected(), getThemedColor(Theme.key_actionBarDefaultSubmenuItem), getThemedColor(Theme.key_actionBarDefaultSubmenuItemIcon), getThemedColor(Theme.key_featuredStickers_addButton));
            castItem.setSelectorColor(castItemButton != null && castItemButton.isConnected() ? Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), .10f) : getThemedColor(Theme.key_listSelector));
        }
    }

    private void onSubItemClick(int id) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null || parentActivity == null) {
            return;
        }
        if (id == 1) {
            forward(messageObject);
        } else if (id == 2) {
            share(messageObject);
        } else if (id == 4) {
            if (UserConfig.selectedAccount != currentAccount) {
                parentActivity.switchToAccount(currentAccount, true);
            }
            
            Bundle args = new Bundle();
            long did = messageObject.getDialogId();
            if (DialogObject.isEncryptedDialog(did)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
            } else if (DialogObject.isUserDialog(did)) {
                args.putLong("user_id", did);
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                if (chat != null && chat.migrated_to != null) {
                    args.putLong("migrated_to", did);
                    did = -chat.migrated_to.channel_id;
                }
                args.putLong("chat_id", -did);
            }
            args.putInt("message_id", messageObject.getId());
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
            parentActivity.presentFragment(new ChatActivity(args), false, false);
            dismiss();
        } else if (id == 5) {
            saveToMusic(messageObject);
        } else if (id == 6) {
            ChromecastController.getInstance().setCurrentMediaAndCastIfNeeded(MediaController.getInstance().getCurrentChromecastMedia());
            castItemButton.performClick();
        } else if (id == 7) {
            saveToProfile(messageObject, false, () -> {
                if (savedMusicList != null) {
                    savedMusicList.remove(messageObject);
                    if (savedMusicList.list.isEmpty()) {
                        MediaController.getInstance().cleanup();
                        dismiss();
                    } else {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.musicListLoaded, savedMusicList);
                    }
                }
            }, false);
        } else if (id == 8) {
            new SelectAudioAlert(getContext(), true, null, audio -> {
                if (audio == null || savedMusicList == null) return;
                final TLRPC.Document document = audio.getDocument();
                if (document == null) return;
                if (document.id != 0) {
                    final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
                    req.id = new TLRPC.TL_inputDocument();
                    req.id.id = document.id;
                    req.id.access_hash = document.access_hash;
                    req.id.file_reference = document.file_reference;
                    if (savedMusicList != null) {
                        savedMusicList.add(document);
                    }
                    playlist.clear();
                    playlist.addAll(savedMusicList.list);
                    listAdapter.notifyDataSetChanged();
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
                    return;
                }
                final AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
                progressDialog.showDelayed(180);
                final File localFile = new File(audio.messageOwner.attachPath);
                if (!localFile.exists()) return;
                FileLoader.getInstance(currentAccount).uploadFile(localFile.getAbsolutePath(), file -> {
                    if (file == null) {
                        progressDialog.dismiss();
                        return;
                    }
                    final TLRPC.TL_messages_uploadMedia req = new TLRPC.TL_messages_uploadMedia();
                    req.peer = MessagesController.getInstance(currentAccount).getInputPeer(UserConfig.getInstance(currentAccount).getClientUserId());
                    req.media = new TLRPC.TL_inputMediaUploadedDocument();
                    req.media.file = file;
                    req.media.mime_type = document.mime_type;
                    req.media.attributes.addAll(document.attributes);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                        progressDialog.dismiss();
                        if (res instanceof TLRPC.TL_messageMediaDocument) {
                            final TLRPC.TL_messageMediaDocument r = (TLRPC.TL_messageMediaDocument) res;

                            final TLRPC.TL_account_saveMusic req2 = new TLRPC.TL_account_saveMusic();
                            req2.id = new TLRPC.TL_inputDocument();
                            req2.id.id = r.document.id;
                            req2.id.access_hash = r.document.access_hash;
                            req2.id.file_reference = r.document.file_reference;
                            if (savedMusicList != null) {
                                savedMusicList.add(r.document);
                            }
                            playlist.clear();
                            playlist.addAll(savedMusicList.list);
                            listAdapter.notifyDataSetChanged();
                            ConnectionsManager.getInstance(currentAccount).sendRequest(req2, null);
                        }
                    }));
                });
            }, null).withoutSavedMusic().show();
        }
    }

    private void showAlbumCover(boolean show, boolean animated) {
        if (show) {
            if (blurredView.getVisibility() == View.VISIBLE || blurredAnimationInProgress) {
                return;
            }
            blurredView.setTag(1);
            bigAlbumConver.setImageBitmap(coverContainer.getImageReceiver().getBitmap());
            blurredAnimationInProgress = true;
            ScrimOptions.makeGlobalBlurBitmaps((bitmapBg, bitmapOptions) ->
                blurredView.setBackground(new BitmapDrawable(bitmapBg)));

            blurredView.setVisibility(View.VISIBLE);
            blurredView.animate().alpha(1.0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    blurredAnimationInProgress = false;
                }
            }).start();
            bigAlbumConver.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        } else {
            if (blurredView.getVisibility() != View.VISIBLE) {
                return;
            }
            blurredView.setTag(null);
            if (animated) {
                blurredAnimationInProgress = true;
                blurredView.animate().alpha(0.0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        blurredView.setVisibility(View.INVISIBLE);
                        bigAlbumConver.setImageBitmap(null);
                        blurredAnimationInProgress = false;
                    }
                }).start();
                bigAlbumConver.animate().scaleX(0.9f).scaleY(0.9f).setDuration(180).start();
            } else {
                blurredView.setAlpha(0.0f);
                blurredView.setVisibility(View.INVISIBLE);
                bigAlbumConver.setImageBitmap(null);
                bigAlbumConver.setScaleX(0.9f);
                bigAlbumConver.setScaleY(0.9f);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidReset) {
            updateTitle(id == NotificationCenter.messagePlayingDidReset && (Boolean) args[1]);
            if (id == NotificationCenter.messagePlayingPlayStateChanged) {
                updateLyricsFollow(true);
                // Nothing to do for the word motion: the whole of it is a function of the playback
                // position, so a pause freezes it exactly where the clock stopped and a resume
                // carries on from wherever the clock has since moved on to. There is no animator
                // to settle, nothing to replay, and no pop either way.
            }
            if (id == NotificationCenter.messagePlayingDidReset || id == NotificationCenter.messagePlayingPlayStateChanged) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = listView.getChildAt(a);
                    if (view instanceof AudioPlayerCell) {
                        AudioPlayerCell cell = (AudioPlayerCell) view;
                        MessageObject messageObject = cell.getMessageObject();
                        if (messageObject != null && (messageObject.isVoice() || messageObject.isMusic())) {
                            cell.updateButtonState(false, true);
                        }
                    }
                }
                if (id == NotificationCenter.messagePlayingPlayStateChanged) {
                    if (MediaController.getInstance().getPlayingMessageObject() != null) {
                        if (MediaController.getInstance().isMessagePaused()) {
                            startForwardRewindingSeek();
                        } else if (rewindingState == 1 && rewindingProgress != -1f) {
                            AndroidUtilities.cancelRunOnUIThread(forwardSeek);
                            lastUpdateRewindingPlayerTime = 0;
                            forwardSeek.run();
                            rewindingProgress = -1f;
                        }
                    }
                }
            } else {
                MessageObject messageObject = (MessageObject) args[0];
                if (messageObject.eventId != 0) {
                    return;
                }
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = listView.getChildAt(a);
                    if (view instanceof AudioPlayerCell) {
                        AudioPlayerCell cell = (AudioPlayerCell) view;
                        MessageObject messageObject1 = cell.getMessageObject();
                        if (messageObject1 != null && (messageObject1.isVoice() || messageObject1.isMusic())) {
                            cell.updateButtonState(false, true);
                        }
                    }
                }
            }
            if (optionsIcon != null) {
                optionsIcon.setCasting(CastSync.isActive(), true);
            }
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
            if (messageObject != null && messageObject.isMusic()) {
                updateProgress(messageObject);
            }
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updatePlaybackButton(true);
        } else if (id == NotificationCenter.musicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            playlist = MediaController.getInstance().getPlaylist();
            listAdapter.notifyDataSetChanged();
        } else if (id == NotificationCenter.moreMusicDidLoad) {
            savedMusicList = MediaController.getInstance().currentSavedMusicList;
            playlist = MediaController.getInstance().getPlaylist();
            listAdapter.notifyDataSetChanged();
            if (SharedConfig.playOrderReversed) {
                listView.stopScroll();
                int addedCount = (Integer) args[0];
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                int position = layoutManager.findLastVisibleItemPosition();
                if (position != RecyclerView.NO_POSITION) {
                    View firstVisView = layoutManager.findViewByPosition(position);
                    int offset = firstVisView == null ? 0 : firstVisView.getTop();
                    layoutManager.scrollToPositionWithOffset(position + addedCount, offset);
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String name = (String) args[0];
            if (name.equals(currentFile)) {
                updateTitle(false);
                currentAudioFinishedLoading = true;
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            String name = (String) args[0];
            if (name.equals(currentFile)) {
                MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
                if (messageObject == null) {
                    return;
                }
                Long loadedSize = (Long) args[1];
                Long totalSize = (Long) args[2];
                float bufferedProgress;
                if (currentAudioFinishedLoading) {
                    bufferedProgress = 1.0f;
                } else {
                    long newTime = SystemClock.elapsedRealtime();
                    if (Math.abs(newTime - lastBufferedPositionCheck) >= 500) {
                        bufferedProgress = MediaController.getInstance().isStreamingCurrentAudio() ? FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(messageObject.audioProgress, currentFile) : 1.0f;
                        lastBufferedPositionCheck = newTime;
                    } else {
                        bufferedProgress = -1;
                    }
                }
                if (bufferedProgress != -1) {
                    seekBarBufferSpring.getSpring().setFinalPosition(bufferedProgress * 1000);
                    seekBarBufferSpring.start();
                }
            }
        } else if (id == NotificationCenter.musicIdsLoaded) {
            updateTitle(false);
        } else if (id == NotificationCenter.syncedLyricsChanged) {
            updateLyrics(true);
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    private void updateLayout() {
        if (dismissing) return;
        if (isLyricsChromeActive()) {
            // The playlist is off-screen; its scroll offset must not drive the lyrics geometry,
            // the action bar fade, or the profile header position. It must still be resolved once,
            // though: the sheet background is drawn from it, and leaving it at its sentinel is what
            // left the shell unpainted when the player opened directly into Lyrics.
            if (scrollOffsetY == Integer.MAX_VALUE) {
                listView.setTopGlowOffset(scrollOffsetY = listView.getPaddingTop());
            }
            updateLyricsGeometry();
            updateLightStatusBar();
            containerView.invalidate();
            return;
        }
        if (listView.getChildCount() <= 0) {
            listView.setTopGlowOffset(scrollOffsetY = listView.getPaddingTop());
            updateLyricsGeometry();
            containerView.invalidate();
            return;
        }
        View child = listView.getChildAt(0);
        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
        int top = child instanceof AudioPlayerCell ? child.getTop() : child.getBottom();
        int newOffset = dp(7);
        if (top >= dp(7) && holder != null && holder.getAdapterPosition() == 0) {
            newOffset = top;
        }
        boolean show = newOffset <= dp(12);
        if (show && actionBar.getTag() == null || !show && actionBar.getTag() != null) {
            actionBar.setTag(show ? 1 : null);
            if (actionBarAnimation != null) {
                actionBarAnimation.cancel();
                actionBarAnimation = null;
            }
            actionBarAnimation = new AnimatorSet();
            if (isProfilePlaylist) {
                actionBarAnimation.playTogether(
                    ObjectAnimator.ofFloat(actionBar, actionBarSlideProperty, show ? 1.0f : 0.0f),
                    ObjectAnimator.ofFloat(actionBarBackground, View.ALPHA, show ? 1.0f : 0.0f),
                    ObjectAnimator.ofFloat(actionBarShadow, View.ALPHA, show ? 1.0f : 0.0f)
                );
            } else {
                actionBarAnimation.playTogether(
                    ObjectAnimator.ofFloat(actionBar, View.ALPHA, show ? 1.0f : 0.0f),
                    ObjectAnimator.ofFloat(actionBarShadow, View.ALPHA, show ? 1.0f : 0.0f)
                );
            }
            actionBarAnimation.setDuration(320);
            actionBarAnimation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            actionBarAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {

                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    actionBarAnimation = null;
                }
            });
            actionBarAnimation.start();
        }
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
        newOffset += layoutParams.topMargin - AndroidUtilities.statusBarHeight - dp(11);
        if (scrollOffsetY != newOffset) {
            listView.setTopGlowOffset((scrollOffsetY = newOffset) - layoutParams.topMargin - AndroidUtilities.statusBarHeight);
            containerView.invalidate();
        }
        updateLyricsGeometry();

        int offset = dp(13);
        top = scrollOffsetY - backgroundPaddingTop - offset;
//        if (currentSheetAnimationType == 1) {
            top += listView.getTranslationY();
//        }
        float rad = 1.0f;

        if (top + backgroundPaddingTop < ActionBar.getCurrentActionBarHeight()) {
            float toMove = offset + dp(11 - 7);
            float moveProgress = Math.min(1.0f, (ActionBar.getCurrentActionBarHeight() - top - backgroundPaddingTop) / toMove);

            rad = 1.0f - moveProgress;
        }

        applyLightStatusBar(rad <= 0.5f && isDialogBackgroundLight());
    }

    private boolean isDialogBackgroundLight() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_dialogBackground)) > 0.7f;
    }

    private void applyLightStatusBar(boolean light) {
        if (light != wasLight) {
            AndroidUtilities.setLightStatusBar(this, wasLight = light);
        }
    }

    /**
     * Lyrics modes own their status-bar appearance, because updateLayout()'s playlist sheet-radius
     * rule never runs while they are active and would otherwise leave {@link #wasLight} stale.
     * Fullscreen fills the window with the dialog background; normal lyrics keeps the sheet below
     * the status bar, so the dimmed backdrop is what shows there.
     */
    private void updateLightStatusBar() {
        applyLightStatusBar(fullscreenLyrics && isDialogBackgroundLight());
    }

    // Normal-lyrics viewport target. Playlist mode keeps Telegram's sheet behaviour; Lyrics mode
    // owns one deterministic geometry derived only from the window and the normal control block -
    // never from playlist length, scroll offset, list children, sheet drags, profile pinning, a
    // previous fullscreen state, a previous open, or any pager/animation value.
    private static final float LYRICS_VIEWPORT_FRACTION = 0.29f;
    private static final int LYRICS_VIEWPORT_MIN = 96;
    private static final int LYRICS_VIEWPORT_MAX = 280;

    /** Deterministic normal-lyrics viewport height for the current window. */
    private int getNormalLyricsViewport(int totalHeight, int actionBarTop) {
        final int available = totalHeight - actionBarTop;
        int viewport = Math.round(available * LYRICS_VIEWPORT_FRACTION);
        viewport = Math.max(dp(LYRICS_VIEWPORT_MIN), Math.min(dp(LYRICS_VIEWPORT_MAX), viewport));
        // Never let the viewport overlap the control block, however short the window is.
        return Math.max(0, Math.min(viewport, available - dp(getNormalPlayerHeight())));
    }

    private int getLyricsContentTop() {
        final int actionBarTop = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        if (fullscreenLyrics) return actionBarTop;
        int totalHeight = containerMeasuredHeight;
        if (totalHeight <= 0 && containerView != null) totalHeight = containerView.getMeasuredHeight();
        if (totalHeight <= 0) return actionBarTop;
        return Math.max(actionBarTop, totalHeight - dp(getNormalPlayerHeight()) - getNormalLyricsViewport(totalHeight, actionBarTop));
    }

    private boolean isLyricsGeometryNeeded() {
        return showingLyrics || lyricsModeRequested || applySavedModeOnOpen || lyricsPageProgress > 0f;
    }

    /** True while the shell presents lyrics, so playlist-only chrome must stay out of the way. */
    private boolean isLyricsChromeActive() {
        return fullscreenLyrics || showingLyrics;
    }

    private void updateLyricsGeometry() {
        if (lyricsListView == null || !isLyricsGeometryNeeded()) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lyricsListView.getLayoutParams();
        int top = getLyricsContentTop();
        if (params.topMargin != top) {
            params.topMargin = top;
            lyricsListView.setLayoutParams(params);
        }
        if (lyricsExpandButton != null) {
            FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams) lyricsExpandButton.getLayoutParams();
            int expandTop = top + dp(4);
            if (expandParams.topMargin != expandTop) {
                expandParams.topMargin = expandTop;
                lyricsExpandButton.setLayoutParams(expandParams);
            }
        }
    }

    private int lyricsChromeState = -1;

    /**
     * Mode-aware chrome. Playlist mode keeps Telegram's profile playlist title and search intact;
     * lyrics mode (normal and fullscreen) takes them out of the way so nothing overlays the lyrics.
     */
    private void updateLyricsChrome() {
        if (dismissing) return;
        final boolean lyricsChrome = isLyricsChromeActive();
        if (lyricsChrome && actionBar != null && actionBar.isSearchFieldVisible()) {
            actionBar.closeSearchField();
        }
        if (searchItem != null) {
            searchItem.setVisibility(lyricsChrome ? View.GONE : View.VISIBLE);
        }
        if (addItem != null) {
            addItem.setVisibility(lyricsChrome || searching ? View.GONE : View.VISIBLE);
        }
        final int state = fullscreenLyrics ? 2 : lyricsChrome ? 1 : 0;
        if (state == lyricsChromeState) return;
        lyricsChromeState = state;
        if (lyricsChrome) {
            if (playlistChromeTitle == null) playlistChromeTitle = actionBar.getTitle();
            // No header label in lyrics mode: the player itself says what this is, so fullscreen
            // top chrome is Back and nothing else.
            actionBar.setTitle(null);
            // The profile playlist floats its action bar with the sheet; pin it back so it can never
            // cut through the lyric stream (normal) or split the viewport (fullscreen).
            actionBar.setTranslationY(0);
            actionBarShadow.setTranslationY(0);
            actionBar.setVisibility(fullscreenLyrics ? View.VISIBLE : View.GONE);
            updateLightStatusBar();
        } else {
            if (playlistChromeTitle != null) {
                actionBar.setTitle(playlistChromeTitle);
                playlistChromeTitle = null;
            }
            actionBar.setVisibility(View.VISIBLE);
        }
    }

    private float actionBarSlide;
    private final Property<ActionBar, Float> actionBarSlideProperty = new AnimationProperties.FloatProperty<ActionBar>("actionBarSlide") {
        @Override
        public void setValue(ActionBar actionBar, float value) {
            actionBarSlide = value;
            final View titleTextView = actionBar.getTitleTextView();
            final View backButton = actionBar.getBackButton();

            titleTextView.setTranslationX(dp(-52) * (1.0f - value));
            backButton.setTranslationX(dp(-52) * (1.0f - value));
            if (searchItem != null && searchItem.getSearchContainer() != null) {
                searchItem.getSearchContainer().setClipChildren(false);
                searchItem.getSearchContainer().setClipToPadding(false);
                searchItem.getSearchContainer().setPadding(0, 0, dp(AndroidUtilities.isTablet() ? 74 : 66), 0);
                searchItem.getSearchContainer().setTranslationX(dp(AndroidUtilities.isTablet() ? 74 : 66) + dp(-52) * (1.0f - value));
                if (searchItem.getSearchClearButton() != null) {
                    searchItem.getSearchClearButton().setTranslationX(dp(52) * (1.0f - value));
                }
            }

            backButton.setScaleX(lerp(0.6f, 1.0f, value));
            backButton.setScaleY(lerp(0.6f, 1.0f, value));
            backButton.setAlpha(lerp(0.0f, 1.0f, value));
            containerView.invalidate();
        }

        @Override
        public Float get(ActionBar object) {
            return actionBarSlide;
        }
    };

    @Override
    public void dismiss() {
        if (!dismissing) {
            dismissing = true;
            // Stop owning the surface before the sheet starts sliding out. No page settle, no
            // geometry write and no chrome animation may run while the dismissal is on screen -
            // that is what made outside-tap close glitch.
            cancelLyricsPageAnimation();
            cancelLyricsPagerTracking();
            cancelLyricsFollow();
            AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
        }
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.moreMusicDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.syncedLyricsChanged);
        AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenLyrics) {
            setFullscreenLyrics(false);
            return;
        }
        if (actionBar != null && actionBar.isSearchFieldVisible()) {
            actionBar.closeSearchField();
            return;
        }
        if (blurredView.getTag() != null) {
            showAlbumCover(false, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onFailedDownload(String fileName, boolean canceled) {

    }

    @Override
    public void onSuccessDownload(String fileName) {

    }

    @Override
    public void onProgressDownload(String fileName, long downloadedSize, long totalSize) {
        progressView.setProgress(Math.min(1f, downloadedSize / (float) totalSize), true);
    }

    @Override
    public void onProgressUpload(String fileName, long uploadedSize, long totalSize, boolean isEncrypted) {

    }

    @Override
    public int getObserverTag() {
        return TAG;
    }

    public void updateRepeatButton() {
        int mode = SharedConfig.repeatMode;
        if (mode == 0 || mode == 1) {
            if (SharedConfig.shuffleMusic) {
                if (mode == 0) {
                    repeatButton.setIcon(R.drawable.player_new_shuffle);
                } else {
                    repeatButton.setIcon(R.drawable.player_new_repeat_shuffle);
                }
            } else if (SharedConfig.playOrderReversed) {
                if (mode == 0) {
                    repeatButton.setIcon(R.drawable.player_new_order);
                } else {
                    repeatButton.setIcon(R.drawable.player_new_repeat_reverse);
                }
            } else {
                repeatButton.setIcon(R.drawable.player_new_repeatall);
            }
            if (mode == 0 && !SharedConfig.shuffleMusic && !SharedConfig.playOrderReversed) {
                repeatButton.setTag(Theme.key_player_button);
                repeatButton.setIconColor(getThemedColor(Theme.key_player_button));
                Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_listSelector), true);
                repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatOff));
            } else {
                repeatButton.setTag(Theme.key_player_buttonActive);
                repeatButton.setIconColor(getThemedColor(Theme.key_player_buttonActive));
                Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_player_buttonActive) & 0x19ffffff, true);
                if (mode == 0) {
                    if (SharedConfig.shuffleMusic) {
                        repeatButton.setContentDescription(LocaleController.getString(R.string.ShuffleList));
                    } else {
                        repeatButton.setContentDescription(LocaleController.getString(R.string.ReverseOrder));
                    }
                } else {
                    repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatList));
                }
            }
        } else if (mode == 2) {
            repeatButton.setIcon(R.drawable.player_new_repeatone);
            repeatButton.setTag(Theme.key_player_buttonActive);
            repeatButton.setIconColor(getThemedColor(Theme.key_player_buttonActive));
            Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_player_buttonActive) & 0x19ffffff, true);
            repeatButton.setContentDescription(LocaleController.getString(R.string.AccDescrRepeatOne));
        }
    }

    private void updateProgress(MessageObject messageObject) {
        updateProgress(messageObject, false);
    }

    private void updateProgress(MessageObject messageObject, boolean animated) {
        if (seekBarView != null) {
            int newTime;
            if (seekBarView.isDragging()) {
                newTime = (int) (messageObject.getDuration() * seekBarView.getProgress());
            } else {
                boolean updateRewinding = rewindingProgress >= 0 && (rewindingState == -1 || (rewindingState == 1 && MediaController.getInstance().isMessagePaused()));
                if (updateRewinding) {
                    seekBarView.setProgress(rewindingProgress, animated);
                } else {
                    seekBarView.setProgress(messageObject.audioProgress, animated);
                }

                float bufferedProgress;
                if (currentAudioFinishedLoading) {
                    bufferedProgress = 1.0f;
                } else {
                    long time = SystemClock.elapsedRealtime();
                    if (Math.abs(time - lastBufferedPositionCheck) >= 500) {
                        bufferedProgress = MediaController.getInstance().isStreamingCurrentAudio() ? FileLoader.getInstance(currentAccount).getBufferedProgressFromPosition(messageObject.audioProgress, currentFile) : 1.0f;
                        lastBufferedPositionCheck = time;
                    } else {
                        bufferedProgress = -1;
                    }
                }
                if (bufferedProgress != -1) {
                    seekBarBufferSpring.getSpring().setFinalPosition(bufferedProgress * 1000);
                    seekBarBufferSpring.start();
                }
                if (updateRewinding) {
                    newTime = (int) (messageObject.getDuration() * seekBarView.getProgress());
                    messageObject.audioProgressSec = newTime;
                } else {
                    newTime = messageObject.audioProgressSec;
                }
            }
            if (lastTime != newTime) {
                lastTime = newTime;
                timeTextView.setText(AndroidUtilities.formatShortDuration(newTime));
            }
            seekBarView.updateTimestamps(messageObject, null);
            updateLyrics(true);
        }
    }

    private void updateLyrics(boolean animated) {
        if (lyricsListView == null || dismissing) return;
        MessageObject message = MediaController.getInstance().getPlayingMessageObject();
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.getInstance(currentAccount).getLyrics(message);
        SyncedLyricsController.State state = SyncedLyricsController.getInstance(currentAccount).getState(message);
        if (currentLyrics != lyrics) {
            cancelLyricsFollow();
            currentLyrics = lyrics;
            visibleLyrics.clear();
            lyricsWordTimed = false;
            for (int i = 0; i < lyrics.lines.size(); i++) {
                final SyncedLyricsController.Line line = lyrics.lines.get(i);
                if (lyrics.kind == SyncedLyricsController.Kind.PLAIN || !TextUtils.isEmpty(line.text)) visibleLyrics.add(i);
                // The karaoke presentation is a property of the document, not of one line: a file
                // that genuinely states word timing anywhere is laid out as a karaoke page
                // throughout, so its rows never differ in size or weight from one another. A file
                // that states none is left exactly as it was.
                if (line.timed && line.segments != null && !TextUtils.isEmpty(line.text)) lyricsWordTimed = true;
            }
            lyricsWordTimed &= lyrics.isSynced();
            updateLyricsPadding();
            activeLyricsLine = Integer.MIN_VALUE;
            activeLyricsRow = RecyclerView.NO_POSITION;
            resetKaraoke();
            lyricsAdapter.notifyDataSetChanged();
        }
        if (visibleLyrics.isEmpty()) {
            if (state == SyncedLyricsController.State.LOADING || state == SyncedLyricsController.State.NOT_LOADED) return;
            if (showingLyrics) setShowingLyrics(false, animated);
            lyricsModeRequested = SyncedLyricsController.getInstance(currentAccount).isLyricsModePreferred();
            return;
        }
        if (applySavedModeOnOpen) {
            applySavedModeOnOpen = false;
            // Opening honours the persisted page, without animation: never force Lyrics and never
            // inherit a previous fullscreen or expanded presentation.
            if (lyricsModeRequested) setShowingLyrics(true, false);
        } else if (lyricsModeRequested && !showingLyrics) {
            setShowingLyrics(true, animated);
        }
        final long position = SyncedLyricsController.positionMs(message);
        int index = lyrics.lineAt(position);
        // The visual follow is re-evaluated on every tick so pause, seek and track changes always
        // recompute from the real playback position instead of from a stale schedule.
        updateLyricsFollow(true);
        // Word highlighting is resolved from the same real position, every tick, so a seek or a
        // pause lands exactly where the timestamps say it should instead of unwinding an animation.
        updateKaraoke(index, position);
        if (index == activeLyricsLine) return;
        int oldRow = activeLyricsRow;
        activeLyricsLine = index;
        activeLyricsRow = RecyclerView.NO_POSITION;
        for (int i = 0; i < visibleLyrics.size(); i++) {
            if (visibleLyrics.get(i) == index) {
                activeLyricsRow = i;
                break;
            }
        }
        // No notifyItemChanged here: the timestamp changes the LOGICAL active line only. The
        // visual transition is already in flight from the pre-roll and owns the presentation.
    }

    private void setShowingLyrics(boolean show, boolean animated) {
        if (dismissing || show && visibleLyrics.isEmpty()) return;
        // A programmatic mode change owns the surface; an in-flight drag must not keep writing
        // progress underneath it and strand a partial page.
        cancelLyricsPagerTracking();
        final boolean changed = showingLyrics != show;
        if (show) updateLyricsGeometry();
        showingLyrics = show;
        if (!show && fullscreenLyrics) setFullscreenLyrics(false);
        lyricsListView.setEnabled(show);
        listView.setEnabled(!show);
        listView.animate().cancel();
        listView.setTranslationY(0);
        listView.setAlpha(1f);
        lyricsListView.setAlpha(1f);
        if (animated && changed) {
            animateLyricsPage(show ? 1f : 0f, 0f);
        } else {
            cancelLyricsPageAnimation();
            setLyricsPageProgress(show ? 1f : 0f);
        }
        updateLyricsChrome();
        updateLyricsPadding();
        showLyricsExpandButton(show && !fullscreenLyrics);
        if (show) updateLyricsFollow(false);
        else cancelLyricsFollow();
    }

    /** Reveal spec copied from Telegram's text-writer expander: alpha + 0.6 scale, EASE_OUT_QUINT, 420ms. */
    private void showLyricsExpandButton(boolean show) {
        if (lyricsExpandButton == null || lyricsExpandShown == show) return;
        lyricsExpandShown = show;
        lyricsExpandButton.animate().cancel();
        lyricsExpandButton.setVisibility(View.VISIBLE);
        lyricsExpandButton.animate()
            .alpha(show ? 1.0f : 0.0f)
            .scaleX(show ? 1.0f : 0.6f)
            .scaleY(show ? 1.0f : 0.6f)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .setDuration(420)
            .withEndAction(() -> {
                if (!lyricsExpandShown) lyricsExpandButton.setVisibility(View.GONE);
            })
            .start();
    }

    private void setFullscreenLyrics(boolean fullscreen) {
        if (fullscreenLyrics == fullscreen || fullscreen && !showingLyrics) return;
        final int previousLyricsTop = lyricsListView.getTop();
        fullscreenLyrics = fullscreen;
        if (actionBarAnimation != null) {
            actionBarAnimation.cancel();
            actionBarAnimation = null;
        }
        // Fullscreen offers Back only; there is no second shrink control.
        showLyricsExpandButton(!fullscreen && showingLyrics);
        actionBar.animate().cancel();
        actionBarBackground.animate().cancel();
        actionBarShadow.animate().cancel();
        if (fullscreen) {
            preFullscreenActionBarAlpha = actionBar.getAlpha();
            preFullscreenActionBarBackgroundAlpha = actionBarBackground.getAlpha();
            preFullscreenActionBarShadowAlpha = actionBarShadow.getAlpha();
            preFullscreenActionBarSlide = actionBarSlide;
        }
        if (isProfilePlaylist) actionBarSlideProperty.set(actionBar, fullscreen ? 1f : preFullscreenActionBarSlide);
        updateLyricsChrome();
        actionBar.animate().alpha(fullscreen ? 1f : preFullscreenActionBarAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        actionBarBackground.animate().alpha(fullscreen ? 1f : preFullscreenActionBarBackgroundAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        actionBarShadow.animate().alpha(fullscreen ? 1f : preFullscreenActionBarShadowAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        setAllowNestedScroll(!fullscreen);
        applyFullscreenPlayerLayout(fullscreen);
        updateLyricsGeometry();
        updateLyricsPadding();
        // Expand/collapse continuity: the same viewport slides to its new bounds instead of cutting.
        lyricsListView.animate().cancel();
        lyricsListView.post(() -> {
            int delta = previousLyricsTop - lyricsListView.getTop();
            if (delta != 0) {
                lyricsListView.setTranslationY(delta);
                lyricsListView.animate().translationY(0f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            } else {
                lyricsListView.setTranslationY(0f);
            }
            updateLyricsPadding();
            // Re-target into the new focus centre without touching the timing state, so expanding
            // or collapsing never restarts or skips the synced follow.
            updateLyricsFollow(true);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Playlist <-> Lyrics pager. One shell, two surfaces; no extra fragment, activity, media
    // controller or playback engine is involved - only the presentation changes.
    // ---------------------------------------------------------------------------------------

    private int getLyricsPageWidth() {
        int width = containerMeasuredWidth;
        if (width <= 0 && containerView != null) width = containerView.getMeasuredWidth();
        return width;
    }

    /**
     * The pager renders fractional progress while dragging or settling, but its resting state is
     * binary: 0f is Playlist, 1f is Lyrics. Everything that ends an interaction goes through here.
     */
    private static float lyricsPageEndpoint(float progress) {
        return progress > 0.5f ? 1f : 0f;
    }

    /** The page the shell logically holds, regardless of what is on screen mid-animation. */
    private float currentLyricsPage() {
        return showingLyrics ? 1f : 0f;
    }

    private void setLyricsPageProgress(float progress) {
        if (dismissing) return;
        lyricsPageProgress = Math.max(0f, Math.min(1f, progress));
        final int width = getLyricsPageWidth();
        final float direction = LocaleController.isRTL ? -1f : 1f;
        listView.setTranslationX(-direction * lyricsPageProgress * width);
        lyricsListView.setTranslationX(direction * (1f - lyricsPageProgress) * width);
        listView.setVisibility(lyricsPageProgress < 1f ? View.VISIBLE : View.GONE);
        lyricsListView.setVisibility(lyricsPageProgress > 0f ? View.VISIBLE : View.GONE);
        if (lyricsViewportFade != null) {
            lyricsViewportFade.setTranslationX(direction * (1f - lyricsPageProgress) * width);
            lyricsViewportFade.setVisibility(lyricsPageProgress > 0f ? View.VISIBLE : View.GONE);
        }
        if (lyricsExpandButton != null) {
            lyricsExpandButton.setTranslationX(direction * (1f - lyricsPageProgress) * width);
        }
        containerView.invalidate();
        if (lyricsPageProgress == 0f) {
            updateLayout();
            updateEmptyViewPosition();
        }
    }

    /**
     * Stops an in-flight settle and leaves the surface wherever it was rendered.
     *
     * <p>The field is cleared <em>before</em> {@link AnimatorSet#cancel()}, which is not
     * incidental: cancel() dispatches onAnimationCancel and then onAnimationEnd synchronously on
     * the calling thread, so with the old ordering the completion listener's
     * {@code lyricsPageAnimation != animation} guard would still match and snap the pager to the
     * cancelled animation's target before the caller could read the rendered position. Clearing
     * first makes the guard reject it. Same idiom as SearchTagsList.show() and ChatSearchTabs.
     */
    private void cancelLyricsPageAnimation() {
        final AnimatorSet animation = lyricsPageAnimation;
        if (animation == null) return;
        lyricsPageAnimation = null;
        animation.cancel();
    }

    private void animateLyricsPage(float targetProgress, float velocityX) {
        // Single funnel for every resting transition, so no caller can leave a stable partial page.
        final float target = lyricsPageEndpoint(targetProgress);
        cancelLyricsPageAnimation();
        final int width = getLyricsPageWidth();
        if (width <= 0) {
            setLyricsPageProgress(target);
            return;
        }
        final float from = lyricsPageProgress;
        if (from == target) {
            setLyricsPageProgress(target);
            return;
        }
        float travelled = Math.abs(target - from) * width;
        int duration;
        float speed = Math.abs(velocityX);
        if (speed > 0) {
            duration = 4 * Math.round(1000.0f * (width / 2f + width / 2f * Math.min(1f, travelled / (float) width)) / speed);
        } else {
            duration = (int) ((travelled / width + 1.0f) * 100.0f);
        }
        duration = Math.max(150, Math.min(duration, 600));
        ValueAnimator animator = ValueAnimator.ofFloat(from, target);
        animator.addUpdateListener(a -> setLyricsPageProgress((float) a.getAnimatedValue()));
        lyricsPageAnimation = new AnimatorSet();
        lyricsPageAnimation.playTogether(animator);
        lyricsPageAnimation.setDuration(duration);
        lyricsPageAnimation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        lyricsPageAnimation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (lyricsPageAnimation != animation) return;
                lyricsPageAnimation = null;
                setLyricsPageProgress(target);
            }
        });
        lyricsPageAnimation.start();
    }

    /** Never expose an empty Lyrics page, and never fight the seek bar, search or fullscreen. */
    private boolean canSwitchLyricsPage() {
        return !fullscreenLyrics && !searching && !searchWas && !keyboardVisible && !draggingSeekBar
            && !visibleLyrics.isEmpty() && lyricsListView != null && listView != null
            && listAdapter != null && listAdapter.getItemCount() > 0
            && (actionBar == null || !actionBar.isSearchFieldVisible())
            && blurredView.getTag() == null;
    }

    /** Only the page area arbitrates horizontal gestures; the controls strip is left untouched. */
    private boolean isInLyricsPageArea(float y) {
        if (containerView == null) return false;
        final int lyricsTop = ((FrameLayout.LayoutParams) lyricsListView.getLayoutParams()).topMargin;
        final int listTop = ((FrameLayout.LayoutParams) listView.getLayoutParams()).topMargin;
        final int top = Math.max(0, Math.min(lyricsTop, listTop));
        final int bottom = containerView.getHeight() - dp(getPlayerHeight());
        return y >= top && y <= bottom;
    }

    private void cancelLyricsPagerTracking() {
        lyricsPagerTracking = false;
        lyricsPagerMaybeTracking = false;
        lyricsPagerOffsetProgress = 0f;
        if (lyricsPagerVelocity != null) {
            lyricsPagerVelocity.recycle();
            lyricsPagerVelocity = null;
        }
    }

    /**
     * Ends a gesture that never moved the pager itself. If ACTION_DOWN took a settle animation off
     * the surface, that settle has to be finished here - dropping the gesture on its own would
     * leave the surface frozen wherever the cancelled animation happened to be.
     */
    private void abandonLyricsPagerGesture() {
        final boolean adopted = lyricsPagerOffsetProgress != 0f;
        cancelLyricsPagerTracking();
        if (adopted) animateLyricsPage(currentLyricsPage(), 0f);
    }

    /**
     * Paging stopped being available while a gesture was live. Never abandon the surface mid-page:
     * resolve to a coherent endpoint first, then drop the gesture. Losing usable lyrics can only
     * resolve to Playlist; anything else returns to the page the shell logically holds. Neither is
     * a completed user choice, so the stored mode preference is left alone.
     */
    private void resolveLyricsPagerInterruption() {
        if (!lyricsPagerTracking) {
            abandonLyricsPagerGesture();
            return;
        }
        cancelLyricsPagerTracking();
        settleLyricsPage(visibleLyrics.isEmpty() ? 0f : currentLyricsPage(), 0f, false);
    }

    private boolean handleLyricsPagerTouch(MotionEvent ev) {
        if (ev == null) return false;
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelLyricsPagerTracking();
            if (!canSwitchLyricsPage() || !isInLyricsPageArea(ev.getY())) return false;
            // Take ownership of the surface immediately: an in-flight settle must not keep moving
            // under the finger. Its remaining travel is carried as a visual offset so the picture
            // does not jump, while the gesture baseline stays a real page.
            if (lyricsPageAnimation != null || lyricsPageProgress != currentLyricsPage()) {
                cancelLyricsPageAnimation();
                lyricsPagerOffsetProgress = lyricsPageProgress - currentLyricsPage();
            }
            lyricsPagerMaybeTracking = true;
            lyricsPagerPointerId = ev.getPointerId(0);
            lyricsPagerStartX = (int) ev.getX();
            lyricsPagerStartY = (int) ev.getY();
            lyricsPagerStartProgress = currentLyricsPage();
            if (lyricsPagerVelocity == null) lyricsPagerVelocity = VelocityTracker.obtain();
            lyricsPagerVelocity.clear();
            lyricsPagerVelocity.addMovement(ev);
            return false;
        }
        if (!lyricsPagerMaybeTracking && !lyricsPagerTracking) return false;
        if (lyricsPagerVelocity != null) lyricsPagerVelocity.addMovement(ev);
        if (action == MotionEvent.ACTION_POINTER_UP) {
            final int upIndex = ev.getActionIndex();
            if (ev.getPointerId(upIndex) != lyricsPagerPointerId) return lyricsPagerTracking;
            final int nextIndex = upIndex == 0 ? 1 : 0;
            if (nextIndex >= ev.getPointerCount()) {
                resolveLyricsPagerInterruption();
                return false;
            }
            // Hand the gesture to a remaining finger by rebasing the origin, so dx - and therefore
            // the rendered progress - is unchanged across the handoff.
            lyricsPagerStartX += (int) (ev.getX(nextIndex) - ev.getX(upIndex));
            lyricsPagerStartY += (int) (ev.getY(nextIndex) - ev.getY(upIndex));
            lyricsPagerPointerId = ev.getPointerId(nextIndex);
            if (lyricsPagerVelocity != null) {
                lyricsPagerVelocity.clear();
                lyricsPagerVelocity.addMovement(ev);
            }
            return lyricsPagerTracking;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            final int index = ev.findPointerIndex(lyricsPagerPointerId);
            if (index < 0) {
                resolveLyricsPagerInterruption();
                return false;
            }
            if (!canSwitchLyricsPage()) {
                resolveLyricsPagerInterruption();
                return false;
            }
            final int dx = (int) (ev.getX(index) - lyricsPagerStartX);
            final int dy = (int) (ev.getY(index) - lyricsPagerStartY);
            if (!lyricsPagerTracking) {
                // Dominant-axis arbitration, identical to ViewPagerFixed: a clearly vertical drag
                // stays with the list, a clearly horizontal one becomes a page switch.
                if (Math.abs(dx) >= lyricsPagerTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                    final float forward = LocaleController.isRTL ? dx : -dx;
                    // Judged against what is on screen, so a gesture begun mid-settle can still
                    // drag toward the page the interrupted animation was heading for.
                    final float rendered = lyricsPagerStartProgress + lyricsPagerOffsetProgress;
                    if (rendered <= 0f && forward <= 0 || rendered >= 1f && forward >= 0) {
                        abandonLyricsPagerGesture();
                        return false;
                    }
                    lyricsPagerTracking = true;
                    lyricsPagerMaybeTracking = false;
                    cancelLyricsPageAnimation();
                    listView.setVisibility(View.VISIBLE);
                    lyricsListView.setVisibility(View.VISIBLE);
                    if (lyricsViewportFade != null) lyricsViewportFade.setVisibility(View.VISIBLE);
                    listView.setAlpha(1f);
                    lyricsListView.setAlpha(1f);
                    updateLyricsGeometry();
                    AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
                    cancelLyricsFollow();
                } else if (Math.abs(dy) >= lyricsPagerTouchSlop) {
                    abandonLyricsPagerGesture();
                    return false;
                }
            }
            if (lyricsPagerTracking) {
                final int width = getLyricsPageWidth();
                if (width > 0) {
                    final float forward = (LocaleController.isRTL ? dx : -dx) / (float) width;
                    setLyricsPageProgress(lyricsPagerStartProgress + lyricsPagerOffsetProgress + forward);
                }
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean wasTracking = lyricsPagerTracking;
            final float startProgress = lyricsPagerStartProgress;
            final float offsetProgress = lyricsPagerOffsetProgress;
            float velocityX = 0, velocityY = 0;
            if (lyricsPagerVelocity != null && action == MotionEvent.ACTION_UP) {
                lyricsPagerVelocity.computeCurrentVelocity(1000, maximumVelocity);
                velocityX = lyricsPagerVelocity.getXVelocity();
                velocityY = lyricsPagerVelocity.getYVelocity();
            }
            if (!wasTracking) {
                abandonLyricsPagerGesture();
                return false;
            }
            cancelLyricsPagerTracking();
            final int width = Math.max(1, getLyricsPageWidth());
            final float forwardVelocity = LocaleController.isRTL ? velocityX : -velocityX;
            final boolean flung = Math.abs(velocityX) >= 3500 && Math.abs(velocityX) > Math.abs(velocityY);
            final float target;
            if (flung) {
                target = forwardVelocity > 0 ? 1f : 0f;
            } else if (offsetProgress != 0f) {
                // The gesture began mid-settle, so distance travelled from the logical page is
                // meaningless; decide by where the surface actually came to rest.
                target = lyricsPageEndpoint(lyricsPageProgress);
            } else {
                final float travelled = Math.abs(lyricsPageProgress - startProgress) * width;
                target = travelled < width / 3.0f ? startProgress : (startProgress > 0.5f ? 0f : 1f);
            }
            settleLyricsPage(target, velocityX, true);
            return true;
        }
        return lyricsPagerTracking;
    }

    /**
     * Resolves the pager onto a page. {@code target} is snapped to an endpoint here as well, so no
     * caller - present or future - can leave a stable partial page behind.
     *
     * @param userChoice true only when the user completed an interaction on this surface; a
     *                   cancellation or an invalidation must not rewrite the stored mode preference.
     */
    private void settleLyricsPage(float target, float velocityX, boolean userChoice) {
        final float endpoint = lyricsPageEndpoint(target);
        final boolean toLyrics = endpoint > 0.5f;
        if (toLyrics != showingLyrics) {
            if (userChoice) {
                lyricsModeRequested = toLyrics;
                SyncedLyricsController.getInstance(currentAccount).setLyricsModePreferred(toLyrics);
            }
            showingLyrics = toLyrics;
            lyricsListView.setEnabled(toLyrics);
            listView.setEnabled(!toLyrics);
            updateLyricsChrome();
            updateLyricsPadding();
            showLyricsExpandButton(toLyrics && !fullscreenLyrics);
        }
        animateLyricsPage(endpoint, velocityX);
        if (toLyrics) updateLyricsFollow(true);
    }

    private float preFullscreenActionBarAlpha;
    private float preFullscreenActionBarBackgroundAlpha;
    private float preFullscreenActionBarShadowAlpha;
    private float preFullscreenActionBarSlide;
    private boolean fullscreenPlayerLayoutApplied;
    private int fullscreenArtworkSize = 104;

    private void applyFullscreenPlayerLayout(boolean fullscreen) {
        boolean animateArtwork = fullscreen != fullscreenPlayerLayoutApplied;
        int height = getPlayerHeight();
        // Three fullscreen control tiers keep every control usable without a landscape-only window.
        boolean compactFullscreen = fullscreen && height < FULLSCREEN_PLAYER_REGULAR;
        boolean tightFullscreen = fullscreen && height < FULLSCREEN_PLAYER_COMPACT;
        int artworkSize = fullscreen ? (tightFullscreen ? 56 : compactFullscreen ? 72 : 104) : 44;
        int oldArtworkSize = animateArtwork ? (fullscreen ? 44 : fullscreenArtworkSize) : artworkSize;
        if (fullscreen) fullscreenArtworkSize = artworkSize;
        FrameLayout.LayoutParams playerParams = (FrameLayout.LayoutParams) playerLayout.getLayoutParams();
        playerParams.height = dp(height);
        playerLayout.setLayoutParams(playerParams);
        FrameLayout.LayoutParams shadowParams = (FrameLayout.LayoutParams) playerShadow.getLayoutParams();
        shadowParams.bottomMargin = dp(height);
        playerShadow.setLayoutParams(shadowParams);
        FrameLayout.LayoutParams lyricsParams = (FrameLayout.LayoutParams) lyricsListView.getLayoutParams();
        lyricsParams.bottomMargin = dp(height);
        lyricsListView.setLayoutParams(lyricsParams);
        fullscreenPlayerLayoutApplied = fullscreen;
        applyProfileButtonsVisibility(false);

        int artTop = tightFullscreen ? 8 : compactFullscreen ? 12 : 20;
        setFrame(coverContainer, artworkSize, artworkSize, Gravity.TOP | (fullscreen ? Gravity.START : Gravity.END), 20, artTop, 20, 0);
        float titleStart = fullscreen ? (tightFullscreen ? 88 : compactFullscreen ? 108 : 144) : 20;
        float authorStart = fullscreen ? (tightFullscreen ? 82 : compactFullscreen ? 102 : 138) : 14;
        setFrame(titleTextView, LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, LocaleController.isRTL ? 20 : titleStart, fullscreen ? (tightFullscreen ? 8 : compactFullscreen ? 18 : 26) : 20, LocaleController.isRTL ? titleStart : 20, 0);
        setFrame(authorTextView, LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.START, LocaleController.isRTL ? 20 : authorStart, fullscreen ? (tightFullscreen ? 34 : compactFullscreen ? 45 : 55) : 47, LocaleController.isRTL ? authorStart : 20, 0);
        setFrame(seekBarView, LayoutHelper.MATCH_PARENT, 44, Gravity.TOP | Gravity.LEFT, 5, fullscreen ? (tightFullscreen ? 58 : compactFullscreen ? 84 : 128) : 67, 5, 0);
        setFrame(progressView, LayoutHelper.MATCH_PARENT, 2, Gravity.TOP | Gravity.LEFT, 21, fullscreen ? (tightFullscreen ? 81 : compactFullscreen ? 107 : 151) : 90, 21, 0);
        setFrame(timeTextView, 100, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 20, fullscreen ? (tightFullscreen ? 89 : compactFullscreen ? 115 : 159) : 98, 0, 0);
        setFrame(durationTextView, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, fullscreen ? (tightFullscreen ? 87 : compactFullscreen ? 113 : 157) : 96, 20, 0);
        setFrame(playbackSpeedButton, 36, 36, Gravity.TOP | Gravity.RIGHT, 0, fullscreen ? (tightFullscreen ? 78 : compactFullscreen ? 104 : 148) : 86, 20, 0);
        setFrame(playbackControlsView, LayoutHelper.MATCH_PARENT, 66, Gravity.TOP | Gravity.LEFT, 0, fullscreen ? (tightFullscreen ? 104 : compactFullscreen ? 139 : 184) : 111, 0, 0);
        coverContainer.setPivotX((fullscreen ? LocaleController.isRTL : !LocaleController.isRTL) ? dp(artworkSize) : 0);
        coverContainer.setPivotY(0);
        float artworkScale = oldArtworkSize / (float) artworkSize;
        coverContainer.setScaleX(artworkScale);
        coverContainer.setScaleY(artworkScale);
        if (animateArtwork) {
            // Same spec as Telegram's own text-writer expander: EASE_OUT_QUINT over 420ms.
            coverContainer.animate().cancel();
            coverContainer.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        containerView.invalidate();
    }

    /**
     * Single authority over the "Add to profile" pair. Everything outside fullscreen keeps
     * Telegram's existing behaviour; fullscreen never shows them, playing or paused, so they can
     * never cover the playback controls.
     */
    private void applyProfileButtonsVisibility(boolean animated) {
        if (saveToProfileButton == null || unsaveFromProfileButton == null) return;
        if (isMyList() || noforwards || fullscreenLyrics) {
            saveToProfileButton.animate().cancel();
            unsaveFromProfileButton.animate().cancel();
            saveToProfileButton.setVisibility(View.GONE);
            unsaveFromProfileButton.setVisibility(View.GONE);
            return;
        }
        final boolean visible = visibleInProfile;
        saveToProfileButton.setVisibility(View.VISIBLE);
        unsaveFromProfileButton.setVisibility(View.VISIBLE);
        saveToProfileButton.animate().cancel();
        unsaveFromProfileButton.animate().cancel();
        if (!animated) {
            saveToProfileButton.setAlpha(visible ? 0.0f : 1.0f);
            saveToProfileButton.setScaleX(visible ? 0.8f : 1.0f);
            saveToProfileButton.setScaleY(visible ? 0.8f : 1.0f);
            saveToProfileButton.setVisibility(visible ? View.GONE : View.VISIBLE);
            unsaveFromProfileButton.setAlpha(!visible ? 0.0f : 1.0f);
            unsaveFromProfileButton.setScaleX(!visible ? 0.8f : 1.0f);
            unsaveFromProfileButton.setScaleY(!visible ? 0.8f : 1.0f);
            unsaveFromProfileButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            return;
        }
        saveToProfileButton.animate()
            .alpha(visible ? 0.0f : 1.0f)
            .scaleX(visible ? 0.8f : 1.0f)
            .scaleY(visible ? 0.8f : 1.0f)
            .setDuration(420)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .withEndAction(() -> saveToProfileButton.setVisibility(visible ? View.GONE : View.VISIBLE))
            .start();
        unsaveFromProfileButton.animate()
            .alpha(!visible ? 0.0f : 1.0f)
            .scaleX(!visible ? 0.8f : 1.0f)
            .scaleY(!visible ? 0.8f : 1.0f)
            .setDuration(420)
            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
            .withEndAction(() -> unsaveFromProfileButton.setVisibility(visible ? View.VISIBLE : View.GONE))
            .start();
    }

    private static void setFrame(View view, int width, int height, int gravity, float left, float top, float right, float bottom) {
        view.setLayoutParams(LayoutHelper.createFrame(width, height, gravity, left, top, right, bottom));
    }

    /** Height (dp) of the ordinary, non-fullscreen player shell. */
    private int getNormalPlayerHeight() {
        return 179 + (!isMyList() && !noforwards ? 52 : 0);
    }

    // Fullscreen control tiers. Each value is the exact stack height the matching branch of
    // applyFullscreenPlayerLayout() lays out, so the controls can never overlap or overflow.
    private static final int FULLSCREEN_PLAYER_REGULAR = 250;
    private static final int FULLSCREEN_PLAYER_COMPACT = 205;
    private static final int FULLSCREEN_PLAYER_TIGHT = 172;
    private static final int FULLSCREEN_LYRICS_MIN = 132;

    private int getPlayerHeight() {
        if (fullscreenLyrics) {
            int containerHeight = containerMeasuredHeight;
            if (containerHeight <= 0 && containerView != null) containerHeight = containerView.getHeight();
            if (containerHeight <= 0) return 276;
            int availableDp = Math.round((containerHeight - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight) / AndroidUtilities.density);
            // Reserve a lyrics viewport that is actually readable rather than merely non-negative.
            int reserved = Math.max(FULLSCREEN_LYRICS_MIN, Math.round(availableDp * 0.3f));
            int maxPlayer = availableDp - reserved;
            return Math.max(FULLSCREEN_PLAYER_TIGHT, Math.min(276, maxPlayer));
        }
        return getNormalPlayerHeight();
    }

    /** Vertical position inside the viewport that the active synced line settles on. */
    private int getLyricsFocusCenter() {
        return lyricsListView.getHeight() / 2;
    }

    // ---------------------------------------------------------------------------------------
    // Large-player synced follow. The compact player is the motion reference: it starts moving a
    // little BEFORE the next timestamp and eases over that lead, so nothing ever snaps on the
    // timestamp itself. The same philosophy is applied here to the lyrics list - one driven
    // animation at a time, never a stack of competing SmoothScrollers - while the LOGICAL active
    // line (emphasis, colour, tap-to-seek) still changes exactly at the real timestamp.
    // ---------------------------------------------------------------------------------------

    private static final long LYRIC_FOLLOW_LEAD_MAX = 440;

    /**
     * How long BEFORE a line's stated time the list starts carrying the previous line away.
     *
     * <p>This is the whole of the pre-roll, and it is the reason the outgoing line's last word has
     * far less visible time than its stated interval suggests: from this many milliseconds before
     * the next timestamp the row is already scrolling off and fading down. Anything decorative
     * that must be SEEN on the outgoing line has to finish by then, not by the timestamp.
     */
    static long lyricFollowLeadMs(long gapMs) {
        return Math.min(LYRIC_FOLLOW_LEAD_MAX, Math.max(80, gapMs / 2));
    }
    private static final long LYRIC_FOLLOW_MIN_MS = 160;
    private static final long LYRIC_FOLLOW_MAX_MS = 900;

    private ValueAnimator lyricsFollowAnimator;
    private int lyricsFollowRow = RecyclerView.NO_POSITION;
    // Visual transition state. Deliberately separate from activeLyricsLine/activeLyricsRow, which
    // stay the LOGICAL state and still change exactly at the real timestamp.
    private int lyricsEmphasisFromRow = RecyclerView.NO_POSITION;
    private int lyricsEmphasisToRow = RecyclerView.NO_POSITION;
    private float lyricsEmphasisProgress = 1f;
    // Where each side of the crossfade STARTED. A transition that begins from rest runs 1 -> 0 and
    // 0 -> 1, but one that interrupts an unfinished transition begins from whatever the eye is
    // actually looking at, which is what keeps a retarget from stepping the hierarchy in one frame.
    private float lyricsEmphasisFromStart = 1f;
    private float lyricsEmphasisToStart = 0f;
    // One extra slot: interrupting a two-row crossfade leaves a THIRD row still partly lit, and it
    // has to be faded out rather than dropped to zero. Two rows cannot express three.
    private int lyricsEmphasisDropRow = RecyclerView.NO_POSITION;
    private float lyricsEmphasisDropStart = 0f;
    private final Runnable advanceLyricsFollow = () -> updateLyricsFollow(true);

    private void cancelLyricsFollow() {
        AndroidUtilities.cancelRunOnUIThread(advanceLyricsFollow);
        cancelLyricsFollowAnimator();
        lyricsFollowRow = RecyclerView.NO_POSITION;
        // Never leave a line half-emphasised behind a cancelled transition: resolve onto whatever
        // the logical state says is active right now. The hierarchy rides the same values, so
        // resolving the transition resolves the colours with it - but it SETTLES there over a
        // frame or two instead of stepping, because painting now reads these values directly and a
        // drag, a mode change or a dismiss would otherwise pop the colour and the blur.
        if (dismissing || lyricsListView == null || !showingLyrics) {
            setLyricsEmphasis(RecyclerView.NO_POSITION, activeLyricsRow, 1f);
        } else {
            settleLyricsEmphasis(activeLyricsRow);
        }
    }

    /**
     * Eases the hierarchy onto {@code toRow} from wherever it visually is, without a step. Used by
     * every path that stops a transition early - a pause inside a pre-roll, a cancelled follow -
     * where resolving the bookkeeping instantly would be a visible colour and blur snap.
     */
    private void settleLyricsEmphasis(int toRow) {
        cancelLyricsFollowAnimator();
        if (lyricsEmphasisToRow == toRow && lyricsEmphasisProgress >= 1f
                && lyricsEmphasisFromRow == RecyclerView.NO_POSITION
                && lyricsEmphasisDropRow == RecyclerView.NO_POSITION) {
            return; // already settled exactly there
        }
        retargetLyricsEmphasis(toRow);
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            if (lyricsFollowAnimator != a) return;
            lyricsEmphasisProgress = (float) a.getAnimatedValue();
            updateLyricsDepth();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (lyricsFollowAnimator != animation) return;
                lyricsFollowAnimator = null;
                resolveLyricsEmphasis();
            }
        });
        animator.setDuration(LYRIC_FOLLOW_MIN_MS);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        lyricsFollowAnimator = animator;
        animator.start();
        updateLyricsDepth();
    }

    /**
     * Points the crossfade at a new target while keeping every row's CURRENT focus as its starting
     * value, so an interrupted transition continues from what is on screen. The outgoing slot takes
     * the brightest row that is not the new target and the drop slot the next brightest; anything
     * dimmer than those two is already at rest.
     */
    private void retargetLyricsEmphasis(int toRow) {
        final int[] rows = {lyricsEmphasisToRow, lyricsEmphasisFromRow, lyricsEmphasisDropRow};
        final float[] focus = new float[rows.length];
        for (int i = 0; i < rows.length; i++) {
            focus[i] = rows[i] == RecyclerView.NO_POSITION || rows[i] == toRow
                    ? -1f : lyricsFocusOf(rows[i]);
        }
        int first = -1, second = -1;
        for (int i = 0; i < rows.length; i++) {
            if (focus[i] < 0f) continue;
            if (first < 0 || focus[i] > focus[first]) {
                second = first;
                first = i;
            } else if (second < 0 || focus[i] > focus[second]) {
                second = i;
            }
        }
        final float toStart = toRow == RecyclerView.NO_POSITION ? 0f : lyricsFocusOf(toRow);
        lyricsEmphasisFromRow = first < 0 ? RecyclerView.NO_POSITION : rows[first];
        lyricsEmphasisFromStart = first < 0 ? 0f : focus[first];
        lyricsEmphasisDropRow = second < 0 ? RecyclerView.NO_POSITION : rows[second];
        lyricsEmphasisDropStart = second < 0 ? 0f : focus[second];
        lyricsEmphasisToRow = toRow;
        lyricsEmphasisToStart = toStart;
        lyricsEmphasisProgress = 0f;
    }

    /** Finishes a transition: the target owns the hierarchy and nothing else is left part-lit. */
    private void resolveLyricsEmphasis() {
        lyricsEmphasisProgress = 1f;
        lyricsEmphasisFromRow = RecyclerView.NO_POSITION;
        lyricsEmphasisFromStart = 1f;
        lyricsEmphasisToStart = 0f;
        lyricsEmphasisDropRow = RecyclerView.NO_POSITION;
        lyricsEmphasisDropStart = 0f;
        updateLyricsDepth();
    }

    /** Eases the current emphasis out to "no active lyric", used for an explicit timed blank. */
    private void clearLyricsEmphasis() {
        if (lyricsEmphasisToRow == RecyclerView.NO_POSITION && (lyricsFollowAnimator != null || lyricsEmphasisProgress >= 1f)) {
            return; // already clearing, or already clear: the blank interval ticks many times
        }
        cancelLyricsFollowAnimator();
        // Continues from the focus every row actually has, so a blank that lands mid-transition
        // dims what is lit rather than stepping it.
        retargetLyricsEmphasis(RecyclerView.NO_POSITION);
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            if (lyricsFollowAnimator != a) return;
            lyricsEmphasisProgress = (float) a.getAnimatedValue();
            updateLyricsDepth();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (lyricsFollowAnimator != animation) return;
                lyricsFollowAnimator = null;
                resolveLyricsEmphasis();
            }
        });
        animator.setDuration(LYRIC_FOLLOW_MIN_MS);
        animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        lyricsFollowAnimator = animator;
        animator.start();
    }

    private void setLyricsEmphasis(int fromRow, int toRow, float progress) {
        lyricsEmphasisFromRow = fromRow;
        lyricsEmphasisFromStart = 1f;
        lyricsEmphasisToRow = toRow;
        lyricsEmphasisToStart = 0f;
        lyricsEmphasisDropRow = RecyclerView.NO_POSITION;
        lyricsEmphasisDropStart = 0f;
        lyricsEmphasisProgress = progress;
        updateLyricsDepth();
    }

    /**
     * Drives the follow's own crossfade bookkeeping. The value is no longer read by the painting -
     * the visual hierarchy reads it through {@link #lyricsFocusOf}, so the fade and the movement
     * are one gesture on one clock. Its every frame repaints the page, which is what keeps both
     * the colours and the depth tracking the glide instead of catching up after it.
     */
    /**
     * Schedules and drives the visual follow. Mirrors the compact player's lead algorithm: the move
     * toward the next line starts {@code lead} before its timestamp, where the lead is half the gap
     * capped at the compact roll duration, so closely spaced lines flow continuously and widely
     * spaced ones get a long, soft move.
     */
    private void updateLyricsFollow(boolean animated) {
        AndroidUtilities.cancelRunOnUIThread(advanceLyricsFollow);
        if (dismissing || lyricsListView == null || !showingLyrics) return;
        if (currentLyrics == null || !currentLyrics.isSynced()) return;
        if (lyricsUserScrolling || lyricsPagerTracking || draggingSeekBar) return;
        final MessageObject message = MediaController.getInstance().getPlayingMessageObject();
        if (message == null) return;
        final long position = SyncedLyricsController.positionMs(message);
        final int line = currentLyrics.lineAt(position);
        if (line >= 0 && line < currentLyrics.lines.size() && TextUtils.isEmpty(currentLyrics.lines.get(line).text)) {
            // An explicit timed blank. Timed blanks are not rows, so there is nothing to follow:
            // the visible active lyric clears and stays clear for the whole interval. Crucially we
            // return before the pre-roll below, which would otherwise start promoting line + 1
            // early. Only a real blank event lands here - line < 0 is "before the first lyric" and
            // keeps its normal pre-roll into the first line.
            lyricsFollowRow = RecyclerView.NO_POSITION;
            clearLyricsEmphasis();
            return;
        }
        int targetRow = rowForLyricsLine(line);
        long duration = 0;
        final boolean paused = MediaController.getInstance().isMessagePaused();
        if (paused && lyricsFollowAnimator != null && lyricsFollowRow != targetRow) {
            // Paused inside a pre-roll: stop short of the next line and hand the emphasis back to
            // the line whose timestamp has actually passed.
            lyricsFollowRow = targetRow;
            // Settle, never step: the pre-roll had already part-promoted the next line, and
            // handing the hierarchy back in one frame is exactly the colour/blur pop QA saw.
            settleLyricsEmphasis(targetRow);
        }
        if (!paused && line + 1 < currentLyrics.lines.size()) {
            final long nextTime = currentLyrics.lines.get(line + 1).timeMs;
            final long previousTime = line < 0 ? 0 : currentLyrics.lines.get(line).timeMs;
            final long untilNext = nextTime - position;
            final long gap = Math.max(1, nextTime - previousTime);
            final long lead = lyricFollowLeadMs(gap);
            final int nextRow = rowForLyricsLine(line + 1);
            if (untilNext <= lead) {
                // Inside the lead window: move toward the next line now. A blank timestamp has no
                // row, so nothing is visually promoted during the blank interval.
                if (nextRow != RecyclerView.NO_POSITION) {
                    targetRow = nextRow;
                    duration = Math.max(80, Math.min(lead, untilNext));
                }
            } else {
                AndroidUtilities.runOnUIThread(advanceLyricsFollow, untilNext - lead);
            }
        }
        if (targetRow == RecyclerView.NO_POSITION) return;
        scrollLyricsToRow(targetRow, animated, duration);
    }

    private int rowForLyricsLine(int line) {
        if (line < 0) return RecyclerView.NO_POSITION;
        for (int i = 0; i < visibleLyrics.size(); i++) {
            if (visibleLyrics.get(i) == line) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private void scrollLyricsToRow(int row, boolean animated, long preferredDuration) {
        if (lyricsListView == null || row < 0 || row >= visibleLyrics.size()) return;
        if (lyricsListView.getHeight() == 0) {
            lyricsListView.post(() -> scrollLyricsToRow(row, false, 0));
            return;
        }
        final View child = lyricsLayoutManager.findViewByPosition(row);
        if (child == null) {
            // Far away (first open, long seek): reach the destination immediately rather than
            // crawling through the whole document, then let the next update ease from there.
            cancelLyricsFollowAnimator();
            lyricsLayoutManager.scrollToPositionWithOffset(row, Math.max(0, getLyricsFocusCenter() - dp(32)));
            lyricsFollowRow = row;
            // Resolve emphasis onto the destination too, so a long seek cannot leave the previous
            // line emphasised or bold a row that is no longer current.
            setLyricsEmphasis(RecyclerView.NO_POSITION, row, 1f);
            return;
        }
        // scrollBy() takes the opposite sign convention: positive dy moves content up.
        final int distance = (child.getTop() + child.getBottom()) / 2 - getLyricsFocusCenter();
        if (!animated) {
            cancelLyricsFollowAnimator();
            lyricsListView.scrollBy(0, distance);
            lyricsFollowRow = row;
            setLyricsEmphasis(RecyclerView.NO_POSITION, row, 1f);
            return;
        }
        // updateLyricsFollow() runs on every progress tick, so a move already easing toward this
        // row must be left alone. Restarting it per tick is exactly what made the old
        // implementation stutter: each restart reset the interpolator and the velocity.
        if (lyricsFollowRow == row && (lyricsFollowAnimator != null || Math.abs(distance) <= dp(1))) {
            return;
        }
        if (distance == 0 && lyricsEmphasisToRow == row && lyricsEmphasisProgress >= 1f) {
            lyricsFollowRow = row;
            return;
        }
        long duration = preferredDuration;
        if (duration <= 0) {
            // Distance-proportional, so a one-line step stays soft and a long seek stays responsive.
            duration = Math.round(220 + Math.abs(distance) / AndroidUtilities.density * 0.9f);
        }
        duration = Math.max(LYRIC_FOLLOW_MIN_MS, Math.min(LYRIC_FOLLOW_MAX_MS, duration));
        lyricsFollowRow = row;
        cancelLyricsFollowAnimator();
        // The incoming row's emphasis and the movement share this one clock, so the line gains
        // prominence while it rises instead of popping into bold once the scroll has finished.
        // Re-targeting the SAME row - a fullscreen expand/collapse, a viewport resize - only moves
        // the surface; restarting the emphasis there would un-bold and re-bold the current line.
        final boolean advancing = lyricsEmphasisToRow != row;
        if (advancing) {
            // Fast consecutive lines retarget a transition that has not finished. Carrying every
            // row's current focus into the new one is what keeps the outgoing row from dropping to
            // zero and the half-promoted row from jumping to full in a single frame.
            retargetLyricsEmphasis(row);
        }
        final int[] applied = {0};
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            if (lyricsFollowAnimator != a) return;
            final float fraction = (float) a.getAnimatedValue();
            final int step = Math.round(distance * fraction);
            final int delta = step - applied[0];
            applied[0] = step;
            if (advancing) lyricsEmphasisProgress = fraction;
            if (delta != 0) {
                lyricsListView.scrollBy(0, delta); // onScrolled repaints the emphasis
            } else if (advancing) {
                updateLyricsDepth();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (lyricsFollowAnimator != animation) return;
                lyricsFollowAnimator = null;
                if (advancing) resolveLyricsEmphasis();
            }
        });
        animator.setDuration(duration);
        // Same easing family as the compact lyric transition: soft in, soft out, no snap.
        animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        lyricsFollowAnimator = animator;
        animator.start();
    }

    private void cancelLyricsFollowAnimator() {
        if (lyricsFollowAnimator == null) return;
        final ValueAnimator animator = lyricsFollowAnimator;
        lyricsFollowAnimator = null;
        animator.cancel();
    }

    private void updateLyricsDepth() {
        if (lyricsListView == null || lyricsListView.getHeight() == 0) return;
        // Hoisted out of the per-row body: this runs for every attached row on every frame of the
        // follow AND of the hierarchy hand-over, and a themed colour is a map lookup, not a field.
        final int inactiveColor = getThemedColor(Theme.key_player_time);
        final int activeColor = getThemedColor(Theme.key_player_actionBarTitle);
        final int sweepColor = karaokeSweepColor();
        for (int i = 0; i < lyricsListView.getChildCount(); i++) {
            applyLyricsDepth(lyricsListView.getChildAt(i), inactiveColor, activeColor, sweepColor);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Word-level karaoke. Only the line whose OWN timestamp has already passed can show word
    // highlighting, and inside it a word lights up only once the time the source stated for that
    // word has passed. Nothing here derives a semantic time from a word's length, from the line's
    // duration, or from anything other than the timestamps captured out of the source, and a line
    // that states none simply keeps the whole-line behaviour it always had.
    //
    // The presentation has exactly two parts, and both are pure functions of the playback
    // position: the type/depth hierarchy of the page, and a horizontal colour fill that travels
    // across the glyphs of the word being sung. Nothing moves vertically inside a lyric row, and
    // nothing keeps animation state that a seek, a pause or a rebind would have to unwind.
    // ---------------------------------------------------------------------------------------

    /**
     * The large player's lyric typography. One family for every mode: Normal, line-synced and
     * true karaoke differ in what they can do, never in how well they are set. It is chosen once
     * per row when the row is bound and never touched again, because a size or a weight is
     * metric-affecting and switching one on a state change would re-measure and possibly re-wrap
     * the row, moving every row below it.
     */
    static final int LYRICS_TEXT_SIZE_DP = 22;
    static final int LYRICS_LINE_SPACING_DP = 3;
    static final int LYRICS_ROW_MIN_HEIGHT_DP = 64;
    static final int LYRICS_ROW_STANZA_HEIGHT_DP = 26;
    static final int LYRICS_ROW_PADDING_H_DP = 22;
    static final int LYRICS_ROW_PADDING_V_DP = 13;
    /** Opacity of a karaoke line at the focus centre but not current. */
    private static final float KARAOKE_REST_ALPHA = 0.74f;
    /** How much of that opacity the distance falloff is allowed to take. */
    private static final float KARAOKE_REST_ALPHA_FALLOFF = 0.20f;
    /** Opacity of the current line. Deliberately only a little above its neighbours. */
    private static final float KARAOKE_ACTIVE_ALPHA = 0.96f;
    /** Blur, in dp, a karaoke line furthest from the focus centre carries. Small on purpose. */
    private static final float KARAOKE_BLUR_MAX_DP = 2.4f;
    /** How much of the active colour sung text takes on a line that is not the current one. */
    private static final float KARAOKE_SUNG_REST = 0.50f;
    /** How much of it text that has not been sung takes, at rest and on the current line. */
    private static final float KARAOKE_MUTED_REST = 0.10f;
    private static final float KARAOKE_MUTED_ACTIVE = 0.30f;
    /** Physical width of the soft sung/unsung boundary feather, in dp. */
    private static final float KARAOKE_FEATHER_DP = 7f;
    /** Scale applied to the line the vocalist is currently singing. Reading-edge pivot. */
    private static final float KARAOKE_ACTIVE_SCALE = 1.015f;

    /** Resolved once per document: true only when some line genuinely states inline word timing. */
    private boolean lyricsWordTimed;

    // --- Visual current-line hierarchy -----------------------------------------------------
    // Three separate ideas share this screen:
    //   1. where the list is physically moving    -> lyricsFollowRow / lyricsEmphasis*
    //   2. which line the clock says is being sung -> karaokeLine / activeLyricsLine
    //   3. which line is drawn sharp and bright    -> lyricsFocusOf(), below
    //
    // (3) is deliberately driven by (1), because the fade and the movement are one gesture: the
    // outgoing line has to be dimming WHILE the list carries it away, and the incoming line
    // brightening over the same travel. Giving the fade a clock of its own made the page move,
    // stop, and only then change colour, which read as two separate events.
    //
    // (2) is what gates the WORDS, and it is untouched by any of this: resolveRow() is asked about
    // karaokeLine, so no word of a line the pre-roll is merely carrying into place can light up
    // before its own stated time, however bright the line itself has become.

    /** The frame the current line is painting. Scratch for one tick; carries nothing between two. */
    private final KaraokeFrame karaoke = new KaraokeFrame();
    /** Scratch holder for painting one row; never carries state between two calls. */
    private final KaraokeFrame rowKaraoke = new KaraokeFrame();
    /** The line the playback position is actually inside, whether or not it states word timing. */
    private int karaokeLine = Integer.MIN_VALUE;
    private long karaokePositionMs;
    private int karaokeRow = RecyclerView.NO_POSITION;
    /** Stated start of the next timed line, cached per line so no tick walks the document. */
    private long karaokeNextLineTimeMs = Long.MAX_VALUE;
    /** The palette the rows were last painted with, so a theme change repaints them all once. */
    private int karaokeMutedSource;
    private int karaokeSungSource;

    /**
     * Resolves the word state for the line the position is actually inside, and repaints what
     * changed. Deliberately driven by the real position rather than by the follow animation: the
     * follow promotes the next line up to a pre-roll early, and no word of that line may light up
     * before its own stated time.
     *
     * <p>Within one line only the current row can change, so a tick costs one binary search and, if
     * the frame actually moved, one invalidate on one row. A settled row costs nothing.
     */
    private void updateKaraoke(int line, long positionMs) {
        final boolean resolvable = currentLyrics != null && lyricsWordTimed
                && line >= 0 && line < currentLyrics.lines.size();
        karaokePositionMs = positionMs;
        if (line != karaokeLine) {
            karaokeLine = line;
            karaokeNextLineTimeMs = nextLyricsLineTimeMs(line);
            karaokeMutedSource = getThemedColor(Theme.key_player_time);
            karaokeSungSource = getThemedColor(Theme.key_player_actionBarTitle);
            karaokeRow = resolvable ? rowForLyricsLine(line) : RecyclerView.NO_POSITION;
            // Every attached row derives its own state from which side of the current line it is
            // on, so a line change - including a seek across several lines - repaints them all.
            updateLyricsDepth();
            return;
        }
        // A theme swapped underneath an open player changes the palette but not the position, so
        // it costs two lookups a tick to notice and one repaint of the page to answer.
        final int inactiveColor = getThemedColor(Theme.key_player_time);
        final int activeColor = getThemedColor(Theme.key_player_actionBarTitle);
        if (inactiveColor != karaokeMutedSource || activeColor != karaokeSungSource) {
            karaokeMutedSource = inactiveColor;
            karaokeSungSource = activeColor;
            updateLyricsDepth();
            return;
        }
        if (!resolvable) return;
        repaintKaraokeRow();
    }

    /** Pushes the frame the current position states to the one row that can be showing a word. */
    private void repaintKaraokeRow() {
        if (!lyricsWordTimed || currentLyrics == null || lyricsLayoutManager == null) return;
        if (karaokeRow == RecyclerView.NO_POSITION) return;
        if (karaokeLine < 0 || karaokeLine >= currentLyrics.lines.size()) return;
        final View child = lyricsLayoutManager.findViewByPosition(karaokeRow);
        if (!(child instanceof LyricsTextView)) return;
        if (karaoke.resolveRow(currentLyrics.lines.get(karaokeLine), karaokeLine, karaokeLine,
                karaokePositionMs, karaokeNextLineTimeMs)) {
            ((LyricsTextView) child).setKaraokeFrame(karaoke.wordStart, karaoke.wordEnd,
                    karaoke.sweep, karaoke.ownedEnd);
        }
    }

    /**
     * 0 = subordinate, 1 = the line being sung, blended continuously across the hand-over.
     *
     * <p>The progress is the follow animator's own fraction - the very number that is moving the
     * list this frame - so at a quarter of the travel the outgoing line is a quarter faded and the
     * incoming line a quarter arrived. There is no second animator and no post-scroll switch.
     *
     * <p>The two sides are derived from one value, so the crossfade stays complementary and the
     * pair can never dip or overshoot in total brightness. That value is the follow animator's
     * fraction, which the animator has ALREADY eased; the interpolation here is therefore linear
     * on purpose. Easing it a second time is what made the outgoing line lose most of its
     * prominence well before the halfway point of the travel.
     */
    private float lyricsFocusOf(int row) {
        if (row == RecyclerView.NO_POSITION) return 0f;
        if (row == lyricsEmphasisDropRow && row != lyricsEmphasisToRow && row != lyricsEmphasisFromRow) {
            return lyricsDropFocusValue(lyricsEmphasisDropStart, lyricsEmphasisProgress);
        }
        return lyricsFocusValue(row, lyricsEmphasisFromRow, lyricsEmphasisToRow,
                lyricsEmphasisProgress, lyricsEmphasisFromStart, lyricsEmphasisToStart);
    }

    /** The crossfade itself, free of the view state, so it can be asserted directly. */
    public static float lyricsFocusValue(int row, int fromRow, int toRow, float progress) {
        return lyricsFocusValue(row, fromRow, toRow, progress, 1f, 0f);
    }

    /**
     * The same crossfade, told where each side started. A transition from rest runs 1 -> 0 and
     * 0 -> 1; one that interrupts an unfinished transition starts from what is on screen.
     */
    public static float lyricsFocusValue(int row, int fromRow, int toRow, float progress,
                                         float fromStart, float toStart) {
        if (row == RecyclerView.NO_POSITION) return 0f;
        final float t = clampUnit(progress);
        if (row == toRow) return lerp(toStart, 1f, t);
        if (row == fromRow) return lerp(fromStart, 0f, t);
        return 0f;
    }

    /**
     * The third row's side of an interrupted hand-over: it fades out from wherever it was rather
     * than being dropped to nothing, which is what a two-row crossfade alone would have to do.
     */
    public static float lyricsDropFocusValue(float dropStart, float progress) {
        return lerp(dropStart, 0f, clampUnit(progress));
    }

    static float clampUnit(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }

    /**
     * How much prominence a line keeps, whatever the scroll fade is doing, while a word of it is
     * genuinely still being sung.
     *
     * <p>The fade and the movement are one gesture and must stay that way - the line visibly dims
     * from the moment the pre-roll starts. But the pre-roll begins up to {@link
     * #LYRIC_FOLLOW_LEAD_MAX} before the next line's timestamp, and the final word of the outgoing
     * line is usually still sweeping through the whole of it. Letting the crossfade alone decide
     * would hand that word over to the subordinate colour while it is still being sung, which is
     * the "swallowed last word" QA kept reporting.
     *
     * <p>So the floor is a composition rule, not a freeze: the painted focus is the larger of the
     * crossfade and this floor. The floor is deliberately well below full focus, so the line is
     * still plainly fading, and it releases over the tail of the word's own fill so that it is
     * already gone by the line boundary - the outgoing line finishes fully secondary, and nothing
     * steps when the floor stops applying.
     */
    static final float KARAOKE_SINGING_FOCUS_FLOOR = 0.55f;
    /** Point in the word's own fill at which the floor starts releasing back to the crossfade. */
    static final float KARAOKE_SINGING_FLOOR_RELEASE = 0.85f;

    public static float karaokeReadableFloor(boolean wordActive, float sweep) {
        if (!wordActive) return 0f;
        final float filled = clampUnit(sweep);
        if (filled <= KARAOKE_SINGING_FLOOR_RELEASE) return KARAOKE_SINGING_FOCUS_FLOOR;
        final float released = (filled - KARAOKE_SINGING_FLOOR_RELEASE)
                / (1f - KARAOKE_SINGING_FLOOR_RELEASE);
        return KARAOKE_SINGING_FOCUS_FLOOR * (1f - released);
    }

    /** Stated start of the first timed line after {@code line}, or MAX_VALUE when there is none. */
    private long nextLyricsLineTimeMs(int line) {
        if (currentLyrics == null || line < 0) return Long.MAX_VALUE;
        for (int i = line + 1; i < currentLyrics.lines.size(); i++) {
            final SyncedLyricsController.Line next = currentLyrics.lines.get(i);
            if (next.timed) return next.timeMs;
        }
        return Long.MAX_VALUE;
    }

    private void resetKaraoke() {
        // The hierarchy is document state too. A new document must start from its OWN current
        // line, so no row index, no half-finished crossfade and no blur depth may survive the
        // change - an old row would otherwise paint as current for a frame before the first tick.
        AndroidUtilities.cancelRunOnUIThread(advanceLyricsFollow);
        cancelLyricsFollowAnimator();
        lyricsFollowRow = RecyclerView.NO_POSITION;
        lyricsEmphasisFromRow = RecyclerView.NO_POSITION;
        lyricsEmphasisFromStart = 1f;
        lyricsEmphasisToRow = RecyclerView.NO_POSITION;
        lyricsEmphasisToStart = 0f;
        lyricsEmphasisDropRow = RecyclerView.NO_POSITION;
        lyricsEmphasisDropStart = 0f;
        lyricsEmphasisProgress = 1f;
        karaoke.clear();
        rowKaraoke.clear();
        karaokeLine = Integer.MIN_VALUE;
        karaokePositionMs = 0;
        karaokeRow = RecyclerView.NO_POSITION;
        karaokeNextLineTimeMs = Long.MAX_VALUE;
        karaokeMutedSource = 0;
        karaokeSungSource = 0;
    }

    private void applyLyricsDepth(View child) {
        applyLyricsDepth(child, getThemedColor(Theme.key_player_time),
                getThemedColor(Theme.key_player_actionBarTitle), karaokeSweepColor());
    }

    /**
     * Paints one row's place in the page. Three ideas, kept apart on purpose:
     *
     * <ul>
     *     <li><b>depth</b> comes from where the row physically is. The list's glide - including its
     *     pre-roll toward the next line - moves rows through it continuously, which is what makes
     *     the page feel layered. Untouched.</li>
     *     <li><b>focus</b> comes from the follow's own progress: {@link #lyricsFocusOf}. It is
     *     what makes a row the sharp, bright one, and it crosses over continuously across the
     *     scroll rather than switching once the scroll has stopped.</li>
     *     <li><b>word state</b> comes from the source's own offsets and the clock, and only for a
     *     document that genuinely states them. It is resolved against {@code karaokeLine}, never
     *     against focus, so a line fading in early still shows nothing lit until its own time -
     *     and a line fading out keeps painting its real word state the whole way down.</li>
     * </ul>
     */
    private void applyLyricsDepth(View child, int inactiveColor, int activeColor, int sweepColor) {
        if (lyricsListView.getHeight() == 0) return;
        final LyricsTextView textView = child instanceof LyricsTextView ? (LyricsTextView) child : null;
        if (currentLyrics == null || !currentLyrics.isSynced()) {
            // Normal lyrics are read, not followed: no invented focus, no depth falloff, no word
            // effects. They get the same large, bold, airy setting as everything else - that is a
            // question of how the page is set, not of what the source can do - and nothing more.
            child.setAlpha(1f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            if (textView != null) {
                textView.clearKaraoke();
                textView.setDepthBlur(0f);
            }
            return;
        }
        final RecyclerView.ViewHolder holder = lyricsListView.findContainingViewHolder(child);
        final int row = holder == null ? RecyclerView.NO_POSITION : holder.getAdapterPosition();
        final SyncedLyricsController.Line lyricLine = lineForLyricsRow(row);
        // Resolved before the focus, because the focus is composed with it: a line whose own word
        // is still being sung keeps a readable floor while the crossfade carries it away.
        final boolean wordFrame = lyricsWordTimed && lyricLine != null && textView != null
                && rowKaraoke.resolveRow(lyricLine, visibleLyrics.get(row), karaokeLine,
                        karaokePositionMs, karaokeNextLineTimeMs);
        // Only the line the crossfade is carrying AWAY needs protecting. The row the transition is
        // arriving at is already heading to full focus on its own, and lifting it with a floor the
        // instant its first word begins would be a step up rather than a rescue.
        final boolean singing = wordFrame && visibleLyrics.get(row) == karaokeLine
                && row != lyricsEmphasisToRow && rowKaraoke.wordEnd > rowKaraoke.wordStart;
        final float focus = Math.max(lyricsFocusOf(row),
                karaokeReadableFloor(singing, rowKaraoke.sweep));
        final float center = getLyricsFocusCenter();
        final float distance = Math.abs((child.getTop() + child.getBottom()) / 2f - center) / Math.max(1f, center);
        // Smoothstep rather than the raw distance: the falloff starts gently, so the lines either
        // side of the active one stay comfortably readable, and deepens further out, where being
        // subordinate is the point. Linear distance did the opposite of both.
        final float linear = Math.min(1f, distance);
        final float depth = linear * linear * (3f - 2f * linear);
        // Depth is carried by opacity and a small real blur. A subtle scale on the active line
        // (~1.5%) adds physical presence without fighting the karaoke word motion; the pivot is
        // at the reading edge so the text stays anchored to the margin as it grows.
        child.setAlpha(lerp(KARAOKE_REST_ALPHA - depth * KARAOKE_REST_ALPHA_FALLOFF, KARAOKE_ACTIVE_ALPHA, focus));
        final float scale = lerp(1f, KARAOKE_ACTIVE_SCALE, focus);
        child.setScaleX(scale);
        child.setScaleY(scale);
        child.setPivotX(LocaleController.isRTL ? child.getWidth() : 0f);
        child.setPivotY(child.getHeight() / 2f);
        if (textView == null) return;
        textView.setDepthBlur(lerp(depth * dp(KARAOKE_BLUR_MAX_DP), 0f, focus));
        if (wordFrame) {
            // Word timing splits what used to be one colour into two: text the source has reached
            // takes the sung colour, text it has not stays muted, and the word being sung right now
            // is filled from the muted colour into the sung one by a clip travelling across its
            // glyphs. A line already passed is wholly sung; a line the pre-roll is bringing in
            // early shows nothing lit until its own time.
            final int sungColor = ColorUtils.blendARGB(inactiveColor, sweepColor,
                    lerp(KARAOKE_SUNG_REST, 1f, focus));
            final int mutedColor = ColorUtils.blendARGB(inactiveColor, activeColor,
                    lerp(KARAOKE_MUTED_REST, KARAOKE_MUTED_ACTIVE, focus));
            textView.setLyricTextColor(mutedColor);
            textView.setKaraokeColors(mutedColor, sungColor);
            textView.setKaraokeFrame(rowKaraoke.wordStart, rowKaraoke.wordEnd, rowKaraoke.sweep,
                    rowKaraoke.ownedEnd);
        } else {
            // Ordinary line-synced text, and any untimed line inside a karaoke document. Line-level
            // hierarchy only: no sweep, no word motion, nothing invented.
            textView.clearKaraoke();
            textView.setLyricTextColor(ColorUtils.blendARGB(inactiveColor, activeColor, focus));
        }
    }

    /**
     * The colour a finished karaoke word resolves to. The design asks for white, and on every
     * player background white actually reads on, white is exactly what it gets.
     *
     * <p>The one exception is deliberate and is not a silent substitution: on a light player
     * background white text is invisible, so when white does not clear a minimum contrast against
     * {@link Theme#key_player_background} the theme's own title colour - which is guaranteed to
     * read on it - stands in. On the dark players this is a karaoke page is realistically shown on,
     * the answer is {@link Color#WHITE}.
     */
    private int karaokeSweepColor() {
        return karaokeSweepColor(getThemedColor(Theme.key_player_background),
                getThemedColor(Theme.key_player_actionBarTitle));
    }

    /** Minimum contrast white must clear against the player background to be used. */
    static final double KARAOKE_SWEEP_MIN_CONTRAST = 2.0;

    public static int karaokeSweepColor(int playerBackground, int titleColor) {
        final int opaque = ColorUtils.setAlphaComponent(playerBackground, 0xFF);
        return ColorUtils.calculateContrast(Color.WHITE, opaque) >= KARAOKE_SWEEP_MIN_CONTRAST
                ? Color.WHITE : titleColor;
    }

    /** The lyric line a lyrics row shows, or null when the row is not a lyric line right now. */
    private SyncedLyricsController.Line lineForLyricsRow(int row) {
        if (currentLyrics == null || row < 0 || row >= visibleLyrics.size()) return null;
        final int line = visibleLyrics.get(row);
        return line < 0 || line >= currentLyrics.lines.size() ? null : currentLyrics.lines.get(line);
    }

    /** Inset of the expand control (40dp target + 4dp) reserved at the top of the normal viewport. */
    private static final int LYRICS_EXPAND_INSET = 44;

    private void updateLyricsPadding() {
        if (lyricsListView == null || lyricsListView.getHeight() == 0) return;
        int top;
        int bottom;
        if (currentLyrics == null || !currentLyrics.isSynced()) {
            // Untimed lyrics scroll manually, so the first and last lines must both be reachable.
            // The top inset is exactly the expand control's bounds in normal mode - not padding
            // chosen by eye - and fullscreen has no expand control at all.
            top = dp(fullscreenLyrics ? 12 : LYRICS_EXPAND_INSET);
            bottom = dp(24);
        } else {
            top = bottom = Math.max(0, getLyricsFocusCenter() - dp(32));
        }
        if (lyricsListView.getPaddingTop() != top || lyricsListView.getPaddingBottom() != bottom) {
            lyricsListView.setPadding(0, top, 0, bottom);
        }
    }

    /**
     * Opening path used by the compact player. It resolves the persisted page as soon as lyrics are
     * known, and deliberately does not force Lyrics - the one-shot "came in through the compact
     * player so show lyrics" behaviour is gone; the user's saved choice decides.
     */
    public AudioPlayerAlert showLyricsWhenAvailable() {
        applySavedModeOnOpen = true;
        return this;
    }

    /** Explicit Lyrics entry point (returning from the lyrics editor), which is a user choice. */
    public AudioPlayerAlert openLyrics() {
        applySavedModeOnOpen = true;
        lyricsModeRequested = true;
        SyncedLyricsController.getInstance(currentAccount).setLyricsModePreferred(true);
        return this;
    }

    private void checkIfMusicDownloaded(MessageObject messageObject) {
        File cacheFile = null;
        if (messageObject.messageOwner.attachPath != null && messageObject.messageOwner.attachPath.length() > 0) {
            cacheFile = new File(messageObject.messageOwner.attachPath);
            if (!cacheFile.exists()) {
                cacheFile = null;
            }
        }
        if (cacheFile == null) {
            cacheFile = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
        }
        boolean canStream = SharedConfig.streamMedia && (int) messageObject.getDialogId() != 0 && messageObject.isMusic();
        if (!cacheFile.exists() && !canStream) {
            String fileName = messageObject.getFileName();
            DownloadController.getInstance(currentAccount).addLoadingFileObserver(fileName, this);
            Float progress = ImageLoader.getInstance().getFileProgress(fileName);
            progressView.setProgress(progress != null ? progress : 0, false);
            progressView.setVisibility(View.VISIBLE);
            seekBarView.setVisibility(View.INVISIBLE);
            playButton.setEnabled(false);
        } else {
            DownloadController.getInstance(currentAccount).removeLoadingFileObserver(this);
            progressView.setVisibility(View.INVISIBLE);
            seekBarView.setVisibility(View.VISIBLE);
            playButton.setEnabled(true);
        }
    }

    private void updateTitle(boolean shutdown) {
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null && shutdown || messageObject != null && !messageObject.isMusic()) {
            dismiss();
        } else {
            if (messageObject == null) {
                lastMessageObject = null;
                return;
            }
            final boolean sameMessageObject = messageObject == lastMessageObject;
            lastMessageObject = messageObject;
            if (messageObject.eventId != 0 || messageObject.getId() <= -2000000000) {
                optionsButton.setVisibility(View.INVISIBLE);
            } else {
                optionsButton.setVisibility(View.VISIBLE);
            }
            final long dialogId = messageObject.getDialogId();
            final long docId = messageObject.getDocument() != null ? messageObject.getDocument().id : 0L;
            final boolean noforwards = (
                dialogId < 0 && MessagesController.getInstance(currentAccount).isPeerNoForwards(dialogId) ||
                MessagesController.getInstance(currentAccount).isPeerNoForwards(messageObject.getDialogId()) ||
                messageObject.messageOwner.noforwards
            );
            if (noforwards != this.noforwards) {
                this.noforwards = noforwards;

                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) playerLayout.getLayoutParams();
                layoutParams.height = dp(getPlayerHeight());
                playerLayout.setLayoutParams(layoutParams);

                layoutParams = (FrameLayout.LayoutParams) playerShadow.getLayoutParams();
                layoutParams.bottomMargin = dp(getPlayerHeight());
                playerShadow.setLayoutParams(layoutParams);
                layoutParams = (FrameLayout.LayoutParams) lyricsListView.getLayoutParams();
                layoutParams.bottomMargin = dp(getPlayerHeight());
                lyricsListView.setLayoutParams(layoutParams);
            }
            if (noforwards) {
                optionsButton.hideSubItem(1);
                optionsButton.hideSubItem(2);
                optionsButton.hideSubItem(5);
                optionsButton.hideSubItem(6);
                optionsButton.setAdditionalYOffset(-dp(16));
            } else {
                optionsButton.showSubItem(1);
                optionsButton.showSubItem(2);
                optionsButton.showSubItem(5);
                optionsButton.setAdditionalYOffset(-dp(157 + 40));
            }

            checkIfMusicDownloaded(messageObject);
            updateProgress(messageObject, !sameMessageObject);
            updateCover(messageObject, !sameMessageObject);

            if (MediaController.getInstance().isMessagePaused()) {
                playPauseDrawable.setPause(false);
                playButton.setContentDescription(LocaleController.getString(R.string.AccActionPlay));
            } else {
                playPauseDrawable.setPause(true);
                playButton.setContentDescription(LocaleController.getString(R.string.AccActionPause));
            }
            String title = messageObject.getMusicTitle();
            String author = messageObject.getMusicAuthor();
            titleTextView.setText(title);
            authorTextView.setText(author);
            activeLyricsLine = Integer.MIN_VALUE;
            updateLyrics(!sameMessageObject);

            final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount).getSavedMusicIds();
            saveToProfileButton.setLoading(musicIds.loading);
            setVisibleInProfile(musicIds.ids.contains(docId));

            int duration = lastDuration = (int) messageObject.getDuration();

            if (durationTextView != null) {
                durationTextView.setText(duration != 0 ? AndroidUtilities.formatShortDuration(duration) : "-:--");
            }

            if (duration > 60 * 10) {
                playbackSpeedButton.setVisibility(View.VISIBLE);
            } else {
                playbackSpeedButton.setVisibility(View.GONE);
            }

            if (!sameMessageObject) {
                preloadNeighboringThumbs();
            }
        }
    }

    private void updateCover(MessageObject messageObject, boolean animated) {
        final BackupImageView imageView = animated ? coverContainer.getNextImageView() : coverContainer.getImageView();
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (animated) {
            coverContainer.switchImageViews();
        }
        if (audioInfo != null && audioInfo.getCover() != null) {
            imageView.setImageBitmap(audioInfo.getCover());
            currentFile = null;
            currentAudioFinishedLoading = true;
        } else {
            TLRPC.Document document = messageObject.getDocument();
            currentFile = FileLoader.getAttachFileName(document);
            currentAudioFinishedLoading = false;
            String artworkUrl = messageObject.getArtworkUrl(false);
            final ImageLocation thumbImageLocation = getArtworkThumbImageLocation(messageObject);
            if (!TextUtils.isEmpty(artworkUrl)) {
                imageView.setImage(ImageLocation.getForPath(artworkUrl), null, thumbImageLocation, null, null, 0, 1, messageObject);
            } else if (thumbImageLocation != null) {
                imageView.setImage(null, null, thumbImageLocation, null, null, 0, 1, messageObject);
            } else {
                imageView.setImageDrawable(null);
            }
            imageView.invalidate();
        }
    }

    private ImageLocation getArtworkThumbImageLocation(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String smallArtworkUrl = messageObject.getArtworkUrl(true);
        if (smallArtworkUrl != null) {
            return ImageLocation.getForPath(smallArtworkUrl);
        }
        return null;
    }

    private void preloadNeighboringThumbs() {
        final MediaController mediaController = MediaController.getInstance();
        final List<MessageObject> playlist = mediaController.getPlaylist();
        if (playlist.size() <= 1) {
            return;
        }

        final List<MessageObject> neighboringItems = new ArrayList<>();
        final int playingIndex = mediaController.getPlayingMessageObjectNum();

        int nextIndex = playingIndex + 1;
        int prevIndex = playingIndex - 1;
        if (nextIndex >= playlist.size()) {
            nextIndex = 0;
        }
        if (nextIndex <= -1) {
            nextIndex = playlist.size() - 1;
        }
        if (prevIndex <= -1) {
            prevIndex = playlist.size() - 1;
        }
        if (prevIndex >= playlist.size()) {
            prevIndex = 0;
        }

        neighboringItems.add(playlist.get(nextIndex));
        if (nextIndex != prevIndex) {
            neighboringItems.add(playlist.get(prevIndex));
        }

        for (int i = 0, N = neighboringItems.size(); i < N; i++) {
            final MessageObject messageObject = neighboringItems.get(i);
            final ImageLocation thumbImageLocation = getArtworkThumbImageLocation(messageObject);
            if (thumbImageLocation != null) {
                if (thumbImageLocation.path != null) {
                    ImageLoader.getInstance().preloadArtwork(thumbImageLocation.path);
                } else {
                    FileLoader.getInstance(currentAccount).loadFile(thumbImageLocation, messageObject, null, FileLoader.PRIORITY_LOW, 1);
                }
            }
        }
    }

    /**
     * Everything the large player needs to paint one lyric line at one playback position, and
     * nothing else. It is a pure function of the position and of the times the source stated: the
     * same position always produces the same frame, so a seek, a pause, a track change, a theme
     * change or a recycled row rebinding all reconstruct the correct picture immediately, and no
     * part of the presentation has state of its own that would have to be unwound.
     *
     * <p>Note what is <em>not</em> here: nothing invents a word's end, stores one, or lets a visual
     * interval feed back into which word or line is current. {@link #wordStart}/{@link #wordEnd}
     * come straight from the source's offsets and {@link #sweep} is decided after them, never the
     * other way round.
     */
    public static final class KaraokeFrame {
        /**
         * Safety bound on a fill derived for start-only word timing, for the genuinely huge gap -
         * a word held over the start of an instrumental break, say - where the next stated time is
         * seconds away and is plainly not describing how long the word was sung.
         *
         * <p>It is deliberately far longer than any sung syllable. Ordinary held words - half a
         * second, a second, a second and a half - are well inside it and therefore follow their
         * real interval exactly. It is not a duration this class prefers; it is the point past
         * which the source's next timestamp stops being evidence about this word.
         */
        public static final long SWEEP_DERIVED_MAX_MS = 3000;
        /** Used only when start-only timing gives nothing at all to bound the fill with. */
        public static final long SWEEP_DERIVED_FALLBACK_MS = 600;
        /** True when this line has genuine inline timing to show at the resolved position. */
        public boolean active;
        /**
         * Exclusive UTF-16 end, in {@link SyncedLyricsController.Line#text}, of the text the source
         * has finished. Always a segment boundary, so it never falls inside a surrogate pair.
         */
        public int sungEnd;
        /** Range of the word being sung right now. Empty when no word has begun on this line. */
        public int wordStart;
        public int wordEnd;
        /** 0..1 across the word at {@link #wordStart}: how much of it the fill has travelled. */
        public float sweep;
        /**
         * Exclusive end of the visual space this word "owns" — from its text start to the next
         * word's text start (or end-of-line for the terminal word). Including trailing whitespace
         * lets the continuous cursor traverse inter-word gaps without snapping.
         */
        public int ownedEnd;

        public void clear() {
            active = false;
            sungEnd = 0;
            wordStart = 0;
            wordEnd = 0;
            sweep = 0f;
            ownedEnd = 0;
        }

        /**
         * Resolves this holder for one line of a document, given which line the position is
         * currently inside. This is the whole of the decision: a line the position has already
         * left has had every word it states started, so all of it has been sung; a line the
         * position has not reached yet states nothing that has happened, so none of it has, even
         * if a player is already moving it into view. Only the current line is resolved against
         * the clock.
         *
         * <p>Returning false means this line has no genuine inline timing at all, and is the
         * signal to render it exactly the way it was rendered before word timing existed.
         */
        public boolean resolveRow(SyncedLyricsController.Line line, int lineIndex, int currentLine,
                                  long positionMs, long nextLineTimeMs) {
            clear();
            if (line == null || line.segments == null || line.text.isEmpty()) return false;
            if (lineIndex == currentLine) {
                if (resolve(line, positionMs, nextLineTimeMs)) return true;
                clear(); // the line's own timestamp has not been reached, so nothing has happened
            } else if (lineIndex < currentLine) {
                // Already left behind: every word it states has started, so all of it is sung. This
                // is also what keeps a fast line's last word from being swallowed - it finishes
                // lit rather than being caught mid-fill by the line change.
                sungEnd = wordStart = wordEnd = ownedEnd = line.text.length();
            }
            // Everything else is a line the position has not reached - including one a player is
            // already moving into view - and clear() has left every boundary at zero.
            active = true;
            return true;
        }

        /**
         * Resolves this holder against one line at one playback position, and reports whether the
         * line has genuine timing to show.
         *
         * <p>Returning false is the fallback path: a line with no inline timing, a line whose own
         * timestamp has not been reached, and an untimed line all land there, and the consumer is
         * expected to fall back to line-level behaviour rather than invent anything.
         *
         * @param nextLineTimeMs the stated start of the line after this one, or
         *                       {@link Long#MAX_VALUE} when there is none. It is one of the genuine
         *                       times a derived fill may be bounded by; it is never stored as a
         *                       word's end.
         */
        public boolean resolve(SyncedLyricsController.Line line, long positionMs, long nextLineTimeMs) {
            clear();
            if (line == null || line.segments == null || line.text.isEmpty()) return false;
            if (!line.timed || positionMs < line.timeMs) return false;
            final SyncedLyricsController.Segments segments = line.segments;
            final int index = segments.indexAt(positionMs);
            if (index < 0) {
                // The line is current but its first stated tag has not been reached. Any text
                // before that tag carries no timing of its own; it belongs to the line and takes
                // the line's own granularity, which is the only thing stated about it.
                sungEnd = wordStart = wordEnd = segments.startOffset(0);
                active = true;
                return true;
            }
            wordStart = segments.startOffset(index);
            wordEnd = Math.max(wordStart, segments.endOffset(index));
            sungEnd = wordStart;
            ownedEnd = (index + 1 < segments.size())
                    ? segments.startOffset(index + 1) : line.text.length();
            final long start = segments.startTimeMs(index);
            final long elapsed = Math.max(0L, positionMs - start);
            final long sweepMs = sweepWindowMs(segments, index, nextLineTimeMs);
            sweep = sweepMs <= 0 ? 1f : clamp01(elapsed / (float) sweepMs);
            active = true;
            return true;
        }

        /**
         * How long a word genuinely owns the presentation: from its stated start to the next
         * stated start there is, whether that is the next word's or - for the last word of a line
         * - the next line's. Returns -1 when the source states nothing after this word at all.
         *
         * <p>This is a rendering interval and only ever that. It is not written back, it is not a
         * claim that the word ended here, and {@link SyncedLyricsController.Segments#indexAt} does
         * not consult it: which word is current is still decided purely by stated starts.
         */
        public static long ownershipWindowMs(SyncedLyricsController.Segments segments, int index, long nextLineTimeMs) {
            final long start = segments.startTimeMs(index);
            long bound = -1;
            if (index + 1 < segments.size()) {
                bound = segments.startTimeMs(index + 1) - start;
            } else if (nextLineTimeMs != Long.MAX_VALUE) {
                bound = nextLineTimeMs - start;
            }
            return bound > 0 ? bound : -1;
        }

        /**
         * How long the fill across one word takes, in milliseconds.
         *
         * <p>Where the source stated an end for the word - TTML does, per span - that stated
         * interval <em>is</em> the answer, whatever its length: a word held for a second and a half
         * fills for a second and a half. Nothing shortens it and nothing lengthens it.
         *
         * <p>Where the source stated a start only - Enhanced LRC always does - there is no end to
         * follow, so the fill takes the whole interval the word genuinely owns: up to the next
         * word's stated start, or for the last word of a line up to the next line's. That is the
         * best evidence the file contains about how long the word was sung, and using it is the
         * difference between a fill that tracks the voice and one that finishes while the singer
         * is still holding the note. Only a gap so large that it cannot be describing a syllable
         * at all is bounded, by {@link #SWEEP_DERIVED_MAX_MS}.
         */
        public static long sweepWindowMs(SyncedLyricsController.Segments segments, int index, long nextLineTimeMs) {
            final long start = segments.startTimeMs(index);
            if (segments.hasEndTime(index)) {
                final long stated = segments.endTimeMs(index) - start;
                if (stated > 0) return stated;
            }
            final long bound = ownershipWindowMs(segments, index, nextLineTimeMs);
            if (bound <= 0) return SWEEP_DERIVED_FALLBACK_MS;
            return Math.min(bound, SWEEP_DERIVED_MAX_MS);
        }

        /**
         * True when segment {@code index} continues the same DISPLAYED word as the one before it -
         * that is, when the source split a single written word across several stated times and
         * there is no whitespace between them.
         *
         * <p>Decided from the text itself, on the offsets the source stated, so it is the real
         * lexical boundary and not a guess from the timing. A gap of any length between two stated
         * times inside one written word is still one word on the page, which is the only thing the
         * decoration cares about.
         */
        public static boolean isWordContinuation(CharSequence text, SyncedLyricsController.Segments segments, int index) {
            if (index <= 0 || index >= segments.size()) return false;
            final int from = Math.max(0, segments.startOffset(index - 1));
            final int to = Math.min(text.length(), segments.startOffset(index));
            if (to <= from) return false;
            for (int i = from; i < to; i++) {
                if (Character.isWhitespace(text.charAt(i))) return false;
            }
            return true;
        }

        /** First segment of the displayed word that segment {@code index} belongs to. */
        public static int lexicalStartIndex(CharSequence text, SyncedLyricsController.Segments segments, int index) {
            int first = Math.max(0, index);
            while (first > 0 && isWordContinuation(text, segments, first)) first--;
            return first;
        }

        private static float clamp01(float value) {
            return value < 0f ? 0f : value > 1f ? 1f : value;
        }
    }

    /**
     * The draw-time half of the karaoke presentation: where the word being sung actually sits on
     * the page, how far across it the fill has travelled, and which side it is read from. Every
     * method here is pure, so the picture is decided by the playback position and the row's own
     * {@link Layout} and by nothing that has to be kept, cancelled or unwound.
     *
     * <p>Nothing here measures text. The one method that asks where something is asks the
     * {@link Layout} the row is already drawing from, so shaping, kerning, wrapping, bidirectional
     * reordering and grapheme clusters stay the platform's answer rather than becoming this class's
     * approximation of it.
     */
    public static final class KaraokeGeometry {
        private static final int ZERO_WIDTH_JOINER = 0x200D;

        /** Receives the visual pieces of a logical range, in the order the range is read. */
        public interface RunSink {
            void addRun(float left, float right, float top, float bottom, boolean rightToLeft);
        }

        private KaraokeGeometry() {}

        /**
         * One edge of one offset on one visual line.
         *
         * <p>{@code trailing} picks which side of the offset is wanted: the leading edge of the
         * character AT the offset, or the trailing edge of the character BEFORE it. Inside a run
         * the two are the same point. At a bidirectional run boundary they are not, and only the
         * trailing one belongs to the run that just ended - which is why a run's right-hand
         * question must be asked this way rather than with {@code getPrimaryHorizontal} alone.
         *
         * <p>Both accessors resolve an offset that sits exactly on a wrap to the <em>following</em>
         * line, so the end of a word that wraps would be reported at the start of the next line.
         * Such an offset is taken from this line's own visible extent instead - past the trailing
         * whitespace where there is any, and from the line's measured edge where the wrap fell
         * mid-word and there is none.
         */
        public static float edgeAt(Layout layout, int line, int offset, boolean trailing) {
            final int lineStart = layout.getLineStart(line);
            final int lineEnd = layout.getLineEnd(line);
            if (offset <= lineStart) return layout.getPrimaryHorizontal(lineStart);
            if (offset >= lineEnd) {
                final int visibleEnd = layout.getLineVisibleEnd(line);
                if (visibleEnd > lineStart && visibleEnd < lineEnd) {
                    return trailing ? layout.getSecondaryHorizontal(visibleEnd)
                            : layout.getPrimaryHorizontal(visibleEnd);
                }
                return layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT
                        ? layout.getLineLeft(line) : layout.getLineRight(line);
            }
            return trailing ? layout.getSecondaryHorizontal(offset) : layout.getPrimaryHorizontal(offset);
        }

        /** Backwards-compatible leading-edge accessor. */
        public static float horizontalAt(Layout layout, int line, int offset) {
            return edgeAt(layout, line, offset, false);
        }

        /**
         * Reports the visual pieces of one logical range, in reading order.
         *
         * <p>A logical range is not a rectangle. It becomes several when it wraps, and it becomes
         * several <em>on one line</em> when the text is bidirectional: an Arabic line with a Latin
         * word in it, or a Hebrew line with a Western number, reorders those characters away from
         * their logical neighbours, so the span between the range's first and last horizontal
         * positions can cover glyphs that are not in the range at all. Filling that span would
         * sweep unrelated text.
         *
         * <p>So the range is cut at every wrap and at every change of resolved direction - the run
         * boundaries Android itself laid the text out with, read back through
         * {@link Layout#isRtlCharAt} - and each piece is measured from its own two edges. Every
         * piece is a real run, each is reported with the side it is read from, and the pieces
         * arrive in logical order so a fill can travel through them the way the word is sung.
         *
         * <p>Nothing here measures text: every number comes from the {@link Layout} the row is
         * already drawing from.
         */
        public static void forEachVisualRun(Layout layout, int start, int end, RunSink sink) {
            if (layout == null || end <= start || start < 0) return;
            if (end > layout.getText().length()) return;
            final int firstLine = layout.getLineForOffset(start);
            final int lastLine = layout.getLineForOffset(end - 1);
            for (int line = firstLine; line <= lastLine; line++) {
                final int from = Math.max(start, layout.getLineStart(line));
                final int to = Math.min(end, layout.getLineEnd(line));
                if (to <= from) continue;
                final float top = layout.getLineTop(line);
                final float bottom = layout.getLineBottom(line);
                int runStart = from;
                boolean runRtl = layout.isRtlCharAt(runStart);
                for (int i = from + 1; i <= to; i++) {
                    final boolean rtl = i < to && layout.isRtlCharAt(i);
                    if (i < to && rtl == runRtl) continue;
                    emitRun(layout, line, runStart, i, top, bottom, sink);
                    runStart = i;
                    runRtl = rtl;
                }
            }
        }

        private static void emitRun(Layout layout, int line, int from, int to, float top, float bottom, RunSink sink) {
            final float leading = edgeAt(layout, line, from, false);
            final float trailing = edgeAt(layout, line, to, true);
            final float left = Math.min(leading, trailing);
            final float right = Math.max(leading, trailing);
            if (right - left <= 0.01f) return;
            // The run's own direction, taken from where its two ends actually landed, so an RTL
            // run fills from its right edge and an LTR one from its left. The paragraph direction
            // is only the tie-break for a run too narrow to tell.
            sink.addRun(left, right, top, bottom, isRightToLeft(leading, trailing,
                    layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT));
        }

        /**
         * Which way the fill travels across one visual run, taken from where the run's two ends
         * actually landed: an RTL word's logical start is its right edge, so it fills right to
         * left. The paragraph's direction is only the tie-break for a run too narrow to tell.
         */
        public static boolean isRightToLeft(float from, float to, boolean paragraphRightToLeft) {
            if (to < from) return true;
            if (to > from) return false;
            return paragraphRightToLeft;
        }

        /**
         * How much of one run's share of the word is filled, given how much of the word has been
         * filled in total and how much of it the earlier runs account for. A word that wraps or
         * that reorders therefore fills its first piece completely before its second begins, which
         * is the order it is read in.
         */
        public static float revealedWidth(float reveal, float consumedBefore, float runWidth) {
            final float shown = reveal - consumedBefore;
            if (shown <= 0f) return 0f;
            return shown > runWidth ? runWidth : shown;
        }

        /** Left edge of the filled rectangle, grown from whichever side the run is read from. */
        public static float revealedLeft(float left, float right, float revealed, boolean rightToLeft) {
            return rightToLeft ? right - revealed : left;
        }

        // --- grapheme boundaries -----------------------------------------------------------
        // Reused across calls: a boundary is asked for when the word changes, a few times a
        // second, and neither the iterator nor the string it is set on should be rebuilt for that.
        // UI thread only, like everything else that paints a row.
        private static BreakIterator graphemes;
        private static CharSequence graphemeSource;

        /**
         * Moves a highlight boundary off the inside of a grapheme cluster, forward to its end.
         *
         * <p>The segmentation itself is the platform's: {@link BreakIterator#getCharacterInstance}
         * is Android's ICU-backed implementation of UAX #29 extended grapheme clusters, so
         * surrogate pairs, combining marks, Indic virama conjuncts, Hangul jamo sequences, Thai and
         * everything else are its answer and not a list maintained here.
         *
         * <p>The loop after it is a compatibility backstop and nothing more. ICU only gained the
         * emoji clustering rules - ZWJ sequences, skin-tone modifiers, flags, variation selectors -
         * in a later revision than the oldest Android this app runs on carries, so on those devices
         * the iterator would still stop inside an emoji. Advancing past those four cases costs
         * nothing on a modern device, where the iterator has already put the boundary past them.
         *
         * <p>This is a rendering adjustment and nothing else: it moves what is painted, never a
         * timestamp and never which word is current.
         */
        public static int clusterEnd(CharSequence text, int offset) {
            final int length = text.length();
            int end = Math.max(0, Math.min(length, offset));
            if (end <= 0 || end >= length) return end;
            end = graphemeEnd(text, end);
            while (end > 0 && end < length) {
                final int next = Character.codePointAt(text, end);
                final int previous = Character.codePointBefore(text, end);
                if (previous == ZERO_WIDTH_JOINER || isVariationSelector(next) || isSkinToneModifier(next)) {
                    end = graphemeEnd(text, end + Character.charCount(next));
                } else if (next == ZERO_WIDTH_JOINER) {
                    end = graphemeEnd(text, end + 1); // the joiner itself; the next pass takes what it joins on
                } else if (isRegionalIndicator(next) && regionalIndicatorsBefore(text, end) % 2 == 1) {
                    end = graphemeEnd(text, end + Character.charCount(next)); // the second half of a flag
                } else {
                    break;
                }
            }
            return end;
        }

        /** The end of the cluster {@code offset} falls inside, or {@code offset} if it is on one. */
        private static int graphemeEnd(CharSequence text, int offset) {
            final int length = text.length();
            if (offset <= 0) return 0;
            if (offset >= length) return length;
            try {
                final BreakIterator iterator = graphemeIterator(text);
                if (iterator.isBoundary(offset)) return offset;
                final int following = iterator.following(offset);
                return following == BreakIterator.DONE ? length : following;
            } catch (Exception ignored) {
                // A segmenter that cannot answer must never be the reason a lyric fails to paint.
                return offset;
            }
        }

        private static BreakIterator graphemeIterator(CharSequence text) {
            if (graphemes == null) graphemes = BreakIterator.getCharacterInstance();
            if (graphemeSource != text) {
                graphemeSource = text;
                graphemes.setText(text.toString());
            }
            return graphemes;
        }

        private static boolean isVariationSelector(int codePoint) {
            return codePoint >= 0xFE00 && codePoint <= 0xFE0F
                    || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
        }

        private static boolean isSkinToneModifier(int codePoint) {
            return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
        }

        private static boolean isRegionalIndicator(int codePoint) {
            return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
        }

        /** Length of the run of regional indicators ending at {@code offset}, in code points. */
        private static int regionalIndicatorsBefore(CharSequence text, int offset) {
            int count = 0;
            int index = offset;
            while (index > 0) {
                final int codePoint = Character.codePointBefore(text, index);
                if (!isRegionalIndicator(codePoint)) break;
                index -= Character.charCount(codePoint);
                count++;
            }
            return count;
        }
    }

    /**
     * A lyrics row. Identical to the plain {@link TextView} it replaces in every layout respect -
     * same gravity, padding, sizing and text direction - it adds exactly one thing: COLOUR.
     *
     * <p>The row is drawn by ONE ordinary {@link TextView#onDraw} per frame. There is no override
     * of it at all, which is the point: nothing here clips the canvas, translates it, splits the
     * row into strips, or rasterises any glyph more than once. Karaoke is expressed entirely as
     * appearance state on three {@link KaraokeSpan}s, and a frame is
     * "set the appearance, then invalidate".
     *
     * <p>{@link KaraokeSpan} is a {@link CharacterStyle} that implements {@link UpdateAppearance}
     * and touches nothing but the paint's colour and shader. Android therefore knows it cannot
     * affect metrics and does not re-measure or re-wrap the line for it. Typeface, text size,
     * fake-bold, scaleX, letter spacing, every glyph position, the word widths, the line breaks
     * and the row height are all decided once, by the platform, from the text alone - and are
     * bit-for-bit independent of the sweep. A word cannot change shape, size, weight, spacing or
     * position as the sung boundary crosses it, because the only thing that differs between
     * sweep 0 and sweep 1 is which colour a pixel is asked to be.
     *
     * <p>The word being sung is filled by a {@link LinearGradient} with a HARD stop, set on the
     * paint of the one grapheme the fill front is inside. The grapheme is drawn once, in its final
     * geometry, with sung colour on one side of the boundary and muted on the other - never once
     * muted and again white. Everything before it is a solid sung span and everything after it a
     * solid muted one.
     *
     * <p>The text itself is always drawn by the platform, from the row's own {@link Layout}. That
     * hands shaping, kerning, wrapping, bidirectional reordering and grapheme clusters back to
     * Android: a word that wraps onto a second visual line, or that is a logical range inside RTL
     * text, is laid out and drawn by exactly the code that would have drawn it unhighlighted.
     */
    private static class LyricsTextView extends TextView implements KaraokeGeometry.RunSink {
        /**
         * One span per VISUAL RUN - one bidi run of one visual line - covering the whole row.
         *
         * <p>The ranges are a property of the {@link Layout} alone and do NOT move with the sweep.
         * That is the whole point, and it is measured: Android's {@link android.text.TextLine}
         * cannot merge two runs whose paints differ, so every span boundary becomes a separate
         * {@code drawTextRun}. A boundary that falls inside a shaping run changes the glyphs the
         * shaper produces - a ligature that straddles it is no longer formed, kerning across it is
         * lost - which is a real change of shape and of width, not of colour. Splitting only where
         * the platform already splits (at a wrap, and at a direction change) costs nothing, so the
         * glyphs are bit-for-bit those of an unstyled render at every sweep value.
         */
        private KaraokeSpan[] runSpans = new KaraokeSpan[4];
        /** Logical range of each visual run, and its visual extent, taken from the layout. */
        private int[] runStart = new int[4];
        private int[] runEnd = new int[4];
        private float[] runLeft = new float[4];
        private float[] runRight = new float[4];
        private int runCount;
        /** The view's own mutable copy of the text, or null for a line with no inline timing. */
        private Spannable karaokeText;

        private boolean karaokeActive;
        private int mutedColor;
        private int sungColor;
        /** The last offsets asked for, before snapping, so an unchanged frame rescans nothing. */
        private int requestedStart = -1;
        private int requestedEnd = -1;
        /** The cluster-snapped range actually painted. */
        private int wordStart;
        private int wordEnd;
        private float sweep;
        /** Owned visual end: next word's start (or line end for terminal word). See KaraokeFrame. */
        private int wordOwnedEnd;
        /** The colour boundaries the spans currently carry, so an unchanged frame re-sets nothing. */
        private int spanSungTo = -1;
        private int spanWordTo = -1;

        // --- grapheme clusters of this row ---------------------------------------------------
        // Boundaries depend on the text and rects on the layout, so each is rebuilt only when the
        // thing it depends on changes. A tick walks them and allocates nothing.
        private CharSequence clusterText;
        private int clusterCount;
        private int[] clusterStart = new int[32];
        private Layout clusterLayout;
        private CharSequence clusterGeometryText;
        private int clusterGeometryCount;
        /**
         * Where each grapheme is, and which way its run reads. Horizontal only: the fill needs the
         * width of each grapheme to know how far the sweep has travelled, and the reading direction
         * of the one it is inside to know which side of it is already sung. Nothing here is a
         * height, and nothing here is ever written back to the layout.
         */
        private float[] clusterLeft = new float[32];
        private float[] clusterRight = new float[32];
        private boolean[] clusterRtl = new boolean[32];
        private boolean[] clusterHasRect = new boolean[32];
        /** The cluster {@link #addRun} is currently filling, or -1 outside a geometry rebuild. */
        private int pendingCluster = -1;
        /** The cluster the fill front is inside, and how far into it the fill has travelled. */
        private int frontCluster = -1;
        private float frontRevealed;
        /** Cached so a repaint with an unchanged colour never allocates a ColorStateList. */
        private int lyricTextColor;
        private boolean lyricTextColorSet;
        /** Quantised blur radius currently on the view, or -1 when nothing has been applied yet. */
        private int appliedBlur = -1;


        LyricsTextView(Context context) {
            super(context);
        }

        /**
         * Binds the row's text. Only a line that genuinely states inline timing is kept spannable;
         * every other row is a plain string, exactly as before. A recycled row is fully reset here,
         * so it can never keep a previous line's word state.
         */
        void setLyricText(CharSequence text, boolean wordTimed) {
            detachSpans();
            karaokeActive = false;
            if (wordTimed) {
                // TextView always makes its own spannable copy here, so the one to colour is the
                // one it ends up holding, not the one handed in.
                setText(text, BufferType.SPANNABLE);
                final CharSequence bound = getText();
                karaokeText = bound instanceof Spannable ? (Spannable) bound : null;
            } else {
                setText(text);
                karaokeText = null;
            }
        }

        /** Sets the text colour without the ColorStateList a repeated call would allocate. */
        void setLyricTextColor(int color) {
            if (lyricTextColorSet && lyricTextColor == color) return;
            lyricTextColor = color;
            lyricTextColorSet = true;
            setTextColor(color);
        }

        void setKaraokeColors(int muted, int sung) {
            if (mutedColor == muted && sungColor == sung) return;
            mutedColor = muted;
            sungColor = sung;
            if (karaokeActive) {
                // The spans carry resolved colours, and the gradient is built from them, so a new
                // palette - a theme change, or this row moving through the focus crossfade - has to
                // be pushed into them. Appearance only: it cannot move a boundary or a metric.
                updateAppearance();
                invalidate();
            }
        }

        /**
         * Pushes one resolved frame: the word the source says is current, and how far its fill has
         * travelled across it. That is the whole of it - there is no vertical state, no wave and no
         * clock. Both values are boundaries the source stated plus a function of the playback
         * position, so pushing the same position twice is a no-op and a settled line costs nothing
         * per tick.
         */
        void setKaraokeFrame(int start, int end, float sweepProgress, int ownedEnd) {
            if (karaokeText == null) return;
            // A row that was not painting karaoke a moment ago - a fresh bind, a recycled view, a
            // line that has just become relevant - carries no spans at all, so its first frame
            // always attaches them rather than trusting the offsets it happens to hold.
            final boolean wasInactive = !karaokeActive;
            karaokeActive = true;
            boolean changed = wasInactive;
            if (wasInactive || requestedStart != start || requestedEnd != end) {
                requestedStart = start;
                requestedEnd = end;
                final int snappedStart = KaraokeGeometry.clusterEnd(karaokeText, start);
                final int snappedEnd = Math.max(snappedStart, KaraokeGeometry.clusterEnd(karaokeText, end));
                if (wasInactive || wordStart != snappedStart || wordEnd != snappedEnd) {
                    wordStart = snappedStart;
                    wordEnd = snappedEnd;
                    changed = true;
                }
            }
            if (wordOwnedEnd != ownedEnd) {
                wordOwnedEnd = ownedEnd;
                changed = true;
            }
            if (Math.abs(sweep - sweepProgress) > 0.0015f) {
                sweep = sweepProgress;
                changed = true;
            }
            if (resolveColourBoundaries()) changed = true;
            // SpannableString does not report span changes to a SpanWatcher, and a colour changed
            // in place is not a change the text could report anyway, so every repaint here is this
            // one. It is also the reason nothing in this class can trigger a re-measure.
            if (changed) invalidate();
        }

        /**
         * Finds the grapheme the fill front is inside and colours the line around it: everything
         * before it is sung, everything after it is still to come, and it alone carries the
         * gradient whose hard stop is the sung boundary.
         *
         * <p>Which grapheme the front is inside, and how far into it the fill has travelled, come
         * from the sum of the graphemes' own advance widths in reading order - the row's own
         * {@link Layout} measured them, nothing here measures anything - so the fill tracks the
         * real visual word for LTR, RTL, Arabic, Amharic, combining marks, emoji, ZWJ sequences,
         * ligatures and a word that wrapped alike.
         *
         * <p>Done here rather than while drawing because setting a span asks the view to repaint,
         * and a view may not ask itself to repaint from inside its own draw.
         */
        private boolean resolveColourBoundaries() {
            if (karaokeText == null) return false;
            // A rebuilt geometry can move a grapheme while leaving the fill on the same grapheme at
            // the same distance into it, and the gradient is expressed in those moved coordinates,
            // so a rebuild always re-derives the appearance even when nothing else changed.
            final boolean relaidOut = ensureClusterGeometry();
            int front = -1;
            float revealed = 0f;
            int sungTo;
            int wordTo;
            // Owned end clamped to the visual line wordStart is on, so the cursor never crosses a
            // line-wrap boundary. This lets sweep=1 colour the trailing whitespace gap (the space
            // between this word and the next) as sung, eliminating the snapping artefact.
            final int effectiveOwnedEnd;
            if (wordOwnedEnd > wordEnd) {
                final Layout layout = getLayout();
                if (layout != null && wordStart >= 0 && wordStart < karaokeText.length()) {
                    final int visualLine = layout.getLineForOffset(wordStart);
                    effectiveOwnedEnd = Math.min(wordOwnedEnd, layout.getLineEnd(visualLine));
                } else {
                    effectiveOwnedEnd = wordOwnedEnd;
                }
            } else {
                effectiveOwnedEnd = wordEnd;
            }
            if (wordEnd <= wordStart) {
                sungTo = wordTo = wordStart;
            } else if (sweep >= 1f) {
                sungTo = wordTo = effectiveOwnedEnd;
            } else if (clusterGeometryCount != clusterCount || clusterCount == 0) {
                // Not laid out yet. The word reads as still to come, and the next tick - by which
                // time there is a layout - puts the fill where the clock says it is.
                sungTo = wordTo = wordStart;
            } else {
                float total = 0f;
                for (int i = 0; i < clusterCount; i++) {
                    final int offset = clusterStart[i];
                    if (offset < wordStart) continue;
                    if (offset >= effectiveOwnedEnd) break;
                    if (isBlankCluster(i) || !clusterHasRect[i]) continue;
                    total += clusterRight[i] - clusterLeft[i];
                }
                if (total <= 0f) {
                    sungTo = wordTo = wordStart;
                } else {
                    final float reveal = sweep * total;
                    float consumed = 0f;
                    for (int i = 0; i < clusterCount; i++) {
                        final int offset = clusterStart[i];
                        if (offset < wordStart) continue;
                        if (offset >= effectiveOwnedEnd) break;
                        if (isBlankCluster(i) || !clusterHasRect[i]) continue;
                        final float width = clusterRight[i] - clusterLeft[i];
                        if (reveal < consumed + width) {
                            front = i;
                            revealed = reveal - consumed;
                            if (revealed < 0f) revealed = 0f;
                            break;
                        }
                        consumed += width;
                    }
                    if (front < 0) {
                        sungTo = wordTo = effectiveOwnedEnd;
                    } else {
                        sungTo = clusterStart[front];
                        wordTo = clusterStart[front + 1];
                    }
                }
            }
            boolean changed = false;
            if (front != frontCluster || Math.abs(revealed - frontRevealed) > 0.05f) {
                frontCluster = front;
                frontRevealed = revealed;
                changed = true;
            }
            if (spanSungTo != sungTo || spanWordTo != wordTo) {
                spanSungTo = sungTo;
                spanWordTo = wordTo;
                changed = true;
            }
            // Note what is NOT here any more: nothing calls setSpan. The spans are attached once
            // per layout, at run boundaries, and a frame only changes their APPEARANCE.
            if (changed || relaidOut) updateAppearance();
            return changed;
        }

        /**
         * Gives every visual run its appearance for this frame, and nothing else. This is the only
         * place karaoke colour is decided, and it is pure appearance: a colour, and for the one run
         * the sung boundary falls inside, a shader.
         *
         * <p>A run the boundary has passed is solid sung; a run it has not reached is solid muted;
         * the run containing it is drawn ONCE with a {@link LinearGradient} whose hard stop sits
         * exactly where the fill has reached. No run is ever subdivided, so no glyph is re-shaped
         * or re-rasterised as the boundary travels through it.
         */
        private void updateAppearance() {
            final int boundary = boundaryCluster();
            final float cut = boundaryX(boundary);
            final boolean rtl = boundary >= 0 && clusterRtl[boundary];
            for (int r = 0; r < runCount; r++) {
                final KaraokeSpan span = runSpans[r];
                if (runEnd[r] <= spanSungTo) {
                    span.setSolid(sungColor);                 // wholly sung
                } else if (runStart[r] >= spanWordTo) {
                    span.setSolid(mutedColor);                // not reached yet
                } else {
                    final LinearGradient gradient = runGradient(r, cut, rtl);
                    if (gradient != null) {
                        // The colour is the fallback the paint would use without a shader, so a
                        // device that somehow refused it shows a muted run rather than nothing.
                        span.set(mutedColor, gradient);
                    } else {
                        // No geometry at all to place a boundary with - the row has not been laid
                        // out yet. The next tick, by which time it has, puts the fill where the
                        // clock says. Until then the run reads as not yet reached.
                        span.setSolid(mutedColor);
                    }
                }
            }
        }

        /**
         * The grapheme the sung boundary is measured against.
         *
         * <p>Normally the one the fill is part-way through. When there is no such grapheme - nothing
         * of the word sung yet, the word complete, or no layout - the boundary sits at the leading
         * edge of the first grapheme that is NOT yet sung, so a run straddling that offset is still
         * divided by colour at the right place instead of being flooded with one of the two.
         *
         * <p>A grapheme with no ink of its own (a space) cannot carry an edge, so the search steps
         * to the next one that has, and failing that to the last one before it.
         */
        private int boundaryCluster() {
            if (frontCluster >= 0 && frontCluster < clusterGeometryCount
                    && clusterHasRect[frontCluster]) {
                return frontCluster;
            }
            final int limit = Math.min(clusterCount, clusterGeometryCount);
            for (int i = 0; i < limit; i++) {
                if (clusterStart[i] < spanSungTo) continue;
                if (clusterHasRect[i]) return i;
            }
            for (int i = limit - 1; i >= 0; i--) {
                if (clusterHasRect[i]) return i;
            }
            return -1;
        }

        /**
         * Layout x the sung boundary has reached, for the grapheme it is measured against: the
         * fill's own position inside a part-filled grapheme, and otherwise that grapheme's leading
         * edge - its left for an LTR run, its right for an RTL one.
         */
        private float boundaryX(int cluster) {
            if (cluster < 0) return Float.NaN;
            final float left = clusterLeft[cluster];
            final float right = clusterRight[cluster];
            if (cluster != frontCluster) {
                return clusterRtl[cluster] ? right : left;
            }
            final float width = right - left;
            if (width <= 0.01f) return clusterRtl[cluster] ? right : left;
            float shown = frontRevealed;
            if (shown < 0f) shown = 0f;
            if (shown > width) shown = width;
            // RTL runs are read from their right edge, so the fill travels leftwards through them.
            return clusterRtl[cluster] ? right - shown : left + shown;
        }

        /**
         * The gradient for the one run the sung boundary falls inside, or null when there is no
         * usable cut.
         *
         * <p>It spans the RUN's own visual extent, with two stops sharing the boundary's position,
         * so the transition is a hard edge and the run is still a single draw. The coordinates are
         * the row's own {@link Layout} coordinates, which is the space the paint's shader is
         * resolved in - {@link TextView#onDraw} translates the canvas by the padding before
         * {@link Layout#draw}, so the shader's matrix is the layout's. That has been measured, not
         * assumed. {@link Shader.TileMode#CLAMP} extends the end colours outwards, so ink that
         * overhangs the run's advance box is coloured by the side it belongs to.
         *
         * <p>For an LTR run the sung side is the left; for an RTL run it is the right, taken from
         * the direction the layout gave the grapheme the boundary is inside.
         */
        private LinearGradient runGradient(int run, float cut, boolean rtl) {
            if (Float.isNaN(cut)) return null;
            final float left = runLeft[run];
            final float right = runRight[run];
            final float width = right - left;
            if (width <= 0.01f) return null;
            float stop = (cut - left) / width;
            if (stop < 0f) stop = 0f;
            if (stop > 1f) stop = 1f;
            final int leading = rtl ? mutedColor : sungColor;
            final int trailing = rtl ? sungColor : mutedColor;
            // Soft physical feather: the boundary blends over ~7dp instead of snapping.
            // For LTR the feather is in the already-sung region (lo..stop); for RTL it is
            // in the unsung region (stop..hi), mirroring how the sung side is at the leading edge.
            final float featherFrac = Math.min(0.35f, dp(KARAOKE_FEATHER_DP) / width);
            final float lo, hi;
            if (rtl) {
                lo = stop;
                hi = Math.min(1f, stop + featherFrac);
            } else {
                lo = Math.max(0f, stop - featherFrac);
                hi = stop;
            }
            float safeLo = lo, safeHi = hi;
            if (safeHi - safeLo < 0.001f) {
                safeHi = safeLo + 0.001f;
                if (safeHi > 1f) { safeHi = 1f; safeLo = Math.max(0f, safeHi - 0.001f); }
            }
            return new LinearGradient(left, 0f, right, 0f,
                    new int[] {leading, leading, trailing, trailing},
                    new float[] {0f, safeLo, safeHi, 1f}, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // A new layout moves every grapheme, so the fill front has to be found again. Doing it
            // here rather than in onDraw keeps span changes out of the draw pass.
            if (karaokeActive && resolveColourBoundaries()) invalidate();
        }

        /** Returns the row to plain, uniformly coloured text. */
        void clearKaraoke() {
            if (!karaokeActive && requestedStart < 0 && requestedEnd < 0) return;
            karaokeActive = false;
            detachSpans();
            invalidate();
        }

        /**
         * Depth, as a real blur on the view's own render node. This is the platform's GPU blur -
         * one property on a RenderNode Android is already compositing - so a blurred row costs no
         * bitmap, no allocation and no per-frame work of ours. The radius is quantised to a half
         * pixel so a moving page sets the property only when it has visibly changed, and below
         * Android 12, where there is no such effect, depth is carried by opacity alone rather than
         * by anything expensive standing in for it.
         */
        void setDepthBlur(float radiusPx) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            final int quantized = radiusPx <= 0.25f ? 0 : Math.min(24, Math.round(radiusPx * 2f));
            if (quantized == appliedBlur) return;
            appliedBlur = quantized;
            setRenderEffect(quantized == 0 ? null
                    : RenderEffect.createBlurEffect(quantized / 2f, quantized / 2f, Shader.TileMode.DECAL));
        }

        /**
         * Splits this row's text into user-visible graphemes, once per text.
         *
         * <p>The segmentation is {@link KaraokeGeometry#clusterEnd}'s, which is the platform's
         * UAX #29 answer with an emoji backstop: a surrogate pair, a combining sequence, a ZWJ
         * emoji, an Amharic syllable and a flag are each one grapheme, and none of them can be cut
         * in half by the fill.
         */
        private void ensureClusters() {
            if (clusterText == karaokeText && clusterCount > 0) return;
            clusterText = karaokeText;
            clusterCount = 0;
            if (karaokeText == null) return;
            final int length = karaokeText.length();
            if (length == 0) return;
            ensureClusterCapacity(length + 1);
            int offset = 0;
            int count = 0;
            clusterStart[0] = 0;
            while (offset < length) {
                final int next = KaraokeGeometry.clusterEnd(karaokeText, offset + 1);
                offset = next <= offset ? offset + 1 : Math.min(next, length);
                clusterStart[++count] = offset;
            }
            clusterCount = count;
        }

        /**
         * Caches where each grapheme actually is, asking the row's own {@link Layout} rather than
         * measuring anything. Rebuilt only when the layout object or the text changes, so a line
         * being sung for several seconds is measured once.
         */
        private boolean ensureClusterGeometry() {
            ensureClusters();
            final Layout layout = getLayout();
            if (layout == clusterLayout && clusterGeometryText == clusterText) return false;
            clusterLayout = layout;
            clusterGeometryText = clusterText;
            clusterGeometryCount = 0;
            if (layout == null || clusterCount == 0 || karaokeText == null) return true;
            final CharSequence laid = layout.getText();
            if (laid == null || laid.length() != karaokeText.length()) return true;
            ensureClusterGeometryCapacity(clusterCount);
            for (int i = 0; i < clusterCount; i++) {
                pendingCluster = i;
                clusterHasRect[i] = false;
                KaraokeGeometry.forEachVisualRun(layout, clusterStart[i], clusterStart[i + 1], this);
            }
            pendingCluster = -1;
            clusterGeometryCount = clusterCount;
            rebuildRunSpans(layout);
            return true;
        }

        /**
         * Attaches exactly one span per visual run, covering the whole row.
         *
         * <p>A visual run is one bidi run of one visual line - the same division
         * {@link KaraokeGeometry#forEachVisualRun} makes, and the same one the platform itself
         * draws in: {@link Layout} draws each visual line separately and
         * {@link android.text.TextLine} each direction run separately. Putting the span boundaries
         * exactly there means karaoke never asks for a split the platform was not making anyway, so
         * it cannot change a glyph. Ranges are rebuilt only with the layout, never with the sweep.
         */
        private void rebuildRunSpans(Layout layout) {
            detachRunSpans();
            runCount = 0;
            if (karaokeText == null || layout == null) return;
            final int length = karaokeText.length();
            if (length == 0) return;
            for (int line = 0; line < layout.getLineCount(); line++) {
                final int from = layout.getLineStart(line);
                final int to = Math.min(length, layout.getLineEnd(line));
                if (to <= from) continue;
                int start = from;
                boolean rtl = layout.isRtlCharAt(start);
                for (int i = from + 1; i <= to; i++) {
                    final boolean next = i < to && layout.isRtlCharAt(i);
                    if (i < to && next == rtl) continue;
                    addRunSpan(layout, line, start, i);
                    start = i;
                    rtl = next;
                }
            }
        }

        private void addRunSpan(Layout layout, int line, int start, int end) {
            if (end <= start) return;
            ensureRunCapacity(runCount + 1);
            if (runSpans[runCount] == null) runSpans[runCount] = new KaraokeSpan();
            final float leading = KaraokeGeometry.edgeAt(layout, line, start, false);
            final float trailing = KaraokeGeometry.edgeAt(layout, line, end, true);
            runStart[runCount] = start;
            runEnd[runCount] = end;
            runLeft[runCount] = Math.min(leading, trailing);
            runRight[runCount] = Math.max(leading, trailing);
            karaokeText.setSpan(runSpans[runCount], start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            runCount++;
        }

        private void ensureRunCapacity(int size) {
            if (size <= runSpans.length) return;
            final int grown = Math.max(size, runSpans.length * 2);
            runSpans = java.util.Arrays.copyOf(runSpans, grown);
            runStart = java.util.Arrays.copyOf(runStart, grown);
            runEnd = java.util.Arrays.copyOf(runEnd, grown);
            runLeft = java.util.Arrays.copyOf(runLeft, grown);
            runRight = java.util.Arrays.copyOf(runRight, grown);
        }

        private void detachRunSpans() {
            if (karaokeText == null) return;
            for (int r = 0; r < runCount; r++) {
                if (runSpans[r] != null) karaokeText.removeSpan(runSpans[r]);
            }
        }

        /** True when a grapheme has no glyph to fill - a space, a tab, a line separator. */
        private boolean isBlankCluster(int index) {
            final CharSequence text = clusterText;
            if (text == null) return true;
            final int from = clusterStart[index];
            final int to = clusterStart[index + 1];
            for (int i = from; i < to; i++) {
                if (!Character.isWhitespace(text.charAt(i))) return false;
            }
            return true;
        }

        /** {@link KaraokeGeometry.RunSink}. Called only while the geometry is being rebuilt. */
        @Override
        public void addRun(float left, float right, float top, float bottom, boolean rightToLeft) {
            final int i = pendingCluster;
            if (i < 0 || i >= clusterHasRect.length) return;
            if (!clusterHasRect[i]) {
                clusterHasRect[i] = true;
                clusterLeft[i] = left;
                clusterRight[i] = right;
                clusterRtl[i] = rightToLeft;
                return;
            }
            // A grapheme cannot wrap or reorder, so this is only ever a defensive union. The run's
            // top and bottom are ignored: nothing here is drawn at a height of its own.
            if (left < clusterLeft[i]) clusterLeft[i] = left;
            if (right > clusterRight[i]) clusterRight[i] = right;
        }

        private void ensureClusterCapacity(int size) {
            if (size <= clusterStart.length) return;
            final int grown = Math.max(size, clusterStart.length * 2);
            clusterStart = java.util.Arrays.copyOf(clusterStart, grown);
        }

        private void ensureClusterGeometryCapacity(int size) {
            if (size <= clusterLeft.length) return;
            final int grown = Math.max(size, clusterLeft.length * 2);
            clusterLeft = java.util.Arrays.copyOf(clusterLeft, grown);
            clusterRight = java.util.Arrays.copyOf(clusterRight, grown);
            clusterRtl = java.util.Arrays.copyOf(clusterRtl, grown);
            clusterHasRect = java.util.Arrays.copyOf(clusterHasRect, grown);
        }

        private void detachSpans() {
            detachRunSpans();
            runCount = 0;
            wordStart = 0;
            wordEnd = 0;
            sweep = 0f;
            wordOwnedEnd = 0;
            requestedStart = -1;
            requestedEnd = -1;
            spanSungTo = -1;
            spanWordTo = -1;
            frontCluster = -1;
            frontRevealed = 0f;
            clusterLayout = null;
            clusterGeometryText = null;
            clusterGeometryCount = 0;
            // The spans are detached, but a recycled row reuses these objects, so no shader from
            // the line just released can reach the next line's paint.
            for (int r = 0; r < runSpans.length; r++) {
                if (runSpans[r] != null) runSpans[r].set(0, null);
            }
        }
    }

    /**
     * Appearance only, and deliberately not metric-affecting: re-colouring a range can never
     * re-measure or re-wrap the line it sits in.
     *
     * <p>It extends {@link CharacterStyle} and implements {@link UpdateAppearance}, which is the
     * platform's own contract for "this changes how the text looks and nothing about where it is".
     * Android does not re-measure or re-layout for such a span. {@link #updateDrawState} touches
     * exactly two properties of the paint - the colour and the shader - and NOTHING else: not the
     * typeface, the text size, fake-bold, scaleX, letter spacing, the baseline shift or the flags.
     * That is why the sweep cannot change a glyph's shape, width, weight, spacing or position.
     *
     * <p>The shader is always set, to null when this run is a flat colour. A {@link TextPaint} is
     * reused across the runs of a line, so a run that did not clear it would inherit the gradient
     * belonging to the run before it and paint its own text through the wrong boundary.
     */
    private static final class KaraokeSpan extends CharacterStyle implements UpdateAppearance {
        private int color;
        private Shader shader;

        /** A flat colour, with any shader from a previous frame explicitly dropped. */
        void setSolid(int value) {
            set(value, null);
        }

        void set(int value, Shader paintShader) {
            color = value;
            shader = paintShader;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setColor(color);
            // Unconditional, including the null: see the class comment. A shader left behind by
            // another run would repaint this run through that run's boundary.
            paint.setShader(shader);
        }
    }

    private class LyricsAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        LyricsAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            // Untimed lyrics have nothing to seek to, so they are not presented as tappable.
            if (currentLyrics == null || !currentLyrics.isSynced()) return false;
            int position = holder.getAdapterPosition();
            return position >= 0 && position < visibleLyrics.size() && !TextUtils.isEmpty(currentLyrics.lines.get(visibleLyrics.get(position)).text);
        }

        @Override
        public int getItemCount() {
            return visibleLyrics.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LyricsTextView textView = new LyricsTextView(context);
            textView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            textView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            textView.setPadding(dp(LYRICS_ROW_PADDING_H_DP), dp(LYRICS_ROW_PADDING_V_DP),
                    dp(LYRICS_ROW_PADDING_H_DP), dp(LYRICS_ROW_PADDING_V_DP));
            textView.setMinHeight(dp(LYRICS_ROW_MIN_HEIGHT_DP));
            textView.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            return new RecyclerListView.Holder(textView);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            LyricsTextView textView = (LyricsTextView) holder.itemView;
            int line = visibleLyrics.get(position);
            final boolean synced = currentLyrics.isSynced();
            final SyncedLyricsController.Line lyricLine = currentLyrics.lines.get(line);
            // Only a line the source actually timed inside is bound as spannable text; every other
            // row stays the plain string it has always been.
            textView.setLyricText(lyricLine.text, synced && lyricLine.segments != null);
            boolean stanzaSpace = !synced && TextUtils.isEmpty(lyricLine.text);
            // ONE typography for the whole large player. Normal lyrics, ordinary line-synced
            // lyrics and true karaoke are three capabilities of one page, not three designs: they
            // are set identically - same size, same weight, same spacing, same margins - and differ
            // only in what the source lets them do with it. Nothing below ever changes any of this
            // again, because a size or a weight is metric-affecting: switching one when a line or a
            // word becomes active would re-measure the row, possibly re-wrap it, and move every row
            // underneath it.
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, LYRICS_TEXT_SIZE_DP);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setLineSpacing(dp(LYRICS_LINE_SPACING_DP), 1f);
            textView.setMinHeight(dp(stanzaSpace ? LYRICS_ROW_STANZA_HEIGHT_DP : LYRICS_ROW_MIN_HEIGHT_DP));
            textView.setPadding(dp(LYRICS_ROW_PADDING_H_DP), dp(stanzaSpace ? 0 : LYRICS_ROW_PADDING_V_DP),
                    dp(LYRICS_ROW_PADDING_H_DP), dp(stanzaSpace ? 0 : LYRICS_ROW_PADDING_V_DP));
            // A recycled row must never arrive carrying the previous line's depth; the attach
            // callback re-derives it from the row's real position immediately afterwards.
            textView.setDepthBlur(0f);
            // Timed rows get their emphasis from applyLyricsDepth(), which runs on attach and on
            // every frame of the transition; binding it here as well would reintroduce the pop.
            if (synced) {
                textView.setLyricTextColor(getThemedColor(Theme.key_player_time));
            } else {
                textView.setLyricTextColor(getThemedColor(Theme.key_player_actionBarTitle));
                textView.setAlpha(1f);
                textView.setScaleX(1f);
                textView.setScaleY(1f);
            }
            textView.setBackground(stanzaSpace || !synced ? null : Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context context;
        private ArrayList<MessageObject> searchResult = new ArrayList<>();
        private String searchQuery;
        private Runnable searchRunnable;

        public ListAdapter(Context context) {
            this.context = context;
        }

        private boolean listViewIsVisible;
        public void setup() {
            listViewIsVisible = playlist.size() > 1;
            if (listViewIsVisible) {
                listView.setVisibility(View.VISIBLE);
                listView.setTranslationY(0);
            } else {
                listView.setVisibility(View.GONE);
                listView.setTranslationY(AndroidUtilities.displaySize.y);
            }
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            if ((playlist.size() > 1) != listViewIsVisible) {
                listViewIsVisible = playlist.size() > 1;
                if (listViewIsVisible) {
                    listView.setVisibility(View.VISIBLE);
                    listView.setTranslationY(AndroidUtilities.displaySize.y);
                    listView.animate()
                        .translationY(0)
                        .setUpdateListener(a -> containerView.invalidate())
                        .setDuration(420)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .start();
                } else {
                    listView.animate()
                        .translationY(AndroidUtilities.displaySize.y)
                        .setUpdateListener(a -> containerView.invalidate())
                        .setDuration(420)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .withEndAction(() -> listView.setVisibility(View.GONE))
                        .start();
                }
            }
            if (playlist.size() > 1) {
                playerLayout.setBackgroundColor(getThemedColor(Theme.key_player_background));
                playerShadow.setVisibility(View.VISIBLE);
                listView.setPadding(0, listView.getPaddingTop(), 0, dp(179 + 52));
            } else {
                playerLayout.setBackgroundColor(getThemedColor(Theme.key_player_background));
                playerShadow.setVisibility(View.VISIBLE);
                listView.setPadding(0, listView.getPaddingTop(), 0, 0);
            }
            if (showingLyrics) {
                listView.animate().cancel();
                listView.setTranslationY(0);
                listView.setVisibility(View.GONE);
                listView.setEnabled(false);
            }
            updateEmptyView();
        }

        @Override
        public int getItemCount() {
            if (searchWas) {
                return (padWithItem ? 1 : 0) + searchResult.size();
            }
            return playlist.size() > 1 ? (padWithItem ? 1 : 0) + playlist.size() : 0;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            if (padWithItem && holder.getAdapterPosition() == 0)
                return false;
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 1) {
                View paddingView = new View(context) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(
                            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.EXACTLY)
                        );
                    }
                };
                paddingView.setTag(RecyclerListView.TAG_NOT_SECTION);
                return new RecyclerListView.Holder(paddingView);
            } else {
                View view = new AudioPlayerCell(context, MediaController.getInstance().currentPlaylistIsGlobalSearch() ? AudioPlayerCell.VIEW_TYPE_GLOBAL_SEARCH : AudioPlayerCell.VIEW_TYPE_DEFAULT, resourcesProvider);
                return new RecyclerListView.Holder(view);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (padWithItem) {
                if (position == 0) {
                    final View paddingView = holder.itemView;
                    return;
                }
                position--;
            }
            final AudioPlayerCell cell = (AudioPlayerCell) holder.itemView;
            final MessageObject messageObject;
            final boolean needDivider;
            final View.OnTouchListener onReorderTouch;
            if (searchWas) {
                messageObject = searchResult.get(position);
                needDivider = position + 1 < searchResult.size();
            } else if (savedMusicList != null ? !SharedConfig.playOrderReversed : SharedConfig.playOrderReversed) {
                messageObject = playlist.get(position);
                needDivider = position + 1 < playlist.size();
            } else {
                messageObject = playlist.get(playlist.size() - position - 1);
                needDivider = (playlist.size() - position - 2) >= 0;
            }
            if (messageObject != null) {
                messageObject.setQuery(searchQuery);
            }
            if (isMyList()) {
                onReorderTouch = (v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(listView.getChildViewHolder(cell));
                    }
                    return false;
                };
            } else {
                onReorderTouch = null;
            }
            cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
            cell.setMessageObject(messageObject, isMyList(), isMyList() || noforwards || messageObject.getId() <= 0 ? null : btn -> showOptions(cell, messageObject), needDivider, onReorderTouch);
        }

        @Override
        public int getItemViewType(int i) {
            if (padWithItem && i == 0) {
                return 1;
            }
            return 0;
        }

        public void search(final String query) {
            if (searchRunnable != null) {
                Utilities.searchQueue.cancelRunnable(searchRunnable);
                searchRunnable = null;
            }
            if (query == null) {
                searchQuery = null;
                searchResult.clear();
                notifyDataSetChanged();
            } else {
                Utilities.searchQueue.postRunnable(searchRunnable = () -> {
                    searchRunnable = null;
                    processSearch(query);
                }, 300);
            }
        }

        private void processSearch(final String query) {
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<MessageObject> copy = new ArrayList<>(playlist);
                Utilities.searchQueue.postRunnable(() -> {
                    String search1 = query.trim().toLowerCase();
                    if (search1.length() == 0) {
                        updateSearchResults(new ArrayList<>(), query);
                        return;
                    }
                    String search2 = LocaleController.getInstance().getTranslitString(search1);
                    if (search1.equals(search2) || search2.length() == 0) {
                        search2 = null;
                    }
                    String[] search = new String[1 + (search2 != null ? 1 : 0)];
                    search[0] = search1;
                    if (search2 != null) {
                        search[1] = search2;
                    }

                    ArrayList<MessageObject> resultArray = new ArrayList<>();

                    for (int a = 0; a < copy.size(); a++) {
                        MessageObject messageObject = copy.get(a);
                        for (int b = 0; b < search.length; b++) {
                            String q = search[b];
                            String name = messageObject.getDocumentName();
                            if (name == null || name.length() == 0) {
                                continue;
                            }
                            name = name.toLowerCase();
                            if (name.contains(q)) {
                                resultArray.add(messageObject);
                                break;
                            }
                            TLRPC.Document document;
                            if (messageObject.type == MessageObject.TYPE_TEXT) {
                                document = messageObject.messageOwner.media.webpage.document;
                            } else {
                                document = messageObject.messageOwner.media.document;
                            }
                            boolean ok = false;
                            for (int c = 0; c < document.attributes.size(); c++) {
                                TLRPC.DocumentAttribute attribute = document.attributes.get(c);
                                if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                                    if (attribute.performer != null) {
                                        ok = attribute.performer.toLowerCase().contains(q);
                                    }
                                    if (!ok && attribute.title != null) {
                                        ok = attribute.title.toLowerCase().contains(q);
                                    }
                                    break;
                                }
                            }
                            if (ok) {
                                resultArray.add(messageObject);
                                break;
                            }
                        }
                    }

                    updateSearchResults(resultArray, query);
                });
            });
        }

        private void updateSearchResults(final ArrayList<MessageObject> documents, String query) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!searching) {
                    return;
                }
                searchWas = true;
                searchResult = documents;
                searchQuery = query;
                notifyDataSetChanged();
                layoutManager.scrollToPosition(0);
                emptySubtitleTextView.setText(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.NoAudioFoundPlayerInfo, query)));
            });
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        ThemeDescription.ThemeDescriptionDelegate delegate = () -> {
            EditTextBoldCursor editText = searchItem.getSearchField();
            editText.setCursorColor(getThemedColor(Theme.key_player_actionBarTitle));

            repeatButton.setIconColor(getThemedColor((Integer) repeatButton.getTag()));
            Theme.setSelectorDrawableColor(repeatButton.getBackground(), getThemedColor(Theme.key_listSelector), true);

            optionsButton.setIconColor(getThemedColor(Theme.key_player_button));
            Theme.setSelectorDrawableColor(optionsButton.getBackground(), getThemedColor(Theme.key_listSelector), true);

            progressView.setBackgroundColor(getThemedColor(Theme.key_player_progressBackground));
            progressView.setProgressColor(getThemedColor(Theme.key_player_progress));

            updateSubMenu();
            repeatButton.redrawPopup(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));

            optionsButton.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), false);
            optionsButton.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), true);
            optionsButton.redrawPopup(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
            if (lyricsAdapter != null) lyricsAdapter.notifyDataSetChanged();
            activeLyricsLine = Integer.MIN_VALUE;
            updateLyrics(false);
        };

//        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_dialogBackground));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, delegate, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBTITLECOLOR, null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_player_actionBarSelector));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_player_time));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inLoader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_outLoader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inLoaderSelected));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inMediaIcon));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inMediaIconSelected));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inAudioSelectedProgress));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{AudioPlayerCell.class}, null, null, null, Theme.key_chat_inAudioProgress));

        themeDescriptions.add(new ThemeDescription(containerView, 0, null, null, new Drawable[]{shadowDrawable}, null, Theme.key_dialogBackground));

        themeDescriptions.add(new ThemeDescription(progressView, 0, null, null, null, null, Theme.key_player_progressBackground));
        themeDescriptions.add(new ThemeDescription(progressView, 0, null, null, null, null, Theme.key_player_progress));
        themeDescriptions.add(new ThemeDescription(seekBarView, 0, null, null, null, null, Theme.key_player_progressBackground));
        themeDescriptions.add(new ThemeDescription(seekBarView, 0, null, null, null, null, Theme.key_player_progressCachedBackground));
        themeDescriptions.add(new ThemeDescription(seekBarView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_player_progress));

        themeDescriptions.add(new ThemeDescription(playbackSpeedButton, ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_inappPlayerPlayPause));
        themeDescriptions.add(new ThemeDescription(playbackSpeedButton, ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_inappPlayerClose));

        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_player_buttonActive));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_actionBarDefaultSubmenuItem));
        themeDescriptions.add(new ThemeDescription(repeatButton, 0, null, null, null, delegate, Theme.key_actionBarDefaultSubmenuBackground));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_actionBarDefaultSubmenuItem));
        themeDescriptions.add(new ThemeDescription(optionsButton, 0, null, null, null, delegate, Theme.key_actionBarDefaultSubmenuBackground));

        themeDescriptions.add(new ThemeDescription(prevButton, 0, null, new RLottieDrawable[]{prevButton.getAnimatedDrawable()}, "Triangle 3", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton, 0, null, new RLottieDrawable[]{prevButton.getAnimatedDrawable()}, "Triangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton, 0, null, new RLottieDrawable[]{prevButton.getAnimatedDrawable()}, "Rectangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(prevButton, ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(playButton, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(playButton, ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(nextButton, 0, null, new RLottieDrawable[]{nextButton.getAnimatedDrawable()}, "Triangle 3", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton, 0, null, new RLottieDrawable[]{nextButton.getAnimatedDrawable()}, "Triangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton, 0, null, new RLottieDrawable[]{nextButton.getAnimatedDrawable()}, "Rectangle 4", Theme.key_player_button));
        themeDescriptions.add(new ThemeDescription(nextButton, ThemeDescription.FLAG_IMAGECOLOR | ThemeDescription.FLAG_USEBACKGROUNDDRAWABLE, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(playerLayout, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_player_background));

        themeDescriptions.add(new ThemeDescription(playerShadow, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_dialogShadowLine));

        themeDescriptions.add(new ThemeDescription(emptyImageView, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_dialogEmptyImage));
        themeDescriptions.add(new ThemeDescription(emptyTitleTextView, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_dialogEmptyText));
        themeDescriptions.add(new ThemeDescription(emptySubtitleTextView, ThemeDescription.FLAG_IMAGECOLOR, null, null, null, null, Theme.key_dialogEmptyText));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_dialogScrollGlow));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(progressView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_emptyListPlaceholder));
        themeDescriptions.add(new ThemeDescription(progressView, ThemeDescription.FLAG_PROGRESSBAR, null, null, null, null, Theme.key_progressCircle));

        themeDescriptions.add(new ThemeDescription(durationTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(timeTextView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(titleTextView.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(titleTextView.getNextTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_actionBarTitle));
        themeDescriptions.add(new ThemeDescription(lyricsListView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_dialogScrollGlow));
        themeDescriptions.add(new ThemeDescription(authorTextView.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_time));
        themeDescriptions.add(new ThemeDescription(authorTextView.getNextTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_player_time));

        themeDescriptions.add(new ThemeDescription(containerView, 0, null, null, null, null, Theme.key_sheet_scrollUp));

        return themeDescriptions;
    }

    private void saveToProfile(MessageObject messageObject, boolean save, Runnable done, boolean triedFileRef) {
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return;
        }
        final long documentId = document.id;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = documentId;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference;
        if (req.id.file_reference == null) {
            req.id.file_reference = new byte[0];
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text)) {
                if (triedFileRef || messageObject.getId() < 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                            .showForError(err);
                    });
                    return;
                }
                if (messageObject.getDialogId() >= 0) {
                    final int msg_id = messageObject.getId();
                    final TLRPC.TL_messages_getMessages fileRefReq = new TLRPC.TL_messages_getMessages();
                    fileRefReq.id.add(msg_id);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                        if (res1 instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res1;
                            TLRPC.Message message = null;
                            for (int i = 0; i < r.messages.size(); ++i) {
                                if (r.messages.get(i).id == msg_id) {
                                    message = r.messages.get(i);
                                    break;
                                }
                            }
                            if (message != null) {
                                saveToProfile(new MessageObject(currentAccount, message, false, true), save, done, true);
                            } else {
                                AndroidUtilities.runOnUIThread(() -> {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .createErrorBulletin(LocaleController.formatString(R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND"))
                                        .show();
                                });
                            }
                        } else if (err1 != null) {
                            AndroidUtilities.runOnUIThread(() -> {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                    .showForError(err1);
                            });
                        }
                    });
                } else {
                    final int msg_id = messageObject.getId();
                    final TLRPC.TL_channels_getMessages fileRefReq = new TLRPC.TL_channels_getMessages();
                    fileRefReq.channel = MessagesController.getInstance(currentAccount).getInputChannel(-messageObject.getDialogId());
                    fileRefReq.id.add(msg_id);
                    ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                        if (res1 instanceof TLRPC.messages_Messages) {
                            final TLRPC.messages_Messages r = (TLRPC.messages_Messages) res1;
                            TLRPC.Message message = null;
                            for (int i = 0; i < r.messages.size(); ++i) {
                                if (r.messages.get(i).id == msg_id) {
                                    message = r.messages.get(i);
                                    break;
                                }
                            }
                            if (message != null) {
                                saveToProfile(new MessageObject(currentAccount, message, false, true), save, done, true);
                            } else {
                                AndroidUtilities.runOnUIThread(() -> {
                                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                        .createErrorBulletin(LocaleController.formatString(R.string.UnknownErrorCode, "CLIENT_MESSAGE_NOT_FOUND"))
                                        .show();
                                });
                            }
                        } else if (err1 != null) {
                            AndroidUtilities.runOnUIThread(() -> {
                                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                                    .showForError(err1);
                            });
                        }
                    });
                }
                return;
            } else if (err != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .showForError(err);
                });
            }

            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(currentAccount)
                    .getSavedMusicIds()
                    .update(documentId, save);
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                final TLRPC.UserFull userInfo = MessagesController.getInstance(currentAccount).getUserFull(selfId);
                if (userInfo != null) {
                    if (save) {
                        userInfo.flags2 |= TLObject.FLAG_21;
                        userInfo.saved_music = document;
                    } else if (userInfo.saved_music != null && userInfo.saved_music.id == documentId) {
                        userInfo.flags2 &=~ TLObject.FLAG_21;
                        userInfo.saved_music = null;
                    }
                    MessagesStorage.getInstance(currentAccount).updateUserInfo(userInfo, true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
                }
                if (done != null) {
                    done.run();
                }
            });
        });
    }

    private void showMenuOptions(View v) {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null) {
            return;
        }

        final ItemOptions o = ItemOptions.makeOptions(container, resourcesProvider, v, true);
        ItemOptions o2 = buildSaveOptions(o, messageObject);
        if (!isMyList()) {
            o.addIf(!noforwards, R.drawable.msg_stories_save, getString(R.string.AudioSaveTo), () -> o.openSwipeback(o2));
            if (!noforwards && o.getLast() != null)
                o.getLast().setRightIcon(R.drawable.msg_arrowright);
            o.addGap();
        }

        o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
            o.dismiss();
            onSubItemClick(1);
        });
        o.addIf(!noforwards, R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
            o.dismiss();
            onSubItemClick(2);
        });
        o.addIf(messageObject.getId() > 0, R.drawable.msg_message, getString(R.string.ShowInChat), () -> {
            o.dismiss();
            onSubItemClick(4);
        });
        SyncedLyricsController lyricsController = SyncedLyricsController.getInstance(currentAccount);
        SyncedLyricsController.Lyrics menuLyrics = lyricsController.getLyrics(messageObject);
        SyncedLyricsController.State lyricsState = lyricsController.getState(messageObject);
        boolean hasLyrics = !menuLyrics.lines.isEmpty();
        o.addIf(hasLyrics, R.drawable.outline_caption_24, getString(showingLyrics ? R.string.ShowPlaylist : R.string.ShowLyrics), () -> {
            o.dismiss();
            lyricsModeRequested = !showingLyrics;
            lyricsController.setLyricsModePreferred(lyricsModeRequested);
            setShowingLyrics(lyricsModeRequested, true);
        });
        o.add(R.drawable.msg_edit, getString(!menuLyrics.source.isEmpty() || lyricsState == SyncedLyricsController.State.LOADING || lyricsState == SyncedLyricsController.State.NOT_LOADED ? R.string.EditLyrics : R.string.AddLyrics), () -> {
            o.dismiss();
            dismiss();
            parentActivity.presentFragment(new SyncedLyricsEditorFragment(messageObject));
        });
        o.addIf(lyricsController.canRestoreEmbedded(messageObject), R.drawable.msg_retry, getString(R.string.RestoreEmbeddedLyrics), () -> {
            o.dismiss();
            lyricsController.restoreEmbedded(messageObject, success -> updateLyrics(true));
        });
        if (castAvailable) {
            castItem = o.add();
            castItem.setTextAndIcon(getString(R.string.VideoPlayerChromecast), R.drawable.menu_video_chromecast);
            castItem.setOnClickListener(v2 -> {
                o.dismiss();
                onSubItemClick(7);
            });
            AndroidUtilities.removeFromParent(castItemButton);
            castItem.addView(castItemButton, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            updateColors();
        }
        o.addIf(isMyList(), R.drawable.msg_delete, getString(R.string.ProfilePlaylistRemoveFromProfile), true, () -> {
            o.dismiss();
            onSubItemClick(7);
        });
        o.setTranslationY(dp(64));
        o.show();
    }

    private ItemOptions buildSaveOptions(ItemOptions o, MessageObject messageObject) {
        final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount).getSavedMusicIds();
        final TLRPC.Document document = messageObject.getDocument();
        final long documentId = document != null ? document.id : 0;

        final ItemOptions o2 = o.makeSwipeback();
        o2.add(R.drawable.ic_ab_back, getString(R.string.Back), o::closeSwipeback);
        o2.addGap();
        o2.addIf(!musicIds.ids.contains(documentId), R.drawable.left_status_profile, getString(R.string.AudioSaveToMyProfile), () -> {
            saveToProfile(messageObject, true, () -> {
                setVisibleInProfile(true);
                BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToMyProfileSaved))
                        .show();
                o.dismiss();
            }, false);
        });
        o2.add(R.drawable.msg_saved, getString(R.string.AudioSaveToSavedMessages), () -> {
            forward(messageObject, UserConfig.getInstance(currentAccount).getClientUserId());
            o.dismiss();

            BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                    .createSimpleBulletin(R.raw.saved_messages, getString(R.string.AudioSaveToSavedMessagesSaved))
                    .show();
        });
        o2.add(R.drawable.menu_download_round, getString(R.string.AudioSaveToMusicFolder), () -> {
            saveToMusic(messageObject);
            o.dismiss();
        });
        o2.addGap();
        o2.addText(getString(R.string.AudioSaveToInfo), 12, dp(200));
        return o2;
    }

    private void showOptions(AudioPlayerCell cell, MessageObject messageObject) {
        final ItemOptions o = ItemOptions.makeOptions(container, resourcesProvider, cell, true);

        if (isMyList()) {
            o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
                o.dismiss();
                forward(messageObject);
            });
            o.addIf(!noforwards, R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
                o.dismiss();
                share(messageObject);
            });
            o.add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> {
                saveToProfile(messageObject, false, () -> {
                    savedMusicList.remove(messageObject);
                    playlist.remove(messageObject);
                    listAdapter.notifyDataSetChanged();
                    o.dismiss();

                    setVisibleInProfile(false);
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.ic_delete, getString(R.string.AudioSaveToMyProfileUnsaved))
                        .show();
                }, false);
            });
        } else {
            ItemOptions o2 = buildSaveOptions(o, messageObject);

            o.addIf(!noforwards, R.drawable.msg_stories_save, getString(R.string.AudioSaveTo), () -> o.openSwipeback(o2));
            if (!noforwards && o.getLast() != null)
                o.getLast().setRightIcon(R.drawable.msg_arrowright);

            o.addGap();
            o.addIf(!noforwards, R.drawable.msg_forward, getString(R.string.Forward), () -> {
                o.dismiss();
                forward(messageObject);
            });
            o.addIf(!noforwards, R.drawable.msg_share, getString(R.string.ShareFile), () -> {
                o.dismiss();
                share(messageObject);
            });
            o.addIf(messageObject.getId() > 0, R.drawable.msg_view_file, getString(R.string.ShowInChat), () -> {
                if (UserConfig.selectedAccount != currentAccount) {
                    parentActivity.switchToAccount(currentAccount, true);
                }

                Bundle args = new Bundle();
                long did = messageObject.getDialogId();
                if (DialogObject.isEncryptedDialog(did)) {
                    args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    args.putLong("user_id", did);
                } else {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
                    if (chat != null && chat.migrated_to != null) {
                        args.putLong("migrated_to", did);
                        did = -chat.migrated_to.channel_id;
                    }
                    args.putLong("chat_id", -did);
                }
                args.putInt("message_id", messageObject.getId());
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
                parentActivity.presentFragment(new ChatActivity(args), false, false);
                dismiss();
            });
        }

        o.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        o.show();
    }

    private boolean visibleInProfile;

    private void setVisibleInProfile(boolean visible) {
        visibleInProfile = visible;
        applyProfileButtonsVisibility(true);
    }

    private void saveToMusic(MessageObject messageObject) {
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) && parentActivity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            parentActivity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        if (TextUtils.isEmpty(fileName)) {
            fileName = messageObject.getFileName();
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && path.length() > 0) {
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (path == null || path.length() == 0) {
            path = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
        }
        MediaController.saveFile(path, parentActivity, 3, fileName, messageObject.getDocument() != null ? messageObject.getDocument().mime_type : "", uri -> BulletinFactory.of((FrameLayout) containerView, resourcesProvider).createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
    }

    private void share(MessageObject messageObject) {
        try {
            File f = null;
            boolean isVideo = false;

            if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
                f = new File(messageObject.messageOwner.attachPath);
                if (!f.exists()) {
                    f = null;
                }
            }
            if (f == null) {
                f = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            }

            if (f.exists()) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(messageObject.getMimeType());
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", f));
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignore) {
                        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                    }
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                }

                parentActivity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.AppName));
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.setMessage(LocaleController.getString(R.string.PleaseDownload));
                builder.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void show() {
        super.show();
        instance = this;
    }

    private void forward(MessageObject messageObject, long dialogId) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }
        if (fmessages != null) {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, dialogId, false, false, true, 0, 0);
        } else {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, dialogId, null, null, null, null, null, null, true, 0, 0, 0, savedMusicList, null, false, false));
        }
        final BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment != null) {
            BulletinFactory.of(lastFragment)
                .createSimpleBulletin(
                    R.raw.forward,
                    dialogId == UserConfig.getInstance(currentAccount).getClientUserId() ?
                        LocaleController.getString(R.string.FwdMessageToSavedMessages) :
                    dialogId > 0 ?
                        LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(dialogId)) :
                        LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(dialogId))
                )
                .show();
        }
    }

    private void forward(MessageObject messageObject) {
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        DialogsActivity fragment = new DialogsActivity(args);
        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }
        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.size() > 1 || dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || message != null || fmessages == null) {
                for (int a = 0; a < dids.size(); a++) {
                    long did = dids.get(a).dialogId;
                    if (message != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false));
                    }
                    if (fmessages != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, did, false, false, true, 0, 0);
                    } else {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, did, null, null, null, null, null, null, notify, scheduleDate, 0, 0, savedMusicList, null, false, false));
                    }
                }
                fragment1.finishFragment();
                final BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    BulletinFactory.of(lastFragment)
                        .createSimpleBulletin(
                            R.raw.forward,
                            dids.size() == 1 && dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId() ?
                                LocaleController.getString(R.string.FwdMessageToSavedMessages) :
                            dids.size() == 1 && dids.get(0).dialogId > 0 ?
                                LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(dids.get(0).dialogId)) :
                            dids.size() == 1 && dids.get(0).dialogId < 0 ?
                                LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(dids.get(0).dialogId)) :
                            LocaleController.formatPluralStringComma("FwdMessageToManyChats", dids.size())
                        )
                        .show();
                }
            } else {
                MessagesStorage.TopicKey topicKey = dids.get(0);
                long did = topicKey.dialogId;
                Bundle args1 = new Bundle();
                args1.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    args1.putLong("user_id", did);
                } else {
                    args1.putLong("chat_id", -did);
                }
                ChatActivity chatActivity = new ChatActivity(args1);
                if (topicKey.topicId != 0) {
                    ForumUtilities.applyTopic(chatActivity, topicKey);
                }
                if (parentActivity.presentFragment(chatActivity, true, false)) {
                    chatActivity.showFieldPanelForForward(true, fmessages);
                    if (topicKey.topicId != 0) {
                        fragment1.removeSelfFromStack();
                    }
                } else {
                    fragment1.finishFragment();
                }
            }
            return true;
        });
        parentActivity.presentFragment(fragment);
        dismiss();
    }

    private static abstract class CoverContainer extends FrameLayout {

        private final BackupImageView[] imageViews = new BackupImageView[2];

        private int activeIndex;
        private AnimatorSet animatorSet;

        public CoverContainer(@NonNull Context context) {
            super(context);
            for (int i = 0; i < 2; i++) {
                imageViews[i] = new BackupImageView(context);
                final int index = i;
                imageViews[i].getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
                    if (index == activeIndex) {
                        onImageUpdated(imageReceiver);
                    }
                });
                imageViews[i].setRoundRadius(dp(4));
                if (i == 1) {
                    imageViews[i].setVisibility(GONE);
                }
                addView(imageViews[i], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
        }

        public final void switchImageViews() {
            if (animatorSet != null) {
                animatorSet.cancel();
            }
            animatorSet = new AnimatorSet();
            activeIndex = activeIndex == 0 ? 1 : 0;

            final BackupImageView prevImageView = imageViews[activeIndex == 0 ? 1 : 0];
            final BackupImageView currImageView = imageViews[activeIndex];

            final boolean hasBitmapImage = prevImageView.getImageReceiver().hasBitmapImage();

            currImageView.setAlpha(hasBitmapImage ? 1f : 0f);
            currImageView.setScaleX(0.8f);
            currImageView.setScaleY(0.8f);
            currImageView.setVisibility(VISIBLE);

            if (hasBitmapImage) {
                prevImageView.bringToFront();
            } else {
                prevImageView.setVisibility(GONE);
                prevImageView.setImageDrawable(null);
            }

            final ValueAnimator expandAnimator = ValueAnimator.ofFloat(0.8f, 1f);
            expandAnimator.setDuration(125);
            expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            expandAnimator.addUpdateListener(a -> {
                float animatedValue = (float) a.getAnimatedValue();
                currImageView.setScaleX(animatedValue);
                currImageView.setScaleY(animatedValue);
                if (!hasBitmapImage) {
                    currImageView.setAlpha(a.getAnimatedFraction());
                }
            });

            if (hasBitmapImage) {
                final ValueAnimator collapseAnimator = ValueAnimator.ofFloat(prevImageView.getScaleX(), 0.8f);
                collapseAnimator.setDuration(125);
                collapseAnimator.setInterpolator(CubicBezierInterpolator.EASE_IN);
                collapseAnimator.addUpdateListener(a -> {
                    float animatedValue = (float) a.getAnimatedValue();
                    prevImageView.setScaleX(animatedValue);
                    prevImageView.setScaleY(animatedValue);
                    final float fraction = a.getAnimatedFraction();
                    if (fraction > 0.25f && !currImageView.getImageReceiver().hasBitmapImage()) {
                        prevImageView.setAlpha(1f - (fraction - 0.25f) * (1f / 0.75f));
                    }
                });
                collapseAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        prevImageView.setVisibility(GONE);
                        prevImageView.setImageDrawable(null);
                        prevImageView.setAlpha(1f);
                    }
                });

                animatorSet.playSequentially(collapseAnimator, expandAnimator);
            } else {
                animatorSet.play(expandAnimator);
            }

            animatorSet.start();
        }

        public final BackupImageView getImageView() {
            return imageViews[activeIndex];
        }

        public final BackupImageView getNextImageView() {
            return imageViews[activeIndex == 0 ? 1 : 0];
        }

        public final ImageReceiver getImageReceiver() {
            return getImageView().getImageReceiver();
        }

        protected abstract void onImageUpdated(ImageReceiver imageReceiver);
    }

    public abstract static class ClippingTextViewSwitcher extends FrameLayout {

        private final TextView[] textViews = new TextView[2];
        private final float[] clipProgress = new float[]{0f, 0.75f};
        private final int gradientSize = dp(24);

        private final Matrix gradientMatrix;
        private final Paint gradientPaint;
        private final Paint erasePaint;

        private int activeIndex;
        private AnimatorSet animatorSet;
        private LinearGradient gradientShader;
        private int stableOffest = -1;
        private final RectF rectF = new RectF();
        private int rightPadding;
        private boolean verticalTransition;
        private int verticalTransitionDuration = 440;

        public ClippingTextViewSwitcher(@NonNull Context context) {
            super(context);
            for (int i = 0; i < 2; i++) {
                textViews[i] = createTextView();
                if (i == 1) {
                    textViews[i].setAlpha(0f);
                    textViews[i].setVisibility(GONE);
                }
                addView(textViews[i], LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
            }
            gradientMatrix = new Matrix();
            gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            gradientShader = new LinearGradient(gradientSize, 0, 0, 0, 0, 0xFF000000, Shader.TileMode.CLAMP);
            gradientPaint.setShader(gradientShader);
        }

        private boolean isCenter;

        public void setIsCenter() {
            isCenter = true;
        }

        public void setVerticalTransition(boolean verticalTransition) {
            this.verticalTransition = verticalTransition;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (isCenter) {
                for (int a = 0; a < textViews.length; a++) {
                    View v = textViews[a];
                    if (v == null) continue;
                    if (v.getMeasuredWidth() < getMeasuredWidth()) {
                        int l = (getMeasuredWidth() - v.getMeasuredWidth()) / 2;
                        v.layout(l, 0, l + v.getMeasuredWidth(), v.getMeasuredHeight());
                    }
                }
            }
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            final int index = child == textViews[0] ? 0 : 1;
            final boolean result;
            boolean hasStableRect = false;

            if (isCenter) {
                stableOffest = -1;
            }

            if (stableOffest > 0) {
                for (TextView tv : textViews) {
                    if (tv instanceof MarqueeTextView && ((MarqueeTextView) tv).isNeedMarquee()) {
                        stableOffest = -1;
                        break;
                    }
                }
            }

            if (stableOffest > 0 && textViews[activeIndex].getAlpha() != 1f && textViews[activeIndex].getLayout() != null) {
                float x1 = textViews[activeIndex].getLayout().getPrimaryHorizontal(0);
                float x2 = textViews[activeIndex].getLayout().getPrimaryHorizontal(stableOffest);
                hasStableRect = true;
                if (x1 == x2) {
                    hasStableRect = false;
                } else if (x2 > x1) {
                    rectF.set(x1, 0, x2, getMeasuredHeight());
                } else {
                    rectF.set(x2, 0, x1, getMeasuredHeight());
                }

                if (hasStableRect && index == activeIndex) {
                    canvas.save();
                    canvas.clipRect(rectF);
                    textViews[0].draw(canvas);
                    canvas.restore();
                }
            }
            if (clipProgress[index] > 0f || hasStableRect) {
                final int width = Math.min(child.getWidth(), getWidth()); // - rightPadding;
                final int height = Math.min(child.getHeight(), getHeight());
                final int saveCount = canvas.saveLayer(0, 0, width, height, null, Canvas.ALL_SAVE_FLAG);
                result = super.drawChild(canvas, child, drawingTime);
                final float gradientStart = width * (1f - clipProgress[index]);
                final float gradientEnd = gradientStart + gradientSize;
                gradientMatrix.setTranslate(gradientStart, 0);
                gradientShader.setLocalMatrix(gradientMatrix);
                canvas.drawRect(gradientStart, 0, gradientEnd, height, gradientPaint);
                if (width > gradientEnd) {
                    canvas.drawRect(gradientEnd, 0, width, height, erasePaint);
                }
                if (hasStableRect) {
                    canvas.drawRect(rectF, erasePaint);
                }
                canvas.restoreToCount(saveCount);
            } else {
                result = super.drawChild(canvas, child, drawingTime);
            }
            return result;
        }

        public void setText(CharSequence text) {
            setText(text, true);
        }

        public void setText(CharSequence text, boolean animated, int duration) {
            verticalTransitionDuration = Math.max(80, duration);
            setText(text, animated);
        }

        public void setText(CharSequence text, boolean animated) {
            final CharSequence currentText = textViews[activeIndex].getText();

            if (TextUtils.isEmpty(currentText) || !animated) {
                if (animatorSet != null) {
                    animatorSet.cancel();
                    animatorSet = null;
                }
                textViews[activeIndex].setText(text);
                if (verticalTransition) settleVerticalState(activeIndex);
                return;
            } else if (TextUtils.equals(text, currentText)) {
                return;
            }

            if (verticalTransition) {
                animateVerticalText(text);
                return;
            }

            stableOffest = 0;
            int n = Math.min(text.length(), currentText.length());
            for (int i = 0; i < n; i++) {
                if (text.charAt(i) != currentText.charAt(i)) {
                    break;
                }
                stableOffest++;
            }
            if (stableOffest <= 3) {
                stableOffest = -1;
            }

            final int index = activeIndex == 0 ? 1 : 0;
            final int prevIndex = activeIndex;
            activeIndex = index;

            if (animatorSet != null) {
                animatorSet.cancel();
            }
            animatorSet = new AnimatorSet();
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    textViews[prevIndex].setVisibility(GONE);
                }
            });

            textViews[index].setText(text);
            textViews[index].bringToFront();
            textViews[index].setVisibility(VISIBLE);

            final int duration = 300;

            final ValueAnimator collapseAnimator = ValueAnimator.ofFloat(clipProgress[prevIndex], 0.75f);
            collapseAnimator.setDuration(duration / 3 * 2); // 0.66
            collapseAnimator.addUpdateListener(a -> {
                clipProgress[prevIndex] = (float) a.getAnimatedValue();
                invalidate();
            });

            final ValueAnimator expandAnimator = ValueAnimator.ofFloat(clipProgress[index], 0f);
            expandAnimator.setStartDelay(duration / 3); // 0.33
            expandAnimator.setDuration(duration / 3 * 2); // 0.66
            expandAnimator.addUpdateListener(a -> {
                clipProgress[index] = (float) a.getAnimatedValue();
                invalidate();
            });

            final ObjectAnimator fadeOutAnimator = ObjectAnimator.ofFloat(textViews[prevIndex], View.ALPHA, 0f);
            fadeOutAnimator.setStartDelay(duration / 4); // 0.25
            fadeOutAnimator.setDuration(duration / 2); // 0.5

            final ObjectAnimator fadeInAnimator = ObjectAnimator.ofFloat(textViews[index], View.ALPHA, 1f);
            fadeInAnimator.setStartDelay(duration / 4); // 0.25
            fadeInAnimator.setDuration(duration / 2); // 0.5

            animatorSet.playTogether(collapseAnimator, expandAnimator, fadeOutAnimator, fadeInAnimator);
            animatorSet.start();
        }

        private void animateVerticalText(CharSequence text) {
            final int previous = activeIndex;
            final int next = previous == 0 ? 1 : 0;
            if (animatorSet != null) animatorSet.cancel();
            stableOffest = -1;
            clipProgress[0] = clipProgress[1] = 0f;
            textViews[previous].setVisibility(VISIBLE);
            textViews[previous].setAlpha(1f);
            textViews[previous].setTranslationY(0f);
            textViews[next].setText(text);
            textViews[next].setVisibility(VISIBLE);
            textViews[next].setAlpha(0f);
            textViews[next].setTranslationY(dp(10));
            textViews[next].bringToFront();
            activeIndex = next;

            int duration = verticalTransitionDuration;
            ObjectAnimator moveOut = ObjectAnimator.ofFloat(textViews[previous], View.TRANSLATION_Y, -dp(10));
            ObjectAnimator moveIn = ObjectAnimator.ofFloat(textViews[next], View.TRANSLATION_Y, 0f);
            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(textViews[previous], View.ALPHA, 0f);
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(textViews[next], View.ALPHA, 1f);
            moveOut.setDuration(duration);
            moveIn.setDuration(duration);
            fadeIn.setDuration(duration);
            fadeOut.setStartDelay(duration / 3L);
            fadeOut.setDuration(duration - duration / 3L);
            animatorSet = new AnimatorSet();
            animatorSet.playTogether(moveOut, moveIn, fadeOut, fadeIn);
            animatorSet.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (animatorSet != animation) return;
                    settleVerticalState(next);
                    animatorSet = null;
                }
            });
            animatorSet.start();
        }

        private void settleVerticalState(int settledIndex) {
            stableOffest = -1;
            clipProgress[0] = clipProgress[1] = 0f;
            for (int i = 0; i < textViews.length; i++) {
                textViews[i].setTranslationY(0f);
                textViews[i].setAlpha(i == settledIndex ? 1f : 0f);
                textViews[i].setVisibility(i == settledIndex ? VISIBLE : GONE);
            }
            invalidate();
        }

        public TextView getTextView() {
            return textViews[activeIndex];
        }

        public TextView getNextTextView() {
            return textViews[activeIndex == 0 ? 1 : 0];
        }

        public int getCustomPaddingRight() {
            return rightPadding;
        }

        public void setCustomPaddingRight(int padding) {
            rightPadding = padding;
            for (TextView tv : textViews) {
                if (tv instanceof MarqueeTextView) {
                    ((MarqueeTextView) tv).setCustomPaddingRight(padding);
                }
            }
            invalidate();
        }

        protected abstract TextView createTextView();
    }

    @Override
    protected boolean isTouchOutside(float x, float y) {
        if (fullscreenLyrics) return false;
        if (topBulletinContainer != null && topBulletinContainer.getChildCount() > 0) {
            View bulletinLayout = topBulletinContainer.getChildAt(0);
            if (
                    y >= topBulletinContainer.getY() + bulletinLayout.getY() &&
                            y <= topBulletinContainer.getY() + bulletinLayout.getY() + bulletinLayout.getHeight() &&
                            x >= topBulletinContainer.getX() + bulletinLayout.getX() &&
                            x <= topBulletinContainer.getX() + bulletinLayout.getX() + bulletinLayout.getWidth()
            )
                return false;
        }
        return y < containerView.getTop() + (shadowDrawable != null ? shadowDrawable.getBounds().top : 0) || x < containerView.getLeft() || x > containerView.getRight();
    }

    private ValueAnimator rightPaddingAnimator;

    private void setCustomPaddingRight(int padding, boolean animated) {
        if (rightPaddingAnimator != null) {
            rightPaddingAnimator.cancel();
            rightPaddingAnimator = null;
        }
        if (titleTextView.getCustomPaddingRight() == padding) {
            return;
        }

        if (!animated) {
            titleTextView.setCustomPaddingRight(padding);
            authorTextView.setCustomPaddingRight(padding);
            return;
        }

        rightPaddingAnimator = ValueAnimator.ofInt(titleTextView.getCustomPaddingRight(), padding);
        if (padding == 0) {
            rightPaddingAnimator.setStartDelay(200L);
            rightPaddingAnimator.setDuration(100L);
        } else {
            rightPaddingAnimator.setDuration(200L);
        }
        rightPaddingAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        rightPaddingAnimator.addUpdateListener(animation -> {
            titleTextView.setCustomPaddingRight((int) animation.getAnimatedValue());
            authorTextView.setCustomPaddingRight((int) animation.getAnimatedValue());
        });
        rightPaddingAnimator.start();
    }
}
