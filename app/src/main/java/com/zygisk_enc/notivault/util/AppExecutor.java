package com.zygisk_enc.notivault.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AppExecutor {
    private static final int CPU_CORES = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger threadCounter = new AtomicInteger(1);

    private static final ExecutorService executor = Executors.newFixedThreadPool(CPU_CORES, r -> {
        Thread thread = new Thread(r, "NotiVault-CoreWorker-" + threadCounter.getAndIncrement());
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        return thread;
    });

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    public static ExecutorService getExecutor() {
        return executor;
    }

    public static int getCpuCores() {
        return CPU_CORES;
    }
}
