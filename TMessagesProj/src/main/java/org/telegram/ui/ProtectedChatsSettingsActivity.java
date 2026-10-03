package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
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
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Options of individual chat protection, reached from Passcode Lock. Follows the structure of
 * {@link ArchiveSettingsActivity}: grouped rows built from a list of items, switches that animate
 * themselves, and diff-based insertion/removal of dependent rows (no page rebuilds).
 */
public class ProtectedChatsSettingsActivity extends BaseFragment {

    private final static int VIEW_TYPE_CHECK = 0;
    private final static int VIEW_TYPE_SETTING = 1;
    private final static int VIEW_TYPE_SHADOW = 2;

    private final static int ID_ENABLE = 1;
    private final static int ID_ENABLE_INFO = 2;
    private final static int ID_HIDE_PREVIEW = 3;
    private final static int ID_RELOCK = 4;
    private final static int ID_OPTIONS_INFO = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<ItemInner> oldItems = new ArrayList<>(), items = new ArrayList<>();

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
            if (item.id == ID_ENABLE) {
                toggleFeature((TextCheckCell) view);
            } else if (item.id == ID_HIDE_PREVIEW) {
                final boolean hide = !ProtectedChats.getState().isHidePreviewWhenLocked();
                ProtectedChats.setHidePreviewWhenLocked(hide);
                ((TextCheckCell) view).setChecked(hide);
            } else if (item.id == ID_RELOCK) {
                showRelockDialog((TextSettingsCell) view);
            }
        });

        updateItems(false);
        return fragmentView;
    }

    private void toggleFeature(TextCheckCell cell) {
        if (!ProtectedChats.isFeatureEnabled()) {
            if (ProtectedChats.enableFeature() == ProtectedChatsState.Result.OK) {
                cell.setChecked(true);
                updateItems(true);
            }
            return;
        }
        final int count = ProtectedChats.getState().protectedCount();
        if (count == 0) {
            ProtectedChats.disableFeatureRemovingAllProtection();
            cell.setChecked(false);
            updateItems(true);
            return;
        }
        // Turning the feature off removes every protection in one deliberate step.
        AlertDialog alertDialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ChatProtectionDisableTitle))
                .setMessage(LocaleController.formatString(R.string.ChatProtectionDisableMessage, count))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.ChatProtectionDisableConfirm), (dialog, which) -> {
                    ProtectedChats.disableFeatureRemovingAllProtection();
                    cell.setChecked(false);
                    updateItems(true);
                }).create();
        showDialog(alertDialog);
        ((TextView) alertDialog.getButton(Dialog.BUTTON_POSITIVE)).setTextColor(Theme.getColor(Theme.key_text_RedBold));
    }

    private String relockValue(int seconds) {
        if (seconds == 0) {
            return LocaleController.getString(R.string.ChatProtectionRelockImmediately);
        } else if (seconds < 60 * 60) {
            return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Minutes", seconds / 60));
        }
        return LocaleController.formatString("AutoLockInTime", R.string.AutoLockInTime, LocaleController.formatPluralString("Hours", seconds / 60 / 60));
    }

    private void showRelockDialog(TextSettingsCell cell) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] choices = ProtectedChatsState.RELOCK_CHOICES;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ChatProtectionRelock));
        final NumberPicker numberPicker = new NumberPicker(getParentActivity());
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(choices.length - 1);
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == ProtectedChats.getState().getRelockSeconds()) {
                numberPicker.setValue(i);
            }
        }
        numberPicker.setFormatter(value -> relockValue(choices[value]));
        builder.setView(numberPicker);
        builder.setNegativeButton(LocaleController.getString(R.string.Done), (dialog, which) -> {
            ProtectedChats.getState().setRelockSeconds(choices[numberPicker.getValue()]);
            cell.setTextAndValue(LocaleController.getString(R.string.ChatProtectionRelock), relockValue(ProtectedChats.getState().getRelockSeconds()), false);
        });
        showDialog(builder.create());
    }

    private void updateItems(boolean animated) {
        oldItems.clear();
        oldItems.addAll(items);
        items.clear();

        items.add(new ItemInner(VIEW_TYPE_CHECK, ID_ENABLE, LocaleController.getString(R.string.ChatProtectionEnable)));
        items.add(new ItemInner(VIEW_TYPE_SHADOW, ID_ENABLE_INFO, LocaleController.getString(R.string.ChatProtectionEnableInfo)));
        if (ProtectedChats.isFeatureEnabled()) {
            items.add(new ItemInner(VIEW_TYPE_CHECK, ID_HIDE_PREVIEW, LocaleController.getString(R.string.ChatProtectionHidePreview)));
            items.add(new ItemInner(VIEW_TYPE_SETTING, ID_RELOCK, LocaleController.getString(R.string.ChatProtectionRelock)));
            items.add(new ItemInner(VIEW_TYPE_SHADOW, ID_OPTIONS_INFO, LocaleController.getString(R.string.ChatProtectionHidePreviewInfo) + "\n\n" + LocaleController.getString(R.string.ChatProtectionRelockInfo)));
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

    private static class ItemInner extends AdapterWithDiffUtils.Item {
        public final CharSequence text;
        public final int id;

        public ItemInner(int viewType, int id, CharSequence text) {
            super(viewType, false);
            this.id = id;
            this.text = text;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemInner item = (ItemInner) o;
            return id == item.id && Objects.equals(text, item.text);
        }

        @Override
        protected boolean contentsEquals(AdapterWithDiffUtils.Item item) {
            // Switch state is applied by the cell itself (animated); unchanged rows are never rebound.
            return equals(item);
        }
    }

    private class ListAdapter extends AdapterWithDiffUtils {
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
            if (position < 0 || position >= items.size()) {
                return;
            }
            final ItemInner item = items.get(position);
            final int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_SHADOW) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (TextUtils.isEmpty(item.text)) {
                    cell.setFixedSize(12);
                    cell.setText(null);
                } else {
                    cell.setFixedSize(0);
                    cell.setText(item.text);
                }
            } else if (viewType == VIEW_TYPE_CHECK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (item.id == ID_ENABLE) {
                    cell.setTextAndCheck(item.text, ProtectedChats.isFeatureEnabled(), false);
                } else if (item.id == ID_HIDE_PREVIEW) {
                    cell.setTextAndCheck(item.text, ProtectedChats.getState().isHidePreviewWhenLocked(), true);
                }
            } else if (viewType == VIEW_TYPE_SETTING) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue(item.text, relockValue(ProtectedChats.getState().getRelockSeconds()), false);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_SHADOW;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_SHADOW;
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
