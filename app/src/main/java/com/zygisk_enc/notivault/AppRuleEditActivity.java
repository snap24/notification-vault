package com.zygisk_enc.notivault;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.zygisk_enc.notivault.database.AppDatabase;
import com.zygisk_enc.notivault.database.AppRuleEntity;
import com.zygisk_enc.notivault.util.AppExecutor;

public class AppRuleEditActivity extends BaseActivity {

    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_APP_NAME = "extra_app_name";

    private String packageName;
    private String appName;

    private MaterialSwitch switchEnableRule;
    private TextInputLayout tilBlockKeywords;
    private TextInputEditText tietBlockKeywords;
    private TextInputLayout tilAllowKeywords;
    private TextInputEditText tietAllowKeywords;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_rule_edit);

        packageName = getIntent().getStringExtra(EXTRA_PACKAGE_NAME);
        appName = getIntent().getStringExtra(EXTRA_APP_NAME);

        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar_edit_rule);
        toolbar.setTitle(appName != null ? getString(R.string.rule_title_format, appName) : getString(R.string.edit_rule_title));
        toolbar.setNavigationOnClickListener(v -> finish());

        ImageView ivIcon = findViewById(R.id.iv_app_icon);
        TextView tvAppName = findViewById(R.id.tv_app_name);
        TextView tvPackageName = findViewById(R.id.tv_package_name);

        tvAppName.setText(appName != null ? appName : packageName);
        tvPackageName.setText(packageName);

        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            if (appName == null || appName.isEmpty()) {
                appName = pm.getApplicationLabel(info).toString();
                tvAppName.setText(appName);
                toolbar.setTitle(getString(R.string.rule_title_format, appName));
            }
            Drawable icon = pm.getApplicationIcon(info);
            ivIcon.setImageDrawable(icon);
        } catch (Exception ignored) {
            ivIcon.setImageResource(R.drawable.ic_code);
        }

        switchEnableRule = findViewById(R.id.switch_enable_rule);
        tilBlockKeywords = findViewById(R.id.til_block_keywords);
        tietBlockKeywords = findViewById(R.id.tiet_block_keywords);
        tilAllowKeywords = findViewById(R.id.til_allow_keywords);
        tietAllowKeywords = findViewById(R.id.tiet_allow_keywords);

        MaterialButton btnSave = findViewById(R.id.btn_save_rule);
        MaterialButton btnDelete = findViewById(R.id.btn_delete_rule);

        switchEnableRule.setOnCheckedChangeListener((buttonView, isChecked) -> {
            tilBlockKeywords.setEnabled(isChecked);
            tietBlockKeywords.setEnabled(isChecked);
            tilAllowKeywords.setEnabled(isChecked);
            tietAllowKeywords.setEnabled(isChecked);
        });

        loadExistingRule();

        btnSave.setOnClickListener(v -> saveRule());
        btnDelete.setOnClickListener(v -> deleteRule());
    }

    private void loadExistingRule() {
        AppExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            AppRuleEntity existingRule = db.appRuleDao().getRuleSync(packageName);

            runOnUiThread(() -> {
                if (existingRule != null) {
                    switchEnableRule.setChecked(existingRule.isRuleEnabled);
                    tietBlockKeywords.setText(existingRule.blockKeywords);
                    tietAllowKeywords.setText(existingRule.allowKeywords);

                    tilBlockKeywords.setEnabled(existingRule.isRuleEnabled);
                    tietBlockKeywords.setEnabled(existingRule.isRuleEnabled);
                    tilAllowKeywords.setEnabled(existingRule.isRuleEnabled);
                    tietAllowKeywords.setEnabled(existingRule.isRuleEnabled);
                } else {
                    switchEnableRule.setChecked(false);
                    tilBlockKeywords.setEnabled(false);
                    tietBlockKeywords.setEnabled(false);
                    tilAllowKeywords.setEnabled(false);
                    tietAllowKeywords.setEnabled(false);
                }
            });
        });
    }

    private void saveRule() {
        boolean isRuleEnabled = switchEnableRule.isChecked();
        String blockKeywords = tietBlockKeywords.getText() != null ? tietBlockKeywords.getText().toString().trim() : "";
        String allowKeywords = tietAllowKeywords.getText() != null ? tietAllowKeywords.getText().toString().trim() : "";

        AppExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            AppRuleEntity existing = db.appRuleDao().getRuleSync(packageName);
            boolean blockAll = existing != null && existing.blockAll;

            AppRuleEntity newRule = new AppRuleEntity(packageName, appName, blockAll, blockKeywords, allowKeywords, isRuleEnabled);
            db.appRuleDao().insert(newRule);

            runOnUiThread(() -> {
                Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private void deleteRule() {
        AppExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            db.appRuleDao().deleteByPackage(packageName);

            runOnUiThread(() -> {
                Toast.makeText(this, R.string.rule_deleted, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }
}
