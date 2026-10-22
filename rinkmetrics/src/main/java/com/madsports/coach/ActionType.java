package com.madsports.coach;

import java.util.Set;

/**
 * Acciones que carga el DT durante el partido, cada una con los resultados que admite (vacío = sin resultado) y si
 * suma un gol al equipo o al rival. Las del arco (atajada, gol recibido, libre y penal en contra) se cargan al
 * arquero en cancha.
 */
public enum ActionType {

    TURNOVER(Set.of(Outcome.OWN_HALF, Outcome.RIVAL_HALF), false),
    RECOVERY(Set.of(Outcome.OWN_HALF, Outcome.RIVAL_HALF), false),
    SHOT(Set.of(Outcome.GOAL, Outcome.NO_GOAL), false),
    SAVE(Set.of(), true),
    GOAL_CONCEDED(Set.of(), true),
    FREE_HIT_FOR(Set.of(Outcome.GOAL, Outcome.NO_GOAL), false),
    FREE_HIT_AGAINST(Set.of(Outcome.GOAL, Outcome.SAVED), true),
    PENALTY_FOR(Set.of(Outcome.GOAL, Outcome.NO_GOAL), false),
    PENALTY_AGAINST(Set.of(Outcome.GOAL, Outcome.SAVED), true),
    FOUL(Set.of(Outcome.COMMITTED, Outcome.RECEIVED), false),
    CARD(Set.of(Outcome.YELLOW, Outcome.BLUE, Outcome.RED), false);

    private final Set<Outcome> outcomes;
    private final boolean goalkeeper;

    ActionType(Set<Outcome> outcomes, boolean goalkeeper) {
        this.outcomes = outcomes;
        this.goalkeeper = goalkeeper;
    }

    /** ¿Se carga al arquero en cancha? */
    public boolean goalkeeper() {
        return goalkeeper;
    }

    /** ¿El resultado corresponde a la acción? (null solo en las que no llevan resultado.) */
    public boolean accepts(Outcome outcome) {
        return outcomes.isEmpty() ? outcome == null : outcome != null && outcomes.contains(outcome);
    }

    /** ¿Es un gol del equipo del DT? */
    public boolean isGoalFor(Outcome outcome) {
        return outcome == Outcome.GOAL && (this == SHOT || this == FREE_HIT_FOR || this == PENALTY_FOR);
    }

    /** ¿Es un gol del rival? */
    public boolean isGoalAgainst(Outcome outcome) {
        return this == GOAL_CONCEDED
                || outcome == Outcome.GOAL && (this == FREE_HIT_AGAINST || this == PENALTY_AGAINST);
    }
}
