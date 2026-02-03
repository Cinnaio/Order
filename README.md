# Order - Global Market Plugin

**Order** 是一个专为 Minecraft 服务器设计的高性能全球市场插件。它支持玩家之间的物品自由交易，具备严格的 NBT 数据匹配、高并发下的数据一致性保障以及直观的 GUI 操作界面。

## ✨ 核心特性

*   **🛡️ 严格 NBT 支持**：交易和封禁系统基于物品 Hash 运作，完美支持附魔、自定义命名、属性修饰符等所有 NBT 数据。
*   **⚡ 高性能与并发安全**：
    *   **异步 IO**：所有的数据库操作均在异步线程执行，不卡顿主线程。
    *   **乐观锁**：采用版本号机制处理并发订单，防止高并发下的超卖问题。
    *   **Folia 支持**：原生支持 Folia 服务端架构。
*   **📦 批量管理**：支持一键批量上架背包内所有同类（相同 NBT）物品。
*   **🔧 管理员工具**：
    *   **黑名单系统**：支持按材质类型或特定物品 Hash 封禁上架。
    *   **自定义手续费**：可针对特定物品设置专属交易手续费率。
    *   **可视化管理**：提供管理员专属的封禁列表和手续费列表 GUI。
*   **💾 双数据库支持**：开箱即用的 SQLite 支持，以及适合大型服的 MySQL 支持。
*   **📊 交易日志**：详细记录每一笔交易，保障经济安全。

## 📥 安装

1.  下载插件 `.jar` 文件。
2.  将其放入服务器的 `plugins` 文件夹中。
3.  启动服务器。
4.  （可选）在 `config.yml` 中配置数据库连接（默认为 SQLite，无需配置）。

## 📖 指令与权限

### 玩家指令

| 指令 | 描述 | 权限 |
| :--- | :--- | :--- |
| `/market` | 打开全球市场主界面 | 无 |
| `/market sell <价格>` | 将手中物品以指定单价上架 | 无 |
| `/market sell <价格> all` | 将背包中所有与手中物品**完全相同**（严格 NBT）的物品批量上架 | 无 |

### 管理员指令

需要权限: `order.admin`

| 指令 | 描述 |
| :--- | :--- |
| `/market admin ban` | 切换手中物品的封禁状态（基于 Hash 的严格封禁） |
| `/market admin ban list` | 打开封禁列表 GUI，查看和管理已封禁物品 |
| `/market admin fee <费率>` | 设置手中物品的自定义手续费率 (0.0 - 1.0) |
| `/market admin fee <费率> list` | 打开自定义手续费列表 GUI |
| `/market reload` | 重载配置文件和数据库连接 |

### 权限节点

*   `order.admin`: 允许使用所有管理员指令。

## ⚙️ 配置说明

位于 `plugins/Order/config.yml`：

```yaml
database:
  type: "sqlite" # 或 "mysql"
  # MySQL 配置仅在 type 为 mysql 时生效
  host: "localhost"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "password"
  file: "order.db" # SQLite 文件名

market:
  transaction-fee: 0.05 # 默认交易手续费 (5%)
  cancellation-fee: 0.00 # 取消订单手续费
  banned-items: [] # 封禁物品列表（建议通过游戏内指令管理）
```

## 🛠️ 构建

本项目使用 Gradle 构建。

```bash
./gradlew clean build
```

构建产物位于 `build/libs/` 目录。

## 📝 开源协议

[MIT License](LICENSE)
