package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Details of the app-wide lock: Auto-lock and the task switcher content setting. The on/off switch
 * lives on the Passcode Lock page. Turning the app lock off never touches the passcode or protected chats.
 */
public class AppLockSettingsActivity extends BaseFragment {

    private final static int VIEW_TYPE_SETTING = 0;
    private final static int VIEW_TYPE_INFO = 1;
    private final static int VIEW_TYPE_HEADER = 2;
    private final static int VIEW_TYPE_CHECK = 3;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private int autoLockRow = 0;
    private int autoLockInfoRow = 1;
    private int captureHeaderRow = 2;
    private int captureRow = 3;
    private int captureInfoRow = 4;
    private final int rowCount = 5;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AppLockTitle));
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
            if (position == autoLockRow) {
                showAutoLockDialog(position);
            } else if (position == captureRow) {
                SharedConfig.allowScreenCapture = !SharedConfig.allowScreenCapture;
                UserConfig.getInstance(currentAccount).saveConfig(false);
                ((TextCheckCell) view).setChecked(SharedConfig.allowScreenCapture);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetPasscode, false);
                if (!SharedConfig.allowScreenCapture) {
                    AlertsCreator.showSimpleAlert(AppLockSettingsActivity.this, LocaleController.getString(R.string.ScreenCaptureAlert));
                }
            }
        });
        return fragmentView;
    }

    private String autoLockValue() {
        if (SharedConfig.autoLockIn == 0) {
            return LocaleController.getString(R.string.AutoLockDisabled);
        } else if (SharedConfig.autoLockIn < 60 * 60) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", SharedConfig.autoLockIn / 60));
        } else if (SharedConfig.autoLockIn < 60 * 60 * 24) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", (int) Math.ceil(SharedConfig.autoLockIn / 60.0f / 60)));
        }
        return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Days", (int) Math.ceil(SharedConfig.autoLockIn / 60.0f / 60 / 24)));
    }

    private final static int[] AUTO_LOCK_CHOICES = {0, 60, 60 * 5, 60 * 60, 60 * 60 * 5};

    /** Same choices and storage as before the setting moved to this page. */
    private void showAutoLockDialog(int position) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AutoLock));
        final NumberPicker numberPicker = new NumberPicker(getParentActivity());
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(AUTO_LOCK_CHOICES.length - 1);
        for (int i = 0; i < AUTO_LOCK_CHOICES.length; i++) {
            if (AUTO_LOCK_CHOICES[i] == SharedConfig.autoLockIn) {
                numberPicker.setValue(i);
            }
        }
        numberPicker.setFormatter(value -> {
            if (value == 0) {
                return LocaleController.getString(R.string.AutoLockDisabled);
            } else if (value == 1) {
                return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", 1));
            } else if (value == 2) {
                return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", 5));
            } else if (value == 3) {
                return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", 1));
            } else if (value == 4) {
                return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", 5));
            }
            return "";
        });
        builder.setView(numberPicker);
        builder.setNegativeButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            SharedConfig.autoLockIn = AUTO_LOCK_CHOICES[numberPicker.getValue()];
            adapter.notifyItemChanged(position);
            UserConfig.getInstance(currentAccount).saveConfig(false);
        });
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            return position == autoLockRow || position == captureRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SETTING:
                    view = new TextSettingsCell(getContext());
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(getContext());
                    break;
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(getContext());
                    break;
                case VIEW_TYPE_INFO:
                default:
                    view = new TextInfoPrivacyCell(getContext());
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(LocaleController.getString(R.string.AutoLock), autoLockValue(), false);
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setHeight(46);
                    cell.setText(LocaleController.getString(R.string.ScreenCaptureHeader));
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(R.string.ScreenCaptureShowContent), SharedConfig.allowScreenCapture, false);
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(position == autoLockInfoRow ? R.string.AutoLockInfo : R.string.ScreenCaptureInfo));
                    cell.getTextView().setGravity(LocaleController.isRTL ? android.view.Gravity.RIGHT : android.view.Gravity.LEFT);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == autoLockRow) {
                return VIEW_TYPE_SETTING;
            } else if (position == captureHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == captureRow) {
                return VIEW_TYPE_CHECK;
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
