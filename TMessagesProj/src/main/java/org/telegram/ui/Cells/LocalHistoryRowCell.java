package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.localhistory.LocalHistory;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The Local History row of the chat list. Deliberately not a DialogCell: it must never take part in dialog
 * selection, swipe, reorder or any code that resolves a dialog id.
 */
public class LocalHistoryRowCell extends FrameLayout {

    private final ImageView avatar;
    private final TextView title;
    private final TextView preview;
    private final TextView time;
    private final TextView badge;
    private boolean needDivider = true;

    public LocalHistoryRowCell(Context context) {
        super(context);
        setWillNotDraw(false);

        avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER);
        avatar.setImageResource(R.drawable.msg_recent);
        avatar.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        avatar.setBackground(Theme.createCircleDrawable(dp(56), Theme.getColor(Theme.key_avatar_backgroundBlue)));
        addView(avatar, LayoutHelper.createFrame(56, 56, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 10, 7, 10, 0));

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 80 : 76, 10, LocaleController.isRTL ? 76 : 80, 0));

        preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        preview.setSingleLine();
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 56 : 76, 36, LocaleController.isRTL ? 76 : 56, 0));

        time = new TextView(context);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        time.setSingleLine();
        addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 14, 12, 14, 0));

        badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(6), 0, dp(6), 0);
        badge.setMinWidth(dp(22));
        addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 22, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 14, 38, 14, 0));

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(SharedConfig.useThreeLinesLayout ? 76 : 70) + 1, MeasureSpec.EXACTLY));
    }

    public void setData(LocalHistory.Summary summary) {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        String name = summary != null && summary.title != null && !summary.title.isEmpty() ? summary.title : LocaleController.getString(R.string.LocalHistoryTitle);
        title.setText(name);
        title.setTextColor(Theme.getColor(Theme.key_chats_name));
        preview.setTextColor(Theme.getColor(Theme.key_chats_message));
        time.setTextColor(Theme.getColor(Theme.key_chats_date));
        if (summary == null) {
            preview.setText("");
            time.setText("");
            badge.setVisibility(View.GONE);
            return;
        }
        String sender = summary.lastSender != null ? summary.lastSender : LocaleController.getString(R.string.LocalHistoryUnknownSender);
        preview.setText(LocaleController.formatString(summary.lastDeleted ? R.string.LocalHistoryPreviewRemoved : R.string.LocalHistoryPreviewEdited, sender));
        time.setText(LocaleController.stringForMessageListDate(summary.lastEventAt));
        if (summary.unread > 0) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(String.valueOf(summary.unread));
            badge.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText));
            badge.setBackground(Theme.createRoundRectDrawable(dp(11), Theme.getColor(Theme.key_chats_unreadCounterMuted)));
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    public void setDivider(boolean divider) {
        needDivider = divider;
        invalidate();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : dp(AndroidUtilities.leftBaseline), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? dp(AndroidUtilities.leftBaseline) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }
}
