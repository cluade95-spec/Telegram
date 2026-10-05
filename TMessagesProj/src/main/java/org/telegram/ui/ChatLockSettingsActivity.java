package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProtectedChatAuthSheet;
import org.telegram.ui.Components.RecyclerListView;

/**
 * The lock settings of one chat, opened from the three-dot menu of the chat's profile: Chat Lock
 * (whether this chat is protected), Hide Message Previews and Auto-lock for this chat. It works for
 * a chat that is protected and for one that is not. The values live in the protected chats state
 * ({@link ProtectedChatsState}); this screen only shows and changes them, with the same
 * authentication the chat list asks for (the existing chat lock sheet, the shared passcode). The
 * protected-chat gate treats this screen as part of the chat it belongs to
 * ({@link ProtectedChatGate#getDialogId}).
 */
public class ChatLockSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final String ARG_DIALOG_ID = "lock_dialog_id";

    private final static int VIEW_TYPE_CHECK = 0;
    private final static int VIEW_TYPE_SETTING = 1;
    private final static int VIEW_TYPE_SHADOW = 2;

    private final int chatLockRow = 0;
    private final int shadowRow = 1;
    private final int hidePreviewRow = 2;
    private final int autoLockRow = 3;
    private final int rowCount = 4;

    private final long dialogId;
    /** Back from the existing passcode setup flow: continue enabling Chat Lock if a passcode exists now. */
    private boolean protectAfterPasscodeSetup;

    private RecyclerListView listView;
    private ListAdapter adapter;

    public ChatLockSettingsActivity(long dialogId) {
        super(argumentsFor(dialogId));
        this.dialogId = dialogId;
    }

    private static Bundle argumentsFor(long dialogId) {
        final Bundle args = new Bundle();
        args.putLong(ARG_DIALOG_ID, dialogId);
        return args;
    }

    /** The dialog these settings belong to. */
    public long getLockDialogId() {
        return dialogId;
    }

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
        if (id == NotificationCenter.protectedChatsChanged && adapter != null) {
            adapter.notifyItemChanged(chatLockRow);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ChatLockSettings));
        actionBar.setSubtitle(ProtectedChatGate.getTitle(currentAccount, dialogId));
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
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position == chatLockRow) {
                onChatLockClicked();
            } else if (position == hidePreviewRow) {
                final boolean hide = !ProtectedChats.getHidePreview(currentAccount, dialogId);
                if (ProtectedChats.setChatHidePreview(currentAccount, dialogId, hide) == ProtectedChatsState.Result.OK) {
                    ((TextCheckCell) view).setChecked(hide);
                }
            } else if (position == autoLockRow) {
                ProtectedChatsSettingsActivity.showAutoLockDialog(this, ProtectedChats.getRelockSeconds(currentAccount, dialogId), seconds -> {
                    if (ProtectedChats.setChatRelockSeconds(currentAccount, dialogId, seconds) == ProtectedChatsState.Result.OK) {
                        adapter.notifyItemChanged(autoLockRow);
                    }
                });
            }
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (protectAfterPasscodeSetup) {
            protectAfterPasscodeSetup = false;
            if (SharedConfig.hasPasscode()) {
                // same hand-over as the chat list uses after the passcode setup screen closed
                AndroidUtilities.runOnUIThread(this::protect, 300);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ---------------------------------------------------------------- Chat Lock

    private void onChatLockClicked() {
        if (ProtectedChats.isProtected(currentAccount, dialogId)) {
            authenticate(ProtectedChatAuthSheet.Mode.UNPROTECT);
        } else {
            protect();
        }
    }

    private void protect() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        if (!SharedConfig.hasPasscode()) {
            // The existing passcode setup remains the only place a credential is created.
            AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.ChatPasscodeRequiredTitle))
                    .setMessage(LocaleController.getString(R.string.ChatPasscodeRequiredMessage))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.ChatPasscodeRequiredButton), (d, w) -> {
                        protectAfterPasscodeSetup = true;
                        presentFragment(PasscodeActivity.determineOpenFragment());
                    })
                    .create();
            showDialog(dialog);
            return;
        }
        if (!ProtectedChats.isFeatureEnabled() && ProtectedChats.enableFeature() != ProtectedChatsState.Result.OK) {
            return;
        }
        authenticate(ProtectedChatAuthSheet.Mode.PROTECT);
    }

    /** Protecting and unprotecting always ask for the passcode, here as in the chat list; cancelling changes nothing. */
    private void authenticate(ProtectedChatAuthSheet.Mode mode) {
        if (getParentActivity() == null) {
            return;
        }
        ProtectedChatAuthSheet.show(getParentActivity(), getResourceProvider(), mode, ProtectedChatGate.getTitle(currentAccount, dialogId), new ProtectedChatAuthSheet.Callback() {
            @Override
            public void onAuthenticated(ProtectedChatsState.AuthProof proof) {
                final ProtectedChatsState.Result result = mode == ProtectedChatAuthSheet.Mode.PROTECT
                        ? ProtectedChatGate.protectWhileOpen(ChatLockSettingsActivity.this, dialogId, proof)
                        : ProtectedChats.unprotect(currentAccount, dialogId, proof);
                if (result == ProtectedChatsState.Result.OK) {
                    if (adapter != null) {
                        adapter.notifyItemChanged(chatLockRow);
                    }
                    BulletinFactory.of(ChatLockSettingsActivity.this).createSimpleBulletin(
                            mode == ProtectedChatAuthSheet.Mode.PROTECT ? R.raw.passcode_lock_close : R.raw.passcode_lock,
                            LocaleController.getString(mode == ProtectedChatAuthSheet.Mode.PROTECT ? R.string.ChatPasscodeProtected : R.string.ChatPasscodeUnprotected)).show();
                }
            }
        });
    }

    // ---------------------------------------------------------------- list

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(getContext());
            } else if (viewType == VIEW_TYPE_SETTING) {
                view = new TextSettingsCell(getContext());
            } else {
                view = new ShadowSectionCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == chatLockRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.ChatLock), ProtectedChats.isProtected(currentAccount, dialogId), false);
                    } else {
                        cell.setTextAndCheck(LocaleController.getString(R.string.ChatProtectionHidePreview), ProtectedChats.getHidePreview(currentAccount, dialogId), true);
                    }
                    break;
                }
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(LocaleController.getString(R.string.AutoLock), ProtectedChatsSettingsActivity.autoLockValue(ProtectedChats.getRelockSeconds(currentAccount, dialogId)), false);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == chatLockRow || position == hidePreviewRow) {
                return VIEW_TYPE_CHECK;
            } else if (position == autoLockRow) {
                return VIEW_TYPE_SETTING;
            }
            return VIEW_TYPE_SHADOW;
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
