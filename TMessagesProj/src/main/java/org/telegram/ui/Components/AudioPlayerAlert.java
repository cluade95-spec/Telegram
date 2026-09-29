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
import android.graphics.BlurMaskFilter;
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
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.UpdateAppearance;
import android.util.FloatProperty;
import android.util.Property;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.Choreographer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** A row in visibleLyrics that holds the instrumental-gap dots rather than a line. */
    private static final int LYRICS_ROW_INTERLUDE = -1;
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
        // The springs rode along with the user's scroll, so they already describe where every
        // row is drawn: the follow re-aims from there.
        lyricsFollowRow = RecyclerView.NO_POSITION;
        scheduleLyricsFrame();
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
            // The edge fade is the list's own fading edge: an alpha mask over whatever is drawn,
            // at the list's real top and bottom. It replaces an overlay that painted the theme's
            // player colour over the edges, which does not match the sheet's actual background,
            // so its gradients showed as bands with a visible line where they ended. The list has
            // large paddings and does not clip to them, so the fade is placed at the view's
            // edges, not the padding's, and is always at full strength.
            @Override
            protected boolean isPaddingOffsetRequired() {
                return true;
            }

            @Override
            protected int getTopPaddingOffset() {
                return -getPaddingTop();
            }

            @Override
            protected int getBottomPaddingOffset() {
                return getPaddingBottom();
            }

            @Override
            protected float getTopFadingEdgeStrength() {
                return 1f;
            }

            @Override
            protected float getBottomFadingEdgeStrength() {
                return 1f;
            }

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
        lyricsListView.setVerticalFadingEdgeEnabled(true);
        lyricsListView.setFadingEdgeLength(dp(LyricsTuning.EDGE_FADE_DP));
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
                markLyricsSeek(lyricsNow());
                lyricsUserScrolling = false;
                AndroidUtilities.cancelRunOnUIThread(resumeLyricsFollow);
                updateLyrics(false);
            }
        });
        FrameLayout.LayoutParams lyricsParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT);
        lyricsParams.topMargin = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        lyricsParams.bottomMargin = dp(179 + (!isMyList() && !noforwards ? 52 : 0));
        containerView.addView(lyricsListView, lyricsParams);


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
                    AndroidUtilities.runOnUIThread(resumeLyricsFollow, LyricsTuning.MANUAL_SCROLL_RESUME_MS);
                } else if (newState == RecyclerView.SCROLL_STATE_SETTLING && !lyricsUserDragging) {
                    // Programmatic smoothScrollBy() also settles; it must not suspend following.
                    lyricsUserScrolling = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                lyricsScrollY += dy;
                // The user's finger or fling moves every row with the list: the springs move with
                // it, so a stagger still settling keeps settling instead of jumping.
                if (!lyricsApplyingMotion && lyricsUserScrolling && dy != 0) {
                    for (LyricsSpring spring : lyricsRowScroll) spring.shift(dy);
                }
                // Our own frame paints every row right after it scrolls; anything else (the user's
                // finger, a fling, a layout) is painted here.
                if (!lyricsApplyingMotion) {
                    updateLyricsDepth();
                    scheduleLyricsFrame();
                }
            }
        });
        lyricsListView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(@NonNull View view) {
                // The row was already painted by LyricsAdapter.onViewAttachedToWindow; this only
                // wakes the frame loop so the springs carry on from it.
                scheduleLyricsFrame();
            }
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
                // Only wake the frame loop. Pause and resume are handled by the clock (it eases
                // into the stop and out of it) and by the scale springs; the follow has nothing to
                // re-aim. It used to be re-aimed here on the slow spring, which cut every stagger
                // in flight short and wobbled the list at each pause and resume.
                scheduleLyricsFrame();
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
        if (lyricsViewportFade != null) {
            FrameLayout.LayoutParams fadeParams = (FrameLayout.LayoutParams) lyricsViewportFade.getLayoutParams();
            if (fadeParams.topMargin != top) {
                fadeParams.topMargin = top;
                lyricsViewportFade.setLayoutParams(fadeParams);
            }
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
            cancelLyricsFrame();
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
            buildLyricsDisplayLines();
            buildLyricsInterludes();
            indexLyricsDocument();
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
        // Everything that moves with the clock - the active line, the follow, the word state -
        // runs on the lyrics frame callback, from a dead-reckoned position (runLyricsFrame).
        scheduleLyricsFrame();
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
        // Fullscreen top chrome is the Back arrow alone, floating on the player's own background.
        // The action bar's panel (the dialog colour, not the player's) and its drop shadow drew
        // a flat grey band with a shadow line across the top of the lyrics; Apple Music has no
        // bar there.
        actionBar.animate().alpha(fullscreen ? 1f : preFullscreenActionBarAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        actionBarBackground.animate().alpha(fullscreen ? 0f : preFullscreenActionBarBackgroundAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        actionBarShadow.animate().alpha(fullscreen ? 0f : preFullscreenActionBarShadowAlpha).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
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
        if (lyricsViewportFade != null) {
            FrameLayout.LayoutParams fadeParams = (FrameLayout.LayoutParams) lyricsViewportFade.getLayoutParams();
            fadeParams.bottomMargin = dp(height);
            lyricsViewportFade.setLayoutParams(fadeParams);
        }
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

    /** Vertical position inside the viewport that the centre of the active synced line settles on. */
    private int getLyricsAnchorY() {
        return Math.round(lyricsListView.getHeight() * LyricsTuning.ANCHOR_FRACTION);
    }

    // ---------------------------------------------------------------------------------------
    // Large-player synced motion. One Choreographer frame callback drives all of it:
    //
    //   1. the clock: the player's position through a critically damped filter, so it advances
    //      evenly at any refresh rate, eases into a pause and out of a resume, and rests exactly
    //      on the player's position (LyricsTuning.CLOCK_*); plus a seek detector (SEEK_*);
    //   2. the logical active line, which changes exactly at its timestamp and owns brightness
    //      and scale (a spring per row each, so an interrupted change keeps its speed);
    //   3. the follow: every row has its own position spring. The row being followed drives the
    //      list's real scroll; every other row's lead or lag against it is its translationY, which
    //      is what the stagger is made of. The list may start moving up to PRE_ROLL_MAX_MS before
    //      the next timestamp; brightness never does.
    //
    // Springs are indexed by adapter position, never by View, so recycling cannot mix them up.
    // Values in LyricsSpring are in the list's own scroll pixels (lyricsScrollY's units).
    // ---------------------------------------------------------------------------------------

    private static final int LYRICS_AIM_NONE = 0;
    /** Re-aim the followed row without stagger (resize, fullscreen, mode change). */
    private static final int LYRICS_AIM_SOFT = 1;
    /** Put the followed row on the anchor in this frame, with no motion (first show). */
    private static final int LYRICS_AIM_SNAP = 2;
    /** The followed row was placed just off the viewport; spring it in on the slow spring. */
    private static final int LYRICS_AIM_FAR = 3;

    private final Choreographer.FrameCallback lyricsFrameCallback = this::onLyricsFrame;
    private boolean lyricsFrameScheduled;
    private boolean lyricsInFrame;
    private long lyricsFrameNanos;

    /** Latest time handed out by lyricsNow(); it never goes backwards. */
    private long lyricsLastNanos;

    // Clock.
    private long lyricsRawPositionMs = -1;
    private long lyricsRawNanos;
    /** Paused state the clock last saw, so a flip restarts the wall-time measurements. */
    private boolean lyricsClockPaused;
    /** The drawn clock (ms), its speed (ms per ms), and when it was last advanced (0 = never). */
    private double lyricsClock;
    private double lyricsClockVelocity;
    private long lyricsClockNanos;
    /** True once a paused clock rests exactly on the player's position. */
    private boolean lyricsClockSettled = true;
    private boolean lyricsSeekPending;
    /** When the pending seek was seen; it only shapes a retarget within SEEK_WINDOW_MS of it. */
    private long lyricsSeekNanos;
    /** End of the last sung word of the document, or MAX_VALUE when the source states none. */
    private long lyricsEndMs = Long.MAX_VALUE;
    private boolean lyricsEndOfSong;
    /** Row of every document line, or NO_POSITION; rebuilt with visibleLyrics. */
    private int[] lyricsLineToRow = new int[0];
    /** Every document line as displayed: background vocals moved to a second, smaller line. */
    private SyncedLyricsController.Line[] lyricsDisplayLines = new SyncedLyricsController.Line[0];
    /**
     * Per document line: when its singing ends if that is after the next line starts (background
     * vocals sung over the next line), else MIN_VALUE.
     */
    private long[] lyricsLineHoldEnd = new long[0];
    /**
     * Instrumental gaps that show the dots: each has a row of its own (LYRICS_ROW_INTERLUDE in
     * visibleLyrics) just before the line that ends it, and spans [start, end) of the song.
     */
    private int[] lyricsInterludeRows = new int[0];
    private long[] lyricsInterludeStart = new long[0];
    private long[] lyricsInterludeEnd = new long[0];
    private boolean[] lyricsInterludeIntro = new boolean[0];
    /** Per document line: when its singing ends if a gap with dots follows it, else MAX_VALUE. */
    private long[] lyricsLineSingingEnd = new long[0];
    /** The dots row the position is inside now, or NO_POSITION. */
    private int lyricsInterludeRow = RecyclerView.NO_POSITION;

    // Follow.
    private int lyricsScrollY;
    private boolean lyricsApplyingMotion;
    private int lyricsFollowRow = RecyclerView.NO_POSITION;
    private int lyricsAim = LYRICS_AIM_NONE;
    private LyricsSpring[] lyricsRowScroll = new LyricsSpring[0];
    /** The last re-aim of the list: when, its spring, and each row's stagger delay. */
    private long lyricsStepNanos;
    private float lyricsStepStiffness;
    private float lyricsStepDamping;
    private long[] lyricsRowStepDelay = new long[0];

    // Active line: brightness spring (0..100) and scale spring (percent), per row.
    private int lyricsFocusRow = RecyclerView.NO_POSITION;
    /**
     * The previous line's row while its background vocals are still being sung over the start of
     * this one, or NO_POSITION. Like Apple Music, it stays lit and keeps its word timing until
     * they finish, alongside the new line.
     */
    private int lyricsHeldRow = RecyclerView.NO_POSITION;
    /**
     * The row the list is bringing to the anchor (the pre-roll included), or NO_POSITION. Size
     * and blur follow it rather than the timestamp, so they change on the scroll's own spring
     * and settle with it.
     */
    private int lyricsStageRow = RecyclerView.NO_POSITION;
    private LyricsSpring[] lyricsRowFocus = new LyricsSpring[0];
    /**
     * Line-synced brightness, per row: a smoothstep from lyricsFadeFrom to lyricsFadeTo over
     * lyricsFadeDur, starting at lyricsFadeStart (nanos).
     */
    private float[] lyricsFadeFrom = new float[0];
    private float[] lyricsFadeTo = new float[0];
    private long[] lyricsFadeStart = new long[0];
    private long[] lyricsFadeDur = new long[0];
    private LyricsSpring[] lyricsRowScale = new LyricsSpring[0];

    // Blur, per row: a spring on the level times 100 (AMLL: CSS filter transition).
    private LyricsSpring[] lyricsRowBlur = new LyricsSpring[0];
    private boolean[] lyricsBlurSet = new boolean[0];

    private void scheduleLyricsFrame() {
        if (lyricsFrameScheduled || dismissing || !showingLyrics || lyricsListView == null) return;
        lyricsFrameScheduled = true;
        Choreographer.getInstance().postFrameCallback(lyricsFrameCallback);
    }

    private void cancelLyricsFrame() {
        if (!lyricsFrameScheduled) return;
        lyricsFrameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(lyricsFrameCallback);
    }

    private void onLyricsFrame(long frameTimeNanos) {
        lyricsFrameScheduled = false;
        if (dismissing || !showingLyrics || lyricsListView == null || lyricsListView.getHeight() == 0) return;
        lyricsInFrame = true;
        lyricsFrameNanos = frameTimeNanos;
        boolean more;
        try {
            more = runLyricsFrame(lyricsNow());
        } finally {
            lyricsInFrame = false;
        }
        if (more) scheduleLyricsFrame();
    }

    /**
     * The time every spring and transition is read at: the frame's vsync time inside the frame
     * callback, and outside it (a row bound during layout) the last frame's time while it is
     * recent, so a row painted between two frames agrees with the rows painted in them. It
     * never goes backwards, so no spring is ever read at an earlier time than before.
     */
    private long lyricsNow() {
        long t;
        if (lyricsInFrame) {
            t = lyricsFrameNanos;
        } else {
            t = System.nanoTime();
            if (lyricsFrameNanos != 0 && t - lyricsFrameNanos < LyricsTuning.CLOCK_MAX_FRAME_MS * 1_000_000L) {
                t = lyricsFrameNanos;
            }
        }
        if (t > lyricsLastNanos) lyricsLastNanos = t;
        return lyricsLastNanos;
    }

    /** One frame of everything. Returns whether another frame is needed. */
    private boolean runLyricsFrame(long now) {
        final MessageObject message = MediaController.getInstance().getPlayingMessageObject();
        if (message == null || currentLyrics == null || !currentLyrics.isSynced() || visibleLyrics.isEmpty()) {
            return false;
        }
        ensureLyricsMotionState(now);
        final boolean paused = MediaController.getInstance().isMessagePaused();
        final long position = sampleLyricsClock(message, now, paused);
        final int index = currentLyrics.lineAt(position);
        // AMLL timeline.ts: once the last stated word has ended, no line is active any more, and
        // neither is any line during an instrumental gap (the dots take the anchor instead).
        lyricsEndOfSong = position >= lyricsEndMs;
        lyricsInterludeRow = lyricsInterludeRowAt(position);
        // The list moves first, so a line-synced line can brighten on the same spring, with the
        // same delay, as its own row's scroll.
        updateLyricsFollow(index, position, now, paused);
        final boolean idle = lyricsEndOfSong || lyricsInterludeRow != RecyclerView.NO_POSITION;
        int activeLine = idle ? -1 : index;
        if (!lyricsWordTimed) {
            // Line-synced lyrics have no words to light up at the timestamp: the line brightens
            // as the list brings it in, pre-roll included.
            final int preRolled = lyricsPreRollLine(index, position);
            if (preRolled != index) activeLine = preRolled;
        }
        final int heldRow = idle ? RecyclerView.NO_POSITION : lyricsHeldRowAt(index, position);
        // Size and blur follow the row the list is bringing in; the dots and the wait before
        // the first line (until its pre-roll) have none.
        int stageRow = lyricsEndOfSong ? RecyclerView.NO_POSITION : lyricsFollowTargetRow(index, position);
        if (stageRow == lyricsInterludeRow || index < 0 && lyricsPreRollLine(index, position) < 0) {
            stageRow = RecyclerView.NO_POSITION;
        }
        updateActiveLyricsLine(activeLine, heldRow, stageRow, now);
        // Word state is resolved from the same position, so pause and seek land exactly where the
        // timestamps say. A line change repaints every row; otherwise only the singing row.
        updateKaraoke(index, position);
        final boolean moving = applyLyricsMotion(now);
        // While paused the loop keeps running until the clock has glided onto the player's
        // position and every spring has settled; then it stops until something changes.
        return moving || !paused || !lyricsClockSettled;
    }

    // --- Clock -----------------------------------------------------------------------------

    /**
     * The playback position for this frame.
     *
     * <p>The player's own position moves in steps (ExoPlayer publishes it from its playback
     * thread about every 10 ms, and corrects it against the audio clock now and then), so read
     * raw at each vsync it advances unevenly, and the fill, the lifts and the emphasis shake. So
     * the drawn clock follows it through a critically damped filter instead: the error against
     * a target moving at the playback speed obeys e'' + 2we' + w^2 e = 0, solved exactly per
     * frame (w = 1 / CLOCK_SMOOTHING_MS). While playing it has no lag and never runs backwards.
     * A pause decelerates it into the stop and a resume accelerates it out, and at rest it sits
     * exactly on the player's position. A jump larger than SEEK_JITTER_TOLERANCE_MS (a seek, while
     * playing or paused) is taken at once, and a change that disagrees with the wall clock by
     * more than that is reported as a seek (AMLL seek-detector.ts).
     */
    private long sampleLyricsClock(MessageObject message, long now, boolean paused) {
        final long raw = SyncedLyricsController.positionMs(message);
        final float speed = paused ? 0f : Math.max(0.1f, MediaController.getInstance().getPlaybackSpeed(true));
        final boolean flipped = paused != lyricsClockPaused;
        if (flipped) {
            // Wall time spent paused is not playback time, and a player that has just resumed has
            // not moved yet: measure from now. Without this, the first frame after an idle pause
            // extrapolated a whole CLOCK_MAX_EXTRAPOLATION_MS ahead and the next report read as a
            // seek.
            lyricsClockPaused = paused;
            lyricsRawNanos = now;
        }
        boolean seek = false;
        if (raw != lyricsRawPositionMs) {
            if (lyricsRawPositionMs >= 0) {
                final long wallMs = Math.min((now - lyricsRawNanos) / 1_000_000L, LyricsTuning.SEEK_MAX_TRUSTED_GAP_MS);
                final long expected = lyricsRawPositionMs + (long) (wallMs * speed);
                seek = Math.abs(raw - expected) > LyricsTuning.SEEK_JITTER_TOLERANCE_MS;
            }
            lyricsRawPositionMs = raw;
            lyricsRawNanos = now;
        } else if (paused) {
            lyricsRawNanos = now;
        }
        if (seek) markLyricsSeek(now);
        // The player's own estimate for this instant.
        double target = raw;
        if (!paused) {
            target += Math.min(Math.max(0L, (now - lyricsRawNanos) / 1_000_000L), LyricsTuning.CLOCK_MAX_EXTRAPOLATION_MS) * (double) speed;
        }
        if (lyricsClockNanos == 0 || seek || Math.abs(target - lyricsClock) > LyricsTuning.SEEK_JITTER_TOLERANCE_MS) {
            lyricsClock = target;
            lyricsClockVelocity = speed;
        } else {
            // On a pause or resume the target's speed changes this frame: start the new motion
            // from here rather than pretending the target already moved at it over the last gap.
            final double dt = flipped ? 0 : Math.min(Math.max(0L, now - lyricsClockNanos) / 1e6, LyricsTuning.CLOCK_MAX_FRAME_MS);
            final double omega = 1.0 / LyricsTuning.CLOCK_SMOOTHING_MS;
            final double e0 = (target - speed * dt) - lyricsClock;
            final double v0 = speed - lyricsClockVelocity;
            final double c = v0 + omega * e0;
            final double decay = Math.exp(-omega * dt);
            final double previous = lyricsClock;
            lyricsClock = target - (e0 + c * dt) * decay;
            lyricsClockVelocity = speed - (v0 - omega * c * dt) * decay;
            if (!paused && lyricsClock < previous) {
                lyricsClock = previous;
                lyricsClockVelocity = Math.max(0, lyricsClockVelocity);
            }
        }
        lyricsClockNanos = now;
        lyricsClockSettled = paused && Math.abs(target - lyricsClock) < 0.5 && Math.abs(lyricsClockVelocity) < 0.002;
        if (lyricsClockSettled) {
            lyricsClock = target;
            lyricsClockVelocity = 0;
        }
        return (long) Math.floor(lyricsClock);
    }

    // --- Per-row state ----------------------------------------------------------------------

    private void ensureLyricsMotionState(long now) {
        final int n = visibleLyrics.size();
        if (lyricsRowScroll.length == n) return;
        lyricsRowScroll = new LyricsSpring[n];
        lyricsRowScale = new LyricsSpring[n];
        lyricsRowFocus = new LyricsSpring[n];
        lyricsRowBlur = new LyricsSpring[n];
        lyricsBlurSet = new boolean[n];
        lyricsFadeFrom = new float[n];
        lyricsFadeTo = new float[n];
        lyricsFadeStart = new long[n];
        lyricsFadeDur = new long[n];
        final float restScale = LyricsTuning.SCALE_INACTIVE * 100f;
        for (int i = 0; i < n; i++) {
            lyricsRowScroll[i] = new LyricsSpring(lyricsScrollY);
            lyricsRowScale[i] = new LyricsSpring(restScale);
            lyricsRowFocus[i] = new LyricsSpring(0f);
            lyricsRowBlur[i] = new LyricsSpring(0f);
        }
        lyricsFocusRow = RecyclerView.NO_POSITION;
        lyricsHeldRow = RecyclerView.NO_POSITION;
        lyricsStageRow = RecyclerView.NO_POSITION;
        lyricsFollowRow = RecyclerView.NO_POSITION;
        lyricsAim = LYRICS_AIM_SNAP;
    }

    private boolean hasLyricsRow(int row) {
        return row >= 0 && row < lyricsRowScroll.length;
    }

    /** 0 = a line at rest, 1 = the active line; a critically damped spring (FOCUS_IN/OUT_*). */
    private float lyricsFocusValue(int row, long now) {
        if (!hasLyricsRow(row)) return 0f;
        if (!lyricsWordTimed && row < lyricsFadeTo.length) {
            final long elapsed = now - lyricsFadeStart[row];
            if (elapsed <= 0) return lyricsFadeFrom[row];
            if (elapsed >= lyricsFadeDur[row]) return lyricsFadeTo[row];
            final float u = elapsed / (float) lyricsFadeDur[row];
            return lerp(lyricsFadeFrom[row], lyricsFadeTo[row], u * u * (3f - 2f * u));
        }
        return Math.max(0f, Math.min(1f, lyricsRowFocus[row].value(now) / 100f));
    }

    private boolean lyricsFocusSettled(int row, long now) {
        if (!hasLyricsRow(row)) return true;
        if (!lyricsWordTimed && row < lyricsFadeTo.length) return now - lyricsFadeStart[row] >= lyricsFadeDur[row];
        return lyricsRowFocus[row].isSettled(now);
    }

    /** True when the list was re-aimed this very frame, so row changes can ride its spring. */
    private boolean lyricsStepNow(int row, long now) {
        return lyricsStepNanos == now && row >= 0 && row < lyricsRowStepDelay.length;
    }

    private void setLyricsFocusTarget(int row, float target, long now) {
        if (!hasLyricsRow(row)) return;
        if (!lyricsWordTimed && row < lyricsFadeTo.length) {
            // Line-synced: the brightness eases in and out on a smoothstep, from whatever it
            // shows now. A spring (Build 4 used the scroll's) is fastest at its start: it covered
            // almost half the change in the first 100 ms, which read as a jump in colour however
            // well it matched the motion. When the list moves with it, the fade starts with this
            // row's own scroll delay and lasts until that scroll has settled.
            if (lyricsFadeTo[row] == target) return;
            final float from = lyricsFocusValue(row, now);
            long delayMs = 0;
            long durationMs = target > 0f ? LyricsTuning.LINE_FADE_IN_MS : LyricsTuning.LINE_FADE_OUT_MS;
            if (lyricsStepNow(row, now)) {
                delayMs = lyricsRowStepDelay[row];
                final double omega = Math.sqrt(lyricsStepStiffness / LyricsTuning.SCROLL_MASS);
                durationMs = Math.round(LyricsTuning.LINE_FADE_SETTLE_OMEGA_T / omega * 1000.0);
            }
            lyricsFadeFrom[row] = from;
            lyricsFadeTo[row] = target;
            lyricsFadeStart[row] = now + delayMs * 1_000_000L;
            lyricsFadeDur[row] = Math.max(1L, durationMs) * 1_000_000L;
            return;
        }
        if (lyricsRowFocus[row].getTarget() == target * 100f) return;
        final boolean in = target > 0f;
        lyricsRowFocus[row].setTarget(target * 100f, now, 0, 1f,
                in ? LyricsTuning.FOCUS_IN_STIFFNESS : LyricsTuning.FOCUS_OUT_STIFFNESS,
                in ? LyricsTuning.FOCUS_IN_DAMPING : LyricsTuning.FOCUS_OUT_DAMPING);
    }

    /**
     * Play state never changes a line's size: every line zooming to 1.0 on pause (an AMLL
     * behaviour) widened the inactive lines from their reading edge on every pause and narrowed
     * them again on resume. Apple Music keeps the sizes when paused.
     */
    private float lyricsScaleTarget(int row) {
        return isLyricsRowStaged(row) ? LyricsTuning.SCALE_ACTIVE : LyricsTuning.SCALE_INACTIVE;
    }

    /**
     * The rows drawn full size and sharp: the row the list is bringing in, and the current line
     * and a line still singing over it (held) unless the list has already moved on past them
     * (the pre-roll, when they are about to end).
     */
    private boolean isLyricsRowStaged(int row) {
        if (row == RecyclerView.NO_POSITION) return false;
        if (row == lyricsStageRow) return true;
        return (row == lyricsFocusRow || row == lyricsHeldRow)
                && (lyricsStageRow == RecyclerView.NO_POSITION || row > lyricsStageRow);
    }

    private void retargetLyricsScale(int row, long now) {
        if (!hasLyricsRow(row) || lyricsRowScale[row].getTarget() == lyricsScaleTarget(row) * 100f) return;
        if (lyricsStepNow(row, now)) {
            // The list is moving this row now: the size changes on the row's own scroll spring,
            // with its stagger delay, so both settle in the same frame. (On its own slower
            // spring, started at the timestamp after the pre-roll, the line that had just ended
            // kept shrinking into place after the scroll had stopped.)
            lyricsRowScale[row].setTarget(lyricsScaleTarget(row) * 100f, now, lyricsRowStepDelay[row],
                    LyricsTuning.SCROLL_MASS, lyricsStepStiffness, lyricsStepDamping);
            return;
        }
        lyricsRowScale[row].setTarget(lyricsScaleTarget(row) * 100f, now, 0,
                LyricsTuning.SCALE_MASS, LyricsTuning.SCALE_STIFFNESS, LyricsTuning.SCALE_DAMPING);
    }

    /**
     * The logical active line: it changes exactly at the line's own timestamp, and brightness and
     * scale follow it from there. A timed blank, or the time before the first line, has no active
     * row, so everything dims.
     */
    private void updateActiveLyricsLine(int index, int heldRow, int stageRow, long now) {
        if (index != activeLyricsLine) {
            activeLyricsLine = index;
            activeLyricsRow = rowForLyricsLine(index);
        }
        final int row = activeLyricsRow;
        final int oldFocus = lyricsFocusRow;
        final int oldHeld = lyricsHeldRow;
        final int oldStage = lyricsStageRow;
        if (row == oldFocus && heldRow == oldHeld && stageRow == oldStage) return;
        lyricsFocusRow = row;
        lyricsHeldRow = heldRow;
        lyricsStageRow = stageRow;
        // A row that stays lit in its other role (the line just left, held on by its background
        // vocals) keeps its target rather than being turned down and up again.
        if (oldFocus != row && oldFocus != heldRow) setLyricsFocusTarget(oldFocus, 0f, now);
        if (oldHeld != heldRow && oldHeld != row) setLyricsFocusTarget(oldHeld, 0f, now);
        setLyricsFocusTarget(row, 1f, now);
        setLyricsFocusTarget(heldRow, 1f, now);
        retargetLyricsScale(oldFocus, now);
        retargetLyricsScale(oldHeld, now);
        retargetLyricsScale(oldStage, now);
        retargetLyricsScale(row, now);
        retargetLyricsScale(heldRow, now);
        retargetLyricsScale(stageRow, now);
    }

    // --- Follow ------------------------------------------------------------------------------

    /**
     * Asks the next frame to re-aim the list. {@code animated == false} puts the followed line on
     * the anchor at once (first show, resize); otherwise it springs there without stagger.
     */
    private void updateLyricsFollow(boolean animated) {
        if (dismissing || lyricsListView == null || !showingLyrics) return;
        lyricsAim = animated ? Math.max(lyricsAim, LYRICS_AIM_SOFT) : LYRICS_AIM_SNAP;
        scheduleLyricsFrame();
    }

    /**
     * Stops following. Rows still leading or lagging in a stagger settle onto the list from
     * where they are drawn now, with their speed, instead of jumping there.
     */
    private void cancelLyricsFollow() {
        lyricsFollowRow = RecyclerView.NO_POSITION;
        settleLyricsRows(lyricsNow());
    }

    /** Springs every row (no delay) onto the list's real scroll, from its current motion. */
    private void settleLyricsRows(long now) {
        if (lyricsRowScroll.length == 0) return;
        final float stiffness = LyricsTuning.SCROLL_STIFFNESS_MAX;
        final float damping = (float) Math.sqrt(stiffness) * LyricsTuning.SCROLL_DAMPING_MULTIPLIER;
        for (LyricsSpring spring : lyricsRowScroll) {
            spring.setTarget(lyricsScrollY, now, 0, LyricsTuning.SCROLL_MASS, stiffness, damping);
        }
        scheduleLyricsFrame();
    }

    /** Folds any stagger offset into rest: every row's spring sits at the list's real scroll. */
    private void freezeLyricsRows() {
        for (LyricsSpring spring : lyricsRowScroll) spring.snap(lyricsScrollY);
        if (lyricsListView == null) return;
        for (int i = 0; i < lyricsListView.getChildCount(); i++) {
            lyricsListView.getChildAt(i).setTranslationY(0f);
        }
    }

    private void markLyricsSeek(long now) {
        lyricsSeekPending = true;
        lyricsSeekNanos = now;
    }

    private void updateLyricsFollow(int line, long position, long now, boolean paused) {
        if (lyricsUserScrolling || lyricsPagerTracking || draggingSeekBar) return;
        final int targetRow = lyricsFollowTargetRow(line, position);
        // A timed blank has no row: the list stays where it is while everything dims.
        if (targetRow == RecyclerView.NO_POSITION) return;
        if (targetRow == lyricsFollowRow && lyricsAim == LYRICS_AIM_NONE) return;
        int aim = lyricsAim;
        lyricsAim = LYRICS_AIM_NONE;
        final boolean seek = lyricsSeekPending
                && now - lyricsSeekNanos <= LyricsTuning.SEEK_WINDOW_MS * 1_000_000L;
        lyricsSeekPending = false;
        if (targetRow != lyricsFollowRow && aim == LYRICS_AIM_SOFT) aim = LYRICS_AIM_NONE;
        retargetLyricsScroll(targetRow, now, aim, seek || aim == LYRICS_AIM_FAR);
    }

    /**
     * The row the list should have on the anchor at {@code position}. A pure function of the
     * position, paused or not: tying it to the play state moved the list back when a pause landed
     * inside the pre-roll, and forward again on resume.
     */
    private int lyricsFollowTargetRow(int line, long position) {
        // Before the first line (song start, a rewind to 0 when the song ends, the next song) the
        // first line waits on the anchor, as AMLL's clamped scrollToIndex does.
        int targetRow = line < 0 ? 0 : rowForLyricsLine(line);
        // During an instrumental gap the dots sit on the anchor.
        if (lyricsInterludeRow != RecyclerView.NO_POSITION) targetRow = lyricsInterludeRow;
        // Background vocals of the previous line still being sung over this one: like Apple
        // Music (and AMLL, which scrolls to the first of its active lines), both lines are lit
        // and the list stays on the earlier one until its singing ends, then moves on, with the
        // same pre-roll as a line change.
        final int held = lyricsHeldRowAt(line, position);
        if (held != RecyclerView.NO_POSITION) {
            final long holdEnd = lyricsLineHoldEnd[line - 1];
            final long lineStart = currentLyrics.lines.get(line).timeMs;
            final long lead = Math.min(LyricsTuning.PRE_ROLL_MAX_MS,
                    (long) (Math.max(1L, holdEnd - lineStart) * LyricsTuning.PRE_ROLL_GAP_FRACTION));
            return holdEnd - position <= lead ? targetRow : held;
        }
        // A line whose background vocals will run on past the next line's start keeps the list
        // until they end, so it does not pre-roll away from it either.
        final boolean willHold = line >= 0 && line < lyricsLineHoldEnd.length && lyricsLineHoldEnd[line] != Long.MIN_VALUE;
        if (!willHold) {
            final int preRolled = lyricsPreRollLine(line, position);
            if (preRolled != line) targetRow = rowForLyricsLine(preRolled);
        }
        return targetRow;
    }

    /** {@code line + 1} when the list is already moving to it (the pre-roll), else {@code line}. */
    private int lyricsPreRollLine(int line, long position) {
        if (currentLyrics == null || line + 1 >= currentLyrics.lines.size()) return line;
        final long nextTime = currentLyrics.lines.get(line + 1).timeMs;
        final long previousTime = line < 0 ? 0 : currentLyrics.lines.get(line).timeMs;
        final long gap = Math.max(1, nextTime - previousTime);
        final long lead = Math.min(LyricsTuning.PRE_ROLL_MAX_MS, (long) (gap * LyricsTuning.PRE_ROLL_GAP_FRACTION));
        final int nextRow = rowForLyricsLine(line + 1);
        return nextRow != RecyclerView.NO_POSITION && nextTime - position <= lead ? line + 1 : line;
    }

    private void retargetLyricsScroll(int row, long now, int aim, boolean seek) {
        final View child = lyricsLayoutManager.findViewByPosition(row);
        if (child == null) {
            // Far away: the row is not laid out, so the list cannot spring through the document to
            // it. On first show it is simply put on the anchor. Otherwise (a long seek, the song
            // restarting from its end, a follow resuming far from where the user scrolled) it is
            // placed just off the anchor on the side it is coming from and springs in on the slow
            // spring next frame, instead of the list jumping there.
            final boolean snap = aim == LYRICS_AIM_SNAP;
            int offset = getLyricsAnchorY() - lyricsListView.getPaddingTop() - dp(32);
            if (!snap) {
                final int first = lyricsLayoutManager.findFirstVisibleItemPosition();
                final int side = first == RecyclerView.NO_POSITION || row >= first ? 1 : -1;
                offset += side * Math.round(lyricsListView.getHeight() * LyricsTuning.FAR_TARGET_ENTRY_FRACTION);
            }
            lyricsLayoutManager.scrollToPositionWithOffset(row, offset);
            freezeLyricsRows();
            lyricsFollowRow = snap ? row : RecyclerView.NO_POSITION;
            lyricsAim = snap ? LYRICS_AIM_SNAP : LYRICS_AIM_FAR;
            scheduleLyricsFrame();
            return;
        }
        // Layout position: getTop()/getBottom() never include translationY.
        final int distance = (child.getTop() + child.getBottom()) / 2 - getLyricsAnchorY();
        lyricsFollowRow = row;
        if (aim == LYRICS_AIM_SNAP) {
            if (distance != 0) {
                lyricsApplyingMotion = true;
                lyricsListView.scrollBy(0, distance);
                lyricsApplyingMotion = false;
            }
            freezeLyricsRows();
            return;
        }
        final float target = lyricsScrollY + distance;
        final boolean slow = seek || aim == LYRICS_AIM_SOFT || isSlowLyricsTarget(row);
        final float stiffness, damping;
        if (!slow && lyricsEndOfSong) {
            // AMLL getPosYSpringPolicy: the song has finished.
            stiffness = LyricsTuning.SCROLL_END_STIFFNESS;
            damping = LyricsTuning.SCROLL_END_DAMPING;
        } else if (slow) {
            stiffness = LyricsTuning.SCROLL_SLOW_STIFFNESS;
            damping = LyricsTuning.SCROLL_SLOW_DAMPING;
        } else {
            stiffness = lyricsScrollStiffness(row);
            damping = (float) Math.sqrt(stiffness) * LyricsTuning.SCROLL_DAMPING_MULTIPLIER;
        }
        final boolean stagger = !seek && aim == LYRICS_AIM_NONE;
        if (lyricsRowStepDelay.length != lyricsRowScroll.length) lyricsRowStepDelay = new long[lyricsRowScroll.length];
        // AMLL calcLayout: from the top of the viewport down, each row whose NEW position is on
        // screen adds one step of delay; from the followed row on, each step shrinks by DECAY.
        final int first = lyricsLayoutManager.findFirstVisibleItemPosition();
        final int last = lyricsLayoutManager.findLastVisibleItemPosition();
        float delay = 0f;
        float step = LyricsTuning.STAGGER_STEP_MS;
        for (int i = 0; i < lyricsRowScroll.length; i++) {
            long rowDelay = 0;
            if (stagger && first != RecyclerView.NO_POSITION && i >= first) {
                rowDelay = Math.round(delay);
                final View c = i <= last ? lyricsLayoutManager.findViewByPosition(i) : null;
                final boolean onScreen = c == null ? i > last : c.getBottom() - distance >= 0;
                if (onScreen) {
                    delay += step;
                    if (i >= row) step /= LyricsTuning.STAGGER_DECAY;
                }
            }
            lyricsRowScroll[i].setTarget(target, now, rowDelay, LyricsTuning.SCROLL_MASS, stiffness, damping);
            lyricsRowStepDelay[i] = rowDelay;
        }
        lyricsStepNanos = now;
        lyricsStepStiffness = stiffness;
        lyricsStepDamping = damping;
    }

    /** AMLL: the first line, and a line coming out of a timed blank, move on the slow spring. */
    private boolean isSlowLyricsTarget(int row) {
        if (row <= 0) return true;
        final int line = visibleLyrics.get(row);
        final int previous = line - 1;
        return previous < 0 || TextUtils.isEmpty(currentLyrics.lines.get(previous).text);
    }

    /** AMLL getPosYSpringPolicy: shorter gaps between lines get stiffer springs. */
    private float lyricsScrollStiffness(int row) {
        // The previous lyric row, skipping a dots row.
        int previous = row - 1;
        while (previous >= 0 && visibleLyrics.get(previous) < 0) previous--;
        if (previous < 0 || visibleLyrics.get(row) < 0) return LyricsTuning.SCROLL_STIFFNESS_MIN;
        final long interval = currentLyrics.lines.get(visibleLyrics.get(row)).timeMs
                - currentLyrics.lines.get(visibleLyrics.get(previous)).timeMs;
        final float clamped = Math.max(LyricsTuning.SCROLL_INTERVAL_MIN_MS, Math.min(LyricsTuning.SCROLL_INTERVAL_MAX_MS, interval));
        float ratio = 1f - (clamped - LyricsTuning.SCROLL_INTERVAL_MIN_MS)
                / (LyricsTuning.SCROLL_INTERVAL_MAX_MS - LyricsTuning.SCROLL_INTERVAL_MIN_MS);
        ratio = (float) Math.pow(ratio, LyricsTuning.SCROLL_INTERVAL_EXPONENT);
        return LyricsTuning.SCROLL_STIFFNESS_MIN + ratio * (LyricsTuning.SCROLL_STIFFNESS_MAX - LyricsTuning.SCROLL_STIFFNESS_MIN);
    }

    private int rowForLyricsLine(int line) {
        return line < 0 || line >= lyricsLineToRow.length ? RecyclerView.NO_POSITION : lyricsLineToRow[line];
    }

    /** Every line as displayed (background vocals split off); once per document. */
    private void buildLyricsDisplayLines() {
        final int lines = currentLyrics == null ? 0 : currentLyrics.lines.size();
        lyricsDisplayLines = new SyncedLyricsController.Line[lines];
        lyricsLineHoldEnd = new long[lines];
        java.util.Arrays.fill(lyricsLineHoldEnd, Long.MIN_VALUE);
        for (int i = 0; i < lines; i++) {
            final SyncedLyricsController.Line display = SyncedLyricsController.splitBackgroundVocals(currentLyrics.lines.get(i));
            lyricsDisplayLines[i] = display;
            final SyncedLyricsController.Segments segments = display.segments;
            if (!display.timed || segments == null || segments.size() == 0) continue;
            final long next = nextLyricsLineTimeMs(i);
            final int last = segments.size() - 1;
            final long end = segments.startTimeMs(last) + KaraokeFrame.sweepWindowMs(display.text, segments, last, next);
            if (next != Long.MAX_VALUE && end > next) lyricsLineHoldEnd[i] = end;
        }
    }

    /** The row of the line before {@code line} while its singing still runs on, or NO_POSITION. */
    private int lyricsHeldRowAt(int line, long position) {
        final int previous = line - 1;
        if (previous < 0 || previous >= lyricsLineHoldEnd.length || position >= lyricsLineHoldEnd[previous]) {
            return RecyclerView.NO_POSITION;
        }
        return rowForLyricsLine(previous);
    }

    /** The line a row displays, background vocals split off; null for a dots row. */
    private SyncedLyricsController.Line displayLyricsLine(int line) {
        if (line < 0) return null;
        if (line < lyricsDisplayLines.length && lyricsDisplayLines[line] != null) return lyricsDisplayLines[line];
        return currentLyrics == null || line >= currentLyrics.lines.size() ? null : currentLyrics.lines.get(line);
    }

    /**
     * Finds the instrumental gaps (AMLL timeline.ts calculateInterludes: at least
     * INTERLUDE_MIN_GAP_MS from the end of one line's singing to the next line, including the
     * intro before the first line) and gives each a dots row just before the line that ends it.
     * Singing ends at a timed blank line, or at the last word's stated end; a line that states
     * neither sings until the next one, as in AMLL.
     */
    private void buildLyricsInterludes() {
        final int lines = currentLyrics == null ? 0 : currentLyrics.lines.size();
        lyricsLineSingingEnd = new long[lines];
        java.util.Arrays.fill(lyricsLineSingingEnd, Long.MAX_VALUE);
        lyricsInterludeRow = RecyclerView.NO_POSITION;
        final ArrayList<long[]> gaps = new ArrayList<>();
        if (currentLyrics != null && currentLyrics.isSynced()) {
            final ArrayList<Integer> rows = new ArrayList<>(visibleLyrics.size() + 4);
            int previous = -1;
            for (int r = 0; r < visibleLyrics.size(); r++) {
                final int line = visibleLyrics.get(r);
                final SyncedLyricsController.Line current = currentLyrics.lines.get(line);
                if (current.timed) {
                    final long gapStart = previous < 0 ? 0 : lyricsSingingEnd(previous, line);
                    final long gapEnd = current.timeMs;
                    if (gapStart != Long.MAX_VALUE && gapEnd - gapStart >= LyricsTuning.INTERLUDE_MIN_GAP_MS) {
                        gaps.add(new long[] {rows.size(), gapStart, gapEnd, previous < 0 ? 1 : 0});
                        rows.add(LYRICS_ROW_INTERLUDE);
                        if (previous >= 0) lyricsLineSingingEnd[previous] = gapStart;
                    }
                    previous = line;
                }
                rows.add(line);
            }
            if (!gaps.isEmpty()) {
                visibleLyrics.clear();
                visibleLyrics.addAll(rows);
            }
        }
        final int n = gaps.size();
        lyricsInterludeRows = new int[n];
        lyricsInterludeStart = new long[n];
        lyricsInterludeEnd = new long[n];
        lyricsInterludeIntro = new boolean[n];
        for (int i = 0; i < n; i++) {
            final long[] gap = gaps.get(i);
            lyricsInterludeRows[i] = (int) gap[0];
            lyricsInterludeStart[i] = gap[1];
            lyricsInterludeEnd[i] = gap[2];
            lyricsInterludeIntro[i] = gap[3] != 0;
        }
    }

    /** When the singing of {@code line} ends before {@code next}, or MAX_VALUE if unknown. */
    private long lyricsSingingEnd(int line, int next) {
        for (int i = line + 1; i < next; i++) {
            final SyncedLyricsController.Line blank = currentLyrics.lines.get(i);
            if (blank.timed && TextUtils.isEmpty(blank.text)) return Math.max(blank.timeMs, currentLyrics.lines.get(line).timeMs);
        }
        final SyncedLyricsController.Segments segments = currentLyrics.lines.get(line).segments;
        if (segments == null || segments.size() == 0) return Long.MAX_VALUE;
        long end = -1;
        for (int k = 0; k < segments.size(); k++) {
            if (segments.hasEndTime(k)) end = Math.max(end, segments.endTimeMs(k));
        }
        final int last = segments.size() - 1;
        if (!segments.hasEndTime(last) || end < 0) return Long.MAX_VALUE;
        return end;
    }

    /** The dots row whose gap contains {@code position}, or NO_POSITION. */
    private int lyricsInterludeRowAt(long position) {
        for (int i = 0; i < lyricsInterludeRows.length; i++) {
            if (position >= lyricsInterludeStart[i] && position < lyricsInterludeEnd[i]) return lyricsInterludeRows[i];
        }
        return RecyclerView.NO_POSITION;
    }

    private int lyricsInterludeIndexForRow(int row) {
        for (int i = 0; i < lyricsInterludeRows.length; i++) {
            if (lyricsInterludeRows[i] == row) return i;
        }
        return -1;
    }

    /** Line-to-row table and the end of the last stated word; once per document. */
    private void indexLyricsDocument() {
        final int lines = currentLyrics == null ? 0 : currentLyrics.lines.size();
        lyricsLineToRow = new int[lines];
        java.util.Arrays.fill(lyricsLineToRow, RecyclerView.NO_POSITION);
        for (int i = 0; i < visibleLyrics.size(); i++) {
            final int line = visibleLyrics.get(i);
            if (line >= 0 && line < lines) lyricsLineToRow[line] = i;
        }
        lyricsEndMs = Long.MAX_VALUE;
        if (!lyricsWordTimed) return;
        // Only word timing states where singing stops; a line-synced document never "ends".
        for (int i = lines - 1; i >= 0; i--) {
            final SyncedLyricsController.Line line = currentLyrics.lines.get(i);
            if (!line.timed || TextUtils.isEmpty(line.text)) continue;
            if (line.segments == null || line.segments.size() == 0) return;
            final SyncedLyricsController.Segments segments = line.segments;
            final int last = segments.size() - 1;
            final long start = segments.startTimeMs(last);
            final long stated = segments.hasEndTime(last) ? segments.endTimeMs(last) - start : 0;
            lyricsEndMs = start + (stated > 0 ? stated : LyricsTuning.END_OF_SONG_DERIVED_MS);
            return;
        }
    }

    /**
     * Moves the list to the followed row's spring and paints every attached row. Returns whether
     * anything on screen is still moving.
     */
    private boolean applyLyricsMotion(long now) {
        if (!lyricsUserScrolling && hasLyricsRow(lyricsFollowRow)) {
            final int delta = Math.round(lyricsRowScroll[lyricsFollowRow].value(now)) - lyricsScrollY;
            if (delta != 0) {
                lyricsApplyingMotion = true;
                lyricsListView.scrollBy(0, delta);
                lyricsApplyingMotion = false;
            }
        }
        final boolean moving = updateLyricsDepth();
        // A target the list could not reach (the very ends of the document) would otherwise leave
        // rows translated for good once the springs rest. They glide back onto the list rather
        // than snapping there.
        if (!moving && hasLyricsRow(lyricsFollowRow)
                && Math.abs(lyricsRowScroll[lyricsFollowRow].getTarget() - lyricsScrollY) >= 1f) {
            settleLyricsRows(now);
            return true;
        }
        return moving;
    }

    /** Paints every attached row; returns whether any of them is still animating. */
    private boolean updateLyricsDepth() {
        if (lyricsListView == null || lyricsListView.getHeight() == 0) return false;
        // Hoisted out of the per-row body: a themed colour is a map lookup, not a field.
        final int inactiveColor = getThemedColor(Theme.key_player_time);
        final int activeColor = getThemedColor(Theme.key_player_actionBarTitle);
        final int sweepColor = karaokeSweepColor();
        boolean moving = false;
        for (int i = 0; i < lyricsListView.getChildCount(); i++) {
            final View child = lyricsListView.getChildAt(i);
            moving |= applyLyricsDepth(child, lyricsRowOf(child), inactiveColor, activeColor, sweepColor);
        }
        return moving;
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

    // Long-note word emphasis constants
    /** Minimum word duration to qualify for long-note scale/glow emphasis. Reference-derived (Apple Music). */
    private static final long LONG_NOTE_MIN_DURATION_MS = 1000L;
    /** Cap on long-note emphasis animation duration. Reference-derived (Apple Music). */
    private static final long LONG_NOTE_MAX_ANIMATION_MS = 3000L;
    /** Maximum eligible grapheme count for long-note emphasis; >7 skips the effect. Reference-derived (Apple Music). */
    private static final int LONG_NOTE_MAX_GRAPHEMES = 7;
    /** Maximum glow shadow alpha: 128/255. Reference-derived (Apple Music). */
    private static final float LONG_NOTE_MAX_SHADOW_ALPHA = 128f / 255f;
    /** Glow radius in dp. PROVISIONAL TELEGRAM VALUE — Apple glow resource not recovered. */
    private static final float LONG_NOTE_GLOW_RADIUS_DP = 5f;
    /** Long-note emphasis easing: cubic-bezier(0.25, 0.10, 0.25, 1.0). Reference-derived (Apple Music). */
    private static final CubicBezierInterpolator LONG_NOTE_EASING =
            new CubicBezierInterpolator(0.25, 0.10, 0.25, 1.0);

    /** Resolved once per document: true only when some line genuinely states inline word timing. */
    private boolean lyricsWordTimed;

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
            // on, so a line change - including a seek across several lines - repaints them all:
            // applyLyricsMotion does, later in this same frame, after the list has moved.
            return;
        }
        // A theme swapped underneath an open player changes the palette but not the position, so
        // it costs two lookups a tick to notice and one repaint of the page to answer.
        final int inactiveColor = getThemedColor(Theme.key_player_time);
        final int activeColor = getThemedColor(Theme.key_player_actionBarTitle);
        if (inactiveColor != karaokeMutedSource || activeColor != karaokeSungSource) {
            karaokeMutedSource = inactiveColor;
            karaokeSungSource = activeColor;
        }
        // The singing row, like every attached row, is painted by applyLyricsMotion this frame.
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
        cancelLyricsFollow();
        lyricsRowScroll = new LyricsSpring[0];
        lyricsRowScale = new LyricsSpring[0];
        lyricsRowFocus = new LyricsSpring[0];
        lyricsFocusRow = RecyclerView.NO_POSITION;
        lyricsHeldRow = RecyclerView.NO_POSITION;
        lyricsStageRow = RecyclerView.NO_POSITION;
        lyricsFadeFrom = new float[0];
        lyricsFadeTo = new float[0];
        lyricsFadeStart = new long[0];
        lyricsFadeDur = new long[0];
        lyricsRowBlur = new LyricsSpring[0];
        lyricsBlurSet = new boolean[0];
        lyricsSeekPending = false;
        lyricsEndOfSong = false;
        // A new document's first position is not a seek against the previous document's clock,
        // and its clock starts where the player is rather than gliding there.
        lyricsRawPositionMs = -1;
        lyricsClockNanos = 0;
        lyricsClock = 0;
        lyricsClockVelocity = 0;
        karaoke.clear();
        rowKaraoke.clear();
        karaokeLine = Integer.MIN_VALUE;
        karaokePositionMs = 0;
        karaokeRow = RecyclerView.NO_POSITION;
        karaokeNextLineTimeMs = Long.MAX_VALUE;
        karaokeMutedSource = 0;
        karaokeSungSource = 0;
        lyricsInterludeRow = RecyclerView.NO_POSITION;
    }

    /**
     * Paints one row synchronously, from the per-row state indexed by {@code row}, so a row that
     * was just bound or attached never draws a frame in the adapter's default colour, scale or
     * translation.
     */
    private void applyLyricsDepth(View child, int row) {
        applyLyricsDepth(child, row, getThemedColor(Theme.key_player_time),
                getThemedColor(Theme.key_player_actionBarTitle), karaokeSweepColor());
    }

    /** The adapter row a lyrics child shows: its adapter position, else its laid-out one. */
    private int lyricsRowOf(View child) {
        final RecyclerView.ViewHolder holder = lyricsListView.findContainingViewHolder(child);
        if (holder == null) return RecyclerView.NO_POSITION;
        final int row = holder.getAdapterPosition();
        return row != RecyclerView.NO_POSITION ? row : holder.getLayoutPosition();
    }

    /**
     * Paints one row's place in the page. Three ideas, kept apart on purpose:
     *
     * <ul>
     *     <li><b>position</b> is the row's own spring against the followed row: the stagger.</li>
     *     <li><b>focus</b> comes from the logical active line: {@link #lyricsFocusValue}. It
     *     changes at the line's own timestamp and rides a spring, and so does scale. Neither is
     *     tied to the scroll.</li>
     *     <li><b>word state</b> comes from the source's own offsets and the clock, and only for a
     *     document that genuinely states them. It is resolved against {@code karaokeLine}, never
     *     against focus, so a line fading in early still shows nothing lit until its own time -
     *     and a line fading out keeps painting its real word state the whole way down.</li>
     * </ul>
     */
    private boolean applyLyricsDepth(View child, int row, int inactiveColor, int activeColor, int sweepColor) {
        final LyricsTextView textView = child instanceof LyricsTextView ? (LyricsTextView) child : null;
        if (currentLyrics == null || !currentLyrics.isSynced()) {
            // Normal lyrics are read, not followed: no invented focus, no depth falloff, no word
            // effects. They get the same large, bold, airy setting as everything else - that is a
            // question of how the page is set, not of what the source can do - and nothing more.
            child.setAlpha(1f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setTranslationY(0f);
            if (textView != null) {
                textView.clearKaraoke();
                textView.setDepthBlur(0f);
            }
            return false;
        }
        final long now = lyricsNow();
        // A row bound before the first frame of a new document still needs its springs and focus.
        if (!visibleLyrics.isEmpty()) ensureLyricsMotionState(now);
        final SyncedLyricsController.Line lyricLine = lineForLyricsRow(row);
        // The held row (background vocals still being sung over the next line) is resolved
        // against the clock like the current line, with its own next-line time.
        final boolean held = row == lyricsHeldRow && hasLyricsRow(row);
        final int lineIndex = row >= 0 && row < visibleLyrics.size() ? visibleLyrics.get(row) : -1;
        final boolean wordFrame = lyricsWordTimed && lyricLine != null && textView != null
                && rowKaraoke.resolveRow(lyricLine, lineIndex, held ? lineIndex : karaokeLine,
                        karaokePositionMs, held ? nextLyricsLineTimeMs(lineIndex) : karaokeNextLineTimeMs);
        boolean moving = false;
        // Position: this row's lead or lag against the row the list is following (the stagger).
        // While the user scrolls, the springs ride along with the finger (onScrolled shifts them),
        // so a stagger still in flight settles instead of jumping.
        float translation = 0f;
        if (hasLyricsRow(row)) {
            final LyricsSpring spring = lyricsRowScroll[row];
            translation = lyricsScrollY - spring.value(now);
            final boolean settled = spring.isSettled(now);
            // At rest the row sits exactly on its pixel, so its layer is drawn unfiltered.
            if (settled && Math.abs(translation) < 0.05f) translation = 0f;
            moving = !settled;
        }
        child.setTranslationY(translation);
        // Brightness changes at the line's own timestamp; brightness and scale each ride their
        // own spring. Neither is tied to the scroll.
        final float focus = lyricsFocusValue(row, now);
        float lineScale = 1f;
        if (hasLyricsRow(row)) {
            moving |= !lyricsFocusSettled(row, now);
            lineScale = lyricsRowScale[row].value(now) / 100f;
            final boolean settled = lyricsRowScale[row].isSettled(now);
            if (settled) lineScale = lyricsRowScale[row].getTarget() / 100f;
            moving |= !settled;
        }
        child.setAlpha(1f);
        // Blur by line distance (AMLL LyricPlayerBase.resolveBlurLevel), on its own spring.
        final float blurLevel = lyricsBlurValue(row, lyricsBlurTarget(row), now);
        if (hasLyricsRow(row)) moving |= !lyricsRowBlur[row].isSettled(now);
        child.setScaleX(lineScale);
        child.setScaleY(lineScale);
        // Pivot at the reading edge of the lyric text — derived from the lyric layout's own
        // paragraph direction, NOT the app UI locale, so RTL lyrics on an LTR device pivot
        // correctly and vice versa.
        final android.text.Layout lyricsTextLayout = textView != null ? textView.getLayout() : null;
        final boolean lyricsRtl = lyricsTextLayout != null
                && lyricsTextLayout.getParagraphDirection(0) == android.text.Layout.DIR_RIGHT_TO_LEFT;
        child.setPivotX(lyricsRtl ? child.getWidth() : 0f);
        child.setPivotY(child.getHeight() / 2f);
        if (child instanceof LyricsInterludeView) {
            final LyricsInterludeView dots = (LyricsInterludeView) child;
            dots.setColor(sweepColor);
            moving |= dots.setClock(karaokePositionMs, now);
            return moving;
        }
        if (textView == null) return moving;
        textView.setDepthBlur(lyricsBlurRadiusPx(blurLevel));
        textView.setBackgroundVisibility(focus);
        if (wordFrame) {
            // Word timing splits what used to be one colour into two: text the source has reached
            // takes the sung colour, text it has not stays muted, and the word being sung right now
            // is filled from the muted colour into the sung one by a clip travelling across its
            // glyphs. A line already passed is wholly sung; a line the pre-roll is bringing in
            // early shows nothing lit until its own time.
            // Opacity lives in the colour, never in View alpha, so no row needs an offscreen layer.
            // Exactly three stages: every inactive line ALPHA_INACTIVE, and on the active line
            // ALPHA_UNSUNG for what is still to come and ALPHA_SUNG for what has been sung.
            final int sungColor = lyricsAlphaColor(sweepColor,
                    lerp(LyricsTuning.ALPHA_INACTIVE, LyricsTuning.ALPHA_SUNG, focus));
            final int mutedColor = lyricsAlphaColor(sweepColor,
                    lerp(LyricsTuning.ALPHA_INACTIVE, LyricsTuning.ALPHA_UNSUNG, focus));
            textView.setLyricTextColor(mutedColor);
            textView.setKaraokeColors(mutedColor, sungColor);
            textView.setKaraokeFrame(rowKaraoke.wordStart, rowKaraoke.wordEnd, rowKaraoke.sweep,
                    rowKaraoke.ownedEnd,
                    rowKaraoke.hasExplicitWordEnd, rowKaraoke.gapProgress);
            // Lift and emphasis are functions of the position alone, for every word-timed row: a
            // line that has just stopped being active lowers its words from where they were.
            textView.setWordClock(karaokePositionMs, now);
        } else {
            // Ordinary line-synced text, and any untimed line inside a karaoke document. Line-level
            // hierarchy only: no sweep, no word motion, nothing invented.
            textView.clearKaraoke();
            textView.setLyricTextColor(lyricsAlphaColor(sweepColor,
                    lerp(LyricsTuning.ALPHA_INACTIVE, LyricsTuning.ALPHA_SUNG, focus)));
        }
        return moving;
    }

    /**
     * AMLL's blur level for a row: 0 on the active line and while the user scrolls, otherwise
     * by line distance. It depends on the line distance alone: it used to depend on whether the
     * row's translated bounds were on screen too, and rows crossing the edge during a stagger
     * flipped between two targets and restarted their blur every time (the blur "pumped").
     */
    private float lyricsBlurTarget(int row) {
        if (lyricsUserScrolling || row == RecyclerView.NO_POSITION) return 0f;
        if (isLyricsRowStaged(row) || row == lyricsInterludeRow) return 0f;
        // Distances count from the row the list is bringing in, so they change with the scroll.
        final int ref = lyricsStageRow != RecyclerView.NO_POSITION ? lyricsStageRow
                : lyricsFocusRow != RecyclerView.NO_POSITION ? lyricsFocusRow : lyricsFollowRow;
        if (ref == RecyclerView.NO_POSITION) return 0f;
        // Lines already passed count one further than lines still to come.
        final int distance = row < ref ? ref - row + 1 : row - ref;
        return Math.min(LyricsTuning.BLUR_LEVEL_MAX, (1 + distance) * LyricsTuning.BLUR_LEVEL_STEP);
    }

    /** The row's blur level now, springing towards {@code target} (BLUR_STIFFNESS / DAMPING). */
    private float lyricsBlurValue(int row, float target, long now) {
        if (!hasLyricsRow(row)) return target;
        final LyricsSpring spring = lyricsRowBlur[row];
        if (!lyricsBlurSet[row]) {
            lyricsBlurSet[row] = true;
            spring.snap(target * 100f);
            return target;
        }
        if (spring.getTarget() != target * 100f) {
            if (lyricsStepNow(row, now)) {
                // Re-aimed this frame: on the row's own scroll spring and delay, like its size.
                spring.setTarget(target * 100f, now, lyricsRowStepDelay[row], LyricsTuning.SCROLL_MASS,
                        lyricsStepStiffness, lyricsStepDamping);
            } else {
                spring.setTarget(target * 100f, now, 0, 1f, LyricsTuning.BLUR_STIFFNESS, LyricsTuning.BLUR_DAMPING);
            }
        }
        if (spring.isSettled(now)) return target;
        return Math.max(0f, spring.value(now) / 100f);
    }

    /**
     * A blur level is a CSS blur() length, which is a Gaussian standard deviation in CSS px (dp
     * here). RenderEffect takes a radius and converts it with Skia's sigma = 0.57735 * r + 0.5.
     */
    private static float lyricsBlurRadiusPx(float level) {
        if (level <= 0.01f) return 0f;
        final float sigma = level * AndroidUtilities.density * (LYRICS_TEXT_SIZE_DP / LyricsTuning.BLUR_TEXT_REFERENCE_DP);
        return Math.max(0f, (sigma - LyricsTuning.BLUR_SIGMA_BIAS) / LyricsTuning.BLUR_SIGMA_SCALE);
    }

    private static int lyricsAlphaColor(int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha))));
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
        return displayLyricsLine(visibleLyrics.get(row));
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
            // Enough room for the first and the last line to reach the anchor.
            top = getLyricsAnchorY();
            bottom = Math.max(0, lyricsListView.getHeight() - top);
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
                if (lyricsViewportFade != null) {
                    FrameLayout.LayoutParams fadeParams = (FrameLayout.LayoutParams) lyricsViewportFade.getLayoutParams();
                    fadeParams.bottomMargin = dp(getPlayerHeight());
                    lyricsViewportFade.setLayoutParams(fadeParams);
                }
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
        /**
         * True when the source stated an explicit end time for this word (TTML per-span ends).
         * False for start-only sources (Enhanced LRC) where the ownership interval drives the fill.
         *
         * <p>When true, {@link #sweep} covers only the authored glyph duration, and {@link
         * #gapProgress} drives the post-word gap animation separately. When false, {@link #sweep}
         * covers the entire owned interval (glyphs + gap) as one continuous sweep.
         */
        public boolean hasExplicitWordEnd;
        /**
         * Gap-phase progress: 0 during the glyph phase (or for start-only words), then 0→1 while
         * the cursor traverses the physical whitespace between this word's glyph end and the next
         * word's visual start. Only meaningful when {@link #hasExplicitWordEnd} is true and
         * {@link #sweep} has already reached 1.
         */
        public float gapProgress;
        /**
         * Whether this word qualifies for long-note glow emphasis.
         * Gate: explicit authored end ({@link #hasExplicitWordEnd}) and
         * {@link AudioPlayerAlert#LONG_NOTE_MIN_DURATION_MS} duration.
         * Grapheme count (1..{@link AudioPlayerAlert#LONG_NOTE_MAX_GRAPHEMES}) must be verified
         * at display time from the row's layout, since the frame has no layout reference.
         */
        public boolean longNoteEligible;
        /** Duration of this word's timing window in ms; used for emphasis animation scheduling. */
        public long wordDurationMs;
        /** Absolute playback-clock start of this word in ms; used for seek-position reconstruction. */
        public long wordAbsoluteStartMs;
        /**
         * Number of live previous-word emphasis tails at this position. Each entry represents a
         * word whose conservative return envelope (pStart + 3*animMs) has not yet elapsed. Stored
         * in reverse chronological order (most-recent eligible word first).
         * This is a pure function of line timestamps and position; seeking reconstructs it exactly.
         */
        public int prevLongNoteCount;
        /** Initial capacity for previous-tail arrays; grown on demand when line structure requires. */
        public static final int PREV_LONG_NOTE_MAX = 4;
        /** Absolute start of each live previous-eligible word, ms. Index 0 = most-recent. */
        public long[] prevWordAbsoluteStartMs = new long[PREV_LONG_NOTE_MAX];
        /** Authored duration of each live previous-eligible word, ms. */
        public long[] prevWordDurationMs = new long[PREV_LONG_NOTE_MAX];
        /** UTF-16 text start offset of each live previous word in the line's text. */
        public int[] prevWordTextStart = new int[PREV_LONG_NOTE_MAX];
        /** UTF-16 text end offset of each live previous word in the line's text. */
        public int[] prevWordTextEnd = new int[PREV_LONG_NOTE_MAX];

        public void clear() {
            active = false;
            sungEnd = 0;
            wordStart = 0;
            wordEnd = 0;
            sweep = 0f;
            ownedEnd = 0;
            hasExplicitWordEnd = false;
            gapProgress = 0f;
            longNoteEligible = false;
            wordDurationMs = 0L;
            wordAbsoluteStartMs = 0L;
            prevLongNoteCount = 0;
        }

        /**
         * Grows the previous-tail arrays to at least {@code needed} entries when the current
         * capacity is smaller. Allocation happens at most once per line-structure change (driven
         * by {@code resolve()} seeing more previous words than current capacity holds), never
         * once per playback tick.
         */
        private void ensurePrevCapacity(int needed) {
            if (needed <= prevWordAbsoluteStartMs.length) return;
            // Geometric doubling so that advancing through a long line reallocates at most
            // O(log n) times, not once per word.
            final int cap = Math.max(needed, prevWordAbsoluteStartMs.length * 2);
            prevWordAbsoluteStartMs = new long[cap];
            prevWordDurationMs = new long[cap];
            prevWordTextStart = new int[cap];
            prevWordTextEnd = new int[cap];
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
            final long sweepMs = sweepWindowMs(line.text, segments, index, nextLineTimeMs);
            sweep = sweepMs <= 0 ? 1f : clamp01(elapsed / (float) sweepMs);
            // For sources with explicit per-word end times (TTML), split the visual animation into
            // two phases so the authored glyph duration is respected exactly:
            //   Phase A (sweep 0→1 during [startTime, endTime]): cursor traverses the word's own
            //   glyph clusters only.
            //   Phase B (gapProgress 0→1 during [endTime, nextStartTime]): cursor traverses the
            //   physical gap (whitespace) between this word's glyph end and the next word's start.
            // For start-only sources, gapProgress stays 0 and the single sweep covers both glyph
            // and gap clusters over the full ownership interval.
            hasExplicitWordEnd = segments.hasEndTime(index);
            gapProgress = 0f;
            if (hasExplicitWordEnd && ownedEnd > wordEnd) {
                final long wordEndTimeMs = segments.endTimeMs(index);
                if (positionMs > wordEndTimeMs) {
                    final long gapStartMs = wordEndTimeMs;
                    final long gapEndMs = (index + 1 < segments.size())
                            ? segments.startTimeMs(index + 1) : nextLineTimeMs;
                    if (gapEndMs > gapStartMs) {
                        gapProgress = clamp01(
                                (positionMs - gapStartMs) / (float) (gapEndMs - gapStartMs));
                    } else {
                        gapProgress = 1f;
                    }
                }
            }
            // Long-note emphasis eligibility. The grapheme count (1..LONG_NOTE_MAX_GRAPHEMES) is
            // validated at display time from the row's layout; here we only gate on duration.
            wordDurationMs = sweepMs > 0 ? sweepMs : ownershipWindowMs(segments, index, nextLineTimeMs);
            wordAbsoluteStartMs = start;
            longNoteEligible = hasExplicitWordEnd && wordDurationMs >= LONG_NOTE_MIN_DURATION_MS;
            // Scan backwards for previous eligible words whose return-phase envelope still contains
            // positionMs. Stored most-recent-first. A word that is expired (positionMs >=
            // pStart + 3*pAnimMs) is skipped with continue, NOT break, because an older word may
            // have a longer authored duration and therefore a later envelope end. We break only when
            // the word's pStart is so early that even the longest possible envelope (pStart +
            // 3*LONG_NOTE_MAX_ANIMATION_MS) has elapsed; any earlier word starts even sooner so
            // its envelope also ends before positionMs.
            // Ensure the arrays can hold all previous words in this line; allocation only when
            // line structure exceeds current capacity, never once per tick.
            ensurePrevCapacity(index);
            prevLongNoteCount = 0;
            for (int pi = index - 1; pi >= 0; pi--) {
                if (!segments.hasEndTime(pi)) continue;
                final long pStart = segments.startTimeMs(pi);
                final long pEnd = segments.endTimeMs(pi);
                final long pDur = pEnd - pStart;
                // Provably no earlier word can be alive past this bound.
                if (positionMs >= pStart + 3L * LONG_NOTE_MAX_ANIMATION_MS) break;
                if (pDur < LONG_NOTE_MIN_DURATION_MS) continue;
                final long pAnimMs = Math.max(1L, Math.min(pDur, LONG_NOTE_MAX_ANIMATION_MS));
                if (positionMs >= pStart + 3L * pAnimMs) continue; // this word expired; older may not have
                prevWordAbsoluteStartMs[prevLongNoteCount] = pStart;
                prevWordDurationMs[prevLongNoteCount] = pDur;
                prevWordTextStart[prevLongNoteCount] = segments.startOffset(pi);
                prevWordTextEnd[prevLongNoteCount] = Math.max(segments.startOffset(pi), segments.endOffset(pi));
                prevLongNoteCount++;
            }
            active = true;
            return true;
        }

        /**
         * Stagger step in ms for per-glyph emphasis (reference-derived, Apple Music).
         * {@code staggerStep = min(MAX_STAGGER_MS, STAGGER_FRACTION * durationMs / glyphCount)}
         */
        public static long longNoteStaggerStepMs(long durationMs, int glyphCount) {
            if (glyphCount <= 0) return 0L;
            return Math.min(400L, Math.round(0.4f * durationMs / glyphCount));
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
         * word's stated start. The last word of a line owns nothing it can be measured by, so it
         * takes {@link #lastWordWindowMs}, never past the next line's start. That is the
         * best evidence the file contains about how long the word was sung, and using it is the
         * difference between a fill that tracks the voice and one that finishes while the singer
         * is still holding the note. Only a gap so large that it cannot be describing a syllable
         * at all is bounded, by {@link #SWEEP_DERIVED_MAX_MS}.
         */
        public static long sweepWindowMs(CharSequence text, SyncedLyricsController.Segments segments, int index, long nextLineTimeMs) {
            final long start = segments.startTimeMs(index);
            if (segments.hasEndTime(index)) {
                final long stated = segments.endTimeMs(index) - start;
                if (stated > 0) return stated;
            }
            if (index + 1 >= segments.size() && text != null) {
                // The last word of a line: the source says when it starts but not when it ends.
                // Up to the next line when that is close enough to be a held note (Build 3),
                // otherwise the gap is a pause and says nothing about the word (a short "hey"
                // before a ten-second break), so it takes its letters' share of the line.
                final long gap = nextLineTimeMs != Long.MAX_VALUE ? nextLineTimeMs - start : -1;
                if (gap > 0 && gap <= LyricsTuning.LAST_WORD_HELD_MAX_MS) return gap;
                return Math.max(1L, Math.min(lastWordWindowMs(text, segments), LyricsTuning.LAST_WORD_HELD_MAX_MS));
            }
            final long bound = ownershipWindowMs(segments, index, nextLineTimeMs);
            if (bound <= 0) return SWEEP_DERIVED_FALLBACK_MS;
            return Math.min(bound, SWEEP_DERIVED_MAX_MS);
        }

        private static double[] lastWordRates = new double[16];

        /**
         * How long the last word of a line takes when nothing states its end: the typical time
         * per letter of the line's other words, times this word's letters. Ported from
         * Gramophone (SemanticLyrics.kt, the "estimate how long this word will take based on
         * character to time ratio" fallback), counting letters without whitespace.
         */
        static long lastWordWindowMs(CharSequence text, SyncedLyricsController.Segments segments) {
            final int last = segments.size() - 1;
            // Read every frame on the UI thread: a shared scratch array, grown only for a longer line.
            if (lastWordRates.length < last) lastWordRates = new double[Math.max(last, lastWordRates.length * 2)];
            final double[] rates = lastWordRates;
            int words = 0;
            for (int k = 0; k < last; k++) {
                final int chars = visibleChars(text, segments.startOffset(k), segments.endOffset(k));
                final long duration = segments.startTimeMs(k + 1) - segments.startTimeMs(k);
                if (chars <= 0 || duration <= 0) continue;
                rates[words++] = duration / (double) chars;
            }
            // The median rather than Gramophone's mean: one held note ("ooh" for two seconds) or
            // a one-letter word would otherwise stretch the estimate for the whole line.
            java.util.Arrays.sort(rates, 0, words);
            final double perChar = words == 0 ? LyricsTuning.LAST_WORD_FALLBACK_MS_PER_CHAR
                    : words % 2 == 1 ? rates[words / 2] : (rates[words / 2 - 1] + rates[words / 2]) / 2;
            final int chars = Math.max(1, visibleChars(text, segments.startOffset(last), segments.endOffset(last)));
            return Math.max(1L, Math.round(perChar * chars));
        }

        private static int visibleChars(CharSequence text, int start, int end) {
            int count = 0;
            for (int i = Math.max(0, start); i < Math.min(text.length(), end); i++) {
                if (!Character.isWhitespace(text.charAt(i))) count++;
            }
            return count;
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
     * <p>A word-timed row draws its own text (see {@link #onDraw}) so words can lift and swell,
     * but only as whole-glyph drawTextRun calls at the Layout's own positions: nothing is clipped,
     * split into strips, or rasterised twice, and every other row is drawn by TextView itself.
     * Karaoke colour is expressed as appearance state on {@link KaraokeSpan}s, and a frame is
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
        /**
         * Plain-string snapshot of {@code karaokeText}, cached once in {@link #setLyricText} so
         * that {@link #onDraw} can call {@code drawTextRun} without allocating a String
         * per frame. Null when {@code karaokeText} is null.
         */
        private String karaokeTextStr;

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
        /** See {@link KaraokeFrame#hasExplicitWordEnd}. */
        private boolean wordHasExplicitEnd;
        /** See {@link KaraokeFrame#gapProgress}. */
        private float wordGapProgress;
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
        // --- fill edge (AMLL mask) ------------------------------------------------------------
        /** Soft edge width, LyricsTuning.FILL_FADE_WIDTH of the font's ascent-to-descent height. */
        private float fadeWidthPx;
        private final Paint.FontMetrics fillMetrics = new Paint.FontMetrics();
        /** First and last non-whitespace offsets of the text, for the first/last word padding. */
        private int trimmedStart;
        private int trimmedEnd;
        /** One shared edge gradient, placed by its local matrix; rebuilt only for new colours. */
        private LinearGradient fillShader;
        private int fillShaderSung;
        private int fillShaderMuted;
        private final Matrix fillMatrix = new Matrix();

        // --- words: lexical words of the line, their timing, lift and emphasis ---------------
        private int wordCount;
        private int[] wordTextStart = new int[8];
        private int[] wordTextEnd = new int[8];
        private long[] wordStartMs = new long[8];
        private long[] wordEndMs = new long[8];
        /** Current lift of each word, px; whole pixels at rest, sub-pixel while moving. */
        private float[] wordLiftPx = new float[8];
        private boolean[] wordEmphasis = new boolean[8];
        /** True while the word's emphasis is between its first and last frame. */
        private boolean[] wordEmphasisLive = new boolean[8];
        private int[] wordGraphemes = new int[8];
        private float[] wordAmount = new float[8];
        private float[] wordGlow = new float[8];
        private long[] wordEmphasisMs = new long[8];
        /** When the line stops being active; lifts reverse from there. */
        private long lineActiveEndMs = Long.MAX_VALUE;
        private long wordClockMs;
        /** False until the first clock of this bind, so a fresh row never blends from nothing. */
        private boolean wordClockSet;
        /** Seek crossfade: lift and emphasis blend from their state at blendFromMs. */
        private long blendFromMs;
        private long blendStartNanos;
        private float blendWeight = 1f;
        private boolean wordsMapped;
        /** Word of each grapheme (-1 outside a word), and its index among the word's graphemes. */
        private int[] clusterWord = new int[32];
        private int[] clusterWordIndex = new int[32];

        // --- draw pieces: one visual run cut at word boundaries, rebuilt with the layout ----
        private int pieceCount;
        private int[] pieceStart = new int[16];
        private int[] pieceEnd = new int[16];
        private int[] pieceRun = new int[16];
        private int[] pieceWord = new int[16];
        private float[] pieceLeft = new float[16];
        private float[] pieceRight = new float[16];
        private int[] runLine = new int[4];
        private boolean[] runRtl = new boolean[4];
        private final TextPaint drawPaint = new TextPaint();
        /** The emphasis glow's blur, made once per radius (the radius only follows the text size). */
        private BlurMaskFilter glowFilter;
        private float glowFilterRadius;
        private float glowAlpha;
        /** Offset where the background vocals line starts, or MAX_VALUE when there is none. */
        private int backgroundStart = Integer.MAX_VALUE;
        /** How much of the background vocals line shows: the row's focus (hidden while inactive). */
        private float backgroundVisibility = 1f;
        private BackgroundVocalsSpan backgroundSpan;
        /**
         * Sub-pixel motion. Text drawn straight onto a canvas is snapped to whole pixels (Skia
         * rounds glyph positions, and y always), so a word rising 3 px moved in three 1 px jumps,
         * and an emphasised letter that swells, spreads and floats jittered by up to half a pixel
         * every frame and was re-rasterised at a new size each time. Anything moving by a fraction
         * of a pixel is therefore drawn into its own small layer, at rest and at full size, and the
         * layer is moved and scaled, which the GPU filters smoothly. One node per word piece and
         * one per emphasised grapheme, reused across frames.
         */
        private RenderNode[] pieceNodes = new RenderNode[0];
        private RenderNode[] clusterNodes = new RenderNode[0];
        /** Room around a node's glyphs for overhangs and the emphasis glow, px. */
        private int nodePad;
        private final float[] emphasisA = new float[4];
        private final float[] emphasisB = new float[4];


        LyricsTextView(Context context) {
            super(context);
            // Every row is its own GPU layer: its scale, its sub-pixel translation and its blur are
            // applied to the layer, so the text inside is rasterised once, at full size, and moves
            // and scales smoothly. Drawn straight, a row scaling between 0.97 and 1 re-rasterised
            // its glyphs at every intermediate size and each letter snapped to its own pixel, so
            // the line shimmered through every scale change, and a row without blur (the active
            // one) moved in whole pixels while its blurred neighbours moved smoothly.
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        /**
         * Binds the row's text. Only a line that genuinely states inline timing is kept spannable;
         * every other row is a plain string, exactly as before. A recycled row is fully reset here,
         * so it can never keep a previous line's word state.
         */
        void setLyricText(CharSequence text, boolean wordTimed) {
            detachSpans();
            karaokeActive = false;
            // A recycled row keeps no word, lift or emphasis of the line it showed before.
            wordCount = 0;
            wordsMapped = false;
            pieceCount = 0;
            lineActiveEndMs = Long.MAX_VALUE;
            wordClockSet = false;
            blendStartNanos = 0;
            blendWeight = 1f;
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
            // Plain-string snapshot for drawTextRun, so drawing allocates nothing per frame.
            karaokeTextStr = karaokeText != null ? karaokeText.toString() : null;
            final int lineBreak = karaokeTextStr != null ? karaokeTextStr.indexOf('\n') : -1;
            backgroundStart = lineBreak >= 0 ? lineBreak + 1 : Integer.MAX_VALUE;
            // The span is shared by TextView's copy of the text, so one field reaches it.
            final CharSequence shown = getText();
            final BackgroundVocalsSpan[] found = shown instanceof Spanned
                    ? ((Spanned) shown).getSpans(0, shown.length(), BackgroundVocalsSpan.class) : null;
            backgroundSpan = found != null && found.length > 0 ? found[0] : null;
            if (backgroundSpan != null) backgroundSpan.visibility = backgroundVisibility;
            trimmedStart = 0;
            trimmedEnd = 0;
            if (karaokeTextStr != null) {
                int a = 0, b = karaokeTextStr.length();
                while (a < b && Character.isWhitespace(karaokeTextStr.charAt(a))) a++;
                while (b > a && Character.isWhitespace(karaokeTextStr.charAt(b - 1))) b--;
                trimmedStart = a;
                trimmedEnd = b;
            }
        }

        /**
         * Shows the background vocals line in proportion to {@code visibility}. Apple Music and
         * AMLL keep it hidden until its line is active; its space stays reserved here, since
         * collapsing it would change the row's height (a metric) while the list moves.
         */
        void setBackgroundVisibility(float visibility) {
            visibility = Math.max(0f, Math.min(1f, visibility));
            if (visibility == backgroundVisibility) return;
            backgroundVisibility = visibility;
            if (backgroundSpan != null) {
                backgroundSpan.visibility = visibility;
                invalidate();
            }
        }

        /** Sets the text colour without the ColorStateList a repeated call would allocate. */
        void setLyricTextColor(int color) {
            if (lyricTextColorSet && lyricTextColor == color) return;
            lyricTextColor = color;
            lyricTextColorSet = true;
            setTextColor(color);
        }

        /**
         * Binds the line's lexical words: syllables with no whitespace between them are one word
         * (AMLL groups them the same way). A word runs from its first syllable's start to its
         * last syllable's end - the stated end, or the derived fill window for start-only timing.
         * {@code activeEndMs} is when the line stops being active, which is when lifts reverse.
         */
        void setWordTiming(SyncedLyricsController.Line line, long activeEndMs, long nextLineTimeMs) {
            wordCount = 0;
            wordsMapped = false;
            lineActiveEndMs = activeEndMs;
            // Draw pieces are cut at word boundaries: the next geometry pass rebuilds them.
            clusterLayout = null;
            pieceCount = 0;
            if (karaokeTextStr == null || line == null || line.segments == null) return;
            final SyncedLyricsController.Segments segments = line.segments;
            final String text = karaokeTextStr;
            final int n = segments.size();
            ensureWordCapacity(n);
            int i = 0;
            while (i < n) {
                int last = i;
                while (last + 1 < n && KaraokeFrame.isWordContinuation(text, segments, last + 1)) last++;
                int start = Math.max(0, Math.min(text.length(), segments.startOffset(i)));
                int end = Math.max(start, Math.min(text.length(), segments.endOffset(last)));
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                if (end > start) {
                    final long startMs = segments.startTimeMs(i);
                    final long endMs = segments.startTimeMs(last) + KaraokeFrame.sweepWindowMs(text, segments, last, nextLineTimeMs);
                    wordTextStart[wordCount] = start;
                    wordTextEnd[wordCount] = end;
                    wordStartMs[wordCount] = startMs;
                    wordEndMs[wordCount] = Math.max(startMs, endMs);
                    wordLiftPx[wordCount] = 0;
                    wordEmphasis[wordCount] = false;
                    wordEmphasisLive[wordCount] = false;
                    wordCount++;
                }
                i = last + 1;
            }
        }

        private void ensureWordCapacity(int size) {
            if (size <= wordTextStart.length) return;
            final int grown = Math.max(size, wordTextStart.length * 2);
            wordTextStart = java.util.Arrays.copyOf(wordTextStart, grown);
            wordTextEnd = java.util.Arrays.copyOf(wordTextEnd, grown);
            wordStartMs = java.util.Arrays.copyOf(wordStartMs, grown);
            wordEndMs = java.util.Arrays.copyOf(wordEndMs, grown);
            wordLiftPx = java.util.Arrays.copyOf(wordLiftPx, grown);
            wordEmphasis = java.util.Arrays.copyOf(wordEmphasis, grown);
            wordEmphasisLive = java.util.Arrays.copyOf(wordEmphasisLive, grown);
            wordGraphemes = java.util.Arrays.copyOf(wordGraphemes, grown);
            wordAmount = java.util.Arrays.copyOf(wordAmount, grown);
            wordGlow = java.util.Arrays.copyOf(wordGlow, grown);
            wordEmphasisMs = java.util.Arrays.copyOf(wordEmphasisMs, grown);
        }

        /**
         * Maps graphemes to words and decides which words are emphasised (AMLL
         * LyricLineBase.shouldEmphasize and calculateEmphasizeParams). Needs the graphemes, so it
         * runs once they exist, once per bind.
         */
        private void mapWords() {
            if (wordsMapped) return;
            ensureClusters();
            if (clusterCount == 0 && karaokeTextStr != null && karaokeTextStr.length() > 0) return;
            wordsMapped = true;
            for (int i = 0; i < wordCount; i++) wordGraphemes[i] = 0;
            if (clusterWord.length < clusterCount) {
                clusterWord = new int[clusterStart.length];
                clusterWordIndex = new int[clusterStart.length];
            }
            int w = 0;
            for (int c = 0; c < clusterCount; c++) {
                final int offset = clusterStart[c];
                while (w < wordCount && wordTextEnd[w] <= offset) w++;
                if (w < wordCount && offset >= wordTextStart[w] && !isBlankCluster(c)) {
                    clusterWord[c] = w;
                    clusterWordIndex[c] = wordGraphemes[w];
                } else {
                    clusterWord[c] = -1;
                    clusterWordIndex[c] = -1;
                }
                if (clusterWord[c] >= 0) wordGraphemes[w]++;
            }
            for (int i = 0; i < wordCount; i++) {
                final long duration = wordEndMs[i] - wordStartMs[i];
                final int graphemes = wordGraphemes[i];
                final boolean cjk = isCjk(karaokeTextStr, wordTextStart[i], wordTextEnd[i]);
                // YouLy+ isGroupGrowable: long enough, short enough, and not background vocals.
                wordEmphasis[i] = duration >= LyricsTuning.EMPHASIS_MIN_DURATION_MS
                        && wordTextStart[i] < backgroundStart && (cjk
                        ? graphemes >= 1
                        : graphemes >= LyricsTuning.EMPHASIS_MIN_GRAPHEMES && graphemes <= LyricsTuning.EMPHASIS_MAX_GRAPHEMES);
                if (!wordEmphasis[i]) continue;
                // YouLy+ calculateEmphasisMetrics / applyGrowthStyles: every qualifying word gets
                // a clearly visible base swell and glow, and longer ones add more on a cubic.
                float p = (duration - LyricsTuning.EMPHASIS_MIN_DURATION_MS)
                        / (float) (LyricsTuning.EMPHASIS_FULL_DURATION_MS - LyricsTuning.EMPHASIS_MIN_DURATION_MS);
                p = (float) Math.pow(Math.max(0f, Math.min(1f, p)), LyricsTuning.EMPHASIS_RAMP_POWER);
                final float base = graphemes <= LyricsTuning.EMPHASIS_SHORT_GRAPHEMES
                        ? LyricsTuning.EMPHASIS_SWELL_SHORT_BASE : LyricsTuning.EMPHASIS_SWELL_BASE;
                wordAmount[i] = base + p * LyricsTuning.EMPHASIS_SWELL_RAMP;
                wordGlow[i] = LyricsTuning.EMPHASIS_GLOW_BASE + p * LyricsTuning.EMPHASIS_GLOW_RAMP;
                wordEmphasisMs[i] = duration;
            }
        }

        /** AMLL isCJK: every code point a unified ideograph or in U+0800..U+9FFC. */
        private static boolean isCjk(String text, int start, int end) {
            if (end <= start) return false;
            for (int i = start; i < end; ) {
                final int cp = text.codePointAt(i);
                if (!Character.isIdeographic(cp) && (cp < 0x0800 || cp > 0x9FFC)) return false;
                i += Character.charCount(cp);
            }
            return true;
        }

        /**
         * Pushes the playback position. Every word's lift and emphasis is a pure function of it
         * and of the line's own timing, so a seek or a pause lands exactly on the right frame.
         * When the position jumps (a seek), the two blend from their old state to the new one
         * over SEEK_BLEND_MS instead of popping, and are exactly the pure function again once the
         * blend is done. The row repaints only when a lift moved or an emphasis is running.
         */
        void setWordClock(long positionMs, long nowNanos) {
            if (wordCount == 0) return;
            mapWords();
            if (wordClockSet && Math.abs(positionMs - wordClockMs) > LyricsTuning.SEEK_JITTER_TOLERANCE_MS) {
                // A second jump during a blend keeps whichever side is showing more.
                if (blendWeight >= 0.5f) blendFromMs = wordClockMs;
                blendStartNanos = nowNanos;
            }
            wordClockSet = true;
            wordClockMs = positionMs;
            final float previousBlend = blendWeight;
            blendWeight = seekBlend(nowNanos);
            final boolean blending = blendWeight < 1f;
            final float maxLift = Math.round(LyricsTuning.LIFT_EM * LyricsTuning.LIFT_MULTIPLIER * getTextSize());
            // Background vocals rise relative to their own smaller size, twice as far (AMLL).
            final float maxBackgroundLift = Math.round(LyricsTuning.LIFT_EM * LyricsTuning.LIFT_MULTIPLIER * getTextSize()
                    * LyricsTuning.BACKGROUND_VOCALS_SCALE * LyricsTuning.LIFT_BACKGROUND_MULTIPLIER);
            boolean changed = blending || previousBlend < 1f;
            for (int w = 0; w < wordCount; w++) {
                float lift = wordLift(w, positionMs);
                if (blending) lift = lerp(wordLift(w, blendFromMs), lift, blendWeight);
                final float px = lift * (wordTextStart[w] >= backgroundStart ? maxBackgroundLift : maxLift);
                if (px != wordLiftPx[w]) {
                    wordLiftPx[w] = px;
                    changed = true;
                }
                if (wordEmphasis[w]) {
                    final boolean live = emphasisLive(w, positionMs) || blending && emphasisLive(w, blendFromMs);
                    if (live || wordEmphasisLive[w]) changed = true;
                    wordEmphasisLive[w] = live;
                }
            }
            if (changed) invalidate();
        }

        /** 0 right after a jump, rising to 1 along a critically damped curve over SEEK_BLEND_MS. */
        private float seekBlend(long nowNanos) {
            if (blendStartNanos == 0) return 1f;
            final float t = (nowNanos - blendStartNanos) / 1e6f;
            if (t >= LyricsTuning.SEEK_BLEND_MS) {
                blendStartNanos = 0;
                return 1f;
            }
            if (t <= 0f) return 0f;
            final float omega = 5.83f / LyricsTuning.SEEK_BLEND_MS;
            return 1f - (1f + omega * t) * (float) Math.exp(-omega * t);
        }

        /**
         * AMLL createFloatAnimation: 0..1 over max(LIFT_MIN_DURATION_MS, word duration) from the
         * word's start, ease-out, held. From the moment the line stops being active the word sinks
         * back over max(LIFT_FALL_MIN_MS, the time it spent rising), starting at the speed it was
         * rising with (a cubic Hermite), so a word still rising when the line ends turns over
         * smoothly instead of reversing on the spot.
         */
        private float wordLift(int w, long p) {
            final long start = wordStartMs[w];
            final long duration = Math.max(LyricsTuning.LIFT_MIN_DURATION_MS, wordEndMs[w] - start);
            if (p < lineActiveEndMs) {
                final long elapsed = p - start;
                if (elapsed <= 0) return 0f;
                // Exactly 1 at the top, so a risen word rests on a whole pixel.
                if (elapsed >= duration) return 1f;
                return CubicBezierInterpolator.EASE_OUT.getInterpolation(elapsed / (float) duration);
            }
            final long rose = Math.max(0L, Math.min(duration, lineActiveEndMs - start));
            if (rose <= 0) return 0f;
            final float u0 = rose / (float) duration;
            final float top = u0 >= 1f ? 1f : CubicBezierInterpolator.EASE_OUT.getInterpolation(u0);
            final float fall = Math.max(LyricsTuning.LIFT_FALL_MIN_MS, rose);
            final float u = (p - lineActiveEndMs) / fall;
            if (u >= 1f) return 0f;
            // Rising speed at the turn, in lift per unit of the fall.
            final float h = 0.002f;
            final float a = Math.max(0f, u0 - h), b = Math.min(1f, u0 + h);
            final float slope = b > a ? (CubicBezierInterpolator.EASE_OUT.getInterpolation(b)
                    - CubicBezierInterpolator.EASE_OUT.getInterpolation(a)) / (b - a) * (fall / duration) : 0f;
            final float u2 = u * u, u3 = u2 * u;
            return Math.max(0f, (2f * u3 - 3f * u2 + 1f) * top + (u3 - 2f * u2 + u) * Math.max(0f, slope));
        }

        private long emphasisStepMs(int w) {
            return Math.round(wordEmphasisMs[w] / LyricsTuning.EMPHASIS_STAGGER_DIVISOR / Math.max(1, wordGraphemes[w]));
        }

        private boolean emphasisLive(int w, long p) {
            final long first = wordStartMs[w] - LyricsTuning.EMPHASIS_FLOAT_LEAD_MS;
            final long last = wordStartMs[w] + emphasisStepMs(w) * Math.max(0, wordGraphemes[w] - 1)
                    + (long) (wordEmphasisMs[w] * Math.max(1f, LyricsTuning.EMPHASIS_FLOAT_STRETCH));
            return p >= first && p <= last;
        }

        /**
         * AMLL makeEmpEasing(0.5): up along cubic-bezier(0.2, 0.4, 0.58, 1) in the first half,
         * back down along 1 - cubic-bezier(0.3, 0, 0.58, 1) in the second.
         */
        private static float emphasisEase(float x) {
            if (x <= 0f || x >= 1f) return 0f;
            if (x < 0.5f) return EMPHASIS_IN.getInterpolation(x / 0.5f);
            return 1f - EMPHASIS_OUT.getInterpolation((x - 0.5f) / 0.5f);
        }

        private static final CubicBezierInterpolator EMPHASIS_IN = new CubicBezierInterpolator(0.2, 0.4, 0.58, 1.0);
        private static final CubicBezierInterpolator EMPHASIS_OUT = new CubicBezierInterpolator(0.3, 0.0, 0.58, 1.0);

        /**
         * Counts grapheme clusters in [{@code start}, {@code end}) whose start codepoint is not
         * an ignorable inter-word spacing character. Excludes ASCII space, Unicode whitespace
         * (isWhitespace), and all Unicode Zs general-category members (isSpaceChar), covering
         * NBSP U+00A0, narrow NBSP U+202F, figure space U+2007, and similar, so that segment
         * ranges that extend to the next word's start do not inflate the eligibility count.
         */
        int graphemeCountInRange(int start, int end) {
            if (clusterCount == 0 || end <= start || karaokeText == null) return 0;
            int count = 0;
            for (int i = 0; i < clusterCount; i++) {
                final int offset = clusterStart[i];
                if (offset < start) continue;
                if (offset >= end) break;
                if (isIgnorableInterWordSpace(karaokeText, offset)) continue;
                count++;
            }
            return count;
        }

        /**
         * Returns true when the character (or codepoint) at {@code charOffset} is an ignorable
         * inter-word spacing character and must not count as a visible grapheme for long-note
         * eligibility. Covers both {@link Character#isWhitespace} (C0/ASCII) and
         * {@link Character#isSpaceChar} (Unicode Zs category: NBSP U+00A0, narrow NBSP U+202F,
         * figure space U+2007, etc.).  Uses codePoint to handle surrogate pairs correctly.
         */
        private static boolean isIgnorableInterWordSpace(CharSequence text, int charOffset) {
            if (charOffset >= text.length()) return false;
            final int cp = Character.codePointAt(text, charOffset);
            return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
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
        void setKaraokeFrame(int start, int end, float sweepProgress, int ownedEnd,
                             boolean hasExplicitEnd, float gapProgress) {
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
            if (wordHasExplicitEnd != hasExplicitEnd) {
                wordHasExplicitEnd = hasExplicitEnd;
                changed = true;
            }
            if (wordGapProgress != gapProgress) {
                wordGapProgress = gapProgress;
                changed = true;
            }
            // No threshold: a skipped small step became a visible jump of the fill edge on a wide
            // word, so the edge advanced unevenly.
            if (sweep != sweepProgress) {
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

            // The gap end (ownedEnd) is clamped to the same visual line as wordStart, so the cursor
            // never crosses a line-wrap boundary whether it is traversing glyphs or the trailing gap.
            final int effectiveGapEnd;
            if (wordOwnedEnd > wordEnd) {
                final Layout layout = getLayout();
                if (layout != null && wordStart >= 0 && wordStart < karaokeText.length()) {
                    final int visualLine = layout.getLineForOffset(wordStart);
                    effectiveGapEnd = Math.min(wordOwnedEnd, layout.getLineEnd(visualLine));
                } else {
                    effectiveGapEnd = wordOwnedEnd;
                }
            } else {
                effectiveGapEnd = wordEnd;
            }

            // For sources with explicit word-end times (TTML), presentation is two phases:
            //   Phase A (gapProgress == 0): sweep covers the word's own glyph clusters only.
            //   Phase B (gapProgress > 0): glyph portion fully sung; gapProgress covers the gap.
            // For start-only sources (hasExplicitEnd == false), a single sweep covers the entire
            // owned span (glyphs + gap), reproducing the pre-Phase-B behavior exactly.
            final boolean inGapPhase = wordHasExplicitEnd && wordGapProgress > 0f;
            // During the glyph phase of an explicit-end word the traversal stops at wordEnd;
            // for start-only words it extends to the owned gap end.
            final int effectiveOwnedEnd = (wordHasExplicitEnd && !inGapPhase) ? wordEnd : effectiveGapEnd;

            if (wordEnd <= wordStart) {
                sungTo = wordTo = wordStart;
            } else if (inGapPhase) {
                // ── Gap phase: glyph clusters fully sung; animate gap clusters by gapProgress ──
                if (wordGapProgress >= 1f || effectiveGapEnd <= wordEnd) {
                    sungTo = wordTo = effectiveGapEnd;
                } else if (clusterGeometryCount != clusterCount || clusterCount == 0) {
                    // Layout not yet measured; hold cursor at word end until geometry arrives.
                    sungTo = wordTo = wordEnd;
                } else {
                    // Sum the physical width of all gap clusters (spaces between words).
                    float gapTotal = 0f;
                    for (int i = 0; i < clusterCount; i++) {
                        final int offset = clusterStart[i];
                        if (offset < wordEnd) continue;
                        if (offset >= effectiveGapEnd) break;
                        if (!clusterHasRect[i]) continue;
                        gapTotal += clusterRight[i] - clusterLeft[i];
                    }
                    if (gapTotal <= 0f) {
                        // No gap geometry on this visual line; snap cursor to gap end.
                        sungTo = wordTo = effectiveGapEnd;
                    } else {
                        final float reveal = wordGapProgress * gapTotal;
                        float consumed = 0f;
                        for (int i = 0; i < clusterCount; i++) {
                            final int offset = clusterStart[i];
                            if (offset < wordEnd) continue;
                            if (offset >= effectiveGapEnd) break;
                            if (!clusterHasRect[i]) continue;
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
                            sungTo = wordTo = effectiveGapEnd;
                        } else {
                            sungTo = clusterStart[front];
                            wordTo = (front + 1 < clusterCount)
                                    ? clusterStart[front + 1] : effectiveGapEnd;
                        }
                    }
                }
            } else if (sweep >= 1f) {
                // Word complete: for explicit-end glyph phase this reaches wordEnd;
                // for start-only this reaches the full ownership end.
                sungTo = wordTo = effectiveOwnedEnd;
            } else if (clusterGeometryCount != clusterCount || clusterCount == 0) {
                // Not laid out yet. The word reads as still to come, and the next tick — by which
                // time there is a layout — puts the fill where the clock says it is.
                sungTo = wordTo = wordStart;
            } else {
                // ── Glyph phase (or start-only single sweep) ──
                // For start-only words: effectiveOwnedEnd == effectiveGapEnd, so blank clusters
                // in the gap range are included (isBlankCluster guard only covers offset < wordEnd).
                // For explicit-end glyph phase: effectiveOwnedEnd == wordEnd, so the loop cannot
                // reach gap clusters regardless of the blank cluster guard.
                float total = 0f;
                for (int i = 0; i < clusterCount; i++) {
                    final int offset = clusterStart[i];
                    if (offset < wordStart) continue;
                    if (offset >= effectiveOwnedEnd) break;
                    if (!clusterHasRect[i]) continue;
                    // Within the word's own glyph range, skip blank clusters.
                    // Beyond wordEnd (start-only gap range), include them.
                    if (isBlankCluster(i) && offset < wordEnd) continue;
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
                        if (!clusterHasRect[i]) continue;
                        if (isBlankCluster(i) && offset < wordEnd) continue;
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
                        wordTo = (front + 1 < clusterCount)
                                ? clusterStart[front + 1] : effectiveOwnedEnd;
                    }
                }
            }
            boolean changed = false;
            if (front != frontCluster || revealed != frontRevealed) {
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
            final boolean rtl = boundary >= 0 && clusterRtl[boundary];
            float cut = boundaryX(boundary);
            if (!Float.isNaN(cut)) cut += (rtl ? -1f : 1f) * fillPadding();
            for (int r = 0; r < runCount; r++) {
                final KaraokeSpan span = runSpans[r];
                if (runEnd[r] <= spanSungTo) {
                    span.setSolid(sungColor);                 // wholly sung
                } else if (runStart[r] >= spanWordTo) {
                    span.setSolid(mutedColor);                // not reached yet
                } else {
                    final Shader gradient = fillShader(cut, rtl);
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
                // Word-local glow disabled: visual runs span the whole line in single-run LTR
                // text, so run-wide glow would cover non-active words. The canvas overlay in
                // onDraw() draws the active-word brightness highlight at precise cluster bounds.
                span.setGlow(0f, 0);
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
         * AMLL's first- and last-word padding: the edge starts FILL_FIRST_WORD_PAD edge widths
         * before the first word, so the line fades in, and the last word travels
         * FILL_LAST_WORD_PAD further, so the line finishes fully lit. Signed in reading direction.
         */
        private float fillPadding() {
            final float s = wordEnd <= wordStart ? 0f
                    : (wordHasExplicitEnd && wordGapProgress > 0f) ? 1f : sweep;
            float pad = 0f;
            if (wordStart <= trimmedStart) pad -= LyricsTuning.FILL_FIRST_WORD_PAD * fadeWidthPx * (1f - s);
            if (wordEnd > wordStart && (wordOwnedEnd >= trimmedEnd || wordEnd >= trimmedEnd)) {
                pad += LyricsTuning.FILL_LAST_WORD_PAD * fadeWidthPx * s;
            }
            return pad;
        }

        /**
         * The soft fill edge (AMLL generateFadeGradient): ALPHA_SUNG up to the fill front, easing
         * to ALPHA_UNSUNG one edge width ahead of it in reading order, clamped on both sides. One
         * gradient per colour pair, moved by its local matrix, so a frame allocates nothing. The
         * coordinates are the row's Layout coordinates, the space the text is drawn in.
         */
        private Shader fillShader(float cut, boolean rtl) {
            if (Float.isNaN(cut) || fadeWidthPx <= 0f) return null;
            if (fillShader == null || fillShaderSung != sungColor || fillShaderMuted != mutedColor) {
                fillShader = new LinearGradient(0f, 0f, 1f, 0f, sungColor, mutedColor, Shader.TileMode.CLAMP);
                fillShaderSung = sungColor;
                fillShaderMuted = mutedColor;
            }
            fillMatrix.setScale(rtl ? -fadeWidthPx : fadeWidthPx, 1f);
            fillMatrix.postTranslate(cut, 0f);
            fillShader.setLocalMatrix(fillMatrix);
            return fillShader;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            // A new layout moves every grapheme, so the fill front has to be found again. Doing it
            // here rather than in onDraw keeps span changes out of the draw pass.
            if (karaokeActive && resolveColourBoundaries()) invalidate();
        }

        /**
         * Word-timed rows draw their own text, so each lexical word can be lifted and an
         * emphasised word's graphemes can swell and glow. Every glyph is still drawn exactly once:
         * the text is cut into pieces at visual-run and word boundaries, each piece is one
         * {@code drawTextRun} with its whole visual line as shaping context (so kerning, contextual
         * forms and bidi match the Layout's own draw), placed at the Layout's own x. Colour comes
         * from the run's {@link KaraokeSpan}, exactly as the platform would apply it. Nothing here
         * changes a metric: size, typeface and line breaks are the Layout's. Any row that is not
         * ready (no layout, no geometry) is drawn by TextView itself.
         */
        @Override
        protected void onDraw(Canvas canvas) {
            final Layout layout = getLayout();
            if (!karaokeActive || karaokeTextStr == null || layout == null || layout != clusterLayout
                    || pieceCount == 0 || clusterGeometryCount != clusterCount) {
                super.onDraw(canvas);
                return;
            }
            // The transform TextView applies before Layout.draw(), CENTER_VERTICAL included.
            final int compTop = getCompoundPaddingTop();
            final int compBottom = getCompoundPaddingBottom();
            final int boxHeight = getMeasuredHeight() - compTop - compBottom;
            final int layoutHeight = layout.getHeight();
            final int voffset = layoutHeight < boxHeight ? (boxHeight - layoutHeight) >> 1 : 0;
            canvas.save();
            canvas.translate(getCompoundPaddingLeft() - getScrollX(), getExtendedPaddingTop() + voffset - getScrollY());
            final TextPaint base = layout.getPaint();
            final boolean layers = canvas.isHardwareAccelerated();
            // With background vocals, text order is no longer singing order (a part in
            // parentheses may be sung first yet sits on the second line), so the words other than
            // the one being filled take their colour from their own time rather than from their
            // side of the fill.
            final boolean timeColoured = backgroundStart < karaokeTextStr.length();
            final int singing = timeColoured ? wordAtOffset(wordStart) : -1;
            for (int p = 0; p < pieceCount; p++) {
                final int r = pieceRun[p];
                final int w = pieceWord[p];
                drawPaint.set(base);
                runSpans[r].updateDrawState(drawPaint);
                if (timeColoured && w >= 0 && w != singing) {
                    drawPaint.setShader(null);
                    drawPaint.setColor(wordStartMs[w] <= wordClockMs ? sungColor : mutedColor);
                }
                if (runStart[r] >= backgroundStart) {
                    // The layout set this line smaller (RelativeSizeSpan); draw it at that size.
                    drawPaint.setTextSize(base.getTextSize() * LyricsTuning.BACKGROUND_VOCALS_SCALE);
                    drawPaint.setAlpha(Math.round(drawPaint.getAlpha() * LyricsTuning.BACKGROUND_VOCALS_ALPHA * backgroundVisibility));
                }
                final int line = runLine[r];
                final int contextStart = layout.getLineStart(line);
                final int contextEnd = layout.getLineEnd(line);
                final float baseline = layout.getLineBaseline(line);
                final float lift = w >= 0 ? wordLiftPx[w] : 0f;
                if (w >= 0 && wordEmphasisLive[w]) {
                    drawEmphasisPiece(canvas, p, w, line, contextStart, contextEnd, baseline, lift, runRtl[r], layers);
                } else if (layers && lift != (float) Math.floor(lift)) {
                    // A word part-way through its rise: its own layer, moved by a fraction of a pixel.
                    glowAlpha = 0f;
                    drawInNode(canvas, pieceNode(p), pieceStart[p], pieceEnd[p], contextStart, contextEnd,
                            pieceLeft[p], pieceRight[p], line, baseline, runRtl[r], 0f, -lift, 1f, 0f, 0f);
                } else {
                    canvas.drawTextRun(karaokeTextStr, pieceStart[p], pieceEnd[p], contextStart, contextEnd,
                            pieceLeft[p], baseline - Math.round(lift), runRtl[r], drawPaint);
                }
            }
            canvas.restore();
        }

        /** The word containing text offset {@code offset}, or -1. */
        private int wordAtOffset(int offset) {
            for (int w = 0; w < wordCount; w++) {
                if (offset >= wordTextStart[w] && offset < wordTextEnd[w]) return w;
            }
            return -1;
        }

        /**
         * Draws [start, end) into {@code node} exactly where drawTextRun would put it at rest (the
         * node sits on whole pixels, so the glyphs keep their pixel phase), then draws the node
         * moved by (tx, ty) and scaled about (pivotX, pivotY). The node is a compositing layer, so
         * the motion is applied to the finished glyphs, filtered, never re-rasterised.
         */
        private void drawInNode(Canvas canvas, RenderNode node, int start, int end, int contextStart, int contextEnd,
                                float x, float right, int line, float baseline, boolean rtl,
                                float tx, float ty, float scale, float pivotX, float pivotY) {
            final Layout layout = getLayout();
            final int left = (int) Math.floor(x) - nodePad;
            final int top = layout.getLineTop(line) - nodePad;
            final int rightPx = (int) Math.ceil(right) + nodePad;
            final int bottom = layout.getLineBottom(line) + nodePad;
            node.setPosition(left, top, rightPx, bottom);
            final Canvas recording = node.beginRecording();
            try {
                recording.translate(-left, -top);
                if (glowAlpha > 0f) drawGlow(recording, start, end, contextStart, contextEnd, x, baseline, rtl);
                recording.drawTextRun(karaokeTextStr, start, end, contextStart, contextEnd, x, baseline, rtl, drawPaint);
            } finally {
                node.endRecording();
            }
            node.setTranslationX(tx);
            node.setTranslationY(ty);
            node.setScaleX(scale);
            node.setScaleY(scale);
            node.setPivotX(pivotX - left);
            node.setPivotY(pivotY - top);
            canvas.drawRenderNode(node);
        }

        private RenderNode pieceNode(int p) {
            if (pieceNodes.length < pieceStart.length) pieceNodes = java.util.Arrays.copyOf(pieceNodes, pieceStart.length);
            if (pieceNodes[p] == null) pieceNodes[p] = newGlyphNode();
            return pieceNodes[p];
        }

        private RenderNode clusterNode(int c) {
            if (clusterNodes.length < clusterStart.length) clusterNodes = java.util.Arrays.copyOf(clusterNodes, clusterStart.length);
            if (clusterNodes[c] == null) clusterNodes[c] = newGlyphNode();
            return clusterNodes[c];
        }

        private static RenderNode newGlyphNode() {
            final RenderNode node = new RenderNode("LyricsGlyphs");
            node.setUseCompositingLayer(true, null);
            return node;
        }

        /**
         * AMLL createEmphasizeAnimation timing with YouLy+ strength, per grapheme: a swell, a push
         * away from the word's middle, a small rise, a white glow, and an extra sin-shaped float
         * that starts EMPHASIS_FLOAT_LEAD_MS early. Each grapheme is drawn once, at full size, into
         * its own layer, and the layer carries the motion. During a seek crossfade the state is
         * blended between the old position and the new one.
         */
        private void drawEmphasisPiece(Canvas canvas, int p, int w, int line, int contextStart, int contextEnd,
                                       float baseline, float lift, boolean rtl, boolean layers) {
            final float em = getTextSize();
            final boolean blending = blendWeight < 1f;
            final float glyphMiddle = baseline + (fillMetrics.ascent + fillMetrics.descent) / 2f;
            for (int c = 0; c < clusterCount; c++) {
                final int start = clusterStart[c];
                if (start < pieceStart[p]) continue;
                if (start >= pieceEnd[p]) break;
                final int end = Math.min(clusterStart[c + 1], pieceEnd[p]);
                if (!clusterHasRect[c]) continue;
                final int i = clusterWordIndex[c];
                if (i < 0) {
                    canvas.drawTextRun(karaokeTextStr, start, end, contextStart, contextEnd, clusterLeft[c], baseline - Math.round(lift), rtl, drawPaint);
                    continue;
                }
                emphasisAt(w, i, wordClockMs, emphasisA);
                if (blending) {
                    emphasisAt(w, i, blendFromMs, emphasisB);
                    for (int k = 0; k < 4; k++) emphasisA[k] = lerp(emphasisB[k], emphasisA[k], blendWeight);
                }
                final float scale = emphasisA[0];
                // Logical order: in an RTL word the first grapheme is on the right.
                final float spread = rtl ? -emphasisA[1] : emphasisA[1];
                final float rise = emphasisA[2] + lift;
                glowAlpha = emphasisA[3] > 0.004f ? Math.min(1f, emphasisA[3]) : 0f;
                final float pivotX = (clusterLeft[c] + clusterRight[c]) / 2f;
                if (layers) {
                    drawInNode(canvas, clusterNode(c), start, end, contextStart, contextEnd,
                            clusterLeft[c], clusterRight[c], line, baseline, rtl, spread, -rise, scale, pivotX, glyphMiddle);
                } else {
                    canvas.save();
                    canvas.translate(spread, -rise);
                    canvas.scale(scale, scale, pivotX, glyphMiddle);
                    if (glowAlpha > 0f) drawGlow(canvas, start, end, contextStart, contextEnd, clusterLeft[c], baseline, rtl);
                    canvas.drawTextRun(karaokeTextStr, start, end, contextStart, contextEnd, clusterLeft[c], baseline, rtl, drawPaint);
                    canvas.restore();
                }
            }
            glowAlpha = 0f;
        }

        /**
         * The emphasis glow, as its own pass under the glyph: the grapheme's shape blurred, in
         * white at glowAlpha. It used to be a shadow layer on the glyph's own paint, and a shadow
         * keeps the paint's shader: in the middle of a line the fill gradient covers the whole
         * visual line (one run), so the glow came out in the gradient's colours, mostly the
         * unsung 40%, and read as nothing. Only once the fill had left the line (its last word)
         * was the paint a flat colour and the glow white. This pass never carries the fill.
         */
        private void drawGlow(Canvas canvas, int start, int end, int contextStart, int contextEnd,
                              float x, float baseline, boolean rtl) {
            // CSS drop-shadow blur is twice the Gaussian sigma; BlurMaskFilter takes a radius,
            // sigma = BLUR_SIGMA_SCALE * r + BLUR_SIGMA_BIAS.
            final float sigma = LyricsTuning.EMPHASIS_GLOW_RADIUS_EM * getTextSize() / 2f;
            final float radius = Math.max(0.5f, (sigma - LyricsTuning.BLUR_SIGMA_BIAS) / LyricsTuning.BLUR_SIGMA_SCALE);
            if (glowFilter == null || glowFilterRadius != radius) {
                glowFilter = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
                glowFilterRadius = radius;
            }
            final Shader shader = drawPaint.getShader();
            final int color = drawPaint.getColor();
            drawPaint.setShader(null);
            drawPaint.setColor(Color.argb(Math.round(glowAlpha * 255), 255, 255, 255));
            drawPaint.setMaskFilter(glowFilter);
            canvas.drawTextRun(karaokeTextStr, start, end, contextStart, contextEnd, x, baseline, rtl, drawPaint);
            drawPaint.setMaskFilter(null);
            drawPaint.setColor(color);
            drawPaint.setShader(shader);
        }

        /** One grapheme's emphasis at clock {@code now}: scale, spread (logical), rise, glow alpha. */
        private void emphasisAt(int w, int i, long now, float[] out) {
            final float em = getTextSize();
            final int n = Math.max(1, wordGraphemes[w]);
            final long du = Math.max(1L, wordEmphasisMs[w]);
            final float swell = wordAmount[w];
            final long delay = wordStartMs[w] + emphasisStepMs(w) * i;
            final float t = emphasisEase((now - delay) / (float) du);
            final long floatStart = delay - LyricsTuning.EMPHASIS_FLOAT_LEAD_MS;
            final float fx = (now - floatStart) / (du * LyricsTuning.EMPHASIS_FLOAT_STRETCH);
            final float floatUp = fx > 0f && fx < 1f ? (float) Math.sin(fx * Math.PI) * LyricsTuning.EMPHASIS_FLOAT_EM * em : 0f;
            // YouLy+ grow-dynamic at its peak: scale 1 + swell, each grapheme pushed out from the
            // word's middle by (position - 0.5) * 2 * swell em, and lifted by a share of its box.
            final float position = (i + 0.5f) / n;
            out[0] = 1f + t * swell;
            out[1] = t * (position - 0.5f) * 2f * swell * LyricsTuning.EMPHASIS_SPREAD * em;
            out[2] = t * swell / LyricsTuning.EMPHASIS_RISE_REF_SWELL * LyricsTuning.EMPHASIS_RISE_BOX
                    * (fillMetrics.descent - fillMetrics.ascent) + floatUp;
            out[3] = t * wordGlow[w];
        }

        /** Returns the row to plain, uniformly coloured text, clearing any long-note emphasis. */
        void clearKaraoke() {
            if (!karaokeActive && requestedStart < 0 && requestedEnd < 0) return;
            karaokeActive = false;
            detachSpans();
            invalidate();
        }

        /**
         * Depth, as a real blur on the view's own render node: the platform's GPU blur, one
         * property on a RenderNode Android is already compositing, so a blurred row costs no
         * bitmap and no work of ours per frame. The radius is quantised to BLUR_RADIUS_STEP_PX,
         * fine enough that an easing blur reads as continuous (half-pixel steps were visible as
         * small jumps in sharpness), and each step's effect is shared by every row.
         */
        void setDepthBlur(float radiusPx) {
            final int quantized = radiusPx < LyricsTuning.BLUR_RADIUS_STEP_PX * 0.5f ? 0
                    : Math.min(BLUR_EFFECTS.length - 1, Math.round(radiusPx / LyricsTuning.BLUR_RADIUS_STEP_PX));
            if (quantized == appliedBlur) return;
            appliedBlur = quantized;
            RenderEffect effect = null;
            if (quantized > 0) {
                effect = BLUR_EFFECTS[quantized];
                if (effect == null) {
                    final float radius = quantized * LyricsTuning.BLUR_RADIUS_STEP_PX;
                    effect = BLUR_EFFECTS[quantized] = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
                }
            }
            setRenderEffect(effect);
        }

        /** Radius steps up to 64px (BLUR_LEVEL_MAX at the densest screens is ~35px). */
        private static final RenderEffect[] BLUR_EFFECTS = new RenderEffect[Math.round(64f / LyricsTuning.BLUR_RADIUS_STEP_PX) + 1];

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
            getPaint().getFontMetrics(fillMetrics);
            fadeWidthPx = LyricsTuning.FILL_FADE_WIDTH * (fillMetrics.descent - fillMetrics.ascent);
            nodePad = (int) Math.ceil(getTextSize() * (LyricsTuning.EMPHASIS_GLOW_RADIUS_EM
                    + LyricsTuning.EMPHASIS_SWELL_SHORT_BASE + LyricsTuning.EMPHASIS_SWELL_RAMP + 0.1f));
            rebuildPieces();
            return true;
        }

        /**
         * Cuts every visual run at word boundaries, in logical order: each piece is either part
         * of one lexical word or text outside any word. Rebuilt only with the layout.
         */
        private void rebuildPieces() {
            pieceCount = 0;
            mapWords();
            for (int r = 0; r < runCount; r++) {
                int c = 0;
                while (c < clusterCount && clusterStart[c] < runStart[r]) c++;
                while (c < clusterCount && clusterStart[c] < runEnd[r]) {
                    final int word = wordsMapped ? clusterWord[c] : -1;
                    final int start = clusterStart[c];
                    float left = Float.MAX_VALUE;
                    float right = -Float.MAX_VALUE;
                    int next = c;
                    while (next < clusterCount && clusterStart[next] < runEnd[r]
                            && (wordsMapped ? clusterWord[next] : -1) == word) {
                        if (clusterHasRect[next]) {
                            if (clusterLeft[next] < left) left = clusterLeft[next];
                            if (clusterRight[next] > right) right = clusterRight[next];
                        }
                        next++;
                    }
                    final int end = Math.min(runEnd[r], clusterStart[next]);
                    if (left != Float.MAX_VALUE && end > start) {
                        ensurePieceCapacity(pieceCount + 1);
                        pieceStart[pieceCount] = start;
                        pieceEnd[pieceCount] = end;
                        pieceRun[pieceCount] = r;
                        pieceWord[pieceCount] = word;
                        pieceLeft[pieceCount] = left;
                        pieceRight[pieceCount] = right;
                        pieceCount++;
                    }
                    c = next;
                }
            }
        }

        private void ensurePieceCapacity(int size) {
            if (size <= pieceStart.length) return;
            final int grown = Math.max(size, pieceStart.length * 2);
            pieceStart = java.util.Arrays.copyOf(pieceStart, grown);
            pieceEnd = java.util.Arrays.copyOf(pieceEnd, grown);
            pieceRun = java.util.Arrays.copyOf(pieceRun, grown);
            pieceWord = java.util.Arrays.copyOf(pieceWord, grown);
            pieceLeft = java.util.Arrays.copyOf(pieceLeft, grown);
            pieceRight = java.util.Arrays.copyOf(pieceRight, grown);
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
            runLine[runCount] = line;
            runRtl[runCount] = layout.isRtlCharAt(start);
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
            runLine = java.util.Arrays.copyOf(runLine, grown);
            runRtl = java.util.Arrays.copyOf(runRtl, grown);
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
            wordHasExplicitEnd = false;
            wordGapProgress = 0f;
            requestedStart = -1;
            requestedEnd = -1;
            spanSungTo = -1;
            spanWordTo = -1;
            frontCluster = -1;
            frontRevealed = 0f;
            clusterLayout = null;
            clusterGeometryText = null;
            clusterGeometryCount = 0;
            pieceCount = 0;
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
     * the colour, the shader, and the shadow layer (for glow), and NOTHING else: not the typeface,
     * the text size, fake-bold, scaleX, letter spacing, the baseline shift or the flags.
     * That is why the sweep cannot change a glyph's shape, width, weight, spacing or position.
     *
     * <p>The shader is always set, to null when this run is a flat colour. A {@link TextPaint} is
     * reused across the runs of a line, so a run that did not clear it would inherit the gradient
     * belonging to the run before it and paint its own text through the wrong boundary.
     */
    private static final class KaraokeSpan extends CharacterStyle implements UpdateAppearance {
        private int color;
        private Shader shader;
        /** Glow shadow: radius > 0 enables setShadowLayer on the span's TextPaint. */
        private float shadowRadius;
        private int shadowColor;

        /** A flat colour, with any shader from a previous frame explicitly dropped. */
        void setSolid(int value) {
            set(value, null);
        }

        void set(int value, Shader paintShader) {
            color = value;
            shader = paintShader;
        }

        /** Sets the glow shadow. Use radius = 0 to clear. Requires a software rendering layer. */
        void setGlow(float radius, int argbColor) {
            shadowRadius = radius;
            shadowColor = argbColor;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            // With a shader set, the paint's own alpha multiplies the shader's output. The
            // gradient's stops already carry the sung and muted alphas, so the paint must be
            // opaque here or the whole run is dimmed a second time by the muted alpha.
            paint.setColor(shader != null ? (color | 0xFF000000) : color);
            // Unconditional, including the null: see the class comment. A shader left behind by
            // another run would repaint this run through that run's boundary.
            paint.setShader(shader);
            // Long-note glow: white halo, rendered via software layer (TextPaint.setShadowLayer
            // is ignored on hardware-accelerated layers). Unconditional clear when inactive so a
            // recycled view cannot carry a previous line's glow forward.
            if (shadowRadius > 0f) {
                paint.setShadowLayer(shadowRadius, 0f, 0f, shadowColor);
            } else {
                paint.clearShadowLayer();
            }
        }
    }

    /**
     * A display line's text with its background part (after the line break) set smaller and
     * dimmer. Both spans are fixed at bind: the size is metric-affecting, so it is never changed
     * afterwards.
     */
    private static CharSequence backgroundVocalsText(String text) {
        final int lineBreak = text.indexOf('\n');
        if (lineBreak < 0 || lineBreak + 1 >= text.length()) return text;
        final SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new RelativeSizeSpan(LyricsTuning.BACKGROUND_VOCALS_SCALE), lineBreak + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new BackgroundVocalsSpan(), lineBreak + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    /** True when the first strong character of {@code text} is right-to-left. */
    private static boolean isRtlLyricText(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            final byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** Dims background vocals on top of whatever colour the line has; appearance only. */
    private static final class BackgroundVocalsSpan extends CharacterStyle implements UpdateAppearance {
        /** Set by the row: its focus, so the line is hidden while inactive. Appearance only. */
        float visibility = 1f;

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setAlpha(Math.round(paint.getAlpha() * LyricsTuning.BACKGROUND_VOCALS_ALPHA * visibility));
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
            return position >= 0 && position < visibleLyrics.size() && visibleLyrics.get(position) >= 0
                    && !TextUtils.isEmpty(currentLyrics.lines.get(visibleLyrics.get(position)).text);
        }

        @Override
        public int getItemCount() {
            return visibleLyrics.size();
        }

        @Override
        public int getItemViewType(int position) {
            return visibleLyrics.get(position) == LYRICS_ROW_INTERLUDE ? 1 : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == 1) {
                final LyricsInterludeView dots = new LyricsInterludeView(context, dp(LYRICS_ROW_PADDING_H_DP));
                dots.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(LyricsTuning.INTERLUDE_ROW_HEIGHT_DP)));
                return new RecyclerListView.Holder(dots);
            }
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
            // A row scrolled back into view starts from its own blur, not from whatever its spring
            // held when it last left the screen (off-screen rows are not updated).
            if (hasLyricsRow(position)) lyricsBlurSet[position] = false;
            if (holder.itemView instanceof LyricsInterludeView) {
                final LyricsInterludeView dots = (LyricsInterludeView) holder.itemView;
                final int gap = lyricsInterludeIndexForRow(position);
                if (gap >= 0) {
                    // The dots sit on the side the next line reads from.
                    final int next = position + 1 < visibleLyrics.size() ? visibleLyrics.get(position + 1) : -1;
                    final boolean rtl = next >= 0 && isRtlLyricText(currentLyrics.lines.get(next).text);
                    dots.bind(lyricsInterludeStart[gap], lyricsInterludeEnd[gap], lyricsInterludeIntro[gap], rtl,
                            dp(LYRICS_TEXT_SIZE_DP));
                }
                applyLyricsDepth(dots, position);
                return;
            }
            LyricsTextView textView = (LyricsTextView) holder.itemView;
            int line = visibleLyrics.get(position);
            final boolean synced = currentLyrics.isSynced();
            // Background vocals (text in parentheses) are shown as a second line, smaller and
            // dimmer. Both are set here, once per bind, and never touched again.
            final SyncedLyricsController.Line lyricLine = displayLyricsLine(line);
            // Only a line the source actually timed inside is bound as spannable text; every other
            // row stays the plain string it has always been.
            textView.setLyricText(backgroundVocalsText(lyricLine.text), synced && lyricLine.segments != null);
            if (synced && lyricLine.segments != null) {
                // A line stays active until the next timed line starts, the song finishes, or an
                // instrumental gap begins.
                final long nextLineMs = nextLyricsLineTimeMs(line);
                final long singingEnd = line < lyricsLineSingingEnd.length ? lyricsLineSingingEnd[line] : Long.MAX_VALUE;
                long activeEnd = Math.min(Math.min(nextLineMs, lyricsEndMs), singingEnd);
                // Background vocals sung over the next line keep the line active until they end.
                if (line < lyricsLineHoldEnd.length && lyricsLineHoldEnd[line] > activeEnd) activeEnd = lyricsLineHoldEnd[line];
                textView.setWordTiming(lyricLine, activeEnd, nextLineMs);
            }
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
            // A recycled or rebound row is painted here, from the state indexed by this position,
            // so it never draws a frame with the previous line's or the adapter's default look.
            // A rebind in place (notifyDataSetChanged) gets no attach callback, so this is the
            // only paint it would get before it is drawn.
            if (synced) {
                applyLyricsDepth(textView, position);
            } else {
                textView.setDepthBlur(0f);
                textView.setLyricTextColor(getThemedColor(Theme.key_player_actionBarTitle));
                textView.setAlpha(1f);
                textView.setScaleX(1f);
                textView.setScaleY(1f);
            }
            textView.setBackground(stanzaSpace || !synced ? null : Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            // A row coming back from the view cache is attached without a rebind: paint it from
            // its position now, before its first draw.
            if (currentLyrics == null || !currentLyrics.isSynced()) return;
            final int position = holder.getAdapterPosition();
            applyLyricsDepth(holder.itemView,
                    position != RecyclerView.NO_POSITION ? position : holder.getLayoutPosition());
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
