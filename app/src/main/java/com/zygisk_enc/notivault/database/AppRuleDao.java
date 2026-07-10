package com.zygisk_enc.notivault.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AppRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AppRuleEntity rule);

    @Delete
    void delete(AppRuleEntity rule);

    @Query("DELETE FROM app_rules WHERE packageName = :packageName")
    void deleteByPackage(String packageName);

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    AppRuleEntity getRuleSync(String packageName);

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    LiveData<AppRuleEntity> getRule(String packageName);

    @Query("SELECT * FROM app_rules ORDER BY appName ASC")
    LiveData<List<AppRuleEntity>> getAllRules();

    @Query("SELECT * FROM app_rules ORDER BY appName ASC")
    List<AppRuleEntity> getAllRulesSync();
}
