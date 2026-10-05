package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** The Local History avatar: the user's own photo when there is one, else the default icon on a blue disc. */
public class LocalHistoryAvatarView extends ImageView {

    public LocalHistoryAvatarView(Context context, int sizeDp) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setClipToOutline(true);
        setPhoto(null, sizeDp);
    }

    public void setPhoto(Bitmap bitmap, int sizeDp) {
        if (bitmap != null) {
            setScaleType(ScaleType.CENTER_CROP);
            setColorFilter(null);
            setBackground(null);
            setImageBitmap(bitmap);
        } else {
            setScaleType(ScaleType.CENTER);
            setImageResource(R.drawable.msg_recent);
            setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            setBackground(Theme.createCircleDrawable(dp(sizeDp), Theme.getColor(Theme.key_avatar_backgroundBlue)));
        }
    }
}
