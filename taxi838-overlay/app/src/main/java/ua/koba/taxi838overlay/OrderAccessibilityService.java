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

    // 838 can expose text differently depending on Android/WebView/Compose build.
    // Accept: ₴639, 639₴, 639 грн, "639 гривень".
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:₴\\s*(\\d{2,5}(?:[.,]\\d{1,2})?)|(\\d{2,5}(?:[.,]\\d{1,2})?)\\s*(?:₴|грн|грив(?:ня|ні|ень)))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Accept Cyrillic/Latin km and non-breaking spaces.
    private static final Pattern KM_PATTERN = Pattern.compile(
            "(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(?:км|km)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^\\s*(\\d{2,5}(?:[.,]\\d{1,2})?)\\s*$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> overlays = new ArrayList<>();
    private WindowManager windowManager;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshOverlays();
            // Periodic refresh is deliberate: 838 can update cards without emitting a useful event.
            handler.postDelayed(this, 700);
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
        info.notificationTimeout = 50;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Periodic refresh is always active; events only accelerate the next scan.
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 80);
    }

    @Override public void onInterrupt() { clearOverlays(); }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        clearOverlays();
        super.onDestroy();
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
                    if (root == null || isOwnNode(root)) continue;
                    collectCardsRobust(root, cards, seen);
                }
            }
        } catch (Throwable ignored) { }

        if (cards.isEmpty()) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && !isOwnNode(root)) collectCardsRobust(root, cards, seen);
            } catch (Throwable ignored) { }
        }

        Collections.sort(cards, Comparator.comparingInt(a -> a.bounds.top));
        for (CardData card : cards) addOverlay(card);
    }

    private boolean isOwnNode(AccessibilityNodeInfo node) {
        CharSequence p = node.getPackageName();
        return p != null && getPackageName().contentEquals(p);
    }

    private void collectCardsRobust(AccessibilityNodeInfo root, List<CardData> out, Set<String> seen) {
        // Pass 1: a single node/contentDescription already contains fare + both km values.
        collectPackedNodes(root, out, seen);

        // Pass 2: normal UI tree, where fare and km values are separate siblings.
        List<RawNode> raw = new ArrayList<>();
        flatten(root, raw);
        if (raw.isEmpty()) return;

        List<RawNode> kmNodes = new ArrayList<>();
        List<RawNode> strongPriceNodes = new ArrayList<>();
        for (RawNode n : raw) {
            if (!extractKms(n.text).isEmpty()) kmNodes.add(n);
            if (extractPrice(n.text) != null) strongPriceNodes.add(n);
        }

        // Strong currency-labelled fare candidates first.
        for (RawNode fareNode : strongPriceNodes) {
            buildFromHeaderRow(fareNode, kmNodes, out, seen);
        }

        // Fallback for 838 builds where the currency symbol isn't exposed to Accessibility:
        // a bare 2-5 digit number on the same header row as two "km" values.
        for (RawNode n : raw) {
            if (extractPrice(n.text) != null) continue;
            Matcher m = NUMBER_PATTERN.matcher(clean(n.text));
            if (!m.matches()) continue;
            Double v = number(m.group(1));
            if (v == null || v < 40 || v > 10000) continue;
            n.fallbackFare = v;
            buildFromHeaderRow(n, kmNodes, out, seen);
        }
    }

    private void collectPackedNodes(AccessibilityNodeInfo node, List<CardData> out, Set<String> seen) {
        if (node == null) return;
        String text = nodeText(node);
        Double fare = extractPrice(text);
        List<Double> kms = extractKms(text);
        if (fare != null && kms.size() >= 2) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (validBounds(b)) addCard(out, seen, fare, kms.get(0), kms.get(1), b, text);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) collectPackedNodes(c, out, seen);
        }
    }

    private void flatten(AccessibilityNodeInfo node, List<RawNode> out) {
        if (node == null) return;
        String text = nodeText(node);
        if (!text.trim().isEmpty()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) out.add(new RawNode(node, clean(text), b));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) flatten(c, out);
        }
    }

    private void buildFromHeaderRow(RawNode fareNode, List<RawNode> kmNodes, List<CardData> out, Set<String> seen) {
        Double fare = extractPrice(fareNode.text);
        if (fare == null) fare = fareNode.fallbackFare;
        if (fare == null) return;

        int cy = centerY(fareNode.bounds);
        List<KmHit> hits = new ArrayList<>();
        for (RawNode n : kmNodes) {
            int ncy = centerY(n.bounds);
            if (Math.abs(ncy - cy) > dp(48)) continue;
            List<Double> values = extractKms(n.text);
            for (Double value : values) hits.add(new KmHit(value, n));
        }
        if (hits.size() < 2) return;

        // Prefer values physically to the right of fare, as on 838 order headers.
        Collections.sort(hits, (a, b) -> {
            int ar = a.node.bounds.left >= fareNode.bounds.left ? 0 : 1;
            int br = b.node.bounds.left >= fareNode.bounds.left ? 0 : 1;
            if (ar != br) return Integer.compare(ar, br);
            return Integer.compare(a.node.bounds.left, b.node.bounds.left);
        });

        double km1 = hits.get(0).value;
        double km2 = hits.get(1).value;
        Rect union = new Rect(fareNode.bounds);
        union.union(hits.get(0).node.bounds);
        union.union(hits.get(1).node.bounds);

        Rect cardBounds = chooseContainerBounds(fareNode.node, union);
        String cardText = collectAncestorText(fareNode.node, cardBounds);
        addCard(out, seen, fare, km1, km2, cardBounds, cardText);
    }

    private Rect chooseContainerBounds(AccessibilityNodeInfo start, Rect row) {
        Rect best = new Rect(row);
        AccessibilityNodeInfo cur = start;
        for (int i = 0; i < 9 && cur != null; i++) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            if (b.contains(row) && b.width() >= dp(260)) {
                best = new Rect(b);
                if (b.height() >= dp(120) && b.height() <= dp(520)) break;
            }
            cur = cur.getParent();
        }
        if (best.height() < dp(90)) {
            best.top = Math.max(0, best.top - dp(8));
            best.bottom += dp(180);
        }
        return best;
    }

    private String collectAncestorText(AccessibilityNodeInfo start, Rect targetBounds) {
        AccessibilityNodeInfo cur = start;
        for (int i = 0; i < 9 && cur != null; i++) {
            Rect b = new Rect();
            cur.getBoundsInScreen(b);
            if (b.equals(targetBounds)) return extract(cur).allText.toString();
            cur = cur.getParent();
        }
        return nodeText(start);
    }

    private void addCard(List<CardData> out, Set<String> seen, double fare, double km1, double km2, Rect bounds, String allText) {
        if (fare <= 0 || km1 < 0 || km2 < 0) return;
        double totalKm = km1 + km2;
        if (totalKm <= 0.05 || totalKm > 1200) return;
        if (!validBounds(bounds)) return;

        String key = Math.round(fare) + ":" + Math.round(km1 * 10) + ":" + Math.round(km2 * 10) + ":" + (bounds.top / Math.max(1, dp(12)));
        if (!seen.add(key)) return;

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double fuelPrice = p.getFloat("fuel_price", 80.0f);
        double cityCons = p.getFloat("city_cons", 8.5f);
        double highwayCons = p.getFloat("highway_cons", 8.5f);
        double amortPct = p.getFloat("amort_pct", 0.0f);

        // With only the 838 card visible, exact city/highway split is not exposed.
        // If the card clearly contains an out-of-city locality/region, use highway consumption;
        // otherwise use city consumption. When both settings are equal this is exact either way.
        boolean outOfCity = isOutOfCity(allText == null ? "" : allText);
        double consumption = outOfCity ? highwayCons : cityCons;

        double fuelCost = totalKm * consumption / 100.0 * fuelPrice;
        double pricePerKm = fare / totalKm;
        double profit = fare - fuelCost - (fare * amortPct / 100.0);

        CardData card = new CardData();
        card.bounds = new Rect(bounds);
        card.totalKm = totalKm;
        card.fuelCost = fuelCost;
        card.pricePerKm = pricePerKm;
        card.profit = profit;
        out.add(card);
    }

    private boolean validBounds(Rect b) {
        return b != null && b.width() >= dp(90) && b.height() >= dp(20);
    }

    private Double extractPrice(String text) {
        if (text == null) return null;
        Matcher m = PRICE_PATTERN.matcher(clean(text));
        if (!m.find()) return null;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        return number(g);
    }

    private List<Double> extractKms(String text) {
        List<Double> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = KM_PATTERN.matcher(clean(text));
        while (m.find()) {
            Double v = number(m.group(1));
            if (v != null) out.add(v);
        }
        return out;
    }

    private Extracted extract(AccessibilityNodeInfo node) {
        Extracted out = new Extracted();
        extractRecursive(node, out);
        return out;
    }

    private void extractRecursive(AccessibilityNodeInfo node, Extracted out) {
        if (node == null) return;
        String text = nodeText(node);
        if (!text.trim().isEmpty()) out.allText.append(' ').append(text);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) extractRecursive(c, out);
        }
    }

    private String nodeText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) sb.append(text);
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0 && (text == null || !desc.toString().equals(text.toString()))) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(desc);
        }
        return sb.toString();
    }

    private String clean(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').replace('\u202F', ' ').trim();
    }

    private Double number(String s) {
        try { return Double.parseDouble(clean(s).replace(',', '.')); }
        catch (Exception e) { return null; }
    }

    private int centerY(Rect r) { return r.top + r.height() / 2; }

    private boolean isOutOfCity(String text) {
        String s = clean(text).toLowerCase(new Locale("uk", "UA"));
        if (s.contains("кіровоградська область") || s.contains("кропивницький район")
                || s.contains(" область") || s.contains(" обл.")
                || s.contains("село ") || s.contains("смт ") || s.contains("селище ")) return true;
        if (s.contains("фортечний") || s.contains("подільський")) return false;
        return false;
    }

    private void addOverlay(CardData card) {
        TextView view = new TextView(this);
        view.setText(buildOverlayText(card));
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        view.setPadding(dp(9), dp(3), dp(9), dp(3));
        view.setSingleLine(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 20, 24, 29));
        bg.setCornerRadius(dp(7));
        view.setBackground(bg);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Math.max(dp(210), Math.min(card.bounds.width() - dp(12), dp(430)));
        if (maxWidth <= dp(150)) maxWidth = Math.min(dp(360), screenWidth - dp(16));
        int width = maxWidth;
        int height = dp(50);

        int x = Math.max(0, card.bounds.left + dp(6));
        if (x + width > screenWidth) x = Math.max(0, screenWidth - width - dp(6));

        // Put the result near the bottom of the detected order card.
        int y = Math.max(card.bounds.top + dp(48), card.bounds.bottom - height - dp(6));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height, TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;

        try {
            windowManager.addView(view, lp);
            overlays.add(view);
        } catch (Throwable ignored) { }
    }

    private String buildOverlayText(CardData c) {
        return "Σ " + fmt(c.totalKm, 1) + " км   •   ⛽ " + fmt(c.fuelCost, 0) + " грн   •   "
                + fmt(c.pricePerKm, 2) + " грн/км\n"
                + "ПРИБУТОК: " + fmt(c.profit, 0) + " грн";
    }

    private String fmt(double value, int decimals) {
        String pattern = decimals == 0 ? "%.0f" : (decimals == 1 ? "%.1f" : "%.2f");
        return String.format(Locale.getDefault(), pattern, value);
    }

    private void clearOverlays() {
        if (windowManager == null) { overlays.clear(); return; }
        for (TextView view : overlays) {
            try { windowManager.removeViewImmediate(view); }
            catch (Throwable ignored) { }
        }
        overlays.clear();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class RawNode {
        final AccessibilityNodeInfo node;
        final String text;
        final Rect bounds;
        Double fallbackFare;
        RawNode(AccessibilityNodeInfo node, String text, Rect bounds) {
            this.node = node; this.text = text; this.bounds = bounds;
        }
    }

    private static class KmHit {
        final double value;
        final RawNode node;
        KmHit(double value, RawNode node) { this.value = value; this.node = node; }
    }

    private static class Extracted { final StringBuilder allText = new StringBuilder(); }

    private static class CardData {
        Rect bounds;
        double totalKm;
        double fuelCost;
        double pricePerKm;
        double profit;
    }
}
