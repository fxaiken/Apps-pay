import type Anthropic from "@anthropic-ai/sdk";

export type ModelId = "claude-opus-5-5" | "claude-sonnet-5-5" | "claude-haiku-5-5";
export type Effort = "low" | "medium" | "high";

export interface Settings {
  apiKey: string;
  model: ModelId;
  effort: Effort;
  webSearch: boolean;
  memoryEnabled: boolean;
  activePersonaId: string;
}

export interface Persona {
  id: string;
  name: string;
  emoji: string;
  prompt: string;
  builtIn?: boolean;
}

export interface Memory {
  id: string;
  fact: string;
  createdAt: number;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  model: ModelId;
  personaId: string;
  // Frozen when the conversation starts: rebuilding system or tools mid-conversation
  // would invalidate earlier thinking blocks and the prompt cache.
  system: string;
  webSearch: boolean;
  memoryEnabled: boolean;
  messages: Anthropic.Beta.BetaMessageParam[];
}

const SETTINGS_KEY = "cc.settings";
const PERSONAS_KEY = "cc.personas";
const MEMORIES_KEY = "cc.memories";

export const MODELS: { id: ModelId; label: string }[] = [
  { id: "claude-opus-5-5", label: "Claude Opus 5.5 (mais inteligente)" },
  { id: "claude-sonnet-5-5", label: "Claude Sonnet 5.5 (equilibrado)" },
  { id: "claude-haiku-5-5", label: "Claude Haiku 5.5 (rápido e barato)" },
];

export const BUILT_IN_PERSONAS: Persona[] = [
  {
    id: "default",
    name: "Assistente",
    emoji: "✨",
    prompt: "És um assistente útil, direto e simpático.",
    builtIn: true,
  },
  {
    id: "dev",
    name: "Programador",
    emoji: "💻",
    prompt:
      "És um engenheiro de software sénior. Dá respostas técnicas precisas, com exemplos de código completos e explica as decisões importantes.",
    builtIn: true,
  },
  {
    id: "tutor",
    name: "Professor",
    emoji: "🎓",
    prompt:
      "És um professor paciente. Explica passo a passo, com exemplos simples, e confirma se o aluno percebeu antes de avançar.",
    builtIn: true,
  },
  {
    id: "translator",
    name: "Tradutor",
    emoji: "🌍",
    prompt:
      "És um tradutor profissional. Traduz o texto que o utilizador enviar mantendo o tom e o sentido. Se a língua de destino não for indicada, traduz entre português e inglês.",
    builtIn: true,
  },
];

const DEFAULT_SETTINGS: Settings = {
  apiKey: "",
  model: "claude-opus-5-5",
  effort: "medium",
  webSearch: true,
  memoryEnabled: true,
  activePersonaId: "default",
};

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (err) {
    console.error("Falha ao guardar", key, err);
  }
}

export function newId(): string {
  return crypto.randomUUID();
}

export function loadSettings(): Settings {
  return { ...DEFAULT_SETTINGS, ...readJson<Partial<Settings>>(SETTINGS_KEY, {}) };
}

export function saveSettings(settings: Settings): void {
  writeJson(SETTINGS_KEY, settings);
}

export function loadPersonas(): Persona[] {
  return [...BUILT_IN_PERSONAS, ...readJson<Persona[]>(PERSONAS_KEY, [])];
}

export function saveCustomPersonas(personas: Persona[]): void {
  writeJson(PERSONAS_KEY, personas.filter((p) => !p.builtIn));
}

export function loadMemories(): Memory[] {
  return readJson<Memory[]>(MEMORIES_KEY, []);
}

export function saveMemories(memories: Memory[]): void {
  writeJson(MEMORIES_KEY, memories);
}

// Conversations live in IndexedDB: attachments (images, PDFs) quickly outgrow localStorage.
const DB_NAME = "claude-chat";
const STORE = "conversations";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "id" });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const req = run(db.transaction(STORE, mode).objectStore(STORE));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function listConversations(): Promise<Conversation[]> {
  const all = await tx<Conversation[]>("readonly", (s) => s.getAll() as IDBRequest<Conversation[]>);
  return all.sort((a, b) => b.updatedAt - a.updatedAt);
}

export function saveConversation(conv: Conversation): Promise<IDBValidKey> {
  return tx("readwrite", (s) => s.put(conv));
}

export function deleteConversation(id: string): Promise<undefined> {
  return tx("readwrite", (s) => s.delete(id));
}
