package com.aiflow.gatling;

import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

/** S1: ten independent sessions, ten sequential rounds per session by default. */
public final class BaselineSimulation extends Simulation {
    public BaselineSimulation() {
        SyntheticChatFlow flow = new SyntheticChatFlow("s1", 10, 10);
        setUp(flow.buildScenario().injectOpen(atOnceUsers(flow.users())))
                .protocols(flow.protocol())
                .assertions(global().failedRequests().count().is(0L));
    }
}
