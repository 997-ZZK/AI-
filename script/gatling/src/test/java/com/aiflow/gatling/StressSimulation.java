package com.aiflow.gatling;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

/** S2: 200 independent submitted sessions; engine worker count is recorded separately. */
public final class StressSimulation extends Simulation {
    public StressSimulation() {
        SyntheticChatFlow flow = new SyntheticChatFlow("s2", 200, 1);
        setUp(flow.buildScenario().injectOpen(atOnceUsers(flow.users())))
                .protocols(flow.protocol())
                .assertions(global().failedRequests().count().is(0L));
    }
}
