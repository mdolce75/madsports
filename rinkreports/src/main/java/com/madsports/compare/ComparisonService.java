package com.madsports.compare;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.madsports.report.FileNames;
import com.madsports.report.CategoryExtractor;
import com.madsports.report.ExcelExporter;
import com.madsports.report.PdfRenderer;
import com.madsports.report.ReadyReport;
import com.madsports.report.ReportService;
import com.madsports.report.ReportService.Scope;
import com.madsports.report.ReportService.Status;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * El comparativo de dos equipos de la misma categoría y rama: lee de la fuente las competiciones del año de cada uno
 * (las mismas lecturas que su informe de equipo, con su cache) y arma el {@link Comparison}.
 */
@Service
public class ComparisonService {

    /**
     * Qué comparar.
     *
     * @param categoryA la opción de categoría del club A ("PRIMERA", "INFANTIL:B" si tiene equipo A y B)
     * @param categoryB la del club B: tiene que ser del mismo nivel
     */
    public record Request(Federation federation, int year, String branch, int clubA, String categoryA, int clubB,
                          String categoryB, Scope scope) {

        /** El del año completo. */
        public Request(Federation federation, int year, String branch, int clubA, String categoryA, int clubB,
                       String categoryB) {
            this(federation, year, branch, clubA, categoryA, clubB, categoryB, Scope.SEASON);
        }
    }

    /** Un período del comparativo, para elegirlo: "OPENING", "Apertura". */
    public record PeriodOption(String value, String label) {
    }

    /**
     * Los períodos de la categoría en el año: cada torneo que tenga (Apertura, Clausura), los últimos 5 partidos y el año
     * completo.
     */
    public List<PeriodOption> periods(Federation fed, int year, String level, String branch) {
        Category category = catalog.category(fed, level, branch);
        List<PeriodOption> out = new ArrayList<>();
        catalog.competitions(fed, year).stream().filter(c -> category.equals(c.category()))
                .map(Competition::tournament).filter(Objects::nonNull).distinct().sorted()
                .forEach(t -> out.add(new PeriodOption(t.name(), t.label())));
        out.add(new PeriodOption(Scope.LAST_5.name(), ComparisonCalc.period(Scope.LAST_5, year)));
        out.add(new PeriodOption(Scope.SEASON.name(), "Año completo"));
        return out;
    }

    /** Un equipo validado: el club, la categoría y la opción elegida (con la letra del equipo). */
    public record Team(ClubView club, Category category, CategoryOption option) {
    }

    /** Un comparativo pedido: se arma aparte y queda listo con su PDF, su Excel y el HTML para verlo en pantalla. */
    public static final class Job implements ReadyReport {
        private final UUID id = UUID.randomUUID();
        private final String owner;
        private volatile Status status = Status.PENDING;
        private volatile String message = "En cola";
        private volatile byte[] pdf;
        private volatile String html;
        private volatile byte[] xlsx;
        private volatile String fileName;
        /** Lo que corre al quedar listo (guardarlo en el historial), antes de que se vea como listo. */
        private final List<Runnable> onReady = new ArrayList<>();
        /** Un lock (no synchronized): lo que corre guarda en la base y no conviene fijar el hilo virtual. */
        private final ReentrantLock readyLock = new ReentrantLock();

        Job(String owner) {
            this.owner = owner;
        }

        public UUID id() { return id; }
        public Status status() { return status; }
        public String message() { return message; }
        @Override public byte[] pdf() { return pdf; }
        @Override public String html() { return html; }
        @Override public byte[] xlsx() { return xlsx; }
        @Override public String fileName() { return fileName; }

        /** El nombre del archivo con otra extensión ("xlsx"). */
        public String fileName(String extension) {
            return fileName.substring(0, fileName.lastIndexOf('.') + 1) + extension;
        }

        @Override
        public void whenReady(Runnable action) {
            readyLock.lock();
            try {
                if (status != Status.READY) {
                    onReady.add(action);
                    return;
                }
            } finally {
                readyLock.unlock();
            }
            action.run();
        }

        void update(Status status, String message) {
            if (status == Status.READY) {
                readyLock.lock();
                try {
                    onReady.forEach(ReadyReport::runQuietly);
                    onReady.clear();
                    this.message = message;
                    this.status = status;
                } finally {
                    readyLock.unlock();
                }
                return;
            }
            this.status = status;
            this.message = message;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ComparisonService.class);

    private final CatalogService catalog;
    private final CategoryExtractor extractor;
    private final PdfRenderer renderer;
    private final ExcelExporter excel;
    private final Clock clock;
    private final Cache<UUID, Job> jobs = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ComparisonService(CatalogService catalog, SidgadClient sidgad, PdfRenderer renderer, ExcelExporter excel,
                             Clock clock) {
        this.catalog = catalog;
        this.extractor = new CategoryExtractor(catalog, sidgad, clock);
        this.renderer = renderer;
        this.excel = excel;
        this.clock = clock;
    }

    /** Valida (falla rápido) y encola el comparativo. Solo el dueño lo ve. */
    public Job submit(Request r, String owner) {
        List<Team> teams = validate(r);
        Job job = new Job(owner);
        jobs.put(job.id(), job);
        executor.submit(() -> run(job, r, teams));
        return job;
    }

    public Optional<Job> find(UUID id, String owner) {
        return Optional.ofNullable(jobs.getIfPresent(id)).filter(j -> Objects.equals(j.owner, owner));
    }

    private void run(Job job, Request r, List<Team> teams) {
        try {
            Comparison c = compare(r, teams, m -> job.update(Status.EXTRACTING, m));
            job.update(Status.RENDERING, "Generando el PDF");
            job.html = renderer.compareHtml(c);
            job.pdf = renderer.comparePdf(c);
            job.xlsx = excel.compare(c);
            job.fileName = fileName(teams.get(0), teams.get(1));
            job.update(Status.READY, "Comparativo listo");
        } catch (RuntimeException e) {
            log.error("Falló el comparativo {}", job.id(), e);
            job.update(Status.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * "RinkReports-Femenino-Primera-CAVS-vs-HURACAN.pdf": rama, categoría y los dos clubes (con la letra del equipo, si
     * tiene: "CAVSB").
     */
    static String fileName(Team a, Team b) {
        return FileNames.of("pdf", FileNames.part(a.category().branchLabel()), FileNames.part(a.category().levelLabel()),
                FileNames.club(a.option().teamKey(a.club().code())), "vs",
                FileNames.club(b.option().teamKey(b.club().code())));
    }

    /** Valida los dos equipos (falla rápido, con un mensaje para mostrar) y devuelve lo que se va a comparar. */
    public List<Team> validate(Request r) {
        Federation fed = r.federation();
        if (!catalog.available(fed, r.year())) {
            throw new IllegalArgumentException(CatalogService.unavailableMessage(fed, r.year()));
        }
        CategoryOption oa = CategoryOption.parse(r.categoryA());
        CategoryOption ob = CategoryOption.parse(r.categoryB());
        if (!oa.level().equals(ob.level())) {
            throw new IllegalArgumentException("Los dos equipos tienen que ser de la misma categoría.");
        }
        if (r.clubA() == r.clubB() && oa.key().equals(ob.key())) {
            throw new IllegalArgumentException("Elegí dos equipos distintos.");
        }
        List<Team> teams = List.of(team(fed, r.year(), r.clubA(), oa, r.branch()),
                team(fed, r.year(), r.clubB(), ob, r.branch()));
        // En un torneo, los dos tienen que jugarlo.
        if (r.scope().tournament() != null) {
            for (Team t : teams) {
                if (catalog.competitionsFor(fed, t.club().id(), t.category(), r.scope().tournament(), r.year()).isEmpty()) {
                    throw new IllegalArgumentException(t.club().name() + " no juega el " + r.scope().tournament().label()
                            + " en " + ReportService.label(t.category(), t.option()) + " en " + r.year() + ".");
                }
            }
        }
        return teams;
    }

    private Team team(Federation fed, int year, int clubId, CategoryOption option, String branch) {
        ClubView club = catalog.club(fed, clubId, year);
        Category category = catalog.category(fed, option.level(), branch);
        boolean exists = club.categories().stream()
                .anyMatch(l -> l.key().equals(option.key()) && l.branches().contains(category.branch()));
        if (!exists || catalog.competitionsFor(fed, club.id(), category, null, year).isEmpty()) {
            throw new IllegalArgumentException(club.name() + " no tiene equipo en "
                    + ReportService.label(category, option) + " en " + year + ".");
        }
        return new Team(club, category, option);
    }

    /** Valida, lee los dos equipos en paralelo y arma el comparativo. */
    public Comparison compare(Request r, Consumer<String> progress) {
        return compare(r, validate(r), progress);
    }

    private Comparison compare(Request r, List<Team> teams, Consumer<String> progress) {
        ReportService.Request season = new ReportService.Request(0, null, r.branch(), Scope.SEASON, r.year(),
                r.federation());
        List<ComparisonCalc.Input> inputs = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        java.time.Instant dataAsOf = null;   // la más vieja de los dos
        progress.accept("Leyendo los dos equipos");
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CategoryExtractor.Read>> reads = teams.stream()
                    .map(t -> io.submit(() -> extractor.read(season, t.club(), t.category(), m -> { })))
                    .toList();
            for (int i = 0; i < teams.size(); i++) {
                Team t = teams.get(i);
                CategoryExtractor.Read read = await(reads.get(i));
                inputs.add(new ComparisonCalc.Input(t.club(), t.option().teamKey(t.club().code()), read.data(),
                        read.logo(), read.byMatch()));
                read.notPublished().forEach(n -> notPublished.add(t.club().name() + ": " + n));
                if (read.dataAsOf() != null && (dataAsOf == null || read.dataAsOf().isBefore(dataAsOf))) {
                    dataAsOf = read.dataAsOf();
                }
            }
        }
        Team a = teams.getFirst();
        String category = a.category().levelLabel() + " " + a.category().branchLabel();
        return ComparisonCalc.build(r.federation(), r.year(), category, r.scope(), inputs.get(0), inputs.get(1),
                LocalDate.now(clock), notPublished).withDataAsOf(dataAsOf);
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido leyendo los equipos", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
