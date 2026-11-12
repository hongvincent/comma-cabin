package app.commacabin;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class CabinScreen extends LinearLayout {
    record Model(String pickup, String endTime, String now, String gear, String door,
                 String headline, String detail, String countdown, boolean playing,
                 boolean canStart, Integer brightness, boolean canEditTime) {}

    private static final int INK = 0xfff4efe6;
    private static final int MUTED = 0xffc3d0c4;
    private static final int MINT = 0xffbfdfc9;
    private static final int AMBER = 0xffffd1a7;
    private static final int DARK = 0xff131a16;
    private static final int RULE = 0xff4a584d;
    private static final int STOP_FILL = 0xff29352d;
    private static final int SECONDARY_FILL = 0xff202a24;
    private static final int DISABLED_FILL = 0xff2a302c;
    private static final int DISABLED_INK = 0xffbec8be;

    private final CabinBackdrop backdrop = new CabinBackdrop();
    private final LinearLayout body;
    private final LinearLayout hero;
    private final LinearLayout plan;
    private final LinearLayout actions;
    private final View separator;
    private final TextView clock;
    private final TextView gear;
    private final TextView door;
    private final TextView sound;
    private final TextView headline;
    private final TextView detail;
    private final LinearLayout remaining;
    private final TextView countdown;
    private final TextView pickup;
    private final TextView endTime;
    private final Button action;
    private final Button change;
    private boolean playing;
    private Boolean wide;

    CabinScreen(Activity activity, Runnable chooseTime, Runnable start, Runnable stop) {
        super(activity);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackground(backdrop);
        setPadding(dp(24), dp(20), dp(24), dp(18));

        LinearLayout header = row();
        TextView mark = text(",", 31, DARK, true);
        mark.setIncludeFontPadding(true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(surface(MINT, 11, Color.TRANSPARENT, 0));
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LayoutParams(dp(45), dp(45)));
        TextView title = text("쉼표 캐빈", 24, INK, true);
        LayoutParams titleParams = new LayoutParams(0, WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(13));
        header.addView(title, titleParams);

        LinearLayout clockBlock = column();
        clockBlock.setGravity(Gravity.END);
        TextView clockLabel = text("현재 시각", 17, MUTED, false);
        clockLabel.setGravity(Gravity.END);
        addBelow(clockBlock, clockLabel, 0);
        clock = time("--:--:--", 23, INK);
        clock.setGravity(Gravity.END);
        addBelow(clockBlock, clock, 4);
        header.addView(clockBlock);
        addBelow(this, header, 0);

        LinearLayout status = row();
        status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        gear = text("확인 중", 20, INK, true);
        door = text("확인 중", 20, INK, true);
        status.addView(statusBlock("기어", gear), new LayoutParams(0, WRAP_CONTENT, 1));
        status.addView(statusBlock("조수석 문", door), new LayoutParams(0, WRAP_CONTENT, 1));
        addBelow(this, status, 19);
        addBelow(this, rule(), 13, dp(1));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        addView(scroll, new LayoutParams(MATCH_PARENT, 0, 1));
        body = row();
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(0, dp(24), 0, dp(25));
        scroll.addView(body, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        hero = column();
        hero.setGravity(Gravity.CENTER_VERTICAL);
        sound = text("빗소리 꺼짐", 18, MUTED, true);
        sound.setPadding(dp(13), dp(8), dp(13), dp(8));
        sound.setBackground(surface(SECONDARY_FILL, 14, RULE, 1));
        hero.addView(sound, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        headline = text("휴식 준비", 48, INK, true);
        headline.setAccessibilityHeading(true);
        addBelow(hero, headline, 18);
        detail = text("", 22, MUTED, false);
        detail.setLineSpacing(dp(3), 1f);
        addBelow(hero, detail, 13);
        remaining = column();
        addBelow(remaining, text("남은 휴식", 16, MUTED, true), 0);
        countdown = time("", 42, MINT);
        addBelow(remaining, countdown, 4);
        remaining.setVisibility(GONE);
        addBelow(hero, remaining, 25);
        body.addView(hero);

        separator = rule();
        body.addView(separator);
        plan = column();
        plan.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout times = row();
        times.setGravity(Gravity.TOP);
        pickup = time("--:--", 48, INK);
        endTime = time("--:--", 48, MINT);
        times.addView(planTime("픽업", pickup), new LayoutParams(0, WRAP_CONTENT, 1));
        LayoutParams endParams = new LayoutParams(0, WRAP_CONTENT, 1);
        endParams.setMarginStart(dp(12));
        times.addView(planTime("휴식 종료 예정", endTime), endParams);
        addBelow(plan, times, 0);
        body.addView(plan);

        addBelow(this, rule(), 0, dp(1));
        actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        action = button("휴식 시작");
        action.setOnClickListener(view -> {
            if (playing) stop.run();
            else start.run();
        });
        change = button("픽업 시각 변경");
        change.setOnClickListener(view -> chooseTime.run());
        action.setNextFocusRightId(change.getId());
        action.setNextFocusForwardId(change.getId());
        change.setNextFocusLeftId(action.getId());
        actions.addView(action, new LayoutParams(0, WRAP_CONTENT, 1));
        LayoutParams editParams = new LayoutParams(0, WRAP_CONTENT, 1);
        editParams.setMarginStart(dp(12));
        actions.addView(change, editParams);
        addBelow(this, actions, 14);

        setControl(action, false, MINT, DARK, DARK);
        setControl(change, false, SECONDARY_FILL, INK, RULE);
        resize(false);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0) return;
        post(() -> {
            int actual = getWidth();
            int edge = Math.max(dp(actual >= dp(920) ? 44 : 22), (actual - dp(1540)) / 2);
            int top = dp(getHeight() >= dp(600) ? 28 : 16);
            if (getPaddingLeft() != edge || getPaddingTop() != top) {
                setPadding(edge, top, edge, top);
            }
            resize(actual - edge * 2 >= dp(790));
        });
    }

    private void resize(boolean landscape) {
        if (wide != null && wide == landscape) return;
        wide = landscape;
        body.setOrientation(landscape ? HORIZONTAL : VERTICAL);
        body.setGravity(landscape ? Gravity.CENTER_VERTICAL : Gravity.CENTER_VERTICAL | Gravity.START);
        hero.setLayoutParams(landscape
                ? new LayoutParams(0, WRAP_CONTENT, 1.35f)
                : new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        plan.setLayoutParams(landscape
                ? new LayoutParams(0, WRAP_CONTENT, 1f)
                : new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        LayoutParams separatorParams = landscape
                ? new LayoutParams(dp(1), dp(128))
                : new LayoutParams(MATCH_PARENT, dp(1));
        if (landscape) {
            separatorParams.setMarginStart(dp(38));
            separatorParams.setMarginEnd(dp(38));
        } else {
            separatorParams.topMargin = dp(22);
            separatorParams.bottomMargin = dp(20);
        }
        separator.setLayoutParams(separatorParams);
        headline.setTextSize(landscape ? 56 : 39);
        pickup.setTextSize(landscape ? 56 : 45);
        endTime.setTextSize(landscape ? 56 : 45);
        LayoutParams actionRow = (LayoutParams) actions.getLayoutParams();
        actionRow.width = landscape ? dp(704) : MATCH_PARENT;
        actions.setLayoutParams(actionRow);
    }

    void show(Model model) {
        updateText(clock, model.now());
        updateText(gear, model.gear());
        updateText(door, model.door());
        door.setTextColor("열림".equals(model.door()) ? AMBER : INK);
        updateText(headline, model.headline());
        updateText(detail, model.detail());
        updateText(pickup, model.pickup());
        updateText(endTime, model.endTime());
        updateText(countdown, model.countdown());
        remaining.setVisibility(model.playing() && !TextUtils.isEmpty(model.countdown()) ? VISIBLE : GONE);
        boolean modeChanged = playing != model.playing();
        if (modeChanged) {
            playing = model.playing();
            backdrop.setPlaying(playing);
            updateText(sound, playing ? "빗소리 재생 중" : "빗소리 꺼짐");
            sound.setTextColor(playing ? MINT : MUTED);
            String label = playing ? "휴식 끝내기" : "휴식 시작";
            action.setText(label);
            action.setContentDescription(label);
        }
        boolean actionEnabled = playing || model.canStart();
        if (modeChanged || actionEnabled != action.isEnabled()) {
            setControl(action, actionEnabled, playing ? STOP_FILL : MINT,
                    playing ? AMBER : DARK, playing ? AMBER : DARK);
        }
        boolean changeEnabled = model.canEditTime() && !playing;
        if (changeEnabled != change.isEnabled()) {
            setControl(change, changeEnabled, SECONDARY_FILL, INK, MUTED);
        }
    }

    private LinearLayout statusBlock(String title, TextView value) {
        LinearLayout block = row();
        block.addView(text(title, 17, MUTED, false));
        LayoutParams valueParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        valueParams.setMarginStart(dp(11));
        block.addView(value, valueParams);
        return block;
    }

    private LinearLayout planTime(String label, TextView value) {
        LinearLayout block = column();
        addBelow(block, text(label, 18, MUTED, true), 0);
        addBelow(block, value, 7);
        return block;
    }

    private void setControl(Button button, boolean enabled, int fill, int ink, int border) {
        button.setEnabled(enabled);
        button.setTextColor(enabled ? ink : DISABLED_INK);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled },
                surface(DISABLED_FILL, 17, RULE, 1));
        int focusBorder = fill == MINT ? DARK : MINT;
        states.addState(new int[] { android.R.attr.state_focused },
                surface(fill, 17, focusBorder, 4));
        states.addState(new int[0], surface(fill, 17, border, 1));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x29ffffff), states, null));
    }

    private View rule() {
        View line = new View(getContext());
        line.setBackgroundColor(RULE);
        line.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    private void updateText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView time(String value, int size, int color) {
        TextView view = text(value, size, color, true);
        view.setFontFeatureSettings("tnum");
        view.setLetterSpacing(-0.028f);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(getContext());
        button.setId(View.generateViewId());
        button.setContentDescription(label);
        button.setText(label);
        button.setTextSize(20);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setMinimumHeight(dp(80));
        button.setMinimumWidth(dp(76));
        button.setFocusable(true);
        return button;
    }

    private void addBelow(LinearLayout parent, View child, int topMargin) {
        addBelow(parent, child, topMargin, WRAP_CONTENT);
    }

    private void addBelow(LinearLayout parent, View child, int topMargin, int height) {
        LayoutParams layout = new LayoutParams(MATCH_PARENT, height);
        layout.topMargin = dp(topMargin);
        parent.addView(child, layout);
    }

    private GradientDrawable surface(int color, int radius, int border, int borderWidth) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (borderWidth > 0) background.setStroke(dp(borderWidth), border);
        return background;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CabinBackdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path reflection = new Path();
        private Shader charcoal;
        private Shader greenGlow;
        private Shader amberGlow;
        private Shader edgeLight;
        private boolean playing;

        void setPlaying(boolean value) {
            if (playing == value) return;
            playing = value;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            float w = bounds.width();
            float h = bounds.height();
            if (w == 0 || h == 0) return;
            charcoal = new LinearGradient(0, 0, 0, h,
                    new int[] { 0xff151a16, 0xff20241d, 0xff151713 },
                    new float[] { 0f, 0.66f, 1f }, Shader.TileMode.CLAMP);
            greenGlow = new RadialGradient(w * 0.29f, h * 0.47f, Math.max(w * 0.52f, h * 0.55f),
                    0xff344e3b, 0x00344e3b, Shader.TileMode.CLAMP);
            amberGlow = new RadialGradient(w * 0.90f, h * 0.85f, Math.max(w * 0.41f, h * 0.32f),
                    0xff55442f, 0x0055442f, Shader.TileMode.CLAMP);
            edgeLight = new LinearGradient(0, 0, w, 0,
                    new int[] { 0x00688169, 0x3a8f9a70, 0x706f5b44, 0x006f5b44 },
                    new float[] { 0f, 0.24f, 0.72f, 1f }, Shader.TileMode.CLAMP);
            reflection.reset();
            reflection.moveTo(0, h * 0.91f);
            reflection.cubicTo(w * 0.30f, h * 0.88f, w * 0.58f, h * 0.97f, w, h * 0.83f);
        }

        @Override
        public void draw(Canvas canvas) {
            if (charcoal == null) return;
            Rect bounds = getBounds();
            int checkpoint = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(charcoal);
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), paint);
            paint.setShader(greenGlow);
            paint.setAlpha(playing ? 205 : 117);
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), paint);
            paint.setShader(amberGlow);
            paint.setAlpha(playing ? 140 : 100);
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), paint);
            paint.setShader(edgeLight);
            paint.setAlpha(playing ? 130 : 92);
            paint.setStrokeWidth(Math.max(1f, bounds.height() * 0.0025f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(reflection, paint);
            canvas.restoreToCount(checkpoint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
