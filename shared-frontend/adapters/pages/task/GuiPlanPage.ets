import Bridge from '@arkui-x.bridge';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
import { router } from '@kit.ArkUI';
import { AppTheme } from '../../utils/AppTheme';
import { FrostedPage } from '../../components/FrostedPage';
import { AppHeader } from '../../components/AppHeader';
interface ScreenshotPoll { v: number; status: string; dataUrl: string; width: number; height: number; }
interface PageAction { title: string; detail: string; action: () => void; }
interface ControlState { supported: boolean; connected: boolean; helperAvailable: boolean; }
@Entry
@Component
struct GuiPlanPage {
  private scroller: Scroller = new Scroller();
  @State status: string = '正在检查连接…';
  @State connected: boolean = false;
  @State helperAvailable: boolean = false;
  @State busy: boolean = false;
  @State preview: string = '';
  @State previewStatus: string = '';
  @State capturing: boolean = false;
  private captureId: string = '';
  private captureGeneration: number = 0;

  private clearPreview(): void {
    this.captureGeneration++;
    if (this.captureId) {
      Bridge.createBridge('ClawMatePlatform').callMethod('guiScreenshotCancel', this.captureId).catch(() => {});
      this.captureId = '';
    }
    if (this.preview) {
      try { fs.unlinkSync(this.preview.slice(7)); } catch (ignored) {}
    }
    this.preview = ''; this.previewStatus = ''; this.capturing = false;
  }
  onPageHide(): void { this.clearPreview(); }
  aboutToDisappear(): void { this.clearPreview(); }
  private async capture(): Promise<void> {
    if (this.capturing) { return; }
    this.clearPreview(); this.capturing = true; let generation = this.captureGeneration;
    let bridge = Bridge.createBridge('ClawMatePlatform');
    try {
      let id = await bridge.callMethod('guiScreenshotStart') as string;
      if (generation !== this.captureGeneration) { await bridge.callMethod('guiScreenshotCancel', id); return; }
      this.captureId = id; let deadline = Date.now() + 8000;
      while (Date.now() < deadline) {
        let value = JSON.parse(await bridge.callMethod('guiScreenshotPoll', id) as string) as ScreenshotPoll;
        if (generation !== this.captureGeneration) { return; }
        if (value.v !== 1) { throw new Error('截图接口版本不兼容'); }
        if (value.status === 'done') {
          if (!/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(value.dataUrl) || value.dataUrl.length > 1400000) { throw new Error('截图数据无效'); }
          let path = (getContext(this) as common.UIAbilityContext).cacheDir + '/gui-preview-' + String(Date.now()) + '.jpg';
          let bytes = new util.Base64Helper().decodeSync(value.dataUrl.split(',')[1]);
          try {
            let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
            try { fs.writeSync(file.fd, bytes.slice().buffer); } finally { fs.closeSync(file); }
          } catch (error) { try { fs.unlinkSync(path); } catch (ignored) {} throw error as Error; }
          this.preview = 'file://' + path;
          this.previewStatus = '本机预览 · ' + String(value.width) + '×' + String(value.height); return;
        }
        if (value.status !== 'running') { throw new Error('截图失败或已失效，请在前台重试'); }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 80); });
      }
      throw new Error('截图超时');
    } catch (error) { if (generation === this.captureGeneration) { this.previewStatus = (error as Error).message; } }
    finally {
      if (generation === this.captureGeneration) {
        const finishedId = this.captureId;
        this.captureId = ''; this.capturing = false;
        if (finishedId) { bridge.callMethod('guiScreenshotCancel', finishedId).catch(() => {}); }
      }
    }
  }
  onPageShow(): void { this.refresh(); }
  aboutToAppear(): void { this.refresh(); }
  private async refresh(): Promise<void> {
    try {
      let state = JSON.parse(await Bridge.createBridge('ClawMatePlatform').callMethod('guiControlStatus') as string) as ControlState;
      this.connected = state.connected;
      this.helperAvailable = state.helperAvailable;
      this.status = state.connected ? '无障碍服务已连接' : state.supported ? '请开启无障碍服务以操作其他应用' : '手机操作需要 Android 14 及以上';
    } catch (error) { this.status = '暂时无法检查连接，请稍后重试'; }
  }
  private async open(method: string): Promise<void> {
    if (this.busy) { return; }
    this.busy = true;
    try { await Bridge.createBridge('ClawMatePlatform').callMethod(method); await this.refresh(); }
    catch (error) { this.status = (error as Error).message; }
    finally { this.busy = false; }
  }
  @Builder Action($$: PageAction) {
    Row({ space: 12 }) {
      Column({ space: 5 }) {
        Text($$.title).fontSize(16).fontColor(AppTheme.text('#161B28')).width('100%')
        Text($$.detail).fontSize(12).lineHeight(18).fontColor(AppTheme.text('#7C879A')).width('100%')
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('›').fontSize(26).fontColor(AppTheme.text('#8993A4'))
    }.padding(18).width('100%').constraintSize({ minHeight: 78 }).enabled(!this.busy).onClick($$.action)
  }
  @Builder Line() { Divider().color(AppTheme.border('#EDF0F5')).margin({ left: 18, right: 18 }) }
  @Builder Header() { AppHeader({ title: '界面采集与规划', showBackButton: true, onBackClick: () => { router.back(); } }) }
  @Builder Content() {
    Scroll(this.scroller) {
      Column({ space: 20 }) {
        Column({ space: 8 }) {
          Text('让助手操作手机').fontSize(21).fontWeight(FontWeight.Bold).fontColor(AppTheme.text('#161B28')).width('100%')
          Text('在云端聊天里描述任务，助手会按应用访问设置打开应用、搜索和浏览。这里管理操作权限与节奏。')
            .fontSize(14).lineHeight(22).fontColor(AppTheme.text('#68758B')).width('100%')
          Text(this.status).fontSize(13).fontColor(AppTheme.text(this.connected ? '#287354' : '#976122')).width('100%').margin({ top: 6 })
        }.padding(20).width('100%').backgroundColor(AppTheme.background('#EDF3FC')).borderRadius(20)
        Column() {
          this.Action({ title: '应用访问', detail: '选择云端助手可以操作的应用', action: () => { this.open('mikaAppAccessSettings'); } })
          this.Line()
          this.Action({ title: '操作节奏', detail: '调整操作速度，也可为不同应用单独设置', action: () => { this.open('mikaPaceSettings'); } })
          this.Line()
          this.Action({ title: '无障碍服务', detail: '查看并设置手机操作所需的系统权限', action: () => { this.open('guiControlSettings'); } })
          this.Line()
          this.Action({ title: '无线配对', detail: this.helperAvailable ? '后台屏助手已连接，管理本机无线配对' : '通过手机的无线调试连接后台屏助手', action: () => { this.open('guiWirelessPairing'); } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Text('手动控制与采集').fontSize(14).fontWeight(FontWeight.Medium).fontColor(AppTheme.text('#68758B')).width('100%').padding({ left: 8 })
        Column() {
          this.Action({ title: '手动控制', detail: '选择要操作的应用', action: () => { this.open('guiControlOpen'); } })
          this.Line()
          this.Action({ title: '手动控制授权', detail: '管理各应用的长期授权，授权后点按、滑动和返回可直接执行', action: () => { this.open('guiControlGrants'); } })
          this.Line()
          this.Action({ title: '采集应用内容', detail: '选择应用，读取当前页或连续浏览；确认后保存到本机', action: () => { this.open('guiCollectionOpen'); } })
          this.Line()
          this.Action({ title: '后台悬浮窗', detail: '管理桌面和其他应用上的悬浮便签', action: () => { this.open('guiFloatingSettings'); } })
          this.Line()
          this.Action({ title: '已采集内容', detail: '查看之前保存在本机的采集记录', action: () => { this.open('guiCollectionHistory'); } })
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
        Column() {
          this.Action({ title: '截取本应用界面', detail: '在本机预览当前页面，离开页面时清除', action: () => { this.capture(); } })
          if (this.preview.length > 0) {
            this.Line()
            this.Action({ title: '清除截图', detail: '删除本次预览', action: () => { this.clearPreview(); } })
            Image(this.preview).width('100%').height(170).objectFit(ImageFit.Contain).accessibilityText('本机截图预览')
          }
          if (this.previewStatus.length > 0) {
            Text(this.previewStatus).fontSize(13).fontColor(AppTheme.text('#68758B')).width('100%').padding(16)
          }
        }.width('100%').backgroundColor(AppTheme.background('#FFFFFF')).borderRadius(20)
      }.width('92%').constraintSize({ maxWidth: 720 }).padding({ top: 16, bottom: 40 })
    }.width('100%').height('100%').scrollBar(BarState.Off)
  }
  build() {
    Column() { FrostedPage({ pageBackgroundColor: '#F4F6FA', scrollers: [this.scroller], headerBuilder: () => { this.Header(); }, contentBuilder: () => { this.Content(); } }) }.width('100%').height('100%')
  }
}
