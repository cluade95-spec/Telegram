package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.localhistory.LocalDialogIds;
import org.telegram.messenger.localhistory.LocalHistory;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.ProtectedChatAuthSheet;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Lists the chats of the current account that are protected with the passcode and lets the user remove
 * the protection (after authenticating). Rows show identity only (avatar and name): no message content
 * is read or drawn here. Rows come from the protection state and refresh when it changes.
 */
public class ProtectedChatsListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final static int VIEW_TYPE_CHAT = 0;
    private final static int VIEW_TYPE_UNAVAILABLE = 1;
    private final static int VIEW_TYPE_EMPTY = 2;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.protectedChatsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.protectedChatsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.protectedChatsChanged) {
            updateItems(true);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ChatProtectionHeader));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDurations(350);
        itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final ItemInner item = items.get(position);
            if (item.viewType != VIEW_TYPE_EMPTY) {
                confirmRemove(item.dialogId);
            }
        });

        updateItems(false);
        return fragmentView;
    }

    private CharSequence getName(long dialogId) {
        final MessagesController controller = getMessagesController();
        if (LocalDialogIds.isLocal(dialogId)) {
            return LocalHistory.getInstance(currentAccount).getTitle();
        }
        if (dialogId == getUserConfig().getClientUserId()) {
            return LocaleController.getString(R.string.SavedMessages);
        } else if (DialogObject.isEncryptedDialog(dialogId)) {
            final TLRPC.EncryptedChat ec = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            final TLRPC.User user = ec != null ? controller.getUser(ec.user_id) : null;
            return user != null ? org.telegram.messenger.UserObject.getUserName(user) : LocaleController.getString(R.string.ChatProtectionUnavailableChat);
        } else if (dialogId > 0) {
            final TLRPC.User user = controller.getUser(dialogId);
            return user != null ? org.telegram.messenger.UserObject.getUserName(user) : LocaleController.getString(R.string.ChatProtectionUnavailableChat);
        }
        final TLRPC.Chat chat = controller.getChat(-dialogId);
        return chat != null ? chat.title : LocaleController.getString(R.string.ChatProtectionUnavailableChat);
    }

    /** Removing protection needs the passcode, exactly like removing it from the chat list. */
    private void confirmRemove(long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ChatProtectionRemoveTitle))
                .setMessage(LocaleController.formatString(R.string.ChatProtectionRemoveMessage, getName(dialogId)))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.ChatProtectionRemoveConfirm), (dialog, which) ->
                        ProtectedChatGate.authenticate(getParentActivity(), getResourceProvider(), currentAccount, dialogId, ProtectedChatAuthSheet.Mode.UNPROTECT, null))
                .create();
        showDialog(alertDialog);
        ((TextView) alertDialog.getButton(android.app.Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_text_RedBold));
    }

    private void updateItems(boolean animated) {
        oldItems.clear();
        oldItems.addAll(items);
        items.clear();
        final ArrayList<Long> dialogs = ProtectedChats.getProtectedDialogs(currentAccount);
        for (long dialogId : dialogs) {
            items.add(new ItemInner(isAvailable(dialogId) ? VIEW_TYPE_CHAT : VIEW_TYPE_UNAVAILABLE, dialogId));
        }
        if (items.isEmpty()) {
            items.add(new ItemInner(VIEW_TYPE_EMPTY, 0));
        }
        if (adapter == null) {
            return;
        }
        if (animated) {
            adapter.setItems(oldItems, items);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private boolean isAvailable(long dialogId) {
        if (LocalDialogIds.isLocal(dialogId)) {
            return true;
        }
        if (dialogId == getUserConfig().getClientUserId()) {
            return true;
        }
        final MessagesController controller = getMessagesController();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            final TLRPC.EncryptedChat ec = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            return ec != null && controller.getUser(ec.user_id) != null;
        } else if (dialogId > 0) {
            return controller.getUser(dialogId) != null;
        }
        return controller.getChat(-dialogId) != null;
    }

    private static class ItemInner extends AdapterWithDiffUtils.Item {
        public final long dialogId;

        public ItemInner(int viewType, long dialogId) {
            super(viewType, false);
            this.dialogId = dialogId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemInner item = (ItemInner) o;
            return viewType == item.viewType && dialogId == item.dialogId;
        }

        @Override
        protected boolean contentsEquals(AdapterWithDiffUtils.Item item) {
            return equals(item);
        }
    }

    private class ListAdapter extends AdapterWithDiffUtils {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_CHAT) {
                view = new ProfileSearchCell(getContext());
            } else if (viewType == VIEW_TYPE_UNAVAILABLE) {
                view = new TextSettingsCell(getContext());
            } else {
                view = new TextInfoPrivacyCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final ItemInner item = items.get(position);
            final boolean divider = position + 1 < items.size();
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHAT: {
                    final MessagesController controller = getMessagesController();
                    final long dialogId = item.dialogId;
                    final ProfileSearchCell cell = (ProfileSearchCell) holder.itemView;
                    if (LocalDialogIds.isLocal(dialogId)) {
                        cell.setData(null, null, LocalHistory.getInstance(currentAccount).getTitle(), null, false, false);
                    } else if (dialogId == getUserConfig().getClientUserId()) {
                        cell.setData(controller.getUser(dialogId), null, LocaleController.getString(R.string.SavedMessages), null, false, true);
                    } else if (DialogObject.isEncryptedDialog(dialogId)) {
                        final TLRPC.EncryptedChat ec = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                        cell.setData(ec != null ? controller.getUser(ec.user_id) : null, ec, null, null, false, false);
                    } else if (dialogId > 0) {
                        cell.setData(controller.getUser(dialogId), null, null, null, false, false);
                    } else {
                        cell.setData(controller.getChat(-dialogId), null, null, null, false, false);
                    }
                    cell.useSeparator = divider;
                    break;
                }
                case VIEW_TYPE_UNAVAILABLE: {
                    ((TextSettingsCell) holder.itemView).setText(LocaleController.getString(R.string.ChatProtectionUnavailableChat), divider);
                    break;
                }
                case VIEW_TYPE_EMPTY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.ChatProtectionNoChats) + "\n\n" + LocaleController.getString(R.string.ChatProtectionNoChatsInfo));
                    cell.getTextView().setGravity(Gravity.CENTER_HORIZONTAL);
                    break;
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_EMPTY;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_EMPTY;
            }
            return items.get(position).viewType;
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, 0, 0, bottom);
        listView.setClipToPadding(false);
    }
}
