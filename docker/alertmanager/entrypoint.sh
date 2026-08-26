#!/bin/sh
# 渲染 alertmanager.yml 并启动(与 rocketmq/broker-entrypoint.sh 同一套路)。
#
# 为什么要这一层:Alertmanager 不展开配置里的环境变量,收件人地址没法直接用
# ${VAR} 写在 yml 里 —— 写了会被当成字面量收件人,发信失败且原因难查。
# 缺变量时用 :? 直接启动失败:静默发不出邮件比起不来危险得多,
# 那会让人以为「没有告警 = 没有故障」,正是本轮要消灭的失效模式。
#
# 口令不经过这里:由 Compose secret 以只读方式挂进 /run/secrets/smtp_password,
# 配置里用 smtp_auth_password_file 读。所以本脚本只替换两个地址占位符。
set -eu

: "${ALERT_EMAIL_TO:?ALERT_EMAIL_TO is required (告警收件人)}"
: "${SMTP_FROM:?SMTP_FROM is required (发信邮箱,须与 QQ 授权码同账号)}"

TEMPLATE=/etc/alertmanager/alertmanager.tmpl.yml
RENDERED=/etc/alertmanager/alertmanager.rendered.yml
PASSWORD_FILE=/run/secrets/smtp_password

# 容器以 USER nobody 跑,/etc/alertmanager 在上游镜像里被 chown 给了 nobody,
# 所以渲染产物写这里不需要额外的 tmpfs 权限设置(也不落进持久卷)。
if [ ! -r "${PASSWORD_FILE}" ]; then
  echo "[alertmanager] 读不到 ${PASSWORD_FILE};检查 compose 的 secrets 段与 QQ_SMTP_PASSWORD" >&2
  exit 1
fi

sed -e "s|__ALERT_EMAIL_TO__|${ALERT_EMAIL_TO}|g" \
    -e "s|__SMTP_FROM__|${SMTP_FROM}|g" \
    "${TEMPLATE}" > "${RENDERED}"

# 占位符残留说明模板改了名字而这里没跟上,继续跑会把字面量当收件人。
#
# ⚠️ 必须先剥掉注释行再查。模板顶部的说明文字里写着 __XXX__ 来解释"占位符长什么样",
#    直接 grep 会把那段散文当成残留 → 容器每次启动即退出、无限重启,而
#    Alertmanager 从头到尾没起来过 = 告警一封都发不出。这正是本守卫想防的失效模式,
#    却被它自己触发(渲染本身是好的:两个真占位符都已替换)。
STRIPPED=$(grep -v '^[[:space:]]*#' "${RENDERED}" || true)
if printf '%s' "${STRIPPED}" | grep -q '__[A-Z_]*__'; then
  echo "[alertmanager] 模板仍有未替换的占位符:" >&2
  printf '%s' "${STRIPPED}" | grep -o '__[A-Z_]*__' | sort -u >&2
  exit 1
fi

exec /bin/alertmanager \
  --config.file="${RENDERED}" \
  --storage.path=/alertmanager \
  "$@"
