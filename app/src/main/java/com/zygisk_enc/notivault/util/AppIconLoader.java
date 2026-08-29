package com.zygisk_enc.notivault.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppIconLoader {

    private static volatile AppIconLoader instance;
    private final LruCache<String, Bitmap> iconCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int iconSizePx;

    private AppIconLoader(Context context) {
        Context appContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024); // in KB
        int cacheSize = Math.min(maxMemory / 16, 16 * 1024); // max 16MB or 1/16th RAM

        iconCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        iconSizePx = Math.max((int) (48 * dm.density), 48);
    }

    public static AppIconLoader getInstance(Context context) {
        if (instance == null) {
            synchronized (AppIconLoader.class) {
                if (instance == null) {
                    instance = new AppIconLoader(context);
                }
            }
        }
        return instance;
    }

    @Nullable
    public Bitmap getCachedBitmap(String packageName) {
        if (packageName == null) return null;
        return iconCache.get(packageName);
    }

    public void loadInto(@NonNull ImageView imageView, @Nullable String packageName, int placeholderResId) {
        if (packageName == null || packageName.isEmpty()) {
            imageView.setTag(null);
            imageView.setImageResource(placeholderResId);
            return;
        }

        imageView.setTag(packageName);
        Bitmap cached = iconCache.get(packageName);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageResource(placeholderResId);
        Context appContext = imageView.getContext().getApplicationContext();

        executor.execute(() -> {
            Bitmap bitmap = renderAppIcon(appContext, packageName);
            if (bitmap != null) {
                iconCache.put(packageName, bitmap);
                mainHandler.post(() -> {
                    if (packageName.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    @Nullable
    private Bitmap renderAppIcon(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Drawable drawable = pm.getApplicationIcon(packageName);
            return drawableToBitmap(drawable, iconSizePx);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static Bitmap drawableToBitmap(Drawable drawable, int size) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && bitmap.getWidth() == size && bitmap.getHeight() == size) {
                return bitmap;
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                return Bitmap.createScaledBitmap(bitmap, size, size, true);
            }
        }

        int targetWidth = size > 0 ? size : Math.max(drawable.getIntrinsicWidth(), 48);
        int targetHeight = size > 0 ? size : Math.max(drawable.getIntrinsicHeight(), 48);

        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    public void clearCache() {
        iconCache.evictAll();
    }
}
