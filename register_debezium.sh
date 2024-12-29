#!/bin/bash
echo -e "\n*** Registering Debezium Config ***\n"

curl -X POST -H Accept:application/json -H Content-Type:application/json http://localhost:8083/connectors/ -d @debezium/debezium-config.json
