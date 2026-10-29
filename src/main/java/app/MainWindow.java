package app;

import app.Engine.PickResult;
import app.Engine.Settings;
import app.Engine.Step;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

public class MainWindow extends JFrame {

    private final JTextField urlField = new JTextField();
    private final JButton openBtn = new JButton("Открыть");
    private final JSpinner pollSpin = new JSpinner(new SpinnerNumberModel(100, 20, 5000, 10));
    private final JSpinner maxSpin = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1));

    private final DefaultListModel<Step> stepsModel = new DefaultListModel<>();
    private final JList<Step> stepsList = new JList<>(stepsModel);
    private final JButton recordBtn = new JButton("Записать шаг");
    private final JButton deleteBtn = new JButton("Удалить выбранный");
    private final JButton clearBtn = new JButton("Очистить всё");

    private final JButton startBtn = new JButton("▶ Старт");
    private final JButton stopBtn = new JButton("■ Стоп");
    private final JTextArea logArea = new JTextArea();

    private final Engine engine;
    private final Settings settings;

    public MainWindow() {
        super("Slot Clicker");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(880, 660);
        setLocationRelativeTo(null);

        settings = Engine.loadSettings();
        engine = new Engine(this::log);

        urlField.setText(settings.url);
        pollSpin.setValue(settings.pollMs);
        maxSpin.setValue(settings.maxBookings);
        for (Step s : Engine.loadSteps()) stepsModel.addElement(s);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        stepsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        startBtn.setFont(startBtn.getFont().deriveFont(Font.BOLD, 15f));
        stopBtn.setFont(stopBtn.getFont().deriveFont(Font.BOLD, 15f));

        // верхняя часть: адрес и настройки
        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.add(new JLabel("Сайт:"), BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(openBtn, BorderLayout.EAST);

        JPanel optRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        optRow.add(new JLabel("Проверять каждые"));
        optRow.add(pollSpin);
        optRow.add(new JLabel("мс.     Остановиться после"));
        optRow.add(maxSpin);
        optRow.add(new JLabel("бронирований (0 = бесконечно)"));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(urlRow);
        top.add(optRow);
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        // шаги
        JPanel stepsPanel = new JPanel(new BorderLayout(0, 4));
        stepsPanel.setBorder(BorderFactory.createTitledBorder("Шаги сценария (выполняются по порядку)"));
        stepsPanel.add(new JScrollPane(stepsList), BorderLayout.CENTER);
        JPanel stepBtns = new JPanel(new FlowLayout(FlowLayout.LEFT));
        stepBtns.add(recordBtn);
        stepBtns.add(deleteBtn);
        stepBtns.add(clearBtn);
        stepsPanel.add(stepBtns, BorderLayout.SOUTH);

        // журнал
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Журнал"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stepsPanel, logScroll);
        split.setResizeWeight(0.4);

        JPanel runRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        runRow.add(startBtn);
        runRow.add(stopBtn);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(runRow, BorderLayout.SOUTH);

        // действия
        openBtn.addActionListener(e -> {
            saveSettings();
            engine.open(urlField.getText());
        });
        urlField.addActionListener(e -> openBtn.doClick());

        recordBtn.addActionListener(e -> {
            setBusy(true);
            engine.pick(r -> {
                try {
                    if (r != null) {
                        Step s = askStep(r);
                        if (s != null) {
                            stepsModel.addElement(s);
                            persistSteps();
                            log("Добавлен шаг: " + s);
                            if (s.dateSource != null) engine.checkDate(s);
                        }
                    }
                } finally {
                    setBusy(false);
                }
            });
        });

        deleteBtn.addActionListener(e -> {
            int i = stepsList.getSelectedIndex();
            if (i >= 0) {
                stepsModel.remove(i);
                persistSteps();
            }
        });

        clearBtn.addActionListener(e -> {
            if (!stepsModel.isEmpty() && JOptionPane.showConfirmDialog(this,
                    "Удалить все шаги?", "Подтверждение", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                stepsModel.clear();
                persistSteps();
            }
        });

        startBtn.addActionListener(e -> {
            if (stepsModel.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Сначала запиши хотя бы один шаг.");
                return;
            }
            List<Step> steps = Collections.list(stepsModel.elements());
            saveSettings();
            setBusy(true);
            engine.start(steps, (Integer) maxSpin.getValue(), (Integer) pollSpin.getValue(),
                    () -> setBusy(false));
        });

        stopBtn.addActionListener(e -> {
            engine.stop.set(true);
            log("Останавливаю...");
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveSettings();
                engine.shutdown();
                dispose();
                System.exit(0);
            }
        });

        setBusy(false);
        log("Готово. Введи адрес сайта, нажми «Открыть», затем «Записать шаг».");
    }

    // ---------- вспомогательное ----------

    private void setBusy(boolean busy) {
        openBtn.setEnabled(!busy);
        recordBtn.setEnabled(!busy);
        deleteBtn.setEnabled(!busy);
        clearBtn.setEnabled(!busy);
        startBtn.setEnabled(!busy);
        pollSpin.setEnabled(!busy);
        maxSpin.setEnabled(!busy);
        stopBtn.setEnabled(busy);
    }

    private void persistSteps() {
        Engine.saveSteps(Collections.list(stepsModel.elements()));
    }

    private void saveSettings() {
        settings.url = urlField.getText().trim();
        settings.pollMs = (Integer) pollSpin.getValue();
        settings.maxBookings = (Integer) maxSpin.getValue();
        Engine.saveSettings(settings);
    }

    private void log(String msg) {
        if (logArea.getDocument().getLength() > 100_000) logArea.setText("");
        logArea.append(LocalTime.now().withNano(0) + "  " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component c : cs) p.add(c);
        return p;
    }

    // окно настройки шага после клика по элементу
    private Step askStep(PickResult r) {
        JRadioButton rbOne = new JRadioButton("Только этот элемент:  " + r.exact);
        JRadioButton rbSim = new JRadioButton(
                "Все похожие:  " + r.similar + "   (найдено сейчас: " + r.similarCount + ")", true);
        JRadioButton rbNot = new JRadioButton("Все похожие, КРОМЕ имеющих класс (если кликнул по занятому слоту):");
        JTextField notField = new JTextField("busy", 10);
        JRadioButton rbCustom = new JRadioButton("Свой CSS-селектор:");
        JTextField customField = new JTextField(22);
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbOne);
        bg.add(rbSim);
        bg.add(rbNot);
        bg.add(rbCustom);

        JSpinner limit = new JSpinner(new SpinnerNumberModel(1, 0, 1000, 1));
        JCheckBox dateCb = new JCheckBox("Ограничить по дате: от сегодня до +N месяцев (окно сдвигается каждый день)");
        JTextField srcF = new JTextField("data-date", 10);
        JTextField patF = new JTextField(12);
        JTextField fmtF = new JTextField("yyyy-MM-dd", 10);
        JSpinner monthsS = new JSpinner(new SpinnerNumberModel(1, 1, 24, 1));

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(new JLabel("Выбран элемент с текстом: «" + r.text + "»   |   классы: "
                + (r.classes.isEmpty() ? "(нет)" : r.classes)));
        p.add(rbOne);
        p.add(rbSim);
        p.add(row(rbNot, notField));
        p.add(row(rbCustom, customField));
        p.add(row(new JLabel("Сколько элементов кликать за раз (0 = все подходящие):"), limit));
        p.add(dateCb);
        p.add(row(new JLabel("Где дата (имя атрибута или слово text):"), srcF));
        p.add(row(new JLabel("Регулярка даты (для text обязательна, иначе можно пусто):"), patF));
        p.add(row(new JLabel("Формат даты:"), fmtF, new JLabel("   Месяцев вперёд:"), monthsS));

        int ans = JOptionPane.showConfirmDialog(this, p, "Новый шаг",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return null;

        Step s = new Step();
        s.text = r.text;
        s.mode = "all";

        if (rbOne.isSelected()) {
            s.mode = "one";
            s.selector = r.exact;
        } else if (rbNot.isSelected() && !notField.getText().isBlank()) {
            String ex = notField.getText().trim();
            String tag = r.similar.contains(".") ? r.similar.substring(0, r.similar.indexOf('.')) : r.similar;
            StringBuilder sb = new StringBuilder(tag);
            for (String c : r.classes.split(" ")) {
                if (!c.isEmpty() && !c.equals(ex)) sb.append('.').append(c);
            }
            sb.append(":not(.").append(ex).append(")");
            s.selector = sb.toString();
        } else if (rbCustom.isSelected() && !customField.getText().isBlank()) {
            s.selector = customField.getText().trim();
        } else {
            s.selector = r.similar;
        }

        if (s.mode.equals("all")) {
            s.limit = (Integer) limit.getValue();
            if (dateCb.isSelected()) {
                if (srcF.getText().isBlank() || fmtF.getText().isBlank()) {
                    log("Ограничение по дате не добавлено: не заполнен источник или формат.");
                } else {
                    s.dateSource = srcF.getText().trim();
                    s.datePattern = patF.getText().isBlank() ? null : patF.getText().trim();
                    s.dateFormat = fmtF.getText().trim();
                    s.windowMonths = (Integer) monthsS.getValue();
                }
            }
        }
        return s;
    }
}