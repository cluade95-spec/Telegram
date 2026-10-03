package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CodeFieldContainer;
import org.telegram.ui.CodeNumberField;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.Executor;

/**
 * Compact floating passcode prompt used for protected chats. It is a bottom sheet over the
 * current screen (never the full-screen app lock) and verifies against the single existing
 * passcode credential through {@link ProtectedChatsState}. Tapping outside and system Back
 * dismiss it; a wrong passcode keeps it open with the usual shake and error haptics.
 */
public class ProtectedChatAuthSheet {

    public enum Mode {
        UNLOCK, PROTECT, UNPROTECT
    }

    public interface Callback {
        /** Called once, after the sheet has been dismissed, with a single-use proof. */
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
        new ProtectedChatAuthSheet(context, resourcesProvider, mode, chatTitle, callback);
    }

    private final Context context;
    private final Callback callback;
    private final BottomSheet sheet;
    private final boolean isPin = SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN;

    private final TextView subtitleView;
    private final TextView retryView;
    private CodeFieldContainer codeFieldContainer;
    private EditTextBoldCursor passwordEditText;
    private OutlineTextContainerView outlinePasswordView;
    private CustomPhoneKeyboardView keyboardView;
    private ImageView fingerprintButton;
    private FlagSecureReason flagSecureReason;

    private boolean done;
    private boolean biometricInProgress;
    private int lastRetryValue;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            updateRetry();
        }
    };

    private ProtectedChatAuthSheet(Context context, Theme.ResourcesProvider resourcesProvider, Mode mode, CharSequence chatTitle, Callback callback) {
        this.context = context;
        this.callback = callback;

        BottomSheet.Builder builder = new BottomSheet.Builder(context, true, resourcesProvider);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, dp(16), 0, 0);

        RLottieImageView lockImage = new RLottieImageView(context);
        lockImage.setFocusable(false);
        lockImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lockImage.setAnimation(R.raw.tsv_setup_intro, 80, 80);
        lockImage.setAutoRepeat(false);
        lockImage.playAnimation();
        root.addView(lockImage, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        TextView titleView = new TextView(context);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setText(LocaleController.getString(mode == Mode.UNLOCK ? R.string.ChatPasscodeUnlockTitle : mode == Mode.PROTECT ? R.string.ChatPasscodeProtectTitle : R.string.ChatPasscodeUnprotectTitle));
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 12, 24, 0));

        TextView chatView = new TextView(context);
        chatView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6, resourcesProvider));
        chatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        chatView.setGravity(Gravity.CENTER_HORIZONTAL);
        chatView.setSingleLine(true);
        chatView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chatView.setText(chatTitle);
        root.addView(chatView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 4, 24, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6, resourcesProvider));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));

        FrameLayout inputContainer = new FrameLayout(context);
        root.addView(inputContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        retryView = new TextView(context);
        retryView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        retryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        retryView.setGravity(Gravity.CENTER_HORIZONTAL);
        retryView.setVisibility(View.GONE);
        root.addView(retryView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 8, 24, 0));

        if (isPin) {
            buildPinInput(context, resourcesProvider, inputContainer);
        } else {
            buildPasswordInput(context, resourcesProvider, inputContainer);
        }

        fingerprintButton = new ImageView(context);
        fingerprintButton.setImageResource(R.drawable.fingerprint);
        fingerprintButton.setScaleType(ImageView.ScaleType.CENTER);
        fingerprintButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        fingerprintButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        fingerprintButton.setContentDescription(LocaleController.getString(R.string.UnlockToUse));
        fingerprintButton.setOnClickListener(v -> startBiometric());
        boolean biometric = biometricUsable();
        fingerprintButton.setVisibility(biometric ? View.VISIBLE : View.GONE);
        root.addView(fingerprintButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        subtitleView.setText(LocaleController.getString(biometric ? R.string.EnterPINorFingerprint : R.string.EnterPIN));
        if (!isPin) {
            subtitleView.setText(LocaleController.getString(R.string.EnterYourPasscode));
        }

        if (isPin && useCustomKeyboard()) {
            keyboardView = new CustomPhoneKeyboardView(context);
            keyboardView.setDispatchBackWhenEmpty(false);
            root.addView(keyboardView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
            for (CodeNumberField f : codeFieldContainer.codeField) {
                f.setShowSoftInputOnFocusCompat(false);
                f.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        keyboardView.setEditText(f);
                        keyboardView.setDispatchBackWhenEmpty(false);
                    }
                });
            }
        } else {
            root.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16));
        }

        builder.setCustomView(root);
        sheet = builder.create();
        sheet.setCanDismissWithSwipe(false);
        sheet.setOnDismissListener(d -> onDismissed());
        showing = true;

        // The floating prompt shows no chat content, but never allow it to be recorded by default.
        flagSecureReason = new FlagSecureReason(sheet.getWindow(), () -> true);
        sheet.show();
        flagSecureReason.attach();
        flagSecureReason.invalidate();

        updateRetry();
        focusInput();
        if (biometric && SharedConfig.passcodeRetryInMs <= 0) {
            AndroidUtilities.runOnUIThread(this::startBiometric, 250);
        }
    }

    // ------------------------------------------------------------------ inputs

    private boolean useCustomKeyboard() {
        return !AndroidUtilities.isTablet() && AndroidUtilities.displaySize.x < AndroidUtilities.displaySize.y && !AndroidUtilities.isAccessibilityTouchExplorationEnabled();
    }

    private void buildPinInput(Context context, Theme.ResourcesProvider resourcesProvider, FrameLayout container) {
        codeFieldContainer = new CodeFieldContainer(context) {
            @Override
            protected void processNextPressed() {
                postDelayed(ProtectedChatAuthSheet.this::submit, 160);
            }
        };
        codeFieldContainer.setNumbersCount(4, CodeFieldContainer.TYPE_PASSCODE);
        for (CodeNumberField f : codeFieldContainer.codeField) {
            f.setTransformationMethod(PasswordTransformationMethod.getInstance());
            f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            f.setContentDescription(LocaleController.getString(R.string.EnterPIN));
        }
        container.addView(codeFieldContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 40, 0, 40, 0));
    }

    private void buildPasswordInput(Context context, Theme.ResourcesProvider resourcesProvider, FrameLayout container) {
        outlinePasswordView = new OutlineTextContainerView(context);
        outlinePasswordView.setText(LocaleController.getString(R.string.EnterPassword));

        passwordEditText = new EditTextBoldCursor(context);
        passwordEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        passwordEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        passwordEditText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        passwordEditText.setBackground(null);
        passwordEditText.setMaxLines(1);
        passwordEditText.setLines(1);
        passwordEditText.setSingleLine(true);
        passwordEditText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        passwordEditText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordEditText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        passwordEditText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider));
        passwordEditText.setCursorSize(dp(20));
        passwordEditText.setCursorWidth(1.5f);
        passwordEditText.setPadding(dp(16), dp(16), dp(16), dp(16));
        passwordEditText.setOnFocusChangeListener((v, hasFocus) -> outlinePasswordView.animateSelection(hasFocus ? 1 : 0));
        passwordEditText.setOnEditorActionListener((tv, actionId, event) -> {
            submit();
            return true;
        });
        passwordEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        outlinePasswordView.addView(passwordEditText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(outlinePasswordView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 0));
    }

    private void focusInput() {
        AndroidUtilities.runOnUIThread(() -> {
            if (done || retryView.getVisibility() == View.VISIBLE) {
                return;
            }
            if (isPin) {
                codeFieldContainer.codeField[0].requestFocus();
                if (keyboardView == null) {
                    AndroidUtilities.showKeyboard(codeFieldContainer.codeField[0]);
                }
            } else {
                passwordEditText.requestFocus();
                AndroidUtilities.showKeyboard(passwordEditText);
            }
        }, 200);
    }

    // ------------------------------------------------------------------ verification

    private String currentInput() {
        return isPin ? codeFieldContainer.getCode() : passwordEditText.getText().toString();
    }

    private void clearInput() {
        if (isPin) {
            for (CodeNumberField f : codeFieldContainer.codeField) {
                f.setText("");
            }
            codeFieldContainer.codeField[0].requestFocus();
        } else {
            passwordEditText.setText("");
        }
    }

    private void submit() {
        if (done) {
            return;
        }
        ProtectedChats.refreshRetryTimer();
        if (SharedConfig.passcodeRetryInMs > 0) {
            updateRetry();
            return;
        }
        String input = currentInput();
        if (input.length() == 0 || isPin && input.length() != 4) {
            onError();
            return;
        }
        ProtectedChatsState.Verification[] result = new ProtectedChatsState.Verification[1];
        ProtectedChatsState.AuthProof proof = ProtectedChats.getState().proofFromPasscode(input, result);
        if (proof == null) {
            clearInput();
            onError();
            updateRetry();
            return;
        }
        succeed(proof);
    }

    private void succeed(ProtectedChatsState.AuthProof proof) {
        if (done) {
            return;
        }
        done = true;
        AndroidUtilities.hideKeyboard(isPin ? codeFieldContainer.codeField[0] : passwordEditText);
        pendingProof = proof;
        sheet.dismiss();
    }

    private ProtectedChatsState.AuthProof pendingProof;

    private void onDismissed() {
        showing = false;
        AndroidUtilities.cancelRunOnUIThread(retryRunnable);
        if (flagSecureReason != null) {
            flagSecureReason.detach();
        }
        ProtectedChatsState.AuthProof proof = pendingProof;
        pendingProof = null;
        if (proof != null) {
            callback.onAuthenticated(proof);
        } else {
            callback.onCancelled();
        }
    }

    private void onError() {
        BotWebViewVibrationEffect.NOTIFICATION_ERROR.vibrate();
        if (isPin) {
            for (CodeNumberField f : codeFieldContainer.codeField) {
                f.animateErrorProgress(1f);
            }
            AndroidUtilities.shakeViewSpring(codeFieldContainer, 10, () -> AndroidUtilities.runOnUIThread(() -> {
                for (CodeNumberField f : codeFieldContainer.codeField) {
                    f.animateErrorProgress(0f);
                }
            }, 150));
        } else {
            outlinePasswordView.animateError(1f);
            AndroidUtilities.shakeViewSpring(outlinePasswordView, 4, () -> AndroidUtilities.runOnUIThread(() -> outlinePasswordView.animateError(0f), 1000));
        }
    }

    /** Mirrors the retry delay of the existing lock screen; the counters are shared. */
    private void updateRetry() {
        ProtectedChats.refreshRetryTimer();
        if (SharedConfig.passcodeRetryInMs > 0) {
            int value = Math.max(1, (int) Math.ceil(SharedConfig.passcodeRetryInMs / 1000.0));
            if (value != lastRetryValue) {
                retryView.setText(LocaleController.formatString(R.string.TooManyTries, LocaleController.formatPluralString("Seconds", value)));
                lastRetryValue = value;
            }
            if (retryView.getVisibility() != View.VISIBLE) {
                retryView.setVisibility(View.VISIBLE);
                retryView.announceForAccessibility(retryView.getText());
            }
            View input = isPin ? codeFieldContainer : outlinePasswordView;
            input.setEnabled(false);
            input.setAlpha(0.4f);
            AndroidUtilities.cancelRunOnUIThread(retryRunnable);
            AndroidUtilities.runOnUIThread(retryRunnable, 250);
        } else if (retryView.getVisibility() == View.VISIBLE) {
            retryView.setVisibility(View.GONE);
            lastRetryValue = 0;
            View input = isPin ? codeFieldContainer : outlinePasswordView;
            input.setEnabled(true);
            input.setAlpha(1f);
            focusInput();
        }
    }

    // ------------------------------------------------------------------ biometrics

    private boolean biometricUsable() {
        if (Build.VERSION.SDK_INT < 23 || !SharedConfig.useFingerprintLock || LaunchActivity.instance == null) {
            return false;
        }
        try {
            return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
                    && FingerprintController.isKeyReady() && !FingerprintController.checkDeviceFingerprintsChanged();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private void startBiometric() {
        if (done || biometricInProgress || !biometricUsable() || SharedConfig.passcodeRetryInMs > 0) {
            return;
        }
        final Activity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        try {
            biometricInProgress = true;
            final Executor executor = ContextCompat.getMainExecutor(context);
            BiometricPrompt prompt = new BiometricPrompt(LaunchActivity.instance, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errMsgId, @NonNull CharSequence errString) {
                    biometricInProgress = false;
                    focusInput();
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    biometricInProgress = false;
                    ProtectedChatsState.AuthProof proof = ProtectedChats.getState().proofFromBiometric();
                    if (proof != null) {
                        SharedConfig.badPasscodeTries = 0;
                        SharedConfig.saveConfig();
                        succeed(proof);
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    // The platform prompt keeps itself open; nothing to do.
                }
            });
            final BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(LocaleController.getString(R.string.ChatPasscodeUnlockTitle))
                    .setNegativeButtonText(LocaleController.getString(isPin ? R.string.UsePIN : R.string.EnterYourPasscode))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            prompt.authenticate(promptInfo);
        } catch (Throwable e) {
            biometricInProgress = false;
            FileLog.e(e);
        }
    }
}
