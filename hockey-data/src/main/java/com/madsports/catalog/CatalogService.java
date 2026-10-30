package com.madsports.catalog;

import com.madsports.sidgad.SidgadProperties;
import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Competition.Category;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.TeamRef;
import com.madsports.source.HockeySources;
import com.madsports.stats.TeamKeys;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catálogo por año y federación ({@link Federation}: FPP = id_fede_3, AMP = id_fede_4), limitado a sus categorías
 * relevadas (de Infantil para arriba) y a los años ofrecidos ({@code madsports.sidgad.years}). Las competiciones salen
 * de la fuente de datos de cada federación ({@link HockeySources}).
 */
@Service
public class CatalogService {

    /** Categoría (nivel) y las ramas en que el club tiene equipo. */
    /**
     * Opción de categoría. Si el club tiene equipo A y B en la categoría, hay una opción por equipo
     * ("Infantil A", "Infantil B"; clave "INFANTIL:A"); si tiene uno solo, una opción ("Infantil", clave "INFANTIL").
     *
     * @param branches ramas en las que existe ese equipo
     */
    public record LevelView(String key, String label, List<String> branches) {
    }

    /** Categoría elegida en la web: nivel y, si el club tiene más de un equipo, la letra ("INFANTIL:B"). */
    public record CategoryOption(String level, String letter) {

        public static CategoryOption parse(String key) {
            String k = key == null ? "" : key.trim().toUpperCase();
            int i = k.indexOf(':');
            return i < 0 ? new CategoryOption(k, null) : new CategoryOption(k.substring(0, i), k.substring(i + 1));
        }

        public String key() {
            return letter == null ? level : level + ":" + letter;
        }

        /** Clave del equipo en el informe: "CAVS B" (o "CAVS" si es el único equipo). */
        public String teamKey(String clubCode) {
            return letter == null ? clubCode : clubCode + " " + letter;
        }
    }

    public record ClubView(int id, String code, String name, List<LevelView> categories) {
    }

    /**
     * @param year       año consultado
     * @param available  false si SIDGAD no tiene competiciones de la federación ese año ({@code message} lo explica)
     * @param years      años que se ofrecen
     * @param federacion sigla de la federación (FPP, AMP)
     */
    public record CatalogView(int year, boolean available, String message, List<Integer> years,
                              List<LevelView> categories, List<String> branches, List<ClubView> clubs,
                              String federation) {

        CatalogView withFederation(Federation f) {
            return new CatalogView(year, available, message, years, categories, branches, clubs, f.name());
        }
    }

    private static final List<String> BRANCHES = List.of("MASCULINO", "FEMENINO");

    private final HockeySources sources;
    private final List<Integer> years;

    public CatalogService(HockeySources sources, SidgadProperties props) {
        this.sources = sources;
        this.years = List.copyOf(props.years());
    }

    /** Años que se ofrecen (configuración {@code madsports.sidgad.years}). */
    public List<Integer> years() {
        return years;
    }

    /** El año más reciente de los ofrecidos que ya tiene datos (de alguna federación); si ninguno tiene, el primero. */
    public int defaultYear() {
        return years.stream().sorted(Comparator.reverseOrder())
                .filter(y -> Arrays.stream(Federation.values()).filter(sources::serves).anyMatch(f -> available(f, y)))
                .findFirst().orElse(years.getFirst());
    }

    /** Valida que el año sea uno de los ofrecidos. */
    public int checkYear(Integer year) {
        if (year == null) {
            return defaultYear();
        }
        if (!years.contains(year)) {
            throw new IllegalArgumentException("Año no ofrecido: " + year + ". Válidos: " + years);
        }
        return year;
    }

    public boolean available(Federation f, int year) {
        return sources.serves(f) && !competitions(f, year).isEmpty();
    }

    public static String unavailableMessage(Federation f, int year) {
        return "No hay información de la " + f.name() + " para el año " + year + " todavía.";
    }

    /** Competiciones de la federación en el año (de su fuente de datos), en sus categorías relevadas. */
    public List<Competition> competitions(Federation f, int year) {
        return competitions(sources.of(f).competitions(f, year), f.levels());
    }

    static List<Competition> competitions(List<Competition> all, int federationId, List<String> levels, int seasonId) {
        return competitions(all.stream().filter(c -> c.federationId() == federationId && c.seasonId() == seasonId)
                .toList(), levels);
    }

    private static List<Competition> competitions(List<Competition> all, List<String> levels) {
        return all.stream()
                .filter(c -> c.category() != null && levels.contains(c.category().level()))
                .sorted(Comparator.comparingInt(Competition::id))
                .toList();
    }

    public CatalogView view(Federation f, int year) {
        List<Competition> comps = competitions(f, year);
        if (comps.isEmpty()) {
            return new CatalogView(year, false, unavailableMessage(f, year), years, List.of(), BRANCHES, List.of(),
                    f.name());
        }
        return view(comps, f.levels(), year, years).withFederation(f);
    }

    static CatalogView view(List<Competition> comps, List<String> levels, int year, List<Integer> years) {
        Map<Integer, List<TeamRef>> teamsByClub = new HashMap<>();
        // Los clubes son los de los torneos de la federación: las copas interprovinciales traen clubes de otras.
        for (Competition c : comps.stream().filter(c -> c.tournament() != Competition.Tournament.CUP).toList()) {
            for (TeamRef t : c.teams()) {
                teamsByClub.computeIfAbsent(t.clubId(), k -> new ArrayList<>()).add(t);
            }
        }
        List<ClubView> clubs = teamsByClub.entrySet().stream()
                .filter(e -> e.getKey() > 0)
                .map(e -> {
                    String code = mode(e.getValue(), t -> baseName(t.code()));
                    String name = mode(e.getValue(), t -> baseName(t.name()));
                    return new ClubView(e.getKey(), code, name, teamOptions(comps, e.getKey(), code, name, levels));
                })
                .sorted(Comparator.comparing(ClubView::name))
                .toList();
        return new CatalogView(year, true, null, years,
                levelViews(comps.stream().map(Competition::category).toList(), levels), BRANCHES, clubs, null);
    }

    /** Agrupa por nivel (en el orden configurado) con sus ramas (masculino primero). */
    /** Opciones de categoría del club: una por equipo (A, B) en cada nivel, con las ramas en que existe. */
    private static List<LevelView> teamOptions(List<Competition> comps, int clubId, String clubCode, String clubName,
                                               List<String> levels) {
        Map<String, List<String>> branchesByOption = new TreeMap<>(Comparator
                .comparingInt((String k) -> levels.indexOf(CategoryOption.parse(k).level()))
                .thenComparing(k -> k));
        Map<Category, Boolean> severalTeams = new HashMap<>();
        for (Competition c : comps) {
            long count = c.teams().stream()
                    .filter(t -> t.clubId() == clubId && TeamKeys.belongs(t, clubCode, clubName)).count();
            severalTeams.merge(c.category(), count > 1, Boolean::logicalOr);
        }
        for (Competition c : comps) {
            List<TeamRef> own = c.teams().stream()
                    .filter(t -> t.clubId() == clubId && TeamKeys.belongs(t, clubCode, clubName)).toList();
            for (String key : TeamKeys.keys(own, clubCode, severalTeams.get(c.category())).values()) {
                String letter = key.length() > clubCode.length() ? key.substring(clubCode.length()).trim() : null;
                String option = new CategoryOption(c.category().level(), letter).key();
                List<String> branches = branchesByOption.computeIfAbsent(option, k -> new ArrayList<>());
                if (!branches.contains(c.category().branch())) {
                    branches.add(c.category().branch());
                }
            }
        }
        return branchesByOption.entrySet().stream()
                .map(e -> {
                    CategoryOption o = CategoryOption.parse(e.getKey());
                    String label = new Category(o.level(), "X").levelLabel() + (o.letter() == null ? "" : " " + o.letter());
                    return new LevelView(e.getKey(), label,
                            e.getValue().stream().sorted(Comparator.comparingInt(BRANCHES::indexOf)).toList());
                })
                .toList();
    }

    private static List<LevelView> levelViews(List<Category> categories, List<String> levels) {
        Map<Integer, List<String>> byLevel = new TreeMap<>();
        for (Category c : categories) {
            List<String> branches = byLevel.computeIfAbsent(levels.indexOf(c.level()), k -> new ArrayList<>());
            if (!branches.contains(c.branch())) {
                branches.add(c.branch());
            }
        }
        return byLevel.entrySet().stream()
                .map(e -> {
                    String level = levels.get(e.getKey());
                    List<String> branches = e.getValue().stream().sorted(Comparator.comparingInt(BRANCHES::indexOf)).toList();
                    return new LevelView(level, new Category(level, branches.getFirst()).levelLabel(), branches);
                })
                .toList();
    }

    /**
     * Competiciones de la categoría en las que juega el club en el año (todas las divisiones).
     *
     * @param torneo Apertura o Clausura; {@code null} = ambos
     */
    public List<Competition> competitionsFor(Federation f, int clubId, Category category, Competition.Tournament tournament,
                                             int year) {
        return competitions(f, year).stream()
                .filter(c -> c.category().equals(category))
                .filter(c -> tournament == null || c.tournament() == tournament)
                .filter(c -> c.teams().stream().anyMatch(t -> t.clubId() == clubId))
                .toList();
    }

    public ClubView club(Federation f, int clubId, int year) {
        CatalogView view = view(f, year);
        if (!view.available()) {
            throw new IllegalArgumentException(view.message());
        }
        return view.clubs().stream().filter(c -> c.id() == clubId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "El club " + clubId + " no juega en la " + f.name() + " en " + year + "."));
    }

    /** Valida y arma la categoría pedida; rechaza Mini/Promo u otras fuera de lo relevado en la federación. */
    public Category category(Federation f, String level, String branch) {
        String l = level == null ? "" : level.trim().toUpperCase();
        String b = branch == null ? "" : branch.trim().toUpperCase();
        if (!f.levels().contains(l)) {
            throw new IllegalArgumentException("Categoría no relevada: " + level + ". Válidas: " + f.levels());
        }
        if (!BRANCHES.contains(b)) {
            throw new IllegalArgumentException("Rama inválida: " + branch + ". Válidas: " + BRANCHES);
        }
        return new Category(l, b);
    }

    /** "CAVS A" -> "CAVS", "VELEZ B" -> "VELEZ", "GEBAB" queda igual (lo resuelve la moda). */
    static String baseName(String s) {
        return s.replaceAll("\\s+[A-D]$", "").trim();
    }

    private static <T> String mode(List<T> items, Function<T, String> key) {
        Map<String, Long> counts = items.stream().map(key).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }
}
