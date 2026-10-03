#!/usr/bin/env bash
# 启动后端(与 install.sh 同体:deploy.env 已存在时跳过生成,已在运行时幂等退出)
exec "$(dirname "${BASH_SOURCE[0]}")/install.sh"
