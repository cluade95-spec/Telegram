package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

/**
 * Floating authentication for a protected chat. It hosts Telegram's own unlock screen
 * ({@link PasscodeView}, in chat mode) inside a dismissible card above the current screen, so the
 * keypad, digit animations, error feedback, biometrics and retry throttling are exactly those of
 * the app lock. Tapping outside and system Back dismiss it and leave the destination unopened.
 */
public class ProtectedChatAuthSheet {

    public enum Mode {
        UNLOCK, PROTECT, UNPROTECT
    }

    public interface Callback {
        /** Called once, after the card has been dismissed, with a single-use proof. */
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
        new CardDialog(context, mode, chatTitle, callback).show();
    }

    private static class CardDialog extends Dialog {

        private final Callback callback;
        private final FrameLayout windowView;
        private final FrameLayout card;
        private final PasscodeView passcodeView;
        private final FlagSecureReason flagSecureReason;
        private ProtectedChatsState.AuthProof proof;

        CardDialog(Context context, Mode mode, CharSequence chatTitle, Callback callback) {
            super(context, R.style.TransparentDialog);
            this.callback = callback;

            final boolean pin = SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN;

            windowView = new FrameLayout(context);
            // Outside tap dismisses; the card below consumes its own touches.
            windowView.setOnClickListener(v -> cancel());
            windowView.setContentDescription(LocaleController.getString(R.string.Close));

            card = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    final int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
                    final int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
                    final int width = Math.min(availableWidth, dp(328));
                    final int wanted = dp(pin ? PasscodeView.CHAT_LOCK_PIN_CARD_HEIGHT : PasscodeView.CHAT_LOCK_PASSWORD_CARD_HEIGHT);
                    final int height = Math.min(availableHeight, wanted);
                    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                }
            };
            card.setClickable(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
                }
            });
            card.setClipToOutline(true);

            passcodeView = new PasscodeView(context);
            CharSequence title;
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
            passcodeView.setChatLockMode(title, proof -> {
                this.proof = proof;
                dismiss();
            });
            card.addView(passcodeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            windowView.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 16, 16, 16, 16));

            // The card never shows chat content, but it must not be capturable by default either.
            flagSecureReason = new FlagSecureReason(getWindow(), () -> true);
            setCanceledOnTouchOutside(true);
            setOnDismissListener(d -> onDismissed());
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(windowView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Window window = getWindow();
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.FILL;
            params.dimAmount = 0.5f;
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            window.setAttributes(params);
        }

        @Override
        public void show() {
            super.show();
            flagSecureReason.attach();
            flagSecureReason.invalidate();
            passcodeView.onShow(false, false);
            passcodeView.playLockAnimation();
            // Same entry point the app lock uses to offer biometrics right away.
            passcodeView.onResume();
        }

        @Override
        public void onBackPressed() {
            cancel();
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
