package ru.cloudjava.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static ru.cloudjava.loadtest.Constants.*;

public class OrdersServiceLoadTest extends Simulation {

    static final Duration LOAD_PHASE_DURATION = PEAK_PHASE_DURATION.plusMinutes(2);
    private static final Duration TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(3);

    private static final String[] USERNAMES = new String[]{MAX, JANE};
    private static final ConcurrentMap<String, String> TOKEN_BY_USERNAME = new ConcurrentHashMap<>();

    public OrdersServiceLoadTest() {
        HttpProtocolBuilder ordersProtocol = http.baseUrl(GATEWAY_ROOT_URL)
            .shareConnections()
            .authorizationHeader(session -> getRandomUserToken());

        HttpProtocolBuilder keycloakProtocol = http.shareConnections();

        ScenarioBuilder warmupTokens = scenario("Warmup tokens")
            .exec(fetchToken(MAX))
            .exec(fetchToken(JANE));

        ScenarioBuilder refreshTokens = scenario("Refresh tokens")
            .during(LOAD_PHASE_DURATION)
            .on(exec(fetchToken(MAX))
                .exec(fetchToken(JANE))
                .pause(TOKEN_REFRESH_INTERVAL));

        setUp(
            warmupTokens.injectOpen(atOnceUsers(1)).protocols(keycloakProtocol)
                .andThen(
                    refreshTokens.injectOpen(atOnceUsers(1)).protocols(keycloakProtocol),
                    createOrderBuilder().protocols(ordersProtocol)
                )
        ).maxDuration(LOAD_PHASE_DURATION.plusMinutes(1));
    }

    /**
     * Возвращает токен случайного пользователя. После прогрева мапа гарантированно непустая, поэтому здесь нет ожидания — только
     * быстрый доступ. Если токена нет (прогрев не отработал) — падаем с понятной ошибкой.
     */
    private String getRandomUserToken() {
        String userName = USERNAMES[RANDOM.nextInt(0, USERNAMES.length)];
        String token = TOKEN_BY_USERNAME.get(userName);
        if (token == null) {
            throw new IllegalStateException("Нет токена для пользователя " + userName
                + ". Прогрев токенов (warmup) не выполнен или завершился ошибкой.");
        }
        return "Bearer " + token;
    }

    /**
     * Цепочка получения access token из Keycloak и сохранения его в общую мапу. Используется и в прогреве, и в фоновом обновлении.
     */
    private ChainBuilder fetchToken(String userName) {
        return exec(
            http("Get Keycloak Token " + userName)
                .post(KEYCLOAK_URL)
                .requestTimeout(HTTP_REQUEST_TIMEOUT_MILLIS)
                .formParam("username", userName)
                .formParam("password", PASSWORD)
                .formParam("grant_type", "password")
                .formParam("client_id", CLIENT_ID)
                .formParam("client_secret", CLIENT_SECRET)
                .formParam("scope", "openid roles")
                .check(status().is(200))
                .check(jsonPath("$.access_token")
//                    .transform(token -> {
//                        TOKEN_BY_USERNAME.put(userName, token);
//                        return token;
//                    })));
                    .saveAs("access_token"))
        ).exec(session -> {
            TOKEN_BY_USERNAME.put(userName, session.getString("access_token"));
            return session;
        });
    }

    private PopulationBuilder createOrderBuilder() {
        return scenario("Create order")
            .exec(callCreateOrder())
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

    private ChainBuilder callCreateOrder() {
        return exec(http("Create Menu Order").post(CREATE_ORDER_URL)
            .requestTimeout(HTTP_REQUEST_TIMEOUT_MILLIS)
            .header("Content-Type", "application/json").body(StringBody("""
                {
                    "nameToQuantity": {
                        "One": 10,
                        "Two": 20,
                        "Three": 30
                    },
                    "address": {
                        "city": "Moscow",
                        "street": "Street",
                        "house": 1,
                        "apartment": 1
                    }
                }
                """))
            .check(status().is(201)));
    }
}