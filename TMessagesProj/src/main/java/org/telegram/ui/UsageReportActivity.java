package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.usage.UsageMetrics;
import org.telegram.messenger.usage.UsageReportMath;
import org.telegram.messenger.usage.UsageStore;
import org.telegram.messenger.usage.UsageSurface;
import org.telegram.messenger.usage.UsageTracker;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Local identity/time only. Chart cells use a negative DC and avatars are generated, never fetched. */
public class UsageReportActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int RESET=1, MORE=2, RETRY=3;
    private static final int[] CATEGORY_NAMES={R.string.UsagePrivateChats,R.string.UsageGroups,R.string.UsageChannels,
            R.string.UsageBots,R.string.UsageChatList,R.string.UsageSearch,R.string.UsageStories,R.string.UsageMedia,
            R.string.UsageCalls,R.string.UsageLocalHistory,R.string.UsageOther};
    private static final int[] CATEGORY_COLORS={Theme.key_statisticChartLine_blue,Theme.key_statisticChartLine_green,
            Theme.key_statisticChartLine_golden,Theme.key_statisticChartLine_purple,Theme.key_statisticChartLine_lightblue,
            Theme.key_statisticChartLine_indigo,Theme.key_statisticChartLine_red,Theme.key_statisticChartLine_orange,
            Theme.key_statisticChartLine_cyan,Theme.key_statisticChartLine_lightgreen,Theme.key_statisticChartLine_lightgreen};
    private Context reportContext;
    private ScrollSlidingTextTabStrip tabs;
    private UsageReportMath.Period period=UsageReportMath.Period.TODAY;
    private UsageReportMath.Range range;
    private ZonedDateTime reportTime;
    private UsageStore.Report snapshot;
    private UsageReportMath.Report report;
    private StatisticActivity.ChartViewData chart;
    private final Map<UsageReportMath.DialogKey,Identity> identities=new HashMap<>();
    private boolean loading,failed,showMore,resetting,destroyed;
    private int request;
    private static final class Identity {
        int slot=-1;
        String name;
        TLRPC.User user;
        TLRPC.Chat chat;
        TLRPC.EncryptedChat encrypted;
    }
    @Override protected CharSequence getTitle() { return getString(R.string.UsageActivity); }
    @Override public boolean onFragmentCreate() {
        if(!super.onFragmentCreate()) return false;
        for(int event:new int[]{NotificationCenter.didSetNewTheme,NotificationCenter.activeAccountChanged,NotificationCenter.appDidLogout})
            NotificationCenter.getGlobalInstance().addObserver(this,event);
        for(int slot=0;slot<UserConfig.MAX_ACCOUNT_COUNT;slot++) NotificationCenter.getInstance(slot).addObserver(this,NotificationCenter.appDidLogout);
        return true;
    }
    @Override public void onFragmentDestroy() {
        destroyed=true; request++;
        for(int event:new int[]{NotificationCenter.didSetNewTheme,NotificationCenter.activeAccountChanged,NotificationCenter.appDidLogout})
            NotificationCenter.getGlobalInstance().removeObserver(this,event);
        for(int slot=0;slot<UserConfig.MAX_ACCOUNT_COUNT;slot++) NotificationCenter.getInstance(slot).removeObserver(this,NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }
    @Override public View createView(Context context) {
        reportContext=context;
        tabs=new ScrollSlidingTextTabStrip(context,resourceProvider);
        tabs.setLayoutDirection(LocaleController.isRTL?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        tabs.setUseSameWidth(true);
        tabs.setColors(Theme.key_windowBackgroundWhiteBlueText,Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteGrayText,Theme.key_listSelector);
        tabs.addTextTab(0,getString(R.string.UsageToday)); tabs.addTextTab(1,getString(R.string.UsageWeek)); tabs.addTextTab(2,getString(R.string.UsageMonth));
        tabs.setInitialTabId(period.ordinal()); tabs.finishAddingTabs();
        tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override public void onPageSelected(int page,boolean forward) {
                if(resetting) return;
                period=UsageReportMath.Period.values()[page]; showMore=false; reload();
            }
            @Override public void onPageScrolled(float progress) { }
        });
        return super.createView(context);
    }
    @Override public void onResume() { super.onResume(); reload(); }
    @Override public void didReceivedNotification(int id,int account,Object... args) {
        if(id==NotificationCenter.didSetNewTheme) {
            if(tabs!=null) tabs.updateColors();
            if(fragmentView!=null) fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray,resourceProvider));
            chart=makeChart(); update();
        } else {
            identities.clear(); ++request; report=null; chart=null; update();
            // Logout anonymization was queued before this notification; let that boundary run first.
            AndroidUtilities.runOnUIThread(this::reload);
        }
    }
    private void update() { if(listView!=null && !destroyed) listView.adapter.update(false); }
    private void reload() {
        if(destroyed || resetting) return;
        int generation=++request;
        loading=true; failed=false; report=null; chart=null; update();
        UsageTracker.flush();
        reportTime=ZonedDateTime.now();
        range=new UsageReportMath.Range(period,reportTime.toLocalDate(),LocaleController.getInstance().getCurrentLocale());
        UsageStore.getInstance().query(range.firstDay(),range.lastDay(),data->{
            if(destroyed || generation!=request) return;
            loading=false; failed=data==null; snapshot=data;
            if(data!=null) { aggregate(); loadIdentities(generation); }
            update();
        });
    }
    private int accountSlot(long uid) {
        for(int slot=0;slot<UserConfig.MAX_ACCOUNT_COUNT;slot++) if(UserConfig.getInstance(slot).getClientUserId()==uid) return slot;
        return -1;
    }
    private void aggregate() {
        ArrayList<UsageReportMath.Record> rows=new ArrayList<>();
        Map<UsageReportMath.DialogKey,UsageReportMath.DialogKey> migrations=new HashMap<>();
        for(UsageStore.Row row:snapshot.rows) {
            rows.add(new UsageReportMath.Record(row.day,row.hour,row.account,UsageReportMath.surface(row.surface),row.dialog,row.seconds));
            int slot=accountSlot(row.account);
            if(slot>=0 && row.dialog<0 && !DialogObject.isEncryptedDialog(row.dialog)) {
                TLRPC.Chat chat=MessagesController.getInstance(slot).getChat(-row.dialog);
                if(chat!=null && chat.migrated_to!=null) migrations.put(new UsageReportMath.DialogKey(row.account,row.dialog),
                        new UsageReportMath.DialogKey(row.account,-chat.migrated_to.channel_id));
            }
        }
        report=UsageReportMath.summarize(range,reportTime,rows,snapshot.daily,migrations);
        chart=makeChart();
    }
    private Identity identity(UsageReportMath.DialogKey key) {
        Identity cached=identities.get(key);
        int slot=accountSlot(key.account);
        if(cached!=null && cached.slot==slot) return cached;
        Identity result=new Identity(); result.slot=slot;
        if(slot>=0) {
            MessagesController controller=MessagesController.getInstance(slot);
            if(DialogObject.isEncryptedDialog(key.dialog)) {
                result.encrypted=controller.getEncryptedChat(DialogObject.getEncryptedChatId(key.dialog));
                if(result.encrypted!=null) result.user=controller.getUser(result.encrypted.user_id);
            } else if(key.dialog>0) result.user=controller.getUser(key.dialog);
            else result.chat=controller.getChat(-key.dialog);
        }
        name(result,key); return result;
    }
    private void name(Identity identity,UsageReportMath.DialogKey key) {
        if(identity.slot<0) identity.name=getString(R.string.UsageDeletedAccount);
        else if(identity.encrypted instanceof TLRPC.TL_encryptedChatDiscarded) identity.name=getString(R.string.UsageDeletedChat);
        else if(key.dialog==key.account) identity.name=getString(R.string.SavedMessages);
        else if(identity.user!=null && !UserObject.isDeleted(identity.user)) identity.name=UserObject.getUserName(identity.user);
        else if(identity.chat!=null) identity.name=identity.chat.title;
        else identity.name=getString(R.string.UsageDeletedChat);
    }
    /** Read missing identities from account storage off the UI thread; never request users/chats/photos. */
    private void loadIdentities(int generation) {
        ArrayList<UsageReportMath.DialogKey> keys=new ArrayList<>();
        for(UsageReportMath.Chat chat:report.chats) if(!identities.containsKey(chat.key)) keys.add(chat.key);
        if(keys.isEmpty()) return;
        Map<UsageReportMath.DialogKey,Identity> resolved=new HashMap<>();
        for(UsageReportMath.DialogKey key:keys) resolved.put(key,identity(key));
        Utilities.globalQueue.postRunnable(()->{
            for(Map.Entry<UsageReportMath.DialogKey,Identity> entry:resolved.entrySet()) {
                UsageReportMath.DialogKey key=entry.getKey(); Identity id=entry.getValue();
                if(id.slot<0) continue;
                MessagesStorage storage=MessagesStorage.getInstance(id.slot);
                if(DialogObject.isEncryptedDialog(key.dialog) && id.user==null) {
                    CountDownLatch done=new CountDownLatch(1); ArrayList<TLObject> result=new ArrayList<>();
                    storage.getEncryptedChat(DialogObject.getEncryptedChatId(key.dialog),done,result);
                    try { done.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    if(result.size()>=2) { id.encrypted=(TLRPC.EncryptedChat)result.get(0); id.user=(TLRPC.User)result.get(1); }
                } else if(key.dialog>0 && id.user==null) id.user=storage.getUserSync(key.dialog);
                else if(key.dialog<0 && id.chat==null && !DialogObject.isEncryptedDialog(key.dialog)) id.chat=storage.getChatSync(-key.dialog);
            }
            AndroidUtilities.runOnUIThread(()->{
                if(destroyed || generation!=request) return;
                for(Map.Entry<UsageReportMath.DialogKey,Identity> entry:resolved.entrySet()) {
                    Identity id=entry.getValue(); UsageReportMath.DialogKey key=entry.getKey();
                    if(accountSlot(key.account)!=id.slot) continue;
                    if(id.slot>=0) {
                        MessagesController controller=MessagesController.getInstance(id.slot);
                        if(id.user!=null) controller.putUser(id.user,true);
                        if(id.chat!=null) controller.putChat(id.chat,true);
                        if(id.encrypted!=null) controller.putEncryptedChat(id.encrypted,true);
                    }
                    name(id,key); identities.put(key,id);
                }
                aggregate(); update(); loadIdentities(generation);
            });
        });
    }
    public static String formatDuration(long seconds) {
        long hours=seconds/3600,minutes=seconds/60%60;
        if(hours>0) return LocaleController.formatString(R.string.UsageHoursMinutes,
                LocaleController.formatPluralString("UsageHours",(int)hours),LocaleController.formatPluralString("UsageMinutes",(int)minutes));
        if(minutes>0) return LocaleController.formatPluralString("UsageMinutes",(int)minutes);
        return LocaleController.formatPluralString("UsageSeconds",(int)Math.max(0,seconds));
    }
    private String periodName() { return getString(period==UsageReportMath.Period.TODAY?R.string.UsageToday:period==UsageReportMath.Period.WEEK?R.string.UsageWeek:R.string.UsageMonth); }
    private String comparison() {
        long difference=report.total-report.previous;
        int id;
        if(difference==0) id=period==UsageReportMath.Period.TODAY?R.string.UsageSameYesterday:period==UsageReportMath.Period.WEEK?R.string.UsageSameWeek:R.string.UsageSameMonth;
        else if(difference>0) id=period==UsageReportMath.Period.TODAY?R.string.UsageMoreYesterday:period==UsageReportMath.Period.WEEK?R.string.UsageMoreWeek:R.string.UsageMoreMonth;
        else id=period==UsageReportMath.Period.TODAY?R.string.UsageLessYesterday:period==UsageReportMath.Period.WEEK?R.string.UsageLessWeek:R.string.UsageLessMonth;
        return difference==0?getString(id):LocaleController.formatString(id,formatDuration(Math.abs(difference)));
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(reportContext); view.setText(value); view.setTextSize(size);
        view.setTextColor(Theme.getColor(color,resourceProvider));
        view.setGravity((LocaleController.isRTL?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
        if(bold) view.setTypeface(AndroidUtilities.bold()); return view;
    }
    private LinearLayout summary() {
        LinearLayout box=new LinearLayout(reportContext); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(18),dp(20),dp(18));
        box.addView(text(periodName(),14,Theme.key_windowBackgroundWhiteGrayText,false));
        box.addView(text(formatDuration(report.total),30,Theme.key_windowBackgroundWhiteBlackText,true));
        box.addView(text(comparison(),14,Theme.key_windowBackgroundWhiteGrayText,false));
        if(period==UsageReportMath.Period.TODAY) box.addView(text(getString(R.string.UsageEstimatedComparison),12,Theme.key_windowBackgroundWhiteGrayText,false));
        box.setContentDescription(periodName()+", "+formatDuration(report.total)+", "+comparison());
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for(int i=0;i<box.getChildCount();i++) box.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return box;
    }
    private View categoryRow(UsageSurface.Category category,long seconds,int percent) {
        String label=getString(CATEGORY_NAMES[category.ordinal()]); int colorKey=CATEGORY_COLORS[category.ordinal()];
        LinearLayout box=new LinearLayout(reportContext); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20),dp(12),dp(20),dp(12));
        box.addView(text(LocaleController.formatString(R.string.UsageCategoryValue,label,formatDuration(seconds),percent),16,Theme.key_windowBackgroundWhiteBlackText,false));
        View bar=new View(reportContext) {
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundGray,resourceProvider)); canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(2),dp(2),paint);
                paint.setColor(Theme.getColor(colorKey,resourceProvider)); float width=getWidth()*percent/100f;
                canvas.drawRoundRect(LocaleController.isRTL?getWidth()-width:0,0,LocaleController.isRTL?getWidth():width,getHeight(),dp(2),dp(2),paint);
            }
        };
        box.addView(bar,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,4,0,8,0,0));
        box.setContentDescription(label+", "+formatDuration(seconds)+", "+percent+"%");
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for(int i=0;i<box.getChildCount();i++) box.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Color dot uses the same category color as the chart and bar.
        TextView title=(TextView)box.getChildAt(0); title.setCompoundDrawablePadding(dp(8));
        android.graphics.drawable.GradientDrawable dot=new android.graphics.drawable.GradientDrawable(); dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Theme.getColor(colorKey,resourceProvider)); dot.setBounds(0,0,dp(8),dp(8));
        title.setCompoundDrawablesRelative(dot,null,null,null);
        return box;
    }
    private View chatRow(UsageReportMath.Chat chat) {
        Identity id=identity(chat.key);
        FrameLayout row=new FrameLayout(reportContext); row.setMinimumHeight(dp(76));
        ImageView avatar=new ImageView(reportContext); AvatarDrawable drawable=new AvatarDrawable();
        drawable.setInfo(chat.key.dialog,id.name,null);
        if(chat.key.dialog==chat.key.account) drawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
        avatar.setImageDrawable(drawable);
        int edge=LocaleController.isRTL?Gravity.RIGHT:Gravity.LEFT;
        row.addView(avatar,LayoutHelper.createFrame(42,42,edge|Gravity.CENTER_VERTICAL,20,0,20,0));
        LinearLayout labels=new LinearLayout(reportContext); labels.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(id.name,16,Theme.key_windowBackgroundWhiteBlackText,true); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title); labels.addView(text(formatDuration(chat.seconds),14,Theme.key_windowBackgroundWhiteGrayText,false));
        row.addView(labels,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.CENTER_VERTICAL,
                LocaleController.isRTL?20:76,8,LocaleController.isRTL?76:20,8));
        int accounts=0; for(int slot=0;slot<UserConfig.MAX_ACCOUNT_COUNT;slot++) if(UserConfig.getInstance(slot).isClientActivated()) accounts++;
        String accountName="";
        if(accounts>1 && id.slot>=0) {
            TLRPC.User owner=UserConfig.getInstance(id.slot).getCurrentUser();
            accountName=owner==null?getString(R.string.UsageDeletedAccount):UserObject.getUserName(owner);
            ImageView badge=new ImageView(reportContext); AvatarDrawable small=new AvatarDrawable(); small.setInfo(chat.key.account,accountName,null); badge.setImageDrawable(small);
            row.addView(badge,LayoutHelper.createFrame(18,18,edge|Gravity.TOP,47,43,47,0));
        }
        row.setContentDescription(id.name+", "+formatDuration(chat.seconds)+(accountName.isEmpty()?"":", "+accountName));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        boolean available=id.slot>=0 && (id.chat!=null || id.user!=null && !UserObject.isDeleted(id.user)) &&
                (!DialogObject.isEncryptedDialog(chat.key.dialog) || id.encrypted!=null && !(id.encrypted instanceof TLRPC.TL_encryptedChatDiscarded));
        if(available) { row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector,resourceProvider),2)); row.setOnClickListener(v->openChat(chat.key)); }
        return row;
    }
    private void openChat(UsageReportMath.DialogKey key) {
        int slot=accountSlot(key.account); if(slot<0) return;
        Bundle args=new Bundle();
        if(DialogObject.isEncryptedDialog(key.dialog)) args.putInt("enc_id",DialogObject.getEncryptedChatId(key.dialog));
        else if(key.dialog>0) args.putLong("user_id",key.dialog); else args.putLong("chat_id",-key.dialog);
        if(!MessagesController.getInstance(slot).checkCanOpenChat(args,this)) return;
        ChatActivity chat=new ChatActivity(args); chat.setCurrentAccount(slot);
        // ActionBarLayout/BaseFragment's existing ProtectedChatGate controls presentation and reveal.
        presentFragment(chat);
    }
    private StatisticActivity.ChartViewData makeChart() {
        if(report==null || report.total==0 || range==null) return null;
        try {
            JSONArray columns=new JSONArray(),x=new JSONArray(); x.put("x");
            for(int i=0;i<range.bins();i++) x.put(period==UsageReportMath.Period.TODAY?i:range.start.plusDays(i).atTime(12,0).atOffset(reportTime.getOffset()).toInstant().toEpochMilli());
            columns.put(x); JSONObject types=new JSONObject(),names=new JSONObject(),colors=new JSONObject(); types.put("x","x");
            for(UsageSurface.Category category:UsageSurface.Category.values()) if(report.categories.containsKey(category)) {
                String key="y"+category.ordinal(); JSONArray series=new JSONArray(); series.put(key);
                for(long seconds:report.chart.get(category)) series.put(seconds);
                columns.put(series); types.put(key,"bar"); names.put(key,getString(CATEGORY_NAMES[category.ordinal()]));
                colors.put(key,String.format(java.util.Locale.US,"#%06X",Theme.getColor(CATEGORY_COLORS[category.ordinal()],resourceProvider)&0xffffff));
            }
            JSONObject json=new JSONObject(); json.put("columns",columns); json.put("types",types); json.put("names",names); json.put("colors",colors); json.put("stacked",true);
            StatisticActivity.ChartViewData data=new StatisticActivity.ChartViewData(getString(R.string.UsageChartSeconds),StatisticActivity.VIEW_TYPE_STACKBAR);
            data.chartData=StatisticActivity.createChartData(json,StatisticActivity.VIEW_TYPE_STACKBAR,false);
            int index=0; for(UsageSurface.Category category:UsageSurface.Category.values()) if(report.categories.containsKey(category)) data.chartData.lines.get(index++).colorKey=CATEGORY_COLORS[category.ordinal()];
            if(period==UsageReportMath.Period.TODAY) for(int i=0;i<24 && i<data.chartData.daysLookup.length;i++) {
                Date date=Date.from(LocalDate.of(2000,1,15).atTime(i,0).atZone(reportTime.getZone()).toInstant());
                data.chartData.daysLookup[i]=LocaleController.getInstance().getFormatterDay().format(date);
            }
            data.useHourFormat=period==UsageReportMath.Period.TODAY;
            if(data.useHourFormat) data.localHourLabels=data.chartData.daysLookup;
            return data;
        } catch(Exception e) { FileLog.e(e); return null; }
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        if(tabs==null) return;
        items.add(UItem.asCustom(tabs,48));
        if(loading || resetting) items.add(UItem.asShadow(getString(R.string.Loading)));
        else if(failed) { items.add(UItem.asShadow(getString(R.string.UsageLoadFailed))); items.add(UItem.asButton(RETRY,getString(R.string.UsageRetry))); }
        else if(report!=null) {
            items.add(UItem.asCustom(summary(),LayoutHelper.WRAP_CONTENT));
            if(report.total==0) items.add(UItem.asShadow(getString(R.string.UsageEmpty)));
            else {
                if(chart!=null) items.add(UItem.asChart(StatisticActivity.VIEW_TYPE_STACKBAR,-1,chart));
                items.add(UItem.asShadow(LocaleController.formatString(R.string.UsageChartSummary,periodName(),formatDuration(report.total))));
                items.add(UItem.asHeader(getString(R.string.UsageCategories)));
                for(UsageSurface.Category category:UsageSurface.Category.values()) if(report.categories.containsKey(category))
                    items.add(UItem.asCustom(categoryRow(category,report.categories.get(category),report.percentages.get(category)),LayoutHelper.WRAP_CONTENT));
                if(!report.chats.isEmpty()) {
                    items.add(UItem.asHeader(getString(R.string.UsageMostUsed)));
                    for(int i=0;i<Math.min(showMore?50:10,report.chats.size());i++) items.add(UItem.asCustom(chatRow(report.chats.get(i)),LayoutHelper.WRAP_CONTENT));
                    if(!showMore && report.chats.size()>10) items.add(UItem.asButton(MORE,getString(R.string.UsageShowMore)));
                }
            }
            items.add(UItem.asHeader(getString(R.string.UsageMore)));
            items.add(UItem.asButton(10,getString(R.string.UsageMessagesSent),LocaleController.formatNumber(report.messages,',')).setEnabled(false));
            items.add(UItem.asButton(11,getString(R.string.UsageAppOpens),LocaleController.formatNumber(report.opens,',')).setEnabled(false));
            items.add(UItem.asButton(12,getString(R.string.UsageLongestSession),formatDuration(report.longestSessionMillis/1000)).setEnabled(false));
            items.add(UItem.asButton(13,getString(R.string.UsageCalls),formatDuration(report.calls)).setEnabled(false));
        }
        items.add(UItem.asShadow(getString(R.string.UsagePrivacy)));
        if(!resetting) items.add(UItem.asButton(RESET,getString(R.string.UsageReset)).red());
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==MORE) { showMore=true; update(); }
        else if(item.id==RETRY) reload();
        else if(item.id==RESET && !resetting) showDialog(new AlertDialog.Builder(getParentActivity(),resourceProvider)
                .setTitle(getString(R.string.UsageReset)).setMessage(getString(R.string.UsageResetConfirm))
                .setNegativeButton(getString(R.string.Cancel),null)
                .setPositiveButton(getString(R.string.UsageReset),(dialog,which)->{
                    resetting=true; ++request; tabs.setEnabled(false); identities.clear(); update();
                    UsageTracker.reset(ok->{
                        if(destroyed) return;
                        resetting=false; tabs.setEnabled(true); showMore=false;
                        if(!ok) showDialog(new AlertDialog.Builder(getParentActivity(),resourceProvider).setTitle(getString(R.string.UsageActivity))
                                .setMessage(getString(R.string.UsageResetFailed)).setPositiveButton(getString(R.string.OK),null).create());
                        reload();
                    });
                }).create());
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
