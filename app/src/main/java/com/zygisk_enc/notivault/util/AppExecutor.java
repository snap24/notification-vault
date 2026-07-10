package com.zygisk_enc.notivault.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppExecutor {
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    public static ExecutorService getExecutor() {
        return executor;
    }
}
