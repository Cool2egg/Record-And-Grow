# Docker 实践笔记

## 镜像加速
在 /etc/docker/daemon.json 里配置 registry-mirrors 字段，填入镜像站地址后重启 docker 生效。
国内常用的是几个公开镜像站，配置多个会按顺序尝试。

## 常用命令
清理悬空镜像用 docker image prune，查看磁盘占用用 docker system df。
容器日志过大时，可在 daemon.json 里限制 log-opts 的 max-size。
