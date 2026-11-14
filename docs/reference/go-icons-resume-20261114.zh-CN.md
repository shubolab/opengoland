# Go 图标续接核对（2026-11-14）

用户截图中的 Go 源文件、测试、`go.mod`、`go.work` 和多个侧栏入口都使用同一个青色 `GO` 方块，难以分辨文件与功能，也偏离原版的视觉习惯。本次续接检查发现，这项问题已经由提交 `cac85013896b0508d88dab9f8c42d5bbc388fec0`（`Restore Go icons and refresh local replacement dependencies`）处理，本轮没有再修改图标代码或资源。

当前 [GoIcons](../../src/main/kotlin/dev/openidea/goide/icons/GoIcons.kt) 使用独立绘制的透明 Gopher SVG：[源文件](../../src/main/resources/icons/go.svg)、[测试](../../src/main/resources/icons/goTest.svg)、[模块](../../src/main/resources/icons/goMod.svg)、[工作区](../../src/main/resources/icons/goWork.svg)，并各有深色版本。测试使用红绿方向角标，模块和工作区使用不同结构角标。[插件声明](../../src/main/resources/META-INF/plugin.xml)中的 Go、Go Coverage、Go Profiles、Go Inspections 工具窗分别使用开源平台的依赖、覆盖率、性能分析和检查图标。它们不是原版资源的逐像素复制；Gopher 改绘及归属见[随包说明](../../src/main/resources/META-INF/GO-ICONS-NOTICE.txt)。

[GoFileIconsTest](../../src/test/kotlin/dev/openidea/goide/icons/GoFileIconsTest.kt)覆盖真实 SDK SVG 在深浅模式的渲染、四类图标的像素差别、透明角和测试文件的扩展注册。[既有定向 XML](cold-external-refresh-20261113-evidence/scope-targeted1-TEST-dev.openidea.goide.icons.GoFileIconsTest.xml)保留当时的测试结果；该结果不替代本次新源码的全量回归。

已有 candidate6 原生 REA [深色截图](cold-external-refresh-20261113-evidence/candidate6/baseline.png)和[浅色截图](cold-external-refresh-20261113-evidence/candidate6/light-icons.png)能看到项目树、编辑器标签及功能侧栏的区别；各自[深色元数据](cold-external-refresh-20261113-evidence/candidate6/baseline.json)和[浅色元数据](cold-external-refresh-20261113-evidence/candidate6/light-icons.json)绑定历史版本 `0.1.0-preview.20261114.2`、源码 `07bb8eb35f48f11564bac5b076fa7752ff03466a`。这些是既有原生证据，本轮没有重新启动该候选或参考应用。

本机 Git 核对：`v0.1.0-preview.20261114.3^{}` 指向 `643480a3d9d01c526210bdeba16df8431c1f4d75`；`git merge-base --is-ancestor cac85013896b0508d88dab9f8c42d5bbc388fec0 v0.1.0-preview.20261114.3^{}` 返回 0。因此，当前已发布 14.3 的源码历史包含上述图标修复。这只核对源码祖先关系，不单独证明下载安装器的运行画面；正式交付范围见[14.3 记录](../verification/releases/0.1.0-preview.20261114.3.json)。

后续新候选的原生验收仍应复核深浅主题下的源文件、测试、mod/work 和四个功能侧栏，保留新候选自己的截图来源，不能把历史截图改标为新版本通过。
