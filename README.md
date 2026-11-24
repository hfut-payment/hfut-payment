&lt;p align=&quot;center&quot;&gt;&lt;img src=&quot;docs/images/app-icon.png&quot; width=&quot;96&quot; alt=&quot;payfor工大 图标&quot;&gt;&lt;/p&gt;

# payfor工大

面向合肥工业大学学生的第三方 Android 校园卡客户端。支持出示校园付款码、查询校园卡余额与宿舍费用，并内置小米手环连接功能（Beta）。系统要求 Android 8.0 及以上。

> [!IMPORTANT]
> **非官方项目声明：本项目与合肥工业大学及校园服务提供方无关，仅供学习交流与自愿试用。**
> * **网络传输风险**：部分校园服务接口采用明文 HTTP 协议传输，使用前请充分了解账号凭据及付款码在网络传输过程中的潜在风险。
> * **账单与扣款核对**：涉及扣款、到账或待圈存金额时，均以校方账单记录为准。若充值后提示结果未确认，请先核对校方账单，避免重复支付。
> * **试用风险**：软件按「现状」提供。请自行评估并决定是否使用，在适用法律允许的范围内自行承担试用风险；依法不得免除的责任不受本声明影响。

## 校区支持范围

| 功能模块 | 合肥校区 | 宣城校区 |
| --- | --- | --- |
| **校园付款码** | 二维码、条形码 | 二维码、条形码 |
| **校园卡余额** | 实际余额、待圈存及合计 | 实际余额、待圈存及合计 |
| **宿舍电费** | 照明电费、空调电费 | 常规用电、空调电费 |
| **校园网与洗浴** | — | 流量额度、网费余额、洗浴余额 |
| **手动充值** | 校园卡充值跳转支付宝校园一卡通 | 校园卡充值跳转支付宝校园一卡通；电费、校园网及洗浴支持在费用详情页使用校园卡余额充值 |

## 安装与基础配置

1. **获取安装包**：使用 Android 8.0 及以上版本的设备，前往 [GitHub Releases](https://github.com/hfut-payment/hfut-payment/releases/latest) 或 [Gitee 发行镜像](https://gitee.com/dustbin1415/hfut-payment-release/releases) 下载并安装 APK。
2. **登录与出码**：打开应用并使用校园卡账号登录，首页默认展示校园付款码。
3. **配置校区与绑定信息**：点击「校园费用」区域右上角的齿轮图标切换校区。查询电费需选择对应的楼栋与宿舍号；宣城校区查询洗浴余额需填写已绑定的手机号。
4. **查看明细与隐私遮挡**：点击各费用卡片可查看账单明细或发起手动充值。点击费用区右上角的眼睛图标，可临时隐藏首页显示的金额。

**应用更新机制**：配置了更新源的正式版每次进入前台时会自动检查更新。检测到新版本并下载后，应用将在本地校验安装包完整性与数字签名，校验通过后调起 Android 系统安装器完成确认。更新源随正式发布包配置，[Gitee 发行镜像](https://gitee.com/dustbin1415/hfut-payment-release/releases) 已上线作为可选下载镜像。

## 隐私与统计说明

在「设置 → 账户管理」中可管理记住密码与自动登录选项。启用使用统计的版本在每日首次启动时，会发送当日日期及当天生成的随机安装标识，用于基础活跃度统计。上传数据不包含学号、密码、余额、付款码或任何交易内容。完整条款请参阅应用内的「关于与隐私」。

## 源码构建

本地构建需准备 Android Studio、JDK 17、Android SDK 36 及 Build Tools 35.0.0。默认构建流程无需依赖小米 SDK 或手环安装包，在项目根目录执行：

```sh
./gradlew assembleDebug
```

Windows 环境请执行 `.\gradlew.bat assembleDebug`。

如需构建带手环功能的 APK，需安装 Node.js 22 与 Bash（Windows 环境可使用 Git Bash）。在项目根目录依次执行以下命令：

```sh
git clone https://github.com/hfut-payment/hfut-payment-band.git .sources/band
git -C .sources/band checkout --detach "$(cat band-source.sha)"
(cd .sources/band && npm ci --ignore-scripts)
bash scripts/fetch_wear_sdk.sh
node scripts/stage_band_rpk.mjs .sources/band debug
./gradlew assembleDebug
```

通过该方式构建的 APK 支持从「设置 → 小米手环」直接导出调试版 RPK。请注意，调试签名与正式签名不同，无法覆盖安装手环上已有的正式版应用。正式发版工作流会使用正式证书从同一份源码编译 RPK，并在验签通过后嵌入主应用 APK。

## 开源许可证与第三方来源

本项目主应用代码采用 AGPL-3.0 协议开源，详见 [LICENSE](https://www.google.com/search?q=LICENSE)。项目所涉及的第三方开源组件、设备 SDK 及外部资源分别遵循各自原有的许可协议与使用条款，不统一适用 AGPL-3.0；完整清单及许可说明请参阅 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

- **功能实现参考**：感谢 [聚在工大（Chiu-xaH/HFUT-Schedule）](https://github.com/Chiu-xaH/HFUT-Schedule)。本项目在开发过程中查阅并参考了其开源实现与文档，涉及宿舍电费接口与房间选择、洗浴余额换算，以及校园卡账单字段与分页处理。
- **交互设计参考**：部分页面转场效果参考了 [SharedNav](https://github.com/Chiu-xaH/SharedNav) 的设计思路，本项目基于 Android Views 实现，不作为运行时依赖引入。
- **改编组件与设备 SDK**：手环连接流程中使用了基于 [SimpleFetch-Android-Bridge](https://github.com/midi-HL/SimpleFetch-Android-Bridge) 改编的实现；其余第三方组件明细见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。小米相关设备 SDK 遵循其单独的条款与协议。

