package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.localhistory.LocalHistoryRepository;
import org.telegram.messenger.localhistory.LocalHistoryVersions;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.Date;
import java.util.List;

/** Every version of one archived message, oldest first (plan B9). Text only; media stays in the feed. */
public class LocalHistoryRevisionsSheet extends BottomSheet {

    public LocalHistoryRevisionsSheet(Context context, List<LocalHistoryRepository.Revision> revisions, int deletedAt) {
        super(context, true);
        fixNavigationBar();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.LocalHistoryVersions));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 22, 18, 22, 8));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        List<LocalHistoryRepository.Revision> ordered = LocalHistoryVersions.ordered(revisions);
        if (LocalHistoryVersions.earlierNotSeen(ordered)) {
            list.addView(line(context, LocaleController.getString(R.string.LocalHistoryVersionEarlierNotSeen), 13, Theme.key_dialogTextGray2, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 8));
        }
        for (int i = 0; i < ordered.size(); i++) {
            LocalHistoryRepository.Revision r = ordered.get(i);
            String label = r.kind == LocalHistoryRepository.KIND_EDIT
                    ? LocaleController.formatString(R.string.LocalHistoryVersionEdit, LocalHistoryVersions.editNumber(ordered, i))
                    : LocaleController.getString(R.string.LocalHistoryVersionOriginal);
            int when = r.editDate != 0 ? r.editDate : r.observedAt;
            String stamp = when == 0 ? "" : " · " + LocaleController.getInstance().getFormatterDayMonth().format(new Date(when * 1000L)) + ", " + LocaleController.getInstance().getFormatterDay().format(new Date(when * 1000L));
            list.addView(line(context, label + stamp, 13, Theme.key_dialogTextBlue, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 2));
            list.addView(line(context, r.text == null || r.text.isEmpty() ? "…" : r.text, 16, Theme.key_dialogTextBlack, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 4));
        }
        if (deletedAt != 0) {
            list.addView(line(context, LocaleController.getString(R.string.LocalHistoryVersionRemoved), 13, Theme.key_text_RedRegular, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 14, 22, 2));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
        setCustomView(root);
    }

    private TextView line(Context context, CharSequence text, int sizeSp, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeSp);
        view.setTextColor(getThemedColor(colorKey));
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        view.setTextIsSelectable(true);
        return view;
    }
}
