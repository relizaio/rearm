# `reliza-oci-artifacts-client`

## About

Pull and Push OCI artifacts over http

## Config
Set the following env

```
export REGISTRY_USERNAME=<USERNAME>
export REGISTRY_TOKEN=<TOKEN>
export REGISTRY_HOST=<HOST>
```

### Optional Environment Variables

| Variable | Default | Description |
|---|---|---|
| `USE_PLAIN_HTTP` | `false` | Use plain HTTP instead of HTTPS when communicating with the registry |
| `LOG_TYPE` | `plain` | Log format: `plain` (human-readable) or `json` (structured JSON) |
| `LOG_LEVEL` | `info` | Log level: `debug`, `info`, `warn`, `error`, `dpanic`, `panic`, `fatal` |
| `LOG_HEALTH_CHECK` | `false` | Log health check requests (`/health` endpoint) |

## Usage

### Push Artifact

/push :
```
curl --location --request POST '/push' \
--header 'username: <USERNAME>' \
--header 'token: <TOKEN>' \
--form 'file=@"<PATH_TO_FILE>"' \
--form 'registry="<REGISTRY_HOST>"' \
--form 'repo="<REPOSITORY>"' \
--form 'tag="<TAG>"'
```

### Pull Artifact

/pull :
```
curl -O --location --request GET '/pull?registry<REGISTRY_HOST>=&repo=<REPOSITORY>&tag=<TAG>' \
--header 'username: <USERNAME>' \
--header 'token: <TOKEN>'
```

### Health Check
```bash
curl http://localhost:8083/health
```

## Testing

```bash
go test -v ./...
```

## Compression

Automatically compresses JSON, XML, YAML, and text files. Skips Docker images, archives, and binaries. Transparent decompression on pull.

