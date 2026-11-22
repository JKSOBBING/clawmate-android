import { AppHeader } from '../../components/AppHeader';
import { FrostedPage } from '../../components/FrostedPage';
import { router } from '@kit.ArkUI';
@Entry
@Component
struct SelfHdcTestPage {
  @Builder Header() { AppHeader({ title: '安卓自动控制', showBackButton: true, onBackClick: (): void => { router.back(); } }) }
  @Builder Content() {
    Column({ space: 16 }) {
      Text('在安卓手机上运行任务').fontSize(20).fontWeight(FontWeight.Bold)
      Text('HDC 是鸿蒙设备的连接方式。在这台安卓手机上，可以通过无线调试连接虚拟屏助手，并在系统设置中授权无障碍服务。')
      Text('打开手机控制设置，检查连接和允许访问的应用。准备好后，到「任务」选择「手机自己控制」。')
      Button('打开手机控制设置').onClick((): void => { router.pushUrl({ url: 'pages/task/GuiPlanPage' }); })
    }.width('100%').padding(24)
  }
  build() {
    Column() {
      FrostedPage({ headerBuilder: (): void => { this.Header(); }, contentBuilder: (): void => { this.Content(); } })
    }.width('100%').height('100%')
  }
}
