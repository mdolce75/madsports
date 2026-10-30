package com.madsports.source;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Una fuente de datos de hockey: lo que leen los informes de club y de jugador, el catálogo y el plantel, ya
 * interpretado. Cada federación elige la suya ({@link Federation#source()}, {@link HockeySources#of(Federation)}).
 * <p>
 * Lo que la fuente no publicó vuelve vacío ({@link Optional#empty()}, {@link GameSheet#EMPTY}, lista vacía) y el
 * informe sigue con lo que haya; cualquier otro error es una {@link SourceException}.
 */
public interface HockeySource {

    DataSource kind();

    /** Competiciones de la federación en el año, todas (la categoría es null si el nombre no se reconoce). */
    List<Competition> competitions(Federation f, int year);

    /** Tabla de posiciones; vacío si no está publicada. */
    Optional<Standings> standings(Competition c);

    /** Calendario y resultados; vacío si no está publicado. */
    Optional<List<Match>> calendar(Competition c);

    /** Estadísticas por jugador de la competición; vacío si no están publicadas. */
    Optional<List<PlayerStats>> playerStats(Competition c);

    /** Acta de un partido jugado ({@code matchId} = {@link Match#idp()}). */
    GameSheet gameSheet(Competition c, int matchId);

    /** Ficha de temporada de un jugador: sus partidos de cada competición, incluidos los que no tienen acta. */
    List<SeasonMatch> playerSeason(int playerId, int seasonId);

    /** La ficha de temporada, aceptando una guardada si se bajó desde {@code notBefore}. */
    List<SeasonMatch> playerSeason(int playerId, int seasonId, Instant notBefore);

    /** Escudo de un club ({@code file} como figura en sus equipos); null si no está: el informe sale sin él. */
    byte[] clubLogo(String file);

    /** Corre el informe del club completo (la fuente puede darle su propio cupo de pedidos). */
    default void forClubReport(Runnable work) {
        work.run();
    }
}
