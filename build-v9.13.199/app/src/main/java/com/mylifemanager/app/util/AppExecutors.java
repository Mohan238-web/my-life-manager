package com.mylifemanager.app.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppExecutors {
    public final ExecutorService disk = Executors.newSingleThreadExecutor(r -> new Thread(r, "mlm-database"));
    public final ExecutorService network = Executors.newFixedThreadPool(2, r -> new Thread(r, "mlm-network"));

    public void shutdown() {
        disk.shutdown();
        network.shutdown();
    }
}
