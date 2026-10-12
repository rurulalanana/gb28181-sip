# gb28181-sip

[![Java 8+](https://img.shields.io/badge/java-8%2B-orange)](https://www.oracle.com/java/technologies/javase/javase8-archive-downloads.html)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

GB/T 28181—2022 SIP 信令模块。**零 Spring 依赖**，只做协议层，流媒体与业务全部外置。

- 只提供 SIP + MANSCDP + SDP 协议编解码
- 统一声明入口：`Gb28181SipServer.builder().port(5060)...`
- 不集成流媒体：收到 `INVITE` 只回调事件，收流/推流由调用方决定
- 支持微服务多实例：SPI 扩展 + Redis 实现
- Lombok + commons-lang3/collections4/codec + jackson，无多余依赖

---

## 1. 引入

```xml
<dependency>
  <groupId>io.github.rurulalanana</groupId>
  <artifactId>gb28181-sip</artifactId>
  <version>1.0.0</version>
</dependency>
```

集群场景额外引入（单机不需要）：

```xml
<dependency>
  <groupId>redis.clients</groupId>
  <artifactId>jedis</artifactId>
</dependency>
```

> ⚠️ **必须引入 log4j 1.x**。jain-sip 1.2.344 的 `SipStackImpl` 直接引用
> `org.apache.log4j.Priority`，缺失会导致启动抛 `PeerUnavailableException`。
> 本模块已声明该依赖，通常无需手动添加。

---

## 2. 最小用法

```java
Gb28181SipServer server = Gb28181SipServer.builder()
        .serverId("34020000002000000001")   // 平台 20 位编码
        .host("0.0.0.0")
        .port(5060)                          // ← 在别的模块里声明 SIP 端口
        .transport("UDP")
        .build()
        .start();

// 需要主动发命令时
Gb28181SipClient client = server.client();

server.stop();
```

`server` 实现 `AutoCloseable`，可用 try-with-resources。

---

## 3. 事件回调

所有回调都是 `default` 空实现，只覆写关心的：

```java
Gb28181SipServer server = Gb28181SipServer.builder()
        .serverId("34020000002000000001")
        .port(5060)
        .callback(new SipCallback() {

            @Override
            public void onRegister(RegisterEvent e) {
                // 标准 9.1：设备注册/注销。认证已由模块完成
                if (e.isUnregistered()) {
                    deviceService.offline(e.getDeviceId());
                } else {
                    deviceService.online(e.getDeviceId(), e.getPeer().getIp());
                }
            }

            @Override
            public void onKeepAlive(KeepAliveEvent e) {
                // 标准 9.10 / A.2.5.2：心跳，模块已自动刷新在线时间戳
            }

            @Override
            public Boolean onManscdpRequest(ManscdpEvent e) {
                // 收到设备侧查询（如上级平台查我方目录）
                if (e.cmdTypeEnum() == CmdType.CATALOG) {
                    int sn = e.getSn();
                    String resp = ManscdpBuilder.catalogResponse(sn, e.getDeviceId(), myChannels());
                    server.client().sendMessage(e.getDeviceId(), resp);
                }
                return null;
            }

            @Override
            public void onInvite(InviteEvent e) {
                // 标准 9.2：模块不处理流媒体，由调用方准备收流地址后回 200 OK
                if (e.isPlay()) {
                    String ssrc = GbId.buildSsrc(e.getPeer().getDeviceId(), 1, true);
                    SdpMessage sdp = new SdpMessage()
                            .origin(serverId, "127.0.0.1")
                            .connection("127.0.0.1")
                            .mediaConnection("127.0.0.1")
                            .ssrc(ssrc)
                            .mediaFormat(SdpMediaFormat.h264Pip())
                            .sessionName(SdpMessage.S_PLAY)
                            .realtime()
                            .recvonly()
                            .mediaPort(10000);
                    server.client().respondInvite(e.getRawRequestEvent(), sdp);
                    streamService.startPull(e.getPeer().getDeviceId(), "127.0.0.1", 10000, ssrc);
                }
            }

            @Override
            public int onSubscribe(SubscribeEvent e) {
                // 标准 9.11：返回订阅有效期秒数，返回 0 表示拒绝
                return e.getEventName() != null && "Catalog".equals(e.getEventName()) ? 3600 : 0;
            }
        })
        .build()
        .start();
```

> `onManscdpRequest` / `onSubscribe` 的 200 OK 应答由模块自动处理
> （标准 9.5.3.1 c 要求"立即返回 200 OK"），业务应答通过
> `client.sendMessage(...)` 另行发送。

---

## 4. 主动发起

```java
Gb28181SipClient client = server.client();

client.queryCatalog("34020000001320000001");           // 目录查询
client.queryDeviceInfo("34020000001320000001");        // 设备信息
client.queryDeviceStatus("34020000001320000001");      // 设备状态
client.queryRecordInfo(deviceId, start, end);          // 历史文件检索

// PTZ：2022 版为 8 字节控制码（A.3）
String ptz = PtzCommand.builder()
        .address(1)
        .up().left()
        .horizSpeed(6).vertSpeed(6)
        .build().encode();
client.ptzControl(deviceId, ptz);

client.subscribe(deviceId, "Catalog", 3600);           // 目录订阅
client.invitePlay(deviceId, sdp);                      // 实时点播
```

SN 由模块自动生成并回绕到标准要求的 [1, 9999]（A.2.1.3）。

---

## 5. 微服务多实例

设备注册在 A 节点、请求落到 B 节点时，通过共享注册表 + 转发通道把信令送回 A。

```java
// 各节点共享注册表
RedisDeviceRegistry registry = RedisDeviceRegistry.of("127.0.0.1", 6379, null, nodeId);

Gb28181SipServer server = Gb28181SipServer.builder()
        .serverId(PLATFORM_ID)
        .port(5060)
        .nodeId(nodeId)          // 集群路由用
        .registry(registry)      // 共享注册表
        .build()
        .start();
```

跨节点转发通过 `MessageRelay` SPI 扩展，`RedisRelay` 基于 Redis Pub/Sub 实现：

```java
RedisRelay relay = RedisRelay.of("127.0.0.1", 6379, nodeId);
relay.subscribe(msg -> handleForwarded(msg));
```

单机场景用默认的 `MessageRelay.LocalRelay`，无需 Redis。

**Redis 键结构**

```
gb28181:device:{deviceId}   Hash    单设备会话字段
gb28181:devices             Set     全部设备 ID
gb28181:domains:{domain}    Set     域内设备 ID
gb28181:nodes               Set     活跃节点
gb28181:relay:{nodeId}      Channel 节点间信令转发
```

会话 TTL 按注册有效期自动过期，无需额外清理线程。

---

## 6. 认证

```java
// 单设备
.credential(Credential.of("34020000001320000001", "123456"))

// 批量
.credentials(map)

// 自定义（接数据库/配置中心）
.credentialProvider(username -> userDao.findPassword(username))
```

不配置则不做认证。摘要算法为 MD5（标准 9.1.1 a）。认证流程遵循
RFC 3261 §22：无 `Authorization` → 401 challenge，携带则校验摘要。

---

## 7. 协议要点（已按标准逐字实现）

### SSRC（附录 G `y=` 字段）

```
1 位实时/历史 + 5 位域标识 + 4 位域内流标识
```

> ⚠️ **标准原文：`0 为实时，1 为历史`** —— 与直觉相反。
> 域标识取设备编码**第 4~8 位**，例如 `13010000002000000001` 取 `10000`。

```java
GbId.buildSsrc("34020000002000000001", 1, true);   // 0200000001（实时）
GbId.buildSsrc("34020000002000000001", 2, false);  // 1200000002（历史）
```

### `f=` 字段三种形态（附录 G 注 15）

```java
SdpMediaFormat.build("2","6",25,1,2048,"1",8,1);  // v/2/6/25/1/2048/a/1/8/1
SdpMediaFormat.videoOnly("2","5",25,1,1024);       // v/2/5/25/1/1024/a///
SdpMediaFormat.audioOnly("1",8,1);                 // v//////a/1/8/1
```

### 统一编码（附录 E，20 位）

```
中心编码(8) + 行业编码(2) + 类型编码(3) + 网络标识(1) + 序号(6)
                                    ↑ 第 14 位，易漏
```

### 2022 版 vs 2016 版差异

| 项 | 2022 版 |
|---|---|
| `MediaControl`（Play/Stop/Pause…） | **已删除**，附录 A 无定义。本模块标记 `@Deprecated` 作兼容保留 |
| PTZ 控制 | `<PTZCmd>` 8 字节控制码，不再是 `Horizontal/Vertical/Zoom` 数值 |
| `PTZPreciseStatus` | 更名为 `PTZPosition` |
| `StorageCardStatus` | 更名为 `SDCardStatus` |
| `CruisesQuery` / `CruiseQuery` | 更名为 `CruiseTrackListQuery` / `CruiseTrackQuery` |
| `itemType` 字段 | `PositionType` → `CapturePositionType` |

### 易错的取值域

`DeviceStatus` 应答里四个字段取值域互不相同：

| 字段 | 取值 |
|---|---|
| `Result` | `OK` / `ERROR` |
| `Online` | `ONLINE` / `OFFLINE` |
| `Status` | `OK` / `ERROR` |
| `Encode` / `Record` | `ON` / `OFF` |

### 其他约定

| 项 | 要求 |
|---|---|
| MANSCDP Content-Type | `Application/MANSCDP+xml` |
| SDP Content-Type | `application/sdp` |
| `SumNum` | **必选**，须与 `DeviceList/@Num` 一致 |
| 注册有效期缺省 | 86400 s；刷新间隔 ≤ 3600 s |
| 收发消息应答 | 收到 `MESSAGE` 立即回 `200 OK`，**应答无消息体** |
| SDP `s=` 字段 | `Play` 实时 / `Playback` 回放 / `Download` 下载 |
| SDP `t=` 字段 | 实时点播两端均置 0 |
| 信令字符集 | GB 18030 |

---

## 8. 构建与发布

```bash
# 构建 + 测试
mvn clean test

# 打包含源码与 javadoc
mvn clean package

# 发布到 Sonatype Central（Maven 中央仓库，需 GPG 签名）
mvn clean deploy -Pcentral

# 备用通道
mvn deploy -Pgithub       # GitHub Packages
mvn deploy -Paliyun-rds   # 阿里云效 Packages
```

**Central 发布需先完成两件事**：

1. 在 [central.sonatype.com](https://central.sonatype.com/) 绑定 namespace
   `io.github.rurulalanana`
2. 生成 GPG 密钥并上传公钥到 keyserver（中央仓库强制要求签名）

完整步骤见 **[DEPLOY.md](DEPLOY.md)**。

---

## 9. 已知限制

- 标准附录 A 只给 XSD Schema、**未给实例 XML**，MANSCDP 采用松散 DOM 模型，厂商私有扩展字段原样保留（2022 版已删除的 2016 版遗留字段如 `SafetyWay` 需作为自定义元素处理）
- `MediaControl` 为兼容保留，非标准内容
- 集群转发为**节点级路由**（整条信令转给持有设备的节点），未实现平台级的信令级联拆分
- 仅 UDP 完整验证，TCP 通道未做端到端测试
- `f=` 字段 XSD 声明 `length=8`，表 A.3 描述 8 字节（16 字符），标准自相矛盾；实现按表 A.3 输出 16 字符，`encodeHex4()` 可取 8 字符形态

---

## 10. 目录结构

```
io.github.rurulalanana.gb28181.sip
├── Gb28181SipServer          统一声明入口（含 Builder）
├── Gb28181SipClient          主动发起查询/控制/点播
├── constant/                 CmdType、GbId、PtzCommand、ManscdpTypes
├── manscdp/                  MANSCDP XML 构造与解析
├── sdp/                      SDP 构造与解析
├── device/                   DeviceRegistry SPI + 内存实现
├── event/                    SipCallback 与事件对象
├── security/                 摘要认证
├── cluster/                  Redis 注册表与节点间转发
└── internal/                 jain-sip 适配（不对外暴露）
```

---

## 11. 测试

```bash
mvn test
```

56 个测试，覆盖编码规则、MANSCDP 构造/解析、SDP、README 示例，
以及**真实 UDP 报文往返**（REGISTER / MESSAGE / 心跳）——不是 mock。

---

## License

Apache License 2.0，见 [LICENSE](LICENSE)。