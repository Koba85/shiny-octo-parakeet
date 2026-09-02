package ua.kobets.taxi838overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "calc";
    public static final String ENABLED = "enabled";
    public static final String DETAILED = "detailed";
    public static final String FUEL_PRICE = "fuel_price";
    public static final String CITY_CONSUMPTION = "city_consumption";
    public static final String SERVICE_COMMISSION = "service_commission";
    public static final String TRANSFER_COMMISSION = "transfer_commission";
    public static final String AMORTIZATION_KM = "amortization_km";
    public static final String FIXED_ORDER_COST = "fixed_order_cost";
    public static final String MIN_PROFIT = "min_profit";
    public static final String MIN_PROFIT_KM = "min_profit_km";

    private static final int REQ_CAPTURE = 8380;
    private static final int REQ_NOTIFICATIONS = 8381;

    private SharedPreferences prefs;
    private TextView statusText;
    private CheckBox enabledBox;
    private CheckBox detailedBox;
    private EditText fuelPrice;
    private EditText cityConsumption;
    private EditText serviceCommission;
    private EditText transferCommission;
    private EditText amortizationKm;
    private EditText fixedOrderCost;
    private EditText minProfit;
    private EditText minProfitKm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("838 • миттєвий розрахунок", 24, true));
        TextView intro = text("Без спецможливостей: локально зчитує ціну, подачу та маршрут з екрана 838 і накладає розрахунок поверх заявки.", 14, false);
        intro.setPadding(0, dp(6), 0, dp(10));
        root.addView(intro);

        statusText = text("", 15, true);
        root.addView(statusText);

        Button overlayPermission = button("1. Дозволити показ поверх програм");
        overlayPermission.setOnClickListener(v -> openOverlayPermission());
        root.addView(overlayPermission);

        Button start = button("2. Запустити аналіз 838");
        start.setOnClickListener(v -> startScanner());
        root.addView(start);

        Button stop = button("Зупинити аналіз");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            Toast.makeText(this, "Аналіз зупинено", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        enabledBox = new CheckBox(this);
        enabledBox.setText("Показувати розрахунок");
        enabledBox.setTextSize(15);
        enabledBox.setChecked(prefs.getBoolean(ENABLED, true));
        root.addView(enabledBox);

        detailedBox = new CheckBox(this);
        detailedBox.setText("Детально: паливо / комісії / амортизація");
        detailedBox.setTextSize(15);
        detailedBox.setChecked(prefs.getBoolean(DETAILED, false));
        root.addView(detailedBox);

        TextView section = text("Змінні розрахунку", 19, true);
        section.setPadding(0, dp(14), 0, dp(4));
        root.addView(section);

        fuelPrice = numberField("Ціна бензину, грн/л", prefs.getString(FUEL_PRICE, "80"));
        cityConsumption = numberField("Розхід місто, л/100 км", prefs.getString(CITY_CONSUMPTION, "0"));
        serviceCommission = numberField("Комісія 838, %", prefs.getString(SERVICE_COMMISSION, "0"));
        transferCommission = numberField("Комісія переказу/виводу, %", prefs.getString(TRANSFER_COMMISSION, "0"));
        amortizationKm = numberField("Амортизація, грн/км", prefs.getString(AMORTIZATION_KM, "0"));
        fixedOrderCost = numberField("Інші витрати, грн/замовлення", prefs.getString(FIXED_ORDER_COST, "0"));
        minProfit = numberField("Мінімальний чистий прибуток, грн", prefs.getString(MIN_PROFIT, "0"));
        minProfitKm = numberField("Мінімальний чистий прибуток, грн/км", prefs.getString(MIN_PROFIT_KM, "0"));

        root.addView(fuelPrice);
        root.addView(cityConsumption);
        root.addView(serviceCommission);
        root.addView(transferCommission);
        root.addView(amortizationKm);
        root.addView(fixedOrderCost);
        root.addView(minProfit);
        root.addView(minProfitKm);

        Button save = button("Зберегти налаштування");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        TextView help = text("Після натискання «Запустити» Android один раз на запуск покаже стандартне вікно дозволу на захоплення екрана. Далі відкрий 838 — розрахунок оновлюється автоматично.", 13, false);
        help.setPadding(0, dp(12), 0, 0);
        help.setTextColor(Color.DKGRAY);
        root.addView(help);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void startScanner() {
        saveSettings();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Спочатку дозволь показ поверх інших програм", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Дозвіл уже надано", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Захоплення екрана не дозволено", Toast.LENGTH_LONG).show();
            return;
        }
        Intent service = new Intent(this, CaptureService.class);
        service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        Toast.makeText(this, "Аналіз запущено. Відкрий 838.", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean(ENABLED, enabledBox.isChecked())
                .putBoolean(DETAILED, detailedBox.isChecked())
                .putString(FUEL_PRICE, clean(fuelPrice))
                .putString(CITY_CONSUMPTION, clean(cityConsumption))
                .putString(SERVICE_COMMISSION, clean(serviceCommission))
                .putString(TRANSFER_COMMISSION, clean(transferCommission))
                .putString(AMORTIZATION_KM, clean(amortizationKm))
                .putString(FIXED_ORDER_COST, clean(fixedOrderCost))
                .putString(MIN_PROFIT, clean(minProfit))
                .putString(MIN_PROFIT_KM, clean(minProfitKm))
                .apply();
    }

    private String clean(EditText e) {
        String s = e.getText().toString().trim().replace(',', '.');
        return TextUtils.isEmpty(s) ? "0" : s;
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        statusText.setText(overlay
                ? "Показ поверх програм: ДОЗВОЛЕНО"
                : "Показ поверх програм: НЕ ДОЗВОЛЕНО");
        statusText.setTextColor(overlay ? Color.rgb(0, 120, 70) : Color.rgb(180, 60, 30));
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(25, 25, 25));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(7), 0, dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private EditText numberField(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        e.setTextSize(16);
        return e;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
