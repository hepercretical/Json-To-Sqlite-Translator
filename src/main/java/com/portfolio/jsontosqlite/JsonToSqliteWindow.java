package com.portfolio.jsontosqlite;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

/** Provides the Swing controls and user interactions for a conversion. */
final class JsonToSqliteWindow {
    private final JFrame frame = new JFrame("JSON to SQLite");
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JLabel statusLabel = new JLabel("Choose a JSON file and a destination for the SQLite database.");
    private final JButton convertButton = new JButton("Convert");
    private final JButton inputBrowseButton = new JButton("Browse...");
    private final JButton outputBrowseButton = new JButton("Browse...");

    /** Builds the window and wires its controls to their actions. */
    JsonToSqliteWindow() {
        buildWindow();
    }

    /** Makes the completed application window visible. */
    void showWindow() {
        frame.setVisible(true);
    }

    /** Creates the layout, controls, and action listeners for the main window. */
    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new java.awt.Dimension(620, 230));
        frame.setSize(720, 260);
        frame.setLocationRelativeTo(null);

        JPanel content = new JPanel(new BorderLayout(12, 16));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));
        JPanel fileChoices = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 5, 6, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addFileRow(fileChoices, constraints, 0, "JSON file", inputField, inputBrowseButton);
        addFileRow(fileChoices, constraints, 1, "SQLite output", outputField, outputBrowseButton);

        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.add(statusLabel, BorderLayout.CENTER);
        actions.add(convertButton, BorderLayout.EAST);
        content.add(fileChoices, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);
        inputBrowseButton.addActionListener(event -> chooseInput());
        outputBrowseButton.addActionListener(event -> chooseOutput());
        convertButton.addActionListener(event -> convert());
    }

    /** Adds one labeled path field and browse button to the file-selection panel. */
    private void addFileRow(JPanel panel, GridBagConstraints constraints, int row,
                            String label, JTextField field, JButton browseButton) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        field.setEditable(false);
        panel.add(field, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(browseButton, constraints);
    }

    /** Opens the JSON chooser and fills the default SQLite destination when needed. */
    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a JSON file");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            inputField.setText(chooser.getSelectedFile().getAbsolutePath());
            if (outputField.getText().isBlank()) {
                Path input = chooser.getSelectedFile().toPath();
                outputField.setText(input.resolveSibling(FilePathSupport.stripExtension(input.getFileName().toString()) + ".sqlite").toString());
            }
        }
    }

    /** Opens the SQLite destination chooser and ensures the selected path has an extension. */
    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose where to save the SQLite database");
        chooser.setSelectedFile(new java.io.File(outputField.getText().isBlank() ? "translated.sqlite" : outputField.getText()));
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite databases (*.sqlite, *.db)", "sqlite", "db"));
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(FilePathSupport.ensureSqliteExtension(chooser.getSelectedFile().toPath()).toString());
        }
    }

    /** Validates the selected paths, starts the background import, and reports its result. */
    private void convert() {
        if (inputField.getText().isBlank() || outputField.getText().isBlank()) {
            showError("Choose both a JSON input file and an SQLite output location.");
            return;
        }
        Path input = Path.of(inputField.getText());
        Path output = FilePathSupport.ensureSqliteExtension(Path.of(outputField.getText()));
        if (!Files.isRegularFile(input) || !input.getFileName().toString().toLowerCase().endsWith(".json")) {
            showError("Select an existing .json file.");
            return;
        }
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            showError("The output database must be different from the input file.");
            return;
        }
        if (!confirmReplaceExistingOutput(output)) {
            return;
        }
        setBusy(true);
        statusLabel.setText("Reading JSON and writing database...");
        ProgressWindow progressWindow = new ProgressWindow(frame, input.getFileName().toString());
        progressWindow.setVisible(true);
        new SwingWorker<ImportResult, Void>() {
            /** Performs the file conversion away from the Swing event-dispatch thread. */
            @Override
            protected ImportResult doInBackground() throws Exception {
                return new JsonImportService().importJson(input, output, progressWindow::scheduleUpdate);
            }

            /** Updates the UI after the background conversion completes or fails. */
            @Override
            protected void done() {
                setBusy(false);
                progressWindow.dispose();
                try {
                    ImportResult result = get();
                    statusLabel.setText("Conversion complete: " + result.recordCount() + " records written.");
                    JOptionPane.showMessageDialog(frame,
                            "Created SQLite database:\n" + output.toAbsolutePath()
                                    + "\n\nRecords written: " + result.recordCount()
                                    + "\nNested array rows: " + result.relatedRowCount(),
                            "Conversion complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Conversion interrupted.");
                    showError("The conversion was interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    statusLabel.setText("Conversion failed.");
                    showError("Could not convert the file: "
                            + (cause == null ? exception.getMessage() : cause.getMessage()));
                }
            }
        }.execute();
    }

    /** Asks whether imported tables may be replaced when the destination already exists. */
    private boolean confirmReplaceExistingOutput(Path output) {
        return !Files.exists(output) || JOptionPane.showConfirmDialog(frame,
                "The selected database already exists. Its imported JSON tables will be replaced; other tables will be kept. Continue?",
                "Replace imported data?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Enables or disables controls while a conversion is running. */
    private void setBusy(boolean busy) {
        inputBrowseButton.setEnabled(!busy);
        outputBrowseButton.setEnabled(!busy);
        convertButton.setEnabled(!busy);
    }

    /** Displays a conversion error dialog with the supplied message. */
    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Conversion error", JOptionPane.ERROR_MESSAGE);
    }
}
