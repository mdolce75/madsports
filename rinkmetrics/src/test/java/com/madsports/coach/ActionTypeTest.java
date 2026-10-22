package com.madsports.coach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionTypeTest {

    @Test
    void outcomesAcceptedByEachAction() {
        assertThat(ActionType.TURNOVER.accepts(Outcome.RIVAL_HALF)).isTrue();
        assertThat(ActionType.TURNOVER.accepts(Outcome.GOAL)).isFalse();
        assertThat(ActionType.TURNOVER.accepts(null)).isFalse();
        assertThat(ActionType.SAVE.accepts(null)).isTrue();
        assertThat(ActionType.SAVE.accepts(Outcome.GOAL)).isFalse();
        assertThat(ActionType.CARD.accepts(Outcome.BLUE)).isTrue();
        assertThat(ActionType.PENALTY_AGAINST.accepts(Outcome.SAVED)).isTrue();
    }

    @Test
    void scoreGoals() {
        assertThat(ActionType.SHOT.isGoalFor(Outcome.GOAL)).isTrue();
        assertThat(ActionType.FREE_HIT_FOR.isGoalFor(Outcome.GOAL)).isTrue();
        assertThat(ActionType.PENALTY_FOR.isGoalFor(Outcome.NO_GOAL)).isFalse();
        assertThat(ActionType.GOAL_CONCEDED.isGoalAgainst(null)).isTrue();
        assertThat(ActionType.PENALTY_AGAINST.isGoalAgainst(Outcome.GOAL)).isTrue();
        assertThat(ActionType.FREE_HIT_AGAINST.isGoalAgainst(Outcome.SAVED)).isFalse();
        assertThat(ActionType.SAVE.goalkeeper()).isTrue();
        assertThat(ActionType.SHOT.goalkeeper()).isFalse();
    }
}
