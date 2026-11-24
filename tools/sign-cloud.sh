#!/system/bin/sh
# 给 cloud.json 重新签名（seq 自动 +1），可选直接上传到服务器。
#
# 用法：
#   sh tools/sign-cloud.sh            # 只签名，写回 cloud.json
#   sh tools/sign-cloud.sh --upload   # 签名后 PUT 到 http://mc.nyafish.top:8081/cloud.json
#   sh tools/sign-cloud.sh --keep-seq # 不递增 seq（只改了无关字段时用，别随便用）
#
# 前提：私钥在本机 /data/local/tmp/ws/keys/cloud.key（不在仓库、不进源码包）。
# 换私钥等于换公钥：必须同步改 Cloud.java 的 PUBKEY_B64 并重新发版，老版本客户端会全部验签失败。
set -e

PROJ=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=${CLOUD_WS:-/data/local/tmp/ws}
CS=$W/cs
KEY=${CLOUD_KEY:-$W/keys/cloud.key}
JAVA=${CLOUD_JAVA:-/data/local/tmp/tc21/bin/java}
JAVAC=${CLOUD_JAVAC:-/data/local/tmp/tc21/bin/javac}
AUTH=${CLOUD_AUTH:-mctga:MeiYou520}
URL=${CLOUD_URL:-http://mc.nyafish.top:8081/cloud.json}

[ -f "$KEY" ] || { echo "缺少私钥 $KEY（见 docs/交接说明：云控签名密钥）"; exit 1; }

# 编译签名工具（每次覆盖，避免用了旧 class）
mkdir -p "$CS"
cp "$PROJ/tools/CloudSign.java" "$W/CloudSign.java"
"$JAVAC" -d "$CS" "$W/CloudSign.java" 2>/dev/null

KEEP=0
UPLOAD=0
for a in "$@"; do
  case "$a" in
    --upload) UPLOAD=1 ;;
    --keep-seq) KEEP=1 ;;
    *) echo "未知参数 $a"; exit 2 ;;
  esac
done

cp "$PROJ/cloud.json" "$W/cloud-in.json"
if [ "$KEEP" = "0" ]; then
  # 输入可能是编辑器写出的「纯内容」（没有 seq），也可能是带 seq 的旧配置；
  # 取「本地 seq」与「服务器当前 seq」的较大者再 +1，保证客户端不会当重放丢掉。
  LOCAL_SEQ=$(sed -n 's/.*"seq"[ \t]*:[ \t]*\([0-9][0-9]*\).*/\1/p' "$W/cloud-in.json" | head -1)
  SRV_SEQ=$(curl -s -m 6 "$URL" | sed -n 's/.*"seq"[ \t]*:[ \t]*\([0-9][0-9]*\).*/\1/p' | head -1)
  BASE=${LOCAL_SEQ:-0}
  [ "${SRV_SEQ:-0}" -gt "$BASE" ] && BASE=$SRV_SEQ
  SEQ=$(( BASE + 1 ))
  if grep -q '"seq"' "$W/cloud-in.json"; then
    sed -i "s/\"seq\"[ \t]*:[ \t]*[0-9][0-9]*/\"seq\": $SEQ/" "$W/cloud-in.json"
  else
    sed -i "1,/{/s|{|{\n  \"seq\": $SEQ,|" "$W/cloud-in.json"
  fi
  echo "seq → $SEQ（本地 ${LOCAL_SEQ:-无} / 服务器 ${SRV_SEQ:-无}）"
fi

"$JAVA" -cp "$CS" CloudSign sign "$KEY" "$W/cloud-in.json" "$PROJ/cloud.json"
"$JAVA" -cp "$CS" CloudSign verify "$W/keys/cloud.pub" "$PROJ/cloud.json"

if [ "$UPLOAD" = "1" ]; then
  curl -s -m 10 -u "$AUTH" -T "$PROJ/cloud.json" -X PUT "$URL" -o /dev/null -w "已上传 %{http_code}\n"
  echo "服务器回读："
  curl -s -m 8 "$URL"
fi
