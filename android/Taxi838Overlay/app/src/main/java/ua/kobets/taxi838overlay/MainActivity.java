package ua.kobets.taxi838overlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
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
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(text("838 • розрахунок заявки", 25, true));
        TextView intro = text(
                "З 838 автоматично зчитуються: ЦІНА ЗАЯВКИ, КМ ДО ПОДАЧІ та КМ МАРШРУТУ. Нижче вводяться тільки твої витрати.",
                15, false);
        intro.setPadding(0, dp(6), 0, dp(12));
        root.addView(intro);

        statusText = text("", 15, true);
        statusText.setPadding(0, 0, 0, dp(6));
        root.addView(statusText);

        Button overlayPermission = button("1. ДОЗВОЛИТИ ПОКАЗ ПОВЕРХ 838");
        overlayPermission.setOnClickListener(v -> openOverlayPermission());
        root.addView(overlayPermission);

        Button start = button("2. ЗАПУСТИТИ РОЗРАХУНОК 838");
        start.setOnClickListener(v -> startScanner());
        root.addView(start);

        Button stop = button("ЗУПИНИТИ РОЗРАХУНОК");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            Toast.makeText(this, "Розрахунок зупинено", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        enabledBox = new CheckBox(this);
        enabledBox.setText("Показувати блок розрахунку на заявках");
        enabledBox.setTextSize(16);
        enabledBox.setChecked(prefs.getBoolean(ENABLED, true));
        root.addView(enabledBox);

        detailedBox = new CheckBox(this);
        detailedBox.setText("Показувати деталізацію витрат у блоці");
        detailedBox.setTextSize(16);
        detailedBox.setChecked(prefs.getBoolean(DETAILED, true));
        root.addView(detailedBox);

        TextView section = text("ТВОЇ ЗМІННІ ДЛЯ РОЗРАХУНКУ", 20, true);
        section.setPadding(0, dp(14), 0, dp(6));
        root.addView(section);

        fuelPrice = addNumberField(root,
                "1. Ціна бензину",
                "грн за 1 літр",
                prefs.getString(FUEL_PRICE, "80"));

        cityConsumption = addNumberField(root,
                "2. Розхід палива по місту",
                "літрів на 100 км. Без цього паливо порахувати неможливо",
                prefs.getString(CITY_CONSUMPTION, "0"));

        serviceCommission = addNumberField(root,
                "3. Комісія служби 838",
                "% від вартості заявки",
                prefs.getString(SERVICE_COMMISSION, "0"));

        transferCommission = addNumberField(root,
                "4. Комісія за виведення / переказ",
                "% від вартості заявки. Якщо немає — залиш 0",
                prefs.getString(TRANSFER_COMMISSION, "0"));

        amortizationKm = addNumberField(root,
                "5. Амортизація автомобіля",
                "грн на 1 км повного пробігу",
                prefs.getString(AMORTIZATION_KM, "0"));

        fixedOrderCost = addNumberField(root,
                "6. Фіксовані витрати на одну заявку",
                "грн на замовлення. Якщо немає — залиш 0",
                prefs.getString(FIXED_ORDER_COST, "0"));

        minProfit = addNumberField(root,
                "7. Мінімальний бажаний чистий прибуток",
                "грн із заявки. Використовується для оцінки вигідності",
                prefs.getString(MIN_PROFIT, "0"));

        minProfitKm = addNumberField(root,
                "8. Мінімальний бажаний чистий прибуток на 1 км",
                "грн/км. Використовується для оцінки вигідності",
                prefs.getString(MIN_PROFIT_KM, "0"));

        Button save = button("ЗБЕРЕГТИ НАЛАШТУВАННЯ");
        save.setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, "Налаштування збережено", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        TextView formulaTitle = text("ЩО БУДЕ ПОКАЗАНО НА ЗАЯВЦІ", 18, true);
        formulaTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(formulaTitle);

        TextView formula = text(
                "• повний пробіг = подача + маршрут\n" +
                "• паливо, комісія 838, амортизація та загальні витрати\n" +
                "• чистий прибуток у грн\n" +
                "• чистий прибуток у грн/км",
                14, false);
        formula.setLineSpacing(0, 1.12f);
        root.addView(formula);

        TextView help = text(
                "Після «Запустити» Android покаже штатний дозвіл на захоплення екрана. Далі відкрий список заявок 838. Розпізнавання виконується локально на телефоні.",
                13, false);
        help.setPadding(0, dp(14), 0, 0);
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

    private EditText addNumberField(LinearLayout root, String label, String note, String value) {
        TextView title = text(label, 16, true);
        title.setPadding(0, dp(10), 0, 0);
        root.addView(title);

        TextView sub = text(note, 12, false);
        sub.setTextColor(Color.rgb(85, 85, 85));
        sub.setPadding(0, dp(1), 0, 0);
        root.addView(sub);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(value);
        field.setTextSize(20);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48));
        lp.setMargins(0, dp(3), 0, dp(2));
        field.setLayoutParams(lp);
        root.addView(field);
        return field;
    }

    private void startScanner() {
        saveSettings();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Спочатку дозволь показ поверх інших програм", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Дозвіл уже надано", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
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
        Toast.makeText(this, "Розрахунок запущено. Відкрий 838.", Toast.LENGTH_LONG).show();
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
                ? "Показ поверх 838: ДОЗВОЛЕНО"
                : "Показ поверх 838: НЕ ДОЗВОЛЕНО");
        statusText.setTextColor(overlay ? Color.rgb(0, 125, 75) : Color.rgb(190, 55, 35));
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(25, 25, 25));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
