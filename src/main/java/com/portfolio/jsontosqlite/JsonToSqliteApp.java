package com.portfolio.jsontosqlite;

import javax.swing.SwingUtilities;

/** Starts the JSON-to-SQLite desktop application. */
public final class JsonToSqliteApp {
    private JsonToSqliteApp() {
        // Prevent instantiation of the application entry-point class.
    }

    /** Starts the Swing user interface on the event-dispatch thread. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JsonToSqliteWindow().showWindow());
    }
}
