package ua.kobets.taxi838overlay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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
    public static final String TARGET_PACKAGE = "target_package";

    private SharedPreferences prefs;
    private TextView statusText;
    private TextView targetText;
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
        root.setPadding(dp(18), dp(16), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("838 • миттєвий розрахунок", 24, true);
        root.addView(title);

        TextView intro = text("Розрахунок з’являється поверх кожної видимої заявки 838. Подача + маршрут враховуються як повний пробіг.", 14, false);
        intro.setPadding(0, dp(6), 0, dp(10));
        root.addView(intro);

        statusText = text("", 15, true);
        root.addView(statusText);

        Button accessButton = button("Увімкнути / перевірити спецможливості");
        accessButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessButton);

        enabledBox = new CheckBox(this);
        enabledBox.setText("Показувати розрахунок поверх 838");
        enabledBox.setTextSize(15);
        enabledBox.setChecked(prefs.getBoolean(ENABLED, true));
        root.addView(enabledBox);

        detailedBox = new CheckBox(this);
        detailedBox.setText("Детально: паливо / комісії / амортизація");
        detailedBox.setTextSize(15);
        detailedBox.setChecked(prefs.getBoolean(DETAILED, true));
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

        Button saveButton = button("Зберегти налаштування");
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton);

        TextView bindSection = text("Прив’язка до 838", 19, true);
        bindSection.setPadding(0, dp(16), 0, dp(4));
        root.addView(bindSection);

        targetText = text("", 14, false);
        root.addView(targetText);

        Button reset = button("Скинути автовизначення 838");
        reset.setOnClickListener(v -> {
            prefs.edit().remove(TARGET_PACKAGE).apply();
            refreshStatus();
            Toast.makeText(this, "Прив’язку скинуто. Тепер відкрий список заявок 838.", Toast.LENGTH_LONG).show();
        });
        root.addView(reset);

        TextView help = text("Перший запуск: 1) увімкни сервіс у спецможливостях; 2) повернись у 838; 3) відкрий список, де видно щонайменше дві заявки з ціною, подачею та кілометражем маршруту. Додаток автоматично запам’ятає пакет 838.", 13, false);
        help.setPadding(0, dp(10), 0, 0);
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

    private void save() {
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
        Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private String clean(EditText e) {
        String s = e.getText().toString().trim().replace(',', '.');
        return TextUtils.isEmpty(s) ? "0" : s;
    }

    private void refreshStatus() {
        if (statusText == null || targetText == null) return;
        boolean enabled = isServiceEnabled();
        statusText.setText(enabled ? "Сервіс спецможливостей: УВІМКНЕНО" : "Сервіс спецможливостей: ВИМКНЕНО");
        statusText.setTextColor(enabled ? Color.rgb(0, 120, 70) : Color.rgb(180, 60, 30));

        String target = prefs.getString(TARGET_PACKAGE, "");
        if (target == null || target.isEmpty()) {
            targetText.setText("Пакет 838 ще не визначено. Відкрий екран заявок 838 після ввімкнення сервісу.");
        } else {
            targetText.setText("Визначений пакет 838: " + target);
        }
    }

    private boolean isServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        ComponentName component = new ComponentName(this, OrderAccessibilityService.class);
        String full = component.flattenToString();
        String shortName = component.flattenToShortString();
        for (String item : enabledServices.split(":")) {
            if (item.equalsIgnoreCase(full) || item.equalsIgnoreCase(shortName)) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
