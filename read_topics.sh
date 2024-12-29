#!/bin/bash

################################
BOOTSTRAP_SERVERS=kafka:29092
SCHEMA_REGISTRY_URL=http://schema-registry:8081

echo -e "\n*** Sampling messages in Kafka topics ***\n"

echo -e "\n-----v1.public.orders_outbox topic-----"
docker compose exec connect kafka-avro-console-consumer --bootstrap-server $BOOTSTRAP_SERVERS --property schema.registry.url=$SCHEMA_REGISTRY_URL --from-beginning --timeout-ms 10000 --max-messages 5 --topic v1.public.orders_outbox

echo -e "\n-----v1.orders_dispatch topic-----"
docker compose exec connect kafka-avro-console-consumer --bootstrap-server $BOOTSTRAP_SERVERS --property schema.registry.url=$SCHEMA_REGISTRY_URL --from-beginning --timeout-ms 10000 --max-messages 5 --topic v1.orders_dispatch

