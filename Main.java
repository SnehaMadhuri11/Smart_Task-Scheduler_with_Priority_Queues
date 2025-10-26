import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}

enum Priority { LOW, MEDIUM, HIGH }

class Task {
    private String title;
    private String description;
    private Priority priority;
    private LocalDateTime deadline;
    private final LocalDateTime createdAt;
    private boolean completed;
    private boolean reminded; // internal: has reminder fired?

    public Task(String title, String description, Priority priority, LocalDateTime deadline) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.deadline = deadline;
        this.createdAt = LocalDateTime.now();
        this.completed = false;
        this.reminded = false;
    }

    public Task(String title, String description, Priority priority, LocalDateTime deadline, LocalDateTime createdAt, boolean completed, boolean reminded) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.deadline = deadline;
        this.createdAt = createdAt;
        this.completed = completed;
        this.reminded = reminded;
    }

    // Getters/Setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public boolean isReminded() { return reminded; }
    public void setReminded(boolean reminded) { this.reminded = reminded; }

    public String toCsvLine() {
        return escape(title) + "|" + escape(description) + "|" + priority + "|" + deadline + "|" + createdAt + "|" + completed + "|" + reminded;
    }

    public static Task fromCsvLine(String line) {
        String[] parts = splitPreservingEmpty(line, '|', 7);
        if (parts.length < 6) throw new IllegalArgumentException("Bad line: " + line);
        String title = unescape(parts[0]);
        String desc = unescape(parts[1]);
        Priority p = Priority.valueOf(parts[2]);
        LocalDateTime deadline = LocalDateTime.parse(parts[3]);
        LocalDateTime createdAt = LocalDateTime.parse(parts[4]);
        boolean completed = Boolean.parseBoolean(parts[5]);
        boolean reminded = parts.length >= 7 && Boolean.parseBoolean(parts[6]);
        return new Task(title, desc, p, deadline, createdAt, completed, reminded);
    }

    private static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n"); }
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                if (c == 'n') out.append('\n');
                else out.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else out.append(c);
        }
        return out.toString();
    }
    private static String[] splitPreservingEmpty(String s, char delim, int expected) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { cur.append(c == 'n' ? '\n' : c); esc = false; }
            else if (c == '\\') esc = true;
            else if (c == delim) { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }
}

class TaskManager {
    private final java.util.List<Task> tasks = new ArrayList<>();

    private final Comparator<Task> priorityComparator = Comparator
            .comparing(Task::getPriority, Comparator.comparingInt(p -> {
                switch (p) {
                    case HIGH: return 0;
                    case MEDIUM: return 1;
                    default: return 2;
                }
            }))
            .thenComparing(Task::getDeadline)
            .thenComparing(Task::getCreatedAt);

    public void add(Task t) { tasks.add(t); }

    public void remove(Task t) { tasks.remove(t); }

    public java.util.List<Task> all() { return tasks; }

    public PriorityQueue<Task> toPriorityQueue() {
        PriorityQueue<Task> pq = new PriorityQueue<>(priorityComparator);
        pq.addAll(tasks.stream().filter(t -> !t.isCompleted()).collect(Collectors.toList()));
        return pq;
    }

    public java.util.List<Task> filtered(Filter f) {
        LocalDate today = LocalDate.now();
        WeekFields wf = WeekFields.of(Locale.getDefault());
        int currentWeek = today.get(wf.weekOfWeekBasedYear());
        return tasks.stream().filter(t -> {
            switch (f) {
                case ALL: return true;
                case TODAY: return t.getDeadline().toLocalDate().isEqual(today);
                case HIGH_PRIORITY: return t.getPriority() == Priority.HIGH && !t.isCompleted();
                case DUE_THIS_WEEK:
                    LocalDate d = t.getDeadline().toLocalDate();
                    return d.get(wf.weekOfWeekBasedYear()) == currentWeek && d.getYear() == today.getYear();
                case OVERDUE: return t.getDeadline().isBefore(LocalDateTime.now()) && !t.isCompleted();
                case COMPLETED: return t.isCompleted();
                case ACTIVE: return !t.isCompleted();
                default: return true;
            }
        }).sorted(priorityComparator).collect(Collectors.toList());
    }

    public void save(Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("title|description|priority|deadlineIso|createdAtIso|completed|reminded\n");
            for (Task t : tasks) {
                w.write(t.toCsvLine());
                w.write('\n');
            }
        }
    }

    public void load(Path file) throws IOException {
        tasks.clear();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                tasks.add(Task.fromCsvLine(line));
            }
        }
    }
}

enum Filter { ALL, TODAY, HIGH_PRIORITY, DUE_THIS_WEEK, OVERDUE, COMPLETED, ACTIVE }

class TaskTableModel extends AbstractTableModel {
    private final String[] cols = {"Done", "Title", "Priority", "Deadline", "Description"};
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private java.util.List<Task> data = new ArrayList<>();

    public void setData(java.util.List<Task> tasks) { this.data = new ArrayList<>(tasks); fireTableDataChanged(); }
    public Task getAt(int row) { return data.get(row); }

    @Override public int getRowCount() { return data.size(); }
    @Override public int getColumnCount() { return cols.length; }
    @Override public String getColumnName(int column) { return cols[column]; }
    @Override public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case 0: return Boolean.class;
            case 2: return Priority.class;
            case 3: return String.class;
            default: return String.class;
        }
    }
    @Override public boolean isCellEditable(int row, int col) { return col != 3; }

    @Override public Object getValueAt(int rowIndex, int columnIndex) {
        Task t = data.get(rowIndex);
        switch (columnIndex) {
            case 0: return t.isCompleted();
            case 1: return t.getTitle();
            case 2: return t.getPriority();
            case 3: return t.getDeadline().format(fmt);
            case 4: return t.getDescription();
        }
        return null;
    }

    @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        Task t = data.get(rowIndex);
        switch (columnIndex) {
            case 0: t.setCompleted((Boolean) aValue); break;
            case 1: t.setTitle((String) aValue); break;
            case 2: t.setPriority((Priority) aValue); break;
            case 4: t.setDescription((String) aValue); break;
        }
        fireTableRowsUpdated(rowIndex, rowIndex);
    }
}

class MainFrame extends JFrame {
    private final TaskManager manager = new TaskManager();
    private final TaskTableModel tableModel = new TaskTableModel();
    private final JTable table = new JTable(tableModel);
    private final JComboBox<Filter> filterCombo = new JComboBox<>(Filter.values());
    private final JTextField searchField = new JTextField();
    private final JLabel status = new JLabel("Ready");
    private final ReminderService reminderService;

    private final DateTimeFormatter pickerFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public MainFrame() {
        super("Smart Task Scheduler");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 640);
        setLocationRelativeTo(null);

        // Table setup
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(Priority.values())));

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                int modelRow = table.convertRowIndexToModel(row);
                if (modelRow >= 0) {
                    Task t = tableModel.getAt(modelRow);
                    // Reset to defaults first
                    if (isSelected) {
                        c.setForeground(table.getSelectionForeground());
                        c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    } else {
                        c.setForeground(Color.BLACK);
                        c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    }

                    if (!t.isCompleted() && t.getDeadline().isBefore(LocalDateTime.now())) {
                        c.setForeground(new Color(180, 0, 0));
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    } else if (t.getPriority() == Priority.HIGH && !t.isCompleted()) {
                        c.setForeground(new Color(160, 80, 0));
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                    }
                }
                return c;
            }
        };
        table.setDefaultRenderer(Object.class, renderer);

        JScrollPane scroll = new JScrollPane(table);

        // Top bar: controls
        JPanel controls = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6,6,6,6);
        gc.gridy = 0; gc.gridx = 0; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 0;
        controls.add(new JLabel("Filter:"), gc);
        gc.gridx++;
        controls.add(filterCombo, gc);
        gc.gridx++; controls.add(new JLabel("Search:"), gc);
        gc.gridx++; gc.weightx = 1; controls.add(searchField, gc);

        JButton addBtn = new JButton("Add");
        JButton editBtn = new JButton("Edit");
        JButton delBtn = new JButton("Delete");
        JButton markBtn = new JButton("Toggle Done");
        JButton saveBtn = new JButton("Save...");
        JButton loadBtn = new JButton("Load...");
        JButton pqPeekBtn = new JButton("Next Up");

        gc.gridy = 1; gc.gridx = 0; gc.weightx = 0; gc.gridwidth = 1;
        controls.add(addBtn, gc); gc.gridx++;
        controls.add(editBtn, gc); gc.gridx++;
        controls.add(delBtn, gc); gc.gridx++;
        controls.add(markBtn, gc); gc.gridx++;
        controls.add(pqPeekBtn, gc); gc.gridx++;
        controls.add(saveBtn, gc); gc.gridx++;
        controls.add(loadBtn, gc);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));
        statusBar.add(status, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(controls, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Listeners
        filterCombo.addActionListener(e -> refreshTable());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshTable(); }
            public void removeUpdate(DocumentEvent e) { refreshTable(); }
            public void changedUpdate(DocumentEvent e) { refreshTable(); }
        });

        addBtn.addActionListener(e -> onAdd());
        editBtn.addActionListener(e -> onEdit());
        delBtn.addActionListener(e -> onDelete());
        markBtn.addActionListener(e -> onToggleDone());
        saveBtn.addActionListener(e -> onSave());
        loadBtn.addActionListener(e -> onLoad());
        pqPeekBtn.addActionListener(e -> onPeek());

        // Seed with sample tasks
        manager.add(new Task("Submit assignment", "SQE assignment upload", Priority.HIGH, LocalDateTime.now().plusHours(3)));
        manager.add(new Task("Workout", "30 min run", Priority.MEDIUM, LocalDateTime.now().plusDays(1).withHour(7).withMinute(0)));
        manager.add(new Task("Buy groceries", "Milk, eggs, veggies", Priority.LOW, LocalDateTime.now().plusHours(26)));

        refreshTable();

        // Reminders
        reminderService = new ReminderService(this, manager);
        reminderService.start();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { reminderService.stop(); }
        });
    }

    private void refreshTable() {
        String q = searchField.getText() == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();
        java.util.List<Task> filtered = manager.filtered((Filter) filterCombo.getSelectedItem());
        if (!q.isEmpty()) {
            filtered = filtered.stream().filter(t ->
                    (t.getTitle() != null && t.getTitle().toLowerCase(Locale.ROOT).contains(q)) ||
                            (t.getDescription() != null && t.getDescription().toLowerCase(Locale.ROOT).contains(q))
            ).collect(Collectors.toList());
        }
        tableModel.setData(filtered);
        status.setText("Tasks: " + manager.all().size() + "  |  Showing: " + filtered.size());
    }

    private void onAdd() {
        TaskDialog dlg = new TaskDialog(this, null);
        dlg.setVisible(true);
        Task t = dlg.getResult();
        if (t != null) { manager.add(t); refreshTable(); }
    }

    private void onEdit() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to edit."); return; }
        Task t = tableModel.getAt(table.convertRowIndexToModel(row));
        TaskDialog dlg = new TaskDialog(this, t);
        dlg.setVisible(true);
        if (dlg.getResult() != null) { refreshTable(); }
    }

    private void onDelete() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task to delete."); return; }
        Task t = tableModel.getAt(table.convertRowIndexToModel(row));
        int c = JOptionPane.showConfirmDialog(this, "Delete '" + t.getTitle() + "'?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (c == JOptionPane.YES_OPTION) { manager.remove(t); refreshTable(); }
    }

    private void onToggleDone() {
        int row = table.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a task."); return; }
        Task t = tableModel.getAt(table.convertRowIndexToModel(row));
        t.setCompleted(!t.isCompleted());
        refreshTable();
    }

    private void onSave() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("tasks.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try { manager.save(fc.getSelectedFile().toPath()); status.setText("Saved to " + fc.getSelectedFile().getName()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage()); }
        }
    }

    private void onLoad() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try { manager.load(fc.getSelectedFile().toPath()); refreshTable(); status.setText("Loaded " + fc.getSelectedFile().getName()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage()); }
        }
    }

    private void onPeek() {
        PriorityQueue<Task> pq = manager.toPriorityQueue();
        if (pq.isEmpty()) { JOptionPane.showMessageDialog(this, "No active tasks."); return; }
        Task next = pq.peek();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        JOptionPane.showMessageDialog(this,
                "Next up (by priority & deadline):\n" +
                        next.getTitle() + " [" + next.getPriority() + "]\nDue: " + next.getDeadline().format(fmt));
    }
}

class TaskDialog extends JDialog {
    private final JTextField titleField = new JTextField();
    private final JComboBox<Priority> priorityBox = new JComboBox<>(Priority.values());
    private final JTextField deadlineField = new JTextField();
    private final JTextArea descArea = new JTextArea(5, 30);
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private Task result;

    public TaskDialog(Frame owner, Task toEdit) {
        super(owner, (toEdit == null ? "Add Task" : "Edit Task"), true);
        setSize(520, 360);
        setLocationRelativeTo(owner);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8,8,8,8); gc.fill = GridBagConstraints.HORIZONTAL; gc.gridx = 0; gc.gridy = 0;
        form.add(new JLabel("Title"), gc); gc.gridx = 1; gc.weightx = 1; form.add(titleField, gc);
        gc.gridx = 0; gc.gridy++; gc.weightx = 0; form.add(new JLabel("Priority"), gc); gc.gridx = 1; form.add(priorityBox, gc);
        gc.gridx = 0; gc.gridy++; form.add(new JLabel("Deadline (yyyy-MM-dd HH:mm)"), gc); gc.gridx = 1; form.add(deadlineField, gc);
        gc.gridx = 0; gc.gridy++; gc.anchor = GridBagConstraints.NORTHWEST; form.add(new JLabel("Description"), gc);
        gc.gridx = 1; gc.weighty = 1; gc.fill = GridBagConstraints.BOTH; descArea.setLineWrap(true); descArea.setWrapStyleWord(true); form.add(new JScrollPane(descArea), gc);

        JButton ok = new JButton("Save"); JButton cancel = new JButton("Cancel");
        JPanel buttons = new JPanel(); buttons.add(ok); buttons.add(cancel);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER); add(buttons, BorderLayout.SOUTH);

        if (toEdit != null) {
            titleField.setText(toEdit.getTitle());
            priorityBox.setSelectedItem(toEdit.getPriority());
            deadlineField.setText(toEdit.getDeadline().format(fmt));
            descArea.setText(toEdit.getDescription());
        } else {
            // sensible default: next hour
            deadlineField.setText(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).format(fmt));
        }

        ok.addActionListener(e -> {
            try {
                String title = titleField.getText().trim();
                if (title.isEmpty()) throw new IllegalArgumentException("Title is required");
                LocalDateTime deadline = LocalDateTime.parse(deadlineField.getText().trim(), fmt);
                Priority pr = (Priority) priorityBox.getSelectedItem();
                String desc = descArea.getText();
                if (toEdit == null) {
                    result = new Task(title, desc, pr, deadline);
                } else {
                    toEdit.setTitle(title); toEdit.setDescription(desc); toEdit.setPriority(pr); toEdit.setDeadline(deadline);
                    result = toEdit;
                }
                dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid input: " + ex.getMessage());
            }
        });
        cancel.addActionListener(e -> { result = null; dispose(); });
    }

    public Task getResult() { return result; }
}

class ReminderService {
    private final java.util.Timer timer = new java.util.Timer("task-reminder", true);
    private final JFrame owner;
    private final TaskManager manager;

    public ReminderService(JFrame owner, TaskManager manager) {
        this.owner = owner;
        this.manager = manager;
    }

    public void start() {
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() { checkReminders(); }
        }, 5_000, 60_000); // start after 5s, then every 60s
    }

    public void stop() { timer.cancel(); }

    private void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        for (Task t : manager.all()) {
            if (t.isCompleted()) continue;
            long minutesUntil = Duration.between(now, t.getDeadline()).toMinutes();
            boolean dueSoon = minutesUntil <= 15 && minutesUntil >= -60; // next 15 min or overdue within past hour
            if (dueSoon && !t.isReminded()) {
                t.setReminded(true);
                SwingUtilities.invokeLater(() -> showReminder(t));
            }
        }
    }

    private void showReminder(Task t) {
        String msg = String.format("%s [%s]\nDue: %s\n%s",
                t.getTitle(), t.getPriority(),
                t.getDeadline().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                Optional.ofNullable(t.getDescription()).orElse(""));
        if (SystemTray.isSupported()) {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                Image image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                TrayIcon trayIcon = new TrayIcon(image, "Smart Task Scheduler");
                trayIcon.setImageAutoSize(true);
                tray.add(trayIcon);
                trayIcon.displayMessage("Task Reminder", msg, TrayIcon.MessageType.INFO);
                // remove later to avoid duplicates in tray
                new java.util.Timer().schedule(new java.util.TimerTask() { public void run() { tray.remove(trayIcon); } }, 5000);
                return;
            } catch (Exception ignored) {}
        }
        JOptionPane.showMessageDialog(owner, msg, "Task Reminder", JOptionPane.INFORMATION_MESSAGE);
    }
}

// Needed for SystemTray icon creation without external images
class BufferedImage extends java.awt.image.BufferedImage {
    public BufferedImage(int width, int height, int imageType) {
        super(width, height, imageType);
        Graphics2D g2 = createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.fillRoundRect(0,0,width,height,6,6);
        g2.dispose();
    }
}