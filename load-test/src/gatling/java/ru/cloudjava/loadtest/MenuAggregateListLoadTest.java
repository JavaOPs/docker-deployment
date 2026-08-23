package ru.cloudjava.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static ru.cloudjava.loadtest.Constants.*;

public class MenuAggregateListLoadTest extends Simulation {

    public MenuAggregateListLoadTest() {
        HttpProtocolBuilder protocolBuilder = http.baseUrl(GATEWAY_ROOT_URL)
            .shareConnections();
        setUp(getMenuAggregateListBuilder().protocols(protocolBuilder));
    }

    private PopulationBuilder getMenuAggregateListBuilder() {
        return scenario("Get menu aggregate list")
            .exec(callGetMenuAggregateList())
            .injectOpen(
                // Шаг 1: разгон до 100 RPS, держим 30 сек.
                constantUsersPerSec(2).during(Duration.ofSeconds(10)),
                rampUsersPerSec(2).to(100).during(Duration.ofSeconds(5)),
                constantUsersPerSec(100).during(Duration.ofSeconds(30)),

                // Шаг 2: разгон со 100 до 200 RPS, держим 30 сек.
                rampUsersPerSec(100).to(200).during(Duration.ofSeconds(5)),
                constantUsersPerSec(200).during(Duration.ofSeconds(30)),

                // Шаг 3: разгон с 200 до пиковых RPC за 10 секунд, держим нагрузку PEAK_PHASE_DURATION
                rampUsersPerSec(200).to(Constants.RPS).during(Duration.ofSeconds(10)),
                constantUsersPerSec(Constants.RPS).during(Constants.PEAK_PHASE_DURATION)
            );
    }

    private ChainBuilder callGetMenuAggregateList() {
        return exec(
            http("Call Get Menu Aggregate List")
                .get(MENU_AGGREGATE_LIST_URL)
                .requestTimeout(HTTP_REQUEST_TIMEOUT_MILLIS)
                .check(status().is(200)));
    }
}
