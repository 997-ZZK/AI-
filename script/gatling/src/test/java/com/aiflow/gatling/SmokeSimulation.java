package com.aiflow.gatling;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

/** S0: one synthetic user and one complete streamed run by default. */
public final class SmokeSimulation extends Simulation {
    public SmokeSimulation() {
        SyntheticChatFlow flow = new SyntheticChatFlow("s0", 1, 1);
        setUp(flow.buildScenario().injectOpen(atOnceUsers(flow.users())))
                .protocols(flow.protocol())
                .assertions(global().failedRequests().count().is(0L));
    }
}
