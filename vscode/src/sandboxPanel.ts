import * as vscode from "vscode";
import { SandboxClient, describeSandboxError } from "./sandboxClient";

interface PanelMessage {
  action: string;
  requestId?: number;
  payload?: Record<string, unknown>;
}

export class SandboxPanel implements vscode.Disposable {
  private panel?: vscode.WebviewPanel;

  constructor(private readonly client: SandboxClient) {}

  show(): void {
    if (this.panel) {
      this.panel.reveal();
      return;
    }
    const panel = vscode.window.createWebviewPanel(
      "datapackSandbox.panel",
      "Datapack Sandbox",
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true },
    );
    this.panel = panel;
    panel.webview.html = html(panel.webview);
    panel.webview.onDidReceiveMessage((message: PanelMessage) => void this.handle(message));
    panel.onDidDispose(() => { this.panel = undefined; });
  }

  dispose(): void {
    this.panel?.dispose();
  }

  private async handle(message: PanelMessage): Promise<void> {
    try {
      const payload = message.payload ?? {};
      const result = await this.perform(message.action, payload);
      await this.panel?.webview.postMessage({ ok: true, action: message.action, requestId: message.requestId, result });
    } catch (error) {
      await this.panel?.webview.postMessage({
        ok: false,
        action: message.action,
        requestId: message.requestId,
        error: describeSandboxError(error),
      });
    }
  }

  private async perform(action: string, payload: Record<string, unknown>): Promise<unknown> {
    switch (action) {
      case "status": {
        const metadata = await this.client.request<{ versions: Array<Record<string, unknown>>; default: string }>("versions");
        return this.client.hasActiveSandbox ? { active: true, data: await this.inspect(), profiles: metadata.versions, defaultVersion: metadata.default } : { active: false, profiles: metadata.versions, defaultVersion: metadata.default };
      }
      case "start": {
        const data = await this.start(payload);
        return { active: true, data, summary: sandboxSummary(data) };
      }
      case "stop": this.client.close(); return { active: false, summary: "Sandbox stopped. Persistent world state was discarded." };
      case "reload": return this.executeAndInspect("Datapacks reloaded", "reload", { keepWorld: payload.keepWorld !== false });
      case "command": return this.executeAndInspect("Command completed", "runCommand", { command: String(payload.value ?? "") });
      case "function": return this.executeAndInspect("Function completed", "runFunction", { id: String(payload.value ?? "") });
      case "tick": return this.executeAndInspect("Ticks advanced", "tick", { count: Number(payload.value || 1) });
      case "load": return this.executeAndInspect("Load functions completed", "load");
      case "event": return this.executeAndInspect("Player event injected", "injectPlayerEvent", { event: parseJsonPayload(payload.value, "Player event") });
      case "fixture": return this.executeAndInspect("World fixture applied", "applyWorldFixture", { path: String(payload.value ?? "") });
      case "reset": {
        await this.client.request("resetWorld");
        const data = await this.inspect();
        return { active: true, data, summary: `World reset · Minecraft ${stateOf(data).version ?? "unknown"} · game time 0` };
      }
      case "inspect": return { active: true, data: await this.inspect(), summary: "Sandbox data refreshed." };
      case "completions": return this.client.request("completions", { buffer: String(payload.value ?? ""), cursor: Number(payload.cursor ?? 0) });
      case "checkCommand": return this.client.request("checkCommand", { command: String(payload.value ?? "") });
      case "openSource": await this.openSource(String(payload.file || ""), Number(payload.line || 1)); return { opened: true };
      default: throw new Error(`Unknown panel action: ${action}`);
    }
  }

  private async start(payload: Record<string, unknown>): Promise<Record<string, unknown>> {
    await this.client.create(String(payload.version || "26.2"), stringList(payload.packs));
    return this.inspect();
  }

  private async executeAndInspect(label: string, method: string, params: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
    const execution = await this.client.request<Record<string, unknown>>(method, params);
    const data = await this.inspect();
    return { active: true, data, summary: executionSummary(label, execution, data) };
  }

  private async inspect(): Promise<Record<string, unknown>> {
    const [state, outputs, traces, snapshot, resources] = await Promise.all([
      this.client.request("state"),
      this.client.request("outputs"),
      this.client.request("traces"),
      this.client.request("snapshot"),
      this.client.request("resources"),
    ]);
    const traceItems = (traces as { traces?: Array<{ errorCode?: string }> }).traces ?? [];
    const world = snapshot as Record<string, unknown>;
    return {
      state,
      outputs,
      traces,
      snapshot,
      resources,
      players: world.players,
      entities: world.entities,
      scores: world.scores,
      storage: world.storage,
      diagnostics: traceItems.filter((trace) => trace.errorCode),
    };
  }

  private async openSource(file: string, line: number): Promise<void> {
    if (!file || file.startsWith("<")) return;
    const document = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
    const editor = await vscode.window.showTextDocument(document, { preview: true });
    const position = new vscode.Position(Math.max(0, line - 1), 0);
    editor.selection = new vscode.Selection(position, position);
    editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
  }
}

function stateOf(data: Record<string, unknown>): Record<string, unknown> {
  return data.state && typeof data.state === "object" ? data.state as Record<string, unknown> : {};
}

function sandboxSummary(data: Record<string, unknown>): string {
  const state = stateOf(data);
  const resources = data.resources as { summary?: Record<string, unknown>; functionIds?: unknown[] } | undefined;
  const functions = Array.isArray(resources?.functionIds) ? resources.functionIds.length : Number(resources?.summary?.functions ?? 0);
  return `Minecraft ${state.version ?? "unknown"} sandbox started · ${plural(functions, "function")} loaded · ${plural(Number(state.entities ?? 0), "entity", "entities")}`;
}

function executionSummary(label: string, execution: Record<string, unknown>, data: Record<string, unknown>): string {
  const commands = Number(execution.commands ?? 0);
  const outputs = Array.isArray(execution.outputs) ? execution.outputs as Array<Record<string, unknown>> : [];
  const diffs = Array.isArray(execution.snapshotDiffs) ? execution.snapshotDiffs.length : 0;
  const state = stateOf(data);
  const lastOutput = outputs.at(-1)?.text;
  return [
    label,
    plural(commands, "command"),
    plural(outputs.length, "output"),
    `${diffs} state ${diffs === 1 ? "change" : "changes"}`,
    `game time ${state.gameTime ?? 0}`,
    typeof lastOutput === "string" && lastOutput ? `“${lastOutput}”` : "",
  ].filter(Boolean).join(" · ");
}

function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}

function stringList(value: unknown): string[] {
  return typeof value === "string" ? value.split(/[\r\n,]+/).map((item) => item.trim()).filter(Boolean) : [];
}

function parseJsonPayload(value: unknown, label: string): unknown {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} must be valid JSON.`);
  }
}

function html(webview: vscode.Webview): string {
  const nonce = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const version = vscode.workspace.getConfiguration("datapackSandbox").get("defaultVersion", "26.2");
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; script-src 'nonce-${nonce}'">
  <style>
    :root{color-scheme:light dark;--panel:color-mix(in srgb,var(--vscode-editor-background) 88%,var(--vscode-foreground) 12%);--panel-strong:color-mix(in srgb,var(--vscode-editor-background) 78%,var(--vscode-foreground) 22%);--muted:var(--vscode-descriptionForeground);--accent:var(--vscode-button-background);--accent-text:var(--vscode-button-foreground);--line:var(--vscode-panel-border,rgba(127,127,127,.25));--radius:14px}
    *{box-sizing:border-box}body{margin:0;padding:22px;color:var(--vscode-foreground);background:radial-gradient(circle at 90% -10%,color-mix(in srgb,var(--accent) 18%,transparent),transparent 34%),var(--vscode-editor-background);font:13px/1.5 var(--vscode-font-family);min-width:360px}
    button,input,select{font:inherit}button{cursor:pointer}button:disabled{cursor:not-allowed;opacity:.45}
    .shell{max-width:1120px;margin:0 auto}.hero{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:18px}.brand{display:flex;align-items:center;gap:13px}.mark{display:grid;place-items:center;width:44px;height:44px;border-radius:13px;background:linear-gradient(145deg,color-mix(in srgb,var(--accent) 90%,white 10%),color-mix(in srgb,var(--accent) 70%,black 30%));box-shadow:0 8px 28px color-mix(in srgb,var(--accent) 26%,transparent)}.mark svg{width:27px;height:27px;color:var(--accent-text)}h1{font-size:20px;line-height:1.1;margin:0 0 5px}.subtitle{color:var(--muted);font-size:12px}.connection{display:flex;align-items:center;gap:8px;padding:7px 11px;border:1px solid var(--line);border-radius:999px;background:var(--panel)}.dot{width:8px;height:8px;border-radius:50%;background:var(--muted)}.connection.active .dot{background:#35c46a;box-shadow:0 0 0 4px rgba(53,196,106,.14)}
    .grid{display:grid;grid-template-columns:minmax(280px,360px) 1fr;gap:16px}.card{border:1px solid var(--line);border-radius:var(--radius);background:color-mix(in srgb,var(--panel) 82%,transparent);box-shadow:0 8px 26px rgba(0,0,0,.08);overflow:hidden}.card-head{display:flex;align-items:center;justify-content:space-between;padding:15px 16px 10px}.card-title{font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}.card-body{padding:6px 16px 16px}
    .field{display:grid;gap:6px;margin-bottom:12px}.field label{font-size:12px;color:var(--muted)}input,select{width:100%;min-height:36px;padding:7px 10px;color:var(--vscode-input-foreground);background:var(--vscode-input-background);border:1px solid var(--vscode-input-border,var(--line));border-radius:8px;outline:none}input:focus,select:focus{border-color:var(--vscode-focusBorder);box-shadow:0 0 0 1px var(--vscode-focusBorder)}
    .actions{display:flex;gap:8px;flex-wrap:wrap}.btn{min-height:34px;border:1px solid var(--line);border-radius:8px;padding:6px 11px;color:var(--vscode-foreground);background:var(--panel-strong)}.btn:hover:not(:disabled){background:color-mix(in srgb,var(--panel-strong) 72%,var(--vscode-foreground) 28%)}.btn.primary{border-color:transparent;background:var(--accent);color:var(--accent-text);font-weight:600}.btn.danger{color:var(--vscode-errorForeground)}.btn.icon{width:34px;padding:0;display:grid;place-items:center}
    .operation{display:grid;grid-template-columns:130px 1fr auto;gap:8px;align-items:start}.command-entry{position:relative}.suggestions{position:absolute;z-index:20;top:calc(100% + 5px);left:0;right:0;max-height:240px;overflow:auto;padding:5px;border:1px solid var(--vscode-widget-border,var(--line));border-radius:9px;background:var(--vscode-editorSuggestWidget-background,var(--vscode-editor-background));box-shadow:0 10px 30px rgba(0,0,0,.28)}.suggestions[hidden]{display:none}.suggestion{display:grid;grid-template-columns:auto 1fr;gap:4px 10px;width:100%;padding:7px 9px;border:0;border-radius:6px;text-align:left;color:var(--vscode-editorSuggestWidget-foreground,var(--vscode-foreground));background:transparent}.suggestion:hover,.suggestion.selected{background:var(--vscode-editorSuggestWidget-selectedBackground,var(--vscode-list-activeSelectionBackground));color:var(--vscode-editorSuggestWidget-selectedForeground,var(--vscode-list-activeSelectionForeground))}.suggestion-value{font-family:var(--vscode-editor-font-family);font-weight:600}.suggestion-group{justify-self:end;color:var(--muted);font-size:10px}.suggestion-detail{grid-column:1 / -1;color:var(--muted);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.command-feedback{display:flex;align-items:center;gap:6px;min-height:18px;margin-top:7px;font-size:11px;color:var(--muted)}.command-feedback.ok{color:var(--vscode-testing-iconPassed,#35c46a)}.command-feedback.error{color:var(--vscode-errorForeground)}.command-feedback.checking::before{content:'';width:9px;height:9px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:spin .7s linear infinite}.hint{margin-top:8px;color:var(--muted);font-size:11px}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:14px}.stat{padding:11px;border-radius:10px;background:var(--panel);border:1px solid var(--line)}.stat-value{font-size:18px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.stat-label{font-size:10px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted)}
    .viewer{grid-column:2;grid-row:1 / span 2;min-height:600px;display:flex;flex-direction:column}.toolbar{display:flex;align-items:center;gap:8px;padding:12px 14px;border-bottom:1px solid var(--line)}.tabs{display:flex;gap:4px;overflow:auto;scrollbar-width:thin}.tab{white-space:nowrap;border:0;border-radius:7px;padding:6px 9px;color:var(--muted);background:transparent;text-transform:capitalize}.tab:hover{color:var(--vscode-foreground);background:var(--panel)}.tab.active{color:var(--accent-text);background:var(--accent)}.spacer{flex:1}.content{flex:1;min-height:0;padding:14px;overflow:auto}.empty{display:grid;place-items:center;height:100%;min-height:420px;text-align:center;color:var(--muted)}.empty-symbol{font-size:36px;opacity:.55;margin-bottom:8px}
    .tree{font-family:var(--vscode-editor-font-family);font-size:12px}.tree details{margin-left:14px}.tree>details{margin-left:0}.tree summary{cursor:pointer;padding:3px 5px;border-radius:5px;list-style:none}.tree summary:hover{background:var(--panel)}.tree summary::-webkit-details-marker{display:none}.chevron{display:inline-block;width:14px;color:var(--muted)}details[open]>summary .chevron{transform:rotate(90deg)}.key{color:var(--vscode-symbolIcon-propertyForeground,#9cdcfe)}.string{color:var(--vscode-debugTokenExpression-string,#ce9178)}.number{color:var(--vscode-debugTokenExpression-number,#b5cea8)}.boolean{color:var(--vscode-debugTokenExpression-boolean,#569cd6)}.null{color:var(--muted)}.meta{color:var(--muted);margin-left:6px}.leaf{padding:3px 5px 3px 19px;word-break:break-word}.source-link{margin:9px 0 0 19px;border:0;background:transparent;color:var(--vscode-textLink-foreground);padding:0}.source-link:hover{text-decoration:underline}
    .error-panel{display:grid;grid-template-columns:auto 1fr auto;gap:10px 12px;margin-bottom:16px;padding:13px 14px;border:1px solid color-mix(in srgb,var(--vscode-errorForeground) 48%,var(--line));border-radius:11px;background:color-mix(in srgb,var(--vscode-errorForeground) 8%,var(--panel));color:var(--vscode-foreground)}.error-panel[hidden]{display:none}.error-icon{color:var(--vscode-errorForeground);font-size:18px}.error-title{font-weight:700}.error-message{margin-top:2px}.error-detail,.error-hint{grid-column:2 / -1;color:var(--muted);white-space:pre-wrap}.error-code{font-family:var(--vscode-editor-font-family);font-size:11px;color:var(--vscode-errorForeground)}.error-close{align-self:start;border:0;background:transparent;color:var(--muted);font-size:18px;padding:0 4px}.execution-result{min-height:38px;margin-top:12px;padding:9px 11px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--muted)}.execution-result.success{color:var(--vscode-testing-iconPassed,#35c46a);border-color:color-mix(in srgb,var(--vscode-testing-iconPassed,#35c46a) 35%,var(--line))}.toast{position:fixed;right:20px;bottom:20px;max-width:min(420px,calc(100vw - 40px));padding:10px 13px;border:1px solid var(--line);border-radius:9px;background:var(--vscode-notifications-background);box-shadow:0 10px 35px rgba(0,0,0,.25);transform:translateY(20px);opacity:0;pointer-events:none;transition:.18s ease}.toast.show{transform:none;opacity:1}.toast.error{color:var(--vscode-errorForeground);border-color:color-mix(in srgb,var(--vscode-errorForeground) 45%,var(--line))}.busy::after{content:'';display:inline-block;width:10px;height:10px;margin-left:8px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:spin .7s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
    @media(max-width:760px){body{padding:14px}.hero{align-items:flex-start}.grid{grid-template-columns:1fr}.viewer{grid-column:1;grid-row:auto;min-height:520px}.stats{grid-template-columns:repeat(2,1fr)}.operation{grid-template-columns:1fr}.operation .btn{width:100%}}
  </style>
</head>
<body>
  <main class="shell">
    <header class="hero">
      <div class="brand"><div class="mark"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M9 4h14v5l-3 4v3.5l6 9.5H6l6-9.5V13L9 9V4Z" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round"/><path d="M10 22h12M13 8h6" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg></div><div><h1>Datapack Sandbox</h1><div class="subtitle">Persistent runtime control and inspection</div></div></div>
      <div id="connection" class="connection"><span class="dot"></span><span id="connectionText">Checking…</span></div>
    </header>
    <section id="errorPanel" class="error-panel" role="alert" hidden><div class="error-icon">!</div><div><div id="errorTitle" class="error-title"></div><div id="errorMessage" class="error-message"></div></div><button id="errorClose" class="error-close" title="Dismiss">×</button><div id="errorCode" class="error-code"></div><div id="errorDetail" class="error-detail"></div><div id="errorHint" class="error-hint"></div></section>
    <div class="grid">
      <section class="card">
        <div class="card-head"><div class="card-title">Sandbox</div></div>
        <div class="card-body">
          <div class="field"><label for="version">Minecraft profile</label><select id="version"><option value="${version}">${version} (configured default)</option></select></div>
          <div class="field"><label for="packs">Datapack paths</label><input id="packs" placeholder="One path per line or comma" autocomplete="off"></div>
          <div class="actions"><button class="btn primary" data-action="start">Start sandbox</button><button class="btn" data-action="reload" data-requires-active>Reload</button><button class="btn" data-action="reset" data-requires-active>Reset world</button><button class="btn danger" data-action="stop" data-requires-active>Stop</button></div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><div class="card-title">Execute</div></div>
        <div class="card-body">
          <div class="operation"><select id="operation"><option value="command">Command</option><option value="function">Function</option><option value="tick">Ticks</option><option value="event">Player event</option><option value="fixture">World fixture</option></select><div class="command-entry"><input id="value" placeholder="Enter a command" autocomplete="off" spellcheck="false" aria-autocomplete="list" aria-controls="suggestions"><div id="suggestions" class="suggestions" role="listbox" hidden></div><div id="commandFeedback" class="command-feedback">Start a sandbox for live completion and checks.</div></div><button id="run" class="btn primary" data-requires-active>Run</button></div>
          <div id="operationHint" class="hint">Type to see sandbox-aware command suggestions. Use ↑/↓ and Tab to accept.</div>
          <div id="executionResult" class="execution-result">No operation has been run yet.</div>
          <div class="actions" style="margin-top:12px"><button class="btn" data-action="load" data-requires-active>Run load</button><button class="btn" data-action="inspect" data-requires-active>Refresh data</button></div>
        </div>
      </section>
      <section class="card viewer">
        <div class="card-head"><div class="card-title">Inspector</div><button class="btn icon" data-action="inspect" data-requires-active title="Refresh">↻</button></div>
        <div class="card-body" style="padding-bottom:0"><div class="stats"><div class="stat"><div id="statVersion" class="stat-value">—</div><div class="stat-label">Version</div></div><div class="stat"><div id="statTime" class="stat-value">—</div><div class="stat-label">Game time</div></div><div class="stat"><div id="statEntities" class="stat-value">—</div><div class="stat-label">Entities</div></div><div class="stat"><div id="statErrors" class="stat-value">—</div><div class="stat-label">Diagnostics</div></div></div></div>
        <div class="toolbar"><div id="tabs" class="tabs"></div><span class="spacer"></span></div>
        <div id="content" class="content"><div class="empty"><div><div class="empty-symbol">◇</div><strong>No active sandbox</strong><div>Start one to inspect its runtime state.</div></div></div></div>
      </section>
    </div>
  </main>
  <div id="toast" class="toast" role="status"></div>
  <script nonce="${nonce}">
    const api=acquireVsCodeApi();
    const names=['state','outputs','traces','snapshot','resources','players','entities','scores','storage','diagnostics'];
    const labels={state:'State',outputs:'Output',traces:'Trace',snapshot:'Snapshot',resources:'Resources',players:'Players',entities:'Entities',scores:'Scores',storage:'Storage',diagnostics:'Diagnostics'};
    const hints={command:'Example: scoreboard players set #test runs 1',function:'Example: demo:main',tick:'Number of ticks to advance',event:'JSON object describing a player event',fixture:'Path to a world fixture JSON file'};
    let activeTab='state',data={},active=false,sequence=0,pending=0,toastTimer,assistTimer,selectedSuggestion=-1,suggestionItems=[];
    const latestAssist={completions:0,checkCommand:0};
    const byId=(id)=>document.getElementById(id);const tabs=byId('tabs'),content=byId('content'),toast=byId('toast'),valueInput=byId('value'),suggestions=byId('suggestions'),feedback=byId('commandFeedback'),errorPanel=byId('errorPanel'),executionResult=byId('executionResult');
    for(const name of names){const button=document.createElement('button');button.className='tab';button.textContent=labels[name];button.dataset.tab=name;button.addEventListener('click',()=>{activeTab=name;render()});tabs.appendChild(button)}
    document.querySelectorAll('[data-action]').forEach((button)=>button.addEventListener('click',()=>send(button.dataset.action)));
    byId('errorClose').addEventListener('click',hideError);
    byId('run').addEventListener('click',()=>send(byId('operation').value));
    valueInput.addEventListener('input',scheduleAssist);
    valueInput.addEventListener('blur',()=>setTimeout(()=>hideSuggestions(),120));
    valueInput.addEventListener('keydown',(event)=>{if(!suggestions.hidden&&['ArrowDown','ArrowUp','Tab','Enter','Escape'].includes(event.key)){if(event.key==='Escape'){hideSuggestions();return}if(event.key==='ArrowDown'||event.key==='ArrowUp'){event.preventDefault();moveSuggestion(event.key==='ArrowDown'?1:-1);return}if((event.key==='Tab'||event.key==='Enter')&&selectedSuggestion>=0){event.preventDefault();applySuggestion(suggestionItems[selectedSuggestion]);return}}if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();byId('run').click()}});
    byId('operation').addEventListener('change',()=>{const operation=byId('operation').value;byId('operationHint').textContent=operation==='command'?'Type to see sandbox-aware command suggestions. Use ↑/↓ and Tab to accept.':hints[operation];valueInput.placeholder=operation==='command'?'Enter a command':operation==='event'?'Enter JSON':'Enter a value';hideSuggestions();feedback.textContent='';feedback.className='command-feedback';if(operation==='command')scheduleAssist()});
    function payload(){return{version:byId('version').value,packs:byId('packs').value,value:byId('value').value}}
    function send(action,extra={}){hideError();const requestId=++sequence;pending++;setBusy(true);api.postMessage({action,requestId,payload:{...payload(),...extra}})}
    function sendAssist(action,extra={}){const requestId=++sequence;latestAssist[action]=requestId;api.postMessage({action,requestId,payload:{...payload(),...extra}})}
    function setBusy(value){document.body.classList.toggle('busy',value&&pending>0);document.querySelectorAll('button').forEach((button)=>{if(pending>0)button.disabled=true;else button.disabled=button.hasAttribute('data-requires-active')&&!active})}
    function setActive(value){active=value;byId('connection').classList.toggle('active',value);byId('connectionText').textContent=value?'Sandbox running':'Sandbox stopped';setBusy(false)}
    function notify(message,error=false){clearTimeout(toastTimer);toast.textContent=message;toast.className='toast show'+(error?' error':'');toastTimer=setTimeout(()=>toast.className='toast',3200)}
    window.addEventListener('message',(event)=>{const message=event.data;if(message.action==='completions'||message.action==='checkCommand'){if(message.requestId!==latestAssist[message.action])return;if(!message.ok){if(message.action==='checkCommand')showCheck({valid:false,message:message.error?.message||'Command check failed.'});return}if(message.action==='completions')showSuggestions(message.result);else showCheck(message.result);return}pending=Math.max(0,pending-1);if(!message.ok){setBusy(false);showError(message.error);return}if(message.action==='openSource'){setBusy(false);return}hideError();const result=message.result||{};if(Array.isArray(result.profiles))setProfiles(result.profiles,result.defaultVersion);if(typeof result.active==='boolean')setActive(result.active);if(result.data)data=result.data;if(result.summary)showSummary(result.summary);else if(message.action!=='status')showSummary(actionLabel(message.action));if(message.action!=='status')notify(actionLabel(message.action));render();if(message.action==='start'||message.action==='command'||message.action==='reset'||message.action==='reload')scheduleAssist()});
    function setProfiles(profiles,defaultVersion){const select=byId('version');const current=select.value||defaultVersion;select.replaceChildren();for(const profile of profiles){const option=document.createElement('option');option.value=profile.id;option.textContent=profile.id+' · pack '+profile.packFormat+' · Java '+profile.javaMajor;select.appendChild(option)}select.value=profiles.some((profile)=>profile.id===current)?current:defaultVersion}
    function showError(error){const details=typeof error==='object'&&error?error:{title:'Operation failed',message:String(error||'Unknown error')};byId('errorTitle').textContent=details.title||'Operation failed';byId('errorMessage').textContent=details.message||'The sandbox did not complete the operation.';byId('errorCode').textContent=details.code?'Error code: '+details.code:'';byId('errorDetail').textContent=details.detail||'';byId('errorHint').textContent=details.hint?'Try this: '+details.hint:'';errorPanel.hidden=false;executionResult.textContent=(details.title||'Operation failed')+' · '+(details.message||'');executionResult.className='execution-result';notify(details.title||'Operation failed',true)}
    function hideError(){errorPanel.hidden=true}
    function showSummary(summary){executionResult.textContent=summary;executionResult.className='execution-result success'}
    function scheduleAssist(){clearTimeout(assistTimer);hideSuggestions();if(!active||byId('operation').value!=='command'){feedback.textContent=active?'':'Start a sandbox for live completion and checks.';feedback.className='command-feedback';return}const value=valueInput.value;if(!value.trim()){feedback.textContent='Enter a command to check.';feedback.className='command-feedback';return}feedback.textContent='Checking command…';feedback.className='command-feedback checking';assistTimer=setTimeout(()=>{sendAssist('completions',{cursor:valueInput.selectionStart??value.length});sendAssist('checkCommand')},140)}
    function showSuggestions(result){suggestionItems=(result?.suggestions??[]).slice(0,40);suggestions.replaceChildren();selectedSuggestion=suggestionItems.length?0:-1;for(const [index,item] of suggestionItems.entries()){const button=document.createElement('button');button.type='button';button.className='suggestion'+(index===selectedSuggestion?' selected':'');button.setAttribute('role','option');const name=document.createElement('span');name.className='suggestion-value';name.textContent=item.value;const group=document.createElement('span');group.className='suggestion-group';group.textContent=item.group??'';button.append(name,group);if(item.description){const detail=document.createElement('span');detail.className='suggestion-detail';detail.textContent=item.description+(item.behavior?' · '+item.behavior:'');button.appendChild(detail)}button.addEventListener('mousedown',(event)=>event.preventDefault());button.addEventListener('click',()=>applySuggestion(item));suggestions.appendChild(button)}suggestions.hidden=!suggestionItems.length}
    function hideSuggestions(){suggestions.hidden=true;selectedSuggestion=-1}
    function moveSuggestion(delta){if(!suggestionItems.length)return;selectedSuggestion=(selectedSuggestion+delta+suggestionItems.length)%suggestionItems.length;[...suggestions.children].forEach((item,index)=>item.classList.toggle('selected',index===selectedSuggestion));suggestions.children[selectedSuggestion]?.scrollIntoView({block:'nearest'})}
    function applySuggestion(item){const start=item.start??valueInput.selectionStart??0,end=item.end??valueInput.selectionStart??0,suffix=item.appendSpace?' ':'';valueInput.setRangeText(item.value+suffix,start,end,'end');hideSuggestions();valueInput.focus();scheduleAssist()}
    function showCheck(result){feedback.textContent=(result?.valid?'✓ ':'! ')+(result?.message??'Unable to check command.');feedback.className='command-feedback '+(result?.valid?'ok':'error')}
    function actionLabel(action){return({start:'Sandbox started',stop:'Sandbox stopped',reload:'Datapacks reloaded',reset:'World reset',inspect:'Data refreshed',command:'Command completed',function:'Function completed',tick:'Ticks advanced',event:'Event injected',fixture:'Fixture applied',load:'Load functions completed'})[action]||'Done'}
    function render(){[...tabs.children].forEach((button)=>button.classList.toggle('active',button.dataset.tab===activeTab));const state=data.state||{};byId('statVersion').textContent=state.version??'—';byId('statTime').textContent=state.gameTime??'—';byId('statEntities').textContent=state.entities??data.entities?.length??'—';byId('statErrors').textContent=data.diagnostics?.length??'—';content.replaceChildren();if(!active){content.appendChild(emptyView('No active sandbox','Start one to inspect its runtime state.'));return}const value=data[activeTab];if(value===undefined||value===null||(Array.isArray(value)&&value.length===0)){content.appendChild(emptyView('Nothing here yet','Run a command or refresh the sandbox data.'));return}const tree=document.createElement('div');tree.className='tree';tree.appendChild(treeNode(labels[activeTab],value,true));content.appendChild(tree)}
    function emptyView(title,message){const root=document.createElement('div');root.className='empty';const inner=document.createElement('div');const symbol=document.createElement('div');symbol.className='empty-symbol';symbol.textContent='◇';const heading=document.createElement('strong');heading.textContent=title;const description=document.createElement('div');description.textContent=message;inner.append(symbol,heading,description);root.appendChild(inner);return root}
    function treeNode(key,value,open=false){if(value!==null&&typeof value==='object'){const details=document.createElement('details');details.open=open;const summary=document.createElement('summary');const chevron=document.createElement('span');chevron.className='chevron';chevron.textContent='›';const keyNode=document.createElement('span');keyNode.className='key';keyNode.textContent=key;const meta=document.createElement('span');meta.className='meta';meta.textContent=Array.isArray(value)?'['+value.length+']':'{'+Object.keys(value).length+'}';summary.append(chevron,keyNode,meta);details.appendChild(summary);Object.entries(value).forEach(([childKey,child])=>details.appendChild(treeNode(childKey,child)));if(value.source?.file&&!String(value.source.file).startsWith('<')){const link=document.createElement('button');link.className='source-link';link.textContent='Open '+value.source.file+':'+(value.source.line??1);link.addEventListener('click',()=>send('openSource',{file:value.source.file,line:value.source.line??1}));details.appendChild(link)}return details}const leaf=document.createElement('div');leaf.className='leaf';const keyNode=document.createElement('span');keyNode.className='key';keyNode.textContent=key+': ';const valueNode=document.createElement('span');const type=value===null?'null':typeof value;valueNode.className=type;valueNode.textContent=typeof value==='string'?'"'+value+'"':String(value);leaf.append(keyNode,valueNode);return leaf}
    send('status');
  </script>
</body>
</html>`;
}
