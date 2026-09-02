package ua.kobets.taxi838overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CaptureService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "capture838_v3";
    private static final int NOTIFICATION_ID = 8383;
    private static final long SCAN_PAUSE_MS = 700;
    private static final long KEEP_LAST_GOOD_MS = 2600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final Runnable scanTick = this::scanTick;
    private final List<Order> stableOrders = new ArrayList<>();

    private SharedPreferences prefs;
    private WindowManager wm;
    private ProfitOverlay overlay;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;
    private long lastGoodAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new ProfitOverlay(this, prefs);
        overlay.setVisibility(View.INVISIBLE);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(overlay, lp);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, main);

        createInitialCapture();
        main.removeCallbacks(scanTick);
        main.postDelayed(scanTick, 350);
        return START_NOT_STICKY;
    }

    private void createInitialCapture() {
        int[] size = currentScreenSize();
        captureWidth = size[0];
        captureHeight = size[1];
        densityDpi = getResources().getDisplayMetrics().densityDpi;

        reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2);
        virtualDisplay = projection.createVirtualDisplay(
                "838ProfitCapture",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                main);
    }

    private void resizeCaptureIfNeeded() {
        if (virtualDisplay == null || projection == null) return;
        int[] size = currentScreenSize();
        if (size[0] == captureWidth && size[1] == captureHeight) return;

        ImageReader old = reader;
        captureWidth = size[0];
        captureHeight = size[1];
        reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2);
        virtualDisplay.resize(captureWidth, captureHeight, densityDpi);
        virtualDisplay.setSurface(reader.getSurface());
        if (old != null) old.close();

        stableOrders.clear();
        lastGoodAt = 0L;
        overlay.setOrders(stableOrders);
        overlay.setVisibility(View.INVISIBLE);
    }

    private int[] currentScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{Math.max(1, b.width()), Math.max(1, b.height())};
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{Math.max(1, dm.widthPixels), Math.max(1, dm.heightPixels)};
    }

    private void scanTick() {
        if (reader == null || overlay == null) return;
        resizeCaptureIfNeeded();

        if (!prefs.getBoolean(MainActivity.ENABLED, true)) {
            stableOrders.clear();
            overlay.setOrders(stableOrders);
            overlay.setVisibility(View.INVISIBLE);
            main.postDelayed(scanTick, 900);
            return;
        }
        if (ocrBusy.get()) {
            main.postDelayed(scanTick, 220);
            return;
        }
        acquireAndRecognize();
    }

    private void acquireAndRecognize() {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                main.postDelayed(scanTick, 220);
                return;
            }
            Bitmap captured = imageToBitmap(image);
            image.close();
            image = null;
            if (captured == null) {
                main.postDelayed(scanTick, 400);
                return;
            }

            Bitmap ocrBitmap = prepareForOcr(captured);
            captured.recycle();
            ocrBusy.set(true);

            recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
                    .addOnSuccessListener(result -> applyOcrResult(result))
                    .addOnFailureListener(e -> keepOrClearLastGood())
                    .addOnCompleteListener(task -> {
                        ocrBitmap.recycle();
                        ocrBusy.set(false);
                        main.postDelayed(scanTick, SCAN_PAUSE_MS);
                    });
        } catch (Throwable t) {
            if (image != null) image.close();
            ocrBusy.set(false);
            keepOrClearLastGood();
            main.postDelayed(scanTick, 650);
        }
    }

    private void applyOcrResult(Text result) {
        List<Order> parsed = Parser.parse(
                result,
                captureWidth,
                captureHeight,
                getResources().getDisplayMetrics().density);

        if (!parsed.isEmpty()) {
            stableOrders.clear();
            stableOrders.addAll(parsed);
            lastGoodAt = SystemClock.elapsedRealtime();
            overlay.setOrders(stableOrders);
            overlay.setVisibility(View.VISIBLE);
        } else {
            keepOrClearLastGood();
        }
    }

    private void keepOrClearLastGood() {
        long now = SystemClock.elapsedRealtime();
        if (!stableOrders.isEmpty() && now - lastGoodAt <= KEEP_LAST_GOOD_MS) {
            overlay.setOrders(stableOrders);
            overlay.setVisibility(View.VISIBLE);
            return;
        }
        stableOrders.clear();
        overlay.setOrders(stableOrders);
        overlay.setVisibility(View.INVISIBLE);
    }

    private Bitmap prepareForOcr(Bitmap source) {
        Bitmap work = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(work);
        Paint mask = new Paint();
        mask.setColor(Color.WHITE);

        int w = work.getWidth();
        int h = work.getHeight();

        // 838 places a hryvnia icon before the fare and a person icon before pickup km.
        // Mask only those narrow icon columns so OCR does not turn them into an extra "2".
        canvas.drawRect(w * 0.028f, 0, w * 0.083f, h, mask);
        canvas.drawRect(w * 0.282f, 0, w * 0.338f, h, mask);
        return work;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int paddedWidth = captureWidth + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(
                paddedWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == captureWidth) return padded;

        Bitmap cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                captureWidth,
                captureHeight);
        padded.recycle();
        return cropped;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "838: фоновий розрахунок",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фонове локальне зчитування заявок 838");
            channel.setSound(null, null);
            channel.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("838: розрахунок активний")
                .setContentText("Зчитую заявки локально")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (overlay != null) overlay.invalidate();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (recognizer != null) recognizer.close();
        if (wm != null && overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
        boolean targetConfigured;
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
        r.totalCost = r.fuelCost
                + r.serviceCommission
                + r.transferCommission
                + r.amortization
                + r.fixedCost;
        r.netProfit = order.fare - r.totalCost;
        r.netPerKm = r.totalKm > 0.001 ? r.netProfit / r.totalKm : 0.0;
        r.incomplete = fuelPrice <= 0 || consumption <= 0;
        r.targetConfigured = minProfit > 0 || minProfitKm > 0;
        r.acceptable = !r.incomplete
                && r.netProfit >= minProfit
                && r.netPerKm >= minProfitKm;
        return r;
    }

    private static final class Token {
        final String raw;
        final double value;
        final Rect box;

        Token(String raw, double value, Rect box) {
            this.raw = raw;
            this.value = value;
            this.box = new Rect(box);
        }

        float cx() {
            return box.exactCenterX();
        }

        float cy() {
            return box.exactCenterY();
        }
    }

    private static final class RowCandidate {
        final Token fare;
        final Token pickup;
        final Token trip;
        final int top;

        RowCandidate(Token fare, Token pickup, Token trip, int top) {
            this.fare = fare;
            this.pickup = pickup;
            this.trip = trip;
            this.top = top;
        }
    }

    private static final class Parser {
        private static final Pattern NUMBER = Pattern.compile(
                "(?<!\\d)(\\d{1,5}(?:[.,]\\d{1,2})?)(?!\\d)");

        static List<Order> parse(Text result, int screenW, int screenH, float density) {
            List<Token> tokens = collectTokens(result);
            List<Token> fares = new ArrayList<>();
            List<Token> pickups = new ArrayList<>();
            List<Token> trips = new ArrayList<>();

            for (Token t : tokens) {
                float x = t.cx() / Math.max(1f, screenW);
                float y = t.cy() / Math.max(1f, screenH);
                if (y < 0.075f || y > 0.94f) continue;

                if (x >= 0.065f && x <= 0.245f && t.value >= 20 && t.value <= 5000) {
                    fares.add(t);
                } else if (x >= 0.31f && x <= 0.60f && t.value > 0 && t.value < 200) {
                    pickups.add(t);
                } else if (x >= 0.59f && x <= 0.90f && t.value > 0 && t.value < 500) {
                    trips.add(t);
                }
            }

            float yTolerance = Math.max(18f * density, screenH * 0.024f);
            List<RowCandidate> rows = new ArrayList<>();

            for (Token fare : fares) {
                Token pickup = nearestSameRow(fare, pickups, yTolerance);
                Token trip = nearestSameRow(fare, trips, yTolerance);
                if (pickup == null || trip == null) continue;

                int top = Math.max(
                        0,
                        Math.min(fare.box.top, Math.min(pickup.box.top, trip.box.top))
                                - Math.round(16f * density));
                rows.add(new RowCandidate(fare, pickup, trip, top));
            }

            rows.sort(Comparator.comparingInt(r -> r.top));
            List<RowCandidate> deduped = new ArrayList<>();
            for (RowCandidate row : rows) {
                if (!deduped.isEmpty()) {
                    RowCandidate prev = deduped.get(deduped.size() - 1);
                    if (Math.abs(row.top - prev.top) < 26f * density) {
                        continue;
                    }
                }
                deduped.add(row);
            }

            List<Order> out = new ArrayList<>();
            for (int i = 0; i < deduped.size(); i++) {
                RowCandidate row = deduped.get(i);
                int top = row.top;
                int nextTop = i + 1 < deduped.size()
                        ? deduped.get(i + 1).top
                        : screenH;
                int preferredBottom = top + Math.round(155f * density);
                int bottom = Math.min(screenH, preferredBottom);
                if (i + 1 < deduped.size()) {
                    bottom = Math.min(bottom, nextTop - Math.round(8f * density));
                }
                if (bottom <= top + Math.round(72f * density)) {
                    bottom = Math.min(screenH, top + Math.round(110f * density));
                }

                out.add(new Order(
                        row.fare.value,
                        row.pickup.value,
                        row.trip.value,
                        new Rect(0, top, screenW, bottom)));
            }
            return out;
        }

        private static List<Token> collectTokens(Text result) {
            List<Token> tokens = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    for (Text.Element element : line.getElements()) {
                        Rect box = element.getBoundingBox();
                        if (box == null) continue;
                        Matcher m = NUMBER.matcher(element.getText());
                        while (m.find()) {
                            try {
                                double value = Double.parseDouble(
                                        m.group(1).replace(',', '.'));
                                tokens.add(new Token(element.getText(), value, box));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
            return tokens;
        }

        private static Token nearestSameRow(Token base, List<Token> candidates, float tolerance) {
            Token best = null;
            float bestDy = Float.MAX_VALUE;
            for (Token t : candidates) {
                float dy = Math.abs(t.cy() - base.cy());
                if (dy <= tolerance && dy < bestDy) {
                    bestDy = dy;
                    best = t;
                }
            }
            return best;
        }
    }

    private static final class ProfitOverlay extends View {
        private final SharedPreferences prefs;
        private final List<Order> orders = new ArrayList<>();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mainText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final float scaledDensity;

        ProfitOverlay(Service context, SharedPreferences prefs) {
            super(context);
            this.prefs = prefs;
            density = getResources().getDisplayMetrics().density;
            scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            setWillNotDraw(false);
            mainText.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD));
            subText.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD));
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

                float boxWidth = Math.min(card.width() * 0.58f, 310f * density);
                float boxHeight = (detailed ? 82f : 62f) * density;
                float margin = 7f * density;

                float left = card.right - boxWidth - margin;
                float right = card.right - margin;
                float top = card.top + 48f * density;
                float bottom = top + boxHeight;

                if (bottom > card.bottom - margin) {
                    bottom = card.bottom - margin;
                    top = bottom - boxHeight;
                }
                if (top < card.top + margin) {
                    top = card.top + margin;
                    bottom = Math.min(card.bottom - margin, top + boxHeight);
                }
                if (bottom - top < 48f * density) continue;

                if (r.incomplete) {
                    background.setColor(Color.argb(242, 78, 82, 88));
                } else if (r.netProfit < 0) {
                    background.setColor(Color.argb(242, 158, 42, 42));
                } else if (r.targetConfigured && !r.acceptable) {
                    background.setColor(Color.argb(242, 175, 105, 15));
                } else {
                    background.setColor(Color.argb(242, 0, 112, 72));
                }

                RectF box = new RectF(left, top, right, bottom);
                canvas.drawRoundRect(box, 10f * density, 10f * density, background);

                float x = left + 10f * density;
                float y = top + 21f * density;
                mainText.setColor(Color.WHITE);
                subText.setColor(Color.WHITE);

                if (r.incomplete) {
                    mainText.setTextSize(14.5f * scaledDensity);
                    canvas.drawText("ВКАЖИ РОЗХІД ПАЛИВА", x, y, mainText);
                    y += 20f * density;
                    subText.setTextSize(11.5f * scaledDensity);
                    canvas.drawText("у налаштуваннях додатка", x, y, subText);
                    continue;
                }

                mainText.setTextSize(15.5f * scaledDensity);
                canvas.drawText(
                        String.format(Locale.US,
                                "ЧИСТО %.0f ₴  •  %.1f ₴/км",
                                r.netProfit,
                                r.netPerKm),
                        x,
                        y,
                        mainText);

                y += 21f * density;
                subText.setTextSize(12.0f * scaledDensity);
                canvas.drawText(
                        String.format(Locale.US,
                                "ВИТРАТИ %.0f ₴  •  Σ %.2f км",
                                r.totalCost,
                                r.totalKm),
                        x,
                        y,
                        subText);

                if (detailed && bottom - top >= 74f * density) {
                    y += 19f * density;
                    subText.setTextSize(10.8f * scaledDensity);
                    canvas.drawText(
                            String.format(Locale.US,
                                    "Паливо %.0f • Комісії %.0f • Аморт. %.0f • Інші %.0f",
                                    r.fuelCost,
                                    r.serviceCommission + r.transferCommission,
                                    r.amortization,
                                    r.fixedCost),
                            x,
                            y,
                            subText);
                }
            }
        }
    }
}
