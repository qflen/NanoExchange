# syntax=docker/dockerfile:1.7
# The bridge depends on the `nanoexchange-client` sibling package, so we build the image
# from the repo root with the `bridge` and `client` directories both in context.
# Build context expected: the repository root (see docker-compose.yml).

FROM python:3.13-slim AS runtime
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

COPY client /app/client
COPY bridge /app/bridge
RUN pip install /app/client /app/bridge

EXPOSE 8765/tcp
# Multicast group + port, TCP gateway host + port, WS port. Override via compose.
ENV NX_MCAST_GROUP=239.200.6.42 \
    NX_MCAST_PORT=6666 \
    NX_GW_HOST=engine \
    NX_GW_PORT=9000 \
    NX_WS_PORT=8765
ENTRYPOINT ["/bin/sh","-c","exec nx-bridge \
    --multicast-group \"$NX_MCAST_GROUP\" --multicast-port \"$NX_MCAST_PORT\" \
    --order-gw-host \"$NX_GW_HOST\" --order-gw-port \"$NX_GW_PORT\" \
    --ws-host 0.0.0.0 --ws-port \"$NX_WS_PORT\""]
