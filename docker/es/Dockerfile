# 关键词通道用的 Elasticsearch：单节点 + 中文分词插件。
#
# 为什么不用官方镜像直接用：官方镜像不带中文分词器，Standard 分词器会把
# 「镜像加速」切成单字，BM25 基本失效。插件版本必须与 ES 版本严格一致。
#
# 插件 zip 由构建机下载后 COPY 进来，而不是在 RUN 里用 URL 安装：
# Docker 构建容器不走宿主机代理，构建期联网安装会失败（实测 404/超时）。
# 下载命令（宿主机，走代理）：
#   curl -L -o docker/es/ik.zip https://get.infini.cloud/elasticsearch/analysis-ik/8.15.3
FROM docker.elastic.co/elasticsearch/elasticsearch:8.15.3

# 镜像默认以 elasticsearch 用户运行，COPY 进来的文件默认为 root 所有，
# 后面的 rm 会因权限失败，所以显式 --chown。
COPY --chown=elasticsearch:root ik.zip /tmp/ik.zip
# 插件安装在 8.15 的镜像里会返回非 0（安全权限提示），但插件其实已经装好，
# 所以用目录存在性作为唯一判据，避免把成功当失败。
RUN (bin/elasticsearch-plugin install --batch file:///tmp/ik.zip || true) \
    && test -d /usr/share/elasticsearch/plugins/analysis-ik \
    && rm -f /tmp/ik.zip

# 备选（没有 ik.zip 时）：官方 smartcn 插件，由 artifacts.elastic.co 分发。
# 换成下面这行前先删掉上面的 COPY/RUN，并把 keyword.analyzer 改成 smartcn、
# keyword.search-analyzer 改成 smartcn。
# RUN bin/elasticsearch-plugin install --batch analysis-smartcn
