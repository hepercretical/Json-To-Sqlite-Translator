package com.portfolio.jsontosqlite;

import java.nio.file.Path;

/** Contains the small path transformations used by the Swing interface. */
final class FilePathSupport {
    private FilePathSupport() {
        // Prevent instantiation of this utility class.
    }

    /** Adds the SQLite extension only when the selected file name has no extension. */
    static Path ensureSqliteExtension(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.contains(".")) {
            return path;
        }
        return path.resolveSibling(fileName + ".sqlite");
    }

    /** Removes the final extension from a file name when one is present. */
    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
