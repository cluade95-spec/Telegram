package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.R;
import org.telegram.messenger.localhistory.LocalDialogIds;
import org.telegram.messenger.localhistory.LocalHistory;
import org.telegram.messenger.localhistory.LocalHistoryScreen;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LocalHistoryAvatarView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Info page of the Local History chat (plan B19). Not a ProfileActivity: that class is built around real peers.
 * Counts as the conversation itself for Protected Chats, so a locked chat asks before this page opens too.
 */
public class LocalHistoryProfileActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate, LocalHistoryScreen {

    private static final int BUTTON_EDIT = 1;
    private static final int BUTTON_LOCK = 2;
    private static final int BUTTON_DELETE_MEDIA = 3;
    private static final int BUTTON_CLEAR = 4;

    private long savedBytes = -1;
    private LocalHistoryAvatarView avatarView;
    private TextView nameView;
    private TextView countView;

    @Override
    public boolean isLocalHistoryConversation() {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.localHistoryChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.protectedChatsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.localHistoryChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.protectedChatsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.localHistoryChanged || id == NotificationCenter.protectedChatsChanged) {
            bindHeader();
            loadSize();
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    @Override
    public View createView(Context context) {
        viewContext = context;
        header = null;
        View view = super.createView(context);
        loadSize();
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.Info);
    }

    private void loadSize() {
        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        LocalHistory.getQueue().postRunnable(() -> {
            final long bytes = localHistory.savedMediaBytes();
            AndroidUtilities.runOnUIThread(() -> {
                if (bytes != savedBytes) {
                    savedBytes = bytes;
                    if (listView != null) {
                        listView.adapter.update(true);
                    }
                }
            });
        });
    }

    private View createHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        avatarView = new LocalHistoryAvatarView(context, 84);
        header.addView(avatarView, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 8));
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameView.setSingleLine();
        header.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        countView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        header.addView(countView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 2, 16, 12));
        bindHeader();
        return header;
    }

    private void bindHeader() {
        if (nameView == null) {
            return;
        }
        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        nameView.setText(localHistory.getTitle());
        avatarView.setPhoto(localHistory.getPhoto(), 84);
        LocalHistory.Summary s = localHistory.getSummary();
        countView.setText(s == null ? "" : LocaleController.formatString(R.string.LocalHistorySubtitle, s.deleted, s.edited));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(createHeaderOnce(), 150));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(BUTTON_EDIT, R.drawable.msg_edit, getString(R.string.Edit)));
        if (ProtectedChats.isLockSettingsAvailable(currentAccount, LocalDialogIds.LOCAL_HISTORY)) {
            items.add(UItem.asButton(BUTTON_LOCK, R.drawable.msg_settings, getString(R.string.ChatLockSettings)));
        }
        items.add(UItem.asShadow(savedBytes < 0 ? null : LocaleController.formatString(R.string.LocalHistorySavedMediaSize, AndroidUtilities.formatFileSize(savedBytes))));
        if (savedBytes > 0) {
            items.add(UItem.asButton(BUTTON_DELETE_MEDIA, R.drawable.msg_clearcache, getString(R.string.LocalHistoryDeleteMedia)).red());
        }
        items.add(UItem.asButton(BUTTON_CLEAR, R.drawable.msg_delete, getString(R.string.ClearHistory)).red());
        items.add(UItem.asShadow(getString(R.string.LocalHistoryAbout)));
    }

    private View header;
    private Context viewContext;

    private View createHeaderOnce() {
        if (header == null) {
            header = createHeader(viewContext);
        }
        return header;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_EDIT) {
            presentFragment(new LocalHistoryEditActivity());
        } else if (item.id == BUTTON_LOCK) {
            presentFragment(new ChatLockSettingsActivity(LocalDialogIds.LOCAL_HISTORY));
        } else if (item.id == BUTTON_DELETE_MEDIA) {
            confirm(R.string.LocalHistoryDeleteMediaConfirm, R.string.Delete, () -> LocalHistory.getInstance(currentAccount).deleteSavedMedia());
        } else if (item.id == BUTTON_CLEAR) {
            confirm(R.string.LocalHistoryClearConfirm, R.string.ClearHistory, () -> LocalHistory.getInstance(currentAccount).clearHistory());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void confirm(int message, int button, Runnable action) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.LocalHistoryTitle));
        builder.setMessage(getString(message));
        builder.setPositiveButton(getString(button), (d, w) -> action.run());
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }
}
