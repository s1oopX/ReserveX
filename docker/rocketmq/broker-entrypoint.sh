#!/bin/sh
set -eu

: "${ROCKETMQ_APP_SECRET_KEY:?ROCKETMQ_APP_SECRET_KEY is required}"
: "${ROCKETMQ_ADMIN_SECRET_KEY:?ROCKETMQ_ADMIN_SECRET_KEY is required}"

validate_secret() {
  name="$1"
  value="$2"
  if [ "${#value}" -lt 32 ]; then
    echo "[rmqbroker] ${name} must be at least 32 characters." >&2
    exit 1
  fi
  case "${value}" in
    *[!A-Za-z0-9]*)
      echo "[rmqbroker] ${name} must be alphanumeric." >&2
      exit 1
      ;;
  esac
}

validate_secret ROCKETMQ_APP_SECRET_KEY "${ROCKETMQ_APP_SECRET_KEY}"
validate_secret ROCKETMQ_ADMIN_SECRET_KEY "${ROCKETMQ_ADMIN_SECRET_KEY}"
if [ "${ROCKETMQ_APP_SECRET_KEY}" = "${ROCKETMQ_ADMIN_SECRET_KEY}" ]; then
  echo "[rmqbroker] app and admin secrets must differ." >&2
  exit 1
fi

umask 077
ACL_FILE="${ROCKETMQ_HOME}/conf/plain_acl.yml"
cat > "${ACL_FILE}" <<EOF
globalWhiteRemoteAddresses: []
accounts:
  - accessKey: ReserveXApp
    secretKey: ${ROCKETMQ_APP_SECRET_KEY}
    admin: false
    defaultTopicPerm: DENY
    defaultGroupPerm: DENY
    topicPerms:
      # RocketMQ 5.5's V1 ACL migration only understands PUB/SUB/DENY; the
      # consumer offset/pull path also checks GET, so PUB|SUB leaves consumers
      # permanently denied. Keep the resource allowlist narrow and use ALL for
      # these explicitly named topics.
      - reservation-created=ALL
      - compensate-rollback=ALL
      - timeout=ALL
      - "%DLQ%cg-persistence=ALL"
      - "%DLQ%cg-rollback=ALL"
      - "%DLQ%cg-timeout=ALL"
    groupPerms:
      - cg-persistence=SUB
      - cg-rollback=SUB
      - cg-timeout=SUB
      - cg-dlq-persistence=SUB
      - cg-dlq-rollback=SUB
      - cg-dlq-timeout=SUB
  - accessKey: ReserveXAdmin
    secretKey: ${ROCKETMQ_ADMIN_SECRET_KEY}
    admin: true
    defaultTopicPerm: DENY
    defaultGroupPerm: DENY
EOF

# 5.5 的 V1 迁移只创建不存在的用户；保留派生 RocksDB 会让环境变量密钥和
# plain_acl.yml 权限变更在重启后失效。消息与消费位点不在这两个目录中。
AUTH_CONFIG_DIR="/home/rocketmq/store/config"
rm -rf "${AUTH_CONFIG_DIR}/users" "${AUTH_CONFIG_DIR}/acls"

mkdir -p /home/rocketmq/store
chown -R rocketmq:rocketmq /home/rocketmq/store
chown rocketmq:rocketmq "${ACL_FILE}"
exec runuser -u rocketmq -p -- sh mqbroker -n rmqnamesrv:9876 -c /init/broker.conf
