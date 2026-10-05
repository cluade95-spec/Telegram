package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/**
 * Authentication for a protected chat as Telegram's native bottom popup ({@link BottomSheet}: slide
 * in/out animation, dimmed background, outside tap, Back and swipe down dismiss, insets and keyboard
 * handling). The content is Telegram's own unlock screen ({@link PasscodeView}, chat lock mode), laid
 * out for the height the popup is offered. Dismissing leaves the destination unopened.
 */
public class ProtectedChatAuthSheet {

    public enum Mode {
        UNLOCK, PROTECT, UNPROTECT
    }

    public interface Callback {
        /** Called once, after the popup has been dismissed, with a single-use proof. */
        void onAuthenticated(ProtectedChatsState.AuthProof proof);

        default void onCancelled() {
        }
    }

    private static boolean showing;

    public static boolean isShowing() {
        return showing;
    }

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider, Mode mode, CharSequence chatTitle, Callback callback) {
        if (showing || context == null) {
            return;
        }
        showing = true;
        new Sheet(context, resourcesProvider, mode, chatTitle, callback).show();
    }

    private static class Sheet extends BottomSheet {

        private final Callback callback;
        private final PasscodeView passcodeView;
        private final FlagSecureReason flagSecureReason;
        private ProtectedChatsState.AuthProof proof;
        private final CharSequence announcement;

        Sheet(Context context, Theme.ResourcesProvider resourcesProvider, Mode mode, CharSequence chatTitle, Callback callback) {
            super(context, true, resourcesProvider);
            this.callback = callback;

            // The unlock screen draws its own wallpaper background; the sheet only rounds the top corners.
            final FrameLayout holder = new FrameLayout(context);
            holder.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final int radius = dp(16);
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + radius, radius);
                }
            });
            holder.setClipToOutline(true);
            holder.setClickable(true);

            passcodeView = new PasscodeView(context);
            final CharSequence title;
            switch (mode) {
                case PROTECT:
                    title = LocaleController.formatString(R.string.ChatPasscodeProtectNamed, chatTitle);
                    break;
                case UNPROTECT:
                    title = LocaleController.formatString(R.string.ChatPasscodeUnprotectNamed, chatTitle);
                    break;
                default:
                    title = LocaleController.formatString(R.string.ChatPasscodeUnlockNamed, chatTitle);
                    break;
            }
            announcement = title;
            passcodeView.setChatLockMode(title, authProof -> {
                this.proof = authProof;
                dismiss();
            });
            holder.addView(passcodeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.BOTTOM));
            containerView = holder;
            fullWidth = false;

            // Visible before the sheet measures itself, so the popup opens at its final height.
            passcodeView.onShow(false, false);

            // The popup never shows chat content, but it must not be capturable by default either.
            flagSecureReason = new FlagSecureReason(getWindow(), () -> true);
            setOnDismissListener((android.content.DialogInterface.OnDismissListener) d -> onDismissed());
        }

        @Override
        public void show() {
            super.show();
            flagSecureReason.attach();
            flagSecureReason.invalidate();
        }

        @Override
        public void onOpenAnimationEnd() {
            super.onOpenAnimationEnd();
            // Same sequence as the app lock when it appears: lock animation, then offer biometrics.
            passcodeView.playLockAnimation();
            passcodeView.onResume();
            passcodeView.announceForAccessibility(announcement);
        }

        private void onDismissed() {
            showing = false;
            passcodeView.onPause();
            flagSecureReason.detach();
            final ProtectedChatsState.AuthProof result = proof;
            proof = null;
            if (result != null) {
                callback.onAuthenticated(result);
            } else {
                callback.onCancelled();
            }
        }
    }
}
