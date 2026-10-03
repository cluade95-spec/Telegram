package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Details of individual chat protection: preview hiding, the protected chats' own Auto-lock and the
 * list of protected chats. The on/off switch is on the Passcode Lock page. Same page structure as the
 * other Telegram settings pages (grouped rows with explanatory info cells between groups).
 */
public class ProtectedChatsSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final static int VIEW_TYPE_CHECK = 0;
    private final static int VIEW_TYPE_SETTING = 1;
    private final static int VIEW_TYPE_INFO = 2;

    private final int hidePreviewRow = 0;
    private final int hidePreviewInfoRow = 1;
    private final int autoLockRow = 2;
    private final int autoLockInfoRow = 3;
    private final int chatsRow = 4;
    private final int rowCount = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

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
            // Only the count depends on protection changes.
            adapter.notifyItemChanged(chatsRow);
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
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position == hidePreviewRow) {
                final boolean hide = !ProtectedChats.getState().isHidePreviewWhenLocked();
                ProtectedChats.setHidePreviewWhenLocked(hide);
                ((TextCheckCell) view).setChecked(hide);
            } else if (position == autoLockRow) {
                showAutoLockDialog((TextSettingsCell) view);
            } else if (position == chatsRow) {
                presentFragment(new ProtectedChatsListActivity());
            }
        });
        return fragmentView;
    }

    private String autoLockValue(int seconds) {
        if (seconds == 0) {
            return LocaleController.getString(R.string.ChatProtectionRelockImmediately);
        } else if (seconds < 60 * 60) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", seconds / 60));
        }
        return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", seconds / 60 / 60));
    }

    private void showAutoLockDialog(TextSettingsCell cell) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] choices = ProtectedChatsState.RELOCK_CHOICES;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AutoLock));
        final NumberPicker numberPicker = new NumberPicker(getParentActivity());
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(choices.length - 1);
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == ProtectedChats.getState().getRelockSeconds()) {
                numberPicker.setValue(i);
            }
        }
        numberPicker.setFormatter(value -> autoLockValue(choices[value]));
        builder.setView(numberPicker);
        builder.setNegativeButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            ProtectedChats.getState().setRelockSeconds(choices[numberPicker.getValue()]);
            cell.setTextAndValue(LocaleController.getString(R.string.AutoLock), autoLockValue(ProtectedChats.getState().getRelockSeconds()), false);
        });
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            return position == hidePreviewRow || position == autoLockRow || position == chatsRow;
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
                view = new TextInfoPrivacyCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHECK: {
                    ((TextCheckCell) holder.itemView).setTextAndCheck(LocaleController.getString(R.string.ChatProtectionHidePreview), ProtectedChats.getState().isHidePreviewWhenLocked(), false);
                    break;
                }
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == autoLockRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.AutoLock), autoLockValue(ProtectedChats.getState().getRelockSeconds()), false);
                    } else {
                        final int count = ProtectedChats.getProtectedDialogs(currentAccount).size();
                        cell.setTextAndValue(LocaleController.getString(R.string.Chats), count == 0 ? LocaleController.getString(R.string.None) : Integer.toString(count), false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == hidePreviewInfoRow ? R.string.ChatProtectionHidePreviewInfo : R.string.ChatProtectionRelockInfo));
                    cell.getTextView().setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == hidePreviewRow) {
                return VIEW_TYPE_CHECK;
            } else if (position == autoLockRow || position == chatsRow) {
                return VIEW_TYPE_SETTING;
            }
            return VIEW_TYPE_INFO;
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
