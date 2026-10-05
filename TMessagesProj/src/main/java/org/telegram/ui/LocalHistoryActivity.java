package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.localhistory.LocalHistory;
import org.telegram.messenger.localhistory.LocalHistoryLedger;
import org.telegram.messenger.localhistory.LocalHistoryRenderModel;
import org.telegram.messenger.localhistory.LocalHistoryRepository;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Read-only feed of what other people edited or removed in the owner's private chats (plan B12, B15).
 * Not a ChatActivity: it never has a peer, never sends a request and never opens a dialog with the reserved id.
 */
public class LocalHistoryActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int PAGE = 50;
    private static final int menu_clear = 1;

    private static final int ROW_MESSAGE = 0;
    private static final int ROW_PILL = 1;

    /** One adapter row. Rows are stored newest first because the list is laid out from the bottom. */
    private static final class Row {
        final int type;
        final long stableId;
        MessageObject message;
        long sourceUserId;
        CharSequence text;
        long batchId;

        Row(int type, long stableId) {
            this.type = type;
            this.stableId = stableId;
        }
    }

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private TextView emptyView;
    private Adapter adapter;

    // everything below is only touched on the UI thread, the feature queue hands over finished results
    private final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<LocalHistoryRepository.Entry> loaded = new ArrayList<>(); // newest first
    private final HashSet<Long> expandedBatches = new HashSet<>();
    private boolean endReached;
    private boolean loading;
    private boolean firstLoadDone;
    private int loadToken;
    private LocalHistory.Summary headerSummary;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.localHistoryChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.localHistoryChanged);
        loadToken++;
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.localHistoryChanged && firstLoadDone) {
            reload();
        }
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        Theme.createChatResources(context, false);

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(R.string.LocalHistoryTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == menu_clear) {
                    confirmClear();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(10, R.drawable.ic_ab_other);
        other.addSubItem(menu_clear, R.drawable.msg_delete, LocaleController.getString(R.string.ClearHistory));

        contentView = new SizeNotifierFrameLayout(context);
        fragmentView = contentView;
        contentView.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion());

        listView = new RecyclerListView(context) {
            @Override
            public boolean drawChild(Canvas canvas, View child, long drawingTime) {
                boolean result = super.drawChild(canvas, child, drawingTime);
                if (child instanceof ChatMessageCell) {
                    drawAvatar(canvas, (ChatMessageCell) child);
                }
                return result;
            }
        };
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(4));
        listView.setVerticalScrollBarEnabled(true);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, true);
        listView.setLayoutManager(layoutManager);
        adapter = new Adapter(context);
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (!endReached && !loading && layoutManager.findLastVisibleItemPosition() >= rows.size() - 5) {
                    loadMore();
                }
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        emptyView.setPadding(dp(12), dp(8), dp(12), dp(8));
        emptyView.setText(LocaleController.getString(R.string.LocalHistoryEmpty));
        emptyView.setBackground(Theme.createServiceDrawable(dp(12), emptyView, contentView));
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(240, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        actionBar.setAddToContainer(false);

        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalHistory.getInstance(currentAccount).markAllRead();
    }

    // loading

    private void reload() {
        loaded.clear();
        endReached = false;
        loadMore();
    }

    private void loadMore() {
        if (loading) {
            return;
        }
        loading = true;
        final int token = ++loadToken;
        final int beforeAt = loaded.isEmpty() ? Integer.MAX_VALUE : loaded.get(loaded.size() - 1).lastEventAt;
        final long beforeId = loaded.isEmpty() ? Long.MAX_VALUE : loaded.get(loaded.size() - 1).id;
        final boolean first = loaded.isEmpty();
        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        LocalHistory.getQueue().postRunnable(() -> {
            List<LocalHistoryRepository.Entry> page = localHistory.getRepository().pageFeedBefore(beforeAt, beforeId, PAGE);
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken || fragmentView == null) {
                    return;
                }
                loading = false;
                if (first) {
                    loaded.clear();
                }
                loaded.addAll(page);
                endReached = page.size() < PAGE;
                rebuild(true);
            });
        });
    }

    /** Builds rows from the loaded entries on the feature queue (message layouts are the expensive part). */
    private void rebuild(boolean keepBottom) {
        final int token = loadToken;
        final ArrayList<LocalHistoryRepository.Entry> snapshot = new ArrayList<>(loaded);
        final HashSet<Long> expanded = new HashSet<>(expandedBatches);
        final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
        LocalHistory.getQueue().postRunnable(() -> {
            ArrayList<Row> built = buildRows(localHistory, snapshot, expanded);
            LocalHistory.Summary summary = localHistory.getSummary();
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken || fragmentView == null) {
                    return;
                }
                boolean atBottom = layoutManager.findFirstVisibleItemPosition() <= 0;
                rows.clear();
                rows.addAll(built);
                headerSummary = summary;
                firstLoadDone = true;
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                updateSubtitle();
                if (keepBottom && atBottom) {
                    layoutManager.scrollToPositionWithOffset(0, 0);
                }
            });
        });
    }

    private ArrayList<Row> buildRows(LocalHistory localHistory, ArrayList<LocalHistoryRepository.Entry> newestFirst, HashSet<Long> expanded) {
        ArrayList<LocalHistoryRepository.Entry> ascending = new ArrayList<>(newestFirst);
        Collections.reverse(ascending);
        LocalHistoryRepository repo = localHistory.getRepository();
        HashMap<Long, TLRPC.User> users = new HashMap<>();
        ArrayList<Row> out = new ArrayList<>();
        int lastDay = -1;
        for (List<LocalHistoryRepository.Entry> group : LocalHistoryLedger.groupBatches(ascending)) {
            LocalHistoryRepository.Entry head = group.get(0);
            int day = dayKey(head.lastEventAt);
            if (day != lastDay) {
                lastDay = day;
                Row date = new Row(ROW_PILL, -1000000L - day);
                date.text = LocaleController.formatDateChat(head.lastEventAt);
                out.add(date);
            }
            boolean bulk = head.batchId != 0 && group.size() > 1;
            if (bulk) {
                Row batch = new Row(ROW_PILL, -2000000000L - head.batchId);
                batch.batchId = head.batchId;
                String name = head.nameSnapshot != null ? head.nameSnapshot : LocaleController.getString(R.string.LocalHistoryUnknownSender);
                batch.text = LocaleController.formatString(R.string.LocalHistoryBatch, group.size(), name);
                out.add(batch);
                if (!expanded.contains(head.batchId)) {
                    continue;
                }
            }
            for (LocalHistoryRepository.Entry entry : group) {
                Row message = messageRow(repo, entry, users);
                if (message == null) {
                    continue;
                }
                out.add(message);
                Row status = new Row(ROW_PILL, entry.id * 4 + 1);
                status.text = statusText(entry);
                out.add(status);
            }
        }
        Collections.reverse(out);
        return out;
    }

    private Row messageRow(LocalHistoryRepository repo, LocalHistoryRepository.Entry entry, HashMap<Long, TLRPC.User> users) {
        List<LocalHistoryRepository.Revision> revisions = repo.revisions(entry.id);
        if (revisions.isEmpty()) {
            return null;
        }
        LocalHistoryRepository.Revision latest = revisions.get(revisions.size() - 1);
        long userId = entry.sourceDialogId;
        if (!users.containsKey(userId)) {
            TLRPC.User user = getMessagesController().getUser(userId);
            if (user == null) {
                user = getMessagesStorage().getUserSync(userId);
                if (user != null) {
                    getMessagesController().putUser(user, true);
                }
            }
            if (user != null) {
                users.put(userId, user);
            }
        }
        TLRPC.Message raw = LocalHistoryRenderModel.deserialize(latest.data, latest.text, entry.sourceDate);
        TLRPC.Message safe = LocalHistoryRenderModel.sanitize(raw, entry.id, userId);
        Row row = new Row(ROW_MESSAGE, entry.id * 4);
        row.message = LocalHistoryRenderModel.toMessageObject(currentAccount, safe, entry.id, users);
        row.sourceUserId = userId;
        return row;
    }

    private static CharSequence statusText(LocalHistoryRepository.Entry entry) {
        String time = LocaleController.getInstance().getFormatterDay().format(new Date(entry.lastEventAt * 1000L));
        if (entry.isDeleted() && entry.editCount > 0) {
            return LocaleController.formatString(R.string.LocalHistoryStatusEditedRemoved, time);
        } else if (entry.isDeleted()) {
            return LocaleController.formatString(R.string.LocalHistoryStatusRemoved, time);
        }
        return LocaleController.formatString(R.string.LocalHistoryStatusEdited, entry.editCount + 1);
    }

    private static int dayKey(int seconds) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(seconds * 1000L);
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private void updateSubtitle() {
        LocalHistory.Summary s = headerSummary;
        if (s != null) {
            actionBar.setSubtitle(LocaleController.formatString(R.string.LocalHistorySubtitle, s.deleted, s.edited));
        } else {
            actionBar.setSubtitle(null);
        }
    }

    // actions

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.LocalHistoryTitle));
        builder.setMessage(LocaleController.getString(R.string.LocalHistoryClearConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.ClearHistory), (d, w) -> LocalHistory.getInstance(currentAccount).clearHistory());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmDeleteEntry(long entryId) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.LocalHistoryTitle));
        builder.setMessage(LocaleController.getString(R.string.LocalHistoryDeleteEntry));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            final LocalHistory localHistory = LocalHistory.getInstance(currentAccount);
            LocalHistory.getQueue().postRunnable(() -> {
                localHistory.getLedger().deleteEntry(entryId);
                localHistory.refreshSummary();
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // avatar of the sender, drawn by the list as in the admin log: bottom of the bubble, never over the next row

    private void drawAvatar(Canvas canvas, ChatMessageCell cell) {
        ImageReceiver avatar = cell.getAvatarImage();
        MessageObject message = cell.getMessageObject();
        if (avatar == null || message == null) {
            return;
        }
        int y = (int) cell.getY() + cell.getLayoutHeight();
        int maxY = listView.getMeasuredHeight() - listView.getPaddingBottom();
        if (y > maxY) {
            y = maxY;
        }
        int top = (int) cell.getY();
        if (y - dp(48) < top) {
            y = top + dp(48);
        }
        int cellBottom = (int) (cell.getY() + cell.getMeasuredHeight());
        if (y > cellBottom) {
            y = cellBottom;
        }
        canvas.save();
        avatar.setImageY(y - dp(44));
        avatar.setAlpha(1f);
        avatar.setVisible(true, false);
        avatar.draw(canvas);
        canvas.restore();
    }

    // adapter

    private class Adapter extends RecyclerView.Adapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
            setHasStableIds(true);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public long getItemId(int position) {
            return rows.get(position).stableId;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == ROW_MESSAGE) {
                ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
                cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                    @Override
                    public void didPressUserAvatar(ChatMessageCell c, TLRPC.User user, float touchX, float touchY, boolean asForward) {
                        if (user != null) {
                            Bundle args = new Bundle();
                            args.putLong("user_id", user.id);
                            presentFragment(new ProfileActivity(args));
                        }
                    }

                    @Override
                    public void didLongPress(ChatMessageCell c, float x, float y) {
                        MessageObject message = c.getMessageObject();
                        if (message != null) {
                            confirmDeleteEntry(message.eventId);
                        }
                    }

                    @Override
                    public void didPressUrl(ChatMessageCell c, android.text.style.CharacterStyle url, boolean longPress) {
                        if (!longPress && url instanceof URLSpan && getParentActivity() != null) {
                            Browser.openUrl(getParentActivity(), ((URLSpan) url).getURL());
                        }
                    }

                    @Override
                    public boolean canPerformActions() {
                        return false;
                    }

                    @Override
                    public boolean canPerformReply() {
                        return false;
                    }
                });
                view = cell;
            } else {
                view = new PillCell(context);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == ROW_MESSAGE) {
                ChatMessageCell cell = (ChatMessageCell) holder.itemView;
                cell.isChat = true;
                cell.setMessageObject(row.message, null, false, false, false);
                cell.setHighlighted(false);
            } else {
                PillCell pill = (PillCell) holder.itemView;
                pill.set(row.text);
                final long batchId = row.batchId;
                pill.setOnClickListener(batchId == 0 ? null : v -> {
                    if (!expandedBatches.remove(batchId)) {
                        expandedBatches.add(batchId);
                    }
                    rebuild(false);
                });
            }
        }
    }

    /** Date separators, status lines and collapsed bulk removals share one centered service pill. */
    private class PillCell extends FrameLayout {

        private final TextView text;

        PillCell(Context context) {
            super(context);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
            text.setGravity(Gravity.CENTER);
            text.setPadding(dp(10), dp(4), dp(10), dp(4));
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setBackground(Theme.createServiceDrawable(dp(12), text, contentView));
            addView(text, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 16, 4, 16, 4));
        }

        void set(CharSequence value) {
            text.setText(value);
            text.setTextColor(Theme.getColor(Theme.key_chat_serviceText));
        }
    }
}
