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
import android.text.Layout;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Locale;

/** A single raw-text editing surface for the current track's local lyrics source. */
public class SyncedLyricsEditorFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int DONE = 1;
    private static final int OTHER = 2;
    private static final int IMPORT = 3;
    private static final int DELETE = 4;
    private static final int PICK_LRC = 41;
    private static final int MAX_SIZE = 1024 * 1024;

    private final MessageObject messageObject;
    private EditTextBoldCursor editText;
    private ActionBarMenuItem doneButton;
    private ActionBarMenuItem otherButton;
    private RadialProgressView progressView;
    private String initialSource = "";
    private String restoredSource;
    private int restoredSelection = -1;
    private boolean changedByUser;
    private boolean saving;
    private boolean importing;
    private boolean editorReady;
    private boolean readErrorShown;

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
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        SyncedLyricsController controller = SyncedLyricsController.getInstance(currentAccount);
        controller.retryIfFailed(messageObject);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.SyncedLyrics));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) finishFragment();
                } else if (id == DONE) {
                    save();
                } else if (id == IMPORT) {
                    importFile();
                } else if (id == DELETE) {
                    confirmDelete();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        otherButton = menu.addItem(OTHER, R.drawable.ic_ab_other);
        otherButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        doneButton = menu.addItemWithWidth(DONE, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneButton.setContentDescription(LocaleController.getString(R.string.Save));

        editText = new EditTextBoldCursor(context);
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
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changedByUser = true; }
            @Override public void afterTextChanged(Editable s) { }
        });
        changedByUser = restoredSource != null && !initialSource.equals(restoredSource);
        updateOtherMenu();
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
        doneButton.setEnabled(!loading && !saving);
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
            editText.setText(initialSource);
            editText.setSelection(editText.length());
            changedByUser = false;
        }
        updateOtherMenu();
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
        if (!initialSource.isEmpty()) {
            ActionBarMenuSubItem delete = otherButton.addSubItem(DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.DeleteLyrics));
            delete.setColors(getThemedColor(Theme.key_text_RedRegular), getThemedColor(Theme.key_text_RedRegular));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.syncedLyricsChanged && editText != null && !saving && !importing) {
            applyControllerState(!changedByUser);
        }
    }

    private void save() {
        if (saving || !editorReady) return;
        saving = true;
        doneButton.setEnabled(false);
        String source = editText.getText().toString();
        SyncedLyricsController.getInstance(currentAccount).save(messageObject, source, success -> {
            saving = false;
            if (isFinished || getParentActivity() == null) return;
            doneButton.setEnabled(true);
            if (success) {
                initialSource = source;
                finishAndReturnToPlayer();
            } else {
                showError(R.string.LyricsSaveFailed);
            }
        });
    }

    private void importFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/x-subrip", "application/octet-stream"});
            startActivityForResult(intent, PICK_LRC);
        } catch (Exception e) {
            FileLog.e(e);
            showError(R.string.LyricsImportFailed);
        }
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
                    if (name != null) {
                        name = name.toLowerCase(Locale.US);
                        if (!name.endsWith(".lrc") && !name.endsWith(".txt")) throw new IllegalArgumentException("Unsupported lyrics file");
                    }
                }
            }
            byte[] buffer = new byte[8192];
            int total = 0, count;
            while (stream != null && (count = stream.read(buffer)) != -1) {
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
                    editText.setText(result);
                    editText.setSelection(editText.length());
                    changedByUser = true;
                    updateOtherMenu();
                }
            });
        });
    }

    private void confirmDelete() {
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
        if (saving) return;
        saving = true;
        doneButton.setEnabled(false);
        SyncedLyricsController.getInstance(currentAccount).delete(messageObject, success -> {
            saving = false;
            if (isFinished || getParentActivity() == null) return;
            doneButton.setEnabled(true);
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
        AndroidUtilities.runOnUIThread(() -> {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (!activity.isFinishing() && playing != null && playing.isMusic() && AudioPlayerAlert.instance == null) {
                new AudioPlayerAlert(activity, provider).showLyricsWhenAvailable().show();
            }
        }, 180);
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
            AndroidUtilities.showKeyboard(editText);
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
}
