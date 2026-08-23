package ru.cloudjava.loadtest;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class Constants {

    public static final Random RANDOM = ThreadLocalRandom.current();
    public static final String KEYCLOAK_URL = "http://127.0.0.1:8080/realms/cloud-java/protocol/openid-connect/token";
    public static final String CLIENT_ID = "cloud-java-gateway";
    public static final String CLIENT_SECRET = "iaDMVOKEGssvW5XRaaqZN4EO3lkvdRu6";
    public static final String GATEWAY_ROOT_URL = "http://127.0.0.1:9099";
    public static final String MAX = "max";
    public static final String JANE = "jane";
    public static final String PASSWORD = "password";

    public static final String MENU_AGGREGATE_URL = GATEWAY_ROOT_URL + "/v1/menu-aggregate/";
    public static final String MENU_AGGREGATE_LIST_URL = GATEWAY_ROOT_URL + "/v1/menu-aggregate?category=DRINKS";
    public static final String CREATE_ORDER_URL = GATEWAY_ROOT_URL + "/v1/menu-orders";

    static final int RPS = 2000;
    static final Duration PEAK_PHASE_DURATION = Duration.ofMinutes(10);
    static final int HTTP_REQUEST_TIMEOUT_MILLIS = 5000;
}
