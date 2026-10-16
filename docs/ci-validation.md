# CI 工作流验收记录

日期：2026-10-16

## 工作流

`.github/workflows/ci.yml` 已新增 GitHub Actions 工作流：

- `push` 触发 `main`、`master`、`feature/**`、`hotfix/**`；对应主分支的 `pull_request` 也触发。
- 使用 `ubuntu-latest`、Temurin JDK 21 和 Maven 缓存。
- 先执行 `mvn --batch-mode --no-transfer-progress test`，再执行 `mvn --batch-mode --no-transfer-progress -DskipTests package`。
- 失败时上传 `target/surefire-reports`，权限只读 `contents`。
- 不启动 PostgreSQL、Redis、MinIO、Elasticsearch，不读取任何凭证，不访问真实厂商或付费模型。

## 本地验证

本机 Maven 默认仓库 `C:\.m2\repository` 不可写，因此使用项目现有的可写 Maven 缓存和离线 settings 完成等价验证：

- 全量测试：1134 条执行，0 失败，0 错误，6 条按开关跳过。
- 离线打包：`BUILD SUCCESS`。

GitHub hosted runner 的首次运行仍需在远端仓库建立后触发；本记录不把本地验证写成 hosted runner 已通过。

本地首个提交已建立在 `feature/ci-pipeline`，提交为 `49fc3b2`，远端按用户提供的账号配置为 `https://gitee.com/xiao-cai-cai/AIFlowEngine.git`。Git 提交身份为 `小蔡蔡 <997303152@qq.com>`。hosted runner 尚未执行，需在 Gitee 仓库存在且认证成功后由推送触发。
