#!/bin/bash

TABLE_NAME="ChatFlow"
REGION="us-east-1"
SCHEMA_FILE="create-chatflow-table.json"

echo "Checking if DynamoDB table '$TABLE_NAME' exists..."

if aws dynamodb describe-table --table-name "$TABLE_NAME" --region "$REGION" >/dev/null 2>&1; then
    echo "Table '$TABLE_NAME' already exists. Skipping creation."
else
    echo "Table not found. Creating table from schema file: $SCHEMA_FILE"

    aws dynamodb create-table \
        --cli-input-json file://"$SCHEMA_FILE" \
        --region "$REGION"

    echo "Waiting for table to become ACTIVE..."
    sleep 60
    aws dynamodb wait table-exists \
        --table-name "$TABLE_NAME" \
        --region "$REGION"

    echo "Table '$TABLE_NAME' created successfully!"
fi

echo "Done."
