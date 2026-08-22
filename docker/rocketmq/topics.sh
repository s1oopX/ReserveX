#!/bin/sh
# ============================================================================
# ReserveX RocketMQ topic 初始化(08 §4.4)
#
# 由一次性容器 rmq-init 执行,跑完即退;backend 用 service_completed_successfully
# 门控它 —— topic 先在,应用再起。
#
# 为什么不靠 autoCreateTopicEnable(默认 true):
#   1. 自动创建的 topic 队列数很少,而 persistence 配了 20 个消费线程 →
#      **消费并发度被队列数卡死**,现象是"线程数调大了但落库延迟没降"。
#   2. 自动创建发生在第一条消息投递时,而抢号是**同步发消息**(超时 3000ms)→
#      放号瞬间的第一条可能因建 topic 而超时,白走一次 pending-scanner 补投。
#   3. 生产通常关掉它,依赖它写出来的代码换环境即挂。
#
# 幂等:updateTopic 对已存在的 topic 是**更新**而非报错,故可重复执行。
# 队列数按消费线程数配:reservation-created 是唯一高吞吐 topic(每次抢号一条)。
# ============================================================================
set -e

: "${ROCKETMQ_ADMIN_SECRET_KEY:?ROCKETMQ_ADMIN_SECRET_KEY is required}"
if [ "${#ROCKETMQ_ADMIN_SECRET_KEY}" -lt 32 ]; then
  echo "[rmq-init] admin secret must be at least 32 characters." >&2
  exit 1
fi
case "${ROCKETMQ_ADMIN_SECRET_KEY}" in
  *[!A-Za-z0-9]*)
    echo "[rmq-init] admin secret must be alphanumeric." >&2
    exit 1
    ;;
esac

umask 077
TOOLS_FILE="${ROCKETMQ_HOME}/conf/tools.yml"
trap 'rm -f "${TOOLS_FILE}"' EXIT
cat > "${TOOLS_FILE}" <<EOF
accessKey: ReserveXAdmin
secretKey: ${ROCKETMQ_ADMIN_SECRET_KEY}
EOF

NAMESRV="rmqnamesrv:9876"
CLUSTER="DefaultCluster"

# broker 注册到 namesrv 需要几秒;depends_on 只保证进程起来,不保证注册完成。
# 这里主动等,避免第一条 updateTopic 报 "No broker in cluster"。
echo "[rmq-init] waiting for broker to register on ${NAMESRV} ..."
i=0
until sh mqadmin clusterList -n "${NAMESRV}" 2>/dev/null | grep -q "${CLUSTER}"; do
  i=$((i + 1))
  if [ "${i}" -ge 60 ]; then
    echo "[rmq-init] broker not registered after 60 tries, abort." >&2
    exit 1
  fi
  sleep 2
done
echo "[rmq-init] broker is up."

create_topic() {
  topic="$1"
  queues="$2"
  echo "[rmq-init] updateTopic ${topic} (-r ${queues} -w ${queues})"
  sh mqadmin updateTopic -n "${NAMESRV}" -c "${CLUSTER}" -t "${topic}" -r "${queues}" -w "${queues}"
}

# 高吞吐:每次抢号一条;persistence 20 线程 → 8 个队列,并留出 D5 多实例的余量
create_topic reservation-created 8
# 补偿回滚:低频(只在落库失败时)
create_topic compensate-rollback 4
# 超时关单:定时消息
create_topic timeout 4
create_topic "%DLQ%cg-persistence" 1
create_topic "%DLQ%cg-rollback" 1
create_topic "%DLQ%cg-timeout" 1

echo "[rmq-init] all topics ready."
