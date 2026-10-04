package com.portfolio.jsontosqlite;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dialog;

/** Displays progress while a JSON file is being imported. */
final class ProgressWindow {
    private final JDialog dialog;
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel keyCountLabel = new JLabel("Scanning JSON keys...");

    /** Builds a modeless progress dialog for the selected JSON file. */
    ProgressWindow(JFrame owner, String fileName) {
        dialog = new JDialog(owner, "Import progress", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(10, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        content.add(new JLabel("Importing " + fileName), BorderLayout.NORTH);
        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(true);
        content.add(progressBar, BorderLayout.CENTER);
        content.add(keyCountLabel, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(440, 145);
        dialog.setLocationRelativeTo(owner);
    }

    /** Shows or hides the progress dialog. */
    void setVisible(boolean visible) {
        dialog.setVisible(visible);
    }

    /** Applies a progress update to the dialog controls. */
    private void updateProgress(ProgressUpdate update) {
        progressBar.setIndeterminate(false);
        int percent = update.totalKeys() == 0 ? 100
                : (int) (100L * update.completedKeys() / update.totalKeys());
        progressBar.setValue(percent);
        progressBar.setString(percent + "%");
        keyCountLabel.setText(String.format("%,d / %,d unique keys",
                update.completedKeys(), update.totalKeys()));
    }

    /** Queues a progress update on the Swing event-dispatch thread. */
    void scheduleUpdate(ProgressUpdate update) {
        SwingUtilities.invokeLater(() -> updateProgress(update));
    }

    /** Closes the progress dialog. */
    void dispose() {
        dialog.dispose();
    }
}
