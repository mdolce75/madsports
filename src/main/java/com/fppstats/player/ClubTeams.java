package com.fppstats.player;

import com.fppstats.catalog.CatalogService.ClubView;
import com.fppstats.sidgad.model.Competition;
import com.fppstats.sidgad.model.Models.TeamRef;
import com.fppstats.stats.TeamKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Equipos de un club en las competiciones y nombre de la categoría de cada uno ("Infantil B Femenino"), con la
 * misma regla que el informe de club: A y B son equipos distintos, y la letra va solo si el club tiene más de un
 * equipo en esa categoría y rama en algún torneo del año.
 */
final class ClubTeams {

    private ClubTeams() {
    }

    /** Equipos del club en la competición (por club y por código o nombre: SIDGAD a veces repite logos). */
    static List<TeamRef> own(Competition c, ClubView club) {
        return c.teams().stream()
                .filter(t -> t.clubId() == club.id() && TeamKeys.belongs(t, club.code(), club.name()))
                .toList();
    }

    /** Clave de cada equipo del club ("CAVS B", o "CAVS" si es el único) por id de equipo de SIDGAD. */
    static Map<Integer, String> keys(List<Competition> comps, ClubView club) {
        Map<Integer, String> keys = new LinkedHashMap<>();
        Map<Competition.Category, List<Competition>> byCategory = comps.stream()
                .collect(Collectors.groupingBy(Competition::category, LinkedHashMap::new, Collectors.toList()));
        byCategory.forEach((category, list) -> {
            boolean several = list.stream().anyMatch(c -> own(c, club).size() > 1);
            list.forEach(c -> keys.putAll(TeamKeys.keys(own(c, club), club.code(), several)));
        });
        return keys;
    }

    /** "Infantil B Femenino" para el equipo con esa clave en esa competición. */
    static String label(Competition c, ClubView club, String key) {
        String letter = key.length() > club.code().length() ? key.substring(club.code().length()).trim() : "";
        return c.category().levelLabel() + (letter.isEmpty() ? "" : " " + letter) + " " + c.category().branchLabel();
    }

    /** Espera un pedido en paralelo; si falló, relanza el error original. */
    static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido leyendo SIDGAD", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
