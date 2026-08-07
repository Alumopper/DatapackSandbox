package moe.afox.dpsandbox.browser.core;

import org.teavm.jso.JSExport;

/** TeaVM entry point used to prove that the JVM execution core can run in a browser worker. */
public final class BrowserCoreModule {
    private BrowserCoreModule() {
    }

    public static void main(String[] args) {
        // Library entry point. Browser callers use the exported methods below.
    }

    @JSExport
    public static BrowserCoreSession createSession(
            String version,
            int maximumCommands,
            int maximumOutputEvents,
            int maximumSnapshotBytes) {
        return new BrowserCoreSession(version, maximumCommands, maximumOutputEvents, maximumSnapshotBytes);
    }
}
