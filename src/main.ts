import "./style.css";
import type Anthropic from "@anthropic-ai/sdk";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { buildSystemPrompt, friendlyError, runTurn, supportsTurbo } from "./claude";
import { fileToBlock, type PendingAttachment } from "./attachments";
import { icon } from "./icons";
import { canDictate, canSpeak, dictate, speak, stopSpeaking, type Dictation } from "./speech";
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
type Child = Node | string | null | undefined | false;

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
  search: "",
  dictation: null as Dictation | null,
};

// ---------- helpers ----------

function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Record<string, string | boolean | ((e: Event) => void)> = {},
  ...children: Child[]
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

function iconButton(name: string, title: string, onclick: (e: Event) => void, cls = "icon-btn"): HTMLButtonElement {
  return h("button", { class: cls, title, "aria-label": title, onclick }, icon(name));
}

function haptic(): void {
  try {
    navigator.vibrate?.(8);
  } catch {
    /* not supported */
  }
}

function markdown(text: string): HTMLElement {
  const div = h("div", { class: "md" });
  div.innerHTML = DOMPurify.sanitize(marked.parse(text, { async: false }));
  for (const pre of div.querySelectorAll("pre")) {
    const code = pre.querySelector("code")?.textContent ?? "";
    const btn = h("button", { class: "copy-code" }, "Copiar");
    btn.addEventListener("click", () => copy(code, btn));
    pre.append(btn);
  }
  return div;
}

async function copy(text: string, btn: HTMLElement): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    haptic();
    btn.classList.add("done");
    setTimeout(() => btn.classList.remove("done"), 1500);
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

function isRealUserTurn(msg: BetaMessageParam): boolean {
  return msg.role === "user" && !blocksOf(msg).every((b) => b.type === "tool_result");
}

function textOf(msg: BetaMessageParam): string {
  return blocksOf(msg)
    .filter((b) => b.type === "text")
    .map((b) => String(b.text))
    .join("");
}

function conversationTitle(text: string): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > 48 ? clean.slice(0, 48) + "…" : clean || "Nova conversa";
}

function relativeDate(ts: number): string {
  const d = new Date(ts);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return d.toLocaleTimeString("pt-PT", { hour: "2-digit", minute: "2-digit" });
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (d.toDateString() === yesterday.toDateString()) return "Ontem";
  return d.toLocaleDateString("pt-PT", { day: "numeric", month: "short" });
}

function formatCost(usd: number): string {
  return usd < 0.01 ? "< $0,01" : "$" + usd.toFixed(2).replace(".", ",");
}

function conversationMarkdown(conv: Conversation): string {
  const lines = [`# ${conv.title}`, ""];
  for (const msg of conv.messages) {
    if (msg.role === "user" && !isRealUserTurn(msg)) continue;
    const text = textOf(msg).trim();
    if (!text) continue;
    lines.push(msg.role === "user" ? `**Eu:** ${text}` : `**Aura:** ${text}`, "");
  }
  return lines.join("\n");
}

// ---------- rendering ----------

const app = document.getElementById("app")!;
let messagesEl: HTMLElement;
let streamingEl: HTMLElement | null = null;
let rafPending = false;

function render(): void {
  document.body.classList.toggle("aura-active", state.busy);
  app.replaceChildren(
    h("div", { class: "aura-glow", "aria-hidden": "true" }),
    h("div", { class: "layout" }, renderSidebar(), renderMain()),
    h("div", { class: "scrim", onclick: closeDrawer }),
  );
  scrollToBottom(true);
}

/** Animated gradient orb (Siri-style); `size` in px. */
function orb(size: number, extraClass = ""): HTMLElement {
  return h(
    "span",
    { class: `orb ${extraClass}`, style: `--orb:${size}px`, "aria-hidden": "true" },
    h("span", { class: "orb-layer l1" }),
    h("span", { class: "orb-layer l2" }),
    h("span", { class: "orb-layer l3" }),
  );
}

/** What the assistant is doing right now, shown in the island at the top. */
function activityLabel(): string {
  const blocks = (state.streaming?.content ?? []) as unknown as AnyBlock[];
  const last = blocks[blocks.length - 1];
  if (!last) return "A pensar…";
  if (last.type === "server_tool_use") return last.name === "web_fetch" ? "A ler a página…" : "A pesquisar…";
  if (last.type === "web_search_tool_result" || last.type === "web_fetch_tool_result") return "A analisar…";
  if (last.type === "tool_use") return "A memorizar…";
  if (last.type === "thinking") return "A pensar…";
  return "A escrever…";
}

function closeDrawer(): void {
  document.body.classList.remove("drawer-open");
}

function conversationMatches(conv: Conversation, query: string): boolean {
  if (!query) return true;
  const q = query.toLowerCase();
  return conv.title.toLowerCase().includes(q) || conv.messages.some((m) => textOf(m).toLowerCase().includes(q));
}

function renderConversationList(): HTMLElement {
  const list = h("nav", { class: "conv-list" });
  const visible = state.conversations.filter((c) => conversationMatches(c, state.search));
  for (const conv of visible) {
    const lastText = [...conv.messages].reverse().map(textOf).find((t) => t.trim()) ?? "";
    list.append(
      h(
        "div",
        { class: "conv-item" + (conv.id === state.current?.id ? " active" : "") },
        h(
          "button",
          { class: "conv-open", onclick: () => openConversation(conv.id) },
          h("span", { class: "avatar" }, persona(conv.personaId).emoji),
          h(
            "span",
            { class: "conv-text" },
            h("span", { class: "conv-top" }, h("span", { class: "conv-title" }, conv.title), h("span", { class: "conv-date" }, relativeDate(conv.updatedAt))),
            h("span", { class: "conv-preview" }, lastText.replace(/[#*`>_]/g, "").slice(0, 90)),
          ),
        ),
        iconButton("trash", "Apagar conversa", () => removeConversation(conv.id), "icon-btn small delete"),
      ),
    );
  }
  if (!visible.length) {
    list.append(h("p", { class: "list-empty" }, state.search ? "Sem resultados." : "Ainda não há conversas."));
  }
  return list;
}

function renderSidebar(): HTMLElement {
  const search = h("input", { type: "search", placeholder: "Pesquisar", value: state.search, "aria-label": "Pesquisar conversas" }) as HTMLInputElement;
  search.addEventListener("input", () => {
    state.search = search.value;
    document.querySelector(".conv-list")?.replaceWith(renderConversationList());
  });

  return h(
    "aside",
    { class: "sidebar" },
    h(
      "div",
      { class: "sidebar-bar" },
      iconButton("gear", "Definições", openSettings),
      h("div", { class: "spacer" }),
      iconButton("compose", "Nova conversa", newConversation),
    ),
    h("h1", { class: "large-title" }, "Conversas"),
    h("label", { class: "search-field" }, icon("search", 17), search),
    renderConversationList(),
    h(
      "div",
      { class: "sidebar-footer" },
      h("button", { class: "footer-link", onclick: openPersonas }, icon("person", 20), "Personas"),
      h("button", { class: "footer-link", onclick: openMemories }, icon("brain", 20), "Memória"),
    ),
  );
}

function renderMain(): HTMLElement {
  const conv = state.current;
  const activePersona = persona(conv?.personaId ?? state.settings.activePersonaId);
  const model = conv?.model ?? state.settings.model;
  const turboOn = state.settings.turbo && supportsTurbo(model);

  const header = h(
    "header",
    { class: "navbar" },
    iconButton("sidebar", "Conversas", () => document.body.classList.add("drawer-open"), "icon-btn menu-btn"),
    h(
      "button",
      { class: "nav-center", onclick: conv?.messages.length ? openConversationInfo : openPersonas },
      h(
        "span",
        { class: "island" + (state.busy ? " active" : "") },
        orb(state.busy ? 22 : 18, state.busy ? "spinning" : ""),
        state.busy
          ? h("span", { class: "island-status", id: "island-status" }, activityLabel())
          : h("span", { class: "island-name" }, activePersona.id === "default" ? "Aura" : `${activePersona.emoji} ${activePersona.name}`),
      ),
      h(
        "span",
        { class: "nav-subtitle" },
        conv?.messages.length ? h("span", { class: "nav-title" }, conv.title) : null,
        h("span", {}, MODELS.find((m) => m.id === model)?.short ?? ""),
        turboOn ? h("span", { class: "turbo-badge" }, icon("bolt", 11), "Turbo") : null,
      ),
    ),
    conv?.messages.length
      ? iconButton("ellipsis", "Opções da conversa", openConversationInfo)
      : iconButton("compose", "Nova conversa", newConversation, "icon-btn compose-mobile"),
  );

  messagesEl = h("section", { class: "messages" });
  if (!conv || !conv.messages.length) {
    messagesEl.append(renderEmpty(activePersona));
  } else {
    const lastAssistantIndex = conv.messages.map((m) => m.role).lastIndexOf("assistant");
    conv.messages.forEach((msg, i) => {
      const el = renderMessage(msg, i === lastAssistantIndex && !state.busy);
      if (el) messagesEl.append(el);
    });
  }
  if (state.streaming) {
    streamingEl = renderAssistantBlocks(state.streaming.content as unknown as AnyBlock[], { live: true, last: false });
    messagesEl.append(streamingEl);
  } else if (state.busy) {
    streamingEl = h("div", { class: "msg assistant" }, h("div", { class: "bubble typing" }, h("span"), h("span"), h("span")));
    messagesEl.append(streamingEl);
  }
  if (state.notice) {
    messagesEl.append(h("div", { class: `notice ${state.notice.kind}` }, state.notice.text));
  }

  return h("main", { class: "main" }, header, messagesEl, renderComposer());
}

function renderEmpty(p: Persona): HTMLElement {
  const suggestions = [
    ["🗞️", "Notícias de hoje", "Quais são as principais notícias de hoje?"],
    ["💡", "Explica-me", "Explica-me como funciona a inflação, com exemplos simples"],
    ["✉️", "Escrever", "Ajuda-me a escrever um email profissional"],
    ["🧠", "Lembra-te", "Lembra-te de que prefiro respostas curtas"],
  ];
  return h(
    "div",
    { class: "empty" },
    orb(132, "hero"),
    h("h2", { class: "aura-title" }, p.id === "default" ? "Olá, sou a Aura" : `${p.emoji} ${p.name}`),
    h("p", { class: "secondary" }, "Pergunte o que quiser, por texto ou com uma foto."),
    h(
      "div",
      { class: "suggestions" },
      ...suggestions.map(([emoji, title, prompt]) =>
        h("button", { class: "suggestion", onclick: () => send(prompt) }, h("span", { class: "s-emoji" }, emoji), h("span", { class: "s-title" }, title), h("span", { class: "s-text" }, prompt)),
      ),
    ),
  );
}

function renderMessage(msg: BetaMessageParam, isLastAssistant: boolean): HTMLElement | null {
  const blocks = blocksOf(msg);
  if (msg.role === "assistant") return renderAssistantBlocks(blocks, { live: false, last: isLastAssistant });

  // User turns that only carry tool results are internal plumbing.
  if (!isRealUserTurn(msg)) return null;
  const files = h("div", { class: "attachments" });
  let text = "";
  for (const b of blocks) {
    if (b.type === "text") text += String(b.text);
    if (b.type === "image") {
      const src = b.source as { media_type: string; data: string };
      files.append(h("img", { class: "thumb", src: `data:${src.media_type};base64,${src.data}`, alt: "imagem" }));
    }
    if (b.type === "document") files.append(h("span", { class: "file-chip" }, icon("doc", 16), String(b.title ?? "documento")));
  }
  return h(
    "div",
    { class: "msg user" },
    files.childElementCount ? files : null,
    text ? h("div", { class: "bubble" }, text) : null,
  );
}

function renderAssistantBlocks(blocks: AnyBlock[], opts: { live: boolean; last: boolean }): HTMLElement {
  const wrap = h("div", { class: "msg assistant" + (opts.live ? " live" : "") });
  const bubble = h("div", { class: "bubble" });
  const sources = new Map<string, string>();
  let fullText = "";

  for (const b of blocks) {
    switch (b.type) {
      case "thinking": {
        const text = String(b.thinking ?? "").trim();
        if (text) {
          wrap.append(h("details", { class: "thinking" }, h("summary", {}, icon("sparkle", 14), opts.live ? "A pensar…" : "Raciocínio"), markdown(text)));
        }
        break;
      }
      case "text": {
        const text = String(b.text ?? "");
        fullText += text;
        bubble.append(markdown(text));
        for (const c of (b.citations as AnyBlock[] | null | undefined) ?? []) {
          if (typeof c.url === "string") sources.set(c.url, String(c.title ?? c.url));
        }
        break;
      }
      case "server_tool_use": {
        const input = b.input as { query?: string; url?: string } | undefined;
        if (b.name === "web_search") wrap.append(h("div", { class: "tool-note" }, icon("search", 14), `A pesquisar “${input?.query ?? "…"}”`));
        if (b.name === "web_fetch") wrap.append(h("div", { class: "tool-note" }, icon("globe", 14), `A ler ${input?.url ?? "página"}`));
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
        const input = b.input as { fact?: string } | undefined;
        if (b.name === "memory_save") wrap.append(h("div", { class: "tool-note" }, icon("brain", 14), `Memorizado: ${input?.fact ?? "…"}`));
        if (b.name === "memory_delete") wrap.append(h("div", { class: "tool-note" }, icon("brain", 14), "Memória apagada"));
        break;
      }
      case "fallback":
        wrap.append(h("div", { class: "tool-note" }, "↪︎ Continuado por outro modelo"));
        break;
    }
  }

  if (bubble.childElementCount) wrap.append(bubble);

  if (sources.size && !opts.live) {
    const chips = h("div", { class: "sources" });
    let i = 1;
    for (const [url, title] of [...sources].slice(0, 8)) {
      let host = url;
      try {
        host = new URL(url).hostname.replace(/^www\./, "");
      } catch {
        /* keep raw url */
      }
      chips.append(h("a", { class: "source", href: url, target: "_blank", rel: "noopener noreferrer", title }, h("span", { class: "source-n" }, String(i++)), host));
    }
    wrap.append(chips);
  }

  if (fullText && !opts.live) {
    const actions = h("div", { class: "msg-actions" });
    const copyBtn = iconButton("copy", "Copiar", () => copy(fullText, copyBtn), "action-btn");
    actions.append(copyBtn);
    if (canSpeak()) {
      const speakBtn = iconButton("speaker", "Ler em voz alta", () => speak(fullText, speakBtn), "action-btn");
      actions.append(speakBtn);
    }
    if (opts.last) actions.append(iconButton("retry", "Gerar outra resposta", regenerate, "action-btn"));
    wrap.append(actions);
  }
  return wrap;
}

function renderComposer(): HTMLElement {
  const input = h("textarea", {
    id: "prompt",
    rows: "1",
    placeholder: state.settings.apiKey ? "Pergunte à Aura" : "Configure a chave de API",
    "aria-label": "Mensagem",
  }) as HTMLTextAreaElement;

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
        a.preview ? h("img", { src: a.preview, alt: "" }) : icon("doc", 16),
        h("span", { class: "chip-name" }, a.name),
        h("button", { class: "chip-x", "aria-label": "Remover", onclick: () => { state.attachments.splice(i, 1); rerenderKeepingDraft(); } }, icon("close", 14)),
      ),
    ),
  );

  const sendBtn = iconButton("arrowUp", "Enviar", () => send(input.value), "send");
  const micBtn = canDictate() ? iconButton("mic", "Ditar", () => toggleDictation(input), "icon-btn mic" + (state.dictation ? " recording" : "")) : null;
  const stopBtn = iconButton("stop", "Parar", () => state.abort?.abort(), "send stop");

  const syncButtons = () => {
    const hasContent = input.value.trim().length > 0 || state.attachments.length > 0;
    sendBtn.hidden = state.busy || (!hasContent && !!micBtn);
    if (micBtn) micBtn.hidden = state.busy || hasContent;
    stopBtn.hidden = !state.busy;
  };
  input.addEventListener("input", () => {
    autoGrow(input);
    syncButtons();
  });
  input.addEventListener("keydown", (e) => {
    const isTouch = matchMedia("(pointer: coarse)").matches;
    if (e.key === "Enter" && !e.shiftKey && !isTouch) {
      e.preventDefault();
      send(input.value);
    }
  });
  syncButtons();

  const conv = state.current;
  const meta: Child[] = [];
  if (conv?.usage) meta.push(`${formatCost(conv.usage.costUsd)} nesta conversa`);

  return h(
    "footer",
    { class: "composer" },
    state.attachments.length ? chips : null,
    h(
      "div",
      { class: "composer-row" },
      fileInput,
      iconButton("plus", "Anexar fotos ou ficheiros", () => fileInput.click(), "attach-btn"),
      h("div", { class: "field" }, input, micBtn, sendBtn, stopBtn),
    ),
    meta.length ? h("div", { class: "composer-meta" }, ...meta) : null,
  );
}

function toggleDictation(input: HTMLTextAreaElement): void {
  if (state.dictation) {
    state.dictation.stop();
    return;
  }
  const base = input.value ? input.value.replace(/\s*$/, " ") : "";
  state.dictation = dictate(
    (text) => {
      input.value = base + text;
      autoGrow(input);
    },
    () => {
      state.dictation = null;
      rerenderKeepingDraft();
    },
  );
  haptic();
  rerenderKeepingDraft();
}

function autoGrow(el: HTMLTextAreaElement): void {
  el.style.height = "auto";
  el.style.height = Math.min(el.scrollHeight, 180) + "px";
}

function rerenderKeepingDraft(): void {
  const draft = (document.getElementById("prompt") as HTMLTextAreaElement | null)?.value ?? "";
  render();
  const input = document.getElementById("prompt") as HTMLTextAreaElement | null;
  if (input) {
    input.value = draft;
    autoGrow(input);
    input.dispatchEvent(new Event("input"));
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
    const status = document.getElementById("island-status");
    if (status) status.textContent = activityLabel();
    if (!state.streaming || !messagesEl) return;
    const next = renderAssistantBlocks(state.streaming.content as unknown as AnyBlock[], { live: true, last: false });
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
  stopSpeaking();
  state.current = null;
  state.attachments = [];
  state.notice = null;
  closeDrawer();
  render();
  (document.getElementById("prompt") as HTMLTextAreaElement | null)?.focus();
}

function openConversation(id: string): void {
  if (state.busy) return;
  stopSpeaking();
  state.current = state.conversations.find((c) => c.id === id) ?? null;
  state.notice = null;
  closeDrawer();
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
    webFetch: s.webSearch,
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
  state.dictation?.stop();
  haptic();

  const conv = state.current ?? startConversation(text);
  state.current = conv;
  const content: Anthropic.Beta.BetaContentBlockParam[] = [
    ...state.attachments.map((a) => a.block),
    ...(text ? [{ type: "text" as const, text }] : []),
  ];
  conv.messages.push({ role: "user", content });
  state.attachments = [];
  await respond(conv);
}

// Removing only trailing turns keeps every earlier thinking block valid.
async function regenerate(): Promise<void> {
  const conv = state.current;
  if (!conv || state.busy) return;
  let lastUser = conv.messages.length - 1;
  while (lastUser >= 0 && !isRealUserTurn(conv.messages[lastUser])) lastUser--;
  if (lastUser < 0) return;
  conv.messages.splice(lastUser + 1);
  haptic();
  await respond(conv);
}

async function respond(conv: Conversation): Promise<void> {
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
        onInfo: (text) => {
          state.notice = { kind: "info", text };
        },
      },
      state.abort.signal,
    );
    if (outcome.kind === "refusal") {
      state.notice = {
        kind: "info",
        text: "A Aura não respondeu a este pedido." + (outcome.explanation ? ` (${outcome.explanation})` : ""),
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

async function shareConversation(conv: Conversation): Promise<void> {
  const text = conversationMarkdown(conv);
  try {
    if (navigator.share) {
      await navigator.share({ title: conv.title, text });
      return;
    }
  } catch {
    /* cancelled or unsupported: fall back to a download */
  }
  const url = URL.createObjectURL(new Blob([text], { type: "text/markdown" }));
  const a = h("a", { href: url, download: `${conv.title.replace(/[^\p{L}\p{N} ]/gu, "").trim() || "conversa"}.md` });
  a.click();
  URL.revokeObjectURL(url);
}

// ---------- sheets (iOS-style modals) ----------

function sheet(title: string, body: HTMLElement, action?: { label: string; onClick: () => void }): { close: () => void } {
  const overlay = h("div", { class: "overlay" });
  const close = () => {
    overlay.classList.add("closing");
    setTimeout(() => overlay.remove(), 200);
  };
  overlay.addEventListener("click", (e) => e.target === overlay && close());
  const actionBtn = action ? h("button", { class: "sheet-action", onclick: () => action.onClick() }, action.label) : h("span");
  overlay.append(
    h(
      "div",
      { class: "sheet", role: "dialog", "aria-label": title },
      h("div", { class: "grabber" }),
      h("div", { class: "sheet-head" }, h("button", { class: "sheet-cancel", onclick: close }, action ? "Cancelar" : "Fechar"), h("h2", {}, title), actionBtn),
      h("div", { class: "sheet-body" }, body),
    ),
  );
  document.body.append(overlay);
  return { close };
}

function group(header: string | null, rows: HTMLElement[], footer?: Child): HTMLElement {
  return h(
    "section",
    { class: "group" },
    header ? h("div", { class: "group-header" }, header) : null,
    h("div", { class: "group-body" }, ...rows),
    footer ? h("div", { class: "group-footer" }, footer) : null,
  );
}

function switchRow(label: Child, checked: boolean, iconName: string, color: string): { row: HTMLElement; input: HTMLInputElement } {
  const input = h("input", { type: "checkbox", class: "switch", checked, role: "switch" }) as HTMLInputElement;
  const row = h("label", { class: "row" }, h("span", { class: "row-icon", style: `background:${color}` }, icon(iconName, 17)), h("span", { class: "row-label" }, label), input);
  return { row, input };
}

function segmented<T extends string>(options: { value: T; label: string }[], current: T): { el: HTMLElement; value: () => T } {
  let value = current;
  const el = h("div", { class: "segmented", role: "radiogroup" });
  const draw = () => {
    el.replaceChildren(
      ...options.map((o) =>
        h("button", { class: "segment" + (o.value === value ? " selected" : ""), role: "radio", "aria-checked": String(o.value === value), onclick: () => { value = o.value; draw(); } }, o.label),
      ),
    );
  };
  draw();
  return { el, value: () => value };
}

function openSettings(): void {
  const s = state.settings;
  const key = h("input", { type: "password", value: s.apiKey, placeholder: "sk-ant-…", autocomplete: "off", class: "row-input" }) as HTMLInputElement;
  const model = segmented(MODELS.map((m) => ({ value: m.id, label: m.short })), s.model);
  const effort = segmented<Effort>(
    [
      { value: "low", label: "Rápido" },
      { value: "medium", label: "Normal" },
      { value: "high", label: "Profundo" },
    ],
    s.effort,
  );
  const turbo = switchRow(h("span", {}, "Modo Turbo", h("small", {}, "Respostas até 2,5× mais rápidas · só Opus · custa 2×")), s.turbo, "bolt", "#FF9500");
  const web = switchRow(h("span", {}, "Internet", h("small", {}, "Pesquisa e leitura de links")), s.webSearch, "globe", "#007AFF");
  const mem = switchRow(h("span", {}, "Memória", h("small", {}, "Lembrar-se de si entre conversas")), s.memoryEnabled, "brain", "#AF52DE");

  const body = h(
    "div",
    { class: "settings" },
    group(
      "Chave da API",
      [h("div", { class: "row" }, key)],
      h("span", {}, "Crie-a em ", h("a", { href: "https://console.anthropic.com/settings/keys", target: "_blank", rel: "noopener noreferrer" }, "console.anthropic.com"), ". Fica só neste dispositivo."),
    ),
    group("Modelo", [h("div", { class: "row padded" }, model.el)], "Opus é o mais inteligente; Haiku o mais barato."),
    group("Raciocínio", [h("div", { class: "row padded" }, effort.el)]),
    group("Funcionalidades", [turbo.row, web.row, mem.row], "Aplicam-se às novas conversas. O Modo Turbo vale para todas."),
  );
  const { close } = sheet("Definições", body, {
    label: "OK",
    onClick: () => {
      state.settings = {
        ...s,
        apiKey: key.value.trim(),
        model: model.value() as ModelId,
        effort: effort.value(),
        turbo: turbo.input.checked,
        webSearch: web.input.checked,
        memoryEnabled: mem.input.checked,
      };
      saveSettings(state.settings);
      close();
      rerenderKeepingDraft();
    },
  });
}

function openConversationInfo(): void {
  const conv = state.current;
  if (!conv) return;
  const u = conv.usage;
  const rows = [
    h("div", { class: "row" }, h("span", { class: "row-label" }, "Modelo"), h("span", { class: "row-value" }, MODELS.find((m) => m.id === conv.model)?.short ?? conv.model)),
    h("div", { class: "row" }, h("span", { class: "row-label" }, "Persona"), h("span", { class: "row-value" }, `${persona(conv.personaId).emoji} ${persona(conv.personaId).name}`)),
    h("div", { class: "row" }, h("span", { class: "row-label" }, "Custo estimado"), h("span", { class: "row-value" }, u ? formatCost(u.costUsd) : "—")),
    h("div", { class: "row" }, h("span", { class: "row-label" }, "Tokens"), h("span", { class: "row-value" }, u ? `${(u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens).toLocaleString("pt-PT")} ↑ · ${u.outputTokens.toLocaleString("pt-PT")} ↓` : "—")),
    h("div", { class: "row" }, h("span", { class: "row-label" }, "Pesquisas web"), h("span", { class: "row-value" }, String(u?.webSearches ?? 0))),
  ];
  const actions = [
    h("button", { class: "row button-row", onclick: () => shareConversation(conv) }, h("span", { class: "row-icon", style: "background:#34C759" }, icon("share", 17)), h("span", { class: "row-label" }, "Partilhar / exportar")),
    h(
      "button",
      { class: "row button-row danger", onclick: async () => { close(); await removeConversation(conv.id); } },
      h("span", { class: "row-icon", style: "background:#FF3B30" }, icon("trash", 17)),
      h("span", { class: "row-label" }, "Apagar conversa"),
    ),
  ];
  const { close } = sheet(conv.title, h("div", { class: "settings" }, group("Detalhes", rows, "Custo calculado a partir dos tokens usados (preços da API)."), group(null, actions)));
}

function openPersonas(): void {
  const body = h("div", { class: "settings" });
  const draw = () => {
    const rows = state.personas.map((p) => {
      const active = p.id === state.settings.activePersonaId;
      return h(
        "div",
        { class: "row persona-row" },
        h(
          "button",
          {
            class: "persona-main",
            onclick: () => {
              state.settings.activePersonaId = p.id;
              saveSettings(state.settings);
              haptic();
              if (state.current && !state.current.messages.length) state.current = null;
              draw();
              if (!state.current) rerenderKeepingDraft();
            },
          },
          h("span", { class: "avatar" }, p.emoji),
          h("span", { class: "persona-text" }, h("span", { class: "persona-name" }, p.name), h("span", { class: "persona-prompt" }, p.prompt)),
          active ? h("span", { class: "check" }, "✓") : null,
        ),
        p.builtIn
          ? null
          : iconButton("trash", "Apagar persona", () => {
              state.personas = state.personas.filter((x) => x.id !== p.id);
              saveCustomPersonas(state.personas);
              if (state.settings.activePersonaId === p.id) state.settings.activePersonaId = "default";
              saveSettings(state.settings);
              draw();
            }, "icon-btn small delete"),
      );
    });
    const emoji = h("input", { value: "🤖", maxlength: "4", class: "row-input emoji-input", "aria-label": "Emoji" }) as HTMLInputElement;
    const name = h("input", { placeholder: "Nome (ex.: Chef de cozinha)", class: "row-input" }) as HTMLInputElement;
    const prompt = h("textarea", { rows: "3", placeholder: "Como deve comportar-se: tom, especialidade, regras…", class: "row-input" }) as HTMLTextAreaElement;
    const add = h("button", { class: "row button-row accent" }, h("span", { class: "row-label" }, "Criar persona"));
    add.addEventListener("click", () => {
      if (!name.value.trim() || !prompt.value.trim()) return;
      state.personas.push({ id: newId(), name: name.value.trim(), emoji: emoji.value.trim() || "🤖", prompt: prompt.value.trim() });
      saveCustomPersonas(state.personas);
      haptic();
      draw();
    });
    body.replaceChildren(
      group("Escolha com quem falar", rows, "A persona aplica-se às novas conversas."),
      group("Nova persona", [h("div", { class: "row" }, emoji, name), h("div", { class: "row" }, prompt), add]),
    );
  };
  draw();
  sheet("Personas", body);
}

function openMemories(): void {
  const body = h("div", { class: "settings" });
  const draw = () => {
    const memories = loadMemories();
    const rows = memories.length
      ? memories.map((m) =>
          h(
            "div",
            { class: "row" },
            h("span", { class: "row-label wrap" }, m.fact),
            iconButton("trash", "Apagar", () => { saveMemories(loadMemories().filter((x) => x.id !== m.id)); draw(); }, "icon-btn small delete"),
          ),
        )
      : [h("div", { class: "row" }, h("span", { class: "row-label secondary" }, "Sem memórias guardadas."))];
    const fact = h("input", { placeholder: "Adicionar uma memória…", class: "row-input" }) as HTMLInputElement;
    const add = h("button", { class: "row button-row accent" }, h("span", { class: "row-label" }, "Adicionar"));
    add.addEventListener("click", () => {
      if (!fact.value.trim()) return;
      saveMemories([...loadMemories(), { id: newId().slice(0, 8), fact: fact.value.trim(), createdAt: Date.now() }]);
      draw();
    });
    const groups = [
      group("O que a Aura sabe sobre si", rows, "A Aura guarda factos quando lhe pedir ou quando forem úteis. Entram nas novas conversas."),
      group(null, [h("div", { class: "row" }, fact), add]),
    ];
    if (memories.length) {
      groups.push(
        group(null, [
          h("button", { class: "row button-row danger", onclick: () => { if (confirm("Apagar todas as memórias?")) { saveMemories([]); draw(); } } }, h("span", { class: "row-label" }, "Apagar todas")),
        ]),
      );
    }
    body.replaceChildren(...groups);
  };
  draw();
  sheet("Memória", body);
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
