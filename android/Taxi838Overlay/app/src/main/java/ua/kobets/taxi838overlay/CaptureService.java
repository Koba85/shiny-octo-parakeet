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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CaptureService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL = "capture838";
    private static final int NOTIFICATION_ID = 8382;
    private static final long SCAN_PAUSE_MS = 650;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final Runnable scanTick = this::scanTick;

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

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, main);

        createInitialCapture();
        main.removeCallbacks(scanTick);
        main.postDelayed(scanTick, 450);
        return START_NOT_STICKY;
    }

    private void createInitialCapture() {
        int[] size = currentScreenSize();
        captureWidth = size[0];
        captureHeight = size[1];
        densityDpi = getResources().getDisplayMetrics().densityDpi;

        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
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
        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay.resize(captureWidth, captureHeight, densityDpi);
        virtualDisplay.setSurface(reader.getSurface());
        if (old != null) old.close();
        overlay.setOrders(Collections.emptyList());
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
            overlay.setOrders(Collections.emptyList());
            overlay.setVisibility(View.INVISIBLE);
            main.postDelayed(scanTick, 900);
            return;
        }
        if (ocrBusy.get()) {
            main.postDelayed(scanTick, 250);
            return;
        }

        overlay.setVisibility(View.INVISIBLE);
        main.postDelayed(this::acquireAndRecognize, 80);
    }

    private void acquireAndRecognize() {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                main.postDelayed(scanTick, 220);
                return;
            }
            Bitmap bitmap = imageToBitmap(image);
            image.close();
            image = null;
            if (bitmap == null) {
                main.postDelayed(scanTick, 450);
                return;
            }

            ocrBusy.set(true);
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(result -> {
                        List<Order> orders = Parser.parse(
                                result,
                                captureWidth,
                                captureHeight,
                                getResources().getDisplayMetrics().density);
                        overlay.setOrders(orders);
                        overlay.setVisibility(orders.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                    })
                    .addOnFailureListener(e -> {
                        overlay.setOrders(Collections.emptyList());
                        overlay.setVisibility(View.INVISIBLE);
                    })
                    .addOnCompleteListener(task -> {
                        bitmap.recycle();
                        ocrBusy.set(false);
                        main.postDelayed(scanTick, SCAN_PAUSE_MS);
                    });
        } catch (Throwable t) {
            if (image != null) image.close();
            ocrBusy.set(false);
            overlay.setVisibility(View.INVISIBLE);
            main.postDelayed(scanTick, 700);
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int paddedWidth = captureWidth + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(paddedWidth, captureHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == captureWidth) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
        padded.recycle();
        return cropped;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "838 Profit Overlay",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Фоновий аналіз заявок 838");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("838 Profit Overlay")
                .setContentText("Аналіз заявок активний")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
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
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
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

        double totalKm() { return pickupKm + tripKm; }
    }

    private static final class Result {
        double totalKm;
        double fuelCost;
        double serviceCommission;
        double transferCommission;
        double amortization;
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
        r.totalCost = r.fuelCost + r.serviceCommission + r.transferCommission + r.amortization + fixed;
        r.netProfit = order.fare - r.totalCost;
        r.netPerKm = r.totalKm > 0.001 ? r.netProfit / r.totalKm : 0.0;
        r.incomplete = fuelPrice <= 0 || consumption <= 0;
        r.acceptable = !r.incomplete && r.netProfit >= minProfit && r.netPerKm >= minProfitKm;
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

        float cx() { return box.exactCenterX(); }
        float cy() { return box.exactCenterY(); }
    }

    private static final class Parser {
        private static final Pattern NUMBER = Pattern.compile("(?<!\\d)(\\d{1,5}(?:[.,]\\d{1,2})?)(?!\\d)");

        static List<Order> parse(Text result, int screenW, int screenH, float density) {
            List<Token> tokens = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    for (Text.Element element : line.getElements()) {
                        Rect b = element.getBoundingBox();
                        if (b == null) continue;
                        Matcher m = NUMBER.matcher(element.getText());
                        while (m.find()) {
                            try {
                                double value = Double.parseDouble(m.group(1).replace(',', '.'));
                                tokens.add(new Token(element.getText(), value, b));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            float yTolerance = Math.max(26f * density, screenH * 0.032f);
            List<Order> candidates = new ArrayList<>();

            for (Token fare : tokens) {
                if (!isFare(fare, screenW)) continue;
                List<Token> kms = new ArrayList<>();
                for (Token t : tokens) {
                    if (t == fare) continue;
                    if (t.cx() <= screenW * 0.28f) continue;
                    if (Math.abs(t.cy() - fare.cy()) > yTolerance) continue;
                    if (t.value < 0 || t.value >= 500) continue;
                    kms.add(t);
                }
                kms.sort(Comparator.comparingDouble(Token::cx));
                if (kms.size() < 2) continue;

                Token pickup = kms.get(0);
                Token trip = kms.get(1);
                if (pickup.cx() > screenW * 0.72f) continue;
                if (trip.cx() < screenW * 0.48f || trip.cx() > screenW * 0.92f) continue;
                if (pickup.value >= 200 || trip.value <= 0) continue;

                int top = Math.max(0, Math.min(fare.box.top, Math.min(pickup.box.top, trip.box.top)) - Math.round(12f * density));
                int bottom = Math.min(screenH, top + Math.round(150f * density));
                candidates.add(new Order(fare.value, pickup.value, trip.value, new Rect(0, top, screenW, bottom)));
            }

            candidates.sort(Comparator.comparingInt(o -> o.bounds.top));
            List<Order> out = new ArrayList<>();
            int lastTop = -100000;
            for (int i = 0; i < candidates.size(); i++) {
                Order o = candidates.get(i);
                if (Math.abs(o.bounds.top - lastTop) < 35f * density) continue;
                int nextTop = i + 1 < candidates.size() ? candidates.get(i + 1).bounds.top : o.bounds.bottom;
                int minimumBottom = o.bounds.top + Math.round(88f * density);
                int bottom = Math.max(minimumBottom, Math.min(o.bounds.bottom, nextTop - Math.round(4f * density)));
                out.add(new Order(o.fare, o.pickupKm, o.tripKm, new Rect(0, o.bounds.top, screenW, bottom)));
                lastTop = o.bounds.top;
            }
            return out;
        }

        private static boolean isFare(Token t, int screenW) {
            if (t.cx() > screenW * 0.30f) return false;
            if (t.value < 20 || t.value > 10000) return false;
            String s = t.raw.toLowerCase(Locale.ROOT);
            if (s.contains("₴") || s.contains("грн") || s.contains("uah")) return true;
            return Math.abs(t.value - Math.rint(t.value)) < 0.001 && t.value >= 40;
        }
    }

    private static final class ProfitOverlay extends View {
        private final SharedPreferences prefs;
        private final List<Order> orders = new ArrayList<>();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ProfitOverlay(Service context, SharedPreferences prefs) {
            super(context);
            this.prefs = prefs;
            density = getResources().getDisplayMetrics().density;
            text.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
            border.setStyle(Paint.Style.STROKE);
        }

        void setOrders(List<Order> newOrders) {
            orders.clear();
            if (newOrders != null) orders.addAll(newOrders);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean detailed = prefs.getBoolean(MainActivity.DETAILED, false);

            for (Order order : orders) {
                Result r = calculate(order, prefs);
                Rect card = order.bounds;
                if (card.height() < 45f * density) continue;

                float margin = 5f * density;
                float boxWidth = Math.min(card.width() * 0.58f, 305f * density);
                float boxHeight = (detailed ? 64f : 46f) * density;
                float left = Math.max(card.left + margin, card.right - boxWidth - margin);
                float top = Math.min(card.bottom - boxHeight - margin, card.top + 35f * density);
                if (top < card.top + margin) top = card.top + margin;
                RectF box = new RectF(left, top, card.right - margin, Math.min(card.bottom - margin, top + boxHeight));

                int accent = r.incomplete
                        ? Color.rgb(90, 95, 105)
                        : (r.acceptable ? Color.rgb(12, 135, 73) : Color.rgb(185, 44, 44));
                background.setColor(Color.argb(226, 24, 29, 34));
                border.setColor(accent);
                border.setStrokeWidth(2.2f * density);
                canvas.drawRoundRect(box, 10f * density, 10f * density, background);
                canvas.drawRoundRect(box, 10f * density, 10f * density, border);

                text.setColor(Color.WHITE);
                float pad = 8f * density;
                float y = box.top + 16f * density;
                text.setTextSize(11.5f * density);

                if (r.incomplete) {
                    canvas.drawText(String.format(Locale.US, "%.2f км • вкажи розхід", r.totalKm), box.left + pad, y, text);
                    y += 17f * density;
                    text.setTextSize(10.5f * density);
                    canvas.drawText(String.format(Locale.US, "₴%.0f • %.2f + %.2f км", order.fare, order.pickupKm, order.tripKm), box.left + pad, y, text);
                } else {
                    canvas.drawText(String.format(Locale.US, "ЧИСТО %.0f₴ • %.1f₴/км", r.netProfit, r.netPerKm), box.left + pad, y, text);
                    y += 17f * density;
                    text.setTextSize(10.2f * density);
                    canvas.drawText(String.format(Locale.US, "Σ %.2f км • витрати %.0f₴", r.totalKm, r.totalCost), box.left + pad, y, text);
                    if (detailed && box.height() > 55f * density) {
                        y += 15f * density;
                        text.setTextSize(9.5f * density);
                        canvas.drawText(String.format(Locale.US, "пал %.0f • ком %.0f • аморт %.0f", r.fuelCost, r.serviceCommission + r.transferCommission, r.amortization), box.left + pad, y, text);
                    }
                }
            }
        }
    }
}
