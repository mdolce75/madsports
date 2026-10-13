package com.fppstats.catalog;

import com.fppstats.config.FppProperties;
import com.fppstats.sidgad.SidgadClient;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Competition.Category;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.sidgad.parse.SidgadParser;
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
 * Catálogo de la temporada activa de la federación configurada (FPP = id_fede_3), limitado a las
 * categorías relevadas ({@code fpp.sidgad.levels}: de Infantil para arriba).
 * La temporada activa es la de mayor id entre las competiciones de esa federación.
 */
@Service
public class CatalogService {

    /** Categoría (nivel) y las ramas en que el club tiene equipo. */
    public record LevelView(String key, String label, List<String> branches) {
    }

    public record ClubView(int id, String code, String name, List<LevelView> categories) {
    }

    public record CatalogView(int season, List<LevelView> categories, List<String> branches, List<ClubView> clubs) {
    }

    private static final List<String> BRANCHES = List.of("MASCULINO", "FEMENINO");

    private final SidgadClient client;
    private final int federationId;
    private final List<String> levels;

    public CatalogService(SidgadClient client, FppProperties props) {
        this.client = client;
        this.federationId = props.sidgad().federationId();
        this.levels = props.sidgad().levels();
    }

    /** Competiciones de la temporada activa en las categorías relevadas. */
    public List<Competition> activeCompetitions() {
        return activeCompetitions(SidgadParser.catalog(client.catalog()), federationId, levels);
    }

    static List<Competition> activeCompetitions(List<Competition> all, int federationId, List<String> levels) {
        List<Competition> fede = all.stream().filter(c -> c.federationId() == federationId).toList();
        int season = fede.stream().mapToInt(Competition::seasonId).max().orElse(0);
        return fede.stream()
                .filter(c -> c.seasonId() == season && c.category() != null)
                .filter(c -> levels.contains(c.category().level()))
                .sorted(Comparator.comparingInt(Competition::id))
                .toList();
    }

    public CatalogView view() {
        return view(activeCompetitions(), levels);
    }

    static CatalogView view(List<Competition> comps, List<String> levels) {
        Map<Integer, List<TeamRef>> teamsByClub = new HashMap<>();
        Map<Integer, List<Category>> categoriesByClub = new HashMap<>();
        for (Competition c : comps) {
            for (TeamRef t : c.teams()) {
                teamsByClub.computeIfAbsent(t.clubId(), k -> new ArrayList<>()).add(t);
                categoriesByClub.computeIfAbsent(t.clubId(), k -> new ArrayList<>()).add(c.category());
            }
        }
        List<ClubView> clubs = teamsByClub.entrySet().stream()
                .filter(e -> e.getKey() > 0)
                .map(e -> new ClubView(e.getKey(),
                        mode(e.getValue(), t -> baseName(t.code())),
                        mode(e.getValue(), t -> baseName(t.name())),
                        levelViews(categoriesByClub.get(e.getKey()), levels)))
                .sorted(Comparator.comparing(ClubView::name))
                .toList();
        int season = comps.isEmpty() ? 0 : comps.getFirst().seasonId();
        return new CatalogView(season, levelViews(comps.stream().map(Competition::category).toList(), levels),
                BRANCHES, clubs);
    }

    /** Agrupa por nivel (en el orden configurado) con sus ramas (masculino primero). */
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

    /** Competiciones (Apertura y Clausura, todas las divisiones) de la categoría en las que juega el club. */
    public List<Competition> competitionsFor(int clubId, Category category) {
        return activeCompetitions().stream()
                .filter(c -> c.category().equals(category))
                .filter(c -> c.teams().stream().anyMatch(t -> t.clubId() == clubId))
                .toList();
    }

    public ClubView club(int clubId) {
        return view().clubs().stream().filter(c -> c.id() == clubId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Club desconocido en FPP: " + clubId));
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
