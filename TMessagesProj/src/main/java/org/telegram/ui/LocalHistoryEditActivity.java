package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.localhistory.LocalHistory;
import org.telegram.messenger.localhistory.LocalHistoryScreen;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LocalHistoryAvatarView;

/**
 * Local name and photo of the Local History chat (plan B18). Both live only in the feature database and directory;
 * no Telegram API is involved. The picture comes from the system picker and the stock crop screen, never from
 * ImageUpdater, whose processing always uploads the result.
 */
public class LocalHistoryEditActivity extends BaseFragment implements LocalHistoryScreen {

    private static final int done_button = 1;
    private static final int REQUEST_PICK = 14;

    private EditTextBoldCursor nameField;
    private LocalHistoryAvatarView avatarView;
    private TextView removePhoto;
    private Bitmap newPhoto;
    private boolean removeRequested;

    @Override
    public boolean isLocalHistoryConversation() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LocalHistoryEditTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    save();
                }
            }
        });
        actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, dp(56), LocaleController.getString(R.string.Done));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        avatarView = new LocalHistoryAvatarView(context, 96);
        avatarView.setPhoto(localHistory.getPhoto(), 96);
        avatarView.setOnClickListener(v -> pickPhoto());
        content.addView(avatarView, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 8));

        TextView setPhoto = link(context, LocaleController.getString(R.string.LocalHistorySetPhoto), Theme.key_windowBackgroundWhiteBlueText4);
        setPhoto.setOnClickListener(v -> pickPhoto());
        content.addView(setPhoto, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        removePhoto = link(context, LocaleController.getString(R.string.LocalHistoryRemovePhoto), Theme.key_text_RedRegular);
        removePhoto.setOnClickListener(v -> {
            newPhoto = null;
            removeRequested = true;
            avatarView.setPhoto(null, 96);
            updateRemove();
        });
        content.addView(removePhoto, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        updateRemove();

        nameField = new EditTextBoldCursor(context);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        nameField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        nameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameField.setBackground(null);
        nameField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameField.setHint(LocaleController.getString(R.string.LocalHistoryNameHint));
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setSingleLine(true);
        nameField.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        nameField.setText(localHistory.hasCustomTitle() ? localHistory.getTitle() : "");
        nameField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        content.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 20, 16, 20, 8));
        return fragmentView;
    }

    private static TextView link(Context context, String text, int colorKey) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(colorKey));
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        return view;
    }

    private void updateRemove() {
        boolean has = !removeRequested && (newPhoto != null || LocalHistory.getInstance(currentAccount).getPhoto() != null);
        removePhoto.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private void pickPhoto() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        Bundle args = new Bundle();
        args.putParcelable("photoUri", uri);
        PhotoCropActivity crop = new PhotoCropActivity(args);
        crop.setDelegate(bitmap -> {
            if (bitmap != null) {
                newPhoto = bitmap;
                removeRequested = false;
                avatarView.setPhoto(bitmap, 96);
                updateRemove();
            }
        });
        presentFragment(crop);
    }

    private void save() {
        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        localHistory.setTitle(nameField.getText() == null ? null : nameField.getText().toString());
        if (newPhoto != null) {
            localHistory.setPhoto(newPhoto);
        } else if (removeRequested) {
            localHistory.setPhoto(null);
        }
        AndroidUtilities.hideKeyboard(nameField);
        finishFragment();
    }
}
