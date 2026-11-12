package app.commacabin;


import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class CabinScreen extends LinearLayout {
    record Model(String pickup, String endTime, String now, String gear, String door,
                 String headline, String detail, String countdown, boolean playing,
                 boolean canStart, Integer brightness) {}

    private static final int INK = 0xffedf4f2;
    private static final int MUTED = 0xffa9babc;
    private static final int QUIET = 0xff89a0a1;
    private static final int MINT = 0xffa5ebd5;
    private static final int AMBER = 0xffffc48f;
    private static final int DARK = 0xff0a141b;
    private static final int PANEL = 0xff101f28;
    private static final int LINE = 0xff2b424c;

    private final LinearLayout columns;
    private final FrameLayout restCard;
    private final LinearLayout planCard;
    private final TextView clock;
    private final TextView gear;
    private final TextView door;
    private final TextView sound;
    private final TextView headline;
    private final TextView detail;
    private final TextView countdown;
    private final TextView pickup;
    private final TextView endTime;
    private final Button change;
    private final Button action;
    private final RainMark rain;
    private final Runnable start;
    private final Runnable stop;
    private boolean playing;
    private Boolean wide;

    CabinScreen(Activity activity, Runnable chooseTime, Runnable start, Runnable stop) {
        super(activity);
        this.start = start;
        this.stop = stop;

        setOrientation(VERTICAL);
        setBackgroundColor(DARK);
        setPadding(dp(28), dp(20), dp(28), dp(20));

        LinearLayout header = row();
        TextView logo = text(",", 32, DARK, true);
        logo.setGravity(Gravity.CENTER);
        logo.setIncludeFontPadding(true);
        logo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        logo.setBackground(surface(MINT, 11, Color.TRANSPARENT));
        header.addView(logo, new LayoutParams(dp(42), dp(42)));
        TextView title = text("쉼표 캐빈", 26, INK, true);
        LayoutParams titleLayout = new LayoutParams(0, WRAP_CONTENT, 1);
        titleLayout.setMarginStart(dp(12));
        header.addView(title, titleLayout);
        LinearLayout clockBlock = column();
        clockBlock.setGravity(Gravity.END);
        TextView clockLabel = text("현재 시각", 12, QUIET, true);
        clockLabel.setGravity(Gravity.END);
        clockBlock.addView(clockLabel);
        clock = time("--:--:--", 21, INK);
        clock.setGravity(Gravity.END);
        addBelow(clockBlock, clock, 2);
        header.addView(clockBlock);
        addBelow(this, header, 0);

        LinearLayout status = row();
        status.setBackground(surface(0xff0e1a22, 13, LINE));
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout gearBlock = column();
        gearBlock.addView(text("기어", 12, QUIET, true));
        gear = text("확인 중", 17, INK, true);
        addBelow(gearBlock, gear, 3);
        status.addView(gearBlock, new LayoutParams(0, WRAP_CONTENT, 1));
        View divider = new View(activity);
        divider.setBackgroundColor(LINE);
        LayoutParams dividerLayout = new LayoutParams(dp(1), dp(30));
        dividerLayout.setMarginEnd(dp(14));
        status.addView(divider, dividerLayout);
        LinearLayout doorBlock = column();
        doorBlock.addView(text("조수석 문", 12, QUIET, true));
        door = text("확인 중", 17, INK, true);
        addBelow(doorBlock, door, 3);
        status.addView(doorBlock, new LayoutParams(0, WRAP_CONTENT, 1));
        addBelow(this, status, 14);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LayoutParams scrollLayout = new LayoutParams(MATCH_PARENT, 0, 1);
        scrollLayout.topMargin = dp(14);
        addView(scroll, scrollLayout);

        columns = row();
        columns.setGravity(Gravity.TOP);
        scroll.addView(columns, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        restCard = new FrameLayout(activity);
        restCard.setClipToOutline(true);
        restCard.setBackground(surface(PANEL, 22, LINE));
        rain = new RainMark(activity);
        restCard.addView(rain, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        LinearLayout restCopy = column();
        restCopy.setPadding(dp(26), dp(25), dp(26), dp(25));
        restCopy.setGravity(Gravity.CENTER_VERTICAL);
        sound = text("빗소리 꺼짐", 14, MUTED, true);
        sound.setPadding(dp(11), dp(7), dp(11), dp(7));
        sound.setBackground(surface(0xff1b2c35, 9, Color.TRANSPARENT));
        restCopy.addView(sound, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        headline = text("휴식 준비", 37, INK, true);
        headline.setAccessibilityHeading(true);
        addBelow(restCopy, headline, 24);
        detail = text("", 19, MUTED, false);
        detail.setLineSpacing(dp(3), 1f);
        addBelow(restCopy, detail, 11);
        countdown = time("", 27, MINT);
        countdown.setVisibility(GONE);
        addBelow(restCopy, countdown, 22);
        restCard.addView(restCopy, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        planCard = column();
        planCard.setGravity(Gravity.CENTER_VERTICAL);
        planCard.setPadding(dp(24), dp(20), dp(24), dp(20));
        planCard.setBackground(surface(0xff13212b, 22, LINE));
        addBelow(planCard, text("픽업", 15, MUTED, true), 0);
        pickup = time("--:--", 43, INK);
        addBelow(planCard, pickup, 4);
        View rule = new View(activity);
        rule.setBackgroundColor(LINE);
        LayoutParams ruleLayout = new LayoutParams(MATCH_PARENT, dp(1));
        ruleLayout.topMargin = dp(12);
        ruleLayout.bottomMargin = dp(12);
        planCard.addView(rule, ruleLayout);
        addBelow(planCard, text("휴식 종료 예정 · 픽업 2분 전", 14, MUTED, true), 0);
        endTime = time("--:--", 43, MINT);
        addBelow(planCard, endTime, 4);

        action = button("휴식 시작");
        action.setOnClickListener(view -> {
            if (playing) this.stop.run();
            else this.start.run();
        });
        addButton(planCard, action, 17, 56);
        change = button("픽업 시각 변경");
        change.setBackground(ripple(0xff172832, LINE));
        change.setTextColor(INK);
        change.setOnClickListener(view -> chooseTime.run());
        addButton(planCard, change, 7, 49);

        columns.addView(restCard);
        columns.addView(planCard);
        resizeColumns(true);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0) post(() -> resizeColumns(getWidth() >= dp(660)));
    }

    private void resizeColumns(boolean landscape) {
        if (wide != null && wide == landscape) return;
        wide = landscape;
        columns.setOrientation(landscape ? HORIZONTAL : VERTICAL);
        LayoutParams restLayout = landscape
                ? new LayoutParams(0, MATCH_PARENT, 1.22f)
                : new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        restCard.setMinimumHeight(dp(landscape ? 360 : 218));
        restCard.setLayoutParams(restLayout);
        LayoutParams planLayout = landscape
                ? new LayoutParams(0, MATCH_PARENT, 1f)
                : new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        if (landscape) planLayout.setMarginStart(dp(14));
        else planLayout.topMargin = dp(14);
        planCard.setLayoutParams(planLayout);
    }

    void show(Model model) {
        updateText(clock, model.now());
        updateText(gear, model.gear());
        updateText(door, model.door());
        boolean doorOpen = model.door() != null && model.door().contains("열림");
        door.setTextColor(doorOpen ? AMBER : INK);
        updateText(headline, model.headline());
        updateText(detail, model.detail());
        updateText(pickup, model.pickup());
        updateText(endTime, model.endTime());
        updateText(countdown, model.countdown());
        countdown.setVisibility(model.playing() && model.countdown() != null
                && !model.countdown().trim().isEmpty() ? VISIBLE : GONE);
        if (playing != model.playing()) {
            playing = model.playing();
            restCard.setBackground(surface(playing ? 0xff122730 : PANEL, 22,
                    playing ? 0xff375954 : LINE));
            rain.invalidate();
        }

        updateText(sound, playing ? "빗소리 재생 중" : "빗소리 꺼짐");
        sound.setTextColor(playing ? MINT : MUTED);
        String actionLabel = playing ? "휴식 끝내기" : "휴식 시작";
        if (!TextUtils.equals(action.getText(), actionLabel)) {
            action.setText(actionLabel);
            action.setContentDescription(actionLabel);
        }
        boolean allowed = playing || model.canStart();
        if (action.getBackground() == null || action.isEnabled() != allowed
                || !actionLabel.equals(action.getTag())) {
            action.setTag(actionLabel);
            action.setBackground(ripple(playing ? AMBER : allowed ? MINT : 0xff283942,
                    Color.TRANSPARENT));
            action.setTextColor(allowed ? DARK : 0xffbac6c9);
            action.setEnabled(allowed);
        }
        change.setEnabled(!playing);
        change.setAlpha(playing ? 0.48f : 1f);
    }

    private void updateText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
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
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView time(String value, int size, int color) {
        TextView view = text(value, size, color, true);
        view.setFontFeatureSettings("tnum");
        view.setLetterSpacing(-0.035f);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setContentDescription(label);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private void addBelow(LinearLayout parent, View child, int topMargin) {
        LayoutParams layout = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        layout.topMargin = dp(topMargin);
        parent.addView(child, layout);
    }

    private void addButton(LinearLayout parent, Button button, int topMargin, int height) {
        LayoutParams layout = new LayoutParams(MATCH_PARENT, dp(height));
        layout.topMargin = dp(topMargin);
        parent.addView(button, layout);
    }

    private GradientDrawable surface(int color, int radius, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) background.setStroke(dp(1), border);
        return background;
    }

    private RippleDrawable ripple(int fill, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                surface(fill, 11, border), null);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class RainMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        RainMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float wave = Math.min(dp(150), width / 2);
            paint.setColor(playing ? 0x3ba5ebd5 : 0x29678997);
            paint.setStrokeWidth(dp(1));
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 7; i++) {
                float y = height * 0.78f + i * dp(9);
                path.reset();
                path.moveTo(width * 0.33f, y);
                path.cubicTo(width * 0.57f, y + wave * 0.22f, width * 0.70f, y + wave * 0.16f,
                        width * 0.87f, y - wave * 0.29f);
                path.quadTo(width * 0.99f, y - wave * 0.53f, width + dp(6), y - wave * 0.45f);
                canvas.drawPath(path, paint);
            }
        }
    }
}
