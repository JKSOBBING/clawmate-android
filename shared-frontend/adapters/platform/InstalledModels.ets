import { UpstreamPlanner } from './UpstreamPlanner';

export class InstalledModels {
  private static profile: Record<string, string> = {};

  static configure(profile: Record<string, string>): void { InstalledModels.profile = profile; }

  static isPlannerService(value: string): boolean {
    return InstalledModels.matchesService(InstalledModels.profile.cloudPlannerBaseUrl, value);
  }

  private static matchesService(origin: string, value: string): boolean {
    return !!origin && [origin, origin + '/v1', origin + '/v1/chat/completions'].includes(value.trim().replace(/\/$/, ''));
  }

  static openPlanner(url: string): UpstreamPlanner | null {
    return InstalledModels.isPlannerService(url) ? new UpstreamPlanner() : null;
  }

  static key(role: string, baseUrl: string, overrideKey: string = ''): string {
    const prefix = 'cloud' + role;
    const deployed = InstalledModels.profile[prefix + 'BaseUrl'];
    const builtIn = InstalledModels.profile[prefix + 'ApiKey'] || '';
    // This value came from the editable settings. Bundled keys are never copied into those fields.
    if (overrideKey) { return overrideKey; }
    return InstalledModels.matchesService(deployed, baseUrl) ? builtIn : '';
  }
}

export function validatePlanner(baseUrl: string, model: string): void {
  if (!baseUrl) { throw new Error('请填写 Planner 服务地址'); }
  if (model.length > 180) { throw new Error('Planner 模型名不能超过 180 个字符'); }
  if (InstalledModels.isPlannerService(baseUrl)) { return; }
  if (/^http:\/\//i.test(baseUrl)) { throw new Error('此 HTTP 地址未获放行，请使用内置 Planner 或填写 HTTPS 服务地址'); }
  if (!/^https:\/\/(?:[A-Za-z0-9.-]+|\[[0-9a-fA-F:]+\])(?::[0-9]{1,5})?(?:[/?][^#\s\\]*)?$/.test(baseUrl)) {
    throw new Error('Planner 地址格式有误，请填写完整的 HTTPS 服务地址');
  }
}

export function plannerTestFailure(message: string, model: string): string {
  if (/Cloud HTTP (401|403)\b/.test(message)) { return 'Planner 服务拒绝访问，请核对 API Key 和该模型的访问权限'; }
  if (/Cloud HTTP 404\b/.test(message)) { return 'Planner 接口或模型不存在，请核对服务地址和模型名（HTTP 404）'; }
  if (/Cloud HTTP 400\b/.test(message)) {
    if (!model.trim() && message.includes('[model_required]')) { return '此 Planner 服务要求填写模型名，请输入该服务支持的模型名（HTTP 400）'; }
    if (message.includes('[model_unavailable]')) { return '该 Planner 模型不可用，请核对模型名（HTTP 400）'; }
    return 'Planner 拒绝了测试请求，请核对模型名和接口兼容性（HTTP 400）';
  }
  if (/Cloud HTTP 429\b/.test(message)) { return 'Planner 请求受限，请检查额度或稍后重试（HTTP 429）'; }
  if (/Cloud HTTP 5[0-9]{2}\b/.test(message)) { return 'Planner 服务暂时不可用，请稍后重试'; }
  if (/timeout|timed out|超时/i.test(message)) { return '连接 Planner 超时，请检查网络和服务状态'; }
  if (/connect|resolve host|certificate|SSL|TLS|network|23000/i.test(message)) { return '无法连接 Planner，请检查网络、HTTPS 地址和证书'; }
  return message;
}
