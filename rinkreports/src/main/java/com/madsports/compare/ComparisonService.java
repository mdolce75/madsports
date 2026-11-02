package com.madsports.compare;

import com.madsports.catalog.CatalogService;
import com.madsports.catalog.CatalogService.CategoryOption;
import com.madsports.catalog.CatalogService.ClubView;
import com.madsports.report.CategoryExtractor;
import com.madsports.report.ReportService;
import com.madsports.report.ReportService.Scope;
import com.madsports.sidgad.SidgadClient;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
                          String categoryB) {
    }

    /** Un equipo validado: el club, la categoría y la opción elegida (con la letra del equipo). */
    public record Team(ClubView club, Category category, CategoryOption option) {
    }

    private final CatalogService catalog;
    private final CategoryExtractor extractor;
    private final Clock clock;

    public ComparisonService(CatalogService catalog, SidgadClient sidgad, Clock clock) {
        this.catalog = catalog;
        this.extractor = new CategoryExtractor(catalog, sidgad, clock);
        this.clock = clock;
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
        return List.of(team(fed, r.year(), r.clubA(), oa, r.branch()), team(fed, r.year(), r.clubB(), ob, r.branch()));
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
        List<Team> teams = validate(r);
        ReportService.Request season = new ReportService.Request(0, null, r.branch(), Scope.SEASON, r.year(),
                r.federation());
        List<ComparisonCalc.Input> inputs = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        progress.accept("Leyendo los dos equipos");
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CategoryExtractor.Read>> reads = teams.stream()
                    .map(t -> io.submit(() -> extractor.read(season, t.club(), t.category(), m -> { })))
                    .toList();
            for (int i = 0; i < teams.size(); i++) {
                Team t = teams.get(i);
                CategoryExtractor.Read read = await(reads.get(i));
                inputs.add(new ComparisonCalc.Input(t.club(), t.option().teamKey(t.club().code()), read.data(),
                        read.logo()));
                read.notPublished().forEach(n -> notPublished.add(t.club().name() + ": " + n));
            }
        }
        Team a = teams.getFirst();
        String category = a.category().levelLabel() + " " + a.category().branchLabel();
        return ComparisonCalc.build(r.federation(), r.year(), category, inputs.get(0), inputs.get(1),
                LocalDate.now(clock), notPublished);
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
