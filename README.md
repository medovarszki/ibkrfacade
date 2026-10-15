# IBKR Facade
## Interactive Brokers API wrapper and trading toolbox

This project is built around the [Interactive Brokers TWS API](https://interactivebrokers.github.io/tws-api/) for Java.
With a running TWS or IB Gateway it works as a REST gateway to the market, a market data source for time series
analysis (Redis TimeSeries) or a trading bot framework. It's based on Spring Boot 4 and Java 21+.

It is designed to be the broker layer of an agent based system: an internal service, reachable only on a private
Docker network, in front of a containerized IB Gateway (paper account by default).

**In this project you'll find:**

- The TWS API exposed to a REST API ([OpenAPI spec](docs/openapi.yaml), Swagger UI at `/ibkr/swagger-ui/index.html`)
    - instrument search, contract details, option chain
    - market data subscription and latest prices, historical OHLCV bars
    - account summary (NAV, cash, buying power, margin), positions with market value and P&L
    - **idempotent** order placement, order status with fills and commissions, cancellation
    - news providers, headlines and articles (if the account has news subscriptions)
- Safety by default: no order on a live account unless explicitly allowed, no order without a recent market price,
  optional read / write API tokens, sample strategies disabled
- Market data streamed into Redis TimeSeries
- Docker image and a Docker Compose stack with IB Gateway (paper, automatic login and daily restart by IBC) and Redis 8
- Structured JSON logs with trace id propagation, health endpoint with the TWS connection state

## Quick start with Docker Compose (paper account)

Prerequisites: Docker with Compose v2, an IBKR account with a paper trading user.

```bash
cp .env.example .env
# edit .env: TWS_USERID / TWS_PASSWORD of the paper user, REDIS_PASSWORD,
# IBKR_SECURITY_READ_TOKEN / IBKR_SECURITY_WRITE_TOKEN (eg. openssl rand -hex 32)
docker compose up -d --build
docker compose logs -f ibkrfacade
```

The stack:

| Service | Image | Notes |
|---|---|---|
| `ib-gateway` | `ghcr.io/gnzsnz/ib-gateway:10.50.1f` | IB Gateway + IBC, `TRADING_MODE=paper`, daily restart (`AUTO_RESTART_TIME`), API on `ib-gateway:4004` (paper) |
| `redis` | `redis:8.8.3` | Redis 8 has RedisTimeSeries built in, password protected, persistent volume |
| `ibkrfacade` | built from `Dockerfile` | REST API on `ibkrfacade:8082/ibkr`, non-root, health check |

Nothing is published to the host: the services share the internal `ibkr-backend` network (no internet access), only
the gateway has an extra network to reach the IB servers. Other containers (eg. an MCP server) join the
`ibkr-backend` network and call `http://ibkrfacade:8082/ibkr`.

To call the API from the host during development, publish the REST port on `127.0.0.1`:

```bash
docker compose -f docker-compose.yml -f docker-compose.expose.yml up -d
curl -H "Authorization: Bearer $IBKR_SECURITY_READ_TOKEN" http://localhost:8082/ibkr/account/summary
curl http://localhost:8082/ibkr/actuator/health
```

### Using a TWS / IB Gateway running on the host

Instead of the `ib-gateway` container the facade can connect to a TWS (or IB Gateway) running on the docker host,
eg. the desktop TWS during development. The [docker-compose.host-tws.yml](docker-compose.host-tws.yml) override
doesn't start `ib-gateway` and points the facade to `host.docker.internal:7497` (paper TWS; live: 7496, IB Gateway:
4002 / 4001, set `HOST_TWS_PORT`). The internal network can't reach the host, so the facade joins an extra network
with a fixed address (`HOST_TWS_CLIENT_IP`, default `172.31.250.10`).

The facade reaches the host through the gateway of this network (`host.docker.internal` = `172.31.250.1`, see
`HOST_TWS_GATEWAY`), so the connection always comes from `172.31.250.10`. That is the address TWS and the firewall
see, not the docker host address (`172.17.0.1`).

Setup on the host:

1. TWS: *File > Global Configuration > API > Settings*
   - check *Enable ActiveX and Socket Clients*, *Socket port* `7497` (paper)
   - uncheck *Allow connections from localhost only*
   - add `172.31.250.10` to the *Trusted IPs* (otherwise TWS asks to accept every connection)
2. Firewall: if the host drops incoming traffic (eg. ufw with the default deny policy, the connection times out and
   `[UFW BLOCK] ... DPT=7497` shows up in `journalctl -k`), allow only the facade to reach the API port:
   ```bash
   sudo ufw allow from 172.31.250.10 to any port 7497 proto tcp comment 'ibkrfacade -> TWS'
   ```

Troubleshooting (the `tws` component of `/actuator/health` stays DOWN):

- Test the TCP connection from the container:
  `docker exec ibkrfacade-ibkrfacade-1 bash -c '</dev/tcp/host.docker.internal/7497' && echo OK`.
  A timeout means the packets don't get through. If the TCP connection works but the API doesn't, check the TWS
  settings above.
- Firewall: `journalctl -k | grep 'DPT=7497'` shows the blocked packets with their real source address.
- Duplicate subnet: if Docker stops while the stack is starting, a bridge can be left behind with the same subnet.
  The host then sends the TWS replies to that dead bridge: TWS accepts the packets, but the container gets a timeout.
  `ip -4 route | grep 172.31.250` must list exactly one route. Delete the extra bridge, which isn't listed in
  `docker network ls` and is usually `linkdown`: `sudo ip link delete br-<id>`.

```bash
docker compose -f docker-compose.yml -f docker-compose.host-tws.yml -f docker-compose.expose.yml up -d --build
```

To make it the default, set `COMPOSE_FILE` in `.env` (see `.env.example`), then a plain `docker compose up -d` is
enough. `TWS_USERID` / `TWS_PASSWORD` are still required by the base file, but unused in this mode: any placeholder
will do.

The facade connects automatically when the gateway is ready, and reconnects after the daily restart.
Secrets are only read from the environment (`.env` is git-ignored), nothing is stored in the image. For the gateway
password you can also use Docker secrets (`TWS_PASSWORD_FILE`), see the
[ib-gateway image docs](https://github.com/gnzsnz/ib-gateway-docker#credentials).

Paper accounts often have no live market data. Share the subscriptions of the live account with the paper account
([how to](https://interactivebrokers.github.io/tws-api/market_data.html#paper_sharing)), or set
`IBKR_MARKET_DATA_TYPE=3` for delayed data.

## Configuration

Every property can be set through environment variables (Spring relaxed binding). The most important ones:

| Environment variable | Default | Description |
|---|---|---|
| `IBKR_TWS_HOST` / `IBKR_TWS_PORT` | `localhost` / `4002` | TWS / IB Gateway API endpoint (compose: `ib-gateway` / `4004`) |
| `IBKR_TWS_CLIENT_ID` | `1` | API client id, unique per gateway |
| `IBKR_ALLOW_LIVE` | `false` | Orders are rejected (403 `LIVE_TRADING_DISABLED`) on a live account unless `true` |
| `IBKR_STRATEGIES_ENABLED` | `false` | Loads the sample strategies (`SampleSaver`, `TrendTradingStrategy`) |
| `IBKR_MARKET_DATA_TYPE` | `1` | 1 live, 2 frozen, 3 delayed, 4 delayed frozen |
| `IBKR_PRICE_STALE_AFTER` | `IBKR_ORDER_MAX_QUOTE_AGE` | `GET /price` reports `stale: true` if the latest bid/ask is older than this |
| `IBKR_PRICE_QUOTE_WAIT` | `3s` | How long `GET /price` waits for the first quote of a not yet subscribed instrument |
| `IBKR_ORDER_MAX_QUOTE_AGE` | `15m` | An order needs a bid/ask not older than this (`0` disables the age check) |
| `IBKR_ORDER_QUOTE_WAIT` | `3s` | How long to wait for the first quote of a not yet subscribed instrument |
| `IBKR_ORDER_IDEMPOTENCY_TTL` | `30d` | How long a `client_order_id` is kept in Redis |
| `IBKR_SECURITY_ENABLED` | `false` (compose: `true`) | Bearer token authentication |
| `IBKR_SECURITY_READ_TOKEN` / `IBKR_SECURITY_WRITE_TOKEN` | | Read-only and read-write tokens (min. 16 characters) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / | Redis connection |
| `REDIS_DEFAULT_RETENTION` | `3600000` | Retention of the price time series (ms) |
| `SERVER_PORT` | `8082` | REST port (context path `/ibkr`) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | (image: `ecs`) | JSON logs: `ecs`, `logstash` or `gelf` |

See [application.properties](src/main/resources/application.properties) for the full list.

## REST API

All paths are relative to `/ibkr`. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details
with a machine readable `code`:

```json
{"status": 422, "title": "Unprocessable Content", "code": "MARKET_DATA_UNAVAILABLE", "detail": "No market price (bid/ask) for conid 265598, no order is placed"}
```

| Method | Path | Description |
|---|---|---|
| GET | `/search?query=` | Instrument search |
| GET | `/contract/{conid}` | Contract details (unknown conids are resolved through TWS, 404 if there's no such contract) |
| GET | `/contract/{conid}/session?hours=liquid` | Market open / `next_open` / `next_close`, see [Trading session](#trading-session) |
| GET | `/subscribe/{conid}` | Market data subscription (stream into Redis) |
| GET | `/price/{conid}?max_age=` | Latest bid / ask / last with timestamps and freshness, see [Prices](#prices) |
| GET | `/history/{conid}?duration=1 M&barSize=1 day&whatToShow=TRADES&useRth=true` | OHLCV bars, times in the exchange time zone |
| GET | `/optionChain/{conid}` | Option chain of an underlying |
| GET | `/account/summary?account=` | NAV, cash, available funds, buying power, margin requirements, currency, `timestamp` (query), `updated_at` (last update from TWS) |
| GET | `/positions` | Positions with average cost, market price, market value, unrealized / realized P&L |
| POST | `/order` | Place an order (**write token**) |
| GET | `/orders`, `/orders?client_order_id=`, `/orders/active`, `/orders/{orderId}` | Order status, filled quantity, average fill price, commission, executions |
| DELETE | `/orders/{orderId}` | Cancel an order (**write token**) |
| GET | `/news/providers`, `/news/{conid}`, `/news/article/{provider}/{articleId}` | News |
| GET | `/actuator/health` | Health including the TWS connection (`tws`) and Redis, always public |

### Prices

```json
{"conid": 265598, "bid": 345.0, "ask": 345.01, "last": 345.0016, "quote_time": "2026-10-15T15:22:17Z",
 "quote_age_seconds": 4, "stale": false, "market_data_type": "LIVE", "subscribed": true, "stream_error": null, ...}
```

- The prices come from the Redis time series, they may be old (eg. from before a restart of the facade, or outside
  of the trading hours). **Check `stale`**: it's `true` if there is no bid/ask, or the latest one is older than
  `IBKR_PRICE_STALE_AFTER` (default: `IBKR_ORDER_MAX_QUOTE_AGE`, 15m). `quote_age_seconds` is the age of `quote_time`.
- Without a market data subscription the instrument is subscribed automatically, and the request waits at most
  `IBKR_PRICE_QUOTE_WAIT` (3s) for the first quote. Without a TWS connection the cached price is returned.
- `max_age` (seconds): an older price (or no price) is rejected with 422 `STALE_MARKET_DATA`.
- `market_data_type`: `LIVE`, `FROZEN`, `DELAYED` or `DELAYED_FROZEN`, as reported by TWS for the subscription.
  A paper account without shared live market data gets `DELAYED` data.
- An unknown conid is 404 `CONTRACT_NOT_FOUND`.

### Trading session

```json
{"conid": 265598, "hours": "liquid", "time_zone": "US/Eastern", "open": true,
 "next_open": "2026-10-16T13:30:00Z", "next_close": "2026-10-15T20:00:00Z", "as_of": "2026-10-15T15:49:00Z"}
```

Computed from the `liquid_hours` (regular trading hours, default) or the `trading_hours` (`hours=trading`, with the
extended hours) and the `time_zone` of the contract details, so the clients don't have to parse the TWS format.
Times are UTC. `next_close` is the end of the current session if open, otherwise of the next one; `next_open` is
the start of the next session (after the current one, if open). TWS sends the hours about one week ahead: the
cached contract details are requested again when they run out (without TWS connection the cached ones are used, and
`next_open` / `next_close` may be null). An unusable time zone is 502 `TRADING_HOURS_UNAVAILABLE`.

### Account summary

The facade keeps a single account summary subscription (`reqAccountSummary`) open per TWS connection, and the
endpoint returns the latest values; TWS sends the changes every 3 minutes (`updated_at`). The subscription is never
cancelled: IB Gateway 10.50 ignores the protobuf encoded `cancelAccountSummary`, and TWS allows only two active
account summary subscriptions (error 322).

### Orders

```bash
curl -X POST http://localhost:8082/ibkr/order \
  -H "Authorization: Bearer $IBKR_SECURITY_WRITE_TOKEN" -H "Content-Type: application/json" \
  -d '{"client_order_id": "auxin-20261014-0001", "conid": 265598, "action": "BUY", "quantity": 10,
       "order_type": "LMT", "limit_price": 150.25, "tif": "DAY"}'
```

The response contains the IBKR `order_id`, the `status` and the fill information. Order types: `MKT`, `LMT`
(`limit_price`), `STP` (`stop_price`), `STP_LMT` (both). Time in force: `DAY` (default), `GTC`, `IOC`, `FOK`, `OPG`.

Safety rules of `POST /order`:

- **Idempotency.** `client_order_id` is mandatory and is stored in the IBKR `orderRef` field. It is reserved in Redis
  (`SET NX`) before the order is sent, so an order can't go out twice, even with concurrent requests or after a
  restart. A repeated request returns the existing order with HTTP 200 and `"duplicate": true`. The same id with
  different parameters is rejected with 409 `CLIENT_ORDER_ID_REUSED`. If the id is reserved but TWS doesn't know
  the order (eg. the facade crashed while sending it), the status is `Unknown` and the order is never re-sent: use a
  new `client_order_id`. A request rejected by a safety check (4xx/503) does not reserve the id.
- **Paper / live.** The account type is logged at connection (`DU...` accounts are paper). Orders on a live account
  are rejected unless `IBKR_ALLOW_LIVE=true`; while the account type is unknown, orders are rejected with 503.
- **Market price.** Without a bid or ask not older than `IBKR_ORDER_MAX_QUOTE_AGE` the order is rejected with
  422 `MARKET_DATA_UNAVAILABLE` (the instrument is subscribed automatically).
- **Unknown contract.** 404 `CONTRACT_NOT_FOUND`.
- **No connection / Redis down.** 503 `TWS_NOT_CONNECTED` / `IDEMPOTENCY_STORE_UNAVAILABLE`, nothing is sent.

Order statuses are the IBKR ones (`PendingSubmit`, `PreSubmitted`, `Submitted`, `Filled`, `Cancelled`, `Inactive`,
...) plus `Rejected` (TWS rejected the order, see `error_code` / `error_message`) and `Unknown`.

### Authentication

With `IBKR_SECURITY_ENABLED=true` every request needs `Authorization: Bearer <token>`:

- read token: `GET` endpoints and `POST /price`
- write token: everything, including `POST /order` and `DELETE /orders/{orderId}`

`/actuator/health` and the API docs (unless `IBKR_SECURITY_PUBLIC_DOCS=false`) are public.

## Observability

- JSON logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, default in the image).
- The trace id of the caller is taken from the W3C `traceparent` header, or `X-Trace-Id`, or generated; it is in
  every log line of the request (`trace_id`) and returned in the `X-Trace-Id` response header.
- `/actuator/health` is DOWN while the TWS connection is not ready or Redis is not reachable; the `tws` component
  shows the connection details and the account type. `/actuator/health/liveness` and `/readiness` are available too.
- Optional OpenTelemetry: mount the [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
  and set `JAVA_TOOL_OPTIONS=-javaagent:...` (see the comments in `docker-compose.yml`).

## Market data in Redis

Subscribed instruments are streamed into Redis TimeSeries under `stream:[conid]:[BID|ASK|LAST]`, labeled with the
conid and the symbol. Contracts are cached as `contract:[conid]` hashes. You can use the full power of Redis on this
data (compaction rules for OHLC, pub/sub, etc.), see `TimeSeriesHandler`.

## Building and running without Docker

Requirements: JDK 21+ and Maven. The TWS API jar is vendored in [lib](lib/README.md), no manual step needed.

```bash
mvn package                      # build and unit tests
REDIS_IT=true REDIS_PORT=6379 mvn test -Dtest=ContractRepositoryRedisIT   # optional, needs a running Redis
java -jar target/ibkrfacade-1.0-SNAPSHOT.jar --ibkr.tws.port=4002 --spring.data.redis.host=localhost
```

You need a running TWS or IB Gateway with the API enabled
([initial setup](https://interactivebrokers.github.io/tws-api/initial_setup.html)) and a Redis with RedisTimeSeries.

## Trading strategy automation

The `strategy` package contains a sample: it watches the price of Apple in Redis looking for a signal and places
orders through the same safety checks as the REST API. The samples are not loaded unless
`IBKR_STRATEGIES_ENABLED=true`, and even then the trading methods are not scheduled.

**Important: This is not a real strategy. Don't even think about using it in real conditions!**

## License note

The TWS API is released by Interactive Brokers under the GNU GPL v3 from version 10.49
(see [lib/README.md](lib/README.md)).
