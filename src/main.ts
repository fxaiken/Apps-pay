import "./style.css";
import type Anthropic from "@anthropic-ai/sdk";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { buildSystemPrompt, friendlyError, runTurn } from "./claude";
import { fileToBlock, type PendingAttachment } from "./attachments";
import {
  type Conversation,
  type Effort,
  type ModelId,
  type Persona,
  MODELS,
  deleteConversation,
  listConversations,
  loadMemories,
  loadPersonas,
  loadSettings,
  newId,
  saveConversation,
  saveCustomPersonas,
  saveMemories,
  saveSettings,
} from "./store";

type BetaMessageParam = Anthropic.Beta.BetaMessageParam;
type AnyBlock = { type: string; [key: string]: unknown };

marked.setOptions({ gfm: true, breaks: true });
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "A") {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener noreferrer");
  }
});

const state = {
  settings: loadSettings(),
  personas: loadPersonas(),
  conversations: [] as Conversation[],
  current: null as Conversation | null,
  attachments: [] as PendingAttachment[],
  busy: false,
  abort: null as AbortController | null,
  streaming: null as Anthropic.Beta.BetaMessage | null,
  notice: null as { kind: "error" | "info"; text: string } | null,
};

// ---------- helpers ----------

function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string | boolean | ((e: Event) => void)> = {},
  ...children: (Node | string | null | undefined | false)[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (typeof value === "function") el.addEventListener(key.replace(/^on/, ""), value);
    else if (value === true) el.setAttribute(key, "");
    else if (value !== false) el.setAttribute(key, value);
  }
  for (const child of children) if (child) el.append(child);
  return el;
}

function markdown(text: string): HTMLElement {
  const div = h("div", { class: "md" });
  div.innerHTML = DOMPurify.sanitize(marked.parse(text, { async: false }));
  for (const pre of div.querySelectorAll("pre")) {
    const btn = h("button", { class: "copy-code", title: "Copiar código" }, "Copiar");
    const code = pre.querySelector("code")?.textContent ?? "";
    btn.addEventListener("click", () => copy(code, btn));
    pre.append(btn);
  }
  return div;
}

async function copy(text: string, btn: HTMLElement): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    const old = btn.textContent;
    btn.textContent = "Copiado ✓";
    setTimeout(() => (btn.textContent = old), 1500);
  } catch {
    /* clipboard unavailable */
  }
}

function persona(id: string): Persona {
  return state.personas.find((p) => p.id === id) ?? state.personas[0];
}

function blocksOf(msg: BetaMessageParam): AnyBlock[] {
  return typeof msg.content === "string" ? [{ type: "text", text: msg.content }] : (msg.content as AnyBlock[]);
}

function conversationTitle(text: string): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > 48 ? clean.slice(0, 48) + "…" : clean || "Nova conversa";
}

// ---------- rendering ----------

const app = document.getElementById("app")!;
let messagesEl: HTMLElement;
let streamingEl: HTMLElement | null = null;
let rafPending = false;

function render(): void {
  app.replaceChildren(
    h("div", { class: "layout" }, renderSidebar(), renderMain()),
    h("div", { class: "scrim", onclick: () => document.body.classList.remove("drawer-open") }),
  );
  scrollToBottom(true);
}

function renderSidebar(): HTMLElement {
  const list = h("nav", { class: "conv-list" });
  for (const conv of state.conversations) {
    const item = h(
      "div",
      { class: "conv-item" + (conv.id === state.current?.id ? " active" : "") },
      h("button", { class: "conv-open", onclick: () => openConversation(conv.id) }, `${persona(conv.personaId).emoji} ${conv.title}`),
      h("button", { class: "icon-btn small", title: "Apagar conversa", onclick: () => removeConversation(conv.id) }, "✕"),
    );
    list.append(item);
  }
  if (!state.conversations.length) list.append(h("p", { class: "muted pad" }, "Ainda não há conversas."));

  return h(
    "aside",
    { class: "sidebar" },
    h("div", { class: "brand" }, h("img", { src: "./icon.svg", alt: "" }), "Claude Chat"),
    h("button", { class: "btn primary block", onclick: () => newConversation() }, "＋ Nova conversa"),
    list,
    h(
      "div",
      { class: "sidebar-footer" },
      h("button", { class: "btn ghost block", onclick: openPersonas }, "🎭 Personas"),
      h("button", { class: "btn ghost block", onclick: openMemories }, "🧠 Memória"),
      h("button", { class: "btn ghost block", onclick: openSettings }, "⚙️ Definições"),
    ),
  );
}

function renderMain(): HTMLElement {
  const conv = state.current;
  const activePersona = persona(conv?.personaId ?? state.settings.activePersonaId);
  const header = h(
    "header",
    { class: "topbar" },
    h("button", { class: "icon-btn menu-btn", title: "Menu", onclick: () => document.body.classList.add("drawer-open") }, "☰"),
    h("div", { class: "title" }, conv?.title ?? "Nova conversa"),
    h(
      "button",
      { class: "chip", title: "Mudar persona", onclick: openPersonas, disabled: !!conv?.messages.length },
      `${activePersona.emoji} ${activePersona.name}`,
    ),
  );

  messagesEl = h("section", { class: "messages" });
  if (!conv || !conv.messages.length) {
    messagesEl.append(renderEmpty(activePersona));
  } else {
    for (const msg of conv.messages) {
      const el = renderMessage(msg);
      if (el) messagesEl.append(el);
    }
  }
  if (state.streaming) {
    streamingEl = renderAssistantBlocks(state.streaming.content as unknown as AnyBlock[], true);
    messagesEl.append(streamingEl);
  } else if (state.busy) {
    streamingEl = h("div", { class: "msg assistant" }, h("div", { class: "typing" }, h("span"), h("span"), h("span")));
    messagesEl.append(streamingEl);
  }
  if (state.notice) {
    messagesEl.append(h("div", { class: `notice ${state.notice.kind}` }, state.notice.text));
  }

  return h("main", { class: "main" }, header, messagesEl, renderComposer());
}

function renderEmpty(p: Persona): HTMLElement {
  const suggestions = [
    "Quais são as notícias de hoje sobre tecnologia?",
    "Explica-me como funciona a inflação, com exemplos simples",
    "Ajuda-me a escrever um email profissional",
    "Lembra-te de que prefiro respostas curtas",
  ];
  return h(
    "div",
    { class: "empty" },
    h("div", { class: "empty-emoji" }, p.emoji),
    h("h1", {}, "Como posso ajudar?"),
    h("p", { class: "muted" }, `A falar com: ${p.name}`),
    h(
      "div",
      { class: "suggestions" },
      ...suggestions.map((s) => h("button", { class: "suggestion", onclick: () => send(s) }, s)),
    ),
  );
}

function renderMessage(msg: BetaMessageParam): HTMLElement | null {
  const blocks = blocksOf(msg);
  if (msg.role === "assistant") return renderAssistantBlocks(blocks, false);

  // User turns that only carry tool results are internal plumbing.
  if (blocks.every((b) => b.type === "tool_result")) return null;
  const bubble = h("div", { class: "bubble" });
  const files = h("div", { class: "attachments" });
  for (const b of blocks) {
    if (b.type === "text") bubble.append(h("div", { class: "plain" }, String(b.text)));
    if (b.type === "image") {
      const src = b.source as { media_type: string; data: string };
      files.append(h("img", { class: "thumb", src: `data:${src.media_type};base64,${src.data}`, alt: "imagem" }));
    }
    if (b.type === "document") files.append(h("span", { class: "file-chip" }, `📄 ${String(b.title ?? "documento")}`));
  }
  return h("div", { class: "msg user" }, files.childElementCount ? files : null, bubble);
}

function renderAssistantBlocks(blocks: AnyBlock[], live: boolean): HTMLElement {
  const wrap = h("div", { class: "msg assistant" + (live ? " live" : "") });
  const sources = new Map<string, string>();
  let fullText = "";

  for (const b of blocks) {
    switch (b.type) {
      case "thinking": {
        const text = String(b.thinking ?? "").trim();
        if (text) {
          wrap.append(
            h("details", { class: "thinking" }, h("summary", {}, live ? "A pensar…" : "Raciocínio"), markdown(text)),
          );
        }
        break;
      }
      case "text": {
        const text = String(b.text ?? "");
        fullText += text;
        wrap.append(markdown(text));
        for (const c of (b.citations as AnyBlock[] | null | undefined) ?? []) {
          if (typeof c.url === "string") sources.set(c.url, String(c.title ?? c.url));
        }
        break;
      }
      case "server_tool_use": {
        const input = b.input as { query?: string } | undefined;
        if (b.name === "web_search") wrap.append(h("div", { class: "tool-note" }, `🔎 A pesquisar: ${input?.query ?? "…"}`));
        break;
      }
      case "web_search_tool_result": {
        const content = b.content as AnyBlock[] | AnyBlock;
        if (Array.isArray(content)) {
          for (const r of content) if (typeof r.url === "string" && !sources.has(r.url)) sources.set(r.url, String(r.title ?? r.url));
        }
        break;
      }
      case "tool_use": {
        const input = b.input as { fact?: string; id?: string } | undefined;
        if (b.name === "memory_save") wrap.append(h("div", { class: "tool-note" }, `🧠 Memorizado: ${input?.fact ?? "…"}`));
        if (b.name === "memory_delete") wrap.append(h("div", { class: "tool-note" }, "🧠 Memória apagada"));
        break;
      }
      case "fallback":
        wrap.append(h("div", { class: "tool-note" }, "↪︎ Resposta continuada por outro modelo"));
        break;
    }
  }

  if (sources.size && !live) {
    const list = h("div", { class: "sources" }, h("div", { class: "sources-title" }, "Fontes"));
    let i = 1;
    for (const [url, title] of [...sources].slice(0, 8)) {
      list.append(h("a", { href: url, target: "_blank", rel: "noopener noreferrer" }, `${i++}. ${title}`));
    }
    wrap.append(list);
  }
  if (fullText && !live) {
    const btn = h("button", { class: "msg-action" }, "Copiar");
    btn.addEventListener("click", () => copy(fullText, btn));
    wrap.append(h("div", { class: "msg-actions" }, btn));
  }
  return wrap;
}

function renderComposer(): HTMLElement {
  const input = h("textarea", {
    id: "prompt",
    rows: "1",
    placeholder: state.settings.apiKey ? "Escreva uma mensagem…" : "Configure a chave de API ⚙️",
  }) as HTMLTextAreaElement;
  input.addEventListener("input", () => autoGrow(input));
  input.addEventListener("keydown", (e) => {
    const isTouch = matchMedia("(pointer: coarse)").matches;
    if (e.key === "Enter" && !e.shiftKey && !isTouch) {
      e.preventDefault();
      send(input.value);
    }
  });

  const fileInput = h("input", {
    type: "file",
    multiple: true,
    accept: "image/*,application/pdf,.txt,.md,.csv,.json,.js,.ts,.py,.java,.kt,.html,.css,.xml,.yaml,.yml,.log",
    hidden: true,
  }) as HTMLInputElement;
  fileInput.addEventListener("change", async () => {
    for (const file of fileInput.files ?? []) {
      try {
        state.attachments.push(await fileToBlock(file));
      } catch (err) {
        state.notice = { kind: "error", text: friendlyError(err) };
      }
    }
    fileInput.value = "";
    rerenderKeepingDraft();
  });

  const chips = h("div", { class: "pending" });
  state.attachments.forEach((a, i) =>
    chips.append(
      h(
        "span",
        { class: "file-chip" },
        a.preview ? h("img", { src: a.preview, alt: "" }) : null,
        a.name,
        h("button", { class: "chip-x", title: "Remover", onclick: () => { state.attachments.splice(i, 1); rerenderKeepingDraft(); } }, "✕"),
      ),
    ),
  );

  const action = state.busy
    ? h("button", { class: "send stop", title: "Parar", onclick: () => state.abort?.abort() }, "■")
    : h("button", { class: "send", title: "Enviar", onclick: () => send(input.value) }, "↑");

  return h(
    "footer",
    { class: "composer" },
    state.attachments.length ? chips : null,
    h(
      "div",
      { class: "composer-row" },
      fileInput,
      h("button", { class: "icon-btn", title: "Anexar ficheiros ou imagens", onclick: () => fileInput.click() }, "📎"),
      input,
      action,
    ),
    h(
      "div",
      { class: "composer-meta" },
      `${MODELS.find((m) => m.id === (state.current?.model ?? state.settings.model))?.label.split(" (")[0]}`,
      (state.current?.webSearch ?? state.settings.webSearch) ? " · 🔎 pesquisa web" : "",
      (state.current?.memoryEnabled ?? state.settings.memoryEnabled) ? " · 🧠 memória" : "",
    ),
  );
}

function autoGrow(el: HTMLTextAreaElement): void {
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 200) + "px";
}

function rerenderKeepingDraft(): void {
  const draft = (document.getElementById("prompt") as HTMLTextAreaElement | null)?.value ?? "";
  render();
  const input = document.getElementById("prompt") as HTMLTextAreaElement | null;
  if (input) {
    input.value = draft;
    autoGrow(input);
  }
}

function scrollToBottom(force = false): void {
  if (!messagesEl) return;
  const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 160;
  if (force || nearBottom) messagesEl.scrollTop = messagesEl.scrollHeight;
}

function updateStreaming(): void {
  if (rafPending) return;
  rafPending = true;
  requestAnimationFrame(() => {
    rafPending = false;
    if (!state.streaming || !messagesEl) return;
    const next = renderAssistantBlocks(state.streaming.content as unknown as AnyBlock[], true);
    if (streamingEl?.isConnected) streamingEl.replaceWith(next);
    else messagesEl.append(next);
    streamingEl = next;
    scrollToBottom();
  });
}

// ---------- actions ----------

async function refreshList(): Promise<void> {
  state.conversations = await listConversations();
}

function newConversation(): void {
  if (state.busy) return;
  state.current = null;
  state.attachments = [];
  state.notice = null;
  document.body.classList.remove("drawer-open");
  render();
}

function openConversation(id: string): void {
  if (state.busy) return;
  state.current = state.conversations.find((c) => c.id === id) ?? null;
  state.notice = null;
  document.body.classList.remove("drawer-open");
  render();
}

async function removeConversation(id: string): Promise<void> {
  if (state.busy || !confirm("Apagar esta conversa?")) return;
  await deleteConversation(id);
  if (state.current?.id === id) state.current = null;
  await refreshList();
  render();
}

function startConversation(firstText: string): Conversation {
  const s = state.settings;
  const p = persona(s.activePersonaId);
  return {
    id: newId(),
    title: conversationTitle(firstText || "Ficheiro"),
    createdAt: Date.now(),
    updatedAt: Date.now(),
    model: s.model,
    personaId: p.id,
    system: buildSystemPrompt(p, s.memoryEnabled, s.memoryEnabled ? loadMemories() : []),
    webSearch: s.webSearch,
    memoryEnabled: s.memoryEnabled,
    messages: [],
  };
}

async function send(rawText: string): Promise<void> {
  const text = rawText.trim();
  if (state.busy || (!text && !state.attachments.length)) return;
  if (!state.settings.apiKey) {
    openSettings();
    return;
  }

  const conv = state.current ?? startConversation(text);
  state.current = conv;
  const content: Anthropic.Beta.BetaContentBlockParam[] = [
    ...state.attachments.map((a) => a.block),
    ...(text ? [{ type: "text" as const, text }] : []),
  ];
  conv.messages.push({ role: "user", content });
  state.attachments = [];
  state.notice = null;
  state.busy = true;
  state.abort = new AbortController();
  await persist(conv);
  render();

  try {
    const outcome = await runTurn(
      conv,
      state.settings,
      {
        onSnapshot: (snapshot) => {
          state.streaming = snapshot;
          updateStreaming();
        },
        onHistoryChanged: async () => {
          state.streaming = null;
          await persist(conv);
          render();
        },
      },
      state.abort.signal,
    );
    if (outcome.kind === "refusal") {
      state.notice = {
        kind: "info",
        text: "O Claude não respondeu a este pedido." + (outcome.explanation ? ` (${outcome.explanation})` : ""),
      };
    } else if (outcome.kind === "max_tokens") {
      state.notice = { kind: "info", text: "A resposta foi cortada por ser demasiado longa." };
    } else if (outcome.kind === "aborted") {
      state.notice = { kind: "info", text: "Resposta interrompida." };
    }
  } catch (err) {
    state.notice = { kind: "error", text: friendlyError(err) };
  } finally {
    state.busy = false;
    state.abort = null;
    state.streaming = null;
    await persist(conv);
    render();
  }
}

async function persist(conv: Conversation): Promise<void> {
  conv.updatedAt = Date.now();
  try {
    await saveConversation(conv);
  } catch (err) {
    state.notice = { kind: "error", text: "Não foi possível guardar a conversa: " + friendlyError(err) };
  }
  await refreshList();
}

// ---------- modals ----------

function modal(title: string, body: HTMLElement, footer?: HTMLElement): { close: () => void } {
  const overlay = h("div", { class: "overlay" });
  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => e.target === overlay && close());
  overlay.append(
    h(
      "div",
      { class: "dialog", role: "dialog", "aria-label": title },
      h("div", { class: "dialog-head" }, h("h2", {}, title), h("button", { class: "icon-btn", title: "Fechar", onclick: close }, "✕")),
      h("div", { class: "dialog-body" }, body),
      footer ?? null,
    ),
  );
  document.body.append(overlay);
  return { close };
}

function openSettings(): void {
  const s = state.settings;
  const key = h("input", { type: "password", value: s.apiKey, placeholder: "sk-ant-…", autocomplete: "off" }) as HTMLInputElement;
  const model = h("select", {}, ...MODELS.map((m) => h("option", { value: m.id, selected: m.id === s.model }, m.label))) as HTMLSelectElement;
  const effortLabels: Record<Effort, string> = { low: "Baixo (rápido)", medium: "Médio", high: "Alto (pensa mais)" };
  const effort = h(
    "select",
    {},
    ...(Object.keys(effortLabels) as Effort[]).map((e) => h("option", { value: e, selected: e === s.effort }, effortLabels[e])),
  ) as HTMLSelectElement;
  const web = h("input", { type: "checkbox", checked: s.webSearch }) as HTMLInputElement;
  const mem = h("input", { type: "checkbox", checked: s.memoryEnabled }) as HTMLInputElement;

  const body = h(
    "div",
    { class: "form" },
    h("label", {}, "Chave da API da Anthropic", key),
    h(
      "p",
      { class: "hint" },
      "Crie a chave em ",
      h("a", { href: "https://console.anthropic.com/settings/keys", target: "_blank", rel: "noopener noreferrer" }, "console.anthropic.com"),
      ". Fica guardada só neste dispositivo e é enviada apenas para a API da Anthropic.",
    ),
    h("label", {}, "Modelo", model),
    h("label", {}, "Esforço de raciocínio", effort),
    h("label", { class: "check" }, web, "Pesquisa na internet"),
    h("label", { class: "check" }, mem, "Memória entre conversas"),
    h("p", { class: "hint" }, "O modelo e as opções aplicam-se às novas conversas."),
  );
  const save = h("button", { class: "btn primary" }, "Guardar");
  const { close } = modal("Definições", body, h("div", { class: "dialog-foot" }, save));
  save.addEventListener("click", () => {
    state.settings = {
      ...s,
      apiKey: key.value.trim(),
      model: model.value as ModelId,
      effort: effort.value as Effort,
      webSearch: web.checked,
      memoryEnabled: mem.checked,
    };
    saveSettings(state.settings);
    close();
    rerenderKeepingDraft();
  });
}

function openPersonas(): void {
  const body = h("div", { class: "form" });
  const draw = () => {
    body.replaceChildren();
    for (const p of state.personas) {
      const active = p.id === state.settings.activePersonaId;
      body.append(
        h(
          "div",
          { class: "row-item" + (active ? " active" : "") },
          h(
            "button",
            {
              class: "row-main",
              onclick: () => {
                state.settings.activePersonaId = p.id;
                saveSettings(state.settings);
                if (state.current && !state.current.messages.length) state.current = null;
                draw();
                if (!state.current) rerenderKeepingDraft();
              },
            },
            h("strong", {}, `${p.emoji} ${p.name}`),
            h("span", { class: "muted small" }, p.prompt),
          ),
          p.builtIn
            ? null
            : h(
                "button",
                {
                  class: "icon-btn small",
                  title: "Apagar persona",
                  onclick: () => {
                    state.personas = state.personas.filter((x) => x.id !== p.id);
                    saveCustomPersonas(state.personas);
                    if (state.settings.activePersonaId === p.id) state.settings.activePersonaId = "default";
                    saveSettings(state.settings);
                    draw();
                  },
                },
                "✕",
              ),
        ),
      );
    }
    const emoji = h("input", { value: "🤖", maxlength: "4", class: "emoji-input" }) as HTMLInputElement;
    const name = h("input", { placeholder: "Nome (ex.: Chef de cozinha)" }) as HTMLInputElement;
    const prompt = h("textarea", { rows: "3", placeholder: "Instruções: como deve comportar-se, tom, especialidade…" }) as HTMLTextAreaElement;
    const add = h("button", { class: "btn" }, "Criar persona");
    add.addEventListener("click", () => {
      if (!name.value.trim() || !prompt.value.trim()) return;
      state.personas.push({ id: newId(), name: name.value.trim(), emoji: emoji.value.trim() || "🤖", prompt: prompt.value.trim() });
      saveCustomPersonas(state.personas);
      draw();
    });
    body.append(h("h3", {}, "Nova persona"), h("div", { class: "inline" }, emoji, name), prompt, add);
  };
  draw();
  modal("Personas", body);
}

function openMemories(): void {
  const body = h("div", { class: "form" });
  const draw = () => {
    const memories = loadMemories();
    body.replaceChildren(
      h("p", { class: "hint" }, "O Claude guarda aqui factos sobre si quando lhe pedir ou quando forem úteis. As memórias entram nas novas conversas."),
    );
    if (!memories.length) body.append(h("p", { class: "muted" }, "Sem memórias guardadas."));
    for (const m of memories) {
      body.append(
        h(
          "div",
          { class: "row-item" },
          h("div", { class: "row-main" }, m.fact),
          h(
            "button",
            { class: "icon-btn small", title: "Apagar", onclick: () => { saveMemories(loadMemories().filter((x) => x.id !== m.id)); draw(); } },
            "✕",
          ),
        ),
      );
    }
    const fact = h("input", { placeholder: "Adicionar uma memória manualmente…" }) as HTMLInputElement;
    const add = h("button", { class: "btn" }, "Adicionar");
    add.addEventListener("click", () => {
      if (!fact.value.trim()) return;
      saveMemories([...loadMemories(), { id: newId().slice(0, 8), fact: fact.value.trim(), createdAt: Date.now() }]);
      draw();
    });
    body.append(h("div", { class: "inline" }, fact, add));
    if (memories.length) {
      body.append(
        h("button", { class: "btn ghost danger", onclick: () => { if (confirm("Apagar todas as memórias?")) { saveMemories([]); draw(); } } }, "Apagar todas"),
      );
    }
  };
  draw();
  modal("Memória", body);
}

// ---------- boot ----------

(async () => {
  try {
    await refreshList();
  } catch (err) {
    state.notice = { kind: "error", text: "Armazenamento indisponível: " + friendlyError(err) };
  }
  render();
  if (!state.settings.apiKey) openSettings();
})();
