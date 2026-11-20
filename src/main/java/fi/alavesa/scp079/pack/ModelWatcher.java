package fi.alavesa.scp079.pack;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the models folder (models.watch-folder) and reports once per burst of changes: Blockbench writes
 * a file in several steps, so the callback only fires after the folder has been quiet for the debounce time.
 * The callback runs on the watcher thread - the caller hops to the server thread itself.
 */
public final class ModelWatcher {

    private final WatchService service;
    private final Thread thread;

    public ModelWatcher(Path folder, long debounceMillis, Runnable onChange) throws IOException {
        service = folder.getFileSystem().newWatchService();
        folder.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        thread = new Thread(() -> run(debounceMillis, onChange), "SCP079-ModelWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(long debounceMillis, Runnable onChange) {
        try {
            while (true) {
                WatchKey key = service.take();
                // swallow everything that follows closely
                while (key != null) {
                    key.pollEvents();
                    if (!key.reset()) return; // the folder is gone
                    key = service.poll(debounceMillis, TimeUnit.MILLISECONDS);
                }
                onChange.run();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // stopped
        }
    }

    public void stop() {
        try {
            service.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
        thread.interrupt();
    }
}
