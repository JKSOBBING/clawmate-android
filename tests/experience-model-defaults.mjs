import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';

function load(name, exports, env = {}) {
  const url = new URL('../shared-frontend/adapters/platform/' + name + '.ets', import.meta.url);
  const source = fs.readFileSync(url, 'utf8').replace(/^import .*;\r?\n/gm, '').replace(/^export /gm, '');
  return runInNewContext(stripTypeScriptTypes(source) + '\n({' + exports + '})', env);
}
const { InstalledModels, validatePlanner, plannerTestFailure } = load('InstalledModels',
  'InstalledModels, validatePlanner, plannerTestFailure');
const profile = {
  cloudPlannerBaseUrl: 'http://models.invalid:9003', cloudPlannerModel: '', cloudPlannerApiKey: 'fixture-chat',
  cloudDeciderBaseUrl: 'http://models.invalid:8080', cloudDeciderModel: '', cloudDeciderApiKey: 'fixture-vision'
};
const stored = new Map(), memory = new Map();
const pref = {
  hasSync: key => stored.has(key), getSync: (key, fallback) => stored.get(key) ?? fallback,
  putSync: (key, value) => stored.set(key, value), flushSync() {}
};
const { SettingsStore } = load('SettingsStore', 'SettingsStore', {
  InstalledModels, preferences: { getPreferencesSync: () => pref }, SETTINGS_STRING_KEYS: Object.keys(profile),
  SETTINGS_BOOLEAN_KEYS: [], AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value) }
});
const init = () => SettingsStore.initialize({}, JSON.stringify(profile));
assert.throws(() => validatePlanner(profile.cloudPlannerBaseUrl, ''), /HTTP/);
init();
assert.equal(memory.get('cloudPlannerBaseUrl'), profile.cloudPlannerBaseUrl);
assert.equal(memory.get('cloudDeciderBaseUrl'), profile.cloudDeciderBaseUrl);
assert.equal(memory.get('cloudPlannerModel'), '');
assert.ok(!stored.has('cloudPlannerApiKey') && !stored.has('cloudDeciderApiKey'));
validatePlanner(profile.cloudPlannerBaseUrl + '/v1', '');
validatePlanner('https://custom.invalid/v1', '');
assert.throws(() => validatePlanner('', ''), /请填写 Planner 服务地址/);
for (const bad of ['http://other.invalid:9003','http://models.invalid:8080','http://models.invalid:9003/other',
  'http://user@models.invalid:9003','http://models.invalid:9003/v1?next=1']) {
  assert.throws(() => validatePlanner(bad, ''), /未获放行/);
  assert.equal(InstalledModels.key('Planner', bad), '');
}
assert.equal(InstalledModels.key('Planner', profile.cloudPlannerBaseUrl), profile.cloudPlannerApiKey);
assert.equal(InstalledModels.key('Decider', profile.cloudDeciderBaseUrl), profile.cloudDeciderApiKey);
assert.equal(InstalledModels.key('Planner', 'https://custom.invalid', 'explicit'), 'explicit');
assert.equal(InstalledModels.key('Planner', 'https://custom.invalid'), '');
assert.equal(InstalledModels.key('Planner', 'https://custom.invalid', profile.cloudPlannerApiKey), profile.cloudPlannerApiKey);
assert.match(plannerTestFailure('Cloud HTTP 400 [model_required]', ''), /要求填写模型名/);
assert.match(plannerTestFailure('Cloud HTTP 401', ''), /API Key/);
assert.match(plannerTestFailure('Cloud HTTP 400 [unclassified]', ''), /接口兼容性/);
stored.clear(); memory.clear();
stored.set('cloudPlannerBaseUrl', 'https://previous.invalid'); stored.set('cloudPlannerModel', 'custom');
init(); assert.equal(memory.get('cloudPlannerBaseUrl'), 'https://previous.invalid');
assert.equal(memory.get('cloudPlannerModel'), 'custom'); assert.equal(memory.get('cloudDeciderBaseUrl'), profile.cloudDeciderBaseUrl);
stored.set('cloudPlannerBaseUrl', ''); memory.clear(); init(); assert.equal(memory.get('cloudPlannerBaseUrl'), '');
console.log('PASS fresh install, upgrades, explicit clearing, role-specific credentials, pinned origin and actionable errors');
