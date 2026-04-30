# Automation Scripts

## deploy.sh

Deploys the latest ECS service revision and performs a health check.

### Required environment variables

- ECS_CLUSTER
- ECS_SERVICE
- APP_URL

### Usage

```bash
ECS_CLUSTER=taski-nodejs-app-dev-cluster \
ECS_SERVICE=taski-nodejs-app-dev-service \
APP_URL=http://example.com/health \
./deploy.sh
```
