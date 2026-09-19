/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SyncedLyricsController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LyricsOnlineSearch;
import org.telegram.ui.Components.RadialProgressView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A single raw-text editing surface for the current track's local lyrics source. */
public class SyncedLyricsEditorFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int DONE = 1;
    private static final int OTHER = 2;
    private static final int IMPORT = 3;
    private static final int DELETE = 4;
    private static final int UNDO = 5;
    private static final int REDO = 6;
    private static final int ONLINE_SEARCH = 7;
    private static final int PICK_LRC = 41;
    private static final int MAX_SIZE = 1024 * 1024;
    /**
     * The file names Import accepts. {@code .lrc} is what this feature writes and what every LRC
     * tool produces, {@code .elrc} is the extension some taggers give an Enhanced (word-timed) LRC,
     * and {@code .txt} was always accepted for plain lyrics. All three are read by the same parser,
     * which decides what the contents actually are.
     */
    // .ttml joins the three that were already here because the editor can now genuinely hold a
    // TTML document: the parser reads the dialect, the online search can return one, and Save
    // persists it verbatim. Nothing else about import changes - same picker, same name check,
    // same size limit, same strict UTF-8 decode, and the same parser deciding afterwards
    // whether what arrived is usable.
    private static final String[] IMPORT_EXTENSIONS = {".lrc", ".elrc", ".txt", ".ttml"};

    private final MessageObject messageObject;
    private LyricsEditText editText;
    private ActionBarMenuItem doneButton;
    private ActionBarMenuItem otherButton;
    private ActionBarMenuItem undoButton;
    private ActionBarMenuItem redoButton;
    private RadialProgressView progressView;
    private LyricsHistory history;
    private String initialSource = "";
    private String restoredSource;
    private int restoredSelection = -1;
    private boolean changedByUser;
    private boolean saving;
    private boolean importing;
    private boolean searching;
    private boolean editorReady;
    private boolean readErrorShown;
    private boolean ignoreTextChange;
    private Boolean lastCanUndo;
    private Boolean lastCanRedo;
    private LyricsOnlineSearch.Request onlineRequest;
    private AlertDialog onlineProgressDialog;
    private boolean pendingControllerRefresh;
    private String searchArtist;
    private String searchTitle;
    /** The flavour the row list opens marked. Remembered like the fields, and never applied on its own. */
    private LyricsOnlineSearch.Type searchType = LyricsOnlineSearch.Type.SYNCED;

    public SyncedLyricsEditorFragment(MessageObject messageObject) {
        super(new Bundle());
        this.messageObject = messageObject;
        currentAccount = messageObject.currentAccount;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.syncedLyricsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.syncedLyricsChanged);
        if (history != null) history.detach();
        // The search owns a background request and a dialog that showDialog() does not manage.
        // The views are already going away here, so nothing is restored or replayed.
        cancelOnlineSearch(false);
        super.onFragmentDestroy();
    }

    /**
     * A plain-text editing surface for raw LRC.
     *
     * <p>{@link EditTextBoldCursor} inherits Telegram's message-composer effects pipeline from
     * {@code EditTextEffects}. That pipeline rescans the <em>whole</em> document on every keystroke
     * (spoiler spans, quote blocks, animated emoji) and again on every frame from {@code onDraw},
     * which is what made a long LRC document lag: the cost grows with the size of the document
     * rather than with the size of the edit. None of those features can occur in raw LRC text, so
     * this surface opts out of all of them. What remains per keystroke is the incremental
     * {@code DynamicLayout} reflow of the edited paragraph - the same order of work per edit that
     * Telegram's own editor gets by giving every block its own small {@code RichEditText} inside
     * {@code RichEditorListView}.
     */
    private static class LyricsEditText extends EditTextBoldCursor {
        private final OverScroller flingScroller;
        private final GestureDetector flingDetector;

        LyricsEditText(Context context) {
            super(context);
            drawAnimatedEmojiDrawables = false;
            setShouldRevealSpoilersByTouch(false);
            // A TextView scrolls through Touch.onTouchEvent, which drags but never flings - that is
            // why the editor felt dry and stopped dead. Add momentum without changing the layout,
            // the movement method, IME behaviour or the single-EditText structure.
            flingScroller = new OverScroller(context);
            flingDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (hasSelection()) return false;
                    final int max = getMaxEditorScroll();
                    if (max <= 0) return false;
                    flingScroller.forceFinished(true);
                    flingScroller.fling(0, getScrollY(), 0, -Math.round(velocityY), 0, 0, 0, max);
                    postInvalidateOnAnimation();
                    return true;
                }
            });
            flingDetector.setIsLongpressEnabled(false);
        }

        private int getMaxEditorScroll() {
            final Layout layout = getLayout();
            if (layout == null) return 0;
            return Math.max(0, layout.getHeight() + getPaddingTop() + getPaddingBottom() - getHeight());
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                flingScroller.forceFinished(true);
            }
            // Selection, handles, cursor placement and the IME keep first claim on the event; the
            // detector only ever reacts to a fling.
            final boolean handled = super.onTouchEvent(event);
            flingDetector.onTouchEvent(event);
            return handled;
        }

        @Override
        public void computeScroll() {
            if (flingScroller.computeScrollOffset()) {
                final int y = Math.max(0, Math.min(flingScroller.getCurrY(), getMaxEditorScroll()));
                if (y != getScrollY()) {
                    scrollTo(getScrollX(), y);
                } else if (y == 0 || y == getMaxEditorScroll()) {
                    flingScroller.forceFinished(true);
                }
                postInvalidateOnAnimation();
            }
        }

        @Override
        public void invalidateEffects() {
            // no spoilers in a lyrics document
        }

        @Override
        protected void invalidateSpoilers() {
            // no spoilers in a lyrics document
        }

        @Override
        public void invalidateQuotes(boolean force) {
            // no quote blocks in a lyrics document
        }

        @Override
        public void updateAnimatedEmoji(boolean force) {
            // no animated emoji spans in a lyrics document
        }
    }

    /**
     * Presentation-only timestamp colour. Deliberately NOT a ParcelableSpan, so it is dropped when
     * the selection is copied or cut - the clipboard, the saved file, import and export all stay
     * exactly the raw lyrics text.
     */
    private static final class TimestampSpan extends CharacterStyle implements UpdateAppearance {
        private final int color;

        TimestampSpan(int color) {
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setColor(color);
        }
    }

    /** Leading LRC timestamps only, matching what the parser accepts. Metadata tags cannot match. */
    private static final Pattern TIMESTAMP_TOKEN = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[\\.:]\\d{1,3})?]");

    /** Inline Enhanced-LRC word timestamps, matching what the parser captures inside a timed line. */
    private static final Pattern WORD_TIMESTAMP_TOKEN = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[\\.:]\\d{1,3})?>");

    /**
     * Re-colours only the lines the last edit touched. Typing re-spans one paragraph; a paste or an
     * import re-spans just the inserted range. Nothing ever walks the whole document per keystroke
     * or per frame.
     */
    private void restyleTimestamps(Editable text, int changeStart, int changeEnd) {
        if (text == null) return;
        final int length = text.length();
        int start = Math.max(0, Math.min(changeStart, length));
        int end = Math.max(start, Math.min(changeEnd, length));
        while (start > 0 && text.charAt(start - 1) != '\n') start--;
        while (end < length && text.charAt(end) != '\n') end++;
        if (start >= end) {
            if (length == 0) return;
            if (start >= length) return;
            end = Math.min(length, start + 1);
        }
        for (TimestampSpan span : text.getSpans(start, end, TimestampSpan.class)) {
            text.removeSpan(span);
        }
        final int color = getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
        final int wordColor = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        int lineStart = start;
        while (lineStart <= end && lineStart < length) {
            int lineEnd = lineStart;
            while (lineEnd < length && text.charAt(lineEnd) != '\n') lineEnd++;
            styleTimestampsInLine(text, lineStart, lineEnd, color, wordColor);
            lineStart = lineEnd + 1;
        }
    }

    private void styleTimestampsInLine(Editable text, int lineStart, int lineEnd, int color, int wordColor) {
        if (lineEnd <= lineStart) return;
        final Matcher matcher = TIMESTAMP_TOKEN.matcher(text);
        matcher.region(lineStart, lineEnd);
        int cursor = lineStart;
        while (matcher.find()) {
            if (matcher.start() != cursor) break; // only a run of timestamps at the start of a line
            try {
                if (Long.parseLong(matcher.group(2)) >= 60) break;
            } catch (RuntimeException ignore) {
                break;
            }
            text.setSpan(new TimestampSpan(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = matcher.end();
        }
        // Inline word timestamps are only timing inside a line that already has a leading one; on
        // any other line the same characters are just text, and colouring them would say otherwise.
        if (cursor > lineStart) styleWordTimestampsInLine(text, cursor, lineEnd, wordColor);
    }

    /**
     * Marks the inline word timestamps of one timed line, all of them or none. The parser discards
     * a line's inline timing outright when any one tag states an impossible time, so colouring the
     * well-formed ones next to a broken one would show timing that will not be read back.
     */
    private void styleWordTimestampsInLine(Editable text, int start, int lineEnd, int color) {
        if (lineEnd <= start) return;
        final Matcher matcher = WORD_TIMESTAMP_TOKEN.matcher(text);
        matcher.region(start, lineEnd);
        while (matcher.find()) {
            try {
                if (Long.parseLong(matcher.group(2)) >= 60) return;
            } catch (RuntimeException ignore) {
                return;
            }
        }
        matcher.region(start, lineEnd); // resets the matcher; every tag on this line is known good
        while (matcher.find()) {
            text.setSpan(new TimestampSpan(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void restyleAllTimestamps() {
        if (editText == null) return;
        final Editable text = editText.getText();
        if (text != null) restyleTimestamps(text, 0, text.length());
    }

    @Override
    public View createView(Context context) {
        SyncedLyricsController controller = SyncedLyricsController.getInstance(currentAccount);
        controller.retryIfFailed(messageObject);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.Lyrics));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) finishFragment();
                } else if (id == DONE) {
                    save();
                } else if (id == UNDO) {
                    undo();
                } else if (id == REDO) {
                    redo();
                } else if (id == IMPORT) {
                    importFile();
                } else if (id == ONLINE_SEARCH) {
                    showOnlineSearch();
                } else if (id == DELETE) {
                    confirmDelete();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        // Same controls, icons and disabled treatment as Telegram's own editor history buttons.
        undoButton = menu.addItem(UNDO, R.drawable.iv_undo);
        undoButton.setContentDescription(LocaleController.getString(R.string.Undo));
        redoButton = menu.addItem(REDO, R.drawable.iv_redo);
        redoButton.setContentDescription(LocaleController.getString(R.string.Redo));
        otherButton = menu.addItem(OTHER, R.drawable.ic_ab_other);
        otherButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        doneButton = menu.addItemWithWidth(DONE, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Save));

        editText = new LyricsEditText(context);
        editText.setTextSize(16);
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setHint(LocaleController.getString(R.string.SyncedLyricsHint));
        editText.setGravity(Gravity.START | Gravity.TOP);
        editText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setSingleLine(false);
        editText.setHorizontallyScrolling(false);
        editText.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Long LRC documents do not benefit from expensive balanced line breaking.
            editText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            editText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        editText.setBackgroundColor(Color.TRANSPARENT);
        editText.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        initialSource = controller.getLyrics(messageObject).source;
        editText.setText(restoredSource == null ? initialSource : restoredSource);
        editText.setSelection(restoredSelection < 0 ? editText.length() : Math.min(restoredSelection, editText.length()));
        history = new LyricsHistory(new LyricsHistory.Delegate() {
            @Override public String getText() { return editText == null ? "" : editText.getText().toString(); }
            @Override public int getSelectionStart() { return editText == null ? 0 : editText.getSelectionStart(); }
            @Override public int getSelectionEnd() { return editText == null ? 0 : editText.getSelectionEnd(); }
            @Override public void restore(String text, int selStart, int selEnd) { applyHistoryText(text, selStart, selEnd); }
            @Override public void onHistoryChanged() { updateHistoryButtons(); }
        });
        editText.addTextChangedListener(new TextWatcher() {
            private int changeStart;
            private int changeEnd;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!ignoreTextChange && history != null) history.onBeforeChange(count, after);
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                changeStart = start;
                changeEnd = start + count;
                if (ignoreTextChange) return;
                changedByUser = true;
                // Debounced only: no snapshot and no document parsing per keystroke.
                if (history != null) history.onTyping();
            }
            @Override public void afterTextChanged(Editable s) {
                // Span work is bounded to the edited lines and never reported to the history:
                // TextWatchers are notified of text changes, not span changes.
                restyleTimestamps(s, changeStart, changeEnd);
            }
        });
        // Scope the selection toolbar to the real window, exactly as Telegram's own long-text
        // editors do (ChatActivityEnterView, PhotoViewerCaptionEnterView). Nothing shared changes.
        if (getParentActivity() != null && getParentActivity().getWindow() != null) {
            editText.setWindowView(getParentActivity().getWindow().getDecorView());
        }
        restyleAllTimestamps();
        changedByUser = restoredSource != null && !initialSource.equals(restoredSource);
        updateOtherMenu();
        updateHistoryButtons();
        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        progressView = new RadialProgressView(context, getResourceProvider());
        progressView.setSize(AndroidUtilities.dp(24));
        content.addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
        fragmentView = content;
        applyControllerState(false);
        return fragmentView;
    }

    private void setEditorLoading(boolean loading) {
        editorReady = !loading;
        editText.setEnabled(!loading);
        editText.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
        updateDoneButton();
    }

    /** Single source of truth for Done, so no path can re-enable it while something else owns the editor. */
    private void updateDoneButton() {
        if (doneButton == null) return;
        doneButton.setEnabled(editorReady && !saving && !searching);
    }

    /**
     * Reflects the online-search lock in the chrome. Only {@code searching} is considered here:
     * the editorReady/saving treatment of these controls is unchanged.
     */
    private void updateSearchLock() {
        if (otherButton != null) {
            otherButton.setEnabled(!searching);
            otherButton.setAlpha(searching ? 0.35f : 1f);
        }
        updateDoneButton();
        updateHistoryButtons();
    }

    private void applyControllerState(boolean replaceText) {
        SyncedLyricsController controller = SyncedLyricsController.getInstance(currentAccount);
        SyncedLyricsController.State state = controller.getState(messageObject);
        if (state == SyncedLyricsController.State.NOT_LOADED || state == SyncedLyricsController.State.LOADING) {
            readErrorShown = false;
            setEditorLoading(true);
            return;
        }
        if (state == SyncedLyricsController.State.READ_FAILED) {
            setEditorLoading(true);
            progressView.setVisibility(View.GONE);
            if (!readErrorShown) {
                readErrorShown = true;
                showReadError();
            }
            return;
        }
        readErrorShown = false;
        String loadedSource = controller.getLyrics(messageObject).source;
        initialSource = loadedSource;
        if (replaceText) {
            ignoreTextChange = true;
            editText.setText(initialSource);
            editText.setSelection(editText.length());
            ignoreTextChange = false;
            restyleAllTimestamps();
            changedByUser = false;
            // The loaded document is the state the first undo should return to.
            if (history != null) history.resetBaseline();
        }
        updateOtherMenu();
        updateHistoryButtons();
        setEditorLoading(false);
    }

    private void showReadError() {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.AppName))
                .setMessage(LocaleController.getString(R.string.LyricsImportFailed))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (ignored, which) -> finishFragment())
                .setPositiveButton(LocaleController.getString(R.string.Retry), (ignored, which) -> {
                    readErrorShown = false;
                    SyncedLyricsController.getInstance(currentAccount).retryIfFailed(messageObject);
                    applyControllerState(false);
                })
                .create();
        showDialog(dialog);
    }

    private void updateOtherMenu() {
        if (otherButton == null) return;
        otherButton.removeAllSubItems();
        otherButton.addSubItem(IMPORT, R.drawable.msg_openin, LocaleController.getString(R.string.ImportLyrics));
        otherButton.addSubItem(ONLINE_SEARCH, R.drawable.msg_search, LocaleController.getString(R.string.LyricsOnlineSearch));
        if (!initialSource.isEmpty()) {
            ActionBarMenuSubItem delete = otherButton.addSubItem(DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.DeleteLyrics));
            delete.setColors(getThemedColor(Theme.key_text_RedRegular), getThemedColor(Theme.key_text_RedRegular));
        }
    }

    private void undo() {
        if (history == null || !editorReady || saving || searching) return;
        history.undo();
    }

    private void redo() {
        if (history == null || !editorReady || saving || searching) return;
        history.redo();
    }

    private void applyHistoryText(String text, int selectionStart, int selectionEnd) {
        if (editText == null) return;
        ignoreTextChange = true;
        editText.setText(text);
        final int length = editText.length();
        final int start = Math.max(0, Math.min(selectionStart, length));
        final int end = Math.max(start, Math.min(selectionEnd, length));
        editText.setSelection(start, end);
        ignoreTextChange = false;
        // Undo/redo restores text, then refreshes the presentation; the spans are never part of
        // the snapshots themselves.
        restyleAllTimestamps();
        changedByUser = !initialSource.equals(text);
    }

    /** Enabled/disabled treatment taken from Telegram's editor toolbar: full opacity vs 0.35. */
    private void updateHistoryButtons() {
        if (undoButton == null || redoButton == null) return;
        final boolean canUndo = history != null && editorReady && !saving && !searching && history.canUndo();
        final boolean canRedo = history != null && editorReady && !saving && !searching && history.canRedo();
        if (lastCanUndo != null && lastCanUndo == canUndo && lastCanRedo != null && lastCanRedo == canRedo) {
            return;
        }
        lastCanUndo = canUndo;
        lastCanRedo = canRedo;
        undoButton.setEnabled(canUndo);
        undoButton.setAlpha(canUndo ? 1f : 0.35f);
        redoButton.setEnabled(canRedo);
        redoButton.setAlpha(canRedo ? 1f : 0.35f);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.syncedLyricsChanged || editText == null || saving || importing) return;
        if (searching) {
            // The controller may finish loading (for example the media file arrives and embedded
            // lyrics are extracted) while an online request is visibly in progress. Applying it now
            // would replace the editor underneath the user, but dropping it would strand the editor
            // on a stale state until some later notification. Remember it and replay it when the
            // search terminates.
            pendingControllerRefresh = true;
            return;
        }
        applyControllerState(!changedByUser);
    }

    /**
     * Replays a controller transition that arrived while a search was running. Nothing is applied
     * unless the UI is still alive, and {@code changedByUser} keeps its existing meaning: a manual
     * unsaved edit is never overwritten, only {@code initialSource} and the menus catch up.
     */
    private void consumePendingControllerRefresh() {
        if (!pendingControllerRefresh) return;
        pendingControllerRefresh = false;
        if (isFinished || getParentActivity() == null || editText == null) return;
        applyControllerState(!changedByUser);
    }

    private void save() {
        if (saving || searching || !editorReady) return;
        saving = true;
        updateDoneButton();
        updateHistoryButtons();
        String source = editText.getText().toString();
        SyncedLyricsController.getInstance(currentAccount).save(messageObject, source, success -> {
            saving = false;
            if (isFinished || getParentActivity() == null) return;
            updateDoneButton();
            updateHistoryButtons();
            if (success) {
                initialSource = source;
                finishAndReturnToPlayer();
            } else {
                showError(R.string.LyricsSaveFailed);
            }
        });
    }

    private void importFile() {
        if (saving || searching || !editorReady) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Deliberately no EXTRA_MIME_TYPES. That extra REPLACES setType() as the picker's
            // filter, and .lrc has no registered MIME type on Android: MimeTypeMap does not know
            // the extension, so every document provider is free to report whatever it likes -
            // text/plain, application/octet-stream, a vendor-specific type, or nothing at all. A
            // provider whose answer is outside the list leaves the file listed but greyed out and
            // unselectable, which is exactly what happened to a real .lrc file. The platform
            // cannot express this format as a MIME allowlist, so the filter is the file name and
            // the contents, checked below after selection, where they can be checked properly.
            intent.setType("*/*");
            startActivityForResult(intent, PICK_LRC);
        } catch (Exception e) {
            FileLog.e(e);
            showError(R.string.LyricsImportFailed);
        }
    }

    /** True when the picked document's name is one of {@link #IMPORT_EXTENSIONS}. */
    private static boolean isSupportedLyricsFileName(String name) {
        final String lower = name.toLowerCase(Locale.US);
        for (int a = 0; a < IMPORT_EXTENSIONS.length; a++) {
            if (lower.endsWith(IMPORT_EXTENSIONS[a])) return true;
        }
        return false;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_LRC || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean editorWasReady = editorReady;
        importing = true;
        setEditorLoading(true);
        Utilities.globalQueue.postRunnable(() -> {
            String imported = null;
            try (InputStream stream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    // A provider that reports no name at all still has to get past the size limit
                    // and the strict UTF-8 decode below, which is what keeps this from being a
                    // door for arbitrary files now that the picker cannot filter by type.
                    if (name != null && !isSupportedLyricsFileName(name)) {
                        throw new IllegalArgumentException("Unsupported lyrics file");
                    }
                }
            }
            // Not the same as an empty file: the provider could not open what the user picked, and
            // reporting that as empty content would silently wipe the editor.
            if (stream == null) throw new IllegalArgumentException("Lyrics file could not be opened");
            byte[] buffer = new byte[8192];
            int total = 0, count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_SIZE) throw new IllegalArgumentException("Lyrics file is too large");
                output.write(buffer, 0, count);
            }
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            if (source.length() > 0 && source.charAt(0) == '\ufeff') source = source.substring(1);
            imported = source;
        } catch (Exception e) {
            FileLog.e(e);
        }
            final String result = imported;
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || getParentActivity() == null || editText == null) return;
                importing = false;
                if (result == null) {
                    if (editorWasReady) setEditorLoading(false);
                    else applyControllerState(true);
                    showError(R.string.LyricsImportFailed);
                } else {
                    setEditorLoading(false);
                    // Commit what is on screen first so the replacement is a single undo step.
                    if (history != null) history.flush();
                    editText.setText(result);
                    editText.setSelection(editText.length());
                    restyleAllTimestamps();
                    changedByUser = true;
                    if (history != null) history.record();
                    updateOtherMenu();
                    updateHistoryButtons();
                }
            });
        });
    }

    /**
     * Opens the online lookup. Artist and Title are prefilled from the track's own metadata but are
     * only ever search parameters: nothing here writes back to the message, and the user picks the
     * lyric flavour explicitly - there is no automatic mode.
     */
    private void showOnlineSearch() {
        if (searching || saving || importing || !editorReady) return;
        final Activity activity = getParentActivity();
        if (activity == null) return;
        if (searchArtist == null) searchArtist = defaultSearchArtist();
        if (searchTitle == null) searchTitle = defaultSearchTitle();

        final LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        final EditTextBoldCursor artistField = createSearchField(activity, searchArtist, EditorInfo.IME_ACTION_NEXT);
        final EditTextBoldCursor titleField = createSearchField(activity, searchTitle, EditorInfo.IME_ACTION_DONE);
        container.addView(createSearchLabel(activity, R.string.LyricsOnlineSearchArtist), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(artistField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.START, 24, 2, 24, 0));
        container.addView(createSearchLabel(activity, R.string.LyricsOnlineSearchTitle), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(titleField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.START, 24, 2, 24, 0));

        // The three flavours are rows rather than dialog buttons: a dialog has three button slots
        // and one of them has to stay Cancel, and this is the same pick-one-and-act row Telegram
        // uses for its own single-choice dialogs. Tapping a row is still one explicit action, and
        // the row that is marked is the one last used - there is no automatic flavour.
        final AlertDialog[] dialogRef = new AlertDialog[1];
        container.addView(createSearchLabel(activity, R.string.LyricsOnlineSearchType), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final LyricsOnlineSearch.Type[] types = {
                LyricsOnlineSearch.Type.KARAOKE,
                LyricsOnlineSearch.Type.SYNCED,
                LyricsOnlineSearch.Type.PLAIN
        };
        final int[] labels = {
                R.string.LyricsOnlineSearchKaraoke,
                R.string.LyricsOnlineSearchSynced,
                R.string.LyricsOnlineSearchPlain
        };
        for (int a = 0; a < types.length; a++) {
            final LyricsOnlineSearch.Type type = types[a];
            final RadioColorCell cell = new RadioColorCell(activity, getResourceProvider());
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(getThemedColor(Theme.key_radioBackground), getThemedColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(LocaleController.getString(labels[a]), type == searchType);
            cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(ignored -> {
                searchType = type;
                // Started before the dialog goes away, as the buttons this replaced did: the
                // fields are still attached, so hiding the keyboard still has a window to act on.
                startOnlineSearch(type, artistField, titleField);
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            });
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }

        AlertDialog dialog = new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.LyricsOnlineSearch))
                .setView(container)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef[0] = dialog;
        // Remembering what was typed is what keeps the fields alive across a cancelled or failed
        // search. It has to be handed to showDialog(): BaseFragment installs its own dismiss
        // listener on whatever it shows, which would replace one set through the builder.
        showDialog(dialog, ignored -> rememberSearchFields(artistField, titleField));
        artistField.requestFocus();
    }

    private TextView createSearchLabel(Context context, int label) {
        TextView view = new TextView(context);
        view.setText(LocaleController.getString(label));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        view.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), 0);
        return view;
    }

    /** Same single-line dialog input treatment Telegram uses for its own name/title dialogs. */
    private EditTextBoldCursor createSearchField(Context context, String value, int imeOptions) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setBackground(null);
        field.setLineColors(getThemedColor(Theme.key_dialogInputField), getThemedColor(Theme.key_dialogInputFieldActivated), getThemedColor(Theme.key_text_RedBold));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setSingleLine(true);
        field.setMaxLines(1);
        field.setLines(1);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(imeOptions);
        field.setGravity(Gravity.START | Gravity.TOP);
        field.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        field.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        field.setText(value == null ? "" : value);
        field.setSelection(field.length());
        return field;
    }

    private void rememberSearchFields(EditTextBoldCursor artistField, EditTextBoldCursor titleField) {
        if (artistField != null) searchArtist = artistField.getText().toString();
        if (titleField != null) searchTitle = titleField.getText().toString();
    }

    private void startOnlineSearch(LyricsOnlineSearch.Type type, EditTextBoldCursor artistField, EditTextBoldCursor titleField) {
        rememberSearchFields(artistField, titleField);
        if (searching || saving || importing || !editorReady) return;
        final Activity activity = getParentActivity();
        if (activity == null) return;
        AndroidUtilities.hideKeyboard(artistField);
        AndroidUtilities.hideKeyboard(titleField);

        searching = true;
        // The spinner is deliberately delayed, so the lock - not the dialog - is what keeps Save,
        // Import, Delete, Undo/Redo and a second search out during the window before it appears.
        updateSearchLock();
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, getResourceProvider());
        // Cancelling the spinner abandons the request; a delayed show keeps a fast answer from
        // flashing a spinner on screen.
        progress.setOnCancelListener(ignored -> cancelOnlineSearch(true));
        progress.showDelayed(180);
        onlineProgressDialog = progress;

        final LyricsOnlineSearch.Request[] started = new LyricsOnlineSearch.Request[1];
        started[0] = LyricsOnlineSearch.search(searchArtist, searchTitle, messageObject.getDuration(), type, (lyrics, resolved, error) -> {
            // Only the search that still owns the editor may act. A cancelled request never calls
            // back at all; this also rejects a result whose search has been superseded.
            if (started[0] == null || onlineRequest != started[0]) return;
            onlineRequest = null;
            searching = false;
            dismissOnlineProgress();
            if (isFinished || getParentActivity() == null || editText == null) return;
            updateSearchLock();
            // Catch up on any controller transition that arrived during the request first, so
            // initialSource reflects the real persisted source before the result is inserted - and
            // so that on failure the editor is not left on a stale state.
            consumePendingControllerRefresh();
            if (lyrics != null) {
                applyOnlineLyrics(lyrics);
                // A karaoke search that no word-timing source could answer still returns something
                // useful, but it is line timing and the user is told so rather than left to
                // discover it when nothing lights up word by word.
                if (resolved != type) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                            LocaleController.getString(R.string.LyricsOnlineSearchLineSyncOnly), 3).show();
                }
            } else {
                // The same compact bottom message the line-sync notice above uses, and for the
                // same reason: a search that found nothing is information, not a decision to
                // confirm, and a centred dialog with an OK button interrupts the editor to say so.
                // All three flavours fail through here, so all three fail the same way.
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error,
                        LocaleController.getString(onlineSearchErrorMessage(error)), 3).show();
            }
        });
        onlineRequest = started[0];
    }

    /**
     * @param uiAlive false during fragment teardown, when the views are gone and there is nothing
     *                to restore or replay - the request and the dialog are still released.
     */
    private void cancelOnlineSearch(boolean uiAlive) {
        final boolean wasSearching = searching;
        searching = false;
        LyricsOnlineSearch.Request request = onlineRequest;
        onlineRequest = null;
        if (request != null) request.cancel();
        dismissOnlineProgress();
        if (!uiAlive) return;
        if (wasSearching) updateSearchLock();
        consumePendingControllerRefresh();
    }

    private void dismissOnlineProgress() {
        AlertDialog progress = onlineProgressDialog;
        onlineProgressDialog = null;
        if (progress == null) return;
        try {
            progress.dismiss();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * A fetched result is an editor import and nothing more: it is deliberately not handed to
     * {@link SyncedLyricsController}, so Save remains the only thing that persists lyrics. The
     * transaction below is the Import transaction verbatim, which is what makes the replacement a
     * single undo step.
     */
    private void applyOnlineLyrics(String lyrics) {
        if (editText == null) return;
        // Commit what is on screen first so the replacement is a single undo step.
        if (history != null) history.flush();
        editText.setText(lyrics);
        editText.setSelection(editText.length());
        restyleAllTimestamps();
        changedByUser = true;
        if (history != null) history.record();
        updateOtherMenu();
        updateHistoryButtons();
    }

    private int onlineSearchErrorMessage(LyricsOnlineSearch.Error error) {
        if (error == null) return R.string.LyricsOnlineSearchServerError;
        switch (error) {
            case NOT_FOUND:
                return R.string.LyricsOnlineSearchNotFound;
            case TYPE_UNAVAILABLE:
                return R.string.LyricsOnlineSearchTypeUnavailable;
            case NETWORK:
                return R.string.LyricsOnlineSearchNetworkError;
            case RATE_LIMITED:
                return R.string.LyricsOnlineSearchBusy;
            case MALFORMED:
                return R.string.LyricsOnlineSearchInvalidResponse;
            case SERVER:
            default:
                return R.string.LyricsOnlineSearchServerError;
        }
    }

    /**
     * {@code getMusicAuthor(false)} still ends at the localized "unknown artist" placeholder, which
     * must never be sent as a query term.
     */
    private String defaultSearchArtist() {
        String artist = messageObject.getMusicAuthor(false);
        if (artist == null) return "";
        artist = artist.trim();
        if (artist.isEmpty() || artist.equals(LocaleController.getString(R.string.AudioUnknownArtist))) return "";
        return artist;
    }

    /**
     * {@code getMusicTitle(false)} falls back to the document file name before it falls back to the
     * localized placeholder, so only an actual audio file extension is trimmed here. Anything else
     * is left alone: a legitimate title must not be rewritten.
     */
    private String defaultSearchTitle() {
        String title = messageObject.getMusicTitle(false);
        if (title == null) return "";
        title = title.trim();
        if (title.isEmpty() || title.equals(LocaleController.getString(R.string.AudioUnknownTitle))) return "";
        return stripAudioFileExtension(title);
    }

    private static final String[] AUDIO_EXTENSIONS = {
            "mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "alac", "ape", "mka"
    };

    private static String stripAudioFileExtension(String title) {
        final int dot = title.lastIndexOf('.');
        if (dot <= 0 || dot == title.length() - 1) return title;
        final String extension = title.substring(dot + 1).toLowerCase(Locale.US);
        for (String known : AUDIO_EXTENSIONS) {
            if (known.equals(extension)) {
                final String stripped = title.substring(0, dot).trim();
                return stripped.isEmpty() ? title : stripped;
            }
        }
        return title;
    }

    private void confirmDelete() {
        if (saving || searching) return;
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DeleteLyrics))
                .setMessage(LocaleController.getString(R.string.DeleteLyricsConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (ignored, which) -> delete())
                .create();
        showDialog(dialog);
        TextView deleteButton = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (deleteButton != null) deleteButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
    }

    private void delete() {
        if (saving || searching) return;
        saving = true;
        updateDoneButton();
        updateHistoryButtons();
        SyncedLyricsController.getInstance(currentAccount).delete(messageObject, success -> {
            saving = false;
            if (isFinished || getParentActivity() == null) return;
            updateDoneButton();
            updateHistoryButtons();
            if (success) {
                finishAndReturnToPlayer();
            } else {
                showError(R.string.LyricsDeleteFailed);
            }
        });
    }

    private void finishAndReturnToPlayer() {
        Activity activity = getParentActivity();
        Theme.ResourcesProvider provider = getResourceProvider();
        finishFragment();
        if (!(activity instanceof LaunchActivity) || activity.isFinishing()) return;
        // Next frame, not a tuned delay. The old 180ms pause existed to cover a black frame that
        // came from the player itself opening with a near-fullscreen sheet; that is fixed at the
        // source, so the sheet can come straight back over the closing fragment.
        AndroidUtilities.runOnUIThread(() -> {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (!activity.isFinishing() && playing != null && playing.isMusic() && AudioPlayerAlert.instance == null) {
                new AudioPlayerAlert(activity, provider).openLyrics().show();
            }
        });
    }

    private void showError(int message) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.AppName))
                .setMessage(LocaleController.getString(message))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!saving && editText != null && !initialSource.equals(editText.getText().toString())) {
            if (invoked) {
                AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.UnsavedChanges))
                        .setMessage(LocaleController.getString(R.string.DiscardLyricsChanges))
                        .setPositiveButton(LocaleController.getString(R.string.Cancel), null)
                        .setNegativeButton(LocaleController.getString(R.string.Discard), (d, which) -> finishFragment())
                        .create();
                showDialog(dialog);
                TextView discardButton = (TextView) dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (discardButton != null) discardButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
            }
            return false;
        }
        return !saving;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && !backward && editText != null) {
            editText.requestFocus();
            // Raising the keyboard immediately re-lays out the whole document; only do it when
            // there is nothing to read yet, i.e. when the user came here to write.
            if (editText.length() == 0) {
                AndroidUtilities.showKeyboard(editText);
            }
        }
    }

    @Override
    public void saveSelfArgs(Bundle args) {
        if (editText != null) {
            args.putString("lyrics_source", editText.getText().toString());
            args.putInt("lyrics_selection", editText.getSelectionStart());
        }
    }

    @Override
    public void restoreSelfArgs(Bundle args) {
        restoredSource = args.getString("lyrics_source");
        restoredSelection = args.getInt("lyrics_selection", -1);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(editText, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(editText, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        descriptions.add(new ThemeDescription(editText, ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        return descriptions;
    }

    /**
     * Undo/redo for the lyrics source, following the contract of Telegram's own editor history
     * ({@code org.telegram.ui.iv.RichEditorHistory}): nothing is captured per keystroke, a snapshot
     * is committed on an 800 ms typing pause or immediately before a large change, the stack is
     * bounded, and unchanged content is shared between snapshots by reference so that a long
     * document does not retain a fresh copy per step.
     */
    private static final class LyricsHistory {
        private static final long DEBOUNCE_MS = 800;
        private static final int MAX_DEPTH = 60;
        private static final int LARGE_CHANGE_THRESHOLD = 16;

        interface Delegate {
            String getText();
            int getSelectionStart();
            int getSelectionEnd();
            void restore(String text, int selectionStart, int selectionEnd);
            void onHistoryChanged();
        }

        private static final class Snapshot {
            final String[] lines;
            final int selectionStart;
            final int selectionEnd;

            Snapshot(String[] lines, int selectionStart, int selectionEnd) {
                this.lines = lines;
                this.selectionStart = selectionStart;
                this.selectionEnd = selectionEnd;
            }
        }

        private final Delegate delegate;
        private final ArrayDeque<Snapshot> undoStack = new ArrayDeque<>();
        private final ArrayDeque<Snapshot> redoStack = new ArrayDeque<>();
        private Snapshot baseline;
        private boolean dirty;
        private boolean restoring;

        private final Runnable commitRunnable = this::commit;

        LyricsHistory(Delegate delegate) {
            this.delegate = delegate;
            baseline = capture();
        }

        void onTyping() {
            if (restoring) return;
            dirty = true;
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            AndroidUtilities.runOnUIThread(commitRunnable, DEBOUNCE_MS);
            delegate.onHistoryChanged();
        }

        void onBeforeChange(int removed, int added) {
            if (restoring) return;
            if (removed > LARGE_CHANGE_THRESHOLD || added > LARGE_CHANGE_THRESHOLD) {
                flush();
            }
        }

        void flush() {
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            commit();
        }

        void record() {
            if (restoring) return;
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            dirty = true;
            commit();
        }

        /** Re-establishes the baseline as the current content and clears undo/redo. */
        void resetBaseline() {
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            undoStack.clear();
            redoStack.clear();
            baseline = capture();
            dirty = false;
            delegate.onHistoryChanged();
        }

        void detach() {
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            undoStack.clear();
            redoStack.clear();
            baseline = null;
            dirty = false;
        }

        boolean canUndo() {
            return dirty || !undoStack.isEmpty();
        }

        boolean canRedo() {
            return !redoStack.isEmpty();
        }

        void undo() {
            flush();
            if (undoStack.isEmpty()) return;
            redoStack.addLast(baseline);
            baseline = undoStack.removeLast();
            applyRestore(baseline);
        }

        void redo() {
            flush();
            if (redoStack.isEmpty()) return;
            undoStack.addLast(baseline);
            baseline = redoStack.removeLast();
            applyRestore(baseline);
        }

        private void commit() {
            AndroidUtilities.cancelRunOnUIThread(commitRunnable);
            if (!dirty || restoring) return;
            Snapshot now = capture();
            dirty = false;
            if (sameAs(baseline, now)) {
                return;
            }
            undoStack.addLast(baseline);
            while (undoStack.size() > MAX_DEPTH) {
                undoStack.removeFirst();
            }
            redoStack.clear();
            baseline = now;
            delegate.onHistoryChanged();
        }

        private void applyRestore(Snapshot snapshot) {
            dirty = false;
            restoring = true;
            delegate.restore(join(snapshot.lines), snapshot.selectionStart, snapshot.selectionEnd);
            restoring = false;
            delegate.onHistoryChanged();
        }

        private Snapshot capture() {
            final String text = delegate.getText();
            final String[] lines = text.split("\n", -1);
            final String[] previous = baseline == null ? null : baseline.lines;
            if (previous != null) {
                // Share the unchanged head and tail with the previous snapshot by reference; a
                // typical edit touches one line, so only that line is retained per step.
                final int limit = Math.min(previous.length, lines.length);
                int head = 0;
                while (head < limit && previous[head].equals(lines[head])) {
                    lines[head] = previous[head];
                    head++;
                }
                int tail = 0;
                while (tail < limit - head
                        && previous[previous.length - 1 - tail].equals(lines[lines.length - 1 - tail])) {
                    lines[lines.length - 1 - tail] = previous[previous.length - 1 - tail];
                    tail++;
                }
            }
            return new Snapshot(lines, delegate.getSelectionStart(), delegate.getSelectionEnd());
        }

        private static boolean sameAs(Snapshot a, Snapshot b) {
            if (a == null || b == null) return false;
            if (a.lines.length != b.lines.length) return false;
            for (int i = 0; i < a.lines.length; i++) {
                // capture() shares unchanged lines by reference
                if (a.lines[i] != b.lines[i] && !a.lines[i].equals(b.lines[i])) return false;
            }
            return true;
        }

        private static String join(String[] lines) {
            if (lines.length == 0) return "";
            int length = lines.length - 1;
            for (String line : lines) length += line.length();
            StringBuilder builder = new StringBuilder(Math.max(0, length));
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) builder.append('\n');
                builder.append(lines[i]);
            }
            return builder.toString();
        }
    }
}
