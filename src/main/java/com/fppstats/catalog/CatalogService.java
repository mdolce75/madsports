package com.fppstats.catalog;

import com.fppstats.config.FppProperties;
import com.fppstats.sidgad.SidgadClient;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Competition.Category;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.sidgad.parse.SidgadParser;
import com.fppstats.stats.TeamKeys;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * Catálogo por año de la federación configurada (FPP = id_fede_3), limitado a las categorías relevadas
 * ({@code fpp.sidgad.levels}: de Infantil para arriba) y a los años ofrecidos ({@code fpp.sidgad.years}).
 * El año se traduce al id de temporada de SIDGAD con el selector de temporadas del portal.
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
     * @param year      año consultado
     * @param available false si SIDGAD no tiene competiciones FPP para ese año (en ese caso {@code message} lo explica)
     * @param years     años que se ofrecen
     */
    public record CatalogView(int year, boolean available, String message, List<Integer> years,
                              List<LevelView> categories, List<String> branches, List<ClubView> clubs) {
    }

    private static final List<String> BRANCHES = List.of("MASCULINO", "FEMENINO");

    private final SidgadClient client;
    private final int federationId;
    private final List<String> levels;
    private final List<Integer> years;

    public CatalogService(SidgadClient client, FppProperties props) {
        this.client = client;
        this.federationId = props.sidgad().federationId();
        this.levels = props.sidgad().levels();
        this.years = List.copyOf(props.sidgad().years());
    }

    /** Años que se ofrecen (configuración {@code fpp.sidgad.years}). */
    public List<Integer> years() {
        return years;
    }

    /** El año más reciente de los ofrecidos que ya tiene datos; si ninguno tiene, el primero. */
    public int defaultYear() {
        return years.stream().sorted(Comparator.reverseOrder()).filter(this::available).findFirst()
                .orElse(years.getFirst());
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

    public boolean available(int year) {
        return !competitions(year).isEmpty();
    }

    public static String unavailableMessage(int year) {
        return "No hay información de la FPP para el año " + year + " todavía.";
    }

    /**
     * Competiciones FPP del año en las categorías relevadas. El año se traduce al id de temporada de SIDGAD con
     * el selector de temporadas del portal; si el año no existe ahí, no hay competiciones.
     */
    public List<Competition> competitions(int year) {
        Integer seasonId = SidgadParser.seasons(client.portal()).get(year);
        if (seasonId == null) {
            return List.of();
        }
        return competitions(SidgadParser.catalog(client.catalog()), federationId, levels, seasonId);
    }

    static List<Competition> competitions(List<Competition> all, int federationId, List<String> levels, int seasonId) {
        return all.stream()
                .filter(c -> c.federationId() == federationId && c.seasonId() == seasonId && c.category() != null)
                .filter(c -> levels.contains(c.category().level()))
                .sorted(Comparator.comparingInt(Competition::id))
                .toList();
    }

    public CatalogView view(int year) {
        List<Competition> comps = competitions(year);
        if (comps.isEmpty()) {
            return new CatalogView(year, false, unavailableMessage(year), years, List.of(), BRANCHES, List.of());
        }
        return view(comps, levels, year, years);
    }

    static CatalogView view(List<Competition> comps, List<String> levels, int year, List<Integer> years) {
        Map<Integer, List<TeamRef>> teamsByClub = new HashMap<>();
        for (Competition c : comps) {
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
                levelViews(comps.stream().map(Competition::category).toList(), levels), BRANCHES, clubs);
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
    public List<Competition> competitionsFor(int clubId, Category category, Competition.Torneo torneo, int year) {
        return competitions(year).stream()
                .filter(c -> c.category().equals(category))
                .filter(c -> torneo == null || c.torneo() == torneo)
                .filter(c -> c.teams().stream().anyMatch(t -> t.clubId() == clubId))
                .toList();
    }

    public ClubView club(int clubId, int year) {
        CatalogView view = view(year);
        if (!view.available()) {
            throw new IllegalArgumentException(view.message());
        }
        return view.clubs().stream().filter(c -> c.id() == clubId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("El club " + clubId + " no juega en la FPP en " + year + "."));
    }

    /** Valida y arma la categoría pedida; rechaza Mini/Promo u otras fuera de lo relevado. */
    public Category category(String level, String branch) {
        String l = level == null ? "" : level.trim().toUpperCase();
        String b = branch == null ? "" : branch.trim().toUpperCase();
        if (!levels.contains(l)) {
            throw new IllegalArgumentException("Categoría no relevada: " + level + ". Válidas: " + levels);
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
