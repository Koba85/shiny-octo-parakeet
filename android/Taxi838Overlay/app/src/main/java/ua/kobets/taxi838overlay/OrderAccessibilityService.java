package ua.kobets.taxi838overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrderAccessibilityService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private WindowManager windowManager;
    private ProfitOverlay overlay;
    private Handler handler;
    private SharedPreferences prefs;
    private String foregroundPackage = "";

    private final Runnable scanRunnable = this::scanNow;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new ProfitOverlay(this, prefs);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlay, lp);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || handler == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) foregroundPackage = pkg.toString();
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, 120);
    }

    private void scanNow() {
        if (overlay == null || prefs == null) return;

        if (!prefs.getBoolean(MainActivity.ENABLED, true)) {
            overlay.setOrders(Collections.emptyList());
            return;
        }

        if (foregroundPackage.equals(getPackageName())) {
            overlay.setOrders(Collections.emptyList());
            return;
        }

        String target = prefs.getString(MainActivity.TARGET_PACKAGE, "");
        if (target == null) target = "";
        if (!target.isEmpty() && !target.equals(foregroundPackage)) {
            overlay.setOrders(Collections.emptyList());
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            overlay.setOrders(Collections.emptyList());
            return;
        }

        List<Order> orders = Parser.parse(root);

        if (target.isEmpty()) {
            if (orders.size() >= 2 && !foregroundPackage.isEmpty()) {
                prefs.edit().putString(MainActivity.TARGET_PACKAGE, foregroundPackage).apply();
            } else {
                overlay.setOrders(Collections.emptyList());
                return;
            }
        }

        overlay.setOrders(orders);
    }

    @Override
    public void onInterrupt() {
        if (overlay != null) overlay.setOrders(Collections.emptyList());
    }

    @Override
    public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (handler == null) return;
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, 50);
    }

    private static double prefDouble(SharedPreferences p, String key) {
        try {
            String s = p.getString(key, "0");
            if (s == null) return 0.0;
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static final class Order {
        final double fare;
        final double pickupKm;
        final double tripKm;
        final Rect bounds;

        Order(double fare, double pickupKm, double tripKm, Rect bounds) {
            this.fare = fare;
            this.pickupKm = pickupKm;
            this.tripKm = tripKm;
            this.bounds = new Rect(bounds);
        }

        double totalKm() {
            return pickupKm + tripKm;
        }
    }

    private static final class Result {
        double totalKm;
        double fuelCost;
        double serviceCommission;
        double transferCommission;
        double amortization;
        double fixedCost;
        double totalCost;
        double netProfit;
        double netPerKm;
        boolean incomplete;
        boolean acceptable;
    }

    private static Result calculate(Order order, SharedPreferences p) {
        double fuelPrice = prefDouble(p, MainActivity.FUEL_PRICE);
        double consumption = prefDouble(p, MainActivity.CITY_CONSUMPTION);
        double servicePct = prefDouble(p, MainActivity.SERVICE_COMMISSION);
        double transferPct = prefDouble(p, MainActivity.TRANSFER_COMMISSION);
        double amortKm = prefDouble(p, MainActivity.AMORTIZATION_KM);
        double fixed = prefDouble(p, MainActivity.FIXED_ORDER_COST);
        double minProfit = prefDouble(p, MainActivity.MIN_PROFIT);
        double minProfitKm = prefDouble(p, MainActivity.MIN_PROFIT_KM);

        Result r = new Result();
        r.totalKm = order.totalKm();
        r.fuelCost = r.totalKm * consumption / 100.0 * fuelPrice;
        r.serviceCommission = order.fare * servicePct / 100.0;
        r.transferCommission = order.fare * transferPct / 100.0;
        r.amortization = r.totalKm * amortKm;
        r.fixedCost = fixed;
        r.totalCost = r.fuelCost + r.serviceCommission + r.transferCommission + r.amortization + r.fixedCost;
        r.netProfit = order.fare - r.totalCost;
        r.netPerKm = r.totalKm > 0.001 ? r.netProfit / r.totalKm : 0.0;
        r.incomplete = fuelPrice <= 0 || consumption <= 0;
        r.acceptable = !r.incomplete && r.netProfit >= minProfit && r.netPerKm >= minProfitKm;
        return r;
    }

    private static final class Parser {
        private static final Pattern PRICE_WITH_CURRENCY = Pattern.compile("(?:₴\\s*|грн\\.?\\s*)(\\d{2,5}(?:[.,]\\d{1,2})?)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        private static final Pattern PLAIN_PRICE = Pattern.compile("^\\s*(\\d{2,5}(?:[.,]\\d{1,2})?)\\s*$");
        private static final Pattern KM = Pattern.compile("(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*км", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        static List<Order> parse(AccessibilityNodeInfo root) {
            List<AccessibilityNodeInfo> priceNodes = new ArrayList<>();
            collectPriceNodes(root, priceNodes);
            List<Order> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (AccessibilityNodeInfo priceNode : priceNodes) {
                Double fare = parseFare(textOf(priceNode));
                if (fare == null) continue;

                AccessibilityNodeInfo current = priceNode;
                for (int up = 0; up < 8 && current != null; up++) {
                    List<Double> kms = new ArrayList<>();
                    collectKmValues(current, kms, new LinkedHashSet<>());
                    Rect b = new Rect();
                    current.getBoundsInScreen(b);

                    if (kms.size() >= 2 && b.width() > 220 && b.height() > 70) {
                        double pickup = kms.get(0);
                        double trip = kms.get(1);
                        if (pickup >= 0 && pickup < 200 && trip > 0 && trip < 500) {
                            String identity = Math.round(fare * 100) + ":" + Math.round(pickup * 100) + ":" + Math.round(trip * 100) + ":" + b.top;
                            if (seen.add(identity)) {
                                out.add(new Order(fare, pickup, trip, b));
                            }
                            break;
                        }
                    }
                    current = current.getParent();
                }
            }
            return out;
        }

        private static void collectPriceNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
            if (node == null) return;
            String t = textOf(node);
            if (looksLikePrice(t)) out.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) collectPriceNodes(child, out);
            }
        }

        private static boolean looksLikePrice(String text) {
            if (text == null) return false;
            String s = text.trim().toLowerCase(Locale.ROOT);
            if (s.contains("км")) return false;
            Double value = parseFare(s);
            return value != null && value >= 20 && value <= 10000;
        }

        private static Double parseFare(String text) {
            if (text == null) return null;
            Matcher withCurrency = PRICE_WITH_CURRENCY.matcher(text);
            if (withCurrency.find()) return parseNumber(withCurrency.group(1));

            Matcher plain = PLAIN_PRICE.matcher(text);
            if (plain.find()) return parseNumber(plain.group(1));
            return null;
        }

        private static Double parseNumber(String value) {
            try {
                return Double.parseDouble(value.replace(',', '.'));
            } catch (Exception e) {
                return null;
            }
        }

        private static void collectKmValues(AccessibilityNodeInfo node, List<Double> out, Set<String> dedupe) {
            if (node == null || out.size() >= 6) return;
            String t = textOf(node);
            if (t != null) {
                Matcher m = KM.matcher(t);
                while (m.find() && out.size() < 6) {
                    Double v = parseNumber(m.group(1));
                    if (v != null) {
                        String key = String.format(Locale.US, "%.2f@%s", v, t);
                        if (dedupe.add(key)) out.add(v);
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) collectKmValues(child, out, dedupe);
            }
        }

        private static String textOf(AccessibilityNodeInfo node) {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) return text.toString();
            CharSequence description = node.getContentDescription();
            return description == null ? null : description.toString();
        }
    }

    private static final class ProfitOverlay extends View {
        private final SharedPreferences prefs;
        private final List<Order> orders = new ArrayList<>();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ProfitOverlay(Context context, SharedPreferences prefs) {
            super(context);
            this.prefs = prefs;
            this.density = getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
            text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        }

        void setOrders(List<Order> newOrders) {
            orders.clear();
            if (newOrders != null) orders.addAll(newOrders);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean detailed = prefs.getBoolean(MainActivity.DETAILED, true);

            for (Order order : orders) {
                Result r = calculate(order, prefs);
                Rect card = order.bounds;
                if (card.width() <= 0 || card.height() <= 0) continue;

                float boxWidth = Math.min(card.width() * 0.52f, 260f * density);
                float boxHeight = detailed ? 66f * density : 48f * density;
                float margin = 6f * density;

                float left = Math.max(card.left + margin, card.right - boxWidth - margin);
                float top = Math.max(card.top + margin, card.bottom - boxHeight - margin);
                float right = Math.min(card.right - margin, left + boxWidth);
                float bottom = Math.min(card.bottom - margin, top + boxHeight);
                if (bottom - top < 34f * density) {
                    top = card.top + margin;
                    bottom = Math.min(card.bottom - margin, top + boxHeight);
                }

                if (r.incomplete) {
                    background.setColor(Color.argb(220, 85, 85, 85));
                } else if (r.acceptable) {
                    background.setColor(Color.argb(225, 0, 115, 70));
                } else {
                    background.setColor(Color.argb(225, 165, 45, 45));
                }

                RectF box = new RectF(left, top, right, bottom);
                canvas.drawRoundRect(box, 11f * density, 11f * density, background);

                float pad = 9f * density;
                float y = top + 17f * density;
                text.setColor(Color.WHITE);
                text.setTextSize(12f * density);

                if (r.incomplete) {
                    canvas.drawText("Вкажи розхід палива", left + pad, y, text);
                    y += 17f * density;
                } else {
                    canvas.drawText(String.format(Locale.US, "ЧИСТО %.0f ₴  •  %.1f ₴/км", r.netProfit, r.netPerKm), left + pad, y, text);
                    y += 17f * density;
                }

                text.setTextSize(10.5f * density);
                canvas.drawText(String.format(Locale.US, "Σ %.2f км  •  витрати %.0f ₴", r.totalKm, r.totalCost), left + pad, y, text);

                if (detailed && bottom - top >= 58f * density) {
                    y += 16f * density;
                    canvas.drawText(String.format(Locale.US, "пал %.0f  ком %.0f  аморт %.0f", r.fuelCost, r.serviceCommission + r.transferCommission, r.amortization), left + pad, y, text);
                }
            }
        }
    }
}
