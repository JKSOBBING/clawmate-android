import Bridge from '@arkui-x.bridge';
import { util } from '@kit.ArkTS';
import { AiServiceAccess } from '../utils/AiServiceAccess';
import { PrivacyConsent } from '../utils/PrivacyConsent';

interface PlannerPoll { code: number; done: boolean; error: string; result: string; chunks: string[]; }
export interface PlannerReply { responseCode: number; result: string; }

/** Only the pinned HTTP service uses this transport. The shared client still owns JSON and SSE parsing. */
export class UpstreamPlanner {
  private id: string = '';
  private stopped: boolean = false;
  private result: string = '';

  cancel(): void {
    this.stopped = true;
    if (this.id) {
      Bridge.createBridge('ClawMatePlatform').callMethod('plannerRequestCancel', this.id).catch(() => {});
    }
  }

  async read(url: string, key: string, body: string, timeout: number, receive: (bytes: ArrayBuffer) => void): Promise<number> {
    const bridge = Bridge.createBridge('ClawMatePlatform');
    try {
      AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
      this.id = await bridge.callMethod('plannerRequestStart', url, key, body, timeout) as string;
      const decoder = new util.Base64Helper();
      while (!this.stopped) {
        AiServiceAccess.requireAllowed(); PrivacyConsent.requireAccepted();
        const poll = JSON.parse(await bridge.callMethod('plannerRequestPoll', this.id) as string) as PlannerPoll;
        if (this.stopped) { break; }
        if (poll.error) { throw new Error(poll.error); }
        for (let i = 0; i < poll.chunks.length; i++) {
          receive(decoder.decodeSync(poll.chunks[i]).slice().buffer as ArrayBuffer);
        }
        if (poll.done) { this.result = poll.result; this.id = ''; return poll.code; }
        await new Promise<void>((resolve): void => { setTimeout(resolve, 60); });
      }
      throw new Error('Planner 请求已取消');
    } finally { this.cancel(); }
  }

  async complete(url: string, key: string, body: string, timeout: number): Promise<PlannerReply> {
    const code = await this.read(url, key, body, timeout, (_bytes: ArrayBuffer): void => {});
    return { responseCode: code, result: this.result };
  }
}
