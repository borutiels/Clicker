package app;

import com.google.gson.*;
import com.microsoft.playwright.*;

import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.io.File;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class Engine {

    // ---------- файлы пользователя ----------

    static final Path DATA = Paths.get(System.getProperty("user.home"), ".slotclicker");
    static final Path SCENARIO = DATA.resolve("scenario.json");
    static final Path SETTINGS = DATA.resolve("settings.json");
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Step {
        String selector;        // CSS-селектор
        String mode;            // "one" - один элемент, "all" - все подходящие
        String text;            // текст элемента (для удобства чтения)
        int limit;              // сколько кликать в режиме "all" (0 - все подходящие)

        String dateSource;      // имя атрибута (data-date) или слово "text"
        String datePattern;     // регулярка для поиска даты
        String dateFormat;      // yyyy-MM-dd, dd.MM.yyyy ...
        int windowMonths = 1;   // окно: от сегодня до сегодня + N месяцев

        @Override
        public String toString() {
            String m = "one".equals(mode) ? "один" : (limit == 0 ? "все" : "до " + limit);
            String d = dateSource == null ? "" : "  | дата: " + dateSource + ", +" + windowMonths + " мес.";
            String t = (text == null || text.isEmpty()) ? "" : "   («" + text + "»)";
            return "[" + m + "] " + selector + d + t;
        }
    }

    public static class Settings {
        String url = "";
        int pollMs = 100;
        int maxBookings = 0;
    }

    public static class PickResult {
        String exact, similar, text, classes;
        int similarCount;
    }

    public static List<Step> loadSteps() {
        try {
            if (Files.exists(SCENARIO)) {
                Step[] a = GSON.fromJson(Files.readString(SCENARIO), Step[].class);
                if (a != null) return new ArrayList<>(Arrays.asList(a));
            }
        } catch (Exception ignored) { }
        return new ArrayList<>();
    }

    public static void saveSteps(List<Step> steps) {
        try {
            Files.createDirectories(DATA);
            Files.writeString(SCENARIO, GSON.toJson(steps));
        } catch (Exception ignored) { }
    }

    public static Settings loadSettings() {
        try {
            if (Files.exists(SETTINGS)) {
                Settings s = GSON.fromJson(Files.readString(SETTINGS), Settings.class);
                if (s != null) return s;
            }
        } catch (Exception ignored) { }
        return new Settings();
    }

    public static void saveSettings(Settings s) {
        try {
            Files.createDirectories(DATA);
            Files.writeString(SETTINGS, GSON.toJson(s));
        } catch (Exception ignored) { }
    }

    // ---------- JS внутри страницы ----------

    static final String PICKER_JS = """
    (() => {
      if (window.__pickerInstalled) return;
      window.__pickerInstalled = true;
      let last = null;
      const bad = new Set(['selected','active','hover','focus','open']);

      function cls(el) {
        return [...el.classList].filter(c => !bad.has(c));
      }

      function css(el) {
        if (el.id && !/\\d{3,}/.test(el.id)) return '#' + CSS.escape(el.id);
        const c = cls(el);
        if (c.length) return el.tagName.toLowerCase() + c.map(x => '.' + CSS.escape(x)).join('');
        const parts = [];
        let cur = el;
        while (cur && cur.nodeType === 1 && cur !== document.body && parts.length < 5) {
          let s = cur.tagName.toLowerCase();
          const sibs = [...cur.parentNode.children].filter(x => x.tagName === cur.tagName);
          if (sibs.length > 1) s += ':nth-of-type(' + (sibs.indexOf(cur) + 1) + ')';
          parts.unshift(s);
          cur = cur.parentElement;
        }
        return parts.join(' > ');
      }

      function sim(el) {
        return el.tagName.toLowerCase() + cls(el).map(x => '.' + CSS.escape(x)).join('');
      }

      document.addEventListener('mouseover', e => {
        if (!window.__pickerOn) return;
        if (last) last.style.outline = '';
        last = e.target;
        last.style.outline = '2px solid red';
      }, true);

      ['mousedown', 'mouseup', 'click'].forEach(type => {
        document.addEventListener(type, e => {
          if (!window.__pickerOn) return;
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          if (type !== 'click') return;
          if (last) last.style.outline = '';
          window.__pickerOn = false;
          window.onPicked(css(e.target), sim(e.target),
                          (e.target.innerText || '').trim().slice(0, 40),
                          cls(e.target).join(' '));
        }, true);
      });
    })();
    """;

    static final String SCAN_JS = """
    (a) => {
      const out = [];
      document.querySelectorAll(a.sel).forEach(el => {
        let raw = '';
        if (a.src) {
          let cur = el;
          for (let k = 0; k < 5 && cur; k++, cur = cur.parentElement) {
            const t = a.src === 'text' ? cur.innerText : cur.getAttribute(a.src);
            if (!t) continue;
            if (a.pat) {
              const m = t.match(new RegExp(a.pat));
              if (m) { raw = m[0]; break; }
            } else { raw = t; break; }
          }
        }
        out.push(raw);
      });
      return out;
    }
    """;

    // ---------- состояние ----------

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playwright-worker");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<String> logger;
    public final AtomicBoolean stop = new AtomicBoolean(false);
    private final AtomicReference<String[]> picked = new AtomicReference<>();
    private final AtomicReference<Page> targetPage = new AtomicReference<>();
    private Playwright pw;
    private volatile BrowserContext ctx;
    private volatile Page page;

    public Engine(Consumer<String> logger) {
        this.logger = logger;
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> logger.accept(msg));
    }

    private static String firstLine(Throwable e) {
        return String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString());
    }

    static String normalizeUrl(String s) {
        s = s.trim();
        if (s.isEmpty()) return "about:blank";
        if (s.matches("(?i)^(https?|file)://.*")) return s;
        File f = new File(s);
        if (f.exists()) return f.toURI().toString();
        return "https://" + s;
    }

    // запускается только в рабочем потоке
    private void ensureBrowser() {
        if (pw == null) {
            log("Подготовка браузера (при самом первом запуске скачивается около 150 МБ, подожди)...");
            pw = Playwright.create();
        }
        if (ctx == null) {
            try { Files.createDirectories(DATA); } catch (Exception ignored) { }
            BrowserContext c = pw.chromium().launchPersistentContext(
                    DATA.resolve("profile"),
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(false)
                            .setViewportSize(null)
                            .setArgs(List.of(
                                    "--disable-background-timer-throttling",
                                    "--disable-renderer-backgrounding",
                                    "--disable-backgrounding-occluded-windows")));
            c.onClose(x -> {
                ctx = null;
                page = null;
                log("Окно браузера закрыто.");
            });
            c.exposeBinding("onPicked", (source, a) -> {
                picked.set(new String[]{(String) a[0], (String) a[1], (String) a[2], (String) a[3]});
                targetPage.set(source.page());
                return null;
            });
            c.addInitScript(PICKER_JS);
            page = c.pages().isEmpty() ? c.newPage() : c.pages().get(0);
            targetPage.set(page);
            ctx = c;
        }
    }

    private Page currentPage() {
        Page p = targetPage.get();
        if (p != null && !p.isClosed()) return p;
        if (ctx != null && !ctx.pages().isEmpty()) return ctx.pages().get(0);
        throw new PlaywrightException("Браузер не открыт. Нажми «Открыть».");
    }

    // ---------- команды из окна ----------

    public void open(String input) {
        worker.submit(() -> {
            try {
                ensureBrowser();
                String url = normalizeUrl(input);
                Page p = currentPage();
                p.navigate(url);
                targetPage.set(p);
                log("Открыто: " + url);
            } catch (Exception e) {
                log("Ошибка: " + firstLine(e));
            }
        });
    }

    // ждёт клик пользователя по элементу на странице; результат вернётся в onDone (null = отмена/ошибка)
    public void pick(Consumer<PickResult> onDone) {
        stop.set(false);
        worker.submit(() -> {
            PickResult result = null;
            try {
                ensureBrowser();
                Page p0 = page;
                picked.set(null);
                for (Page p : ctx.pages()) {
                    try {
                        p.evaluate(PICKER_JS);
                        p.evaluate("window.__pickerOn = true");
                    } catch (PlaywrightException ignored) { }
                }
                log("Кликни в браузере по нужному элементу (он подсветится красной рамкой)...");
                while (picked.get() == null && !stop.get()) {
                    p0.waitForTimeout(100);
                }

                if (picked.get() != null) {
                    String[] a = picked.get();
                    PickResult r = new PickResult();
                    r.exact = a[0];
                    r.similar = a[1];
                    r.text = a[2];
                    r.classes = a[3];
                    r.similarCount = currentPage().locator(r.similar).count();
                    result = r;
                } else {
                    for (Page p : ctx.pages()) {
                        try { p.evaluate("window.__pickerOn = false"); } catch (PlaywrightException ignored) { }
                    }
                    log("Запись шага отменена.");
                }
            } catch (Exception e) {
                log("Ошибка: " + firstLine(e));
            }
            PickResult fin = result;
            SwingUtilities.invokeLater(() -> onDone.accept(fin));
        });
    }

    public void checkDate(Step s) {
        worker.submit(() -> {
            try {
                Page p = currentPage();
                ElementHandle h = p.querySelector(s.selector);
                if (h == null) {
                    log("Проверка даты: подходящих элементов сейчас нет на странице (это нормально, если все слоты заняты).");
                } else {
                    LocalDate d = readDate(h, s);
                    log("Проверка даты: " + (d == null
                            ? "дата НЕ распознана, проверь источник и формат"
                            : "первый элемент имеет дату " + d));
                }
            } catch (Exception e) {
                log("Проверка даты не удалась: " + firstLine(e));
            }
        });
    }

    // бесконечное слежение за страницей
    public void start(List<Step> steps, int maxBookings, int pollMs, Runnable onFinish) {
        stop.set(false);
        worker.submit(() -> {
            int booked = 0;
            int failed = 0;
            long lastLog = 0;
            try {
                if (ctx == null) {
                    log("Сначала нажми «Открыть» и открой нужную страницу.");
                    return;
                }
                Page p = currentPage();
                log("Слежу за страницей. Окно браузера можно сворачивать. Остановить: кнопка «Стоп».");

                while (!stop.get()) {
                    try {
                        List<Locator> first = candidates(p, steps.get(0));

                        if (!first.isEmpty()) {
                            long t0 = System.currentTimeMillis();
                            log("Слот найден, кликаю...");

                            for (int i = 0; i < steps.size(); i++) {
                                Step s = steps.get(i);
                                List<Locator> list;
                                if (i == 0) {
                                    list = first;
                                } else {
                                    p.waitForSelector(s.selector,
                                            new Page.WaitForSelectorOptions().setTimeout(3000));
                                    list = candidates(p, s);
                                }
                                if (list.isEmpty()) {
                                    throw new PlaywrightException("нет подходящего элемента для шага " + (i + 1));
                                }
                                for (Locator l : list) {
                                    l.click(new Locator.ClickOptions().setTimeout(2000));
                                }
                            }
                            booked++;
                            log("Бронирование №" + booked + " выполнено за "
                                    + (System.currentTimeMillis() - t0) + " мс.");
                            SwingUtilities.invokeLater(() -> Toolkit.getDefaultToolkit().beep());

                            if (maxBookings > 0 && booked >= maxBookings) {
                                log("Достигнут лимит бронирований (" + maxBookings + ").");
                                break;
                            }
                            p.waitForTimeout(500);
                            continue;
                        }

                    } catch (PlaywrightException e) {
                        if (p.isClosed() || ctx == null) {
                            log("Браузер закрыт, останавливаюсь.");
                            break;
                        }
                        failed++;
                        log("Не удалось (" + firstLine(e) + "). Продолжаю следить. Неудач: " + failed);
                        p.waitForTimeout(500);
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastLog > 10_000) {
                        log("Жду слот... забронировано: " + booked);
                        lastLog = now;
                    }
                    p.waitForTimeout(pollMs);
                }
            } catch (Exception e) {
                log("Остановлено из-за ошибки: " + firstLine(e));
            } finally {
                log("Остановлено. Забронировано: " + booked + ", неудач: " + failed);
                SwingUtilities.invokeLater(onFinish);
            }
        });
    }

    public void shutdown() {
        stop.set(true);
        worker.submit(() -> {
            try { if (ctx != null) ctx.close(); } catch (Exception ignored) { }
            try { if (pw != null) pw.close(); } catch (Exception ignored) { }
        });
        worker.shutdown();
        try { worker.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    // ---------- даты и поиск кандидатов ----------

    static LocalDate readDate(ElementHandle h, Step s) {
        if (s.dateSource == null) return null;
        try {
            Object raw = h.evaluate("""
                (e, a) => {
                  let cur = e;
                  for (let i = 0; i < 5 && cur; i++, cur = cur.parentElement) {
                    const t = a.src === 'text' ? cur.innerText : cur.getAttribute(a.src);
                    if (!t) continue;
                    if (a.pat) {
                      const m = t.match(new RegExp(a.pat));
                      if (m) return m[0];
                    } else {
                      return t;
                    }
                  }
                  return null;
                }
                """, Map.of("src", s.dateSource, "pat", s.datePattern == null ? "" : s.datePattern));
            if (raw == null) return null;
            return LocalDate.parse(raw.toString().trim(), DateTimeFormatter.ofPattern(s.dateFormat));
        } catch (DateTimeParseException | PlaywrightException | IllegalArgumentException e) {
            return null;
        }
    }

    static boolean rawInWindow(String raw, Step s) {
        if (s.dateSource == null) return true;
        if (raw == null || raw.isBlank()) return false;
        try {
            LocalDate d = LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(s.dateFormat));
            LocalDate today = LocalDate.now();           // окно пересчитывается при каждой проверке
            return !d.isBefore(today) && !d.isAfter(today.plusMonths(s.windowMonths));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return false;
        }
    }

    static List<Locator> candidates(Page page, Step s) {
        Map<String, Object> arg = new HashMap<>();
        arg.put("sel", s.selector);
        arg.put("src", s.dateSource == null ? "" : s.dateSource);
        arg.put("pat", s.datePattern == null ? "" : s.datePattern);

        List<?> raws = (List<?>) page.evaluate(SCAN_JS, arg);
        Locator all = page.locator(s.selector);

        int max = "one".equals(s.mode) ? 1 : s.limit;
        List<Locator> res = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            if (rawInWindow(String.valueOf(raws.get(i)), s)) {
                res.add(all.nth(i));
                if (max > 0 && res.size() >= max) break;
            }
        }
        return res;
    }
}