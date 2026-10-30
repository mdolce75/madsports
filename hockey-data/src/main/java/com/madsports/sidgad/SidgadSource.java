package com.madsports.sidgad;

import com.madsports.sidgad.model.Competition;
import com.madsports.sidgad.model.Federation;
import com.madsports.sidgad.model.Models.LineupPlayer;
import com.madsports.sidgad.model.Models.Match;
import com.madsports.sidgad.model.Models.MatchEvent;
import com.madsports.sidgad.model.Models.PlayerStats;
import com.madsports.sidgad.model.Models.SeasonMatch;
import com.madsports.sidgad.parse.SidgadParser;
import com.madsports.source.DataSource;
import com.madsports.source.GameSheet;
import com.madsports.source.HockeySource;
import com.madsports.source.Standings;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** SIDGAD como fuente de datos: los fragmentos HTML de {@link SidgadClient} interpretados con {@link SidgadParser}. */
@Component
public class SidgadSource implements HockeySource {

    private final SidgadClient client;

    public SidgadSource(SidgadClient client) {
        this.client = client;
    }

    @Override
    public DataSource kind() {
        return DataSource.SIDGAD;
    }

    /** El año se traduce al id de temporada con el selector de temporadas del portal; si no está, no hay ninguna. */
    @Override
    public List<Competition> competitions(Federation f, int year) {
        Integer seasonId = SidgadParser.seasons(client.portal()).get(year);
        if (seasonId == null) {
            return List.of();
        }
        return SidgadParser.catalog(client.catalog()).stream()
                .filter(c -> c.federationId() == f.id() && c.seasonId() == seasonId)
                .sorted(Comparator.comparingInt(Competition::id))
                .toList();
    }

    @Override
    public Optional<Standings> standings(Competition c) {
        return published(client.standings(c.id()),
                html -> new Standings(SidgadParser.standings(html), SidgadParser.reportDate(html)));
    }

    @Override
    public Optional<List<Match>> calendar(Competition c) {
        return published(client.calendar(c.id()), SidgadParser::calendar);
    }

    @Override
    public Optional<List<PlayerStats>> playerStats(Competition c) {
        return published(client.playerStats(c.id()), SidgadParser::playerStats);
    }

    /** El acta se interpreta en cada llamada a {@code events()} o {@code lineups()}, como antes en cada informe. */
    @Override
    public GameSheet gameSheet(Competition c, int matchId) {
        return new Sheet(client.gameSheet(c.id(), matchId));
    }

    @Override
    public List<SeasonMatch> playerSeason(int playerId, int seasonId) {
        return SidgadParser.seasonMatches(client.playerSeason(playerId, seasonId));
    }

    @Override
    public List<SeasonMatch> playerSeason(int playerId, int seasonId, Instant notBefore) {
        return SidgadParser.seasonMatches(client.playerSeason(playerId, seasonId, notBefore));
    }

    @Override
    public byte[] clubLogo(String file) {
        return client.clubLogo(file);
    }

    @Override
    public void forClubReport(Runnable work) {
        client.forClubReport(work);
    }

    private static <T> Optional<T> published(String html, Function<String, T> parse) {
        return SidgadClient.NOT_PUBLISHED.equals(html) ? Optional.empty() : Optional.of(parse.apply(html));
    }

    /** Un acta de SIDGAD (vacía si no se publicó: los parsers devuelven listas vacías). */
    private record Sheet(String html) implements GameSheet {

        @Override
        public List<MatchEvent> events() {
            return SidgadParser.gameEvents(html);
        }

        @Override
        public List<LineupPlayer> lineups() {
            return SidgadParser.lineups(html);
        }
    }
}
