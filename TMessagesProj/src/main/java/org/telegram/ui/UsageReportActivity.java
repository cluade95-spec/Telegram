package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.usage.UsageDays;
import org.telegram.messenger.usage.UsageReportMath;
import org.telegram.messenger.usage.UsageStore;
import org.telegram.messenger.usage.UsageSurface;
import org.telegram.messenger.usage.UsageTracker;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Settings > Activity: where the time spent in Telegram went. Everything shown is computed from the local usage store;
 * no request is made (Reference A, A15-A18).
 */
public class UsageReportActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_SHOW_MORE = 100;
    private static final int ID_RESET = 101;
    private static final int ID_INFO = 102;
    private static final int TOP_COLLAPSED = 10;
    private static final int TOP_EXPANDED = 50;

    private static class DialogEntry {
        UsageReportMath.DialogRow row;
        int accountIndex = -1;
        TLObject object;
        TLRPC.EncryptedChat encryptedChat;
        boolean saved;
    }

    private UsageReportMath.Period period = UsageReportMath.Period.TODAY;
    private UsageReportMath.Report report;
    private final ArrayList<DialogEntry> entries = new ArrayList<>();
    private boolean showAll;
    private int loadToken;

    private PeriodSwitcher switcher;
    private SummaryView summary;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        loadToken++;
        super.onFragmentDestroy();
    }

    @Override
    public android.view.View createView(Context context) {
        switcher = new PeriodSwitcher(context);
        summary = new SummaryView(context);
        View view = super.createView(context);
        reload();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (switcher != null) {
            reload();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme && listView != null) {
            listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            switcher.updateColors();
            summary.updateColors();
            listView.adapter.update(false);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.UsageActivityTitle);
    }

    // data

    private static int today() {
        long now = System.currentTimeMillis();
        return UsageDays.dayOfLocal(now + java.util.TimeZone.getDefault().getOffset(now));
    }

    private int firstDow() {
        return UsageReportMath.firstDowMonday0(Calendar.getInstance().getFirstDayOfWeek());
    }

    private void reload() {
        final int token = ++loadToken;
        final UsageReportMath.Period p = period;
        final int today = today();
        final int firstDow = firstDow();
        UsageTracker.flushThen(() -> UsageStore.getInstance().query(UsageReportMath.queryFrom(p, today, firstDow), today, result -> {
            if (token != loadToken) {
                return;
            }
            Calendar now = Calendar.getInstance();
            report = UsageReportMath.build(p, today, firstDow, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), result.rows, result.daily);
            resolveDialogs(token, report);
            update();
        }));
    }

    private void resolveDialogs(int token, UsageReportMath.Report r) {
        final List<UsageReportMath.DialogRow> rows = new ArrayList<>(r.dialogs.subList(0, Math.min(TOP_EXPANDED, r.dialogs.size())));
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<DialogEntry> out = new ArrayList<>();
            for (UsageReportMath.DialogRow row : rows) {
                DialogEntry e = new DialogEntry();
                e.row = row;
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    UserConfig uc = UserConfig.getInstance(a);
                    if (uc.isClientActivated() && uc.getClientUserId() == row.account) {
                        e.accountIndex = a;
                        break;
                    }
                }
                if (e.accountIndex >= 0) {
                    resolve(e);
                }
                out.add(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (token != loadToken) {
                    return;
                }
                entries.clear();
                entries.addAll(out);
                update();
            });
        });
    }

    /** Runs off the UI thread: storage lookups block. */
    private static void resolve(DialogEntry e) {
        final int a = e.accountIndex;
        final long dialog = e.row.dialog;
        final MessagesController mc = MessagesController.getInstance(a);
        if (DialogObject.isEncryptedDialog(dialog)) {
            TLRPC.EncryptedChat enc = mc.getEncryptedChat(DialogObject.getEncryptedChatId(dialog));
            if (enc != null) {
                e.encryptedChat = enc;
                e.object = userOf(a, enc.user_id);
            }
        } else if (dialog > 0) {
            e.object = userOf(a, dialog);
            e.saved = e.row.surface == UsageSurface.CHAT_SAVED;
        } else {
            TLRPC.Chat chat = mc.getChat(-dialog);
            if (chat == null) {
                chat = MessagesStorage.getInstance(a).getChatSync(-dialog);
            }
            e.object = chat;
        }
    }

    private static TLRPC.User userOf(int account, long id) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(id);
        if (user == null) {
            user = MessagesStorage.getInstance(account).getUserSync(id);
        }
        return user;
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    // list

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switcher.select(period);
        items.add(UItem.asCustom(switcher, 56));
        if (report == null) {
            return;
        }
        summary.set(report);
        items.add(UItem.asCustom(summary, 104));
        if (report.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.UsageEmpty)));
            items.add(UItem.asButton(ID_RESET, getString(R.string.UsageReset)));
            items.add(UItem.asShadow(getString(R.string.UsageFooter)));
            return;
        }
        items.add(UItem.asShadow(null));

        StatisticActivity.ChartViewData chart = buildChart();
        if (chart != null) {
            items.add(UItem.asChart(StatisticActivity.VIEW_TYPE_STACKBAR, -1, chart));
            items.add(UItem.asShadow(null));
        }

        Context context = getContext();
        items.add(UItem.asHeader(getString(R.string.UsageCategories)));
        for (UsageReportMath.CategoryRow row : report.categories) {
            items.add(UItem.asCustom(new CategoryRowView(context, row), 52));
        }
        items.add(UItem.asShadow(null));

        if (!entries.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.UsageMostUsed)));
            int limit = showAll ? entries.size() : Math.min(TOP_COLLAPSED, entries.size());
            for (int i = 0; i < limit; i++) {
                items.add(UItem.asCustom(buildDialogCell(context, entries.get(i), i < limit - 1), 56));
            }
            if (!showAll && entries.size() > TOP_COLLAPSED) {
                items.add(UItem.asButton(ID_SHOW_MORE, getString(R.string.UsageShowMore)));
            }
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(getString(R.string.UsageMore)));
        items.add(UItem.asSettingsCell(ID_INFO, getString(R.string.UsageMessagesSent), String.valueOf(report.messagesSent)));
        items.add(UItem.asSettingsCell(ID_INFO, getString(R.string.UsageAppOpens), String.valueOf(report.opens)));
        items.add(UItem.asSettingsCell(ID_INFO, getString(R.string.UsageLongestSession), formatDuration(report.longestSessionSeconds)));
        items.add(UItem.asSettingsCell(ID_INFO, getString(R.string.UsageCallsTotal), formatDuration(report.callSeconds)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(ID_RESET, getString(R.string.UsageReset)));
        items.add(UItem.asShadow(getString(R.string.UsageFooter)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_SHOW_MORE) {
            showAll = true;
            update();
        } else if (item.id == ID_RESET) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle(getString(R.string.UsageResetTitle));
            builder.setMessage(getString(R.string.UsageResetText));
            builder.setPositiveButton(getString(R.string.UsageReset), (dialog, which) -> {
                final int token = ++loadToken;
                UsageTracker.flushThen(() -> UsageStore.getInstance().reset(() -> {
                    if (token == loadToken) {
                        entries.clear();
                        report = null;
                        reload();
                    }
                }));
            });
            builder.setNegativeButton(getString(R.string.Cancel), null);
            showDialog(builder.create());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private View buildDialogCell(Context context, DialogEntry e, boolean divider) {
        UserCell cell = new UserCell(context, 16, 0, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        CharSequence status = formatDuration(e.row.seconds);
        if (accountCount() > 1 && e.accountIndex >= 0) {
            TLRPC.User self = UserConfig.getInstance(e.accountIndex).getCurrentUser();
            if (self != null) {
                status = status + " · " + UserObject.getFirstName(self);
            }
        }
        CharSequence name = null;
        if (e.object == null) {
            name = getString(R.string.UsageDeletedChat);
        } else if (e.saved) {
            name = getString(R.string.SavedMessages);
        }
        cell.setData(e.object, e.encryptedChat, name, status, 0, divider);
        if (e.object != null && e.accountIndex >= 0) {
            cell.setOnClickListener(v -> openDialog(e));
        }
        return cell;
    }

    private void openDialog(DialogEntry e) {
        Bundle args = new Bundle();
        long dialog = e.row.dialog;
        if (DialogObject.isEncryptedDialog(dialog)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialog));
        } else if (dialog > 0) {
            args.putLong("user_id", dialog);
        } else {
            args.putLong("chat_id", -dialog);
        }
        if (e.accountIndex != UserConfig.selectedAccount && LaunchActivity.instance != null) {
            LaunchActivity.instance.switchToAccount(e.accountIndex, true);
        }
        // the chat opens through the normal navigation, so Protected Chats gates it like any other route
        presentFragment(new ChatActivity(args));
    }

    private static int accountCount() {
        int n = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                n++;
            }
        }
        return n;
    }

    // formatting

    static String formatDuration(long seconds) {
        if (seconds > 0 && seconds < 60) {
            return LocaleController.formatString(R.string.UsageDurS, (int) seconds);
        }
        long minutes = (seconds + 30) / 60;
        int h = (int) (minutes / 60);
        int m = (int) (minutes % 60);
        if (h == 0) {
            return LocaleController.formatString(R.string.UsageDurM, m);
        }
        if (m == 0) {
            return LocaleController.formatString(R.string.UsageDurH, h);
        }
        return LocaleController.formatString(R.string.UsageDurHM, h, m);
    }

    private static String periodName(UsageReportMath.Period p) {
        switch (p) {
            case WEEK:
                return getString(R.string.UsageWeek);
            case MONTH:
                return getString(R.string.UsageMonth);
            default:
                return getString(R.string.UsageToday);
        }
    }

    private static String categoryName(UsageSurface.Category c) {
        switch (c) {
            case PRIVATE:
                return getString(R.string.UsageCatPrivate);
            case BOTS:
                return getString(R.string.UsageCatBots);
            case GROUPS:
                return getString(R.string.UsageCatGroups);
            case CHANNELS:
                return getString(R.string.UsageCatChannels);
            case CHAT_LIST:
                return getString(R.string.UsageCatChatList);
            case SEARCH:
                return getString(R.string.UsageCatSearch);
            case STORIES:
                return getString(R.string.UsageCatStories);
            case MEDIA:
                return getString(R.string.UsageCatMedia);
            case CALLS:
                return getString(R.string.UsageCatCalls);
            case LOCAL_HISTORY:
                return getString(R.string.UsageCatLocalHistory);
            default:
                return getString(R.string.UsageCatOther);
        }
    }

    private static int categoryColorKey(UsageSurface.Category c) {
        switch (c) {
            case PRIVATE:
                return Theme.key_statisticChartLine_blue;
            case BOTS:
                return Theme.key_statisticChartLine_purple;
            case GROUPS:
                return Theme.key_statisticChartLine_green;
            case CHANNELS:
                return Theme.key_statisticChartLine_orange;
            case CHAT_LIST:
                return Theme.key_statisticChartLine_lightblue;
            case SEARCH:
                return Theme.key_statisticChartLine_cyan;
            case STORIES:
                return Theme.key_statisticChartLine_red;
            case MEDIA:
                return Theme.key_statisticChartLine_golden;
            case CALLS:
                return Theme.key_statisticChartLine_lightgreen;
            case LOCAL_HISTORY:
                return Theme.key_statisticChartLine_indigo;
            default:
                return Theme.key_windowBackgroundWhiteGrayText;
        }
    }

    /** Series id used by the chart JSON (theme key name and fallback color). */
    private static String chartColor(UsageSurface.Category c) {
        switch (c) {
            case PRIVATE:
                return "blue#3896D4";
            case BOTS:
                return "purple#9A6CD8";
            case GROUPS:
                return "green#33C759";
            case CHANNELS:
                return "orange#F2994A";
            case CHAT_LIST:
                return "lightblue#64B5EF";
            case SEARCH:
                return "cyan#3CB8C4";
            case STORIES:
                return "red#E0524C";
            case MEDIA:
                return "golden#E7B23D";
            case CALLS:
                return "lightgreen#7BD27B";
            case LOCAL_HISTORY:
                return "indigo#5C6BC0";
            default:
                return "#8E8E93";
        }
    }

    private static long dayMillis(int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(day / 10000, (day / 100) % 100 - 1, day % 100, 0, 0, 0);
        return c.getTimeInMillis();
    }

    private StatisticActivity.ChartViewData buildChart() {
        try {
            UsageReportMath.Report r = report;
            long[] x = new long[r.bucketCount];
            for (int b = 0; b < r.bucketCount; b++) {
                x[b] = r.period == UsageReportMath.Period.TODAY ? dayMillis(r.firstDay) + b * 3600_000L : dayMillis(r.firstDay) + b * 86_400_000L + 12 * 3600_000L;
            }
            JSONObject json = new JSONObject();
            JSONArray columns = new JSONArray();
            JSONArray xs = new JSONArray();
            xs.put("x");
            for (long v : x) {
                xs.put(v);
            }
            columns.put(xs);
            JSONObject types = new JSONObject();
            JSONObject names = new JSONObject();
            JSONObject colors = new JSONObject();
            types.put("x", "x");
            for (UsageReportMath.CategoryRow row : r.categories) {
                String id = "y" + row.category.ordinal();
                JSONArray ys = new JSONArray();
                ys.put(id);
                for (int b = 0; b < r.bucketCount; b++) {
                    ys.put(Math.round(r.chartSeconds[row.category.ordinal()][b] / 60.0));
                }
                columns.put(ys);
                types.put(id, "bar");
                names.put(id, categoryName(row.category));
                colors.put(id, chartColor(row.category));
            }
            json.put("columns", columns);
            json.put("types", types);
            json.put("names", names);
            json.put("colors", colors);
            json.put("stacked", true);
            String title = getString(r.period == UsageReportMath.Period.TODAY ? R.string.UsageChartTodayTitle : R.string.UsageChartPeriodTitle);
            StatisticActivity.ChartViewData data = new StatisticActivity.ChartViewData(title, StatisticActivity.VIEW_TYPE_STACKBAR);
            data.chartData = StatisticActivity.createChartData(json, StatisticActivity.VIEW_TYPE_STACKBAR, false);
            data.isEmpty = data.chartData == null || data.chartData.x == null || data.chartData.x.length < 2;
            return data.chartData == null ? null : data;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    // views

    private class PeriodSwitcher extends LinearLayout {

        private final TextView[] tabs = new TextView[3];

        PeriodSwitcher(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(16), dp(10), dp(16), dp(10));
            final UsageReportMath.Period[] periods = UsageReportMath.Period.values();
            for (int i = 0; i < 3; i++) {
                final UsageReportMath.Period p = periods[i];
                TextView tv = new TextView(context);
                tv.setGravity(Gravity.CENTER);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                tv.setText(periodName(p));
                tv.setOnClickListener(v -> {
                    if (period != p) {
                        period = p;
                        showAll = false;
                        report = null;
                        entries.clear();
                        update();
                        reload();
                    }
                });
                tabs[i] = tv;
                addView(tv, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 2, 0, 2, 0));
            }
            updateColors();
        }

        void select(UsageReportMath.Period p) {
            for (int i = 0; i < 3; i++) {
                tabs[i].setSelected(UsageReportMath.Period.values()[i] == p);
            }
            updateColors();
        }

        void updateColors() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            for (int i = 0; i < 3; i++) {
                boolean selected = tabs[i].isSelected();
                tabs[i].setTextColor(Theme.getColor(selected ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
                tabs[i].setBackground(Theme.createRoundRectDrawable(dp(10), selected ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.getColor(Theme.key_windowBackgroundGray)));
            }
        }
    }

    private class SummaryView extends LinearLayout {

        private final TextView total;
        private final TextView comparison;

        SummaryView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            total = new TextView(context);
            total.setTypeface(AndroidUtilities.bold());
            total.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
            total.setGravity(Gravity.CENTER);
            comparison = new TextView(context);
            comparison.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            comparison.setGravity(Gravity.CENTER);
            addView(total, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(comparison, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            updateColors();
        }

        void updateColors() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            total.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            comparison.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }

        void set(UsageReportMath.Report r) {
            String duration = formatDuration(r.totalSeconds);
            total.setText(duration);
            String ref = r.period == UsageReportMath.Period.TODAY ? getString(R.string.UsageRefYesterday)
                    : r.period == UsageReportMath.Period.WEEK ? getString(R.string.UsageRefLastWeek) : getString(R.string.UsageRefLastMonth);
            long diff = r.totalSeconds - r.previousSeconds;
            String line;
            if (Math.abs(diff) < 60) {
                line = LocaleController.formatString(R.string.UsageSameAs, ref);
            } else if (diff < 0) {
                line = LocaleController.formatString(R.string.UsageLessThan, formatDuration(-diff), ref);
            } else {
                line = LocaleController.formatString(R.string.UsageMoreThan, formatDuration(diff), ref);
            }
            comparison.setText(line);
            setContentDescription(periodName(r.period) + ", " + duration + ". " + line);
            updateColors();
        }
    }

    private static class CategoryRowView extends View {

        private final UsageReportMath.CategoryRow row;
        private final String name;
        private final String time;
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint namePaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint timePaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CategoryRowView(Context context, UsageReportMath.CategoryRow row) {
            super(context);
            this.row = row;
            this.name = categoryName(row.category);
            this.time = formatDuration(row.seconds) + "  " + row.percent + "%";
            namePaint.setTextSize(dp(16));
            timePaint.setTextSize(dp(15));
            setContentDescription(name + ", " + formatDuration(row.seconds) + ", " + row.percent + "%");
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(52));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final boolean rtl = LocaleController.isRTL;
            final float left = dp(16), right = w - dp(16);
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            namePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            timePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dotPaint.setColor(Theme.getColor(categoryColorKey(row.category)));
            barPaint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));

            final float baseline = dp(22);
            canvas.drawCircle(rtl ? right - dp(5) : left + dp(5), dp(16), dp(5), dotPaint);
            namePaint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(name, rtl ? right - dp(20) : left + dp(20), baseline, namePaint);
            timePaint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
            canvas.drawText(time, rtl ? left : right, baseline, timePaint);

            final float top = dp(34), bottom = dp(38);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, dp(2), dp(2), barPaint);
            final float filled = (right - left) * Math.max(0.02f, row.percent / 100f);
            if (rtl) {
                rect.set(right - filled, top, right, bottom);
            } else {
                rect.set(left, top, left + filled, bottom);
            }
            canvas.drawRoundRect(rect, dp(2), dp(2), dotPaint);
        }
    }
}
