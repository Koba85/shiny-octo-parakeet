package ua.koba.taxi838overlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrderAccessibilityService extends AccessibilityService {
    private static final String PREFS = "calc";
    private static final int TYPE_ACCESSIBILITY_OVERLAY = 2032;
    private static final Pattern PRICE_PATTERN = Pattern.compile("₴\\s*(\\d{2,5}(?:[.,]\\d{1,2})?)");
    private static final Pattern KM_PATTERN = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*км", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> overlays = new ArrayList<>();
    private WindowManager windowManager;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshOverlays();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 80;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
        scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null
                && getPackageName().contentEquals(event.getPackageName())) {
            return;
        }
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
        clearOverlays();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        clearOverlays();
        super.onDestroy();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 140);
    }

    private void refreshOverlays() {
        clearOverlays();
        if (windowManager == null) return;

        List<CardData> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) collectCards(root, cards, seen);
                }
            }
        } catch (Throwable ignored) {
        }

        if (cards.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) collectCards(root, cards, seen);
        }

        Collections.sort(cards, Comparator.comparingInt(a -> a.bounds.top));
        for (CardData card : cards) addOverlay(card);
    }

    private void collectCards(AccessibilityNodeInfo root, List<CardData> out, Set<String> seen) {
        List<AccessibilityNodeInfo> priceNodes = new ArrayList<>();
        findPriceNodes(root, priceNodes);

        for (AccessibilityNodeInfo priceNode : priceNodes) {
            AccessibilityNodeInfo cardNode = chooseCardContainer(priceNode);
            if (cardNode == null) continue;

            Extracted extracted = extract(cardNode);
            if (extracted.prices.size() != 1 || extracted.kilometers.size() < 2) continue;

            Rect bounds = new Rect();
            cardNode.getBoundsInScreen(bounds);
            if (bounds.width() < dp(120) || bounds.height() < dp(45)) continue;

            double fare = extracted.prices.get(0);
            double firstKm = extracted.kilometers.get(0);
            double secondKm = extracted.kilometers.get(1);
            if (fare <= 0 || firstKm < 0 || secondKm < 0) continue;

            double totalKm = firstKm + secondKm;
            if (totalKm <= 0.05 || totalKm > 1000) continue;

            String key = bounds.left + ":" + bounds.top + ":" + bounds.right + ":" + bounds.bottom + ":" + fare;
            if (!seen.add(key)) continue;

            boolean outOfCity = isOutOfCity(extracted.allText.toString());
            SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            double fuelPrice = p.getFloat("fuel_price", 80.0f);
            double consumption = p.getFloat(outOfCity ? "highway_cons" : "city_cons", 8.5f);
            double amortPct = p.getFloat("amort_pct", 0.0f);

            double fuelCost = totalKm * consumption / 100.0 * fuelPrice;
            double pricePerKm = fare / totalKm;
            double profit = fare - fuelCost - (fare * amortPct / 100.0);

            CardData card = new CardData();
            card.bounds = bounds;
            card.totalKm = totalKm;
            card.fuelCost = fuelCost;
            card.pricePerKm = pricePerKm;
            card.profit = profit;
            out.add(card);
        }
    }

    private void findPriceNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        String own = nodeText(node);
        if (own.indexOf('₴') >= 0 && PRICE_PATTERN.matcher(own).find()) out.add(node);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findPriceNodes(child, out);
        }
    }

    private AccessibilityNodeInfo chooseCardContainer(AccessibilityNodeInfo priceNode) {
        AccessibilityNodeInfo current = priceNode;
        AccessibilityNodeInfo best = null;

        for (int i = 0; i < 10 && current != null; i++) {
            Extracted e = extract(current);
            if (e.prices.size() == 1 && e.kilometers.size() >= 2) {
                best = current;
            }
            if (e.prices.size() > 1) break;
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            current = parent;
        }
        return best;
    }

    private Extracted extract(AccessibilityNodeInfo node) {
        Extracted out = new Extracted();
        extractRecursive(node, out);
        return out;
    }

    private void extractRecursive(AccessibilityNodeInfo node, Extracted out) {
        if (node == null) return;
        String text = nodeText(node);
        if (!text.isEmpty()) {
            out.allText.append(' ').append(text);

            Matcher pm = PRICE_PATTERN.matcher(text);
            while (pm.find()) {
                Double v = number(pm.group(1));
                if (v != null) out.prices.add(v);
            }

            Matcher km = KM_PATTERN.matcher(text);
            while (km.find()) {
                Double v = number(km.group(1));
                if (v != null) out.kilometers.add(v);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) extractRecursive(child, out);
        }
    }

    private String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) return text.toString();
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString();
    }

    private Double number(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isOutOfCity(String text) {
        String s = text.toLowerCase(new Locale("uk", "UA"));
        if (s.contains("кіровоградська область") || s.contains("кропивницький район")
                || s.contains(" обл.") || s.contains(" область")
                || s.contains("село ") || s.contains("смт ") || s.contains("селище ")) {
            return true;
        }
        if (s.contains("фортечний") || s.contains("подільський")) return false;
        return false;
    }

    private void addOverlay(CardData card) {
        TextView view = new TextView(this);
        view.setText(buildOverlayText(card));
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setSingleLine(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 25, 29, 34));
        bg.setCornerRadius(dp(8));
        view.setBackground(bg);

        int width = Math.max(dp(220), card.bounds.width() - dp(16));
        int height = dp(52);
        int x = card.bounds.left + dp(8);
        int y = Math.max(card.bounds.top, card.bounds.bottom - height - dp(6));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                height,
                TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;

        try {
            windowManager.addView(view, lp);
            overlays.add(view);
        } catch (Throwable ignored) {
        }
    }

    private String buildOverlayText(CardData c) {
        return "Σ " + fmt(c.totalKm, 1) + " км   ·   ⛽ " + fmt(c.fuelCost, 0) + " грн   ·   "
                + fmt(c.pricePerKm, 2) + " грн/км\n"
                + "ПРИБУТОК: " + fmt(c.profit, 0) + " грн";
    }

    private String fmt(double value, int decimals) {
        String pattern = decimals == 0 ? "%.0f" : (decimals == 1 ? "%.1f" : "%.2f");
        return String.format(Locale.getDefault(), pattern, value);
    }

    private void clearOverlays() {
        if (windowManager == null) {
            overlays.clear();
            return;
        }
        for (TextView view : overlays) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Throwable ignored) {
            }
        }
        overlays.clear();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class Extracted {
        final List<Double> prices = new ArrayList<>();
        final List<Double> kilometers = new ArrayList<>();
        final StringBuilder allText = new StringBuilder();
    }

    private static class CardData {
        Rect bounds;
        double totalKm;
        double fuelCost;
        double pricePerKm;
        double profit;
    }
}
