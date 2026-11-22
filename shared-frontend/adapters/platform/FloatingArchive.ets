import Bridge from '@arkui-x.bridge';
import { CollectionGroupViewModel } from '../datamanager/DataManagerTypes';
import { showToastSafely } from '../utils/PromptSafe';

interface FloatingGroup { scene: number; pkg: string; label: string; count: number; summary: string; capturedAt: number; }

export class FloatingArchive {
  static async groups(): Promise<CollectionGroupViewModel[]> {
    const raw = await Bridge.createBridge('ClawMatePlatform').callMethod('guiFloatingGroups') as string;
    return (JSON.parse(raw) as FloatingGroup[]).map((item: FloatingGroup): CollectionGroupViewModel => {
      return { id: 'floating:' + item.pkg, title: item.label + ' · 悬浮摘录', meta: item.count + ' 条摘录', source: item.summary,
        time: new Date(item.capturedAt).toLocaleDateString(), status: '', domain: String(item.scene),
        eventCount: item.count, thumbnailUri: '', events: [] };
    });
  }

  static async open(scene: number, pkg: string): Promise<void> {
    try { await Bridge.createBridge('ClawMatePlatform').callMethod('guiFloatingGroup', scene, pkg); }
    catch (error) { showToastSafely('无法打开悬浮摘录，请重试'); }
  }
}
