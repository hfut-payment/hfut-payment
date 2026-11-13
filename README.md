<p align="center"><img src="docs/images/app-icon.png" width="96" alt="payfor工大 图标"></p>

# payfor工大

在手机上出示校园付款码、查校园卡余额和宿舍费用。合肥、宣城两个校区可查询的项目见下表。

> [!IMPORTANT]
> **非官方项目，与合肥工业大学及校园服务提供方无关。** 项目由个人维护，仅供学习、交流和自愿试用。
>
> 软件按现状提供。部分校园服务使用明文 HTTP，使用前请了解账号和付款码的传输风险。涉及扣款、到账或预圈存时，以校方账单为准。充值结果未确认时，请先核对账单。
>
> 请自行决定是否使用，并承担法律允许由使用者承担的风险。依法不得免除的责任不受本声明影响。

## 各校区支持的功能

| 功能 | 合肥校区 | 宣城校区 |
| --- | --- | --- |
| 校园付款 | 二维码、条形码 | 二维码、条形码 |
| 校园卡 | 实际余额、待圈存和合计 | 实际余额、待圈存和合计 |
| 宿舍电费 | 照明与空调 | 常规用电与空调 |
| 校园网和洗浴 | — | 流量额度、网费和洗浴余额 |

需要给校园卡充值时，可从余额详情打开支付宝校园一卡通。宣城校区的电费、网费和洗浴也可以在各自的费用详情里用校园卡充值。

## 安装与首次使用

1. 用 Android 8.0 或更新版本的手机，从 [Releases](../../releases) 下载并安装 APK。
2. 打开应用，用校园卡账号登录。首页即可出示付款码。
3. 点「校园费用」右上角的齿轮选择校区。查电费时选择楼栋和宿舍，宣城的洗浴余额使用绑定的手机号查询。
4. 点费用卡片查看明细。想临时遮住首页金额，点费用区右上角的眼睛图标。

正式版每次进入前台时会检查更新。有新版时按弹窗提示操作，应用会校验安装包及签名，最后由 Android 安装器确认更新。

## 隐私

在「设置 → 账户管理」可以管理记住密码和自动登录。启用使用统计的版本每天首次打开时，会发送日期和当天生成的随机安装标识。统计不包含学号、余额、付款码或交易内容。完整说明见应用的「关于与隐私」。

## 从源码构建

用 Android Studio 打开当前目录，安装 Android SDK 36 和 Build Tools 35.0.0，使用 JDK 17。

~~~sh
./gradlew assembleDebug
~~~

Windows 使用 `.\gradlew.bat assembleDebug`。默认构建无需小米 SDK 或手环安装包。

<details>
<summary>可选：从源码构建带手环功能的 APK</summary>

需 Node.js 22 和 Bash（Windows 可用 Git Bash）。下面的命令从项目根目录运行：

~~~sh
git clone https://github.com/hfut-payment/hfut-payment-band.git .sources/band
git -C .sources/band checkout --detach "$(cat band-source.sha)"
(cd .sources/band && npm ci --ignore-scripts)
bash scripts/fetch_wear_sdk.sh
node scripts/stage_band_rpk.mjs .sources/band debug
./gradlew assembleDebug
~~~

这样构建的 APK 可从「设置 → 小米手环」导出调试 RPK。调试签名不能覆盖已装的正式手环应用。正式发行工作流会用正式证书从同一份源码构建 RPK，验签后嵌入 APK。

</details>

第三方组件见 [许可说明](THIRD_PARTY_NOTICES.md)。
