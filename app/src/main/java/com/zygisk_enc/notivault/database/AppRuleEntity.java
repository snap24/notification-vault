package com.zygisk_enc.notivault.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_rules")
public class AppRuleEntity {
    @PrimaryKey
    @NonNull
    public String packageName;
    
    public String appName;
    public boolean blockAll;
    public String blockKeywords;
    public String allowKeywords;
    public boolean isRuleEnabled;

    public AppRuleEntity(@NonNull String packageName, String appName, boolean blockAll, String blockKeywords, String allowKeywords, boolean isRuleEnabled) {
        this.packageName = packageName;
        this.appName = appName;
        this.blockAll = blockAll;
        this.blockKeywords = blockKeywords != null ? blockKeywords : "";
        this.allowKeywords = allowKeywords != null ? allowKeywords : "";
        this.isRuleEnabled = isRuleEnabled;
    }
}
