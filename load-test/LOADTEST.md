# Нагрузочное тестирование с Gatling

## Содержание

1. [Введение в нагрузочное тестирование](#1-введение-в-нагрузочное-тестирование)
2. [Что такое Gatling](#2-что-такое-gatling)
3. [Ключевые концепции Gatling Java DSL](#3-ключевые-концепции-gatling-java-dsl)
   - 3.1 [Simulation — точка входа](#31-simulation--точка-входа)
   - 3.2 [HttpProtocolBuilder — настройка HTTP](#32-httpprotocolbuilder--настройка-http)
   - 3.3 [ScenarioBuilder — сценарий пользователя](#33-scenariobuilder--сценарий-пользователя)
   - 3.4 [ChainBuilder — цепочка действий](#34-chainbuilder--цепочка-действий)
   - 3.5 [Injection — профили нагрузки](#35-injection--профили-нагрузки)
   - 3.6 [Session — состояние виртуального пользователя](#36-session--состояние-виртуального-пользователя)
   - 3.7 [Checks — проверки ответа](#37-checks--проверки-ответа)
4. [Структура проекта и настройка Gradle](#4-структура-проекта-и-настройка-gradle)
5. [Общие константы](#5-общие-константы)
6. [Профиль нагрузки, используемый в тестах](#6-профиль-нагрузки-используемый-в-тестах)
7. [Скрипт MenuAggregateByIdLoadTest](#7-скрипт-menuaggregatebyidloadtest)
8. [Скрипт MenuAggregateListLoadTest](#8-скрипт-menuaggregatelistloadtest)
9. [Скрипт OrdersServiceLoadTest](#9-скрипт-ordersserviceloadtest)
10. [Запуск тестов](#10-запуск-тестов)
11. [Интерпретация HTML-отчёта](#11-интерпретация-html-отчёта)

---

## 1. Введение в нагрузочное тестирование

Нагрузочное тестирование — это процесс проверки поведения системы под различными уровнями нагрузки. Оно позволяет ответить на вопросы:

- **Сколько запросов в секунду** выдерживает сервис без деградации?
- **Какова задержка (latency)** при нормальной и пиковой нагрузке?
- **Где находится точка отказа** — при каком RPS (requests per second) начинают появляться ошибки или задержки выходят за допустимые пределы?
- **Как система ведёт себя во времени** — не происходит ли утечек памяти или деградации после длительной работы под нагрузкой?

### Виды нагрузочных тестов

| Вид теста | Описание |
|---|---|
| **Load test** | Проверка системы при ожидаемой рабочей нагрузке |
| **Stress test** | Постепенное увеличение нагрузки выше нормы до нахождения предела |
| **Spike test** | Резкий скачок нагрузки — имитация внезапного наплыва пользователей |
| **Soak test** | Длительная работа под нормальной нагрузкой для выявления утечек ресурсов |
| **Endurance test** | Сочетание soak и load: длительная работа под высокой нагрузкой |

Скрипты в данном проекте реализуют **stress test**: нагрузка планомерно возрастает от минимума до пика (2000 пользователей в секунду), после чего удерживается длительное время.

---

## 2. Что такое Gatling

**Gatling** — это инструмент нагрузочного тестирования с открытым исходным кодом, написанный на Scala. Тесты описываются на JVM-совместимом DSL (Domain-Specific Language) — в данном проекте используется **Java DSL**, доступный начиная с Gatling 3.7.

Версия Gatling в проекте: **3.14.9** (определяется версией Gradle-плагина `io.gatling.gradle:3.14.9.8`, где `3.14.9` — версия Gatling, `.8` — итерация плагина).

### Почему Gatling, а не JMeter или k6?

- **Код, а не XML/JSON**: сценарии описываются на Java — они читаемы, версионируются и рефакторятся как обычный код.
- **Асинхронная архитектура**: Gatling использует Netty под капотом, что позволяет одному потоку обслуживать тысячи виртуальных пользователей без расходования памяти на каждый поток.
- **HTML-отчёты**: встроенная генерация интерактивных отчётов с перцентилями, гистограммами и временными рядами.
- **Поддержка Gradle/Maven**: простая интеграция в существующий Java-проект.

---

## 3. Ключевые концепции Gatling Java DSL

### 3.1 Simulation — точка входа

`Simulation` — это корневой класс любого нагрузочного теста в Gatling. Каждый тестовый файл расширяет `io.gatling.javaapi.core.Simulation` и в конструкторе вызывает метод `setUp(...)`, который соединяет все части теста воедино.

```java
public class MyLoadTest extends Simulation {

    public MyLoadTest() {
        HttpProtocolBuilder protocol = http.baseUrl("http://example.com");

        PopulationBuilder population = scenario("My Scenario")
                .exec(http("GET /api").get("/api").check(status().is(200)))
                .injectOpen(constantUsersPerSec(10).during(Duration.ofSeconds(30)));

        setUp(population.protocols(protocol));
    }
}
```

Метод `setUp` принимает один или несколько `PopulationBuilder` (сценариев с профилями нагрузки). Если передаётся несколько, они выполняются **параллельно** — каждый со своими виртуальными пользователями.

### 3.2 HttpProtocolBuilder — настройка HTTP

`HttpProtocolBuilder` создаётся статическим методом `http` из `io.gatling.javaapi.http.HttpDsl` и конфигурирует общие параметры HTTP для всех запросов в сценарии.

```java
HttpProtocolBuilder protocol = http
        .baseUrl("http://localhost:9099")         // базовый URL — добавляется к относительным путям
        .maxConnectionsPerHost(100)               // максимум 100 TCP-соединений к одному хосту
        .shareConnections();                      // виртуальные пользователи делят пул соединений
```

**`maxConnectionsPerHost(100)`** — ограничивает количество одновременных TCP-соединений к одному хосту. Без этого параметра каждый виртуальный пользователь может создавать своё соединение, что ведёт к исчерпанию портов при большом числе пользователей.

**`shareConnections()`** — по умолчанию в Gatling каждый виртуальный пользователь имеет собственный пул соединений. Вызов `shareConnections()` переключает режим: все пользователи сценария **разделяют единый пул**. Это реалистичнее имитирует нагрузку через балансировщик, где соединения переиспользуются.

Протокол привязывается к сценарию одним из двух способов:

```java
// Способ 1: через PopulationBuilder.protocols()
population.protocols(protocol)

// Способ 2: через setUp вторым аргументом (глобально для всех сценариев)
setUp(population).protocols(protocol)
```

### 3.3 ScenarioBuilder — сценарий пользователя

`ScenarioBuilder` создаётся функцией `scenario("Name")` из `io.gatling.javaapi.core.CoreDsl`. Сценарий описывает **последовательность действий одного виртуального пользователя**: запросы, паузы, условия, циклы.

```java
ScenarioBuilder myScenario = scenario("User Journey")
        .exec(step1())
        .exec(step2())
        .pause(Duration.ofSeconds(1));
```

Метод `.exec()` принимает `ChainBuilder` или `SessionAction` (лямбду над `Session`). Несколько вызовов `.exec()` выполняются **последовательно**.

После описания действий к сценарию применяется **профиль нагрузки** через `.injectOpen(...)` или `.injectClosed(...)` — так `ScenarioBuilder` превращается в `PopulationBuilder`, который передаётся в `setUp`.

### 3.4 ChainBuilder — цепочка действий

`ChainBuilder` — это переиспользуемый строительный блок, содержащий одно или несколько действий. Обычно описывается отдельным методом для читаемости:

```java
private ChainBuilder callMyEndpoint() {
    return exec(
            http("Request Name")           // имя запроса в отчёте
                    .get("/api/resource")  // HTTP-метод и путь
                    .check(status().is(200))
    );
}
```

`ChainBuilder` можно компоновать:

```java
exec(step1()).exec(step2()).pause(Duration.ofSeconds(1))
```

#### HTTP-запрос

Gatling поддерживает все стандартные HTTP-методы:

```java
http("name").get("/path")
http("name").post("/path").body(StringBody("{...}"))
http("name").post("/path").formParam("key", "value")
http("name").put("/path")
http("name").delete("/path")
```

**`.formParam("key", "value")`** — добавляет поле формы (Content-Type: `application/x-www-form-urlencoded`). Каждый вызов добавляет одно поле. Именно этот метод используется для передачи учётных данных в Keycloak.

**`StringBody("...")`** — устанавливает тело запроса из строки. Поддерживает Java text blocks (многострочные строки). Content-Type нужно выставить явно через `.header("Content-Type", "application/json")`.

### 3.5 Injection — профили нагрузки

Профиль нагрузки определяет, **как и когда** виртуальные пользователи появляются в системе. Gatling предлагает две модели.

#### Открытая модель (Open model) — управление интенсивностью

В открытой модели (`injectOpen`) вы задаёте **скорость появления** новых пользователей (arrival rate). Система не ограничивает их накопление — если сервер медленно отвечает, виртуальные пользователи копятся.

```java
.injectOpen(
    constantUsersPerSec(10).during(Duration.ofSeconds(30)),  // 10 пользователей/сек, 30 секунд
    rampUsersPerSec(10).to(100).during(Duration.ofSeconds(20)) // плавный рост от 10 до 100/сек за 20 сек
)
```

**`constantUsersPerSec(rate).during(duration)`** — генерирует новых виртуальных пользователей с постоянной частотой `rate` пользователей в секунду на протяжении `duration`. Параметр `rate` — это `double`, т.е. допустимо значение `0.5` (один пользователь каждые 2 секунды).

**`rampUsersPerSec(from).to(to).during(duration)`** — линейно увеличивает (или уменьшает) скорость появления пользователей от `from` до `to` за время `duration`. Используется для плавного наращивания нагрузки без резких скачков.

#### Закрытая модель (Closed model) — управление параллелизмом

В закрытой модели (`injectClosed`) задаётся **количество одновременно активных** виртуальных пользователей. Новый пользователь появляется только когда предыдущий завершил итерацию. Эта модель лучше имитирует пул соединений или очередь обработки.

```java
.injectClosed(
    constantConcurrentUsers(50).during(Duration.ofSeconds(30)),
    rampConcurrentUsers(50).to(200).during(Duration.ofSeconds(20))
)
```

В данном проекте используется исключительно **открытая модель**, что характерно для стресс-тестирования публичных HTTP API.

### 3.6 Session — состояние виртуального пользователя

`Session` — это изолированный контейнер данных для каждого виртуального пользователя. Значения попадают в сессию через `saveAs` в проверках и извлекаются через методы `session.getString(key)`, `session.getInt(key)` и т.д.

Прямое манипулирование сессией выполняется через лямбду в `exec`:

```java
exec(session -> {
    String token = session.getString("access_token");
    // ... любая логика
    return session.set("myKey", computedValue); // immutable: возвращаем новую сессию
})
```

`Session` в Gatling **иммутабельна** — метод `set` возвращает новый экземпляр с добавленным значением, поэтому лямбда обязана вернуть `session` (или изменённый экземпляр).

### 3.7 Checks — проверки ответа

Checks валидируют ответ сервера и при необходимости извлекают данные в сессию. Проверки добавляются методом `.check(...)` к HTTP-запросу.

```java
http("name")
    .get("/api")
    .check(status().is(200))                          // проверка HTTP-статуса
    .check(jsonPath("$.access_token").saveAs("token")) // извлечение значения из JSON
```

**`status().is(200)`** — проверяет, что HTTP-статус ответа равен 200. Если проверка не прошла, запрос помечается как **failed** в отчёте. Несколько проверок задаются несколькими вызовами `.check(...)` или через запятую: `.check(status().is(200), jsonPath("$.id").exists())`.

**`jsonPath("$.access_token").saveAs("access_token")`** — применяет JSONPath-выражение к телу ответа и сохраняет найденное значение в сессию под ключом `"access_token"`. После этого значение доступно как `session.getString("access_token")`.

---

## 4. Структура проекта и настройка Gradle

```
load-test/
├── build.gradle                          # конфигурация Gradle-плагина Gatling
├── settings.gradle
├── gradlew / gradlew.bat
└── src/
    └── gatling/
        └── java/
            └── ru/cloudjava/loadtest/
                ├── Constants.java        # общие константы
                ├── MenuAggregateByIdLoadTest.java
                ├── MenuAggregateListLoadTest.java
                └── OrdersServiceLoadTest.java
```

Исходный код Gatling-тестов располагается в `src/gatling/java/` — это **стандартный source set** плагина `io.gatling.gradle`, отдельный от основного `src/main/java/` и тестового `src/test/java/`.

### build.gradle

```groovy
plugins {
    id 'java'
    id 'io.gatling.gradle' version '3.14.9.8'
}

dependencies {
    gatlingImplementation 'io.gatling:gatling-core'
    gatlingImplementation 'io.gatling:gatling-http'
    gatlingImplementation 'io.gatling.highcharts:gatling-charts-highcharts'
}
```

Зависимости подключены в конфигурацию **`gatlingImplementation`** — это специальная конфигурация, созданная плагином для изоляции Gatling-зависимостей от зависимостей основного проекта.

| Артефакт | Назначение |
|---|---|
| `gatling-core` | Ядро Gatling: DSL, симуляции, профили нагрузки, сессия |
| `gatling-http` | HTTP-протокол: HttpProtocolBuilder, HttpDsl, checks |
| `gatling-charts-highcharts` | Генератор интерактивных HTML-отчётов на базе Highcharts |

Версии `gatling-core` и `gatling-http` не указаны явно — они управляются BOM (Bill of Materials), который плагин подтягивает автоматически, обеспечивая совместимость.

---

## 5. Общие константы

Файл `Constants.java` централизует все URL и учётные данные:

```java
public class Constants {
    public static final Random RANDOM = ThreadLocalRandom.current();

    // Keycloak token endpoint
    public static final String KEYCLOAK_URL =
        "http://keycloak:8080/realms/cloud-java/protocol/openid-connect/token";
    public static final String CLIENT_ID     = "cloud-java-gateway";
    public static final String CLIENT_SECRET = "iaDMVOKEGssvW5XRaaqZN4EO3lkvdRu6";

    // API Gateway
    public static final String GATEWAY_ROOT_URL = "http://localhost:9099";

    // Тестовые пользователи (realm cloud-java)
    public static final String MAX     = "max";
    public static final String JANE    = "jane";
    public static final String JOHN    = "john";
    public static final String MICHAEL = "michael";
    public static final String PASSWORD = "password";

    // Тестируемые эндпоинты
    public static final String MENU_AGGREGATE_URL      = GATEWAY_ROOT_URL + "/v1/menu-aggregate/";
    public static final String MENU_AGGREGATE_LIST_URL = GATEWAY_ROOT_URL + "/v1/menu-aggregate?category=DRINKS";
    public static final String CREATE_ORDER_URL        = GATEWAY_ROOT_URL + "/v1/menu-orders";
}
```

**`ThreadLocalRandom.current()`** — потокобезопасный генератор случайных чисел. Gatling выполняет виртуальных пользователей в многопоточном окружении (хотя основной движок асинхронный, некоторые части работают в пуле потоков), поэтому использование `ThreadLocalRandom` вместо `new Random()` исключает конкуренцию за состояние генератора.

Обратите внимание: Keycloak доступен по имени хоста `keycloak` (не `localhost`), что предполагает запуск тестов внутри той же Docker-сети, что и остальные сервисы. Gateway, напротив, доступен через `localhost:9099` — проброшенный порт.

---

## 6. Профиль нагрузки, используемый в тестах

Все три тестируемых сценария используют один и тот же профиль нагрузки в открытой модели:

```java
.injectOpen(
    constantUsersPerSec(2).during(Duration.ofSeconds(10)),     // [1] прогрев
    rampUsersPerSec(2).to(100).during(Duration.ofSeconds(5)),  // [2] рост до 100/с
    constantUsersPerSec(100).during(Duration.ofSeconds(30)),   // [3] стабильная нагрузка
    rampUsersPerSec(100).to(200).during(Duration.ofSeconds(5)),// [4] рост до 200/с
    constantUsersPerSec(200).during(Duration.ofSeconds(30)),   // [5] повышенная нагрузка
    rampUsersPerSec(200).to(2000).during(Duration.ofSeconds(10)),// [6] быстрый рост
    constantUsersPerSec(2000).during(Duration.ofMinutes(10))   // [7] пиковая нагрузка
)
```

Фазы выполняются **последовательно** одна за другой:

```
RPS
2000 |                                          ___________________
     |                                         /
 200 |                    ____________________/
     |                   /
 100 |      ____________/
     |     /
   2 |____/
     |-----|-----|----------|-----|------------|------|------------|
       10с   5с     30с      5с      30с        10с      10мин
```

| Фаза | Длительность | RPS | Назначение |
|---|---|---|---|
| Прогрев | 10 с | 2/с | Инициализация пулов соединений, прогрев JIT-компилятора |
| Рост 1 | 5 с | 2 → 100/с | Плавное наращивание нагрузки |
| Стабильная нагрузка 1 | 30 с | 100/с | Сбор метрик при умеренной нагрузке |
| Рост 2 | 5 с | 100 → 200/с | Дальнейшее наращивание |
| Стабильная нагрузка 2 | 30 с | 200/с | Сбор метрик при повышенной нагрузке |
| Быстрый рост | 10 с | 200 → 2000/с | Имитация пикового наплыва |
| Пиковая нагрузка | 10 мин | 2000/с | Длительный стресс-тест на пиковых значениях |

Общая продолжительность теста: **≈ 11,5 минут** (690 секунд).

Пиковое значение 2000 RPS — это значительная нагрузка. Способность системы её выдержать зависит от пропускной способности сети, ресурсов сервера и горизонтального масштабирования сервисов.

---

## 7. Скрипт MenuAggregateByIdLoadTest

### Что тестируется

Эндпоинт `GET /v1/menu-aggregate/{id}` — получение агрегированной информации о пункте меню по его идентификатору. В тесте идентификатор генерируется случайно в диапазоне **[1, 3]**, что имитирует обращение к трём разным записям.

Эндпоинт **публичный** — не требует аутентификации.

### Полный исходный код

```java
public class MenuAggregateByIdLoadTest extends Simulation {

    public MenuAggregateByIdLoadTest() {
        HttpProtocolBuilder protocolBuilder = http
                .baseUrl(GATEWAY_ROOT_URL)
                .maxConnectionsPerHost(100)
                .shareConnections();
        setUp(getMenuAggregateBuilder().protocols(protocolBuilder));
    }

    private PopulationBuilder getMenuAggregateBuilder() {
        return scenario("Get menu aggregate")
                .exec(callGetMenuAggregate())
                .injectOpen(
                        constantUsersPerSec(2).during(Duration.ofSeconds(10)),
                        rampUsersPerSec(2).to(100).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(100).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(100).to(200).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(200).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(200).to(2000).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(2000).during(Duration.ofMinutes(10))
                );
    }

    private ChainBuilder callGetMenuAggregate() {
        return exec(
                http("Call Get Menu Aggregate")
                        .get(MENU_AGGREGATE_URL + RANDOM.nextInt(1, 4))
                        .check(status().is(200)));
    }
}
```

### Разбор по шагам

#### Шаг 1: Конфигурация HTTP-протокола

```java
HttpProtocolBuilder protocolBuilder = http
        .baseUrl(GATEWAY_ROOT_URL)          // "http://localhost:9099"
        .maxConnectionsPerHost(100)
        .shareConnections();
```

Базовый URL — API Gateway на `localhost:9099`. Все запросы маршрутизируются через Gateway, который перенаправляет их к соответствующему микросервису.

#### Шаг 2: Описание HTTP-запроса

```java
private ChainBuilder callGetMenuAggregate() {
    return exec(
            http("Call Get Menu Aggregate")
                    .get(MENU_AGGREGATE_URL + RANDOM.nextInt(1, 4))
                    .check(status().is(200)));
}
```

Ключевой момент: **`RANDOM.nextInt(1, 4)`** вычисляется **один раз при инициализации класса**, а не при каждом запросе. Это означает, что на протяжении всего теста все виртуальные пользователи будут обращаться к одному и тому же идентификатору (например, `/v1/menu-aggregate/2`), выбранному случайно в начале.

`RANDOM.nextInt(1, 4)` возвращает случайное целое из диапазона `[1, 4)`, то есть `1`, `2` или `3`. Это соответствует трём возможным записям в тестовой базе данных.

Если бы требовалось случайное значение **на каждый запрос**, нужно было бы использовать Gatling Expression Language или лямбду сессии:

```java
// Пример: случайный id на каждый запрос
.get(session -> MENU_AGGREGATE_URL + RANDOM.nextInt(1, 4))
```

#### Шаг 3: Привязка протокола к сценарию

```java
setUp(getMenuAggregateBuilder().protocols(protocolBuilder));
```

Метод `.protocols(protocolBuilder)` связывает HTTP-конфигурацию с `PopulationBuilder`. В `setUp` передаётся один сценарий.

---

## 8. Скрипт MenuAggregateListLoadTest

### Что тестируется

Эндпоинт `GET /v1/menu-aggregate?category=DRINKS` — получение списка агрегированных пунктов меню по категории. Фиксированный параметр `category=DRINKS` означает, что тест всегда запрашивает одну и ту же выборку.

Эндпоинт **публичный** — не требует аутентификации.

### Полный исходный код

```java
public class MenuAggregateListLoadTest extends Simulation {

    public MenuAggregateListLoadTest() {
        HttpProtocolBuilder protocolBuilder = http
                .baseUrl(GATEWAY_ROOT_URL)
                .maxConnectionsPerHost(100)
                .shareConnections();
        setUp(getMenuAggregateListBuilder().protocols(protocolBuilder));
    }

    private PopulationBuilder getMenuAggregateListBuilder() {
        return scenario("Get menu aggregate list")
                .exec(callGetMenuAggregateList())
                .injectOpen(
                        constantUsersPerSec(2).during(Duration.ofSeconds(10)),
                        rampUsersPerSec(2).to(100).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(100).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(100).to(200).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(200).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(200).to(2000).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(2000).during(Duration.ofMinutes(10))
                );
    }

    private ChainBuilder callGetMenuAggregateList() {
        return exec(
                http("Call Get Menu Aggregate List")
                        .get(MENU_AGGREGATE_LIST_URL)
                        .check(status().is(200)));
    }
}
```

### Разбор по шагам

#### Структура запроса

```java
private ChainBuilder callGetMenuAggregateList() {
    return exec(
            http("Call Get Menu Aggregate List")
                    .get(MENU_AGGREGATE_LIST_URL)   // GET /v1/menu-aggregate?category=DRINKS
                    .check(status().is(200)));
}
```

`MENU_AGGREGATE_LIST_URL` содержит строку запроса прямо в URL: `http://localhost:9099/v1/menu-aggregate?category=DRINKS`. Gatling поддерживает query-параметры как часть строки URL или через отдельный метод `.queryParam("key", "value")` — оба варианта эквивалентны.

#### Отличие от MenuAggregateByIdLoadTest

| Аспект | ById | List |
|---|---|---|
| Эндпоинт | `/v1/menu-aggregate/{id}` | `/v1/menu-aggregate?category=DRINKS` |
| Параметр | Случайный id в `[1,3]`, вычисляется при старте | Фиксированная категория `DRINKS` |
| Ожидаемый ответ | Один объект | Список объектов |
| Нагрузка на БД | Точечная выборка по PK | Фильтрация по индексу категории |

Эта пара тестов позволяет сравнить производительность точечного чтения (by ID) и выборки по категории (list), что полезно для выявления неоптимальных запросов к базе данных или отсутствия индексов.

---

## 9. Скрипт OrdersServiceLoadTest

### Что тестируется

Эндпоинт `POST /v1/menu-orders` — создание заказа. В отличие от предыдущих сценариев, этот эндпоинт **защищён** и требует валидного JWT Bearer-токена, выданного Keycloak.

### Архитектура теста

Сценарий `OrdersServiceLoadTest` состоит из **пяти параллельно выполняемых сценариев**:

```
┌─────────────────────────────────────────────────────────────┐
│  setUp(                                                      │
│    maxTokenScenario     → инжекция 1 user/s на 10 минут     │
│    janeTokenScenario    → инжекция 1 user/s на 10 минут     │
│    johnTokenScenario    → инжекция 1 user/s на 10 минут     │
│    michaelTokenScenario → инжекция 1 user/s на 10 минут     │
│    createOrderBuilder   → stress-профиль с Bearer-токеном   │
│  )                                                           │
└─────────────────────────────────────────────────────────────┘
```

Четыре сценария-обновителя токенов работают **параллельно** с основным сценарием создания заказа. Они периодически получают свежие JWT-токены и сохраняют их в общую потокобезопасную структуру — `ConcurrentHashMap`.

### Полный исходный код

```java
public class OrdersServiceLoadTest extends Simulation {

    private static final ConcurrentMap<String, String> TOKEN_BY_USERNAME =
            new ConcurrentHashMap<>();

    public OrdersServiceLoadTest() {
        HttpProtocolBuilder httpProtocolWithAccessToken = http
                .baseUrl(GATEWAY_ROOT_URL)
                .maxConnectionsPerHost(100)
                .shareConnections()
                .authorizationHeader(session -> getRandomUserToken());

        ScenarioBuilder maxTokenScenario     = getTokenBuilder(MAX);
        ScenarioBuilder janeTokenScenario    = getTokenBuilder(JANE);
        ScenarioBuilder johnTokenScenario    = getTokenBuilder(JOHN);
        ScenarioBuilder michaelTokenScenario = getTokenBuilder(MICHAEL);

        setUp(
                maxTokenScenario.injectOpen(constantUsersPerSec(1).during(Duration.ofMinutes(10))),
                janeTokenScenario.injectOpen(constantUsersPerSec(1).during(Duration.ofMinutes(10))),
                johnTokenScenario.injectOpen(constantUsersPerSec(1).during(Duration.ofMinutes(10))),
                michaelTokenScenario.injectOpen(constantUsersPerSec(1).during(Duration.ofMinutes(10))),
                createOrderBuilder(httpProtocolWithAccessToken)
        );
    }

    private String getRandomUserToken() {
        Collection<String> values = TOKEN_BY_USERNAME.values();
        int tokenIdx = values.size() == 1 ? 1 : RANDOM.nextInt(0, values.size());
        return values.stream().skip(tokenIdx).findFirst().orElseThrow();
    }

    private ScenarioBuilder getTokenBuilder(String userName) {
        return scenario("Get Token %s".formatted(userName))
                .exec(
                        http("Get Keycloak Token")
                                .post(KEYCLOAK_URL)
                                .formParam("username", userName)
                                .formParam("password", PASSWORD)
                                .formParam("grant_type", "password")
                                .formParam("client_id", CLIENT_ID)
                                .formParam("client_secret", CLIENT_SECRET)
                                .formParam("scope", "openid roles")
                                .check(status().is(200))
                                .check(jsonPath("$.access_token").saveAs("access_token")))
                .exec(session -> {
                    TOKEN_BY_USERNAME.put(userName, "Bearer " + session.getString("access_token"));
                    return session;
                })
                .pause(Duration.ofMinutes(4));
    }

    private PopulationBuilder createOrderBuilder(HttpProtocolBuilder protocolBuilder) {
        return scenario("Create order")
                .exec(callCreateOrder())
                .injectOpen(
                        constantUsersPerSec(2).during(Duration.ofSeconds(10)),
                        rampUsersPerSec(2).to(100).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(100).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(100).to(200).during(Duration.ofSeconds(5)),
                        constantUsersPerSec(200).during(Duration.ofSeconds(30)),
                        rampUsersPerSec(200).to(2000).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(2000).during(Duration.ofMinutes(10))
                ).protocols(protocolBuilder);
    }

    private ChainBuilder callCreateOrder() {
        return exec(
                http("Create Menu Order")
                        .post(CREATE_ORDER_URL)
                        .header("Content-Type", "application/json")
                        .body(StringBody("""
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
                        .check(status().is(201))
        );
    }
}
```

### Разбор по шагам

#### Шаг 1: Общее хранилище токенов

```java
private static final ConcurrentMap<String, String> TOKEN_BY_USERNAME =
        new ConcurrentHashMap<>();
```

`ConcurrentHashMap` — это потокобезопасная реализация `Map`. Она используется здесь намеренно: **разные виртуальные пользователи** (и даже разные сценарии) выполняются в разных потоках Gatling и параллельно читают/пишут эту структуру. `HashMap` в такой ситуации привела бы к гонкам данных.

Поле объявлено `static`, чтобы хранилище было **общим для всех экземпляров** — хотя при одном запуске `Simulation` создаётся ровно один раз, `static` явно документирует глобальность данной структуры.

Ключ — имя пользователя (`"max"`, `"jane"` и т.д.), значение — готовый заголовок `"Bearer <jwt_token>"`.

#### Шаг 2: Динамический заголовок авторизации

```java
HttpProtocolBuilder httpProtocolWithAccessToken = http
        .baseUrl(GATEWAY_ROOT_URL)
        .maxConnectionsPerHost(100)
        .shareConnections()
        .authorizationHeader(session -> getRandomUserToken());
```

**`.authorizationHeader(Function<Session, String>)`** — устанавливает заголовок `Authorization` динамически: переданная лямбда вызывается **для каждого HTTP-запроса** каждого виртуального пользователя. Это позволяет каждому запросу получить актуальный токен из общей карты.

Статическая альтернатива `.authorizationHeader("Bearer <token>")` не подходит, так как токены обновляются периодически и у каждого пользователя свой.

#### Шаг 3: Сценарий получения токена

```java
private ScenarioBuilder getTokenBuilder(String userName) {
    return scenario("Get Token %s".formatted(userName))
            .exec(
                    http("Get Keycloak Token")
                            .post(KEYCLOAK_URL)                          // Token endpoint Keycloak
                            .formParam("username", userName)             // логин
                            .formParam("password", PASSWORD)             // пароль
                            .formParam("grant_type", "password")         // OAuth2 grant type
                            .formParam("client_id", CLIENT_ID)           // идентификатор клиента
                            .formParam("client_secret", CLIENT_SECRET)   // секрет клиента
                            .formParam("scope", "openid roles")          // запрашиваемые scopes
                            .check(status().is(200))
                            .check(jsonPath("$.access_token").transform(token -> {
                                TOKEN_BY_USERNAME.put(userName, "Bearer " + token);
                                return token;
                            })))
            .pause(Duration.ofMinutes(4));
}
```

Это реализация **OAuth2 Resource Owner Password Credentials (ROPC)** flow. Хотя ROPC не рекомендуется для production-сценариев с участием пользователей, он удобен для нагрузочного тестирования, так как не требует интерактивного браузера.

Параметры формы (`formParam`) отправляются как `application/x-www-form-urlencoded` — стандарт для Keycloak token endpoint.

**Порядок действий в сценарии:**

1. `POST /realms/cloud-java/protocol/openid-connect/token` → получение JWT
2. `jsonPath("$.access_token").transform(token -> { ... })` → извлечение access_token из JSON-ответа и немедленное сохранение в общую `ConcurrentHashMap` с добавлением префикса `"Bearer "` — без промежуточного `saveAs` и лишней записи в сессию Gatling
3. `.pause(Duration.ofMinutes(4))` → пауза 4 минуты перед следующей итерацией

**Жизненный цикл токена:**

JWT-токены в Keycloak имеют ограниченное время жизни (обычно 5 минут для access_token). После паузы в 4 минуты сценарий снова запрашивает токен. Поскольку `injectOpen(constantUsersPerSec(1).during(Duration.ofMinutes(10)))` генерирует по 1 виртуальному пользователю в секунду, через 4 минуты накапливается 240 параллельных "пользователей", каждый из которых находится в паузе и затем снова делает запрос — это обеспечивает непрерывное обновление токенов.

#### Шаг 4: Случайный выбор токена

```java
private String getRandomUserToken() {
    Collection<String> values = TOKEN_BY_USERNAME.values();
    int tokenIdx = values.size() == 1 ? 1 : RANDOM.nextInt(0, values.size());
    return values.stream().skip(tokenIdx).findFirst().orElseThrow();
}
```

Метод случайно выбирает токен из доступных. `values.stream().skip(tokenIdx).findFirst()` — это идиома случайного выбора из коллекции через `skip`: пропускаем `tokenIdx` элементов и берём первый оставшийся.

Важно понимать: метод вызывается **в момент отправки запроса**, когда карта уже заполнена (сценарии токенов запускаются параллельно с `createOrderBuilder`). Если карта ещё пуста — `orElseThrow()` бросит исключение, и запрос будет помечен как failed.

Использование четырёх разных пользователей имитирует реальный сценарий, где запросы приходят от множества различных учётных записей, что проверяет корректность авторизации на уровне gateway и backend.

#### Шаг 5: Запрос создания заказа

```java
private ChainBuilder callCreateOrder() {
    return exec(
            http("Create Menu Order")
                    .post(CREATE_ORDER_URL)
                    .header("Content-Type", "application/json")
                    .body(StringBody("""
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
                    .check(status().is(201))
    );
}
```

**`StringBody(...)`** — задаёт тело запроса из строки. В Java 15+ поддерживаются text blocks (тройные кавычки `"""`), что делает JSON читаемым без экранирования. `StringBody` в Gatling также поддерживает **Expression Language** — в строке можно использовать `${variableName}` для подстановки значений из сессии, хотя в данном тесте это не используется.

Ожидаемый статус — **201 Created**, что соответствует REST-конвенции для успешного создания ресурса.

Заголовок `Authorization` добавляется **автоматически** через `httpProtocolWithAccessToken.authorizationHeader(...)`, определённый на уровне протокола, — явно указывать его в запросе не нужно.

---

## 10. Запуск тестов

Тесты запускаются через Gradle-плагин `io.gatling.gradle`. Плагин добавляет задачу `gatlingRun`.

### Запуск конкретного скрипта

```bash
# Запуск MenuAggregateByIdLoadTest
./gradlew gatlingRun-ru.cloudjava.loadtest.MenuAggregateByIdLoadTest

# Запуск MenuAggregateListLoadTest
./gradlew gatlingRun-ru.cloudjava.loadtest.MenuAggregateListLoadTest

# Запуск OrdersServiceLoadTest
./gradlew gatlingRun-ru.cloudjava.loadtest.OrdersServiceLoadTest
```

### Запуск всех скриптов последовательно

```bash
./gradlew gatlingRun
```

Без указания конкретного класса плагин запускает **все** классы, расширяющие `Simulation`, найденные в `src/gatling/java/`.

### Расположение отчётов

После завершения теста Gatling выводит путь к HTML-отчёту:

```
Reports generated in:
  build/reports/gatling/menuaggregatebyidloadtest-<timestamp>/index.html
```

Каждый запуск создаёт отдельную директорию с временной меткой.

### Требования для запуска

Перед запуском тестов убедитесь, что:

1. Запущена вся инфраструктура через Docker Compose (Gateway, Keycloak, микросервисы)
2. Для `OrdersServiceLoadTest` — Keycloak realm `cloud-java` содержит пользователей `max`, `jane`, `john`, `michael` с паролем `password` и клиент `cloud-java-gateway` с указанным `client_secret`
3. В Docker-сети разрешена маршрутизация к `keycloak:8080` (для `OrdersServiceLoadTest` тест должен запускаться в той же Docker-сети или с соответствующим hosts-записью)

---

## 11. Интерпретация HTML-отчёта

Gatling генерирует интерактивный HTML-отчёт. Ключевые разделы:

### Global Information

Сводная таблица по всем запросам:

| Метрика | Описание |
|---|---|
| **Total** | Общее число запросов за тест |
| **OK** | Число успешных запросов (прошли все checks) |
| **KO** | Число неуспешных запросов |
| **% KO** | Процент ошибок — целевой показатель: < 1% |
| **min / 50th / 75th / 95th / 99th / max** | Перцентили времени ответа в миллисекундах |

**Перцентили** — ключевой инструмент анализа:
- **p50 (медиана)** — половина запросов быстрее этого значения
- **p95** — 95% запросов быстрее; стандартный SLO-показатель
- **p99** — 99% запросов быстрее; показывает "хвост" распределения
- **max** — наихудший запрос; часто аномалия, не репрезентативная для системы

### Response Time Distribution

Гистограмма распределения времён ответа. Здоровая система показывает унимодальное распределение (один пик) с коротким правым хвостом. Двойной пик или длинный хвост — признак проблемы (например, GC-паузы или тайм-аутов соединений).

### Response Time over Simulation

График изменения времени ответа во времени. Позволяет увидеть момент деградации — при каком уровне нагрузки (см. фазы профиля) начинается рост latency. Горизонтальная линия вплоть до фазы пиковой нагрузки — признак горизонтально масштабируемой системы.

### Requests per Second

График реального RPS, который видел сервер. Должен соответствовать заданному профилю нагрузки. Расхождение говорит о том, что Gatling не успевает генерировать нагрузку (проблема на стороне клиента) или Gateway ограничивает входящий трафик.

### Анализ результатов скриптов

| Скрипт | Хороший результат | Тревожный признак |
|---|---|---|
| `MenuAggregateByIdLoadTest` | p99 < 200 мс при 2000 RPS | Рост p99 после фазы 200 RPS — отсутствие индекса или кэша |
| `MenuAggregateListLoadTest` | p99 < 300 мс при 2000 RPS | Высокий p99 при низком RPS — тяжёлый запрос или N+1 |
| `OrdersServiceLoadTest` | p99 < 500 мс, KO < 1% | Всплески KO — истёкшие токены или перегрузка очереди |