package ua.koba.taxi838overlay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "calc";
    private EditText fuelPrice;
    private EditText cityConsumption;
    private EditText highwayConsumption;
    private EditText amortizationPercent;
    private TextView serviceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("838 Аналізатор");

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("838 · РОЗРАХУНОК ЗАЯВОК");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(24, 28, 33));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(18));
        root.addView(title, matchWrap());

        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(15);
        serviceStatus.setTextColor(Color.DKGRAY);
        serviceStatus.setPadding(0, 0, 0, dp(12));
        root.addView(serviceStatus, matchWrap());

        fuelPrice = addField(root, "Ціна пального, грн/л", p.getFloat("fuel_price", 80.0f));
        cityConsumption = addField(root, "Розхід місто, л/100 км", p.getFloat("city_cons", 8.5f));
        highwayConsumption = addField(root, "Розхід за містом, л/100 км", p.getFloat("highway_cons", 8.5f));
        amortizationPercent = addField(root, "Амортизація, % від вартості заявки", p.getFloat("amort_pct", 0.0f));

        Button save = new Button(this);
        save.setText("ЗБЕРЕГТИ ПАРАМЕТРИ");
        save.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveLp = matchWrap();
        saveLp.topMargin = dp(12);
        root.addView(save, saveLp);

        Button accessibility = new Button(this);
        accessibility.setText("УВІМКНУТИ / ПЕРЕВІРИТИ ДОСТУП");
        accessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        LinearLayout.LayoutParams accessLp = matchWrap();
        accessLp.topMargin = dp(8);
        root.addView(accessibility, accessLp);

        TextView note = new TextView(this);
        note.setText("Після ввімкнення сервісу відкрийте 838. Дані з'являються поверх карток заявок автоматично.");
        note.setTextSize(13);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note, matchWrap());

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serviceStatus != null) {
            serviceStatus.setText(isAccessibilityEnabled() ? "Сервіс: УВІМКНЕНО" : "Сервіс: ВИМКНЕНО");
        }
    }

    private EditText addField(LinearLayout root, String label, float value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(Color.rgb(45, 50, 56));
        tv.setPadding(0, dp(10), 0, dp(4));
        root.addView(tv, matchWrap());

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(trimNumber(value));
        edit.setTextSize(18);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        edit.setSelectAllOnFocus(true);
        root.addView(edit, matchWrap());
        return edit;
    }

    private void saveSettings() {
        try {
            float fuel = parse(fuelPrice);
            float city = parse(cityConsumption);
            float highway = parse(highwayConsumption);
            float amort = parse(amortizationPercent);

            if (fuel <= 0 || city <= 0 || highway <= 0 || amort < 0 || amort > 100) {
                throw new IllegalArgumentException();
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat("fuel_price", fuel)
                    .putFloat("city_cons", city)
                    .putFloat("highway_cons", highway)
                    .putFloat("amort_pct", amort)
                    .apply();
            Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Перевірте введені значення", Toast.LENGTH_SHORT).show();
        }
    }

    private float parse(EditText e) {
        return Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
    }

    private String trimNumber(float v) {
        if (Math.abs(v - Math.round(v)) < 0.0001f) return String.valueOf(Math.round(v));
        return String.valueOf(v).replace('.', ',');
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String needle = getPackageName().toLowerCase() + "/" + OrderAccessibilityService.class.getName().toLowerCase();
        String compact = enabled.toLowerCase();
        return compact.contains(getPackageName().toLowerCase()) && compact.contains("orderaccessibilityservice");
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
