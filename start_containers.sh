#!/bin/bash
echo -e "\n*** Starting Docker Containers ***\n"

docker volume rm docker-deployment_db-data
docker volume rm docker-deployment_connect-data
docker volume rm docker-deployment_kafka-data
docker-compose up -d